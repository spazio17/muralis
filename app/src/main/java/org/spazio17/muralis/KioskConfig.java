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
    private static final String ORIENTATION = "orientation";
    private static final String DISPLAY_OFF_METHOD = "display_off_method";
    /**
     * Retired 2026-08-29, when the two-way portrait switch became the three-way orientation
     * setting. Read only by the migration in {@link #orientationOf} and removed by the first
     * orientation write; never written any more.
     */
    private static final String PORTRAIT = "portrait";
    private static final String KIOSK_STOPPED = "kiosk_stopped";
    private static final String VISUAL_OFF_BOOT_COUNT = "visual_off_boot_count";
    private static final String LAST_DASHBOARD_RELAUNCH_AT = "last_dashboard_relaunch_at";
    private static final String WEB_ADMIN_ENABLED = "web_admin_enabled";
    private static final String LAST_NIGHTLY_RESTART_DAY = "last_nightly_restart_day";
    private static final String SETTINGS_SEQUENCE = "settings_sequence";
    private static final String LAUNCHER_SEQUENCE = "launcher_sequence";

    static final int DEFAULT_HTTP_PORT = 8080;

    /** Follow the accelerometer around all four ways up, until the operator fixes one. */
    static final String ORIENTATION_AUTO = "auto";
    static final String ORIENTATION_LANDSCAPE = "landscape";
    static final String ORIENTATION_PORTRAIT = "portrait";

    // A loaded KioskConfig is a READ snapshot and a display model, never a write vehicle. The
    // fields stay mutable because the screens overlay half-typed values on one for redisplay, but
    // nothing can persist a whole object: writes go through edit(), which touches only the fields
    // explicitly set. The whole-object save() this replaces caused the same bug three separate
    // times, a stale snapshot silently reverting whatever another surface changed meanwhile, and
    // the third time it was reintroduced proved the documentation rule was not enough: the safe
    // pattern has to be the only pattern the API can express.
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
     * How the panel is mounted: {@link #ORIENTATION_LANDSCAPE}, {@link #ORIENTATION_PORTRAIT},
     * or {@link #ORIENTATION_AUTO}, which follows the accelerometer until the operator picks a
     * fixed one, the same shape auto-brightness has with the light sensor. Auto by default,
     * because a fresh install is a device in somebody's hands, not yet a panel on a wall.
     *
     * <p>The fixed states are still the *sensor* variants rather than truly fixed ones, so a
     * panel screwed to the wall the other way up renders the right way round without a second
     * setting for it; what they never do is flip between landscape and portrait on their own.
     */
    String orientation = ORIENTATION_AUTO;
    /**
     * How {@code display.visual_off} darkens the panel: {@link DisplayOffPolicy#AUTO}, a real
     * sleep where it can be trusted and the black film otherwise; {@link DisplayOffPolicy#SLEEP};
     * or {@link DisplayOffPolicy#FILM}. Automatic by default, which on a device-owner panel on
     * mains means the backlight really goes off, as decided on 2026-09-08. See DisplayOffPolicy
     * for the whole rule and for how a sleep that ended badly turns this into the film.
     */
    String displayOffMethod = DisplayOffPolicy.AUTO;
    /**
     * Whether the web admin is allowed to serve at all, independent of the password: turning the
     * surface off must not cost the operator their stored password, and turning it back on must
     * not require retyping one. Enabled by default; the no-password fail-closed rule in
     * HttpAdminServer.start still applies on top.
     */
    boolean webAdminEnabled = true;
    /**
     * Corner-tap combination that opens this configuration screen. Empty until the user records
     * one. There is deliberately no compiled-in default any more (the fixed BL x9 / BR x9 pair
     * was retired 2026-08-25): a default that ships in the binary also ships in the
     * documentation, at which point it unlocks every panel in the world, and nine taps was too
     * much to hand a new user anyway. The first-start wizard in KioskActivity refuses to hand
     * the screen to the kiosk until both combinations exist.
     */
    String settingsSequence = "";
    /** Corner-tap combination that leaves the kiosk for the system launcher. Empty until recorded. */
    String launcherSequence = "";

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
        String storedUsername = secrets.getOrNull(MQTT_USERNAME);
        config.mqttUsername = storedUsername == null ? "" : storedUsername;
        String storedPassword = secrets.getOrNull(MQTT_PASSWORD);
        config.mqttPassword = storedPassword == null ? "" : storedPassword;
        config.httpPort = preferences.getInt(HTTP_PORT, DEFAULT_HTTP_PORT);
        String storedAdminPassword = secrets.getOrNull(HTTP_ADMIN_PASSWORD);
        config.httpAdminPassword = storedAdminPassword == null ? "" : storedAdminPassword;
        config.webAdminEnabled = preferences.getBoolean(WEB_ADMIN_ENABLED, true);
        config.statsOverlay = statsOverlayEnabled(context);
        config.orientation = orientationOf(context);
        config.displayOffMethod = displayOffMethodOf(context);
        config.settingsSequence = preferences.getString(SETTINGS_SEQUENCE, "");
        config.launcherSequence = preferences.getString(LAUNCHER_SEQUENCE, "");
        return config;
    }

    /**
     * True once both corner-tap combinations exist and parse as valid sequences, whether they
     * were recorded on the glass or typed into the web admin. Until then KioskActivity shows the
     * first-start wizard instead of a dashboard and never engages lock task: a pinned screen with
     * no recorded way out is a bricked panel, so like visual_off this fails toward an exit.
     */
    boolean escapeSequencesConfigured() {
        return EscapeSequence.isValid(EscapeSequence.parse(settingsSequence))
                && EscapeSequence.isValid(EscapeSequence.parse(launcherSequence));
    }

    /** The only way to write settings; see the comment on the fields above. */
    static Editor edit(Context context) {
        return new Editor(storageContext(context));
    }

    /**
     * Writes exactly the fields that were set on it and nothing else, so a writer cannot revert a
     * field it never meant to touch, whatever snapshot its screen was built from.
     */
    static final class Editor {
        private final Context storageContext;
        private final SharedPreferences.Editor plain;
        /** Secrets queued for {@link #apply}; LinkedHashMap so writes land in call order. */
        private final java.util.LinkedHashMap<String, String> secrets =
                new java.util.LinkedHashMap<>();

        private Editor(Context storageContext) {
            this.storageContext = storageContext;
            plain = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        }

        Editor dashboardUrl(String value) {
            plain.putString(DASHBOARD_URL, value.trim());
            return this;
        }

        Editor deviceId(String value) {
            plain.putString(DEVICE_ID, value.trim());
            return this;
        }

        Editor mqttHost(String value) {
            plain.putString(MQTT_HOST, value.trim());
            return this;
        }

        Editor mqttPort(int value) {
            plain.putInt(MQTT_PORT, value);
            return this;
        }

        Editor httpPort(int value) {
            plain.putInt(HTTP_PORT, value);
            return this;
        }

        Editor statsOverlay(boolean value) {
            plain.putBoolean(STATS_OVERLAY, value);
            return this;
        }

        Editor orientation(String value) {
            plain.putString(ORIENTATION, value);
            // Completes the 2026-08-29 migration: the retired boolean stays readable until the
            // first orientation write, then nothing is left to migrate.
            plain.remove(PORTRAIT);
            return this;
        }

        Editor displayOffMethod(String value) {
            plain.putString(DISPLAY_OFF_METHOD, value);
            return this;
        }

        Editor kioskStopped(boolean value) {
            plain.putBoolean(KIOSK_STOPPED, value);
            return this;
        }

        Editor webAdminEnabled(boolean value) {
            plain.putBoolean(WEB_ADMIN_ENABLED, value);
            return this;
        }

        Editor mqttUsername(String value) {
            secrets.put(MQTT_USERNAME, value);
            return this;
        }

        Editor mqttPassword(String value) {
            secrets.put(MQTT_PASSWORD, value);
            return this;
        }

        Editor httpAdminPassword(String value) {
            secrets.put(HTTP_ADMIN_PASSWORD, value);
            return this;
        }

        void apply() {
            plain.apply();
            if (secrets.isEmpty()) {
                return;
            }
            SecretStore store = new SecretStore(storageContext);
            for (java.util.Map.Entry<String, String> secret : secrets.entrySet()) {
                // Writing an empty string is a delete. An empty value arriving while the stored
                // secret is unreadable is almost certainly the echo of that unreadable read, a
                // form prefilled blank because the Keystore was briefly unavailable, so it is
                // skipped rather than allowed to destroy the credential. Nothing clears a
                // stored secret any more: the web admin's on/off is its own flag now, and the
                // password fields only ever set a new value.
                if (secret.getValue().isEmpty() && store.getOrNull(secret.getKey()) == null) {
                    continue;
                }
                store.put(secret.getKey(), secret.getValue());
            }
        }
    }

    /**
     * Narrow reader for the display-off method, read on every {@code display.visual_off} and by
     * the live-sync polls. An unknown stored spelling reads as automatic rather than failing: a
     * panel must always have some way to go dark.
     */
    static String displayOffMethodOf(Context context) {
        String stored = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(DISPLAY_OFF_METHOD, DisplayOffPolicy.AUTO);
        return DisplayOffPolicy.isMethod(stored) ? stored : DisplayOffPolicy.AUTO;
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
     * Narrow reader for the orientation, so callers that only need this one value do not load
     * and risk re-saving a whole snapshot. Same reasoning as {@link #statsOverlayEnabled}.
     *
     * <p>Migration lives here and only here: the setting was a "portrait" boolean until
     * 2026-08-29, so a stored boolean is read as the fixed orientation it meant and an installed
     * panel keeps its mount. Only an install that never stored either key gets the
     * follow-the-sensor default. Anything unrecognised also falls to auto, which is the value
     * that never strands a device sideways.
     */
    static String orientationOf(Context context) {
        SharedPreferences preferences = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = preferences.getString(ORIENTATION, null);
        if (ORIENTATION_LANDSCAPE.equals(stored) || ORIENTATION_PORTRAIT.equals(stored)
                || ORIENTATION_AUTO.equals(stored)) {
            return stored;
        }
        if (preferences.contains(PORTRAIT)) {
            return preferences.getBoolean(PORTRAIT, false)
                    ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        }
        return ORIENTATION_AUTO;
    }

    /**
     * Whether the panel was deliberately blanked with kiosk.stop and has not been started since.
     *
     * <p>Persisted, not process state. As a field in the activity it evaporated on the nightly
     * restart, so a panel stopped in the evening woke up rendering the dashboard at 04:xx with
     * nobody told, and the pressure rebuild reset it the same way. A stop is an operator's
     * decision and holds until kiosk.start or a new URL, whatever housekeeping runs in between.
     */
    static boolean kioskStopped(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KIOSK_STOPPED, false);
    }

    /**
     * The boot in which display.visual_off was engaged, or -1 while it is not engaged.
     *
     * <p>Stored as the boot count rather than a boolean so a reboot cannot leave the panel dark:
     * the value only counts when it was written in the current boot. That makes the blackout
     * survive the nightly restart and the pressure rebuild, which are process-level, and
     * invalidate itself across a reboot by construction, with no cleanup step that could be
     * missed. Decided 2026-08-25, after the nightly restart lit up a panel blanked with the
     * Display off button: kiosk.stop already survived (see {@link #kioskStopped}), visual_off
     * did not.
     */
    static int visualOffBootCount(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(VISUAL_OFF_BOOT_COUNT, -1);
    }

    /** Wall-clock time of the last relaunch {@link RelaunchPolicy} ordered, or 0 for never. */
    static long lastDashboardRelaunchAt(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(LAST_DASHBOARD_RELAUNCH_AT, 0L);
    }

    static void recordDashboardRelaunch(Context context, long atMs) {
        // commit, not apply: the floor this feeds is meant to survive the very kill that follows a
        // relaunch, and an asynchronous write may not have landed by then.
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LAST_DASHBOARD_RELAUNCH_AT, atMs)
                .commit();
    }

    static void recordVisualOffBootCount(Context context, int bootCount) {
        // commit, not apply: the nightly restart ends in System.exit, which does not wait for
        // asynchronous preference writes, and surviving exactly that restart is this field's job.
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(VISUAL_OFF_BOOT_COUNT, bootCount)
                .commit();
    }

    /**
     * Narrow reader for the web admin flag: the tablet's status line follows it on the live-sync
     * poll, and going through {@link #load} for that would decrypt every secret per tick. Same
     * reasoning as {@link #statsOverlayEnabled}.
     */
    static boolean webAdminEnabled(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(WEB_ADMIN_ENABLED, true);
    }

    /**
     * Escape sequences are written only here and are deliberately absent from {@link Editor}.
     *
     * <p>They are recorded on a different screen from the one that saves everything else, and
     * before {@link Editor} existed, a whole-object save from a screen built before a recording
     * silently reverted a freshly recorded combination, which is exactly what happened on
     * 2026-08-17. The Editor pattern generalises this method's lesson; the method itself stays so
     * the recorder keeps one obvious write path for its pair of fields.
     */
    void saveEscapeSequences(Context context) {
        // commit, not apply: these two strings are the precondition for everything irreversible the
        // device owner does. The wizard's last save is followed within milliseconds by
        // applyKioskPolicy pinning HOME, blocking safe mode and starting lock task, all of which the
        // system persists at once. An asynchronous write here could lose the combinations to a kill
        // or a power cut in that window and leave a panel pinned with no recorded way out, which is
        // the one state this app must never reach. Synchronous, on a button press, costs nothing.
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(SETTINGS_SEQUENCE, settingsSequence)
                .putString(LAUNCHER_SEQUENCE, launcherSequence)
                .commit();
    }

    /**
     * The local date of the last nightly restart, as a day number, or -1 if there has never been one.
     *
     * <p>A date rather than an elapsed time, because the nightly pass now restarts the whole process
     * and any monotonic "time since the last one" resets with it. A calendar day is also the honest
     * expression of the rule: once a night, whatever else has happened to the panel in between. The
     * previous twelve-hour floor meant a panel restarted for any other reason at 20:00 silently
     * skipped that night's clean.
     *
     * <p>Written with {@code commit()}, not {@code apply()}, and that is load-bearing: the caller
     * calls {@link System#exit} moments later, and an asynchronous write would be lost, leaving the
     * day unrecorded, so the pass fires again on the next tick after the restart, forever.
     *
     * <p>Kept out of {@link #load} and {@link Editor} entirely: it is bookkeeping owned by the
     * recycle pass, not a setting any surface should be able to touch.
     */
    static long lastNightlyRestartDay(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(LAST_NIGHTLY_RESTART_DAY, -1L);
    }

    static void recordNightlyRestartDay(Context context, long epochDay) {
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LAST_NIGHTLY_RESTART_DAY, epochDay)
                .commit();
    }

    /**
     * Reads just the device id, skipping {@link #load} and its three Keystore decryptions.
     *
     * <p>Needed because the recycle schedule is derived from this value on every sampler tick, and
     * going through {@code load()} to reach one immutable string meant constructing a
     * {@link SecretStore} and decrypting the broker username, the broker password and the admin
     * password, twice a second, forever. Same reasoning as {@link #statsOverlayEnabled}.
     *
     * <p>Returns empty rather than minting an id, unlike {@code load()}: minting writes to disk with
     * {@code commit()}, and doing that from the sampler thread is not this method's business.
     * RecyclePolicy.scheduledMinuteOf treats empty as minute zero, which is the un-spread default,
     * acceptable for the window before load() has ever run, and load() runs at startup.
     */
    static String deviceIdOf(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(DEVICE_ID, "").trim();
    }


    /**
     * Device-protected storage for every preference file this app owns, not only this class's.
     * The service is directBootAware and BootReceiver starts it on LOCKED_BOOT_COMPLETED, so any
     * preference it can reach before the first unlock must live here; a credential-encrypted read
     * on a locked device throws. kiosk_runtime learned that the audited way, read by
     * displaySnapshot from the pre-unlock telemetry path. Package-visible so KioskActivity's
     * kiosk_runtime and kiosk_ui files use the same rule instead of each picking a storage.
     */
    static Context storageContext(Context context) {
        return context.createDeviceProtectedStorageContext();
    }
}
