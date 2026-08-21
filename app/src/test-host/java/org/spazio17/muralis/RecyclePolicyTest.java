/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

public final class RecyclePolicyTest {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    /** Roughly what a settled dashboard costs on the test tablet, in KiB of system memory. */
    private static final long SETTLED_KB = 1_620_000;

    public static void main(String[] args) {
        long now = 100 * HOUR;
        MemoryBaseline learned = trained(SETTLED_KB);
        MemoryBaseline unlearned = MemoryBaseline.empty();

        // Healthy tablet outside the quiet hour: leave it alone.
        require(!RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 13, 0, 4, 0,
                SETTLED_KB, false, learned).act(),
                "a healthy dashboard should never be recycled");

        // Quiet hour, and the last recycle was long ago.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 0, 4, 0,
                SETTLED_KB, false, learned).cause == RecyclePolicy.Cause.SCHEDULED,
                "quiet-hour recycle did not fire");

        // Still the quiet hour an hour later, but one already ran: exactly one per night.
        require(!RecyclePolicy.shouldRecycle(now, now - HOUR, 4, 0, 4, 0,
                SETTLED_KB, false, learned).act(),
                "quiet hour recycled twice in one night");

        // The minute matters, since it is derived per device rather than always zero.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 37, 4, 37,
                SETTLED_KB, false, learned).act(), "04:37 did not fire when scheduled for 04:37");
        require(!RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 0, 4, 37,
                SETTLED_KB, false, learned).act(), "04:00 fired when scheduled for 04:37");

        // The device's own low-memory verdict, which is the only threshold this app trusts.
        RecyclePolicy.Decision pressure = RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0,
                SETTLED_KB, true, learned);
        require(pressure.cause == RecyclePolicy.Cause.SYSTEM_PRESSURE,
                "system low memory did not trigger a recycle");

        // Same pressure moments later must not fire again; a too-big dashboard would otherwise
        // reload in a loop, which is worse than the pressure it reacts to.
        require(!RecyclePolicy.shouldRecycle(now, now - MINUTE, 13, 0, 4, 0,
                SETTLED_KB, true, learned).act(), "pressure recycles must be rate limited");

        // Growth against what this dashboard historically costs here. Note there is no fraction of
        // total memory involved anywhere: the same figure is a leak or healthy depending only on
        // what this device has learned.
        long wayAbove = learned.budgetKb() + 100_000;
        require(RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0,
                wayAbove, false, learned).cause == RecyclePolicy.Cause.GROWTH,
                "growth beyond the learned baseline did not trigger a recycle");
        require(!RecyclePolicy.shouldRecycle(now, now - MINUTE, 13, 0, 4, 0,
                wayAbove, false, learned).act(), "growth recycles must be rate limited too");

        // Exactly at the budget is not yet growth.
        require(!RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0,
                learned.budgetKb(), false, learned).act(),
                "recycled while still inside the learned budget");

        // An untrained model must never act on memory. A fresh panel would otherwise reload itself
        // on the strength of no evidence at all.
        require(!RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0,
                SETTLED_KB * 4, false, unlearned).act(),
                "an untrained baseline was acted on");
        // ...but the schedule and the system's own verdict still work while it learns.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 0, 4, 0,
                SETTLED_KB, false, unlearned).act(),
                "the nightly pass should not wait for the model to train");
        require(RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0,
                SETTLED_KB, true, unlearned).act(),
                "system pressure should not wait for the model to train");

        // A backwards clock must not be read as "a very long time since the last recycle".
        require(!RecyclePolicy.shouldRecycle(now, now + HOUR, 4, 0, 4, 0,
                SETTLED_KB, true, learned).act(), "backwards clock triggered a recycle");

        testScheduleJitter();
        testFormat();

        System.out.println("RecyclePolicyTest passed");
    }

    /**
     * The nightly minute is per device, deterministic, and in range. Deterministic is the
     * load-bearing half: the twelve-hour interval check only behaves if a panel picks the same
     * minute every night.
     */
    private static void testScheduleJitter() {
        for (String id : new String[] {"kiosk-1a2b3c4d", "kiosk-deadbeef", "kiosk-00000000", ""}) {
            int minute = RecyclePolicy.scheduledMinuteOf(id);
            require(minute >= 0 && minute < 60, "minute out of range for \"" + id + "\": " + minute);
            require(minute == RecyclePolicy.scheduledMinuteOf(id),
                    "minute was not stable for \"" + id + "\"");
        }
        require(RecyclePolicy.scheduledMinuteOf(null) == 0, "a null id should not throw");

        // Two ids should not normally collide. Not a guarantee — 60 buckets, so collisions exist —
        // but the whole point is that panels spread, so a implementation that returned a constant
        // must fail here.
        int distinct = 0;
        int[] seen = new int[60];
        String[] ids = {"kiosk-11111111", "kiosk-22222222", "kiosk-33333333", "kiosk-44444444",
            "kiosk-55555555", "kiosk-66666666", "kiosk-77777777", "kiosk-88888888"};
        for (String id : ids) {
            int minute = RecyclePolicy.scheduledMinuteOf(id);
            if (seen[minute]++ == 0) {
                distinct++;
            }
        }
        require(distinct >= ids.length - 2,
                "ids barely spread across the hour: " + distinct + " of " + ids.length);
    }

    private static void testFormat() {
        require(RecyclePolicy.formatTime(4, 0).equals("04:00"), "24h format wrong");
        require(RecyclePolicy.formatTime(14, 5).equals("14:05"), "afternoon format wrong");
        require(RecyclePolicy.formatTime(0, 0).equals("00:00"), "midnight format wrong");
    }

    /** A baseline that has seen enough settled generations to be worth acting on. */
    private static MemoryBaseline trained(long usedKb) {
        MemoryBaseline baseline = MemoryBaseline.empty();
        for (int i = 0; i < MemoryBaseline.MIN_GENERATIONS + 2; i++) {
            baseline = baseline.observeNaturalEnd(usedKb);
        }
        require(baseline.trusted(), "the fixture failed to train the baseline");
        return baseline;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
