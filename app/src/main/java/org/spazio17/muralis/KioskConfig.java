/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

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
    private static final String MEMORY_BASELINE = "memory_baseline";
    private static final String SETTINGS_SEQUENCE = "settings_sequence";
    private static final String LAUNCHER_SEQUENCE = "launcher_sequence";
    private static final String TELEMETRY_INTERVAL_SECONDS = "telemetry_interval_seconds";

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
    /** How often MQTT state is published. One of {@link TelemetryInterval#OPTIONS}. */
    int telemetryIntervalSeconds = TelemetryInterval.DEFAULT_SECONDS;
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
            // Persisted at once, not just returned. This identifier is the MQTT topic prefix and
            // the unique_id of every Home Assistant entity, and load() is called independently by
            // MqttController, the stats builder and the admin page: minting it per-call without
            // storing it gave each of them a *different* id, so the topics being published to and
            // the id being reported did not match, and every restart created a fresh Home
            // Assistant device. commit() rather than apply() because MqttController reads it back
            // immediately to build its topic prefix.
            config.deviceId = "kiosk-" + UUID.randomUUID().toString().substring(0, 8);
            preferences.edit().putString(DEVICE_ID, config.deviceId).commit();
        }
        config.mqttHost = preferences.getString(MQTT_HOST, "").trim();
        config.mqttPort = preferences.getInt(MQTT_PORT, 1883);
        config.mqttUsername = secrets.get(MQTT_USERNAME);
        config.mqttPassword = secrets.get(MQTT_PASSWORD);
        config.httpPort = preferences.getInt(HTTP_PORT, DEFAULT_HTTP_PORT);
        config.httpAdminPassword = secrets.get(HTTP_ADMIN_PASSWORD);
        config.statsOverlay = statsOverlayEnabled(context);
        config.settingsSequence = preferences.getString(
                SETTINGS_SEQUENCE, DEFAULT_SETTINGS_SEQUENCE);
        config.launcherSequence = preferences.getString(
                LAUNCHER_SEQUENCE, DEFAULT_LAUNCHER_SEQUENCE);
        // Defensive against a hand-edited preferences file rather than against any code path in
        // this app, which only ever writes one of the four presets.
        config.telemetryIntervalSeconds = TelemetryInterval.clampOrDefault(
                preferences.getInt(TELEMETRY_INTERVAL_SECONDS, TelemetryInterval.DEFAULT_SECONDS));
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
                .putInt(TELEMETRY_INTERVAL_SECONDS,
                        TelemetryInterval.clampOrDefault(telemetryIntervalSeconds))
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

    /**
     * The learned memory model, read on every sample and written whenever a dashboard generation
     * ends. Kept out of {@link #load} and {@link #save} on purpose: those two write every field, and
     * a stale snapshot round-tripping through them would discard a fortnight of learning. Same
     * reasoning as {@link #saveEscapeSequences}.
     */
    static MemoryBaseline memoryBaselineOf(Context context) {
        return MemoryBaseline.fromStorage(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MEMORY_BASELINE, null));
    }

    static void saveMemoryBaseline(Context context, MemoryBaseline baseline) {
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MEMORY_BASELINE, baseline.toStorage())
                .apply();
    }

    /** Read on every telemetry tick, so it skips {@link #load} and its SecretStore decryption. */
    static int telemetryIntervalSecondsOf(Context context) {
        return TelemetryInterval.clampOrDefault(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(TELEMETRY_INTERVAL_SECONDS, TelemetryInterval.DEFAULT_SECONDS));
    }

    private static Context storageContext(Context context) {
        return context.createDeviceProtectedStorageContext();
    }
}
