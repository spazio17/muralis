/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.app.Application;
import android.os.SystemClock;

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
    @Override
    public void onCreate() {
        super.onCreate();
        KioskRuntimeState.useClock(SystemClock::elapsedRealtime);
    }
}
