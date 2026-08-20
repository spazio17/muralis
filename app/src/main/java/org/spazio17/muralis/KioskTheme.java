/*
 * Copyright 2026 Muralis contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.spazio17.muralis;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * Catppuccin palette for every Muralis surface, the on-device configuration screens, the escape
 * recorder, the stats overlay and the HTTP admin page all take their colours from here so the app
 * and the web page look like one product.
 *
 * <p>Mocha for dark, Latte for light, matching the existing light/dark switch. Accents are
 * Catppuccin's own hues pushed slightly further towards saturation at the user's request: on a wall
 * panel seen from across a room the stock accents read as washed out, and the stats overlay in
 * particular has to carry meaning by colour alone.
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
    final int ok;
    final int warn;
    final int bad;
    final boolean light;

    private KioskTheme(int base, int mantle, int surface, int surfaceAlt, int border, int text,
            int subtext, int accent, int accentAlt, int ok, int warn, int bad, boolean light) {
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
        this.light = light;
    }

    static KioskTheme of(boolean lightTheme) {
        return lightTheme ? latte() : mocha();
    }

    /** Catppuccin Mocha, accents intensified. */
    static KioskTheme mocha() {
        return new KioskTheme(
                Color.parseColor("#1e1e2e"),   // base
                Color.parseColor("#181825"),   // mantle
                Color.parseColor("#313244"),   // surface0
                Color.parseColor("#45475a"),   // surface1
                Color.parseColor("#585b70"),   // surface2 as a border
                Color.parseColor("#cdd6f4"),   // text
                Color.parseColor("#a6adc8"),   // subtext0
                Color.parseColor("#c08cff"),   // mauve, more vivid
                Color.parseColor("#7aa2ff"),   // blue, more vivid
                Color.parseColor("#8ee88a"),   // green, more vivid
                Color.parseColor("#ffdf8f"),   // yellow, more vivid
                Color.parseColor("#ff6f91"),   // red, more vivid
                false);
    }

    /** Catppuccin Latte, same treatment. */
    static KioskTheme latte() {
        return new KioskTheme(
                Color.parseColor("#eff1f5"),
                Color.parseColor("#e6e9ef"),
                Color.parseColor("#dce0e8"),
                Color.parseColor("#ccd0da"),
                Color.parseColor("#bcc0cc"),
                Color.parseColor("#4c4f69"),
                Color.parseColor("#6c6f85"),
                Color.parseColor("#8226ef"),
                Color.parseColor("#1e66f5"),
                Color.parseColor("#2fa019"),
                Color.parseColor("#d68000"),
                Color.parseColor("#d20f39"),
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
        GradientDrawable shape = panel(fill, cornerRadiusPx);
        shape.setStroke(strokePx, border);
        return shape;
    }

    /** Solid accent button. */
    GradientDrawable filledButton(int fill, float cornerRadiusPx) {
        return panel(fill, cornerRadiusPx);
    }

    /** Outlined button for secondary actions, so the primary action stays obvious. */
    GradientDrawable outlinedButton(float cornerRadiusPx, int strokePx, int strokeColor) {
        GradientDrawable shape = panel(Color.TRANSPARENT, cornerRadiusPx);
        shape.setStroke(strokePx, strokeColor);
        return shape;
    }

    /** Readable foreground for text drawn on top of an accent fill. */
    int onAccent() {
        return light ? Color.parseColor("#eff1f5") : Color.parseColor("#11111b");
    }
}
