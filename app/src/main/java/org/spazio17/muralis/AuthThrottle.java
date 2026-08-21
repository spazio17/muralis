/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Slows down password guessing against the web admin, per remote address.
 *
 * <p>Before this, the admin password could be guessed at whatever rate the LAN allowed, and nothing
 * anywhere recorded that it was happening. The password floor is eight characters, which is a
 * reasonable floor and a poor defence on its own against an attacker who gets thousands of tries a
 * second.
 *
 * <p><b>Refuses rather than sleeps.</b> The obvious implementation delays the response to a failed
 * attempt, and it is the wrong one here: the delay is served on one of a small pool of worker
 * threads, so an attacker sending failures would hold every worker and take the admin server down
 * while being throttled. Refusing immediately with the remaining time costs no worker at all.
 *
 * <p><b>Per address, and it decays.</b> A wall panel has nobody standing next to it to clear a
 * lockout, so a permanent one is a self-inflicted outage. Each address gets its own budget, the
 * lockout doubles while failures continue, it is capped, and one success erases the record
 * completely. An attacker locking themselves out never touches the operator.
 *
 * <p><b>The table is bounded</b>, because an unbounded map keyed by attacker-controlled input is the
 * same class of bug as the unbounded accept queue this server already had. Known consequence, worth
 * stating plainly: an attacker who can source from more than {@link #MAX_TRACKED_HOSTS} addresses
 * can push their own entry out and reset their budget. Eviction prefers entries that are not locked
 * out to make that as awkward as possible, and the per-host connection cap and the bounded accept
 * queue both still apply. Fixing it properly needs a fixed-size counter keyed by subnet, which is
 * not worth it for a home LAN.
 *
 * <p>Free of Android imports, and the clock is a parameter rather than a call, so every branch here
 * is host-testable without waiting real seconds for a lockout to expire.
 */
final class AuthThrottle {

    /** Consecutive failures from one address before it is locked out. */
    static final int FAILURES_BEFORE_LOCKOUT = 5;
    /** First lockout. Doubles on each subsequent round for the same address. */
    static final long BASE_LOCKOUT_MS = 30_000L;
    /** Ceiling on the doubling, so a lockout stays something an operator can wait out. */
    static final long MAX_LOCKOUT_MS = 15 * 60 * 1_000L;
    /** How long an idle, unlocked record is kept before it is forgotten. */
    static final long FORGET_AFTER_MS = 60 * 60 * 1_000L;
    /** Most addresses tracked at once. See the note on eviction above. */
    static final int MAX_TRACKED_HOSTS = 64;
    /** Cap on the doubling exponent, so the shift below cannot overflow. */
    private static final int MAX_ROUNDS = 10;

    private static final class Record {
        int consecutiveFailures;
        int lockoutRounds;
        long lockedUntilMs;
        long lastSeenMs;
    }

    private final Map<String, Record> records = new HashMap<>();

    /**
     * @return milliseconds remaining before this address may try again, or 0 when it may try now
     */
    synchronized long lockedOutFor(String host, long nowMs) {
        Record record = records.get(host);
        if (record == null) {
            return 0L;
        }
        // Touched even on a refusal, so an address that keeps hammering keeps its record alive
        // rather than ageing out of the table while it is still attacking.
        record.lastSeenMs = nowMs;
        long remaining = record.lockedUntilMs - nowMs;
        return remaining > 0L ? remaining : 0L;
    }

    /**
     * Records one failed authentication.
     *
     * @return true when this failure started a new lockout, which is the moment worth logging.
     *     Returning false for the attempts in between is deliberate: logging every failure lets
     *     anyone on the LAN fill the panel's log by guessing.
     */
    synchronized boolean recordFailure(String host, long nowMs) {
        forgetStale(nowMs);
        Record record = records.get(host);
        if (record == null) {
            if (records.size() >= MAX_TRACKED_HOSTS) {
                evictOne();
            }
            record = new Record();
            records.put(host, record);
        }
        record.lastSeenMs = nowMs;
        record.consecutiveFailures++;
        if (record.consecutiveFailures < FAILURES_BEFORE_LOCKOUT) {
            return false;
        }
        record.consecutiveFailures = 0;
        if (record.lockoutRounds < MAX_ROUNDS) {
            record.lockoutRounds++;
        }
        long lockout = BASE_LOCKOUT_MS << (record.lockoutRounds - 1);
        if (lockout > MAX_LOCKOUT_MS) {
            lockout = MAX_LOCKOUT_MS;
        }
        record.lockedUntilMs = nowMs + lockout;
        return true;
    }

    /** Forgets everything about an address that has just authenticated successfully. */
    synchronized void recordSuccess(String host, long nowMs) {
        records.remove(host);
        forgetStale(nowMs);
    }

    /** How many addresses are currently tracked. For tests and diagnostics. */
    synchronized int trackedHosts() {
        return records.size();
    }

    private void forgetStale(long nowMs) {
        Iterator<Map.Entry<String, Record>> entries = records.entrySet().iterator();
        while (entries.hasNext()) {
            Record record = entries.next().getValue();
            boolean lockedOut = record.lockedUntilMs > nowMs;
            if (!lockedOut && nowMs - record.lastSeenMs >= FORGET_AFTER_MS) {
                entries.remove();
            }
        }
    }

    /**
     * Drops one record to make room. Prefers an address that is not currently locked out, and the
     * least recently seen of those, so an attacker cannot cheaply evict their own active lockout.
     */
    private void evictOne() {
        String victim = null;
        boolean victimLocked = true;
        long victimLastSeen = Long.MAX_VALUE;
        long now = Long.MIN_VALUE;
        for (Map.Entry<String, Record> entry : records.entrySet()) {
            Record record = entry.getValue();
            // "Locked" here is relative to the newest timestamp seen in the table, since this
            // method has no clock of its own and does not need one to rank candidates.
            now = Math.max(now, record.lastSeenMs);
        }
        for (Map.Entry<String, Record> entry : records.entrySet()) {
            Record record = entry.getValue();
            boolean locked = record.lockedUntilMs > now;
            if (victim == null
                    || (victimLocked && !locked)
                    || (victimLocked == locked && record.lastSeenMs < victimLastSeen)) {
                victim = entry.getKey();
                victimLocked = locked;
                victimLastSeen = record.lastSeenMs;
            }
        }
        if (victim != null) {
            records.remove(victim);
        }
    }
}
