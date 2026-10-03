/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** The beacon distance: the trimmed average, the model, and the two calibration steps. */
public final class BeaconDistanceTest {
    private BeaconDistanceTest() {
    }

    public static void main(String[] args) {
        require(Double.isNaN(BeaconDistance.average(new int[0])), "no readings, no average");
        require(BeaconDistance.average(new int[] {-60, -62}) == -61, "few readings: all of them");
        int[] tenWithOutliers = {-90, -60, -60, -60, -60, -60, -60, -60, -60, -30};
        require(BeaconDistance.average(tenWithOutliers) == -60,
                "the highest and lowest tenth are left out");
        double nan = Double.NaN;
        require(BeaconDistance.metres(-59, -59, nan, nan) == 1.0, "heard as announced: 1 m");
        require(BeaconDistance.metres(-70, -59, nan, nan) == 2.8,
                "uncalibrated, 11 dB weaker than announced is 2.8 m");
        // Measured on the Huawei tablet, 2026-10-03: the Pixel on high power heard at -58 dBm
        // at one metre and -70 at three, announcing -59.
        require(BeaconDistance.metres(-70, -59, -58, -70) == 3.0, "both steps: 3 m reads 3.0");
        require(BeaconDistance.metres(-58, -59, -58, -70) == 1.0, "both steps: 1 m reads 1.0");
        require(BeaconDistance.farProblem(-58, -70) == null, "a room's fade is kept");
        // The Pixel on low power: -79.4 at one metre, -86 at three, a fade of 1.4.
        require(BeaconDistance.metres(-79.4, -59, -79.4, nan) == 1.0,
                "a beacon announcing too much reads right once calibrated at 1 m");
        require(BeaconDistance.farProblem(-79.4, -86) != null, "too weak a fade is refused");
        require(BeaconDistance.farProblem(-58, -95) != null, "too steep a fade is refused");
        require(BeaconDistance.metres(-70, -59, -58, -59) == BeaconDistance.metres(-70, -59,
                -58, nan), "a three-metre step no room gives is left out");
        require(BeaconDistance.farProblem(nan, -70) != null, "no power known, no far step");
        require(BeaconDistance.metres(-60, 0, -60, nan) == 1.0,
                "no announced power, calibrated at 1 m: a distance");
        require(Double.isNaN(BeaconDistance.metres(-60, 0, nan, nan)),
                "no announced power, uncalibrated: no distance");
        require(Double.isNaN(BeaconDistance.metres(nan, -59, nan, nan)),
                "nothing heard, no distance");
        System.out.println("BeaconDistanceTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
