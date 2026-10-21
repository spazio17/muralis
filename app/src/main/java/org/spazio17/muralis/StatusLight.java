/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

/**
 * The status light's vocabulary: a coloured frame around the edge of the screen, for panels
 * with no light of their own, set the way a Home Assistant light is (on or off, a colour, a
 * brightness). Pure, so the parsing and the refusals are host-tested; the frame itself is drawn
 * by {@code KioskActivity}.
 */
final class StatusLight {
    /** Red, the colour a status light is most often asked for first. */
    static final int DEFAULT_COLOR = 0xFF0000;
    static final int DEFAULT_PERCENT = 100;
    static final String COLOR_RULE = "colour must be three numbers from 0 to 255, like 255,0,0";
    static final String PERCENT_RULE = "brightness must be a whole number from 1 to 100";

    /** The colours both surfaces offer by name; any other stays settable over HTTP and MQTT. */
    static final String[][] NAMED = {
            {"Red", "255,0,0"},
            {"Amber", "255,160,0"},
            {"Green", "0,200,0"},
            {"Blue", "0,90,255"},
            {"White", "255,255,255"},
    };

    /** What is stored and drawn. */
    static final class State {
        final boolean on;
        final int color;
        final int percent;

        State(boolean on, int color, int percent) {
            this.on = on;
            this.color = color & 0xFFFFFF;
            this.percent = percent;
        }
    }

    private StatusLight() {
    }

    /** "255, 160, 0" to 0xFFA000, or null when it is not three numbers from 0 to 255. */
    static Integer parseColor(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split("\\s*,\\s*");
        if (parts.length != 3) {
            return null;
        }
        int rgb = 0;
        for (String part : parts) {
            if (!part.matches("\\d{1,3}")) {
                return null;
            }
            int channel = Integer.parseInt(part);
            if (channel > 255) {
                return null;
            }
            rgb = (rgb << 8) | channel;
        }
        return rgb;
    }

    /** 0xFFA000 to "255,160,0", the form every surface shows and takes. */
    static String formatColor(int rgb) {
        return ((rgb >> 16) & 0xFF) + "," + ((rgb >> 8) & 0xFF) + "," + (rgb & 0xFF);
    }

    static boolean isPercent(int percent) {
        return percent >= 1 && percent <= 100;
    }

    /** The frame's colour as Android draws it: the brightness is its opacity. */
    static int argb(State state) {
        int alpha = Math.round(255f * Math.max(1, Math.min(100, state.percent)) / 100f);
        return (alpha << 24) | state.color;
    }

    /** The name a surface shows for a colour, or null when it is none of {@link #NAMED}. */
    static String nameOf(int rgb) {
        String formatted = formatColor(rgb);
        for (String[] named : NAMED) {
            if (named[1].equals(formatted)) {
                return named[0];
            }
        }
        return null;
    }
}
