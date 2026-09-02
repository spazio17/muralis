/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.app.Application;
import android.os.SystemClock;
import android.util.Log;

/**
 * Process-wide setup that must happen before any component runs.
 *
 * <p>Currently one job: giving {@link KioskRuntimeState} a clock that advances while the device is
 * suspended. That class defaults to {@code System.nanoTime} so it can stay free of Android imports
 * and host-testable, but nanoTime freezes across a suspend, and the nightly restart schedule and
 * every "ago" figure in telemetry ride on it. The deployed wall panel holds a wake lock and never
 * suspends, so this never mattered there; a customer's tablet configured differently is exactly
 * the install that must not silently skip its nightly restarts.
 *
 * <p>Application.onCreate rather than a service or activity, because both record into
 * {@code KioskRuntimeState} and the timestamps are relative: readings from two different clocks
 * mixed in one field produce garbage elapsed times, so the clock has to be installed before the
 * first reading anyone takes.
 */
public final class MuralisApplication extends Application {
    private static final String TAG = "MuralisApp";

    @Override
    public void onCreate() {
        super.onCreate();
        KioskRuntimeState.useClock(SystemClock::elapsedRealtime);
        // On a device-owner panel the service runs whenever this process does, whatever started
        // the process. The case that needs this is a force-stop, which cancels every alarm, job
        // and sticky service the app owns, but the system still starts this process on its own
        // account, for its attempt to relaunch Muralis as HOME or for the lock-task-exiting
        // broadcast to the admin receiver, and that process is the only foothold left. The
        // service then finds no dashboard and brings it back
        // (RelaunchPolicy). Ordinary installs are untouched: they start the service from the
        // activity, and a closed Muralis stays closed. Starting a foreground service from here is
        // unrestricted below Android 12 and exempt for device owners from Android 12 on; the catch
        // is for any device that disagrees, where the boot receiver and the activity still start it.
        if (KioskDeviceAdminReceiver.isDeviceOwner(this)) {
            try {
                KioskService.start(this);
            } catch (IllegalStateException refused) {
                Log.w(TAG, "Could not start the service from the process start", refused);
            }
        }
    }
}
