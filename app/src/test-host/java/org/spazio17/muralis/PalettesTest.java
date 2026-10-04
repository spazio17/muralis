/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** The generated schemes: every colour offered has fifteen colours, dark and light. */
public final class PalettesTest {
    private PalettesTest() {
    }

    public static void main(String[] args) {
        for (String colour : Palettes.COLOURS) {
            for (boolean dark : new boolean[] {true, false}) {
                String[] scheme = Palettes.colours(colour, dark);
                require(scheme != null && scheme.length == 15,
                        colour + (dark ? " dark" : " light") + " has 15 colours");
                for (String one : scheme) {
                    require(one.matches("#[0-9a-f]{6}"), colour + ": " + one);
                }
            }
        }
        require(Palettes.colours("purple", true)[7].equals("#d8baff"), "purple dark primary");
        require(Palettes.colours("green", true) == null, "a colour not offered has no scheme");
        require(Palettes.COLOURS[0].equals("purple"), "purple is the default");
        System.out.println("PalettesTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
