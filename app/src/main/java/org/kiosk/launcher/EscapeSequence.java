/*
 * Copyright 2026 KiOSk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.kiosk.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A user-defined corner-tap combination that unlocks the kiosk: the "magic escape sequence".
 *
 * <p>The fixed nine-taps-in-one-corner gesture it replaces is strong only while nobody knows it.
 * Once somebody has watched it being used it is worthless, and it is the same on every KiOSk
 * install, so a published app would ship one universally-known way out of every kiosk. A sequence
 * the owner records themselves (say bottom-left twice, bottom-right once, bottom-left three times)
 * is unguessable by a bystander and different on every device.
 *
 * <p>No Android imports: matching a tap sequence against a buffer with a timeout is exactly the
 * sort of logic that is easy to get subtly wrong and tedious to test by hand on a tablet.
 */
final class EscapeSequence {
    static final String TOP_LEFT = "TL";
    static final String TOP_RIGHT = "TR";
    static final String BOTTOM_LEFT = "BL";
    static final String BOTTOM_RIGHT = "BR";

    /** Anything shorter is too easy to hit by accident on a touch screen mounted on a wall. */
    static final int MIN_LENGTH = 3;
    /** Long enough for a genuinely private combination, short enough to perform reliably. */
    static final int MAX_LENGTH = 12;
    /** Allowed pause between taps. Beyond this the user has stopped, not continued. */
    static final long MAX_GAP_MS = 2_000L;

    private static final List<String> ZONES =
            Arrays.asList(TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT);

    private EscapeSequence() {
    }

    /** One recorded tap: which corner, and when. */
    static final class Tap {
        final String zone;
        final long timeMs;

        Tap(String zone, long timeMs) {
            this.zone = zone;
            this.timeMs = timeMs;
        }
    }

    static boolean isZone(String value) {
        return ZONES.contains(value);
    }

    /** Parses "BL,BL,BR" into a list. Unknown or malformed entries yield an empty list. */
    static List<String> parse(String spec) {
        List<String> zones = new ArrayList<>();
        if (spec == null) {
            return zones;
        }
        for (String part : spec.split(",")) {
            String zone = part.trim().toUpperCase(Locale.US);
            if (zone.isEmpty()) {
                continue;
            }
            if (!isZone(zone)) {
                return new ArrayList<>();
            }
            zones.add(zone);
        }
        return zones;
    }

    static String format(List<String> zones) {
        StringBuilder text = new StringBuilder();
        for (String zone : zones) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(zone);
        }
        return text.toString();
    }

    /** Human-readable form for the configuration screen, e.g. "bottom-left x2, bottom-right". */
    static String describe(List<String> zones) {
        if (zones.isEmpty()) {
            return "not set";
        }
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < zones.size()) {
            int run = 1;
            while (index + run < zones.size() && zones.get(index + run).equals(zones.get(index))) {
                run++;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(name(zones.get(index)));
            if (run > 1) {
                text.append(" x").append(run);
            }
            index += run;
        }
        return text.toString();
    }

    static String name(String zone) {
        switch (zone) {
            case TOP_LEFT: return "top-left";
            case TOP_RIGHT: return "top-right";
            case BOTTOM_LEFT: return "bottom-left";
            case BOTTOM_RIGHT: return "bottom-right";
            default: return zone;
        }
    }

    static boolean isValid(List<String> zones) {
        if (zones.size() < MIN_LENGTH || zones.size() > MAX_LENGTH) {
            return false;
        }
        for (String zone : zones) {
            if (!isZone(zone)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the most recent taps are exactly this sequence, performed without pausing longer
     * than {@code maxGapMs} between them.
     *
     * <p>Matching the tail rather than the whole buffer is deliberate: a user who mistypes simply
     * carries on and gets it right, instead of having to wait for a timeout they cannot see.
     */
    static boolean matchesTail(List<Tap> taps, List<String> sequence, long maxGapMs) {
        if (sequence.isEmpty() || taps.size() < sequence.size()) {
            return false;
        }
        int offset = taps.size() - sequence.size();
        for (int index = 0; index < sequence.size(); index++) {
            Tap tap = taps.get(offset + index);
            if (!tap.zone.equals(sequence.get(index))) {
                return false;
            }
            if (index > 0) {
                long gap = tap.timeMs - taps.get(offset + index - 1).timeMs;
                if (gap < 0 || gap > maxGapMs) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Drops taps that can no longer contribute to a match, so the buffer cannot grow without bound
     * on a wall panel that people touch all day.
     */
    static List<Tap> trim(List<Tap> taps, long nowMs, long maxGapMs, int maxLength) {
        List<Tap> kept = new ArrayList<>();
        for (Tap tap : taps) {
            if (nowMs - tap.timeMs <= maxGapMs * maxLength) {
                kept.add(tap);
            }
        }
        while (kept.size() > maxLength) {
            kept.remove(0);
        }
        return kept;
    }
}
