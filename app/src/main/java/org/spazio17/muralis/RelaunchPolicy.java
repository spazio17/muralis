/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Decides whether the service should put the dashboard back on screen, as pure state with time
 * passed in.
 *
 * <p>Why this exists, measured on the Huawei MediaPad on 2026-09-03: with the tablet on battery and
 * the screen turned off at the power button, EMUI's PowerGenie force-stopped Muralis five and a half
 * minutes later. Android then tried to relaunch Muralis as HOME, the force-stop's own finishing pass
 * killed that relaunch, and the system fell back to the OEM launcher's task, which was still
 * underneath because the panel had last been opened from the launcher. The tablet woke to the
 * launcher and stayed there. Reproduced over adb with {@code am force-stop} and a launcher task
 * underneath; with no launcher task the system's retry does bring Muralis back, so "the system
 * relaunches HOME" is true only when nothing else can be resumed. Hence a supervisor: a force-stop
 * cancels every alarm, job and sticky service this app owns, but the system's HOME attempt starts
 * this process, and a process is enough to start the service, which then asks this class.
 *
 * <p>Pure logic with no Android imports, so every branch is covered by host tests instead of by a
 * battery and a stopwatch next to the tablet.
 */
final class RelaunchPolicy {
    /**
     * Floor between relaunches, read across processes. It exists against one failure only: a killer
     * that strikes back the moment the dashboard reappears would otherwise have the panel thrash,
     * process start, WebView, dashboard load, kill, forever, on battery. PowerGenie waited minutes,
     * so this costs nothing in the measured case and bounds the unmeasured one.
     */
    static final long RELAUNCH_FLOOR_MS = 60_000L;
    /**
     * How old the service must be before "no activity" means anything. A process that has just
     * started has the activity's creation still queued behind the service's own: the boot receiver
     * and the package-replaced path both start the service and the activity back to back, and the
     * telemetry tick runs the instant the service is up. Measured 2026-09-03: checked at once, the
     * supervisor relaunched an activity that was a few milliseconds from existing, and the stamp it
     * wrote then made the real relaunch after a force-stop eight seconds later read as too soon.
     * The same delay also clears the force-stop's own finishing pass, which killed the system's
     * HOME relaunch 7 ms after starting it and resumed the launcher 44 ms later.
     */
    static final long SETTLE_MS = 1_500L;

    /** What the supervisor should do, and why it is not doing it otherwise. */
    enum Verdict {
        /** Not a device-owner panel: an ordinary install closes like any app and stays closed. */
        NOT_A_KIOSK,
        /** The service is younger than {@link #SETTLE_MS}; the activity may simply not exist yet. */
        SETTLING,
        /** The activity exists, on screen or paused behind something the operator opened. */
        ON_SCREEN,
        /** First-run setup has not finished or the user is not unlocked yet; try again later. */
        NOT_READY,
        /** A relaunch happened less than {@link #RELAUNCH_FLOOR_MS} ago; try again later. */
        TOO_SOON,
        /** Start the activity. */
        RELAUNCH
    }

    private RelaunchPolicy() {
    }

    /**
     * @param deviceOwner     whether this app is the device owner
     * @param serviceAgeMs    how long ago the service was created, on a monotonic clock
     * @param dashboardAlive  whether a {@code KioskActivity} instance exists in this process
     * @param deviceReady     whether first-run setup is complete and the user is unlocked, the same
     *                        rule {@code BootReceiver} applies before starting the activity at boot
     * @param nowMs           wall-clock time
     * @param lastRelaunchMs  wall-clock time of the last relaunch this policy ordered, persisted
     *                        across processes, or 0 for never
     */
    static Verdict decide(boolean deviceOwner, long serviceAgeMs, boolean dashboardAlive,
            boolean deviceReady, long nowMs, long lastRelaunchMs) {
        if (!deviceOwner) {
            return Verdict.NOT_A_KIOSK;
        }
        if (serviceAgeMs < SETTLE_MS) {
            return Verdict.SETTLING;
        }
        if (dashboardAlive) {
            return Verdict.ON_SCREEN;
        }
        if (!deviceReady) {
            return Verdict.NOT_READY;
        }
        long sinceLast = nowMs - lastRelaunchMs;
        // A clock that went backwards (a time sync after boot) is treated as "long ago", the
        // opposite of RecyclePolicy's rule for the pressure floor. There a wrong guess costs one
        // skipped rebuild; here it would keep a kiosk off its screen.
        if (lastRelaunchMs > 0 && sinceLast >= 0 && sinceLast < RELAUNCH_FLOOR_MS) {
            return Verdict.TOO_SOON;
        }
        return Verdict.RELAUNCH;
    }
}
