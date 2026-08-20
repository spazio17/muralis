/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The small monochrome glyphs the status chip is built from: battery, address, memory and CPU.
 * Drawn on a canvas rather than shipped as drawables, because this app builds its UI in code and
 * has no resource-backed theme to hang vector assets on.
 *
 * <p>Deliberately monochrome in the style of a Pixel status bar: one tint for the whole set (the
 * theme's text colour, so near-white on Mocha and near-black on Latte) with the inactive part of a
 * gauge drawn as a ghost of the same colour. Level is carried by shape, not by hue, so the icons
 * read as one family. The single exception is a battery below {@link #LOW_BATTERY_PERCENT} on
 * mains-free power, which turns red. That is what a Pixel does too, and it is the one status
 * worth shouting about.
 *
 * <p>The web admin page draws the same glyphs as inline SVG with matching geometry, so the two
 * surfaces look like one product; keep them in step when either changes.
 */
final class StatusIcon extends View {
    enum Kind { BATTERY, ADDRESS, MEMORY, CPU }

    /** Below this, on battery, the glyph turns red. */
    static final int LOW_BATTERY_PERCENT = 15;


    private final Kind kind;
    private final KioskTheme theme;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 0-4 for Wi-Fi, 0-100 for the battery, ignored by the rest. */
    private int level;

    StatusIcon(Context context, KioskTheme theme, Kind kind) {
        super(context);
        this.kind = kind;
        this.theme = theme;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        tint(theme.text);
    }

    private void tint(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    /** Battery level plus its charge state in one call, since the tint depends on both. */
    StatusIcon battery(int percent, boolean plugged) {
        this.level = percent;
        tint(percent >= 0 && percent < LOW_BATTERY_PERCENT && !plugged ? theme.bad : theme.text);
        return this;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float unit = Math.min(w, h) / 24f;
        stroke.setStrokeWidth(1.7f * unit);
        switch (kind) {
            case BATTERY:
                drawBattery(canvas, w, h, unit);
                break;
            case ADDRESS:
                drawAddress(canvas, w, h, unit);
                break;
            case MEMORY:
                drawMemory(canvas, w, h, unit);
                break;
            case CPU:
            default:
                drawCpu(canvas, w, h, unit);
                break;
        }
    }

    /**
     * Upright cell with a nub, filled from the bottom in proportion to charge. No charging bolt: at
     * status-bar size the cell interior is about seven pixels wide and every bolt tried there read as
     * a smudge, so the charge state is carried by the words beside the glyph instead.
     */
    private void drawBattery(Canvas canvas, float w, float h, float unit) {
        float cx = w / 2f;
        rect.set(cx - 5.5f * unit, 4f * unit, cx + 5.5f * unit, 22f * unit);
        canvas.drawRoundRect(rect, 3f * unit, 3f * unit, stroke);
        rect.set(cx - 2f * unit, 1.9f * unit, cx + 2f * unit, 4.2f * unit);
        canvas.drawRoundRect(rect, unit, unit, fill);

        float inset = 1.9f * unit;
        float top = 4f * unit + inset;
        float bottom = 22f * unit - inset;
        float clamped = Math.max(0, Math.min(100, level)) / 100f;
        float filledTop = bottom - (bottom - top) * (level < 0 ? 0f : clamped);
        if (level >= 0) {
            rect.set(cx - 3.6f * unit, filledTop, cx + 3.6f * unit, bottom);
            canvas.drawRoundRect(rect, unit, unit, fill);
        }
    }

    /** A globe: the address is what reaches this panel from elsewhere on the network. */
    private void drawAddress(Canvas canvas, float w, float h, float unit) {
        float cx = w / 2f;
        float cy = h / 2f;
        float r = 9f * unit;
        canvas.drawCircle(cx, cy, r, stroke);
        canvas.drawLine(cx - r, cy, cx + r, cy, stroke);
        rect.set(cx - 4.2f * unit, cy - r, cx + 4.2f * unit, cy + r);
        canvas.drawOval(rect, stroke);
    }

    /** A memory chip with its pins, the conventional glyph for RAM. */
    private void drawMemory(Canvas canvas, float w, float h, float unit) {
        float cx = w / 2f;
        float cy = h / 2f;
        rect.set(cx - 6f * unit, cy - 6f * unit, cx + 6f * unit, cy + 6f * unit);
        canvas.drawRoundRect(rect, 2f * unit, 2f * unit, stroke);
        rect.set(cx - 2.5f * unit, cy - 2.5f * unit, cx + 2.5f * unit, cy + 2.5f * unit);
        canvas.drawRoundRect(rect, unit, unit, fill);
        float pinWidth = stroke.getStrokeWidth();
        stroke.setStrokeWidth(1.4f * unit);
        for (float offset : new float[] {-3f, 0f, 3f}) {
            canvas.drawLine(cx + offset * unit, cy - 9f * unit,
                    cx + offset * unit, cy - 6f * unit, stroke);
            canvas.drawLine(cx + offset * unit, cy + 6f * unit,
                    cx + offset * unit, cy + 9f * unit, stroke);
            canvas.drawLine(cx - 9f * unit, cy + offset * unit,
                    cx - 6f * unit, cy + offset * unit, stroke);
            canvas.drawLine(cx + 6f * unit, cy + offset * unit,
                    cx + 9f * unit, cy + offset * unit, stroke);
        }
        stroke.setStrokeWidth(pinWidth);
    }

    /** A dial with a needle: how hard the thing is working. */
    private void drawCpu(Canvas canvas, float w, float h, float unit) {
        float cx = w / 2f;
        float cy = h / 2f + 4f * unit;
        float r = 8.5f * unit;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(rect, 180f, 180f, false, stroke);
        canvas.drawLine(cx, cy, cx + 4.6f * unit, cy - 6f * unit, stroke);
        canvas.drawCircle(cx, cy, 1.5f * unit, fill);
    }
}
