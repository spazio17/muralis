/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/**
 * The palette for every Muralis surface: the on-device configuration screens, the escape
 * recorder, the stats overlay and the HTTP admin page all take their colours from here so the app
 * and the web page look like one product.
 *
 * <p>A dark palette and a light one, matching the light/dark switch. The neutrals are greys with a
 * faint blue cast; the accents are vivid enough to carry meaning by colour alone, because on a
 * wall panel seen from across a room a washed-out accent says nothing, and the stats overlay in
 * particular has nothing but colour to say it with. It is the app's own palette: it began near a
 * well-known one and diverged accent by accent, and since 2026-09-19 it is not credited as that
 * one anywhere (Juri: similar, but not it).
 *
 * <p>Kept as plain constants and drawable factories rather than XML themes because this app builds
 * its UI in code; there is no layout inflation to hook a style onto.
 */
final class KioskTheme {
    final int base;
    final int mantle;
    final int surface;
    final int surfaceAlt;
    final int border;
    final int text;
    final int subtext;
    final int accent;
    final int accentAlt;
    /**
     * The label a tonal button uses.
     *
     * <p>A tonal button is its hue at 18% over the card, and the hue itself cannot be read on a
     * tint of itself at any strength: measured 3.24:1 at best. This is the same value the web
     * admin carries as --ink-alt, so the two surfaces are one design.
     */
    final int inkAlt;
    final int ok;
    final int warn;
    final int bad;
    final boolean light;

    private KioskTheme(int base, int mantle, int surface, int surfaceAlt, int border, int text,
            int subtext, int accent, int accentAlt, int ok, int warn, int bad,
            int inkAlt, boolean light) {
        this.base = base;
        this.mantle = mantle;
        this.surface = surface;
        this.surfaceAlt = surfaceAlt;
        this.border = border;
        this.text = text;
        this.subtext = subtext;
        this.accent = accent;
        this.accentAlt = accentAlt;
        this.ok = ok;
        this.warn = warn;
        this.bad = bad;
        this.inkAlt = inkAlt;
        this.light = light;
    }

    static KioskTheme of(boolean lightTheme) {
        return lightTheme ? lightPalette() : darkPalette();
    }

    /** The dark palette. */
    static KioskTheme darkPalette() {
        return new KioskTheme(
                Color.parseColor("#1e1e2e"),   // base
                Color.parseColor("#181825"),   // mantle
                Color.parseColor("#313244"),   // surface
                Color.parseColor("#45475a"),   // surface, alternate
                Color.parseColor("#585b70"),   // border
                Color.parseColor("#cdd6f4"),   // text
                Color.parseColor("#a6adc8"),   // subtext
                Color.parseColor("#c08cff"),   // accent, violet
                Color.parseColor("#7aa2ff"),   // accent, blue
                Color.parseColor("#8ee88a"),   // ok, green
                Color.parseColor("#ffdf8f"),   // warn, yellow
                Color.parseColor("#ff6f91"),   // bad, red
                Color.parseColor("#9bb9ff"),   // the label a tonal button uses
                false);
    }

    /**
     * The light palette.
     *
     * <p>Its accents were darkened on 2026-09-12 so every pair this app actually draws clears
     * WCAG's 4.5:1 for text. Before that, measured against the card colour, the subtext was 3.73,
     * the blue 3.71, the red 4.10, the green 2.58 and the yellow 2.28, and the green and yellow
     * are the ones a person would actually have trouble reading. Hue and saturation were kept
     * exactly; only lightness moved, by the smallest amount that clears the minimum. The dark
     * palette needed nothing.
     */
    static KioskTheme lightPalette() {
        return new KioskTheme(
                Color.parseColor("#eff1f5"),
                Color.parseColor("#e6e9ef"),
                Color.parseColor("#dce0e8"),
                Color.parseColor("#ccd0da"),
                Color.parseColor("#bcc0cc"),
                Color.parseColor("#4c4f69"),
                Color.parseColor("#606276"),
                Color.parseColor("#8226ef"),
                Color.parseColor("#0a55eb"),
                Color.parseColor("#227212"),
                Color.parseColor("#905600"),
                Color.parseColor("#c60e36"),
                Color.parseColor("#083e9e"),
                true);
    }

    /** Rounded filled panel, used for grouped sections and input backgrounds. */
    GradientDrawable panel(int fill, float cornerRadiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(fill);
        shape.setCornerRadius(cornerRadiusPx);
        return shape;
    }

    /** Rounded panel with a hairline border, so surfaces separate without heavy dividers. */
    GradientDrawable outlinedPanel(int fill, float cornerRadiusPx, int strokePx) {
        return outlinedPanel(fill, cornerRadiusPx, strokePx, border);
    }

