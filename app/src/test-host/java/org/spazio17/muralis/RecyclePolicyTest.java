/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

public final class RecyclePolicyTest {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long TODAY = 20_687L;
    private static final long YESTERDAY = TODAY - 1;
    private static final long NEVER = -1L;

    public static void main(String[] args) {
        testNothingHappensByDefault();
        testNightlyRestart();
        testPressureRebuild();
        testPressureBeatsTheSchedule();
        testScheduleJitter();
        System.out.println("RecyclePolicyTest passed");
    }

    private static void testNothingHappensByDefault() {
        // Wrong hour, no pressure: leave the panel alone.
        require(decide(13, 0, 4, 0, TODAY, YESTERDAY, false).action == RecyclePolicy.Action.NONE,
                "a healthy panel outside the quiet hour should be left alone");
        // Right hour, wrong minute — the minute is per device, so this must matter.
        require(decide(4, 0, 4, 37, TODAY, YESTERDAY, false).action == RecyclePolicy.Action.NONE,
                "04:00 fired when this panel is scheduled for 04:37");
    }

    private static void testNightlyRestart() {
        require(decide(4, 37, 4, 37, TODAY, YESTERDAY, false).action
                == RecyclePolicy.Action.NIGHTLY_RESTART, "the nightly restart did not fire");
        require(decide(4, 37, 4, 37, TODAY, NEVER, false).action
                == RecyclePolicy.Action.NIGHTLY_RESTART,
                "a panel that has never restarted should still do its first one");

        // The whole point of recording a date: the process dies and comes straight back inside the
        // same minute, and must not restart again. A monotonic "time since last" cannot express
        // this, because it resets with the process — which is why this is a calendar day.
        require(decide(4, 37, 4, 37, TODAY, TODAY, false).action == RecyclePolicy.Action.NONE,
                "restarted twice in one night");

        // ...and it must fire again the following night.
        require(decide(4, 37, 4, 37, TODAY + 1, TODAY, false).action
                == RecyclePolicy.Action.NIGHTLY_RESTART, "the next night was skipped");

        // A panel restarted for some other reason at 20:00 must still get its nightly clean. Under
        // the twelve-hour floor this replaced, that night was silently skipped.
        require(decide(4, 37, 4, 37, TODAY, YESTERDAY, false).action
                == RecyclePolicy.Action.NIGHTLY_RESTART,
                "an unrelated restart should not cost the panel its nightly clean");
    }

    private static void testPressureRebuild() {
        long now = 100 * HOUR;
        // Nothing rebuilt yet this process: act immediately, do not wait out the floor.
        RecyclePolicy.Decision first = RecyclePolicy.decide(now, 0, 13, 0, 4, 37,
                TODAY, YESTERDAY, true);
        require(first.action == RecyclePolicy.Action.REBUILD_DASHBOARD,
                "system low memory did not trigger a rebuild");

        // Rate limited: a dashboard simply too big for the device would otherwise reload in a loop,
        // which is worse than the pressure it reacts to.
        require(RecyclePolicy.decide(now, now - MINUTE, 13, 0, 4, 37, TODAY, YESTERDAY, true)
                .action == RecyclePolicy.Action.NONE, "pressure rebuilds must be rate limited");
        require(RecyclePolicy.decide(now, now - 31 * MINUTE, 13, 0, 4, 37, TODAY, YESTERDAY, true)
                .action == RecyclePolicy.Action.REBUILD_DASHBOARD,
                "a rebuild should be allowed again once the floor has passed");
        // Exactly at the floor counts as elapsed.
        require(RecyclePolicy.decide(now, now - RecyclePolicy.PRESSURE_MIN_INTERVAL_MS,
                13, 0, 4, 37, TODAY, YESTERDAY, true).action
                == RecyclePolicy.Action.REBUILD_DASHBOARD,
                "the floor should be inclusive");

        // A backwards monotonic clock must not read as "a very long time ago".
        require(RecyclePolicy.decide(now, now + HOUR, 13, 0, 4, 37, TODAY, YESTERDAY, true)
                .action == RecyclePolicy.Action.NONE, "a backwards clock triggered a rebuild");
    }

    private static void testPressureBeatsTheSchedule() {
        // Both true at once. Pressure wins, because it is a response to a live condition while the
        // nightly pass is housekeeping that can wait for the next minute or the next night. If this
        // ever flips, a panel under pressure at exactly 04:37 would take the heavier action.
        require(decide(4, 37, 4, 37, TODAY, YESTERDAY, true).action
                == RecyclePolicy.Action.REBUILD_DASHBOARD,
                "pressure should outrank the nightly pass");
    }

    /**
     * The nightly minute is per device, deterministic and in range. Deterministic matters more now
     * than it did: the schedule has to survive the restart it causes.
     */
    private static void testScheduleJitter() {
        for (String id : new String[] {"kiosk-1a2b3c4d", "kiosk-deadbeef", "kiosk-00000000", ""}) {
            int minute = RecyclePolicy.scheduledMinuteOf(id);
            require(minute >= 0 && minute < 60, "minute out of range for \"" + id + "\": " + minute);
            // A fresh, equal-but-distinct instance: re-hashing the SAME reference would pass even
            // for System.identityHashCode, which is exactly what must not be used — an address moves
            // the schedule on every restart.
            require(minute == RecyclePolicy.scheduledMinuteOf(new String(id.toCharArray())),
                    "minute was not stable across instances for \"" + id + "\"");
        }
        require(RecyclePolicy.scheduledMinuteOf(null) == 0, "a null id should not throw");
        // Integer.MIN_VALUE exactly: Math.abs of it is still negative, which is why floorMod is
        // used. Nothing in the list above hashes to it, so without this the plain-abs bug passes.
        require(RecyclePolicy.scheduledMinuteOf("polygenelubricants") == 52,
                "Integer.MIN_VALUE hash was not folded into range");
        // Pins String.hashCode itself, not just the range.
        require(RecyclePolicy.scheduledMinuteOf("kiosk-1a2b3c4d") == 44,
                "the derived minute changed for a known id");

        int distinct = 0;
        boolean[] seen = new boolean[60];
        String[] ids = {"kiosk-11111111", "kiosk-22222222", "kiosk-33333333", "kiosk-44444444",
            "kiosk-55555555", "kiosk-66666666", "kiosk-77777777", "kiosk-88888888"};
        for (String id : ids) {
            int minute = RecyclePolicy.scheduledMinuteOf(id);
            if (!seen[minute]) {
                seen[minute] = true;
                distinct++;
            }
        }
        require(distinct >= ids.length - 2,
                "ids barely spread across the hour: " + distinct + " of " + ids.length);
    }

    /** No pressure history, so the pressure floor is out of the way; hours and dates vary. */
    private static RecyclePolicy.Decision decide(int hour, int minute, int scheduledHour,
            int scheduledMinute, long today, long lastRestartDay, boolean lowMemory) {
        return RecyclePolicy.decide(100 * HOUR, 0, hour, minute, scheduledHour, scheduledMinute,
                today, lastRestartDay, lowMemory);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
