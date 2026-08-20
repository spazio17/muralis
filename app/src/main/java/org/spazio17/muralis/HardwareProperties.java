/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.os.Build;
import android.os.CpuUsageInfo;
import android.os.HardwarePropertiesManager;
import android.util.Log;

/**
 * CPU load and chip temperatures read through the hardware HAL instead of procfs.
 *
 * <p><b>Why this exists.</b> On stock Android an ordinary app is in the {@code untrusted_app} SELinux
 * domain, and the kernel refuses {@code /proc/stat}, {@code /proc/loadavg} and
 * {@code /sys/class/thermal} outright, verified on the MediaPad on 2026-08-19. That left the overlay
 * showing {@code CPU --%} and {@code TEMP --cpu --gpu} with no way back, because Android offers no
 * public API for system-wide CPU usage.
 *
 * <p>{@link HardwarePropertiesManager} is the exception, and it only became usable once this app was
 * provisioned as device owner: it is public API from API 24, but it throws {@link SecurityException}
 * unless the caller is a device or profile owner, or holds the signature {@code DEVICE_POWER}
 * permission. The privileged ROM build held {@code DEVICE_POWER} and so never needed this route; an
 * app build reaches the same data through ownership instead.
 *
 * <p>Everything here is best-effort. The HAL is optional and many devices implement none of it, in
 * which case the readings stay unknown exactly as they are now, and the overlay keeps showing
 * {@code --}. That is the same fail-soft contract the rest of the stats path follows: an unavailable
 * figure is unknown, never zero and never a crash.
 */
final class HardwareProperties {

    private static final String TAG = "MuralisHwProps";

    private final HardwarePropertiesManager manager;

    /**
     * Whether the HAL has been found unusable, so it is asked once and then left alone. A device with
     * no thermal HAL would otherwise throw on every two-second sample for the life of the panel, the
     * same waste the procfs read latch exists to avoid.
     */
    private boolean unavailable;

    /** Cumulative per-core times from the previous sample; CPU load is a delta, not an instant. */
    private long[] previousActive;
    private long[] previousTotal;

    HardwareProperties(Context context) {
        HardwarePropertiesManager found = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                found = context.getSystemService(HardwarePropertiesManager.class);
            } catch (RuntimeException missing) {
                Log.w(TAG, "No hardware properties service", missing);
            }
        }
        manager = found;
        unavailable = manager == null;
    }

    /**
     * Busy percentage across all cores since the previous call, or {@code NaN}.
     *
     * <p>Returns {@code NaN} on the first call by design: the figures are cumulative, so the first
     * sample establishes a baseline and has nothing to compare against. Reporting 0% there would
     * claim an idle CPU rather than an unknown one.
     */
    double cpuBusyPercent() {
        if (unavailable) {
            return Double.NaN;
        }
        CpuUsageInfo[] cores;
        try {
            cores = manager.getCpuUsages();
        } catch (SecurityException notOwner) {
            // Not device owner, or ownership was removed. Permanent for this process.
            Log.w(TAG, "CPU usage needs device-owner status; giving up on the HAL", notOwner);
            unavailable = true;
            return Double.NaN;
        } catch (RuntimeException | LinkageError noHal) {
            Log.w(TAG, "Hardware CPU usage unavailable", noHal);
            unavailable = true;
            return Double.NaN;
        }
        if (cores == null || cores.length == 0) {
            return Double.NaN;
        }

        long[] active = new long[cores.length];
        long[] total = new long[cores.length];
        for (int i = 0; i < cores.length; i++) {
            // A null entry means that core is offline, which is normal on a big.LITTLE SoC. Treat it
            // as zero rather than skipping, so the array indices keep lining up with the last sample.
            active[i] = cores[i] == null ? 0 : cores[i].getActive();
            total[i] = cores[i] == null ? 0 : cores[i].getTotal();
        }

        double busy = Double.NaN;
        if (previousActive != null && previousActive.length == active.length) {
            long activeDelta = 0;
            long totalDelta = 0;
            for (int i = 0; i < active.length; i++) {
                // Cores coming online, or the HAL resetting its counters, can make a delta negative.
                // Ignore those cores rather than letting one produce a nonsense percentage.
                long a = active[i] - previousActive[i];
                long t = total[i] - previousTotal[i];
                if (a >= 0 && t > 0) {
                    activeDelta += a;
                    totalDelta += t;
                }
            }
            if (totalDelta > 0) {
                busy = Math.max(0.0, Math.min(100.0, 100.0 * activeDelta / totalDelta));
            }
        }
        previousActive = active;
        previousTotal = total;
        return busy;
    }

    /** Current CPU temperature in Celsius, or {@code NaN}. */
    double cpuTemperatureC() {
        return temperature(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU);
    }

    /** Current GPU temperature in Celsius, or {@code NaN}. */
    double gpuTemperatureC() {
        return temperature(HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU);
    }

    /**
     * Hottest current reading of one device type, or {@code NaN}.
     *
     * <p>Hottest rather than first or mean: a SoC reports several zones and the one that matters for a
     * fanless wall panel is whichever is closest to throttling. Android documents unavailable zones as
     * {@code Float.NaN} and some HALs return an absolute-zero sentinel instead, so both are filtered.
     */
    private double temperature(int deviceType) {
        if (unavailable) {
            return Double.NaN;
        }
        float[] readings;
        try {
            readings = manager.getDeviceTemperatures(deviceType,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
        } catch (SecurityException notOwner) {
            Log.w(TAG, "Temperatures need device-owner status; giving up on the HAL", notOwner);
            unavailable = true;
            return Double.NaN;
        } catch (RuntimeException | LinkageError noHal) {
            Log.w(TAG, "Hardware temperatures unavailable", noHal);
            unavailable = true;
            return Double.NaN;
        }
        if (readings == null) {
            return Double.NaN;
        }
        double hottest = Double.NaN;
        for (float reading : readings) {
            if (Float.isNaN(reading) || reading <= -273.0f) {
                continue;
            }
            if (Double.isNaN(hottest) || reading > hottest) {
                hottest = reading;
            }
        }
        return hottest;
    }
}
