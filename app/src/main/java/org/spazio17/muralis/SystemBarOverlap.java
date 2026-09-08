/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * How far the system's chrome actually intrudes into a window, one edge at a time.
 *
 * <p>Pure arithmetic on numbers the platform reports, deliberately free of Android imports so the
 * cases that need hardware to reproduce can be tested on the host instead. Nothing here knows a
 * bar height, a density or a device: every size is measured and passed in.
 *
 * <p><b>Why a clamp rather than a subtraction.</b> The honest question is "where is the window
 * actually visible", and below API 30 the only answer the platform gives is the visible display
 * frame. That frame is also shrunk by the soft keyboard, which is not chrome that eats corner
 * taps in the sense this is used for, so the raw difference can be several hundred pixels of
 * keyboard. Capping it at the stable inset, which is the platform's own measurement of that
 * edge's bar whether or not the bar is on screen, keeps the result inside "at most one bar" while
 * still collapsing to zero the moment the bar is hidden. A hidden bar and a raised keyboard both
 * come out right, and neither needs to be recognised as such.
 */
public final class SystemBarOverlap {

    private SystemBarOverlap() {
    }

    /**
     * Intrusion at a window's leading edge, its top or its left.
     *
     * @param windowStart  where the window begins, in display coordinates
     * @param visibleStart where the window's visible region begins, same coordinates
     * @param stable       the platform's stable inset for this edge, the cap
     */
    public static int leading(int windowStart, int visibleStart, int stable) {
        return clamp(visibleStart - windowStart, stable);
    }

    /**
     * Intrusion at a window's trailing edge, its bottom or its right.
     *
     * @param windowEnd  where the window ends, in display coordinates
     * @param visibleEnd where the window's visible region ends, same coordinates
     * @param stable     the platform's stable inset for this edge, the cap
     */
    public static int trailing(int windowEnd, int visibleEnd, int stable) {
        return clamp(windowEnd - visibleEnd, stable);
    }

    /** Into [0, stable], so a negative measurement and a keyboard-sized one both land in range. */
    private static int clamp(int measured, int stable) {
        if (stable <= 0 || measured <= 0) {
            return 0;
        }
        return Math.min(measured, stable);
    }
}
