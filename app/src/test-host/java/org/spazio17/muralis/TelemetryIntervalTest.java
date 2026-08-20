/*
 * Copyright 2026 Muralis contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.spazio17.muralis;

public final class TelemetryIntervalTest {
    public static void main(String[] args) {
        for (int option : TelemetryInterval.OPTIONS) {
            require(TelemetryInterval.isValid(option), option + "s should be a valid preset");
            require(TelemetryInterval.clampOrDefault(option) == option,
                    "a valid preset should pass through clampOrDefault unchanged");
        }

        require(!TelemetryInterval.isValid(45), "45s is not one of the presets");
        require(!TelemetryInterval.isValid(0), "0s is not one of the presets");
        require(!TelemetryInterval.isValid(-30), "a negative interval is not valid");
        require(!TelemetryInterval.isValid(3600), "an hour is not one of the presets");

        require(TelemetryInterval.clampOrDefault(45) == TelemetryInterval.DEFAULT_SECONDS,
                "an invalid interval should fall back to the default, not to itself");
        require(TelemetryInterval.clampOrDefault(0) == TelemetryInterval.DEFAULT_SECONDS,
                "zero should fall back to the default");

        require(TelemetryInterval.DEFAULT_SECONDS == 30,
                "the default must stay 30s, today's fixed interval, so an upgrade changes nobody's "
                        + "configured behaviour");

        System.out.println("TelemetryIntervalTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
