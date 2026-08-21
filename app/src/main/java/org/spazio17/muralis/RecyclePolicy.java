/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Decides when the panel cleans itself up, and which of the two kinds of cleanup to do.
 *
 * <p>Measured on this tablet: with a real Home Assistant dashboard the renderer's memory climbs
 * steadily until lmkd kills it, 1.62 GB to 1.71 GB of system memory over half an hour, then a kill
 * reclaiming ~900 MB. The kiosk already survives that (see
 * {@code KioskWebViewClient.onRenderProcessGone}), but the reload lands at the worst possible
 * moment and at an unpredictable time. So there are two mechanisms, and they are deliberately
 * different in scale:
 *
 * <ul>
 *   <li><b>A nightly restart of the whole app</b>, once a day in the quiet hour. Routine cleaning,
 *   not a response to anything. Because the process actually exits, this reclaims everything, *   heap, the renderer, native allocations, any handler that leaked a reference, in a way that
 *   rebuilding the WebView in place cannot.
 *   <li><b>A WebView rebuild when the operating system says memory is low</b>, which is a response
 *   to something, and needs to be cheap enough to do at any hour without anyone noticing.
 * </ul>
 *
 * <p><b>There is no memory threshold in this app, and no learned model either.</b> An earlier
 * version recycled below a fixed 12% of total memory; the version after that tried to learn what
 * this dashboard normally costs and act on growth, in the shape of a Kubernetes VPA raising a pod's
 * requests from observed usage. Both are gone, and the second one is worth a note so it does not
 * get reinvented: it could not work, because the thing it needed to measure is not measurable. The
 * WebView renderer is an isolated process belonging to the WebView provider, not to this app, so no
 * public API and no readable procfs path exposes its footprint. Measuring system-wide memory
 * instead measures the device rather than the dashboard, which is the node rather than the pod, and
 * every threshold derived from it was either unreachable or above physical RAM.
 *
 * <p>What replaced it is simpler and is already sufficient: the panel restarts nightly, rebuilds
 * when the OS reports pressure, and rebuilds when the renderer is killed. Between them the
 * unpredictable event the whole design was worried about is either pre-empted by the nightly pass
 * or survived by the recovery path.
 *
 * <p><b>No user-facing control either.</b> There is no switch to turn any of this off and no
 * setting for when it runs. Both existed and both were removed: these are recovery mechanisms, and
 * a control whose only use is to stop a panel healing itself is surface area that can only be used
 * to break it. The schedule is derived from the device id instead, see {@link #scheduledMinuteOf}.
 *
 * <p>Pure logic with no Android imports, and time is passed in rather than read, so every branch is
 * covered by host tests instead of by waiting a day next to the tablet.
 */
final class RecyclePolicy {
    /** Quiet hour, when nobody is looking at the wall. The minute within it is per device. */
    static final int QUIET_HOUR = 4;
    /**
     * Floor between pressure-driven rebuilds. Without it a dashboard that is simply too big for the
     * device would reload in a loop, which is worse than the memory pressure it is reacting to.
     */
    static final long PRESSURE_MIN_INTERVAL_MS = 30L * 60 * 1000;

    private RecyclePolicy() {
    }

    /** What the panel should do, if anything. */
    enum Action {
        /** Nothing to do. */
        NONE,
        /** Restart the whole app. Once a day, in the quiet hour. */
        NIGHTLY_RESTART,
        /** Rebuild the dashboard WebView in place, because the OS reported low memory. */
        REBUILD_DASHBOARD
    }

    static final class Decision {
        final Action action;
        /** Human-readable, published to Home Assistant as the reason. */
        final String reason;

        private Decision(Action action, String reason) {
            this.action = action;
            this.reason = reason;
        }

        static final Decision NOTHING = new Decision(Action.NONE, "");

        boolean act() {
            return action != Action.NONE;
        }
    }

    /**
     * The minute of {@link #QUIET_HOUR} at which this particular panel restarts.
     *
     * <p>Spread deliberately. A fixed 04:00 is fine for one household and wrong at any scale: every
     * panel in every install would restart, reconnect to its broker and re-fetch its dashboard
     * inside the same sixty seconds. Deriving the offset from the device id keeps it deterministic, * a panel restarts at the same minute every night, while two panels in one house almost
     * certainly differ.
     *
     * <p>{@link String#hashCode()} rather than {@link Object#hashCode()}: the former is specified by
     * the language and identical in every process and every release, the latter is an address and
     * would move the schedule on every restart. Which matters more here than it looks, because the
     * schedule now survives a restart that this very method causes.
     */
    static int scheduledMinuteOf(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return 0;
        }
        // floorMod, not %: the remainder of a negative hash is negative, and Math.abs of
        // Integer.MIN_VALUE is still negative.
        return Math.floorMod(deviceId.hashCode(), 60);
    }

    /**
     * @param nowMs           a monotonic clock, used only for the pressure floor
     * @param lastRebuildMs   when the last pressure rebuild happened on that same clock, or 0 if
     *                        none has since this process started
     * @param todayEpochDay   today's local date as a day number
     * @param lastRestartDay  the day number of the last nightly restart, or a negative value if the
     *                        panel has never done one
     * @param systemLowMemory the device's own verdict, from ActivityManager.MemoryInfo.lowMemory,
     *                        whose threshold is set per device by the vendor rather than by us
     */
    static Decision decide(long nowMs, long lastRebuildMs, int hourOfDay, int minuteOfHour,
            int scheduledHour, int scheduledMinute, long todayEpochDay, long lastRestartDay,
            boolean systemLowMemory) {
        // Pressure first: it is a response to a condition that is true right now, where the nightly
        // pass is only housekeeping and can wait for the next minute, or the next night.
        if (systemLowMemory) {
            long sinceRebuild = nowMs - lastRebuildMs;
            // A backwards monotonic clock is treated as "just rebuilt" rather than as a very long
            // time, so a clock anomaly cannot turn into a reload loop.
            if (lastRebuildMs != 0 && (sinceRebuild < 0 || sinceRebuild < PRESSURE_MIN_INTERVAL_MS)) {
                return Decision.NOTHING;
            }
            return new Decision(Action.REBUILD_DASHBOARD, "the system reported low memory");
        }

        if (hourOfDay == scheduledHour && minuteOfHour == scheduledMinute
                && todayEpochDay != lastRestartDay) {
            return new Decision(Action.NIGHTLY_RESTART, "nightly restart");
        }

        return Decision.NOTHING;
    }
}
