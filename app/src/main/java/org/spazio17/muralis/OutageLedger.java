/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayDeque;

/**
 * Remembers when the device's network was down, so an MQTT outage can be blamed on the right
 * thing once the connection is back.
 *
 * <p>This exists because the question it answers cannot be answered live. Everything Home
 * Assistant knows about this panel arrives over the MQTT session itself, so "MQTT is down but the
 * network is fine" is precisely the state the panel cannot report while it is true: publishing
 * the news requires the channel that is gone, and the Last Will cannot carry it either, because a
 * will's payload is frozen at connect time. A live "Network state" entity is therefore
 * information-free, it can only ever say "connected", and it was removed in favour of this:
 * the panel tracks its own connectivity continuously, and on every MQTT <em>reconnect</em> it
 * publishes what an outage was, in hindsight:
 *
 * <ul>
 *   <li><b>{@code network}</b>: the device's own network was down during the outage. Wi-Fi, the
 *   access point, or the router; not the broker's fault.
 *   <li><b>{@code broker}</b>: the network stayed up the whole time, so the MQTT session died
 *   alone. A broker restart, a Home Assistant update, changed credentials, a dropped TCP path.
 * </ul>
 *
 * <p>The timing subtlety that shapes the API: a lost connection is <em>detected</em> late. The
 * broker and the client each notice roughly one and a half keep-alives after the wire actually
 * broke (about 45s at the 30s keep-alive in {@code MqttController.start}), so the network drop
 * that caused an outage happened <em>before</em> {@code connectionLost} fired. The verdict
 * therefore looks back {@link #DETECTION_LOOKBACK_MS} before the reported loss; without that, a
 * brief Wi-Fi drop would be misfiled as a broker problem because the network was already back by
 * the time the loss was noticed.
 *
 * <p>Pure logic with no Android imports, host-tested. Callers pass a monotonic clock
 * ({@code SystemClock.elapsedRealtime()} on the device), and all methods are synchronized because
 * the network callback and Paho's callback arrive on different threads.
 */
final class OutageLedger {
    /**
     * How far before a reported MQTT loss the network history is consulted. One and a half
     * keep-alives is when the loss is typically noticed; twice that leaves margin for a slow
     * detection without reaching back far enough to blame an unrelated earlier blip.
     */
    static final long DETECTION_LOOKBACK_MS = 90_000L;
    static final String CAUSE_NETWORK = "network";
    static final String CAUSE_BROKER = "broker";
    /** Enough for weeks of ordinary flapping; a panel with more has bigger problems. */
    private static final int MAX_REMEMBERED_INTERVALS = 64;

    /** Closed [start, end] intervals during which the network was down, oldest first. */
    private final ArrayDeque<long[]> downIntervals = new ArrayDeque<>();
    private boolean networkUp;
    /** When the current down period began, meaningful only while {@code !networkUp}. */
    private long downSinceMs;

    OutageLedger(boolean networkUpNow, long nowMs) {
        networkUp = networkUpNow;
        downSinceMs = nowMs;
    }

    /** Idempotent: the ConnectivityManager can report the same state more than once. */
    synchronized void networkLost(long nowMs) {
        if (!networkUp) {
            return;
        }
        networkUp = false;
        downSinceMs = nowMs;
    }

    synchronized void networkAvailable(long nowMs) {
        if (networkUp) {
            return;
        }
        networkUp = true;
        downIntervals.addLast(new long[]{downSinceMs, nowMs});
        if (downIntervals.size() > MAX_REMEMBERED_INTERVALS) {
            downIntervals.removeFirst();
        }
    }

    /**
     * The verdict for an MQTT outage: {@link #CAUSE_NETWORK} if the device's network was down at
     * any point from {@link #DETECTION_LOOKBACK_MS} before the loss was reported until the
     * reconnect, {@link #CAUSE_BROKER} otherwise. Any overlap at all counts as the network's
     * fault, deliberately: a session that died while the network was flapping died of the
     * flapping, and "broker" must mean the stronger claim that the network was clean throughout.
     */
    synchronized String causeOfMqttOutage(long lostReportedAtMs, long restoredAtMs) {
        long from = lostReportedAtMs - DETECTION_LOOKBACK_MS;
        return networkDownMsWithin(from, restoredAtMs) > 0 ? CAUSE_NETWORK : CAUSE_BROKER;
    }

    /** Milliseconds within [fromMs, toMs] during which the network was down. */
    synchronized long networkDownMsWithin(long fromMs, long toMs) {
        long down = 0;
        for (long[] interval : downIntervals) {
            down += overlap(interval[0], interval[1], fromMs, toMs);
        }
        if (!networkUp) {
            down += overlap(downSinceMs, toMs, fromMs, toMs);
        }
        return down;
    }

    private static long overlap(long startA, long endA, long startB, long endB) {
        long start = Math.max(startA, startB);
        long end = Math.min(endA, endB);
        return Math.max(0, end - start);
    }
}
