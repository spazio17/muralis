/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** The generated scheme: fifteen colours, dark and light, ultramarine's accent in each. */
public final class PalettesTest {
    private PalettesTest() {
    }

    public static void main(String[] args) {
        for (boolean dark : new boolean[] {true, false}) {
            String[] scheme = Palettes.colours(dark);
            require(scheme != null && scheme.length == 15,
                    (dark ? "dark" : "light") + " has 15 colours");
            for (String one : scheme) {
                require(one.matches("#[0-9a-f]{6}"), one);
            }
        }
        require(Palettes.colours(true)[7].equals("#b9c3ff"), "the dark accent");
        require(Palettes.colours(false)[7].equals("#0043ff"), "the light accent");
        System.out.println("PalettesTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
