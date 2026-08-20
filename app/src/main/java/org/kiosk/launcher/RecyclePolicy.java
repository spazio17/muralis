/*
 * Copyright 2026 KiOSk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.kiosk.launcher;

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
 * <p>Pure logic with no Android imports, and time is passed in rather than read, so every branch is
 * covered by host tests instead of by waiting a day next to the tablet.
 */
final class RecyclePolicy {
    /** Default quiet time, when nobody is looking at the wall. The owner can change it. */
    static final int DEFAULT_QUIET_HOUR = 4;
    static final int DEFAULT_QUIET_MINUTE = 0;
    /** Below this share of RAM available, recycle now rather than wait for the quiet hour. */
    static final double LOW_MEMORY_FRACTION = 0.12;
    /** A scheduled pass may not repeat inside this window, so one quiet hour means one recycle. */
    static final long SCHEDULED_MIN_INTERVAL_MS = 12L * 60 * 60 * 1000;
    /**
     * Floor between pressure-driven recycles. Without it a dashboard that is simply too big for the
     * device would reload in a loop, which is worse than the memory pressure it is reacting to.
     */
    static final long PRESSURE_MIN_INTERVAL_MS = 30L * 60 * 1000;

    private RecyclePolicy() {
    }

    /** Keeps a configured hour in range, so a typo cannot disable the nightly pass silently. */
    static int clampHour(int hour) {
        return hour < 0 || hour > 23 ? DEFAULT_QUIET_HOUR : hour;
    }

    /** Why a recycle should happen now, or null when it should not. */
    static int clampMinute(int minute) {
        return minute < 0 || minute > 59 ? DEFAULT_QUIET_MINUTE : minute;
    }

    /** "02:00 AM" style label for the settings screens. */
    static String formatTime(int hour, int minute) {
        int safeHour = clampHour(hour);
        int safeMinute = clampMinute(minute);
        String suffix = safeHour < 12 ? "AM" : "PM";
        int display = safeHour % 12;
        if (display == 0) {
            display = 12;
        }
        return String.format(java.util.Locale.US, "%02d:%02d %s", display, safeMinute, suffix);
    }

    static String shouldRecycle(long nowMs, long lastRecycleMs, int hourOfDay, int minuteOfHour,
            int quietHour, int quietMinute, long memAvailableKb, long memTotalKb) {
        long sinceLast = nowMs - lastRecycleMs;
        if (sinceLast < 0) {
            // Monotonic clock went backwards; treat it as "just recycled" rather than firing.
            return null;
        }

        if (memAvailableKb != SystemStats.UNKNOWN && memTotalKb > 0
                && memAvailableKb < memTotalKb * LOW_MEMORY_FRACTION
                && sinceLast >= PRESSURE_MIN_INTERVAL_MS) {
            return "low memory: " + (memAvailableKb / 1024) + "M of "
                    + (memTotalKb / 1024) + "M available";
        }

        if (hourOfDay == clampHour(quietHour) && minuteOfHour == clampMinute(quietMinute)
                && sinceLast >= SCHEDULED_MIN_INTERVAL_MS) {
            return "scheduled quiet-hour recycle";
        }

        return null;
    }
}
