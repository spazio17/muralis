/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * The cases that decide where the corner tap targets land. Three of the four need hardware that
 * is not always attached, which is the whole reason this arithmetic was pulled out of the
 * activity: a bar on the bottom, a bar on the side, a window laid out below the bars, and a
 * keyboard shrinking the visible frame while no bar is on screen at all.
 */
public final class SystemBarOverlapTest {
    public static void main(String[] args) {
        testHiddenBarsCollapseToNothing();
        testVisibleBarsAreMeasured();
        testWindowLaidOutBelowTheBars();
        testKeyboardCannotInflateAnEdge();
        testDensityIsNeverAssumed();
        testGarbageIsClamped();
        System.out.println("SystemBarOverlapTest passed");
    }

    /**
     * The kiosk case, and the bug this class was written for. A device-owner panel hides both
     * bars, so the window is visible edge to edge and every edge must report zero, no matter what
     * the bars would measure if they came back.
     */
    private static void testHiddenBarsCollapseToNothing() {
        // A hidden bar is passed a cap of zero by the caller.
        require(SystemBarOverlap.trailing(800, 800, 0) == 0, "hidden bottom bar must be zero");
        require(SystemBarOverlap.leading(0, 0, 0) == 0, "hidden status bar must be zero");
        // And it stays zero even where the frame still reports the old height, which is exactly
        // what SYSTEM_UI_FLAG_LAYOUT_STABLE used to do through getSystemWindowInsets.
        require(SystemBarOverlap.trailing(800, 752, 0) == 0,
                "a hidden bar must be zero even if something still reports its height");
    }

    /** An ordinary install on Android 15, laid out edge to edge under real bars. */
    private static void testVisibleBarsAreMeasured() {
        require(SystemBarOverlap.leading(0, 24, 24) == 24, "a visible status bar must measure 24");
        require(SystemBarOverlap.trailing(800, 752, 48) == 48,
                "a visible navigation bar must measure 48");
        // Landscape phones put the navigation bar on a side, so the same arithmetic runs sideways.
        require(SystemBarOverlap.trailing(1280, 1232, 48) == 48,
                "a side navigation bar must measure the same way");
        require(SystemBarOverlap.leading(0, 48, 48) == 48, "a left-hand bar must measure too");
    }

    /**
     * Every ordinary install before Android 15: the window itself begins below the status bar and
     * ends above the navigation bar, so no bar reaches into it and nothing may be shifted.
     */
    private static void testWindowLaidOutBelowTheBars() {
        require(SystemBarOverlap.leading(24, 24, 24) == 0,
                "a window that starts below the bar must not be shifted again");
        require(SystemBarOverlap.trailing(752, 752, 48) == 0,
                "a window that ends above the bar must not be shifted again");
    }

    /**
     * The reason each edge's cap is gated on that bar being on screen. Below API 30 the visible
     * frame is the only measurement available and the soft keyboard shrinks it too, here by 352px
     * on a panel whose bars are hidden. The answer has to stay zero.
     */
    private static void testKeyboardCannotInflateAnEdge() {
        require(SystemBarOverlap.trailing(800, 448, 0) == 0,
                "a keyboard must not conjure a bar that is hidden");
        // With a real bar present the keyboard must not inflate it beyond one bar either.
        require(SystemBarOverlap.trailing(800, 448, 48) == 48,
                "a keyboard must not inflate a visible bar past its own height");
    }

    /** No size is known in advance: the same call answers for both test tablets. */
    private static void testDensityIsNeverAssumed() {
        // 48dp at 160dpi on the Lenovo, and at 272dpi on the wall panel.
        require(SystemBarOverlap.trailing(800, 752, 48) == 48, "160dpi bar");
        require(SystemBarOverlap.trailing(1920, 1838, 82) == 82, "272dpi bar");
        require(SystemBarOverlap.trailing(2992, 2929, 63) == 63, "the Pixel's own figure");
    }

    private static void testGarbageIsClamped() {
        require(SystemBarOverlap.leading(100, 40, 24) == 0,
                "a visible region starting before the window must clamp to zero");
        require(SystemBarOverlap.trailing(800, 900, 48) == 0,
                "a visible region ending after the window must clamp to zero");
        require(SystemBarOverlap.leading(0, 500, 24) == 24, "an absurd frame must cap at one bar");
        require(SystemBarOverlap.trailing(800, 300, -5) == 0, "a negative cap must not go negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
