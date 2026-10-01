/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** The beacon distance: the trimmed average, the curve, and the panel's correction. */
public final class BeaconDistanceTest {
    private BeaconDistanceTest() {
    }

    public static void main(String[] args) {
        require(Double.isNaN(BeaconDistance.average(new int[0])), "no readings, no average");
        require(BeaconDistance.average(new int[] {-60, -62}) == -61, "few readings: all of them");
        int[] tenWithOutliers = {-90, -60, -60, -60, -60, -60, -60, -60, -60, -30};
        require(BeaconDistance.average(tenWithOutliers) == -60,
                "the highest and lowest tenth are left out");
        double atOne = BeaconDistance.metres(-59, -59, 0);
        require(Math.abs(atOne - 1.0) < 0.05, "heard as announced, one metre: " + atOne);
        require(BeaconDistance.metres(-75, -59, 0) > 2,
                "16 dB weaker than announced is a few metres (2.8)");
        // The Huaweis: a phone 30 cm away heard at about -75 dBm against an announced -59. Held
        // at one metre it read, say, -78: the correction is -19, and -75 is then under a metre.
        double correction = BeaconDistance.correction(-78, -59);
        require(correction == -19, "the correction is heard minus announced");
        require(BeaconDistance.metres(-75, -59, correction) < 1,
                "with the panel's correction the near phone reads near");
        require(Double.isNaN(BeaconDistance.metres(-60, 0, 0)), "no announced power, no distance");
        require(Double.isNaN(BeaconDistance.metres(Double.NaN, -59, 0)), "nothing heard, no distance");
        System.out.println("BeaconDistanceTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
