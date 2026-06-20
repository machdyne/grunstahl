package com.grunstahl.fram;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Arrays;

/**
 * GrunstahlHid — USB HID feature report protocol for Grünstahl FRAM device.
 *
 * HID feature reports travel over EP0 (the control endpoint) using
 * standard USB class requests:
 *   SET_REPORT (0x09) — host → device  (our "request")
 *   GET_REPORT (0x01) — device → host  (our "response")
 *
 * UsbDeviceConnection.controlTransfer() maps directly to USB control
 * transfers — no kernel HID driver claiming is required. The interface
 * is claimed here to prevent other drivers from interfering, then
 * released on close.
 *
 * Request layout (255 bytes, first byte = report ID 0xAA):
 *   [0]   0xAA  report ID
 *   [1]   0x00  direction: request
 *   [2]   cmd   GS_CMD_*
 *   [3]   addr_lo
 *   [4]   addr_hi
 *   [5]   len   payload byte count (max 248)
 *   [6..] data  write payload
 *
 * Response (255 bytes, first byte = report ID 0xAA):
 *   [0]   0xAA  report ID
 *   [1]   0x01  direction: response
 *   [2]   0x01  status (0x00 = error)
 *   [3..] data  read payload
 */
public class GrunstahlHid implements AutoCloseable {

    public static final int USB_VID = 0x1209;
    public static final int USB_PID = 0xD003;

    public static final int FRAM_SIZE   = 2048;
    public static final int MAX_PAYLOAD = 248;

    private static final int REPORT_ID  = 0xAA;
    private static final int REPORT_LEN = 255;

    private static final int CMD_PING  = 0x00;
    private static final int CMD_READ  = 0x01;
    private static final int CMD_WRITE = 0x02;

    /* USB HID class request constants */
    private static final int HID_GET_REPORT = 0x01;
    private static final int HID_SET_REPORT = 0x09;
    private static final int HID_REPORT_TYPE_FEATURE = 0x03;

    /* bmRequestType for SET_REPORT: host→device, class, interface */
    private static final int RT_SET = 0x21;
    /* bmRequestType for GET_REPORT: device→host, class, interface */
    private static final int RT_GET = 0xA1;

    private static final int TIMEOUT_MS = 2000;
    private static final int POLL_MS    = 10;
    private static final int POLL_MAX   = 200;

    private final UsbDeviceConnection connection;
    private final UsbInterface         usbInterface;

    public GrunstahlHid(UsbManager manager, UsbDevice device) throws IOException {
        /* Find the HID interface (class 3) */
        UsbInterface iface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                iface = device.getInterface(i);
                break;
            }
        }
        if (iface == null) throw new IOException("No HID interface found");

        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) throw new IOException("Failed to open device");

        if (!conn.claimInterface(iface, true)) {
            conn.close();
            throw new IOException("Failed to claim HID interface");
        }

        this.connection  = conn;
        this.usbInterface = iface;
    }

    /** Send a SET_REPORT (feature) control transfer — request to device. */
    private void setReport(byte[] data) throws IOException {
        int wValue = (HID_REPORT_TYPE_FEATURE << 8) | REPORT_ID;
        int r = connection.controlTransfer(
                RT_SET,
                HID_SET_REPORT,
                wValue,
                usbInterface.getId(),
                data,
                data.length,
                TIMEOUT_MS);
        if (r < 0) throw new IOException("SET_REPORT failed: " + r);
    }

    /** Send a GET_REPORT (feature) control transfer — read response from device. */
    private byte[] getReport() throws IOException {
        byte[] buf = new byte[REPORT_LEN];
        int wValue = (HID_REPORT_TYPE_FEATURE << 8) | REPORT_ID;
        int r = connection.controlTransfer(
                RT_GET,
                HID_GET_REPORT,
                wValue,
                usbInterface.getId(),
                buf,
                buf.length,
                TIMEOUT_MS);
        if (r < 0) throw new IOException("GET_REPORT failed: " + r);
        return buf;
    }

    /**
     * RPC: send request, poll until response ready.
     * buf must be REPORT_LEN (255) bytes; [0] is set to the report ID here.
     */
    private byte[] rpc(byte[] req) throws IOException {
        req[0] = (byte) REPORT_ID;
        req[1] = 0x00; // direction: request
        setReport(req);

        // Poll until device sets [1] = 0x01
        for (int i = 0; i < POLL_MAX; i++) {
            byte[] resp = getReport();
            if ((resp[0] & 0xFF) == REPORT_ID && (resp[1] & 0xFF) == 0x01) {
                if ((resp[2] & 0xFF) != 0x01) {
                    throw new IOException("Device returned error for cmd 0x"
                            + Integer.toHexString(req[2] & 0xFF));
                }
                return resp;
            }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
        }
        throw new IOException("Device did not respond in time");
    }

    /** Build a zeroed request buffer. */
    private byte[] newReq(int cmd, int addr, int len) {
        byte[] req = new byte[REPORT_LEN];
        req[2] = (byte) cmd;
        req[3] = (byte) (addr & 0xFF);
        req[4] = (byte) ((addr >> 8) & 0xFF);
        req[5] = (byte) len;
        return req;
    }

    /** Ping — confirm device is alive. */
    public void ping() throws IOException {
        rpc(newReq(CMD_PING, 0, 0));
    }

    /**
     * Read 'len' bytes from FRAM at 'addr' into 'dest' at 'destOff'.
     * Handles chunking internally.
     *
     * @param addr    FRAM start address (0..2047)
     * @param dest    destination buffer
     * @param destOff offset into dest
     * @param len     number of bytes to read
     * @param cb      optional progress callback (0.0–1.0), may be null
     */
    public void read(int addr, byte[] dest, int destOff, int len, ProgressCallback cb)
            throws IOException {
        int done = 0;
        while (done < len) {
            int chunk = Math.min(MAX_PAYLOAD, len - done);
            byte[] req  = newReq(CMD_READ, addr + done, chunk);
            byte[] resp = rpc(req);
            System.arraycopy(resp, 3, dest, destOff + done, chunk);
            done += chunk;
            if (cb != null) cb.onProgress((float) done / len);
        }
    }

    /**
     * Write 'len' bytes from 'src' at 'srcOff' to FRAM at 'addr'.
     * Handles chunking internally.
     */
    public void write(int addr, byte[] src, int srcOff, int len, ProgressCallback cb)
            throws IOException {
        int done = 0;
        while (done < len) {
            int chunk = Math.min(MAX_PAYLOAD, len - done);
            byte[] req = newReq(CMD_WRITE, addr + done, chunk);
            System.arraycopy(src, srcOff + done, req, 6, chunk);
            rpc(req);
            done += chunk;
            if (cb != null) cb.onProgress((float) done / len);
        }
    }

    /** Convenience: read full 2 KB FRAM. */
    public byte[] readAll(ProgressCallback cb) throws IOException {
        byte[] buf = new byte[FRAM_SIZE];
        read(0, buf, 0, FRAM_SIZE, cb);
        return buf;
    }

    /** Convenience: write full 2 KB FRAM. */
    public void writeAll(byte[] data, ProgressCallback cb) throws IOException {
        if (data.length != FRAM_SIZE)
            throw new IOException("Data must be exactly " + FRAM_SIZE + " bytes");
        write(0, data, 0, FRAM_SIZE, cb);
    }

    @Override
    public void close() {
        try {
            connection.releaseInterface(usbInterface);
        } catch (Exception ignored) {}
        try {
            connection.close();
        } catch (Exception ignored) {}
    }

    public interface ProgressCallback {
        void onProgress(float fraction);
    }
}
