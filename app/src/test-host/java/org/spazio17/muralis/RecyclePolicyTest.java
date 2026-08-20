/*
 * Copyright 2026 Muralis contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.spazio17.muralis;

public final class RecyclePolicyTest {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long TOTAL_KB = 1_908_756;

    public static void main(String[] args) {
        long now = 100 * HOUR;

        // Healthy tablet outside the quiet hour: leave it alone.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 13, 0, 4, 0, 1_000_000, TOTAL_KB) == null,
                "a healthy dashboard should never be recycled");

        // Quiet hour, and the last recycle was long ago.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, RecyclePolicy.DEFAULT_QUIET_HOUR, 0, RecyclePolicy.DEFAULT_QUIET_HOUR, 0, 1_000_000, TOTAL_KB) != null, "quiet-hour recycle did not fire");

        // Still the quiet hour an hour later, but one already ran: exactly one per night.
        require(RecyclePolicy.shouldRecycle(now, now - HOUR, RecyclePolicy.DEFAULT_QUIET_HOUR, 0, RecyclePolicy.DEFAULT_QUIET_HOUR, 0, 1_000_000, TOTAL_KB) == null, "quiet hour recycled twice in one night");

        // Real pressure: this is roughly what the tablet showed before lmkd killed the renderer.
        String pressure = RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0, 180_000, TOTAL_KB);
        require(pressure != null, "low memory did not trigger a recycle");
        require(pressure.contains("low memory"), "wrong reason: " + pressure);

        // Same pressure moments later must not fire again; a too-big dashboard would otherwise
        // reload in a loop, which is worse than the pressure it reacts to.
        require(RecyclePolicy.shouldRecycle(now, now - MINUTE, 13, 0, 4, 0, 180_000, TOTAL_KB) == null,
                "pressure recycles must be rate limited");

        // Just above the threshold is not pressure.
        long justAbove = (long) (TOTAL_KB * RecyclePolicy.LOW_MEMORY_FRACTION) + 1_000;
        require(RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0, justAbove, TOTAL_KB) == null,
                "recycled while memory was still above the threshold");

        // Unknown memory (an enforcing-SELinux device that cannot read meminfo) must not be
        // mistaken for zero available and trigger a permanent reload loop.
        require(RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0, SystemStats.UNKNOWN, TOTAL_KB)
                == null, "unknown memory was treated as pressure");
        require(RecyclePolicy.shouldRecycle(now, now - HOUR, 13, 0, 4, 0, 100_000, 0) == null,
                "unknown total was treated as pressure");

        // A backwards clock must not be read as "a very long time since the last recycle".
        require(RecyclePolicy.shouldRecycle(now, now + HOUR, RecyclePolicy.DEFAULT_QUIET_HOUR, 0, RecyclePolicy.DEFAULT_QUIET_HOUR, 0, 100_000, TOTAL_KB) == null, "backwards clock triggered a recycle");

        // A configured hour is honoured, and only that hour fires.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 22, 0, 22, 0, 1_000_000, TOTAL_KB)
                != null, "a configured quiet hour of 22 did not fire at 22:00");
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 0, 22, 0, 1_000_000, TOTAL_KB)
                == null, "fired at 04:00 when the configured hour was 22");

        // An out-of-range hour falls back to the default rather than disabling the pass.
        require(RecyclePolicy.clampHour(99) == RecyclePolicy.DEFAULT_QUIET_HOUR,
                "an impossible hour should fall back to the default");
        require(RecyclePolicy.clampHour(-1) == RecyclePolicy.DEFAULT_QUIET_HOUR,
                "a negative hour should fall back to the default");
        require(RecyclePolicy.clampHour(0) == 0, "midnight is a valid hour");
        require(RecyclePolicy.clampHour(23) == 23, "23:00 is a valid hour");

        // Minutes matter now: the same hour at the wrong minute must not fire.
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 30, 4, 30, 1_000_000,
                TOTAL_KB) != null, "04:30 did not fire when configured for 04:30");
        require(RecyclePolicy.shouldRecycle(now, now - 20 * HOUR, 4, 0, 4, 30, 1_000_000,
                TOTAL_KB) == null, "04:00 fired when configured for 04:30");
        require(RecyclePolicy.clampMinute(60) == RecyclePolicy.DEFAULT_QUIET_MINUTE,
                "an impossible minute should fall back to the default");
        require(RecyclePolicy.clampMinute(59) == 59, "59 is a valid minute");

        require(RecyclePolicy.formatTime(2, 0).equals("02:00 AM"), "morning format wrong");
        require(RecyclePolicy.formatTime(14, 5).equals("02:05 PM"), "afternoon format wrong");
        require(RecyclePolicy.formatTime(0, 0).equals("12:00 AM"), "midnight should read 12 AM");
        require(RecyclePolicy.formatTime(12, 0).equals("12:00 PM"), "noon should read 12 PM");

        System.out.println("RecyclePolicyTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
