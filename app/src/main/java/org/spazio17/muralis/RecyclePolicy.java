/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Decides when to rebuild the dashboard WebView before the kernel does it for us.
 *
 * <p>Measured on this tablet: with a real Home Assistant dashboard the renderer's memory climbs
 * steadily until lmkd kills it, 1.62 GB to 1.71 GB of system memory over half an hour, then a kill
 * reclaiming ~900 MB. The kiosk already survives that (see
 * {@code KioskWebViewClient.onRenderProcessGone}), but the reload lands at the worst possible
 * moment and at an unpredictable time. Recycling deliberately turns an involuntary event into a
 * scheduled one.
 *
 * <p><b>No memory threshold lives here.</b> An earlier version recycled when available memory fell
 * below a fixed 12% of total. That number cannot be right for every device this app runs on — the
 * same resident footprint is a leak on a 2 GB tablet and unremarkable on a 16 GB one — and it
 * cannot distinguish a heavy dashboard from a growing one at all. The two signals that replaced it
 * both come from outside this class: what the device itself reports as low memory, and what this
 * dashboard has historically cost here ({@link MemoryBaseline}). See that class for why it is a
 * learned recommendation rather than a limit.
 *
 * <p><b>No user-facing control either.</b> There is no switch to turn recycling off and no
 * setting for when it runs. Both existed and both were removed: recycling is a recovery mechanism,
 * and a control whose only use is to stop a panel healing itself is surface area that can only be
 * used to break it. The schedule is derived from the device id instead — see
 * {@link #scheduledMinuteOf}.
 *
 * <p>Pure logic with no Android imports, and time and memory are passed in rather than read, so
 * every branch is covered by host tests instead of by waiting a day next to the tablet.
 */
final class RecyclePolicy {
    /** Quiet hour, when nobody is looking at the wall. The minute within it is per device. */
    static final int QUIET_HOUR = 4;
    /** A scheduled pass may not repeat inside this window, so one quiet hour means one recycle. */
    static final long SCHEDULED_MIN_INTERVAL_MS = 12L * 60 * 60 * 1000;
    /**
     * Floor between pressure-driven recycles. Without it a dashboard that is simply too big for the
     * device would reload in a loop, which is worse than the memory pressure it is reacting to.
     */
    static final long PRESSURE_MIN_INTERVAL_MS = 30L * 60 * 1000;

    private RecyclePolicy() {
    }

    /** Why a generation ended, so the caller knows what the baseline should learn from it. */
    enum Cause {
        /** Nothing to do. */
        NONE,
        /** The nightly pass came round. */
        SCHEDULED,
        /** The operating system said it was low on memory, by its own per-device measure. */
        SYSTEM_PRESSURE,
        /** This generation cost more than this dashboard historically costs here. */
        GROWTH
    }

    static final class Decision {
        final Cause cause;
        /** Human-readable, published to Home Assistant as the recycle reason. */
        final String reason;

        private Decision(Cause cause, String reason) {
            this.cause = cause;
            this.reason = reason;
        }

        static final Decision NOTHING = new Decision(Cause.NONE, "");

        boolean act() {
            return cause != Cause.NONE;
        }
    }

    /**
     * The minute of {@link #QUIET_HOUR} at which this particular panel recycles.
     *
     * <p>Spread deliberately. A fixed 04:00 is fine for one household and wrong at any scale: every
     * panel in every install would rebuild its dashboard, and hit whatever Home Assistant it talks
     * to, in the same sixty seconds. Deriving the offset from the device id keeps it deterministic —
     * a panel recycles at the same minute every night, which is what makes the twelve-hour interval
     * check below behave predictably — while two panels in one house almost certainly differ.
     *
     * <p>{@link String#hashCode()} rather than {@link Object#hashCode()}: the former is specified by
     * the language and identical in every process and every release, the latter is an address and
     * would move the schedule on every restart.
     */
    static int scheduledMinuteOf(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return 0;
        }
        // floorMod, not %: the remainder of a negative hash is negative, and Math.abs of
        // Integer.MIN_VALUE is still negative.
        return Math.floorMod(deviceId.hashCode(), 60);
    }

    /** "04:23" style label, for the admin surfaces and the logs. */
    static String formatTime(int hour, int minute) {
        return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }

    /**
     * @param memUsedKb     system memory in use, which is what a growing renderer moves. Not the
     *                      renderer's own footprint: the WebView renderer is an isolated process
     *                      belonging to the WebView provider, so no public API and no readable
     *                      procfs path exposes its PSS to us. On a panel running one app it is
     *                      dominated by the dashboard, which is what makes it usable here.
     * @param systemLowMemory the device's own verdict, from ActivityManager.MemoryInfo.lowMemory,
     *                      whose threshold is set per device by the vendor rather than by us
     * @param baseline      what this dashboard has historically cost here
     */
    static Decision shouldRecycle(long nowMs, long lastRecycleMs, int hourOfDay, int minuteOfHour,
            int scheduledHour, int scheduledMinute, long memUsedKb, boolean systemLowMemory,
            MemoryBaseline baseline) {
        long sinceLast = nowMs - lastRecycleMs;
        if (sinceLast < 0) {
            // Monotonic clock went backwards; treat it as "just recycled" rather than firing.
            return Decision.NOTHING;
        }

        if (systemLowMemory && sinceLast >= PRESSURE_MIN_INTERVAL_MS) {
            return new Decision(Cause.SYSTEM_PRESSURE, "the system reported low memory");
        }

        long budgetKb = baseline.budgetKb();
        if (budgetKb != MemoryBaseline.UNKNOWN && memUsedKb > budgetKb
                && sinceLast >= PRESSURE_MIN_INTERVAL_MS) {
            return new Decision(Cause.GROWTH, "in use " + (memUsedKb / 1024)
                    + "M, above the learned " + (budgetKb / 1024) + "M for this dashboard");
        }

        if (hourOfDay == scheduledHour && minuteOfHour == scheduledMinute
                && sinceLast >= SCHEDULED_MIN_INTERVAL_MS) {
            return new Decision(Cause.SCHEDULED, "scheduled quiet-hour recycle");
        }

        return Decision.NOTHING;
    }
}
