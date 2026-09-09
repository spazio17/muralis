/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * How long to wait before trying the broker again after a connection attempt failed outright.
 *
 * <p>Paho's automatic reconnect covers one case only: a connection that was established and then
 * lost. A first connect that fails, because the broker is down, not yet up after a power cut, or
 * simply not reachable from this network yet, leaves the client disconnected for good, with no
 * timer and no callback. Measured on the Lenovo 2026-09-09: broker reachable, three minutes, not
 * one attempt. A panel that boots faster than its broker after a power cut is exactly this case,
 * and the nightly restart was the only thing that healed it.
 *
 * <p>So the controller retries on its own after a failed connect, with this schedule: 15 s, 30 s,
 * 60 s, 120 s, 240 s, then every five minutes for as long as the client stands. Fast at first,
 * because a broker restarting is back within a minute; capped, because a broker that is gone for
 * the night should not be hammered every few seconds by every panel in the house.
 *
 * <p>Pure, so the schedule is host-tested rather than read off a stopwatch next to a broker that
 * has to be stopped and started by hand.
 */
final class MqttConnectRetry {
    static final long FIRST_DELAY_MS = 15_000L;
    static final long MAX_DELAY_MS = 300_000L;

    private MqttConnectRetry() {
    }

    /**
     * @param failures how many attempts have failed since the client was built, at least 1
     * @return how long to wait before the next attempt
     */
    static long delayMs(int failures) {
        if (failures < 1) {
            throw new IllegalArgumentException("failures must be at least 1");
        }
        // Shift capped well before it could overflow; anything beyond the cap is the cap anyway.
        int doublings = Math.min(failures - 1, 10);
        return Math.min(FIRST_DELAY_MS << doublings, MAX_DELAY_MS);
    }
}
