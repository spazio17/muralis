/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;

/**
 * How far a beacon is, from its signal. The signal is averaged over the last
 * {@link #WINDOW_MS}, the highest and the lowest tenth of the readings thrown out first, so one
 * reflected packet does not move the distance (AltBeacon's running average, which Home
 * Assistant's companion app uses).
 *
 * <p>The distance is the log-distance model, d = 10^((t - r) / (10 n)): r the averaged signal,
 * t the signal at one metre, n how fast the signal fades with distance. Each beacon can be
 * calibrated on each panel, both steps optional (Juri, 2026-10-03): held at one metre, the
 * panel learns t; held at three metres too, it learns n for that room. Without a calibration t
 * is what the beacon announces and n is {@link #ROOM_FADE}. AltBeacon's curve, which this
 * replaced, takes no second point, and on the Huawei tablet it read 2.1 m for a beacon three
 * metres away. Pure, for the host tests.
 */
final class BeaconDistance {
    private BeaconDistance() {
    }

    /** How long the signal is averaged over. */
    static final long WINDOW_MS = 20_000;
    /** The share of readings dropped at each end before averaging. */
    static final double TRIM = 0.1;
    /**
     * How fast the signal fades in a room until the panel learns it: 2 in open air, more
     * indoors; 2.5 is what the Huawei tablet measured on 2026-10-03, the Pixel on high power.
     */
    static final double ROOM_FADE = 2.5;
    /** The fade a three-metre step may give: outside it, a distance was wrong or the beacon weak. */
    static final double LEAST_FADE = 1.5;
    static final double MOST_FADE = 5;
    /** Where the second calibration step holds the beacon. */
    static final double FAR_METRES = 3;

    /**
     * The average of the signal readings, in dBm, the highest and lowest tenth left out; NaN
     * for none. With fewer than ten readings nothing is dropped.
     */
    static double average(int[] readings) {
        if (readings == null || readings.length == 0) {
            return Double.NaN;
        }
        int[] sorted = readings.clone();
        Arrays.sort(sorted);
        int drop = (int) Math.floor(sorted.length * TRIM);
        double sum = 0;
        int count = 0;
        for (int index = drop; index < sorted.length - drop; index++) {
            sum += sorted[index];
            count++;
        }
        return sum / count;
    }

    /**
     * The signal at one metre: as heard there when calibrated, else as the beacon announces;
     * NaN when neither is known.
     */
    static double atOneMetre(int announced, double heardAtOneMetre) {
        if (!Double.isNaN(heardAtOneMetre)) {
            return heardAtOneMetre;
        }
        return announced == 0 ? Double.NaN : announced;
    }

    /** How fast the signal fades, from the signal at one metre and at three. */
    static double fade(double atOneMetre, double heardAtThreeMetres) {
        return (atOneMetre - heardAtThreeMetres) / (10 * Math.log10(FAR_METRES));
    }

    /**
     * Why a three-metre step cannot be kept, or null: the signal at one metre unknown, or a
     * fade no room gives (the Pixel on low power faded 1.4, too weak to tell).
     */
    static String farProblem(double atOneMetre, double heardAtThreeMetres) {
        if (Double.isNaN(atOneMetre)) {
            return "the beacon announces no power; calibrate it at 1 m first";
        }
        double fade = fade(atOneMetre, heardAtThreeMetres);
        if (fade < LEAST_FADE) {
            return "the signal at 3 m is hardly weaker than at 1 m: check both distances, or "
                    + "raise the beacon's transmit power and calibrate all distances again";
        }
        if (fade > MOST_FADE) {
            return "the signal at 3 m is far weaker than at 1 m: check both distances, and "
                    + "that nothing stands between the beacon and the panel";
        }
        return null;
    }

    /**
     * The distance in metres, to a tenth, from the averaged signal, the power the beacon
     * announces at one metre, and what the panel heard at one and three metres (NaN for a step
     * not done); NaN when nothing was heard or the signal at one metre is unknown.
     */
    static double metres(double rssi, int announced, double heardAtOneMetre,
            double heardAtThreeMetres) {
        if (Double.isNaN(rssi) || rssi == 0) {
            return Double.NaN;
        }
        double atOneMetre = atOneMetre(announced, heardAtOneMetre);
        if (Double.isNaN(atOneMetre) || atOneMetre >= 0) {
            return Double.NaN;
        }
        double fade = Double.isNaN(heardAtThreeMetres)
                || farProblem(atOneMetre, heardAtThreeMetres) != null
                ? ROOM_FADE : fade(atOneMetre, heardAtThreeMetres);
        double metres = Math.pow(10, (atOneMetre - rssi) / (10 * fade));
        return Math.round(metres * 10) / 10.0;
    }
}