    /** Same, with the stroke colour chosen explicitly rather than defaulting to {@link #border}. */
    GradientDrawable outlinedPanel(int fill, float cornerRadiusPx, int strokePx, int strokeColor) {
        GradientDrawable shape = panel(fill, cornerRadiusPx);
        shape.setStroke(strokePx, strokeColor);
        return shape;
    }

    /** Solid accent button. */
    GradientDrawable filledButton(int fill, float cornerRadiusPx) {
        return panel(fill, cornerRadiusPx);
    }

    /**
     * {@code face} over a solid colour peeking out along the bottom edge, the same offset the web
     * admin's buttons use ({@code box-shadow: 0 2px 0 0 <colour>}). Needed on this device: a plain
     * {@code View.setElevation} shadow is an ambient/spot *shadow*, essentially black, and is barely
     * visible against the dark palette's near-black base. A solid coloured edge is visible regardless of the
     * background it sits on.
     */
    Drawable raisedButton(GradientDrawable face, int edgeColor, float cornerRadiusPx, int edgePx) {
        GradientDrawable edge = new GradientDrawable();
        edge.setShape(GradientDrawable.RECTANGLE);
        edge.setColor(edgeColor);
        edge.setCornerRadius(cornerRadiusPx);
        LayerDrawable layered = new LayerDrawable(new Drawable[]{edge, face});
        layered.setLayerInset(1, 0, 0, 0, edgePx);
        return layered;
    }

    /**
     * The pressed counterpart of {@link #raisedButton}: the same face, pushed down onto its edge so
     * the coloured lip disappears and the button reads as depressed.
     *
     * <p>The inset moves from the bottom to the top, which is the drawable-level equivalent of the
     * web admin's {@code :active { transform: translateY(2px); box-shadow: none }}. Both surfaces
     * therefore react the same way to a press, which is the point: the tablet's buttons had no press
     * feedback at all, because {@code raiseSlightly} turns off the platform's own elevation animator
     * to hold a constant lift and nothing replaced it.
     */
    Drawable pressedButton(GradientDrawable face, int edgeColor, float cornerRadiusPx, int edgePx) {
        GradientDrawable edge = new GradientDrawable();
        edge.setShape(GradientDrawable.RECTANGLE);
        edge.setColor(edgeColor);
        edge.setCornerRadius(cornerRadiusPx);
        LayerDrawable layered = new LayerDrawable(new Drawable[]{edge, face});
        layered.setLayerInset(1, 0, edgePx, 0, 0);
        return layered;
    }

    /**
     * Picks between a resting and a pressed background.
     *
     * <p>The two must be separate {@link Drawable} instances rather than one reused twice: a drawable
     * carries its own bounds and state, so sharing one between both entries of a
     * {@link StateListDrawable} makes the pressed entry drag the resting one's geometry around with
     * it.
     */
    Drawable pressable(Drawable resting, Drawable pressed) {
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], resting);
        return states;
    }

    /** The same colour, scaled darker by {@code factor} (0-1), for a bevel-style bottom edge. */
    static int darken(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= factor;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /**
     * {@code fraction} of one colour over another, both opaque.
     *
     * <p>For the tinted role: the accent at about a quarter strength over the card it sits on, so
     * a button can be plainly the same family as the main one and plainly lighter. Mixed against a
     * real colour rather than drawn with alpha, because these fills are layered inside a
     * {@code LayerDrawable} where a translucent layer shows whatever is beneath it, not the card.
     */
    static int mix(int over, int under, float fraction) {
        float keep = 1f - fraction;
        return Color.argb(255,
                Math.round(Color.red(over) * fraction + Color.red(under) * keep),
                Math.round(Color.green(over) * fraction + Color.green(under) * keep),
                Math.round(Color.blue(over) * fraction + Color.blue(under) * keep));
    }

    /** Outlined button for secondary actions, so the primary action stays obvious. */
    GradientDrawable outlinedButton(float cornerRadiusPx, int strokePx, int strokeColor) {
        return outlinedButton(cornerRadiusPx, strokePx, strokeColor, Color.TRANSPARENT);
    }

    /**
     * Same, with the fill chosen explicitly rather than true transparency. Needed for
     * {@link #raisedButton}: a genuinely transparent fill lets whatever is layered underneath show
     * through the entire interior, not just the exposed edge, since a LayerDrawable's layers do not
     * mask each other, they only draw in sequence. An opaque fill matching the surface the button
     * actually sits on (every caller here is inside a {@code card()}, so {@link #surface}) looks
     * identical to true transparency in practice while behaving correctly when layered.
     */
    GradientDrawable outlinedButton(float cornerRadiusPx, int strokePx, int strokeColor, int fill) {
        GradientDrawable shape = panel(fill, cornerRadiusPx);
        shape.setStroke(strokePx, strokeColor);
        return shape;
    }

    /** Readable foreground for text drawn on top of an accent fill. */
    int onAccent() {
        return light ? Color.parseColor("#eff1f5") : Color.parseColor("#11111b");
    }
}
