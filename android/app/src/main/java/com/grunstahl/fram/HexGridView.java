package com.grunstahl.fram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * HexGridView — custom View rendering a hex editor grid.
 *
 * Layout per row:
 *   [offset 4 hex digits] [space] [16 hex pairs] [space] [16 ASCII chars]
 *
 * Tapping a hex or ASCII cell selects it and fires the OnByteSelectedListener.
 */
public class HexGridView extends View {

    public interface OnByteSelectedListener {
        void onByteSelected(int index, byte value);
    }

    private static final int FRAM_SIZE  = 2048;
    private static final int COLS       = 16;
    private static final int ROWS       = FRAM_SIZE / COLS;  // 128

    /* Layout constants (dp → set in init, scaled to px) */
    private float cellW;      // hex cell width
    private float cellH;      // row height
    private float offsetW;    // offset label column width
    private float gapW;       // gap between hex and ASCII sections
    private float asciiW;     // ASCII cell width

    private Paint paintOffset;
    private Paint paintHex;
    private Paint paintHexZero;
    private Paint paintHexModified;
    private Paint paintHexSelected;
    private Paint paintAscii;
    private Paint paintAsciiModified;
    private Paint paintAsciiSelected;
    private Paint paintSelBg;
    private Paint paintModBg;

    private byte[] data     = new byte[FRAM_SIZE];
    private byte[] modified = new byte[FRAM_SIZE];
    private int    selected = -1;

    private OnByteSelectedListener listener;

    public HexGridView(Context context) { super(context); init(); }
    public HexGridView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public HexGridView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        float dp = getResources().getDisplayMetrics().density;

        cellW   = 26 * dp;
        cellH   = 22 * dp;
        offsetW = 52 * dp;
        gapW    = 14 * dp;
        asciiW  =  9 * dp;

        float textSizeHex   = 12 * dp;
        float textSizeSmall = 11 * dp;

        paintOffset = makePaint(0xFF5a7a5a, textSizeSmall);
        paintHex    = makePaint(0xFFc8e6c8, textSizeHex);
        paintHexZero     = makePaint(0xFF3a5a3a, textSizeHex);
        paintHexModified = makePaint(0xFFffb347, textSizeHex);
        paintHexSelected = makePaint(0xFF4dff91, textSizeHex);
        paintAscii       = makePaint(0xFF4a6b4a, textSizeSmall);
        paintAsciiModified = makePaint(0xFFffb347, textSizeSmall);
        paintAsciiSelected = makePaint(0xFF4dff91, textSizeSmall);

        paintSelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintSelBg.setColor(0xFF1a5c35);
        paintSelBg.setStyle(Paint.Style.FILL);

        paintModBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintModBg.setColor(0x22ffb347);
        paintModBg.setStyle(Paint.Style.FILL);

        setClickable(true);
    }

    private Paint makePaint(int color, float textSize) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(textSize);
        p.setTypeface(android.graphics.Typeface.MONOSPACE);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    /* ── Data API ──────────────────────────────────────────────────────── */

    public void setData(byte[] newData) {
        if (newData.length != FRAM_SIZE) return;
        System.arraycopy(newData, 0, data, 0, FRAM_SIZE);
        invalidate();
    }

    public void setModified(byte[] mod) {
        if (mod.length != FRAM_SIZE) return;
        System.arraycopy(mod, 0, modified, 0, FRAM_SIZE);
        invalidate();
    }

    public void setSelected(int idx) {
        selected = idx;
        invalidate();
    }

    public void setOnByteSelectedListener(OnByteSelectedListener l) {
        this.listener = l;
    }

    /* Update a single byte without full redraw */
    public void updateByte(int idx, byte value, byte mod) {
        data[idx]     = value;
        modified[idx] = mod;
        invalidate(); // fine for 2 KB — fast enough
    }

    /* ── Measurement ───────────────────────────────────────────────────── */

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float w = offsetW + COLS * cellW + gapW + COLS * asciiW;
        float h = ROWS * cellH;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    /* ── Drawing ───────────────────────────────────────────────────────── */

    private final RectF selRect = new RectF();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Vertical center of text within cell
        float textOffset = cellH / 2f
                - (paintHex.descent() + paintHex.ascent()) / 2f;

        for (int row = 0; row < ROWS; row++) {
            float y = row * cellH;
            int base = row * COLS;

            // Offset label
            float oy = y + textOffset;
            canvas.drawText(
                    String.format("%04X", base),
                    offsetW / 2f,
                    oy,
                    paintOffset);

            // Hex cells
            for (int col = 0; col < COLS; col++) {
                int idx = base + col;
                int val = data[idx] & 0xFF;
                float cx = offsetW + col * cellW + cellW / 2f;

                boolean isSel = idx == selected;
                boolean isMod = modified[idx] != 0;

                // Background highlight
                if (isSel) {
                    selRect.set(cx - cellW / 2f + 1, y + 1, cx + cellW / 2f - 1, y + cellH - 1);
                    canvas.drawRoundRect(selRect, 3, 3, paintSelBg);
                }

                Paint p = isSel ? paintHexSelected
                        : isMod ? paintHexModified
                        : val == 0 ? paintHexZero
                        : paintHex;

                canvas.drawText(String.format("%02X", val), cx, y + textOffset, p);
            }

            // Gap then ASCII
            float asciiStart = offsetW + COLS * cellW + gapW;
            for (int col = 0; col < COLS; col++) {
                int idx = base + col;
                int val = data[idx] & 0xFF;
                float cx = asciiStart + col * asciiW + asciiW / 2f;

                boolean isSel = idx == selected;
                boolean isMod = modified[idx] != 0;

                char ch = (val >= 0x20 && val < 0x7F) ? (char) val : '·';

                if (isSel) {
                    selRect.set(cx - asciiW / 2f, y + 1, cx + asciiW / 2f, y + cellH - 1);
                    canvas.drawRoundRect(selRect, 2, 2, paintSelBg);
                }

                Paint p = isSel ? paintAsciiSelected
                        : isMod ? paintAsciiModified
                        : paintAscii;

                canvas.drawText(String.valueOf(ch), cx, y + textOffset, p);
            }
        }
    }

    /* ── Touch handling ─────────────────────────────────────────────────── */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;

        float x = event.getX();
        float y = event.getY();
        int row = (int) (y / cellH);
        if (row < 0 || row >= ROWS) return true;

        int idx = -1;

        // Hit-test hex area
        float hexStart = offsetW;
        float hexEnd   = hexStart + COLS * cellW;
        if (x >= hexStart && x < hexEnd) {
            int col = (int) ((x - hexStart) / cellW);
            idx = row * COLS + col;
        }

        // Hit-test ASCII area
        float asciiStart = offsetW + COLS * cellW + gapW;
        float asciiEnd   = asciiStart + COLS * asciiW;
        if (x >= asciiStart && x < asciiEnd) {
            int col = (int) ((x - asciiStart) / asciiW);
            idx = row * COLS + col;
        }

        if (idx >= 0 && idx < FRAM_SIZE) {
            selected = idx;
            invalidate();
            if (listener != null) listener.onByteSelected(idx, data[idx]);
        }

        return true;
    }
}
