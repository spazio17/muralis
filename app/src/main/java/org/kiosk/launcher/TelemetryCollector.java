/*
 * Copyright 2026 KiOSk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.kiosk.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

final class TelemetryCollector {
    private final Context context;

    TelemetryCollector(Context context) {
        this.context = context.getApplicationContext();
    }

    JSONObject snapshot() {
        JSONObject root = new JSONObject();
        try {
            root.put("schema", 1);
            root.put("uptime_ms", SystemClock.elapsedRealtime());
            root.put("battery", batterySnapshot());
            root.put("memory", memorySnapshot());
            root.put("storage", storageSnapshot());
            root.put("network", networkSnapshot());
            // PowerManager.getCurrentThermalStatus is API 29. The contract says an unsupported
            // counter is JSON null rather than absent or zero, so a pre-29 device (the MediaPad is
            // API 26) reports null and every consumer keeps working.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                PowerManager power = context.getSystemService(PowerManager.class);
                root.put("thermal_status",
                        power == null ? JSONObject.NULL : power.getCurrentThermalStatus());
            } else {
                root.put("thermal_status", JSONObject.NULL);
            }
            root.put("load_average", readLoadAverage());
            // Kernel counters and WebView health come from the shared sampler rather than being
            // re-read here, so MQTT, the HTTP stats surface and the on-screen overlay can never
            // disagree about what the tablet is doing.
            root.put("system", systemSnapshot());
            root.put("runtime", runtimeSnapshot());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return root;
    }

    /**
     * The handful of fields worth publishing the moment they change, rather than waiting for the
     * next scheduled tick: battery percent, charging state, low-memory and thermal status. Not the
     * full {@link #snapshot()}, deliberately: this is read every two seconds by the change-watch
     * loop in {@link KioskService}, and {@link #snapshot()} also queries storage and network state,
     * which are not among the watched fields and are not cheap enough to read fifteen times more
     * often than before for no reason.
     */
    static final class Watch {
        final double batteryPercent;
        final int batteryStatus;
        final boolean memoryLow;
        /** Null when unsupported on this API level, same convention as the published field. */
        final Integer thermalStatus;

        Watch(double batteryPercent, int batteryStatus, boolean memoryLow, Integer thermalStatus) {
            this.batteryPercent = batteryPercent;
            this.batteryStatus = batteryStatus;
            this.memoryLow = memoryLow;
            this.thermalStatus = thermalStatus;
        }

        /**
         * Whether this reading differs meaningfully from the last one published. Battery percent
         * compares by whole number, matching what every surface actually displays; without that, a
         * reading wobbling around Xx.5 would trigger a publish every two seconds forever.
         */
        boolean differsFrom(Watch previous) {
            if (previous == null) {
                return true;
            }
            if (Math.round(batteryPercent) != Math.round(previous.batteryPercent)) {
                return true;
            }
            if (batteryStatus != previous.batteryStatus) {
                return true;
            }
            if (memoryLow != previous.memoryLow) {
                return true;
            }
            return !java.util.Objects.equals(thermalStatus, previous.thermalStatus);
        }
    }

    Watch readWatch() {
        Intent state = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        double percent = Double.NaN;
        int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
        if (state != null) {
            int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (scale > 0) {
                percent = level * 100.0 / scale;
            }
            status = state.getIntExtra(
                    BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        Integer thermal = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null) {
                thermal = power.getCurrentThermalStatus();
            }
        }
        return new Watch(percent, status, info.lowMemory, thermal);
    }

    private JSONObject batterySnapshot() throws JSONException {
        Intent state = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject battery = new JSONObject();
        if (state == null) {
            return battery;
        }

        int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        battery.put("percent", scale > 0 ? level * 100.0 / scale : JSONObject.NULL);
        battery.put("status", state.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN));
        battery.put("health", state.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN));
        battery.put("plugged", state.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
        SystemStats.RuntimeFacts batteryFacts = KioskRuntimeState.lastFacts();
        battery.put("charge_state", batteryFacts == null
                ? JSONObject.NULL : SystemStats.chargeStateLabel(batteryFacts));
        battery.put("temperature_c",
                number(state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0));
        battery.put("voltage_mv", state.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
        BatteryManager manager = context.getSystemService(BatteryManager.class);
        battery.put("charge_counter_uah", nullableBatteryProperty(
                manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
        battery.put("current_now_ua", nullableBatteryProperty(
                manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        battery.put("current_average_ua", nullableBatteryProperty(
                manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE));
        battery.put("energy_counter_nwh", nullableBatteryProperty(
                manager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER));
        return battery;
    }

    private JSONObject memorySnapshot() throws JSONException {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        JSONObject memory = new JSONObject();
        memory.put("available_bytes", info.availMem);
        memory.put("total_bytes", info.totalMem);
        memory.put("low", info.lowMemory);
        memory.put("threshold_bytes", info.threshold);
        memory.put("process_pss_kib", Debug.getPss());
        return memory;
    }

    /** The latest shared kernel sample, or an empty object before the first one is taken. */
    static JSONObject systemSnapshot() throws JSONException {
        JSONObject system = new JSONObject();
        SystemStats.Sample sample = KioskRuntimeState.lastSample();
        if (sample == null) {
            return system;
        }
        system.put("cpu_busy_percent", number(sample.cpuBusyPercent));
        system.put("cpu_max_frequency_khz", number(sample.cpuMaxFrequencyKhz));
        system.put("mem_total_kb", number(sample.memTotalKb));
        system.put("mem_available_kb", number(sample.memAvailableKb));
        system.put("mem_used_kb", number(sample.memUsedKb()));
        system.put("swap_total_kb", number(sample.swapTotalKb));
        system.put("swap_used_kb", number(sample.swapUsedKb()));
        system.put("cpu_temperature_c", number(sample.cpuTemperatureC));
        system.put("gpu_temperature_c", number(sample.gpuTemperatureC));
        if (sample.loadAverage != null) {
            JSONArray load = new JSONArray();
            for (double value : sample.loadAverage) {
                load.put(number(value));
            }
            system.put("load_average", load);
        } else {
            system.put("load_average", JSONObject.NULL);
        }
        return system;
    }

    /**
     * WebView health. The renderer runs under its own uid and /proc is mounted {@code hidepid=2},
     * so its memory is not observable from here, but its *deaths* are, and for a dashboard that has
     * to survive weeks unattended that is the number that actually matters.
     */
    static JSONObject runtimeSnapshot() throws JSONException {
        JSONObject runtime = new JSONObject();
        runtime.put("renderer_deaths", KioskRuntimeState.rendererDeaths());
        runtime.put("last_renderer_death_ago_ms",
                number(KioskRuntimeState.lastRendererDeathAgoMs()));
        runtime.put("last_page_finished_ago_ms",
                number(KioskRuntimeState.lastPageFinishedAgoMs()));
        runtime.put("last_page_error_ago_ms", number(KioskRuntimeState.lastPageErrorAgoMs()));
        runtime.put("last_page_error", KioskRuntimeState.lastPageError());
        runtime.put("last_page_url", KioskRuntimeState.lastPageUrl());
        runtime.put("recycles", KioskRuntimeState.recycles());
        runtime.put("last_recycle_ago_ms", number(KioskRuntimeState.lastRecycleAgoMs()));
        runtime.put("last_recycle_reason", KioskRuntimeState.lastRecycleReason());
        return runtime;
    }

    private static Object number(long value) {
        return value == SystemStats.UNKNOWN ? JSONObject.NULL : value;
    }

    /**
     * Maps any non-finite reading to JSON null, matching this project's rule that a value the
     * device could not measure is null and never a fabricated number.
     *
     * <p><b>Every floating-point value that reaches JSON must go through here.</b> {@code org.json}
     * rejects NaN and both infinities, {@code JSONObject.put(String,double)} and
     * {@code JSONArray.put(double)} throw {@link org.json.JSONException} on them. Skipping this
     * helper has crashed the app twice: {@code readLoadAverage} on 2026-08-07, and the sensor dump
     * on 2026-08-18, when a QTI sensor on the TB-X505F reported NaN and took the whole kiosk down
     * (it restarts, being persistent, but the dashboard blinks out). That sensor dump has since been
     * removed entirely, nothing consumed it, but the rule stands for every remaining reading.
     */
    private static Object number(double value) {
        return Double.isFinite(value) ? (Object) value : JSONObject.NULL;
    }

    private JSONObject storageSnapshot() throws JSONException {
        JSONObject storage = new JSONObject();
        try {
            // Measure the data filesystem, not this app's credential-encrypted
            // directory. A persistent app can start before Android creates
            // /data/user/0/<package> on the first boot after a data format.
            StatFs stats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            storage.put("available_bytes", stats.getAvailableBytes());
            storage.put("total_bytes", stats.getTotalBytes());
        } catch (IllegalArgumentException unavailable) {
            // Telemetry must degrade to unknown rather than crash the kiosk.
            storage.put("available_bytes", JSONObject.NULL);
            storage.put("total_bytes", JSONObject.NULL);
        }
        return storage;
    }

    private JSONObject networkSnapshot() throws JSONException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        JSONObject network = new JSONObject();
        SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
        network.put("connected", capabilities != null);
        // Outside the Wi-Fi branch on purpose: an Ethernet or tethered panel has an address too.
        network.put("ip_address", facts == null || facts.ipAddress.isEmpty()
                ? JSONObject.NULL : facts.ipAddress);
        network.put("metered", manager.isActiveNetworkMetered());
        if (capabilities != null) {
            network.put("validated", capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            network.put("wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            network.put("ethernet", capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET));
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiInfo wifi = context.getSystemService(WifiManager.class)
                        .getConnectionInfo();
                network.put("wifi_rssi_dbm", wifi.getRssi());
                network.put("wifi_signal_level",
                        WifiManager.calculateSignalLevel(wifi.getRssi(), 5));
                network.put("wifi_link_speed_mbps", wifi.getLinkSpeed());
                network.put("wifi_frequency_mhz", wifi.getFrequency());
            }
        }
        return network;
    }

    private static Object nullableBatteryProperty(BatteryManager manager, int property) {
        long value = manager.getLongProperty(property);
        return value == Long.MIN_VALUE ? JSONObject.NULL : value;
    }

    private static Object readLoadAverage() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"))) {
            String line = reader.readLine();
            if (line != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length >= 3) {
                    JSONArray load = new JSONArray();
                    load.put(number(Double.parseDouble(fields[0])));
                    load.put(number(Double.parseDouble(fields[1])));
                    load.put(number(Double.parseDouble(fields[2])));
                    return load;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // A missing or SELinux-protected proc entry, or an unparsable line, becomes JSON null.
            // JSONException is deliberately absent: since every value goes through number(), the
            // puts here cannot throw it, and listing it would not compile.
        }
        return JSONObject.NULL;
    }
}
