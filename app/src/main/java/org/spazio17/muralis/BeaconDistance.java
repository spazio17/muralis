/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;

/**
 * How far a beacon is, from its signal: the way Home Assistant's companion app estimates it,
 * through the AltBeacon library, plus a correction for the panel that hears it (Juri,
 * 2026-10-01: the Huaweis read 9 m and 23 m for a phone 30 cm away).
 *
 * <p>The signal is averaged over the last {@link #WINDOW_MS}, the highest and the lowest tenth
 * of the readings thrown out first, so one reflected packet does not move the distance
 * (AltBeacon's running average). The distance is AltBeacon's default curve, d = A (r/t)^B + C,
 * r the averaged signal and t the signal at one metre (Android Beacon Library, "Distance
 * estimates"; coefficients of its default model, model-distance-calculations.json).
 *
 * <p>t is what the beacon announces it sends at one metre, corrected by how much weaker or
 * stronger this panel hears than that: AltBeacon says each receiving model needs its own
 * curve, and Home Assistant's iBeacon page says to calibrate at one metre. The panel learns
 * the correction once, a beacon held at one metre (Beacons.calibrate); until then it is 0.
 * Pure, for the host tests.
 */
final class BeaconDistance {
    private BeaconDistance() {
    }

    /** How long the signal is averaged over. */
    static final long WINDOW_MS = 20_000;
    /** The share of readings dropped at each end before averaging. */
    static final double TRIM = 0.1;
    /** AltBeacon's default curve (its Nexus 5 model). */
    static final double A = 0.42093;
    static final double B = 6.9476;
    static final double C = 0.54992;

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
     * The distance in metres, to a tenth, from the averaged signal, the power the beacon
     * announces at one metre, and this panel's correction in dB; NaN where the beacon announces
     * no power or nothing was heard.
     */
    static double metres(double rssi, int announcedAtOneMetre, double correctionDb) {
        if (Double.isNaN(rssi) || rssi == 0 || announcedAtOneMetre == 0) {
            return Double.NaN;
        }
        double atOneMetre = announcedAtOneMetre + correctionDb;
        if (atOneMetre >= 0) {
            return Double.NaN;
        }
        double metres = A * Math.pow(rssi / atOneMetre, B) + C;
        return Math.round(metres * 10) / 10.0;
    }

    /**
     * The correction for a beacon held at one metre: how much stronger (positive) or weaker
     * (negative) this panel hears it than it announces.
     */
    static double correction(double heardAtOneMetre, int announcedAtOneMetre) {
        return heardAtOneMetre - announcedAtOneMetre;
    }
}
