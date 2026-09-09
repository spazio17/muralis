/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** The retry schedule after a failed broker connection, on the host. */
public final class MqttConnectRetryTest {
    public static void main(String[] args) {
        require(MqttConnectRetry.delayMs(1) == 15_000L, "the first retry waits 15 s");
        require(MqttConnectRetry.delayMs(2) == 30_000L, "the second waits 30 s");
        require(MqttConnectRetry.delayMs(3) == 60_000L, "the third waits 60 s");
        require(MqttConnectRetry.delayMs(4) == 120_000L, "the fourth waits 120 s");
        require(MqttConnectRetry.delayMs(5) == 240_000L, "the fifth waits 240 s");
        require(MqttConnectRetry.delayMs(6) == 300_000L, "the sixth is capped at five minutes");
        require(MqttConnectRetry.delayMs(60) == 300_000L, "and so is the sixtieth");
        require(MqttConnectRetry.delayMs(Integer.MAX_VALUE) == 300_000L,
                "an absurd count must not overflow into a negative delay");
        boolean refused = false;
        try {
            MqttConnectRetry.delayMs(0);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        require(refused, "zero failures is not a retry");
        System.out.println("MqttConnectRetryTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
