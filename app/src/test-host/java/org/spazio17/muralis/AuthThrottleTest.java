/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** Host tests for {@link AuthThrottle}: when guessing is refused, and when it is forgiven. */
public final class AuthThrottleTest {

    private static final String ATTACKER = "192.0.2.99";
    private static final String OPERATOR = "192.0.2.20";

    public static void main(String[] args) {
        belowThresholdIsNotLockedOut();
        thresholdLocksOut();
        lockoutExpires();
        lockoutDoublesAndIsCapped();
        successForgivesEverything();
        oneAddressCannotLockOutAnother();
        onlyLockoutStartIsWorthLogging();
        staleRecordsAreForgotten();
        tableIsBounded();
        System.out.println("AuthThrottleTest passed");
    }

    private static void belowThresholdIsNotLockedOut() {
        AuthThrottle throttle = new AuthThrottle();
        for (int i = 1; i < AuthThrottle.FAILURES_BEFORE_LOCKOUT; i++) {
            throttle.recordFailure(ATTACKER, 1_000L * i);
            expect(throttle.lockedOutFor(ATTACKER, 1_000L * i) == 0L,
                    "must not lock out after " + i + " failures");
        }
    }

    private static void thresholdLocksOut() {
        AuthThrottle throttle = new AuthThrottle();
        failTimes(throttle, ATTACKER, AuthThrottle.FAILURES_BEFORE_LOCKOUT, 0L);
        long remaining = throttle.lockedOutFor(ATTACKER, 0L);
        expect(remaining == AuthThrottle.BASE_LOCKOUT_MS,
                "first lockout should be BASE_LOCKOUT_MS, got " + remaining);
    }

    private static void lockoutExpires() {
        AuthThrottle throttle = new AuthThrottle();
        failTimes(throttle, ATTACKER, AuthThrottle.FAILURES_BEFORE_LOCKOUT, 0L);
        expect(throttle.lockedOutFor(ATTACKER, AuthThrottle.BASE_LOCKOUT_MS - 1) > 0L,
                "still locked out one millisecond early");
        expect(throttle.lockedOutFor(ATTACKER, AuthThrottle.BASE_LOCKOUT_MS) == 0L,
                "lockout must expire on its own; a wall panel has nobody to clear it");
    }

    private static void lockoutDoublesAndIsCapped() {
        AuthThrottle throttle = new AuthThrottle();
        long at = 0L;
        long previous = 0L;
        for (int round = 1; round <= 12; round++) {
            failTimes(throttle, ATTACKER, AuthThrottle.FAILURES_BEFORE_LOCKOUT, at);
            long lockout = throttle.lockedOutFor(ATTACKER, at);
            expect(lockout <= AuthThrottle.MAX_LOCKOUT_MS,
                    "lockout " + lockout + " exceeded the cap in round " + round);
            if (round > 1 && previous < AuthThrottle.MAX_LOCKOUT_MS) {
                expect(lockout > previous,
                        "lockout should grow while failures continue, round " + round);
            }
            previous = lockout;
            at += lockout;                    // wait it out, then fail again
        }
        expect(previous == AuthThrottle.MAX_LOCKOUT_MS, "should settle at the cap");
    }

    private static void successForgivesEverything() {
        AuthThrottle throttle = new AuthThrottle();
        failTimes(throttle, OPERATOR, AuthThrottle.FAILURES_BEFORE_LOCKOUT, 0L);
        throttle.recordSuccess(OPERATOR, AuthThrottle.BASE_LOCKOUT_MS);
        expect(throttle.lockedOutFor(OPERATOR, AuthThrottle.BASE_LOCKOUT_MS) == 0L,
                "a success must clear the record");
        // And the next failure starts from the beginning rather than resuming the doubling.
        failTimes(throttle, OPERATOR, AuthThrottle.FAILURES_BEFORE_LOCKOUT, 100_000L);
        expect(throttle.lockedOutFor(OPERATOR, 100_000L) == AuthThrottle.BASE_LOCKOUT_MS,
                "after a success the escalation must restart at the base lockout");
    }

    private static void oneAddressCannotLockOutAnother() {
        AuthThrottle throttle = new AuthThrottle();
        failTimes(throttle, ATTACKER, AuthThrottle.FAILURES_BEFORE_LOCKOUT * 4, 0L);
        expect(throttle.lockedOutFor(ATTACKER, 0L) > 0L, "the attacker should be locked out");
        expect(throttle.lockedOutFor(OPERATOR, 0L) == 0L,
                "the operator must be unaffected by someone else guessing");
    }

    private static void onlyLockoutStartIsWorthLogging() {
        AuthThrottle throttle = new AuthThrottle();
        int announced = 0;
        for (int i = 0; i < AuthThrottle.FAILURES_BEFORE_LOCKOUT; i++) {
            if (throttle.recordFailure(ATTACKER, i)) {
                announced++;
            }
        }
        expect(announced == 1,
                "exactly one failure in a round should report true, got " + announced);
    }

    private static void staleRecordsAreForgotten() {
        AuthThrottle throttle = new AuthThrottle();
        throttle.recordFailure(ATTACKER, 0L);
        expect(throttle.trackedHosts() == 1, "record should exist");
        // A later failure from someone else drives the sweep.
        throttle.recordFailure(OPERATOR, AuthThrottle.FORGET_AFTER_MS + 1);
        expect(throttle.trackedHosts() == 1,
                "the idle record should have been forgotten, tracked="
                        + throttle.trackedHosts());
    }

    private static void tableIsBounded() {
        AuthThrottle throttle = new AuthThrottle();
        for (int i = 0; i < AuthThrottle.MAX_TRACKED_HOSTS * 3; i++) {
            throttle.recordFailure("10.1." + (i / 250) + "." + (i % 250), i);
        }
        expect(throttle.trackedHosts() <= AuthThrottle.MAX_TRACKED_HOSTS,
                "table must stay bounded, got " + throttle.trackedHosts());
    }

    private static void failTimes(AuthThrottle throttle, String host, int times, long atMs) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(host, atMs);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
