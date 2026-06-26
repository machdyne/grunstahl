/*
 * hidxfer — Grünstahl FRAM transfer utility
 *
 * Reads and writes data to the 2 KB F-RAM on a Grünstahl device
 * via USB HID feature reports.
 *
 * Usage:
 *   hidxfer -r output.bin [--offset N] [--bytes N]
 *   hidxfer -w input.bin  [--offset N]
 *   hidxfer --ping
 *
 * Protocol:
 *   255-byte HID feature report (report ID 0xaa).
 *   Request:
 *     [0]    0xaa  magic
 *     [1]    0x00  request marker
 *     [2]    cmd   GS_CMD_PING / GS_CMD_READ / GS_CMD_WRITE
 *     [3..4] addr  16-bit FRAM address, little-endian
 *     [5]    len   payload byte count (max 248)
 *     [6..]  data  write payload
 *   Response (polled until [1] == 0x01):
 *     [0]    0xaa  magic
 *     [1]    0x01  response marker
 *     [2]    0x01  status (0=error, 1=ok)
 *     [3..]  data  read payload
 *
 * Build:
 *   gcc -Wall -DBACKEND_HIDAPI -o hidxfer hidxfer.c hidapi.c -ludev
 *
 * Windows: link against setupapi and hid instead of udev.
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <getopt.h>
#include <errno.h>

#include "hidapi.h"

/* ── Device identity ────────────────────────────────────────────────────── */

#define USB_VID  0x16c0
#define USB_PID  0x05df

/* ── Protocol constants ─────────────────────────────────────────────────── */

#define GS_CMD_PING   0x00
#define GS_CMD_READ   0x01
#define GS_CMD_WRITE  0x02

#define GS_REPORT_LEN  255
#define GS_MAX_PAYLOAD 248   /* 255 - 7 header bytes */
#define FRAM_SIZE      2048

/* ── HID handle ─────────────────────────────────────────────────────────── */

static hid_device *hd = NULL;

/* ── Low-level RPC: send request, poll until response ───────────────────── */

static int gs_rpc(uint8_t *buf)
{
    buf[0] = 0xaa;
    buf[1] = 0x00;

    int r = hid_send_feature_report(hd, buf, GS_REPORT_LEN);
    if (r != GS_REPORT_LEN) {
        fprintf(stderr, "hidxfer: send failed (r=%d): %ls\n",
                r, hid_error(hd));
        return -1;
    }

    /* Poll until device sets [1] = 0x01 (response ready) */
    do {
        r = hid_get_feature_report(hd, buf, GS_REPORT_LEN);
        if (r != GS_REPORT_LEN || buf[0] != 0xaa) {
            fprintf(stderr, "hidxfer: recv failed (r=%d): %ls\n",
                    r, hid_error(hd));
            return -1;
        }
    } while (buf[1] != 0x01);

    if (buf[2] != 0x01) {
        fprintf(stderr, "hidxfer: device returned error for cmd 0x%02x\n",
                buf[2]);
        return -1;
    }

    return 0;
}

/* ── PING ───────────────────────────────────────────────────────────────── */

static int gs_ping(void)
{
    uint8_t buf[GS_REPORT_LEN];
    memset(buf, 0, sizeof(buf));
    buf[2] = GS_CMD_PING;
    return gs_rpc(buf);
}

/* ── READ: read 'total' bytes from FRAM at 'offset' into 'out' ──────────── */

static int gs_read(uint16_t offset, uint8_t *out, uint16_t total)
{
    uint8_t buf[GS_REPORT_LEN];
    uint16_t done = 0;

    while (done < total) {
        uint16_t chunk = total - done;
        if (chunk > GS_MAX_PAYLOAD) chunk = GS_MAX_PAYLOAD;

        memset(buf, 0, sizeof(buf));
        buf[2] = GS_CMD_READ;
        buf[3] = (uint8_t)((offset + done) & 0xFF);
        buf[4] = (uint8_t)((offset + done) >> 8);
        buf[5] = (uint8_t)chunk;

        if (gs_rpc(buf) != 0) return -1;

        memcpy(out + done, &buf[3], chunk);
        done += chunk;
    }

    return 0;
}

/* ── WRITE: write 'total' bytes from 'in' to FRAM at 'offset' ──────────── */

static int gs_write(uint16_t offset, const uint8_t *in, uint16_t total)
{
    uint8_t buf[GS_REPORT_LEN];
    uint16_t done = 0;

    while (done < total) {
        uint16_t chunk = total - done;
        if (chunk > GS_MAX_PAYLOAD) chunk = GS_MAX_PAYLOAD;

        memset(buf, 0, sizeof(buf));
        buf[2] = GS_CMD_WRITE;
        buf[3] = (uint8_t)((offset + done) & 0xFF);
        buf[4] = (uint8_t)((offset + done) >> 8);
        buf[5] = (uint8_t)chunk;
        memcpy(&buf[6], in + done, chunk);

        if (gs_rpc(buf) != 0) return -1;

        done += chunk;
        fprintf(stderr, "\r  %u / %u bytes", done, total);
        fflush(stderr);
    }

    fprintf(stderr, "\n");
    return 0;
}

/* ── Usage ──────────────────────────────────────────────────────────────── */

