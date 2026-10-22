/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
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
    private static final String MQTT_TLS = "mqtt_tls";
    /** App lock: the package shown instead of the web page; empty when off. */
    private static final String APP_LOCK = "app_lock";
    /** What the chosen app is called, kept so a panel can name it after it was uninstalled. */
    private static final String APP_LOCK_LABEL = "app_lock_label";
    /** The broker certificate a person trusted, its SHA-256 (MqttTls); empty when none. */
    private static final String MQTT_TLS_PIN = "mqtt_tls_pin";
    /** The client certificate's chain and key, in the SecretStore; its name and end in plain. */
    static final String MQTT_CLIENT_CHAIN = "mqtt_client_chain";
    static final String MQTT_CLIENT_KEY = "mqtt_client_key";
    private static final String MQTT_CLIENT_NAME = "mqtt_client_name";
    private static final String MQTT_CLIENT_UNTIL = "mqtt_client_until";
    private static final String HTTP_PORT = "http_port";
    private static final String HTTP_ADMIN_PASSWORD = "http_admin_password";
    /** The optional PIN behind the tap combinations, as EscapePin stores it; absent means none. */
    private static final String ESCAPE_PIN_HASH = "escape_pin_hash";
    private static final String STATS_OVERLAY = "stats_overlay";
    /** The page's browser options (BrowserOptions); the sign-in password is a secret. */
    private static final String BROWSER_USER_AGENT = "browser_user_agent";
    private static final String BROWSER_TEXT_SIZE = "browser_text_size";
    private static final String BROWSER_PAGE_ZOOM = "browser_page_zoom";
    private static final String BROWSER_DARK = "browser_dark";
    private static final String BROWSER_BACK_AFTER = "browser_back_after_s";
    /** The certificates a person trusted, one host and fingerprint a line (CertificatePins). */
    private static final String CERTIFICATE_PINS = "certificate_pins";
    /** The cameras, as Cameras writes them; their sign-ins are one secret apart. */
    private static final String CAMERAS = "cameras";
    private static final String CAMERA_LOGINS = "camera_logins";
    private static final String CAMERA_GROUPS = "camera_groups";
    /** The camera or group the panel shows as its main view; empty for the web page. */
    private static final String DASHBOARD_CAMERAS = "dashboard_cameras";
    /** How the camera view lays its cameras out: Cameras.LAYOUTS, the big tile's camera, names. */
    private static final String CAMERA_LAYOUT = "camera_layout";
    private static final String CAMERA_BIG = "camera_big";
    private static final String CAMERA_NAMES = "camera_names";
    private static final String BROWSER_PAGE_CAMERA = "browser_page_camera";
    private static final String BROWSER_PAGE_MICROPHONE = "browser_page_microphone";
    private static final String BROWSER_USER = "browser_user";
    private static final String BROWSER_PASSWORD = "browser_password";
    /** The status light (StatusLight): on or off, its colour as 0xRRGGBB, its brightness. */
    private static final String STATUS_LIGHT_ON = "status_light_on";
    private static final String STATUS_LIGHT_COLOR = "status_light_color";
    private static final String STATUS_LIGHT_PERCENT = "status_light_percent";
    /** Whether the device's volume buttons do nothing while Muralis is on screen. */
    private static final String VOLUME_BUTTONS_DISABLED = "volume_buttons_disabled";
    /** Where the stats overlay was dragged to; see statsOverlayPlace and KioskActivity. */
    private static final String STATS_OVERLAY_X = "stats_overlay_x";
    private static final String STATS_OVERLAY_Y = "stats_overlay_y";
    private static final String STATS_OVERLAY_ANCHOR = "stats_overlay_anchor";
    private static final String ORIENTATION = "orientation";
    private static final String DISPLAY_OFF_METHOD = "display_off_method";
    private static final String SCREENSAVER_MODE = "screensaver_mode";
    private static final String SCREENSAVER_IDLE_SECONDS = "screensaver_idle_s";
    private static final String SCREENSAVER_OFF_SECONDS = "screensaver_off_s";
    private static final String SCREENSAVER_URL = "screensaver_url";
    private static final String SCREENSAVER_DIM_PERCENT = "screensaver_dim_percent";
    private static final String SCREENSAVER_ON_WAKE = "screensaver_on_wake";
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
    /** The member endpoint's port until a person sets another. */
    static final int DEFAULT_FLEET_PORT = 8081;
    private static final String FLEET_PORT = "fleet_port";
    /** The network speaker (NetworkSpeaker): off until a person switches it on, and its port. */
    private static final String NETWORK_SPEAKER = "network_speaker";
    private static final String NETWORK_SPEAKER_PORT = "network_speaker_port";
    static final int DEFAULT_NETWORK_SPEAKER_PORT = 2325;
    /**
     * The speaker's UPnP identity, minted once. Not a setting: it never travels in a settings
     * file, since two panels with one UDN would be one device to a controller.
     */
    private static final String NETWORK_SPEAKER_UDN = "network_speaker_udn";
    /** The fleet's alerts, stored under the keys both surfaces use. */
    private static final String ALERT_OFFLINE_MINUTES = "alert_offline_minutes";
    private static final String ALERT_PAGE_ERROR = "alert_page_error";
    private static final String ALERT_BATTERY_BELOW = "alert_battery_below";
    private static final String ALERT_TEMPERATURE_ABOVE = "alert_temperature_above";
    private static final String ALERT_COMBINATION_USED = "alert_combination_used";
    private static final String ALERT_PIN_LOCKOUT = "alert_pin_lockout";
    private static final String ALERT_MQTT_TOPIC = "alert_mqtt_topic";
    private static final String ALERT_WEBHOOK_URL = "alert_webhook_url";

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
     * 1883, the plain-TCP MQTT port. Switching on TLS does not change it: the person types the
     * broker's TLS port (often 8883) with it.
     */
    int mqttPort = 1883;
    String mqttUsername = "";
    String mqttPassword = "";
    boolean mqttTls;
    String appLockPackage = "";
    String mqttTlsPin = "";
    /** The client certificate's names and when it ends; empty and 0 when none is uploaded. */
    String mqttClientName = "";
    long mqttClientUntil;
    int httpPort = DEFAULT_HTTP_PORT;
    /** The member endpoint's port, a setting like the web admin's (2026-10-08). */
    int fleetPort = DEFAULT_FLEET_PORT;
    /**
     * Whether the panel is a DLNA speaker on the network. Off by default and so failing closed:
     * DLNA has no authentication, so while it is on anyone on the network can play sound here.
     */
    boolean networkSpeaker;
    int networkSpeakerPort = DEFAULT_NETWORK_SPEAKER_PORT;
    /** The fleet's alerts: thresholds and destinations; see FleetAlerts. */
    FleetAlerts.Settings alerts = new FleetAlerts.Settings();
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
        config.mqttTls = preferences.getBoolean(MQTT_TLS, false);
        config.appLockPackage = preferences.getString(APP_LOCK, "");
        config.mqttTlsPin = preferences.getString(MQTT_TLS_PIN, "");
        config.mqttClientName = preferences.getString(MQTT_CLIENT_NAME, "");
        config.mqttClientUntil = preferences.getLong(MQTT_CLIENT_UNTIL, 0);
        config.httpPort = preferences.getInt(HTTP_PORT, DEFAULT_HTTP_PORT);
        config.fleetPort = preferences.getInt(FLEET_PORT, DEFAULT_FLEET_PORT);
        config.networkSpeaker = preferences.getBoolean(NETWORK_SPEAKER, false);
        config.networkSpeakerPort = preferences.getInt(NETWORK_SPEAKER_PORT,
                DEFAULT_NETWORK_SPEAKER_PORT);
        config.alerts = alertsOf(preferences);
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

        /**
         * The app App lock shows, a package name, empty switches App lock off; the app the panel shows; one answer of "The panel shows", so choosing an app, or the
         * web page (empty), also ends the cameras.
         */
        Editor appLock(String packageName) {
            String chosen = packageName == null ? "" : packageName.trim();
            plain.putString(APP_LOCK, chosen)
                    .putString(APP_LOCK_LABEL, chosen.isEmpty() ? ""
                            : AppLock.labelOf(storageContext, chosen))
                    .putString(DASHBOARD_CAMERAS, "");
            return this;
        }

        /** The camera view's layout, the camera of its big tile (empty: the first) and names. */
        Editor cameraLayout(String layout, String big, boolean names) {
            plain.putString(CAMERA_LAYOUT, Cameras.isLayout(layout) ? layout : Cameras.LAYOUT_AUTO)
                    .putString(CAMERA_BIG, big == null ? "" : big.trim())
                    .putBoolean(CAMERA_NAMES, names);
            return this;
        }

        /** The camera or group the panel shows; choosing one ends App lock, empty is the page. */
        Editor dashboardCameras(String name) {
            String chosen = name == null ? "" : name.trim();
            plain.putString(DASHBOARD_CAMERAS, chosen);
            if (!chosen.isEmpty()) {
                plain.putString(APP_LOCK, "").putString(APP_LOCK_LABEL, "");
            }
            return this;
        }

        Editor mqttTls(boolean value) {
            plain.putBoolean(MQTT_TLS, value);
            return this;
        }

        /** A fingerprint as CertificatePins prints it, or empty to trust none. */
        Editor mqttTlsPin(String value) {
            plain.putString(MQTT_TLS_PIN, value == null ? "" : value);
            return this;
        }

        /** The uploaded client certificate's names and end, shown on both surfaces; "" and 0 clear. */
        Editor mqttClientShown(String names, long untilMs) {
            plain.putString(MQTT_CLIENT_NAME, names).putLong(MQTT_CLIENT_UNTIL, untilMs);
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

        Editor networkSpeaker(boolean value) {
            plain.putBoolean(NETWORK_SPEAKER, value);
            return this;
        }

        Editor networkSpeakerPort(int value) {
            plain.putInt(NETWORK_SPEAKER_PORT, value);
            return this;
        }

        Editor alertOfflineMinutes(long value) {
            plain.putLong(ALERT_OFFLINE_MINUTES, value);
            return this;
        }

        Editor alertPageError(boolean value) {
            plain.putBoolean(ALERT_PAGE_ERROR, value);
            return this;
        }

        Editor alertBatteryBelow(long value) {
            plain.putLong(ALERT_BATTERY_BELOW, value);
            return this;
        }

        Editor alertTemperatureAbove(long value) {
            plain.putLong(ALERT_TEMPERATURE_ABOVE, value);
            return this;
        }

        Editor alertCombinationUsed(boolean value) {
            plain.putBoolean(ALERT_COMBINATION_USED, value);
            return this;
        }

        Editor alertPinLockout(boolean value) {
            plain.putBoolean(ALERT_PIN_LOCKOUT, value);
            return this;
        }

        Editor alertMqttTopic(String value) {
            plain.putString(ALERT_MQTT_TOPIC, value.trim());
            return this;
        }

        Editor alertWebhookUrl(String value) {
            plain.putString(ALERT_WEBHOOK_URL, value.trim());
            return this;
        }

        Editor statsOverlay(boolean value) {
            plain.putBoolean(STATS_OVERLAY, value);
            return this;
        }

        Editor browserUserAgent(String value) {
            plain.putString(BROWSER_USER_AGENT, value.trim());
            return this;
        }

        Editor browserTextSize(int percent) {
            plain.putInt(BROWSER_TEXT_SIZE, percent);
            return this;
        }

        Editor browserPageZoom(int percent) {
            plain.putInt(BROWSER_PAGE_ZOOM, percent);
            return this;
        }

        Editor browserDark(boolean value) {
            plain.putBoolean(BROWSER_DARK, value);
            return this;
        }

        Editor browserBackAfter(int seconds) {
            plain.putInt(BROWSER_BACK_AFTER, seconds);
            return this;
        }

        /**
         * One value from a settings file, already checked by SettingsImport: only a
         * key the file may carry, stored with its own type.
         */
        Editor imported(String key, Object value) {
            Character type = SettingsTransfer.typeOf(key);
            if (type == null) {
                return this;
            }
            switch (type) {
                case 'b': plain.putBoolean(key, (Boolean) value); break;
                case 'i': plain.putInt(key, (Integer) value); break;
                case 'l': plain.putLong(key, ((Number) value).longValue()); break;
                default: plain.putString(key, (String) value); break;
            }
            return this;
        }

        /** One password from a settings file, a key the file may carry only. */
        Editor importedSecret(String key, String value) {
            if (java.util.Arrays.asList(SettingsTransfer.SECRETS).contains(key)
                    && !value.isEmpty()) {
                secrets.put(key, value);
            }
            return this;
        }

        Editor cameras(java.util.List<Cameras.Camera> cameras) {
            plain.putString(CAMERAS, Cameras.format(cameras));
            return this;
        }

        Editor cameraGroups(java.util.List<Cameras.Group> groups) {
            plain.putString(CAMERA_GROUPS, Cameras.formatGroups(groups));
            return this;
        }

        Editor cameraLogins(java.util.Map<String, Cameras.Login> logins) {
            secrets.put(CAMERA_LOGINS, Cameras.formatLogins(logins));
            return this;
        }

        Editor certificatePins(java.util.Map<String, String> pins) {
            plain.putString(CERTIFICATE_PINS, CertificatePins.format(pins));
            return this;
        }

        Editor browserPageCamera(boolean value) {
            plain.putBoolean(BROWSER_PAGE_CAMERA, value);
            return this;
        }

        Editor browserPageMicrophone(boolean value) {
            plain.putBoolean(BROWSER_PAGE_MICROPHONE, value);
            return this;
        }

        /** The sign-in name; an empty one clears the password with it. */
        Editor browserUser(String value) {
            secrets.put(BROWSER_USER, value);
            if (value.isEmpty()) {
                secrets.put(BROWSER_PASSWORD, "");
            }
            return this;
        }

        Editor browserPassword(String value) {
            secrets.put(BROWSER_PASSWORD, value);
            return this;
        }

        /** The light on or off; a null colour or a percent of -1 keeps what is stored. */
        Editor statusLight(boolean on, Integer color, int percent) {
            plain.putBoolean(STATUS_LIGHT_ON, on);
            if (color != null) {
                plain.putInt(STATUS_LIGHT_COLOR, color & 0xFFFFFF);
            }
            if (percent != -1) {
                plain.putInt(STATUS_LIGHT_PERCENT, percent);
            }
            return this;
        }

        /** Joined: the fleet, and its manager's key as the one key this panel trusts so far. */
        Editor joinFleet(String id, String name, String managerSpki) {
            plain.putString(FLEET_ID, id);
            plain.putString(FLEET_NAME, name.trim());
            plain.putString(FLEET_TRUSTED, managerSpki);
            plain.putString(FLEET_REVOKED, "");
            plain.remove(FLEET_DOCUMENT);
            // The history is one membership's: a new fleet starts with none.
            writeFleetHistory(storageContext, new java.util.ArrayList<String>());
            return this;
        }

        /** Left: no fleet, and no key trusted or revoked; the panel keeps its id and name. */
        Editor leaveFleet(Context context) {
            plain.remove(FLEET_ID);
            plain.remove(FLEET_NAME);
            plain.remove(FLEET_TRUSTED);
            plain.remove(FLEET_REVOKED);
            plain.remove(FLEET_DOCUMENT);
            writeFleetHistory(storageContext, new java.util.ArrayList<String>());
            for (String key : FleetDocument.stripSecrets(fleetDocumentOf(context)).keySet()) {
                secrets.put(FLEET_SECRET + key, "");
            }
            return this;
        }

        /**
         * The fleet document this panel took or wrote, already checked and merged
         * with the one held: it names the fleet, its members' keys are the ones this panel
         * trusts, and its revoked keys the ones it refuses. The first one written here also
         * founds the fleet. The profile's secrets go to the SecretStore and the
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
            String stored = FleetDocument.canonical(document);
            plain.putString(FLEET_DOCUMENT, stored);
            // History: the last versions held, newest first, the stored copy of
            // each (secrets blanked like the list itself); the same text twice is kept once.
            java.util.List<String> history = fleetHistory(storageContext);
            if (history.isEmpty() || !history.get(0).equals(stored)) {
                history.add(0, stored);
                while (history.size() > FLEET_HISTORY_SIZE) {
                    history.remove(history.size() - 1);
                }
                writeFleetHistory(storageContext, history);
            }
            return this;
        }

        Editor volumeButtonsDisabled(boolean value) {
            plain.putBoolean(VOLUME_BUTTONS_DISABLED, value);
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
     * Ambient's settings alone, without the secrets a full load decrypts: the activity's
     * clock reads them every second, and a stored value this build does not know reads as the
     * default rather than being refused, the same rule as {@link #displayOffMethodOf}.
     */
    static ScreensaverPolicy.Settings screensaverOf(Context context) {
        SharedPreferences preferences = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = preferences.getString(SCREENSAVER_MODE, ScreensaverPolicy.OFF);
        String onWake = preferences.getString(SCREENSAVER_ON_WAKE,
                ScreensaverPolicy.WAKE_SCREENSAVER);
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
        SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // The Ambient sensor was called screensaver before 0.7: a switch stored under that name
        // still counts until the switch is set again.
        if (Sensors.SCREENSAVER.id.equals(id) && !prefs.contains("sensor_" + id)) {
            return prefs.getBoolean("sensor_screensaver", fallback);
        }
        return prefs.getBoolean("sensor_" + id, fallback);
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

    /** How many versions of the fleet's list a member keeps. */
    static final int FLEET_HISTORY_SIZE = 20;
    /** Beside the list, in device-protected storage: one stored document per line, newest first. */
    private static final String FLEET_HISTORY_FILE = "fleet_history.jsonl";

    /** The versions of the fleet's list this panel held, newest first, as stored (see history). */
    static java.util.List<String> fleetHistory(Context context) {
        java.util.List<String> history = new java.util.ArrayList<>();
        java.io.File file = new java.io.File(storageContext(context).getFilesDir(),
                FLEET_HISTORY_FILE);
        if (!file.exists()) {
            return history;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    history.add(line);
                }
            }
        } catch (java.io.IOException unreadable) {
            android.util.Log.w("MuralisFleet", "The fleet history could not be read", unreadable);
        }
        return history;
    }

    private static void writeFleetHistory(Context storageContext, java.util.List<String> history) {
        java.io.File file = new java.io.File(storageContext.getFilesDir(), FLEET_HISTORY_FILE);
        java.io.File fresh = new java.io.File(storageContext.getFilesDir(),
                FLEET_HISTORY_FILE + ".tmp");
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(fresh), java.nio.charset.StandardCharsets.UTF_8)) {
            for (String text : history) {
                writer.write(text);
                writer.write('\n');
            }
        } catch (java.io.IOException failed) {
            android.util.Log.w("MuralisFleet", "The fleet history could not be written", failed);
            return;
        }
        if (!fresh.renameTo(file)) {
            android.util.Log.w("MuralisFleet", "The fleet history could not be replaced");
        }
    }

    /** The alert settings as stored, the defaults where unset; see FleetAlerts.Settings. */
    static FleetAlerts.Settings alertsOf(Context context) {
        return alertsOf(storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    private static FleetAlerts.Settings alertsOf(SharedPreferences preferences) {
        FleetAlerts.Settings alerts = new FleetAlerts.Settings();
        alerts.offlineMinutes = preferences.getLong(ALERT_OFFLINE_MINUTES, alerts.offlineMinutes);
        alerts.pageError = preferences.getBoolean(ALERT_PAGE_ERROR, alerts.pageError);
        alerts.batteryBelow = preferences.getLong(ALERT_BATTERY_BELOW, alerts.batteryBelow);
        alerts.temperatureAbove = preferences.getLong(ALERT_TEMPERATURE_ABOVE,
                alerts.temperatureAbove);
        alerts.combinationUsed = preferences.getBoolean(ALERT_COMBINATION_USED,
                alerts.combinationUsed);
        alerts.pinLockout = preferences.getBoolean(ALERT_PIN_LOCKOUT, alerts.pinLockout);
        alerts.mqttTopic = preferences.getString(ALERT_MQTT_TOPIC, "");
        alerts.webhookUrl = preferences.getString(ALERT_WEBHOOK_URL, "");
        return alerts;
    }

    /**
     * One alert setting from a surface's text, checked ({@link FleetAlerts#problem}) and
     * stored; the refusal, or null when saved.
     */
    static String saveAlertSetting(Context context, String key, String value) {
        String problem = FleetAlerts.problem(key, value);
        if (problem != null) {
            return problem;
        }
        String text = value == null ? "" : value.trim();
        Editor editor = edit(context);
        switch (key) {
            case ALERT_OFFLINE_MINUTES: editor.alertOfflineMinutes(Long.parseLong(text)); break;
            case ALERT_BATTERY_BELOW: editor.alertBatteryBelow(Long.parseLong(text)); break;
            case ALERT_TEMPERATURE_ABOVE:
                editor.alertTemperatureAbove(Long.parseLong(text));
                break;
            case ALERT_PAGE_ERROR: editor.alertPageError(isOn(text)); break;
            case ALERT_COMBINATION_USED: editor.alertCombinationUsed(isOn(text)); break;
            case ALERT_PIN_LOCKOUT: editor.alertPinLockout(isOn(text)); break;
            case ALERT_MQTT_TOPIC: editor.alertMqttTopic(text); break;
            default: editor.alertWebhookUrl(text);
        }
        editor.apply();
        return null;
    }

    private static boolean isOn(String text) {
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
    }

    private static final String FLEET_NAME = "fleet_name";
    /** This panel's own key hash, kept plain once the key was read, for lookups before it is. */
    private static final String FLEET_SELF_KEY = "fleet_self_key";

    static void rememberFleetSelfKey(Context context, String spki) {
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(FLEET_SELF_KEY, spki).apply();
    }

    static String fleetSelfKeyOf(Context context) {
        String runtime = KioskRuntimeState.fleetKey();
        return runtime.isEmpty() ? storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FLEET_SELF_KEY, "")
                : runtime;
    }

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
     * The owner saw, 2026-10-04). This panel's alone: a place on one screen means nothing on another.
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
     * The browser options as stored; a value this build would refuse reads as its default. The
     * sign-in name is decrypted here, the password only by {@link #browserPasswordOf}.
     */
    static BrowserOptions.Settings browserOf(Context context) {
        SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int text = prefs.getInt(BROWSER_TEXT_SIZE, BrowserOptions.DEFAULT_TEXT_SIZE);
        int zoom = prefs.getInt(BROWSER_PAGE_ZOOM, BrowserOptions.AUTO_ZOOM);
        int back = prefs.getInt(BROWSER_BACK_AFTER, 0);
        String user = new SecretStore(storageContext(context)).getOrNull(BROWSER_USER);
        return new BrowserOptions.Settings(prefs.getString(BROWSER_USER_AGENT, ""),
                BrowserOptions.parseTextSize(String.valueOf(text)) != null
                        ? text : BrowserOptions.DEFAULT_TEXT_SIZE,
                BrowserOptions.parsePageZoom(String.valueOf(zoom)) != null
                        ? zoom : BrowserOptions.AUTO_ZOOM,
                prefs.getBoolean(BROWSER_DARK, false),
                BrowserOptions.parseBackAfter(String.valueOf(back)) != null ? back : 0,
                user == null ? "" : user,
                prefs.getBoolean(BROWSER_PAGE_CAMERA, false),
                prefs.getBoolean(BROWSER_PAGE_MICROPHONE, false));
    }

    /** The settings a setup QR carried, kept until the service applies them once. */
    private static final String PROVISIONED_SETTINGS = "provisioned_settings";
    private static final String PROVISIONED_SETTINGS_KEPT = "provisioned_settings_kept";

    /**
     * Keeps the settings file a setup QR carried in its admin extras for the service
     * to apply at its start, and asks a service already running to apply them now; nothing when
     * the QR carried none.
     */
    static void keepProvisionedSettings(Context context, android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        android.os.PersistableBundle extras = intent.getParcelableExtra(
                android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        keepInvite(context, extras == null ? null : extras.getString("muralis_invite"));
        String packed = extras == null ? null : extras.getString("muralis_settings_z");
        String text = packed == null ? null : SettingsTransfer.unpacked(packed);
        SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Kept once: the setup may hand the extras both to the compliance screen and to the
        // receiver, and a second copy taken after the service applied the first would put the
        // QR's settings back over the person's changes at the next start.
        if (text != null && !text.isEmpty() && !prefs.getBoolean(PROVISIONED_SETTINGS_KEPT, false)) {
            prefs.edit().putString(PROVISIONED_SETTINGS, text)
                    .putBoolean(PROVISIONED_SETTINGS_KEPT, true).commit();
            reloadOnOwner(context);
        }
    }

    /** Where an invitation QR's extra waits for the first start (kiosk_runtime). */
    private static final String FLEET_INVITE = "fleet_invite";

    /**
     * Keeps an invitation QR's extra ({@link FleetInvite}) for the first start: the code and
     * where the inviting panel listens. In device-protected storage like the other runtime
     * state, never logged, removed once this panel is in the fleet or the invitation is over.
     */
    private static void keepInvite(Context context, String extra) {
        if (extra == null || FleetJoin.parseQr(extra) == null || FleetInvite.until(extra) == 0) {
            return;
        }
        SharedPreferences runtime = storageContext(context)
                .getSharedPreferences("kiosk_runtime", Context.MODE_PRIVATE);
        if (!runtime.contains(FLEET_INVITE)) {
            runtime.edit().putString(FLEET_INVITE, extra).commit();
            // The service may have started before the setup handed the extras over: it starts
            // knocking now (KioskService.inviteKnock).
            reloadOnOwner(context);
        }
    }

    /**
     * Asks a running service to take what the setup kept. Not before Muralis is the owner: the
     * setup's first question comes earlier, and the service that onEnabled starts reads it then.
     */
    private static void reloadOnOwner(Context context) {
        if (KioskDeviceAdminReceiver.isDeviceOwner(context)) {
            KioskService.reloadConfiguration(context);
        }
    }

    /** The kept invitation while it lasts and this panel is in no fleet, else null (and gone). */
    static String pendingInvite(Context context) {
        SharedPreferences runtime = storageContext(context)
                .getSharedPreferences("kiosk_runtime", Context.MODE_PRIVATE);
        String extra = runtime.getString(FLEET_INVITE, null);
        if (extra != null && (!fleetIdOf(context).isEmpty()
                || System.currentTimeMillis() >= FleetInvite.until(extra))) {
            dropInvite(context);
            return null;
        }
        return extra;
    }

    static void dropInvite(Context context) {
        storageContext(context).getSharedPreferences("kiosk_runtime", Context.MODE_PRIVATE)
                .edit().remove(FLEET_INVITE).apply();
    }

    /** The kept settings file, removed as it is read, or null. */
    static String takeProvisionedSettings(Context context) {
        SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String text = prefs.getString(PROVISIONED_SETTINGS, null);
        if (text != null) {
            prefs.edit().remove(PROVISIONED_SETTINGS).commit();
        }
        return text;
    }

    /** Every stored setting as Android gives it back, for the settings file to pick from. */
    static java.util.Map<String, ?> storedSettings(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getAll();
    }

    /**
     * The stored settings as a settings file carries them: a value stored under a name from
     * before 0.7 in today's form, and without the calibrations a sensor keeps for its own
     * hardware (they need a hand at the panel), which another panel's import would refuse.
     */
    static java.util.Map<String, Object> transferableSettings(Context context) {
        java.util.Map<String, Object> settings = new java.util.HashMap<>(storedSettings(context));
        Object wake = settings.get(SCREENSAVER_ON_WAKE);
        if (wake != null && !ScreensaverPolicy.isOnWake(String.valueOf(wake))) {
            settings.put(SCREENSAVER_ON_WAKE, ScreensaverPolicy.WAKE_SCREENSAVER);
        }
        Object oldSensor = settings.remove("sensor_screensaver");
        if (oldSensor != null && !settings.containsKey("sensor_" + Sensors.SCREENSAVER.id)) {
            settings.put("sensor_" + Sensors.SCREENSAVER.id, oldSensor);
        }
        for (String key : new java.util.ArrayList<>(settings.keySet())) {
            if (key.startsWith("sensor_option_") && !key.startsWith("sensor_option_beacon_")
                    && String.valueOf(Sensors.checkOption(key.substring("sensor_option_".length()),
                            String.valueOf(settings.get(key)))).startsWith("no sensor setting")) {
                settings.remove(key);
            }
        }
        return settings;
    }

    /** The passwords a settings file may carry, read only when the person asks for them. */
    static java.util.Map<String, String> transferableSecrets(Context context) {
        SecretStore store = new SecretStore(storageContext(context));
        java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
        for (String key : SettingsTransfer.SECRETS) {
            String value = store.getOrNull(key);
            if (value != null && !value.isEmpty()) {
                secrets.put(key, value);
            }
        }
        return secrets;
    }

    static java.util.List<Cameras.Camera> camerasOf(Context context) {
        return Cameras.parse(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CAMERAS, ""));
    }

    static String cameraLayoutOf(Context context) {
        String layout = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CAMERA_LAYOUT, Cameras.LAYOUT_AUTO);
        return Cameras.isLayout(layout) ? layout : Cameras.LAYOUT_AUTO;
    }

    static String cameraBigOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CAMERA_BIG, "");
    }

    static boolean cameraNamesOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(CAMERA_NAMES, false);
    }

    static String dashboardCamerasOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(DASHBOARD_CAMERAS, "");
    }

    static java.util.List<Cameras.Group> cameraGroupsOf(Context context) {
        return Cameras.parseGroups(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CAMERA_GROUPS, ""));
    }

    /** The cameras' sign-ins, read only to fetch a camera or to save one; empty when none. */
    static java.util.Map<String, Cameras.Login> cameraLoginsOf(Context context) {
        return Cameras.parseLogins(new SecretStore(storageContext(context))
                .getOrNull(CAMERA_LOGINS));
    }

    /** The trusted certificates, read for every certificate Android refuses. */
    static java.util.Map<String, String> certificatePinsOf(Context context) {
        return CertificatePins.parse(storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CERTIFICATE_PINS, ""));
    }

    /** Just the time before going back, read every second while the page is away. */
    static int browserBackAfterOf(Context context) {
        int back = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(BROWSER_BACK_AFTER, 0);
        return BrowserOptions.parseBackAfter(String.valueOf(back)) != null ? back : 0;
    }

    /** The page's sign-in password, read only when a page asks for it; empty when none. */
    static String browserPasswordOf(Context context) {
        String stored = new SecretStore(storageContext(context)).getOrNull(BROWSER_PASSWORD);
        return stored == null ? "" : stored;
    }

    /** The status light as stored, for the frame, the status document and both surfaces. */
    /** App lock's chosen package, empty when off; a plain read, cheap enough for a layout pass. */
    static String appLockOf(Context context) {
        return storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(APP_LOCK, "");
    }

    /** What App lock's app was called when it was chosen; its package when that was not known. */
    static String appLockLabelOf(Context context) {
        SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String label = prefs.getString(APP_LOCK_LABEL, "");
        return label.isEmpty() ? prefs.getString(APP_LOCK, "") : label;
    }

    static StatusLight.State statusLightOf(Context context) {
        SharedPreferences prefs = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int percent = prefs.getInt(STATUS_LIGHT_PERCENT, StatusLight.DEFAULT_PERCENT);
        return new StatusLight.State(prefs.getBoolean(STATUS_LIGHT_ON, false),
                prefs.getInt(STATUS_LIGHT_COLOR, StatusLight.DEFAULT_COLOR),
                StatusLight.isPercent(percent) ? percent : StatusLight.DEFAULT_PERCENT);
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
     * The boot in which Ambient was put on the glass, or -1: the same shape as the
     * visual-off record and for the same reason, so the nightly restart and the WebView rebuilds
     * bring Ambient back rather than lighting the page for the idle time at four in
     * the morning. Ends with Ambient (a touch, a command, the settings screen) or with
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

    /**
     * The network speaker's UDN, minted and stored the first time it is asked for, so a
     * controller keeps knowing the panel as the same speaker across restarts and renames.
     * commit() rather than apply(), like the device id, because the caller announces it at once.
     */
    static String networkSpeakerUdnOf(Context context) {
        SharedPreferences preferences = storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String udn = preferences.getString(NETWORK_SPEAKER_UDN, "");
        if (!DlnaProtocol.isUdn(udn)) {
            udn = DlnaProtocol.newUdn();
            preferences.edit().putString(NETWORK_SPEAKER_UDN, udn).commit();
        }
        return udn;
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
