/*
 * Copyright 2026 KiOSk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.kiosk.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

final class KioskConfig {
    private static final String PREFS = "kiosk";
    private static final String DASHBOARD_URL = "dashboard_url";
    private static final String DEVICE_ID = "device_id";
    private static final String MQTT_HOST = "mqtt_host";
    private static final String MQTT_PORT = "mqtt_port";
    private static final String MQTT_USERNAME = "mqtt_username";
    private static final String MQTT_PASSWORD = "mqtt_password";
    private static final String HTTP_PORT = "http_port";
    private static final String HTTP_ADMIN_PASSWORD = "http_admin_password";
    private static final String STATS_OVERLAY = "stats_overlay";
    private static final String AUTO_RECYCLE = "auto_recycle";
    private static final String RECYCLE_HOUR = "recycle_hour";
    private static final String RECYCLE_MINUTE = "recycle_minute";
    private static final String SETTINGS_SEQUENCE = "settings_sequence";
    private static final String LAUNCHER_SEQUENCE = "launcher_sequence";
    private static final String TELEMETRY_INTERVAL_SECONDS = "telemetry_interval_seconds";
    private static final String DETECT_FROZEN_PAGE = "detect_frozen_page";

    static final int DEFAULT_HTTP_PORT = 8080;
    /** The original fixed gesture, kept as the default so nothing changes until it is recorded. */
    static final String DEFAULT_SETTINGS_SEQUENCE = "BL,BL,BL,BL,BL,BL,BL,BL,BL";
    static final String DEFAULT_LAUNCHER_SEQUENCE = "BR,BR,BR,BR,BR,BR,BR,BR,BR";

    String dashboardUrl = "";
    String deviceId = "";
    String mqttHost = "";
    /**
     * 1883, the plain-TCP MQTT port. Was 8883 with a TLS flag; see MqttController for why TLS was
     * removed rather than left as an option that could not be configured.
     */
    int mqttPort = 1883;
    String mqttUsername = "";
    String mqttPassword = "";
    int httpPort = DEFAULT_HTTP_PORT;
    String httpAdminPassword = "";
    /**
     * Draws the live system-stats block over the dashboard. On by default because it exists to be
     * watched during the multi-week endurance test; it is a switch rather than a build-time choice
     * so turning it off later needs no reflash.
     */
    boolean statsOverlay = true;
    /**
     * Rebuilds the dashboard WebView overnight and under memory pressure. On by default: measured
     * on this hardware, a real Home Assistant dashboard grows until lmkd kills the renderer.
     */
    boolean autoRecycle = true;
    /** Hour of the day (0-23) for the scheduled dashboard recycle. */
    int recycleHour = RecyclePolicy.DEFAULT_QUIET_HOUR;
    int recycleMinute = RecyclePolicy.DEFAULT_QUIET_MINUTE;
    /** How often MQTT state is published. One of {@link TelemetryInterval#OPTIONS}. */
    int telemetryIntervalSeconds = TelemetryInterval.DEFAULT_SECONDS;
    /**
     * Reload the dashboard if its own rendered content stops changing for a long time, even though
     * nothing ever reported an error. Default on, but a heuristic and openly one: a legitimately
     * static dashboard, a single unchanging image with no live tiles, would look identical to a
     * frozen one by this measure, so the operator can turn it off. See
     * {@code KioskActivity#checkForFrozenPage} for exactly what is measured and why.
     */
    boolean detectFrozenPage = true;
    /** Corner-tap combination that opens this configuration screen. */
    String settingsSequence = DEFAULT_SETTINGS_SEQUENCE;
    /** Corner-tap combination that leaves the kiosk for the system launcher. */
    String launcherSequence = DEFAULT_LAUNCHER_SEQUENCE;

    static KioskConfig load(Context context) {
        Context storageContext = storageContext(context);
        SharedPreferences preferences = storageContext.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        SecretStore secrets = new SecretStore(storageContext);
        KioskConfig config = new KioskConfig();
        config.dashboardUrl = preferences.getString(DASHBOARD_URL, "").trim();
        config.deviceId = preferences.getString(DEVICE_ID, "").trim();
        if (config.deviceId.isEmpty()) {
            config.deviceId = "kiosk-" + UUID.randomUUID().toString().substring(0, 8);
        }
        config.mqttHost = preferences.getString(MQTT_HOST, "").trim();
        config.mqttPort = preferences.getInt(MQTT_PORT, 1883);
        config.mqttUsername = secrets.get(MQTT_USERNAME);
        config.mqttPassword = secrets.get(MQTT_PASSWORD);
        config.httpPort = preferences.getInt(HTTP_PORT, DEFAULT_HTTP_PORT);
        config.httpAdminPassword = secrets.get(HTTP_ADMIN_PASSWORD);
        config.statsOverlay = statsOverlayEnabled(context);
        config.autoRecycle = autoRecycleEnabled(context);
        config.recycleHour = recycleHourOf(context);
        config.recycleMinute = recycleMinuteOf(context);
        config.settingsSequence = preferences.getString(
                SETTINGS_SEQUENCE, DEFAULT_SETTINGS_SEQUENCE);
        config.launcherSequence = preferences.getString(
                LAUNCHER_SEQUENCE, DEFAULT_LAUNCHER_SEQUENCE);
        // Defensive against a hand-edited preferences file rather than against any code path in
        // this app, which only ever writes one of the four presets.
        config.telemetryIntervalSeconds = TelemetryInterval.clampOrDefault(
                preferences.getInt(TELEMETRY_INTERVAL_SECONDS, TelemetryInterval.DEFAULT_SECONDS));
        config.detectFrozenPage = preferences.getBoolean(DETECT_FROZEN_PAGE, true);
        return config;
    }

    void save(Context context) {
        Context storageContext = storageContext(context);
        storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(DASHBOARD_URL, dashboardUrl.trim())
                .putString(DEVICE_ID, deviceId.trim())
                .putString(MQTT_HOST, mqttHost.trim())
                .putInt(MQTT_PORT, mqttPort)
                .putInt(HTTP_PORT, httpPort)
                .putBoolean(STATS_OVERLAY, statsOverlay)
                .putBoolean(AUTO_RECYCLE, autoRecycle)
                .putInt(RECYCLE_HOUR, RecyclePolicy.clampHour(recycleHour))
                .putInt(RECYCLE_MINUTE, RecyclePolicy.clampMinute(recycleMinute))
                .putInt(TELEMETRY_INTERVAL_SECONDS,
                        TelemetryInterval.clampOrDefault(telemetryIntervalSeconds))
                .putBoolean(DETECT_FROZEN_PAGE, detectFrozenPage)
                .apply();

        SecretStore secrets = new SecretStore(storageContext);
        secrets.put(MQTT_USERNAME, mqttUsername);
        secrets.put(MQTT_PASSWORD, mqttPassword);
        secrets.put(HTTP_ADMIN_PASSWORD, httpAdminPassword);
    }

    /**
     * Reads just the overlay switch. The dashboard ticker consults it on every redraw, and going
     * through {@link #load} for that would decrypt every secret in {@link SecretStore} each time.
     */
    static boolean statsOverlayEnabled(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(STATS_OVERLAY, true);
    }

    /** Read on every sample, so it avoids {@link #load} and its SecretStore decryption. */
    static boolean autoRecycleEnabled(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(AUTO_RECYCLE, true);
    }

    /** Read on every sample alongside {@link #autoRecycleEnabled}, so it skips SecretStore too. */
    static int recycleHourOf(Context context) {
        return RecyclePolicy.clampHour(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(RECYCLE_HOUR, RecyclePolicy.DEFAULT_QUIET_HOUR));
    }

    /**
     * Escape sequences are written only here, never by {@link #save}.
     *
     * <p>They are recorded on a different screen from the one that saves everything else, so a
     * configuration screen built before a recording still holds the old value in memory; letting
     * its save write that back silently reverted a freshly recorded combination, which is exactly
     * what happened on 2026-08-17. Splitting the write makes the stale-overwrite impossible rather
     * than merely unlikely.
     */
    void saveEscapeSequences(Context context) {
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(SETTINGS_SEQUENCE, settingsSequence)
                .putString(LAUNCHER_SEQUENCE, launcherSequence)
                .apply();
    }

    static int recycleMinuteOf(Context context) {
        return RecyclePolicy.clampMinute(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(RECYCLE_MINUTE, RecyclePolicy.DEFAULT_QUIET_MINUTE));
    }

    private static Context storageContext(Context context) {
        return context.createDeviceProtectedStorageContext();
    }
}