static void usage(const char *prog)
{
    fprintf(stderr,
        "Usage:\n"
        "  %s -r FILE [--offset N] [--bytes N]   read from FRAM to FILE\n"
        "  %s -w FILE [--offset N]               write FILE to FRAM\n"
        "  %s --ping                              ping device\n"
        "\n"
        "Options:\n"
        "  -r FILE     read mode: save FRAM data to FILE\n"
        "  -w FILE     write mode: load FILE and write to FRAM\n"
        "  --offset N  FRAM byte offset (default: 0)\n"
        "  --bytes N   number of bytes to read (default: remaining from offset)\n"
        "  --ping      send a ping and confirm device is responding\n"
        "\n"
        "Examples:\n"
        "  %s -r backup.bin --bytes 2048\n"
        "  %s -w data.txt\n"
        "  %s -w data.dat --offset 512\n",
        prog, prog, prog, prog, prog, prog);
}

/* ── Main ───────────────────────────────────────────────────────────────── */

int main(int argc, char *argv[])
{
    const char *read_file  = NULL;
    const char *write_file = NULL;
    int do_ping   = 0;
    int offset    = 0;
    int bytes_req = -1;   /* -1 = all remaining */

    /* ── Argument parsing ───────────────────────────────────────────────── */

    static const struct option long_opts[] = {
        { "offset", required_argument, NULL, 'O' },
        { "bytes",  required_argument, NULL, 'B' },
        { "ping",   no_argument,       NULL, 'P' },
        { "help",   no_argument,       NULL, 'h' },
        { NULL, 0, NULL, 0 }
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "r:w:h", long_opts, NULL)) != -1) {
        switch (opt) {
            case 'r': read_file  = optarg; break;
            case 'w': write_file = optarg; break;
            case 'O': offset    = atoi(optarg); break;
            case 'B': bytes_req = atoi(optarg); break;
            case 'P': do_ping   = 1; break;
            case 'h': usage(argv[0]); return 0;
            default:  usage(argv[0]); return 1;
        }
    }

    if (!read_file && !write_file && !do_ping) {
        usage(argv[0]);
        return 1;
    }

    if (offset < 0 || offset >= FRAM_SIZE) {
        fprintf(stderr, "hidxfer: offset %d out of range (0..%d)\n",
                offset, FRAM_SIZE - 1);
        return 1;
    }

    /* ── Open device ────────────────────────────────────────────────────── */

    if (hid_init() != 0) {
        fprintf(stderr, "hidxfer: hid_init failed\n");
        return 1;
    }

    hd = hid_open(USB_VID, USB_PID, NULL);
    if (!hd) {
        fprintf(stderr,
            "hidxfer: device not found (VID=0x%04x PID=0x%04x)\n"
            "         Is Grunstahl plugged in?\n",
            USB_VID, USB_PID);
        hid_exit();
        return 1;
    }

    hid_set_nonblocking(hd, 0);

    int ret = 0;

    /* ── Ping ───────────────────────────────────────────────────────────── */

    if (do_ping) {
        if (gs_ping() == 0)
            printf("pong — device is responding\n");
        else {
            fprintf(stderr, "hidxfer: ping failed\n");
            ret = 1;
        }
        goto done;
    }

    /* ── Write mode ─────────────────────────────────────────────────────── */

    if (write_file) {
        FILE *f = fopen(write_file, "rb");
        if (!f) {
            perror(write_file);
            ret = 1;
            goto done;
        }

        fseek(f, 0, SEEK_END);
        long fsize = ftell(f);
        rewind(f);

        if (fsize <= 0) {
            fprintf(stderr, "hidxfer: empty file\n");
            fclose(f);
            ret = 1;
            goto done;
        }

        /* Clamp to available FRAM space from offset */
        long available = FRAM_SIZE - offset;
        long to_write  = fsize < available ? fsize : available;

        if (to_write < fsize) {
            fprintf(stderr,
                "hidxfer: warning: file (%ld bytes) truncated to fit "
                "FRAM from offset %d (%ld bytes available)\n",
                fsize, offset, available);
        }

        uint8_t *data = malloc((size_t)to_write);
        if (!data) { perror("malloc"); fclose(f); ret = 1; goto done; }

        if ((long)fread(data, 1, to_write, f) != to_write) {
            perror(write_file);
            free(data); fclose(f); ret = 1; goto done;
        }
        fclose(f);

        fprintf(stderr, "Writing %ld bytes to FRAM at offset %d ...\n",
                to_write, offset);

        ret = gs_write((uint16_t)offset, data, (uint16_t)to_write);
        free(data);

        if (ret == 0)
            printf("Write complete (%ld bytes at offset %d).\n",
                   to_write, offset);
        goto done;
    }

    /* ── Read mode ──────────────────────────────────────────────────────── */

    if (read_file) {
        int available = FRAM_SIZE - offset;
        int to_read   = (bytes_req < 0) ? available : bytes_req;

        if (to_read > available) {
            fprintf(stderr,
                "hidxfer: requested %d bytes but only %d available "
                "from offset %d\n", to_read, available, offset);
            ret = 1;
            goto done;
        }

        uint8_t *data = malloc((size_t)to_read);
        if (!data) { perror("malloc"); ret = 1; goto done; }

        fprintf(stderr, "Reading %d bytes from FRAM at offset %d ...\n",
                to_read, offset);

        if (gs_read((uint16_t)offset, data, (uint16_t)to_read) != 0) {
            free(data); ret = 1; goto done;
        }

        FILE *f = fopen(read_file, "wb");
        if (!f) {
            perror(read_file);
            free(data); ret = 1; goto done;
        }

        if ((int)fwrite(data, 1, to_read, f) != to_read) {
            perror(read_file);
            fclose(f); free(data); ret = 1; goto done;
        }

        fclose(f);
        free(data);

        printf("Read complete (%d bytes → %s).\n", to_read, read_file);
        goto done;
    }

done:
    hid_close(hd);
    hid_exit();
    return ret;
}
