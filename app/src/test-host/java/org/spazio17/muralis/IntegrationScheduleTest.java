/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

/** When an integration asks its service: interval, floor, Retry-After, unavailable, reauth. */
public final class IntegrationScheduleTest {

    public static void main(String[] args) {
        long t = 1_000_000L;
        IntegrationSchedule s = new IntegrationSchedule(60_000, 60_000, t);
        require(s.unavailable(), "available before any answer");
        require(s.start(t), "the first poll does not start at once");
        require(!s.start(t + 59_999), "polled before the interval");
        s.succeeded();
        require(s.start(t + 60_000) && !s.unavailable(), "the next interval did not poll");

        // Failures: Retry-After taken when longer, capped; unavailable after three in a row.
        require(!s.failed(t + 61_000, 120_000), "unavailable after one failure");
        require(!s.start(t + 61_000 + 119_999), "Retry-After not waited");
        require(s.start(t + 61_000 + 120_000), "Retry-After waited too long");
        require(!s.failed(t + 182_000, 0), "unavailable after two failures");
        require(s.start(t + 242_000), "poll after a failure");
        require(s.failed(t + 243_000, 10L * 24 * 3_600_000) && s.unavailable(),
                "not unavailable after three failures");
        require(s.nextAtMs() == t + 243_000 + IntegrationSchedule.RETRY_AFTER_MAX_MS,
                "a huge Retry-After was not capped");
        require(s.start(s.nextAtMs()), "no poll after the capped wait");
        s.succeeded();
        require(!s.unavailable(), "a success did not end unavailable");

        // Poll now: at once when the floor allows, otherwise at the floor.
        IntegrationSchedule b = new IntegrationSchedule(60_000, 15_000, t);
        require(b.start(t), "first poll");
        require(!b.pollNow(t + 5_000) && b.nextAtMs() == t + 15_000, "poll now ignored the floor");
        require(b.start(t + 15_000), "poll now did not run at the floor");
        require(b.pollNow(t + 40_000) && b.start(t + 40_000), "poll now did not run at once");

        // Credentials refused: no polling until new ones.
        b.credentialsRefused();
        require(b.needsCredentials() && b.unavailable() && !b.start(t + 999_999)
                && !b.pollNow(t + 999_999), "polled with refused credentials");
        b.credentialsReplaced(t + 1_000_000);
        require(!b.needsCredentials() && b.start(t + 1_000_000), "new credentials did not poll");

        // A poll asked for during a poll is not lost to that poll's answer.
        IntegrationSchedule d = new IntegrationSchedule(60_000, 15_000, t);
        require(d.start(t), "first poll");
        d.pollNow(t + 2_000);
        d.succeeded();
        require(!d.unavailable() && d.start(t + 15_000), "a poll asked for during a poll was lost");

        // Poll now waits for the service's Retry-After.
        require(d.start(t + 80_000), "poll");
        d.failed(t + 81_000, 30 * 60_000);
        require(!d.pollNow(t + 100_000) && !d.start(t + 100_000)
                && d.nextAtMs() == t + 81_000 + 30 * 60_000, "poll now ignored Retry-After");

        // New credentials: unavailable until the first answer with them.
        b.credentialsRefused();
        b.credentialsReplaced(t + 2_000_000);
        require(b.unavailable(), "available with new credentials before an answer");
        require(b.start(t + 2_000_000), "poll with new credentials");
        b.succeeded();
        require(!b.unavailable(), "an answer with new credentials did not count");

        // A floor longer than the interval wins; no floor below a second.
        IntegrationSchedule c = new IntegrationSchedule(1_000, 60_000, t);
        require(c.start(t) && !c.start(t + 59_000) && c.start(t + 60_000), "the floor did not win");
        System.out.println("IntegrationScheduleTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
