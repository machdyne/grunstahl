package com.grunstahl.fram;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.grunstahl.fram.databinding.ActivityMainBinding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.grunstahl.fram.USB_PERMISSION";

    private ActivityMainBinding binding;
    private final Handler       mainHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService executor   = Executors.newSingleThreadExecutor();

    private UsbManager    usbManager;
    private GrunstahlHid  hid;

    private byte[] framData = new byte[GrunstahlHid.FRAM_SIZE];
    private byte[] modified = new byte[GrunstahlHid.FRAM_SIZE];

    private String activeTab = "hex"; // "hex" | "text"
    private boolean warningDismissed = false;

    /* ── File pickers ────────────────────────────────────────────────────── */

    private final ActivityResultLauncher<String[]> importPicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importFile(uri);
        });

    private final ActivityResultLauncher<String> exportPicker =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            if (uri != null) exportFile(uri);
        });

    /* ── USB permission receiver ─────────────────────────────────────────── */

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openDevice(device);
                } else {
                    setLog(getString(R.string.usb_permission_denied), 0xFFff4d4d);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (hid != null) {
                    hid.close();
                    hid = null;
                    mainHandler.post(() -> onDisconnected());
                }
            }
        }
    };

    /* ── Lifecycle ───────────────────────────────────────────────────────── */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        setupClickListeners();

        // Register USB permission + detach receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // Auto-connect if launched from USB attach intent
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) requestPermissionAndConnect(device);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        if (hid != null) hid.close();
        executor.shutdownNow();
    }

    /* ── Click listeners ─────────────────────────────────────────────────── */

    private void setupClickListeners() {
        binding.btnConnect.setOnClickListener(v -> connectClicked());
        binding.btnConnect2.setOnClickListener(v -> connectClicked());
        binding.btnRead.setOnClickListener(v -> readFram());
        binding.btnWrite.setOnClickListener(v -> writeFram());
        binding.btnImport.setOnClickListener(v -> importPicker.launch(new String[]{"*/*"}));
        binding.btnExport.setOnClickListener(v -> exportPicker.launch("grunstahl.bin"));
        binding.btnFill.setOnClickListener(v -> fillConfirm());
        binding.warnDismiss.setOnClickListener(v -> {
            warningDismissed = true;
            binding.binaryWarning.setVisibility(View.GONE);
        });
        binding.tabHex.setOnClickListener(v -> switchTab("hex"));
        binding.tabText.setOnClickListener(v -> switchTab("text"));

        // Text editor live byte counter
        binding.textEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTextMeta();
            }
        });

        // Hex grid byte selection
        binding.hexGrid.setOnByteSelectedListener((idx, value) -> {
            updateStatusBarByte(idx, value & 0xFF);
            showByteEditDialog(idx, value & 0xFF);
        });
    }

    /* ── Connect ─────────────────────────────────────────────────────────── */

    private void connectClicked() {
        // Find Grünstahl device
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId()  == GrunstahlHid.USB_VID &&
                device.getProductId() == GrunstahlHid.USB_PID) {
                requestPermissionAndConnect(device);
                return;
            }
        }
        setLog(getString(R.string.no_device), 0xFFff4d4d);
    }

    private void requestPermissionAndConnect(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            openDevice(device);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
        }
    }

    private void openDevice(UsbDevice device) {
        executor.execute(() -> {
            try {
                GrunstahlHid newHid = new GrunstahlHid(usbManager, device);
                hid = newHid;
                mainHandler.post(() -> {
                    onConnected(device.getProductName());
                    readFram();
                });
            } catch (IOException e) {
                mainHandler.post(() -> setLog("Connect error: " + e.getMessage(), 0xFFff4d4d));
            }
        });
    }

    private void onConnected(String name) {
        setStatus("connected", getString(R.string.status_connected)
                + (name != null ? " — " + name : ""));
        binding.connectScreen.setVisibility(View.GONE);
        binding.editorScreen.setVisibility(View.VISIBLE);
        binding.tabBar.setVisibility(View.VISIBLE);
        setControlsEnabled(true);
        binding.btnConnect.setEnabled(false);
        binding.btnConnect2.setEnabled(false);
    }

    private void onDisconnected() {
        setStatus("", getString(R.string.status_disconnected));
        binding.connectScreen.setVisibility(View.VISIBLE);
        binding.editorScreen.setVisibility(View.GONE);
        binding.tabBar.setVisibility(View.GONE);
        binding.binaryWarning.setVisibility(View.GONE);
        setControlsEnabled(false);
        binding.btnConnect.setEnabled(true);
        binding.btnConnect2.setEnabled(true);
        setLog("Device disconnected", 0xFFffb347);
    }

    /* ── FRAM Read / Write ───────────────────────────────────────────────── */

    private void readFram() {
        if (hid == null) return;
        setBusy(true);
        setLog("Reading FRAM…", 0xFFc8e6c8);
        executor.execute(() -> {
            try {
                byte[] buf = hid.readAll(fraction ->
                    mainHandler.post(() -> setXferProgress((int)(fraction * 100))));
                mainHandler.post(() -> {
                    framData = buf;
                    Arrays.fill(modified, (byte) 0);
                    warningDismissed = false;
                    if ("hex".equals(activeTab)) {
                        refreshHexGrid();
                    } else {
                        loadBufferToText();
                    }
                    updateUnsaved();
                    setBusy(false);
                    setXferProgress(0);
                    setLog("Read complete — " + GrunstahlHid.FRAM_SIZE + " bytes", 0xFF4dff91);
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    setXferProgress(0);
                    setLog("Read error: " + e.getMessage(), 0xFFff4d4d);
                });
            }
        });
    }

    private void writeFram() {
        if (hid == null) return;
        if ("text".equals(activeTab)) commitTextToBuffer();
        final byte[] snapshot = Arrays.copyOf(framData, framData.length);
        setBusy(true);
        setLog("Writing FRAM…", 0xFFc8e6c8);
        executor.execute(() -> {
            try {
                hid.writeAll(snapshot, fraction ->
                    mainHandler.post(() -> setXferProgress((int)(fraction * 100))));
                mainHandler.post(() -> {
                    Arrays.fill(modified, (byte) 0);
                    if ("hex".equals(activeTab)) refreshHexGrid();
                    updateUnsaved();
                    setBusy(false);
                    setXferProgress(0);
                    setLog("Write complete — " + GrunstahlHid.FRAM_SIZE + " bytes", 0xFF4dff91);
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    setXferProgress(0);
                    setLog("Write error: " + e.getMessage(), 0xFFff4d4d);
                });
            }
        });
    }

    /* ── Tab switching ───────────────────────────────────────────────────── */

    private void switchTab(String tab) {
        if (tab.equals(activeTab)) return;
        if ("text".equals(activeTab)) commitTextToBuffer();
        activeTab = tab;

        boolean isHex = "hex".equals(tab);
        binding.tabHex.setTextColor(isHex ? 0xFF4dff91 : 0xFF5a7a5a);
        binding.tabText.setTextColor(isHex ? 0xFF5a7a5a : 0xFF4dff91);
        binding.tabHex.setBackgroundColor(isHex ? 0xFF0a0e0a : 0xFF0f150f);
        binding.tabText.setBackgroundColor(isHex ? 0xFF0f150f : 0xFF0a0e0a);

        binding.panelHex.setVisibility(isHex ? View.VISIBLE : View.GONE);
        binding.panelText.setVisibility(isHex ? View.GONE : View.VISIBLE);
        binding.binaryWarning.setVisibility(View.GONE);

        if (!isHex) {
            warningDismissed = false;
            loadBufferToText();
        } else {
            refreshHexGrid();
        }
    }

    /* ── Hex grid ────────────────────────────────────────────────────────── */

    private void refreshHexGrid() {
        binding.hexGrid.setData(framData);
        binding.hexGrid.setModified(modified);
    }

    private void updateStatusBarByte(int idx, int val) {
        binding.sbAddr.setText(String.format("0x%04X", idx));
        binding.sbVal.setText(String.format("0x%02X (%d)", val, val));
    }

    /* ── Byte edit dialog ────────────────────────────────────────────────── */

    private void showByteEditDialog(int idx, int initialVal) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_edit_byte, null);

        TextView addrLabel = dialogView.findViewById(R.id.dialogAddr);
        EditText fieldHex  = dialogView.findViewById(R.id.fieldHex);
        EditText fieldDec  = dialogView.findViewById(R.id.fieldDec);
        EditText fieldChr  = dialogView.findViewById(R.id.fieldChr);

        addrLabel.setText("Edit byte — 0x" + String.format("%04X", idx));
        fieldHex.setText(String.format("%02X", initialVal));
        fieldDec.setText(String.valueOf(initialVal));
        fieldChr.setText((initialVal >= 0x20 && initialVal < 0x7F)
                ? String.valueOf((char) initialVal) : "");

        // Cross-field sync
        fieldHex.addTextChangedListener(new SyncWatcher(fieldHex, fieldDec, fieldChr, true));
        fieldDec.addTextChangedListener(new SyncWatcher(fieldDec, fieldHex, fieldChr, false));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.dialogCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.dialogSet).setOnClickListener(v -> {
            String hexStr = fieldHex.getText().toString().trim();
            try {
                int val = Integer.parseInt(hexStr, 16);
                if (val < 0 || val > 255) throw new NumberFormatException();
                setByte(idx, (byte) val);
                dialog.dismiss();
            } catch (NumberFormatException e) {
                fieldHex.setError("0x00–0xFF");
            }
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void setByte(int idx, byte val) {
        if (framData[idx] == val) return;
        framData[idx] = val;
        modified[idx] = 1;
        binding.hexGrid.updateByte(idx, val, (byte)1);
        updateUnsaved();
        setLog(String.format("Set 0x%04X = 0x%02X", idx, val & 0xFF), 0xFFc8e6c8);
    }

    /** TextWatcher that syncs hex↔dec↔char fields, guarded against recursion */
    private static class SyncWatcher implements TextWatcher {
        private final EditText src, otherA, otherB;
        private final boolean  isHex;
        private boolean syncing = false;

        SyncWatcher(EditText src, EditText otherA, EditText otherB, boolean isHex) {
            this.src = src; this.otherA = otherA; this.otherB = otherB; this.isHex = isHex;
        }

        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        @Override public void afterTextChanged(Editable s) {}
        @Override public void onTextChanged(CharSequence cs, int start, int before, int count) {
            if (syncing) return;
            syncing = true;
            try {
                int v = isHex
                        ? Integer.parseInt(cs.toString(), 16)
                        : Integer.parseInt(cs.toString(), 10);
                if (v >= 0 && v <= 255) {
                    if (isHex) {
                        otherA.setText(String.valueOf(v));
                    } else {
                        otherA.setText(String.format("%02X", v));
                    }
                    otherB.setText((v >= 0x20 && v < 0x7F) ? String.valueOf((char) v) : "");
                }
            } catch (NumberFormatException ignored) {}
            syncing = false;
        }
    }

    /* ── Text editor ─────────────────────────────────────────────────────── */

    private void loadBufferToText() {
        BinaryAnalysis analysis = analyseBinary(framData);

        // Decode up to first null byte
        int end = indexOf(framData, (byte)0);
        if (end < 0) end = GrunstahlHid.FRAM_SIZE;

        String text;
        try {
            text = new String(framData, 0, end, StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = new String(framData, 0, end, StandardCharsets.ISO_8859_1);
        }
        // Normalise line endings
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        binding.textEditor.setText(text);
        updateTextMeta();

        if (analysis.isBinary && !warningDismissed) {
            String msg = "⚠ Binary data detected ("
                    + (analysis.nullCount > 0 ? analysis.nullCount + " nulls" : "")
                    + (analysis.nullCount > 0 && analysis.nonPrintCount > 0 ? ", " : "")
                    + (analysis.nonPrintCount > 0 ? analysis.nonPrintCount + " non-printable" : "")
                    + "). Text edits will overwrite it.";
            ((TextView) binding.binaryWarning.getChildAt(0)).setText(msg);
            binding.binaryWarning.setVisibility(View.VISIBLE);
        }
    }

    private void commitTextToBuffer() {
        String text = binding.textEditor.getText().toString();
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(encoded.length, GrunstahlHid.FRAM_SIZE);
        System.arraycopy(encoded, 0, framData, 0, limit);
        Arrays.fill(framData, limit, GrunstahlHid.FRAM_SIZE, (byte) 0);
        Arrays.fill(modified, (byte) 1);
        updateUnsaved();
        updateTextMeta();
    }

    private void updateTextMeta() {
        String text = binding.textEditor.getText().toString();
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        int used  = Math.min(encoded.length, GrunstahlHid.FRAM_SIZE);
        int lines = text.isEmpty() ? 0
                : (int)(text.chars().filter(c -> c == '\n').count() + 1);

        binding.txtBytesUsed.setText(used + " / 2048 bytes");
        binding.txtLines.setText(lines + " lines");
        binding.textUsedBar.setProgress(used);

        int barColor = used > 1945 ? 0xFFff4d4d   // >95%
                     : used > 1638 ? 0xFFffb347   // >80%
                     : 0xFF1a5c35;
        binding.textUsedBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(barColor));
    }

    /* ── Binary detection ─────────────────────────────────────────────────── */

    private static class BinaryAnalysis {
        boolean isBinary;
        int nullCount;
        int nonPrintCount;
    }

    private static BinaryAnalysis analyseBinary(byte[] data) {
        BinaryAnalysis a = new BinaryAnalysis();
        // find last non-null
        int last = -1;
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] != 0) { last = i; break; }
        }
        int contentLen = last + 1;
        for (int i = 0; i < contentLen; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) { a.nullCount++; }
            else if (b != 0x09 && b != 0x0A && b != 0x0D && (b < 0x20 || b == 0x7F)) {
                a.nonPrintCount++;
            }
        }
        int total = Math.max(contentLen, 1);
        a.isBinary = a.nullCount >= 1 || ((float) a.nonPrintCount / total) > 0.05f;
        return a;
    }

    private static int indexOf(byte[] arr, byte val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    /* ── Fill ─────────────────────────────────────────────────────────────── */

    private void fillConfirm() {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.fill_confirm))
                .setPositiveButton(R.string.fill_confirm_yes, (d, w) -> {
                    Arrays.fill(framData, (byte) 0);
                    Arrays.fill(modified, (byte) 1);
                    if ("hex".equals(activeTab)) refreshHexGrid();
                    else loadBufferToText();
                    updateUnsaved();
                    setLog("Buffer filled with 0x00", 0xFFc8e6c8);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /* ── Import / Export ─────────────────────────────────────────────────── */

    private void importFile(Uri uri) {
        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Cannot open file");
                byte[] buf = is.readAllBytes();
                int len = Math.min(buf.length, GrunstahlHid.FRAM_SIZE);
                mainHandler.post(() -> {
                    System.arraycopy(buf, 0, framData, 0, len);
                    if (len < GrunstahlHid.FRAM_SIZE)
                        Arrays.fill(framData, len, GrunstahlHid.FRAM_SIZE, (byte)0);
                    Arrays.fill(modified, (byte)1);
                    warningDismissed = false;
                    if ("hex".equals(activeTab)) refreshHexGrid();
                    else loadBufferToText();
                    updateUnsaved();
                    setLog("Imported " + len + " bytes", 0xFFc8e6c8);
                });
            } catch (IOException e) {
                mainHandler.post(() -> setLog("Import error: " + e.getMessage(), 0xFFff4d4d));
            }
        });
    }

    private void exportFile(Uri uri) {
        if ("text".equals(activeTab)) commitTextToBuffer();
        final byte[] snapshot = Arrays.copyOf(framData, framData.length);
        executor.execute(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Cannot open file");
                os.write(snapshot);
                mainHandler.post(() -> setLog("Exported grunstahl.bin", 0xFF4dff91));
            } catch (IOException e) {
                mainHandler.post(() -> setLog("Export error: " + e.getMessage(), 0xFFff4d4d));
            }
        });
    }

    /* ── UI helpers ──────────────────────────────────────────────────────── */

    private void setStatus(String state, String text) {
        binding.statusText.setText(text);
        int color;
        switch (state) {
            case "connected": color = 0xFF4dff91; break;
            case "busy":      color = 0xFFffb347; break;
            case "error":     color = 0xFFff4d4d; break;
            default:          color = 0xFF4a6b4a; break;
        }
        binding.statusDot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color));
    }

    private void setLog(String msg, int color) {
        binding.logLine.setText(msg);
        binding.logLine.setTextColor(color);
    }

    private void setXferProgress(int pct) {
        if (pct == 0) {
            binding.progressBar.setVisibility(View.GONE);
        } else {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.progressBar.setProgress(pct);
        }
    }

    private void setBusy(boolean busy) {
        setStatus(busy ? "busy" : (hid != null ? "connected" : ""),
                  busy ? getString(R.string.status_busy)
                       : (hid != null ? getString(R.string.status_connected) : getString(R.string.status_disconnected)));
        setControlsEnabled(!busy);
        if (!busy && hid != null) {
            binding.btnConnect.setEnabled(false);
            binding.btnConnect2.setEnabled(false);
        }
    }

    private void setControlsEnabled(boolean en) {
        binding.btnRead.setEnabled(en);
        binding.btnWrite.setEnabled(en);
        binding.btnImport.setEnabled(en);
        binding.btnExport.setEnabled(en);
        binding.btnFill.setEnabled(en);
    }

    private void updateUnsaved() {
        int count = 0;
        for (byte b : modified) count += (b != 0 ? 1 : 0);
        binding.unsavedBadge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }
}
