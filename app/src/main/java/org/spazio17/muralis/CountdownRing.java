/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A countdown as Material 3's determinate circular progress indicator, the seconds left in its
 * centre: the indicator empties clockwise from the top, a gap between it and the track
 * (2026-10-04, sketch A of media/drafts/beacon-countdown). Drawn here rather than taken from a
 * library: two arcs and a number. Read from across a room, so it is drawn as large as it is
 * laid out.
 */
final class CountdownRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    /** The share of the time left, 1 at the start and 0 at the end. */
    private float left = 1f;
    private String seconds = "";

    CountdownRing(Context context, int indicatorColour, int trackColour, int textColour) {
        super(context);
        for (Paint arc : new Paint[] {track, indicator}) {
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(trackColour);
        indicator.setColor(indicatorColour);
        digits.setColor(textColour);
        digits.setTextAlign(Paint.Align.CENTER);
        // Figures of one width, so the number does not shift as it counts down.
        digits.setFontFeatureSettings("tnum");
    }

    /** Shows {@code leftShare} of the time left (0 to 1): every frame. */
    void show(float leftShare) {
        left = Math.max(0f, Math.min(1f, leftShare));
        invalidate();
    }

    /** The whole seconds left: once a second, since it is also what a screen reader hears. */
    void seconds(int secondsLeft) {
        seconds = Integer.toString(secondsLeft);
        setContentDescription(seconds + " seconds left");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = size / 16f;
        track.setStrokeWidth(stroke);
        indicator.setStrokeWidth(stroke);
        float inset = stroke / 2f;
        float x = (getWidth() - size) / 2f;
        float y = (getHeight() - size) / 2f;
        bounds.set(x + inset, y + inset, x + size - inset, y + size - inset);
        float sweep = 360f * left;
        // The gap each side of the indicator, in degrees: the stroke's round caps reach into it.
        float gap = (float) Math.toDegrees(stroke * 1.5f / (bounds.width() / 2f));
        if (left > 0f && left < 1f && 360f - sweep > gap * 2f) {
            canvas.drawArc(bounds, -90f + sweep + gap, 360f - sweep - gap * 2f, false, track);
        } else if (left == 0f) {
            canvas.drawArc(bounds, 0f, 360f, false, track);
        }
        if (left > 0f) {
            canvas.drawArc(bounds, -90f, sweep, false, indicator);
        }
        digits.setTextSize(size * 0.38f);
        Paint.FontMetrics metrics = digits.getFontMetrics();
        canvas.drawText(seconds, getWidth() / 2f,
                getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, digits);
    }
}
