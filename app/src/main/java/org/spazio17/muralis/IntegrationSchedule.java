/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

/**
 * When one integration instance asks its service, and what a failure means: every interval,
 * never more often than the definition's floor (a service's rate limit, UptimeRobot's ten a
 * minute), later when the service says Retry-After, "unavailable" after a few failures in a row
 * (never the last value standing in for a service that does not answer), and stopped while the
 * credentials were refused, until the person gives new ones. A poll can
 * be asked for now (a booking needs the room read back at once), but never sooner than the floor.
 *
 * <p>A rate-limited answer (429) counts as a failure like any other: three in a row make the
 * values unavailable even if the service is fine, which is honest, since nothing fresh came.
 *
 * <p>Pure, with no Android imports, so the rules are host-tested; the poller in the service asks
 * it and does the asking. Times are on a clock that a change of the date cannot move.
 */
final class IntegrationSchedule {
    /** Failures in a row after which the values are unavailable. */
    static final int FAILURES_UNAVAILABLE = 3;
    /** The longest Retry-After taken at its word: a service's hour, not a broken header's year. */
    static final long RETRY_AFTER_MAX_MS = 60 * 60_000L;

    private final long intervalMs;
    private final long floorMs;
    private long nextAtMs;
    private long lastStartMs = Long.MIN_VALUE / 2;
    private int failures;
    private boolean credentialsRefused;
    /** No answer since the start or since new credentials: nothing to show yet. */
    private boolean hasData;
    /** A poll asked for now and not started yet, or MIN when none. */
    private long requestedAtMs = Long.MIN_VALUE;
    /** Until when the service asked to be left alone (Retry-After), or MIN. */
    private long retryUntilMs = Long.MIN_VALUE;

    IntegrationSchedule(long intervalMs, long floorMs, long nowMs) {
        this.floorMs = Math.max(1_000L, floorMs);
        this.intervalMs = Math.max(this.floorMs, intervalMs);
        this.nextAtMs = nowMs;
    }

    /** Whether a poll should start now; if so it is counted as started. */
    synchronized boolean start(long nowMs) {
        if (credentialsRefused || nowMs < nextAtMs || nowMs - lastStartMs < floorMs) {
            return false;
        }
        lastStartMs = nowMs;
        nextAtMs = nowMs + intervalMs;
        requestedAtMs = Long.MIN_VALUE;
        return true;
    }

    /** The service answered: the values are what it said, the next poll one interval on. */
    synchronized void succeeded() {
        failures = 0;
        hasData = true;
        retryUntilMs = Long.MIN_VALUE;
        nextAtMs = lastStartMs + intervalMs;
        keepRequest();
    }

    /**
     * A poll asked for while one was running is not lost to that poll's answer, which may predate
     * what it was asked for (a booking made meanwhile): it runs at the floor.
     */
    private void keepRequest() {
        if (requestedAtMs != Long.MIN_VALUE) {
            nextAtMs = Math.min(nextAtMs, Math.max(Math.max(requestedAtMs, lastStartMs + floorMs),
                    retryUntilMs));
        }
    }

    /**
     * The service did not answer, or answered an error: the next poll waits the interval or the
     * service's Retry-After, whichever is longer. True when the values are now unavailable.
     */
    synchronized boolean failed(long nowMs, long retryAfterMs) {
        failures++;
        long retry = Math.min(Math.max(0, retryAfterMs), RETRY_AFTER_MAX_MS);
        retryUntilMs = retryAfterMs > 0 ? nowMs + retry : Long.MIN_VALUE;
        nextAtMs = nowMs + Math.max(intervalMs, retry);
        keepRequest();
        return failures >= FAILURES_UNAVAILABLE;
    }

    /** The credentials were refused for good (a 401 or 403): polling stops until new ones. */
    synchronized void credentialsRefused() {
        credentialsRefused = true;
    }

    /** New credentials were saved: polling starts again at once (within the floor). */
    synchronized void credentialsReplaced(long nowMs) {
        credentialsRefused = false;
        failures = 0;
        hasData = false;
        retryUntilMs = Long.MIN_VALUE;
        nextAtMs = nowMs;
    }

    synchronized boolean needsCredentials() {
        return credentialsRefused;
    }

    /**
     * Whether the values are unavailable: nothing answered yet (since the start or new
     * credentials), too many failures in a row, or no credentials.
     */
    synchronized boolean unavailable() {
        return !hasData || credentialsRefused || failures >= FAILURES_UNAVAILABLE;
    }

    /**
     * Asks for a poll as soon as the floor allows (a booking read back); true when it will run on
     * the next check, false when the floor makes it wait for its turn.
     */
    synchronized boolean pollNow(long nowMs) {
        if (credentialsRefused) {
            return false;
        }
        // Never sooner than the floor, nor before the service's Retry-After.
        nextAtMs = Math.max(Math.max(nowMs, lastStartMs + floorMs), retryUntilMs);
        requestedAtMs = nowMs;
        return nextAtMs == nowMs;
    }

    synchronized long nextAtMs() {
        return nextAtMs;
    }
}
