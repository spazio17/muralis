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
    /** The optional PIN behind the tap combinations, as EscapePin stores it; absent means none. */
    private static final String ESCAPE_PIN_HASH = "escape_pin_hash";
    private static final String STATS_OVERLAY = "stats_overlay";
    /** Whether the device's volume buttons do nothing while Muralis is on screen. */
    private static final String VOLUME_BUTTONS_DISABLED = "volume_buttons_disabled";
    /** Where the stats overlay was dragged to; see statsOverlayPlace and KioskActivity. */
    private static final String STATS_OVERLAY_X = "stats_overlay_x";
    private static final String STATS_OVERLAY_Y = "stats_overlay_y";
    private static final String STATS_OVERLAY_ANCHOR = "stats_overlay_anchor";
    /** The look a person chose on the Appearance page; see appearanceColourOf, Palettes. */
    private static final String APPEARANCE_COLOUR = "appearance_colour";
    private static final String SEPARATORS = "separators";
    static final String SEPARATORS_LINES = "lines";
    static final String SEPARATORS_SEGMENTS = "segments";
    static final String SEPARATORS_NONE = "none";
    /** The separators on offer, in the order both surfaces list them. */
    static final String[] SEPARATOR_CHOICES = {SEPARATORS_LINES, SEPARATORS_SEGMENTS,
            SEPARATORS_NONE};
    private static final String ORIENTATION = "orientation";
    private static final String DISPLAY_OFF_METHOD = "display_off_method";
    private static final String SCREENSAVER_MODE = "screensaver_mode";
    private static final String SCREENSAVER_IDLE_SECONDS = "screensaver_idle_s";
    private static final String SCREENSAVER_OFF_SECONDS = "screensaver_off_s";
    private static final String SCREENSAVER_URL = "screensaver_url";
    private static final String SCREENSAVER_DIM_PERCENT = "screensaver_dim_percent";
    private static final String SCREENSAVER_ON_WAKE = "screensaver_on_wake";
    private static final String SCREENSAVER_SOURCE = "screensaver_source";
    private static final String SCREENSAVER_PICTURE_SECONDS = "screensaver_picture_s";
    private static final String SCREENSAVER_TRANSITION = "screensaver_transition";
    private static final String SCREENSAVER_PICTURE_FIT = "screensaver_picture_fit";
    private static final String SCREENSAVER_SHUFFLE = "screensaver_shuffle";
    private static final String SCREENSAVER_ONE_PER_CYCLE = "screensaver_one_per_cycle";
    private static final String SCREENSAVER_CREDIT = "screensaver_credit";
    private static final String SCREENSAVER_CREDIT_CORNER = "screensaver_credit_corner";
    /** How a folder of pictures is shown on the playlist page: list, details, small or big. */
    private static final String PICTURE_VIEW = "picture_view";
    /**
     * Retired 2026-08-29, when the two-way portrait switch became the three-way orientation
     * setting. Read only by the migration in {@link #orientationOf} and removed by the first
     * orientation write; never written any more.
     */
    private static final String PORTRAIT = "portrait";
    private static final String KIOSK_STOPPED = "kiosk_stopped";
    private static final String VISUAL_OFF_BOOT_COUNT = "visual_off_boot_count";
    private static final String SCREENSAVER_BOOT_COUNT = "screensaver_boot_count";
    private static final String LAST_DASHBOARD_RELAUNCH_AT = "last_dashboard_relaunch_at";
    private static final String WEB_ADMIN_ENABLED = "web_admin_enabled";
    /** Which certificate the web admin serves; see AdminCertificate. */
    private static final String HTTP_CERTIFICATE = "http_certificate";
    static final String CERTIFICATE_OWN = KioskCommandDispatcher.CERTIFICATE_OWN;
    static final String CERTIFICATE_UPLOADED = KioskCommandDispatcher.CERTIFICATE_UPLOADED;
    /**
     * The uploaded certificate as the surfaces show it: its SHA-256, its names and when it ends.
     * Plain settings, since a certificate is public; its key is SecretStore's (AdminCertificate).
     */
    private static final String HTTP_UPLOAD_SHA256 = "http_certificate_upload";
    private static final String HTTP_UPLOAD_NAMES = "http_certificate_upload_names";
    private static final String HTTP_UPLOAD_NAME = "http_certificate_upload_name";
    private static final String HTTP_UPLOAD_UNTIL = "http_certificate_upload_until";
    private static final String LAST_NIGHTLY_RESTART_DAY = "last_nightly_restart_day";
    private static final String SETTINGS_SEQUENCE = "settings_sequence";
    private static final String LAUNCHER_SEQUENCE = "launcher_sequence";

    static final int DEFAULT_HTTP_PORT = 8080;
    /** The member endpoint's port until a person sets another (fleet design, 2026-09-24). */
    static final int DEFAULT_FLEET_PORT = 8081;
    private static final String FLEET_PORT = "fleet_port";

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
    /** The member endpoint's port, a setting like the web admin's (Juri, 2026-10-08). */
    int fleetPort = DEFAULT_FLEET_PORT;
    String httpAdminPassword = "";
    /**
     * Draws the live system-stats block over the dashboard. On by default because it exists to be
     * watched during the multi-week endurance test; it is a switch rather than a build-time choice
     * so turning it off later needs no reflash.
     */
    boolean statsOverlay = true;
    /**
     * Disable volume buttons (2026-10-05): off by default, so the buttons on the device's side
     * change the volume as Android has them; on, Muralis takes their presses while its own screen
     * has the keys, so a passer-by cannot turn a wall panel's sound up or down (a dropdown or a
     * page's popup open over it lets them through). Commands and the audio sensor still set the
     * volume.
     */
    boolean volumeButtonsDisabled;
    String appearanceColour = Palettes.COLOURS[0];
    String separators = SEPARATORS_LINES;
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
    ScreensaverPolicy.Settings screensaver = new ScreensaverPolicy.Settings(
            ScreensaverPolicy.OFF, ScreensaverPolicy.DEFAULT_IDLE_SECONDS,
            ScreensaverPolicy.DEFAULT_OFF_SECONDS, "", ScreensaverPolicy.DEFAULT_DIM_PERCENT,
            ScreensaverPolicy.WAKE_SCREENSAVER);
    /**
     * Whether the web admin is allowed to serve at all, independent of the password: turning the
     * surface off must not cost the operator their stored password, and turning it back on must
     * not require retyping one. Enabled by default; the no-password fail-closed rule in
     * HttpAdminServer.start still applies on top.
     */
    boolean webAdminEnabled = true;
    /** {@link #CERTIFICATE_OWN} or {@link #CERTIFICATE_UPLOADED}; see AdminCertificate. */
    String httpCertificate = CERTIFICATE_OWN;
    /** The uploaded certificate's SHA-256, empty when none is kept. */
    String httpUploadSha256 = "";
    /** The names as shown, "a, b, c, and 4 more", and the first alone, what a Delete calls it. */
    String httpUploadNames = "";
    String httpUploadName = "";
    long httpUploadUntil;
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
        config.fleetPort = preferences.getInt(FLEET_PORT, DEFAULT_FLEET_PORT);
        String storedAdminPassword = secrets.getOrNull(HTTP_ADMIN_PASSWORD);
        config.httpAdminPassword = storedAdminPassword == null ? "" : storedAdminPassword;
        config.webAdminEnabled = preferences.getBoolean(WEB_ADMIN_ENABLED, true);
        KioskConfig certificate = certificateFacts(context);
        config.httpCertificate = certificate.httpCertificate;
        config.httpUploadSha256 = certificate.httpUploadSha256;
        config.httpUploadNames = certificate.httpUploadNames;
        config.httpUploadName = certificate.httpUploadName;
        config.httpUploadUntil = certificate.httpUploadUntil;
        config.statsOverlay = statsOverlayEnabled(context);
        config.volumeButtonsDisabled = volumeButtonsDisabled(context);
        config.appearanceColour = appearanceColourOf(context);
        config.separators = separatorsOf(context);
        config.orientation = orientationOf(context);
        config.displayOffMethod = displayOffMethodOf(context);
        config.screensaver = screensaverOf(context);
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
    static boolean escapePinSet(Context context) {
        return escapePinHash(context) != null;
    }

    /**
     * The stored hash, or null when none is set. SecretStore answers "" for a value never stored
     * and null only while the Keystore is unavailable; both read as no PIN here, so a Keystore
     * hiccup at boot fails open to the combination rather than locking the operator out of
     * settings behind a PIN nobody can check.
     */
    static String escapePinHash(Context context) {
        String hash = new SecretStore(storageContext(context)).getOrNull(ESCAPE_PIN_HASH);
        return hash == null || hash.isEmpty() ? null : hash;
    }

    /**
     * Stores or, with null or empty, removes the PIN. Direct rather than through Editor, whose
     * apply() deliberately never clears a secret: removing the PIN is the one clear that is a
     * decision rather than an echo of an unreadable read.
     */
    static void setEscapePinHash(Context context, String hash) {
        SecretStore secrets = new SecretStore(storageContext(context));
        if (hash == null || hash.isEmpty()) {
            secrets.clear(ESCAPE_PIN_HASH);
        } else {
            secrets.put(ESCAPE_PIN_HASH, hash);
        }
    }

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

        Editor fleetPort(int value) {
            plain.putInt(FLEET_PORT, value);
            return this;
        }

        Editor statsOverlay(boolean value) {
            plain.putBoolean(STATS_OVERLAY, value);
            return this;
        }

        /** Joined: the fleet, and its manager's key as the one key this panel trusts so far. */
        Editor joinFleet(String id, String name, String managerSpki) {
            plain.putString(FLEET_ID, id);
            plain.putString(FLEET_NAME, name.trim());
            plain.putString(FLEET_TRUSTED, managerSpki);
            plain.putString(FLEET_REVOKED, "");
            plain.remove(FLEET_DOCUMENT);
            return this;
        }

        /** Left: no fleet, and no key trusted or revoked; the panel keeps its id and name. */
        Editor leaveFleet(Context context) {
            plain.remove(FLEET_ID);
            plain.remove(FLEET_NAME);
            plain.remove(FLEET_TRUSTED);
            plain.remove(FLEET_REVOKED);
            plain.remove(FLEET_DOCUMENT);
            for (String key : FleetDocument.stripSecrets(fleetDocumentOf(context)).keySet()) {
                secrets.put(FLEET_SECRET + key, "");
            }
            return this;
        }

        /**
         * The fleet document this panel took or wrote (block 12a), already checked and merged
         * with the one held: it names the fleet, its members' keys are the ones this panel
         * trusts, and its revoked keys the ones it refuses. The first one written here also
         * founds the fleet. The profile's secrets (block 12d) go to the SecretStore and the
         * stored copy has them blanked; fleetDocumentText puts them back for the wire.
         */
        Editor fleetDocument(Context context, String text) {
            java.util.Map<String, Object> document = FleetDocument.parse(text);
            if (document == null) {
                return this;
            }
            java.util.Set<String> revoked = FleetDocument.revoked(document);
            plain.putString(FLEET_ID, FleetDocument.fleetId(document));
            plain.putString(FLEET_NAME, FleetDocument.fleetName(document));
            plain.putString(FLEET_TRUSTED, String.join(",", FleetDocument.trustedKeys(document)));
            plain.putString(FLEET_REVOKED, String.join(",", revoked));
            // Every secret slot the new list has (group/key), set or cleared; the slots of a
            // list held before and gone now are cleared too.
            java.util.Map<String, String> carried = FleetDocument.stripSecrets(document);
            for (String key : FleetDocument.stripSecrets(fleetDocumentOf(context)).keySet()) {
                secrets.put(FLEET_SECRET + key, "");
            }
            for (java.util.Map.Entry<String, String> secret : carried.entrySet()) {
                secrets.put(FLEET_SECRET + secret.getKey(), secret.getValue());
            }
            plain.putString(FLEET_DOCUMENT, FleetDocument.canonical(document));
            return this;
        }

        Editor volumeButtonsDisabled(boolean value) {
            plain.putBoolean(VOLUME_BUTTONS_DISABLED, value);
            return this;
        }

        /** The look, checked first by checkAppearance; see Palettes. */
        Editor appearanceColour(String value) {
            plain.putString(APPEARANCE_COLOUR, value);
            // Written through, not cleared: cleared here and read back before apply(), the
            // cache took the old value and kept it (review, 2026-10-04). Checked by the caller.
            appearanceColourCache = value;
            return this;
        }

        Editor separators(String value) {
            plain.putString(SEPARATORS, value);
            separatorsCache = value;
            return this;
        }

        /** One sensor's switch; see Sensors. Off until switched on, like the companion app. */
        // Every sensor setting carries when it changed, under changed_<its key>, for the fleet
        // of 0.7 to merge by (2026-09-30); see sensorSettingsDocument. Writing the value already
        // stored is no change and keeps the time.
        Editor sensorEnabled(String id, boolean value) {
            stampSensor("sensor_" + id, value);
            plain.putBoolean("sensor_" + id, value);
            return this;
        }

        /** One setting of a sensor's own page, a word or a number as text; see Sensors. */
        Editor sensorOption(String key, String value) {
            // A tag read is a fact of this panel, not a setting anybody changes: no change
            // time, which was one orphan line per tag for ever (review, 2026-10-01).
            if (!key.startsWith("tag_seen_")) {
                stampSensor("sensor_option_" + key, value);
            }
            plain.putString("sensor_option_" + key, value);
            return this;
        }

        Editor removeSensorOption(String key) {
            stampSensor("sensor_option_" + key, null);
            plain.remove("sensor_option_" + key);
            return this;
        }

        private void stampSensor(String key, Object value) {
            Object stored = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getAll().get(key);
            if (!java.util.Objects.equals(stored, value)) {
                plain.putLong("changed_" + key, System.currentTimeMillis());
            }
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

        Editor screensaverMode(String value) {
            plain.putString(SCREENSAVER_MODE, value);
            return this;
        }

        /** Where the stats overlay sits; the shape is statsOverlayPlace's. */
        Editor statsOverlayPlace(boolean right, boolean bottom, float x, float y) {
            plain.putString(STATS_OVERLAY_ANCHOR, (bottom ? "b" : "t") + (right ? "r" : "l"));
            plain.putFloat(STATS_OVERLAY_X, x);
            plain.putFloat(STATS_OVERLAY_Y, y);
            return this;
        }

        Editor pictureView(String value) {
            plain.putString(PICTURE_VIEW, PictureBrowser.view(value));
            return this;
        }

        Editor screensaverIdleSeconds(int value) {
            plain.putInt(SCREENSAVER_IDLE_SECONDS, value);
            return this;
        }

        Editor screensaverOffSeconds(int value) {
            plain.putInt(SCREENSAVER_OFF_SECONDS, value);
            return this;
        }

        Editor screensaverUrl(String value) {
            plain.putString(SCREENSAVER_URL, value.trim());
            return this;
        }

        Editor screensaverDimPercent(int value) {
            plain.putInt(SCREENSAVER_DIM_PERCENT, value);
            return this;
        }

        Editor screensaverOnWake(String value) {
            plain.putString(SCREENSAVER_ON_WAKE, value);
            return this;
        }

        Editor screensaverSource(String value) {
            plain.putString(SCREENSAVER_SOURCE, value);
            return this;
        }

        Editor screensaverPictureSeconds(int value) {
            plain.putInt(SCREENSAVER_PICTURE_SECONDS, value);
            return this;
        }

        Editor screensaverTransition(String value) {
            plain.putString(SCREENSAVER_TRANSITION, value);
            return this;
        }

        Editor screensaverPictureFit(String value) {
            plain.putString(SCREENSAVER_PICTURE_FIT, value);
            return this;
        }

        Editor screensaverShuffle(boolean value) {
            plain.putBoolean(SCREENSAVER_SHUFFLE, value);
            return this;
        }

        Editor screensaverOnePerCycle(boolean value) {
            plain.putBoolean(SCREENSAVER_ONE_PER_CYCLE, value);
            return this;
        }

        Editor screensaverCredit(boolean value) {
            plain.putBoolean(SCREENSAVER_CREDIT, value);
            return this;
        }

        Editor screensaverCreditCorner(String value) {
            plain.putString(SCREENSAVER_CREDIT_CORNER, value);
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

        /** Which certificate the web admin serves, checked first by checkHttpCertificate. */
        Editor httpCertificate(String value) {
            plain.putString(HTTP_CERTIFICATE, value);
            return this;
        }

        /** The uploaded certificate as shown; an empty SHA-256 says none is kept. */
        Editor httpUpload(String sha256, String name, String names, long untilMs) {
            plain.putString(HTTP_UPLOAD_SHA256, sha256);
            plain.putString(HTTP_UPLOAD_NAME, name);
            plain.putString(HTTP_UPLOAD_NAMES, names);
            plain.putLong(HTTP_UPLOAD_UNTIL, untilMs);
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
            try {
                writeSecrets(store);
            } finally {
                // The fleet's list is read with its secrets put back (fleetDocumentText): a copy
                // cached between the two writes above would carry the old secret for as long as
                // the list stays the same, so it is dropped once both are in.
                synchronized (FleetDocument.class) {
                    fleetDocumentStored = null;
                }
            }
        }

        private void writeSecrets(SecretStore store) {
            for (java.util.Map.Entry<String, String> secret : secrets.entrySet()) {
                // A fleet secret the new list carries empty is cleared, so what comes back for
                // the wire is what was signed.
                if (secret.getKey().startsWith(FLEET_SECRET) && secret.getValue().isEmpty()) {
                    store.clear(secret.getKey());
                    continue;
                }
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
     * The screensaver's settings alone, without the secrets a full load decrypts: the activity's
     * clock reads them every second, and a stored value this build does not know reads as the
     * default rather than being refused, the same rule as {@link #displayOffMethodOf}.
     */
    static ScreensaverPolicy.Settings screensaverOf(Context context) {
        SharedPreferences preferences = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = preferences.getString(SCREENSAVER_MODE, ScreensaverPolicy.OFF);
        String onWake = preferences.getString(SCREENSAVER_ON_WAKE,
                ScreensaverPolicy.WAKE_SCREENSAVER);
        String source = preferences.getString(SCREENSAVER_SOURCE, PictureSources.LOCAL);
        String transition = preferences.getString(SCREENSAVER_TRANSITION,
                ScreensaverPolicy.TRANSITION_FADE);
        String fit = preferences.getString(SCREENSAVER_PICTURE_FIT, ScreensaverPolicy.FIT_WHOLE);
        String corner = preferences.getString(SCREENSAVER_CREDIT_CORNER,
                ScreensaverPolicy.CORNER_BOTTOM_LEFT);
        return new ScreensaverPolicy.Settings(
                ScreensaverPolicy.isMode(mode) ? mode : ScreensaverPolicy.OFF,
                clamp(preferences.getInt(SCREENSAVER_IDLE_SECONDS,
                        ScreensaverPolicy.DEFAULT_IDLE_SECONDS), 0, ScreensaverPolicy.MAX_SECONDS),
                clamp(preferences.getInt(SCREENSAVER_OFF_SECONDS,
                        ScreensaverPolicy.DEFAULT_OFF_SECONDS), 0, ScreensaverPolicy.MAX_SECONDS),
                preferences.getString(SCREENSAVER_URL, ""),
                clamp(preferences.getInt(SCREENSAVER_DIM_PERCENT,
                        ScreensaverPolicy.DEFAULT_DIM_PERCENT), 1, 100),
                ScreensaverPolicy.isOnWake(onWake) ? onWake : ScreensaverPolicy.WAKE_SCREENSAVER,
                PictureSources.isSource(source) ? source : PictureSources.LOCAL,
                clamp(preferences.getInt(SCREENSAVER_PICTURE_SECONDS,
                        ScreensaverPolicy.DEFAULT_PICTURE_SECONDS), 1, ScreensaverPolicy.MAX_SECONDS),
                ScreensaverPolicy.isTransition(transition) ? transition
                        : ScreensaverPolicy.TRANSITION_FADE,
                preferences.getBoolean(SCREENSAVER_SHUFFLE, false),
                preferences.getBoolean(SCREENSAVER_ONE_PER_CYCLE, false),
                preferences.getBoolean(SCREENSAVER_CREDIT, true),
                ScreensaverPolicy.isCorner(corner) ? corner : ScreensaverPolicy.CORNER_BOTTOM_LEFT,
                ScreensaverPolicy.isFit(fit) ? fit : ScreensaverPolicy.FIT_WHOLE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Whether the sensor with this id is switched on, {@code fallback} when nothing is stored:
     * Sensors.enabled knows which start on (the panel's own values) and which start off.
     */
    static boolean sensorEnabled(Context context, String id, boolean fallback) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("sensor_" + id, fallback);
    }

    private static final String AUTOMATIONS = "automations";
    /** The markers of the rules deleted lately; see Automations.deleted. */
    private static final String AUTOMATIONS_DELETED = "automations_deleted";

    static String sensorOption(Context context, String key, String fallback) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("sensor_option_" + key, fallback);
    }

    private static final String BEACON_NAMES = "beacon_names";
    private static final Object BEACON_NAMES_LOCK = new Object();

    /**
     * The names given to beacons, one NamedList document (2026-09-30, the fleet's shape). The
     * loose sensor_option_beacon_<id> keys an older build wrote are read into it, and go at
     * the next change.
     */
    static NamedList beaconNames(Context context) {
        android.content.SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        NamedList names = NamedList.parse(prefs.getString(BEACON_NAMES, null));
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("sensor_option_beacon_")
                    && entry.getValue() instanceof String) {
                String id = entry.getKey().substring("sensor_option_beacon_".length());
                if (names.name(id).isEmpty()) {
                    names.set(id, (String) entry.getValue(), 0);
                }
            }
        }
        return names;
    }

    /** Names a beacon, or takes its name away when {@code name} is blank. */
    static void beaconName(Context context, String id, String name) {
        synchronized (BEACON_NAMES_LOCK) {
            android.content.SharedPreferences prefs = storageContext(context)
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            NamedList names = beaconNames(context);
            names.set(id, name, System.currentTimeMillis());
            android.content.SharedPreferences.Editor editor = prefs.edit()
                    .putString(BEACON_NAMES, names.store());
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("sensor_option_beacon_")) {
                    editor.remove(key);
                }
            }
            editor.apply();
        }
    }

    /**
     * The sensor settings as one document, for the web API and the fleet of 0.7: the switches,
     * the shared settings and this panel's own (SensorSettings), each with its value and when
     * it changed, 0 when never since change times were kept.
     */
    static org.json.JSONObject sensorSettingsDocument(Context context) {
        android.content.SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONObject switches = new org.json.JSONObject();
        org.json.JSONObject shared = new org.json.JSONObject();
        org.json.JSONObject thisPanel = new org.json.JSONObject();
        try {
            // Every sensor's switch as it stands, stored or not: the panel's own values start
            // on with nothing stored, and a document that left them out was not the whole
            // state (review, 2026-10-01).
            for (Sensors.Def def : Sensors.ALL) {
                org.json.JSONObject one = new org.json.JSONObject();
                one.put("on", Sensors.enabled(context, def));
                one.put("changed_at", prefs.getLong("changed_sensor_" + def.id, 0));
                switches.put(def.id, one);
            }
            for (java.util.Map.Entry<String, ?> entry : new java.util.TreeMap<>(prefs.getAll())
                    .entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("sensor_option_")) {
                    String option = key.substring("sensor_option_".length());
                    if (option.startsWith("beacon_") || option.startsWith("tag_seen_")) {
                        continue;
                    }
                    org.json.JSONObject one = new org.json.JSONObject();
                    one.put("value", String.valueOf(entry.getValue()));
                    one.put("changed_at", prefs.getLong("changed_" + key, 0));
                    (SensorSettings.shared(option) ? shared : thisPanel).put(option, one);
                }
            }
            org.json.JSONObject document = new org.json.JSONObject();
            document.put("switches", switches);
            document.put("shared", shared);
            document.put("this_panel", thisPanel);
            return document;
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static int sensorOptionInt(Context context, String key, int fallback) {
        try {
            return Integer.parseInt(sensorOption(context, key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    static boolean sensorOptionOn(Context context, String key, boolean fallback) {
        return "true".equals(sensorOption(context, key, Boolean.toString(fallback)));
    }

    /**
     * Drops the settings of the beacon transmitter, which was removed on 2026-10-01, and the
     * one-number beacon correction the per-beacon calibration replaced: a panel that ever
     * stored them kept them, and the sensor settings document then carried keys a
     * post back is refused for. Also {@code automations_announced}, the automation switches
     * discovery had announced: since 2026-10-06 the broker's own retained payload is the record
     * of what was announced. Run at every start; it does nothing once they are gone.
     */
    static void dropRetiredSettings(Context context) {
        java.util.Set<String> retired = new java.util.HashSet<>();
        retired.add("automations_announced");
        for (String option : new String[] {"beacons_transmit", "beacons_uuid", "beacons_major",
                "beacons_minor", "beacons_correction_db"}) {
            retired.add("sensor_option_" + option);
            retired.add("changed_sensor_option_" + option);
        }
        android.content.SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = null;
        for (String key : prefs.getAll().keySet()) {
            if (retired.contains(key)) {
                if (editor == null) {
                    editor = prefs.edit();
                }
                editor.remove(key);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    /**
     * Keeps the tags seen down to the newest {@code keep}: a panel in a public place reads tags
     * all day, and each one is a line in the settings file otherwise. The names a person could
     * give a tag went on 2026-09-30 (a tag is named in Home Assistant, as the companion app
     * leaves it), and what an older build stored for them goes here too.
     */
    static void pruneSeenTags(Context context, int keep) {
        android.content.SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.List<String[]> unnamed = new java.util.ArrayList<>();
        android.content.SharedPreferences.Editor leftovers = prefs.edit();
        boolean stale = false;
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("nfc_tag_")
                    || entry.getKey().startsWith("changed_sensor_option_tag_seen_")) {
                // The change times an earlier build stamped on tag reads go with the names.
                leftovers.remove(entry.getKey());
                stale = true;
                continue;
            }
            if (!entry.getKey().startsWith("sensor_option_tag_seen_")) {
                continue;
            }
            String id = entry.getKey().substring("sensor_option_tag_seen_".length());
            long at;
            try {
                at = Long.parseLong(String.valueOf(entry.getValue()));
            } catch (NumberFormatException notATime) {
                at = 0;
            }
            unnamed.add(new String[] {String.format(java.util.Locale.ROOT, "%020d", at), id});
        }
        if (stale) {
            leftovers.apply();
        }
        if (unnamed.size() <= keep) {
            return;
        }
        // Oldest first; two tags read in the same millisecond stay two (review, 2026-09-27).
        java.util.Collections.sort(unnamed, (a, b) -> a[0].compareTo(b[0]));
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (int index = 0; index < unnamed.size() - keep; index++) {
            editor.remove("sensor_option_tag_seen_" + unnamed.get(index)[1]);
        }
        editor.apply();
    }

    /** The tags this panel has read, newest first, id to when (milliseconds). */
    static java.util.LinkedHashMap<String, Long> seenTags(Context context) {
        java.util.List<java.util.Map.Entry<String, Long>> seen = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (entry.getKey().startsWith("sensor_option_tag_seen_")) {
                try {
                    seen.add(new java.util.AbstractMap.SimpleEntry<>(
                            entry.getKey().substring("sensor_option_tag_seen_".length()),
                            Long.parseLong(String.valueOf(entry.getValue()))));
                } catch (NumberFormatException notATime) {
                    // A value this build did not write.
                }
            }
        }
        java.util.Collections.sort(seen, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        java.util.LinkedHashMap<String, Long> ordered = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Long> one : seen) {
            ordered.put(one.getKey(), one.getValue());
        }
        return ordered;
    }


    /**
     * Stores the whole list of automations, the one way every writer does it: each rule's
     * change time set (Automations.stamp) and a deletion marker for each rule left out
     * (Automations.deleted), so the fleet of 0.7 can merge copies and carry deletions. The
     * caller holds Automations.STORE across its read and this write.
     */
    static void storeAutomations(Context context, java.util.List<Automations.Rule> rules) {
        android.content.SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.List<Automations.Rule> stored = automationsOf(context);
        long now = System.currentTimeMillis();
        Automations.stamp(stored, rules, now);
        prefs.edit()
                .putString(AUTOMATIONS, Automations.store(rules))
                .putString(AUTOMATIONS_DELETED, Automations.deleted(
                        prefs.getString(AUTOMATIONS_DELETED, "[]"), stored, rules, now))
                .apply();
    }

    /** The deletion markers, a JSON array of {"id", "deleted_at"}. */
    static String automationsDeleted(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(AUTOMATIONS_DELETED, "[]");
    }

    /** The stored automations; the shipped default until something is stored. */
    static java.util.List<Automations.Rule> automationsOf(Context context) {
        return Automations.parse(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(AUTOMATIONS, null));
    }

    /**
     * The fleet keys this panel admits on its member endpoint, and the ones it never admits again,
     * each a SHA-256 of a public key in lowercase hex (FleetTls.spkiOf). Written by the join and
     * the fleet document (blocks 11 and 12 of the 0.7 plan); read at every handshake, so a change
     * applies to the next call. Anything that is not such a hash is ignored.
     */
    static java.util.Set<String> fleetTrusted(Context context) {
        return keyHashes(context, FLEET_TRUSTED);
    }

    static java.util.Set<String> fleetRevoked(Context context) {
        return keyHashes(context, FLEET_REVOKED);
    }

    private static java.util.Set<String> keyHashes(Context context, String name) {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        String stored = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(name, "");
        for (String hash : stored.split(",")) {
            if (hash.matches("[0-9a-f]{64}")) {
                hashes.add(hash);
            }
        }
        return hashes;
    }

    /** The fleet this panel joined, its id and name as its manager sent them; empty when none. */
    static String fleetIdOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(FLEET_ID, "");
    }

    static String fleetNameOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(FLEET_NAME, "");
    }

    private static final String FLEET_TRUSTED = "fleet_trusted";
    private static final String FLEET_REVOKED = "fleet_revoked";
    private static final String FLEET_ID = "fleet_id";
    private static final String FLEET_DOCUMENT = "fleet_document";

    /** Secrets of the fleet's profile in the SecretStore, under the key's own name. */
    private static final String FLEET_SECRET = "fleet_";
    /** The stored copy last read, with its secrets put back: read and parsed once per change. */
    private static String fleetDocumentStored = null;
    private static String fleetDocumentFilled = "";

    /**
     * The fleet document held, whole, as it was signed: the stored copy with its secrets back
     * from the SecretStore. Empty before the first. The copy on disk is parsed and the secrets
     * decrypted only when it changed, since both surfaces ask for the document every second.
     */
    static String fleetDocumentText(Context context) {
        String stored = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(FLEET_DOCUMENT, "");
        synchronized (FleetDocument.class) {
            if (stored.equals(fleetDocumentStored)) {
                return fleetDocumentFilled;
            }
            java.util.Map<String, Object> document = stored.isEmpty() ? null
                    : FleetDocument.parse(stored);
            String filled = "";
            if (document != null) {
                java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
                SecretStore store = new SecretStore(storageContext(context));
                for (String group : FleetDocument.profileGroups(document)) {
                    for (String key : FleetDocument.SECRET_KEYS) {
                        String value = store.getOrNull(FLEET_SECRET + group + "/" + key);
                        if (value != null && !value.isEmpty()) {
                            secrets.put(group + "/" + key, value);
                        }
                    }
                }
                FleetDocument.fillSecrets(document, secrets);
                filled = FleetDocument.canonical(document);
            }
            fleetDocumentStored = stored;
            fleetDocumentFilled = filled;
            return filled;
        }
    }

    /** The fleet document held, as maps and lists; null before the first (see fleetDocument). */
    static java.util.Map<String, Object> fleetDocumentOf(Context context) {
        String text = fleetDocumentText(context);
        return text.isEmpty() ? null : FleetDocument.parse(text);
    }

    private static final String FLEET_NAME = "fleet_name";

    /** Where the stats overlay was dragged to; see {@link #statsOverlayPlace}. */
    static final class OverlayPlace {
        /** Which edges the block is measured from: the ones it was dropped nearest to. */
        final boolean right;
        final boolean bottom;
        /** The gap from those edges, as shares of the screen's width and height. */
        final float x;
        final float y;

        OverlayPlace(boolean right, boolean bottom, float x, float y) {
            this.right = right;
            this.bottom = bottom;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Where the stats overlay was dragged to, or null while it was never moved and sits in
     * its default place. Measured from the edges it was dropped nearest to, as shares of the
     * screen, not of the room left over: the block's own size changes with every line it
     * draws, and a place that depended on it moved a few pixels every second (the flicker
     * Juri saw, 2026-10-04). This panel's alone: a place on one screen means nothing on another.
     */
    static OverlayPlace statsOverlayPlace(Context context) {
        android.content.SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(STATS_OVERLAY_ANCHOR) || !prefs.contains(STATS_OVERLAY_X)
                || !prefs.contains(STATS_OVERLAY_Y)) {
            return null;
        }
        String anchor = prefs.getString(STATS_OVERLAY_ANCHOR, "tl");
        return new OverlayPlace(anchor.endsWith("r"), anchor.startsWith("b"),
                prefs.getFloat(STATS_OVERLAY_X, 0f), prefs.getFloat(STATS_OVERLAY_Y, 0f));
    }

    /**
     * The look a person chose on the Appearance page under This panel (Juri, 2026-10-04; a page
     * of its own since 2026-10-07): a seed colour, which names a scheme of Palettes, and how
     * the rows of a list are kept apart. A second axis went twice the same day (Material's
     * variants, then its contrast levels): other apps offer a colour and day or night, nothing
     * more. This panel's alone, like the light theme of the settings: a look is a thing of the
     * screen in front of someone. Purple with lines is Muralis's own look and the default; a
     * stored value nothing offers reads as the default.
     */
    static String appearanceColourOf(Context context) {
        String cached = appearanceColourCache;
        if (cached != null) {
            return cached;
        }
        String stored = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(APPEARANCE_COLOUR, null);
        String colour = checkAppearance("appearance_colour", stored) == null ? stored
                : Palettes.COLOURS[0];
        appearanceColourCache = colour;
        return colour;
    }

    static String separatorsOf(Context context) {
        String cached = separatorsCache;
        if (cached != null) {
            return cached;
        }
        String stored = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SEPARATORS, null);
        String separators = checkAppearance("separators", stored) == null ? stored
                : SEPARATORS_LINES;
        separatorsCache = separators;
        return separators;
    }

    /**
     * The two look settings as last read: every row of a list asked for them, and each ask was a
     * storage context and a preferences lookup on the UI thread (review, 2026-10-04). The Editor
     * is the only writer and writes them through.
     */
    private static volatile String appearanceColourCache;
    private static volatile String separatorsCache;

    /** A colour's name, "Purple", on both surfaces' radios and in the Appearance summary. */
    static String colourLabel(String colour) {
        return colour == null || colour.isEmpty() ? ""
                : Character.toUpperCase(colour.charAt(0)) + colour.substring(1);
    }

    /** A separators choice's name, "Lines", "Segments" or "None", wherever it is shown. */
    static String separatorsLabel(String separators) {
        return SEPARATORS_SEGMENTS.equals(separators) ? "Segments"
                : SEPARATORS_NONE.equals(separators) ? "None" : "Lines";
    }

    /** The Appearance row's summary on both surfaces, "Purple · Lines", from the two labels. */
    static String appearanceSummary(String colour, String separators) {
        return colourLabel(colour) + " \u00b7 " + separatorsLabel(separators);
    }

    /** Why a value for one of the two look settings is refused, or null when it is offered. */
    static String checkAppearance(String key, String value) {
        String[] offered;
        switch (key) {
            case "appearance_colour": offered = Palettes.COLOURS; break;
            case "separators": offered = SEPARATOR_CHOICES; break;
            default: return "no setting is called " + key;
        }
        if (value != null) {
            for (String one : offered) {
                if (one.equals(value)) {
                    return null;
                }
            }
        }
        return key.replace('_', ' ') + " must be " + String.join(", ",
                java.util.Arrays.copyOf(offered, offered.length - 1)) + " or "
                + offered[offered.length - 1];
    }

    /** Narrow reader for Disable volume buttons, asked on every press of one. */
    static boolean volumeButtonsDisabled(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(VOLUME_BUTTONS_DISABLED, false);
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
     * How the playlist page shows a folder, one preference for the panel and the web page alike,
     * so both open the next folder the same way (decided 2026-09-23). Same narrow-reader
     * reasoning as {@link #statsOverlayEnabled}.
     */
    static String pictureViewOf(Context context) {
        return PictureBrowser.view(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PICTURE_VIEW, PictureBrowser.DEFAULT_VIEW));
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

    /**
     * The boot in which the screensaver was put on the glass, or -1: the same shape as the
     * visual-off record and for the same reason, so the nightly restart and the WebView rebuilds
     * bring the screensaver back rather than lighting the page for the idle time at four in
     * the morning. Ends with the screensaver (a touch, a command, the settings screen) or with
     * the display going dark, never with a rebuild.
     */
    static void recordScreensaverBootCount(Context context, int bootCount) {
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(SCREENSAVER_BOOT_COUNT, bootCount)
                .commit();
    }

    static int screensaverBootCount(Context context) {
        return storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(SCREENSAVER_BOOT_COUNT, -1);
    }

    static long screensaverSinceMs(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("screensaver_since_ms", -1L);
    }

    static void recordScreensaverSinceMs(Context context, long sinceMs) {
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("screensaver_since_ms", sinceMs).commit();
    }

    static void recordVisualOffBootCount(Context context, int bootCount) {
        // commit, not apply: the nightly restart ends in System.exit, which does not wait for
        // asynchronous preference writes, and surviving exactly that restart is this field's job.
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(VISUAL_OFF_BOOT_COUNT, bootCount)
                .commit();
    }

    /**
     * Which certificate the web admin serves: the uploaded one only while one is kept, so a
     * stored choice that outlived its upload reads as this panel's own.
     */
    static String httpCertificateOf(Context context) {
        SharedPreferences preferences = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return CERTIFICATE_UPLOADED.equals(preferences.getString(HTTP_CERTIFICATE, null))
                && !preferences.getString(HTTP_UPLOAD_SHA256, "").isEmpty()
                ? CERTIFICATE_UPLOADED : CERTIFICATE_OWN;
    }

    /**
     * The certificate settings alone, the rest left at their defaults: the panel's live sync reads
     * them every tick, and {@link #load} would decrypt every secret each time.
     */
    static KioskConfig certificateFacts(Context context) {
        SharedPreferences preferences = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        KioskConfig facts = new KioskConfig();
        facts.httpCertificate = httpCertificateOf(context);
        facts.httpUploadSha256 = preferences.getString(HTTP_UPLOAD_SHA256, "");
        facts.httpUploadNames = preferences.getString(HTTP_UPLOAD_NAMES, "");
        facts.httpUploadName = preferences.getString(HTTP_UPLOAD_NAME, "");
        facts.httpUploadUntil = preferences.getLong(HTTP_UPLOAD_UNTIL, 0);
        return facts;
    }

    /**
     * Why a choice of certificate is refused, or null when it can be served: the dispatcher's
     * rule, given whether an upload is kept.
     */
    static String checkHttpCertificate(Context context, String value) {
        return KioskCommandDispatcher.validateCertificateChoice(value,
                !certificateFacts(context).httpUploadSha256.isEmpty());
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

    /** The member port alone, without load()'s decryption of every secret. */
    static int fleetPortOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(FLEET_PORT, DEFAULT_FLEET_PORT);
    }

    /** The web admin's stored port alone, for the member endpoint's start. */
    static int httpPortOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(HTTP_PORT, DEFAULT_HTTP_PORT);
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
