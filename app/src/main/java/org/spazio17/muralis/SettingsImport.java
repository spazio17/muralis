/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a settings file read back by {@link SettingsTransfer} (block 8), from the web admin's
 * Import or the provisioning QR: every value through the same check a person's change takes, a
 * value the panel cannot take refused with its reason and the rest applied. What changes is
 * listed first for the web admin's preview.
 */
final class SettingsImport {
    private SettingsImport() {
    }

    /** The settings that differ from what is stored, by the name the file uses. */
    static Map<String, String> changes(Context context, SettingsTransfer.Contents contents) {
        Map<String, ?> stored = KioskConfig.storedSettings(context);
        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : contents.settings.entrySet()) {
            Object now = stored.get(entry.getKey());
            Object next = entry.getValue();
            boolean same = now != null && (now instanceof Number && next instanceof Number
                    ? ((Number) now).longValue() == ((Number) next).longValue() : now.equals(next));
            if (!same) {
                String text = String.valueOf(next);
                changes.put(SettingsTransfer.publicName(entry.getKey()),
                        text.length() > 80 ? text.substring(0, 80) + "…" : text);
            }
        }
        for (String key : contents.secrets.keySet()) {
            changes.put(key, "(a password)");
        }
        return changes;
    }

    /** Applies the file; returns one line per value refused, empty when all were taken. */
    static List<String> apply(Context context, SettingsTransfer.Contents contents) {
        List<String> refused = new ArrayList<>();
        KioskConfig current = KioskConfig.load(context);
        Map<String, Object> settings = contents.settings;
        int adminPort = settings.containsKey("http_port") ? (Integer) settings.get("http_port")
                : current.httpPort;
        int memberPort = settings.containsKey("fleet_port")
                ? (Integer) settings.get("fleet_port") : current.fleetPort;
        KioskConfig.Editor editor = KioskConfig.edit(context);
        boolean alertsChanged = false;
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String problem;
            if (key.startsWith("alert_")) {
                problem = KioskConfig.saveAlertSetting(context, key, String.valueOf(value));
                alertsChanged |= problem == null;
            } else if (key.startsWith("sensor_option_")) {
                String option = key.substring("sensor_option_".length());
                problem = Sensors.checkOption(option, (String) value);
                if (problem == null) {
                    editor.sensorOption(option, Sensors.cleanOption(option, (String) value));
                }
            } else {
                problem = check(context, key, value, current, adminPort, memberPort, settings);
                if (problem == null) {
                    editor.imported(key, normalised(key, value));
                }
            }
            if (problem != null) {
                refused.add(SettingsTransfer.publicName(key) + ": " + problem);
            }
        }
        for (Map.Entry<String, String> secret : contents.secrets.entrySet()) {
            if ("http_admin_password".equals(secret.getKey())
                    && secret.getValue().length() < HttpAdminServer.MIN_ADMIN_PASSWORD_LENGTH) {
                refused.add("http_admin_password: shorter than "
                        + HttpAdminServer.MIN_ADMIN_PASSWORD_LENGTH + " characters");
                continue;
            }
            editor.importedSecret(secret.getKey(), secret.getValue());
        }
        editor.apply();
        if (alertsChanged) {
            KioskService.alertsChanged(context);
        }
        KioskService.refreshSensorsSoon(context);
        KioskService.reloadConfiguration(context);
        KioskService.publishTelemetrySoon(context);
        return refused;
    }

    /** A JSON text value in the form the panel writes it, so a reformatted file stores the same. */
    private static Object normalised(String key, Object value) {
        switch (key) {
            case "popup_cameras":
                return PopupCameras.format(PopupCameras.parse((String) value));
            case "certificate_pins":
                return CertificatePins.format(CertificatePins.parse((String) value));
            default:
                // Trimmed, as the boxes store what a person types: the checks read it trimmed.
                return value instanceof String ? ((String) value).trim() : value;
        }
    }

    /** The check a person's change to this key takes, or null when the value can be stored. */
    private static String check(Context context, String key, Object value, KioskConfig current,
            int adminPort, int memberPort, Map<String, Object> imported) {
        String text = value instanceof String ? ((String) value).trim() : null;
        int number = value instanceof Integer ? (Integer) value : 0;
        switch (key) {
            case "dashboard_url":
                return text.isEmpty() ? null : KioskCommandDispatcher.validateDashboardUrl(text);
            case "mqtt_host":
                return text.length() > 253 || text.contains(" ") ? "not a host name" : null;
            case "mqtt_port":
                return number >= 1 && number <= 65535 ? null : "not between 1 and 65535";
            case "app_lock":
                if (!text.isEmpty() && AppLock.doors(context).contains(text)) {
                    return "it opens Android's settings, a home screen or the Play Store";
                }
                return text.isEmpty()
                        || context.getPackageManager().getLaunchIntentForPackage(text) != null
                        ? null : "no installed app " + text + " has anything to open";
            case "mqtt_tls_pin":
                return text.isEmpty() || CertificatePins.isFingerprint(text) ? null
                        : "not a certificate's SHA-256";
            case "http_port": {
                String problem = KioskCommandDispatcher.validateAdminPort(number,
                        current.httpPort, memberPort);
                if (problem == null && number != current.httpPort && !SettingProbe.portFree(number)) {
                    problem = "port " + number + " is already in use on this device";
                }
                return problem;
            }
            case "fleet_port": {
                String problem = KioskCommandDispatcher.validateMemberPort(number,
                        current.fleetPort, adminPort);
                if (problem == null && number != current.fleetPort && !SettingProbe.portFree(number)) {
                    problem = "port " + number + " is already in use on this device";
                }
                return problem;
            }
            case "browser_user_agent":
                return BrowserOptions.checkUserAgent(text);
            case "browser_text_size":
                return BrowserOptions.parseTextSize(String.valueOf(number)) == null
                        ? BrowserOptions.TEXT_SIZE_RULE : null;
            case "browser_page_zoom":
                return BrowserOptions.parsePageZoom(String.valueOf(number)) == null
                        ? BrowserOptions.ZOOM_RULE : null;
            case "browser_back_after_s":
                return BrowserOptions.parseBackAfter(String.valueOf(number)) == null
                        ? BrowserOptions.BACK_AFTER_RULE : null;
            case "browser_dark":
                return Boolean.TRUE.equals(value) && !BrowserOptions.darkLookPossible(
                        android.os.Build.VERSION.SDK_INT) ? BrowserOptions.DARK_RULE : null;
            case "browser_page_camera":
                return permitted(context, value, android.Manifest.permission.CAMERA);
            case "browser_page_microphone":
                return permitted(context, value, android.Manifest.permission.RECORD_AUDIO);
            case "status_light_color":
                return number >= 0 && number <= 0xFFFFFF ? null : StatusLight.COLOR_RULE;
            case "status_light_percent":
                return StatusLight.isPercent(number) ? null : StatusLight.PERCENT_RULE;
            case "orientation":
                return KioskConfig.ORIENTATION_AUTO.equals(text)
                        || KioskConfig.ORIENTATION_LANDSCAPE.equals(text)
                        || KioskConfig.ORIENTATION_PORTRAIT.equals(text) ? null
                        : "must be auto, landscape or portrait";
            case "display_off_method":
                if (!DisplayOffPolicy.isMethod(text)) {
                    return "must be auto, sleep or film";
                }
                return DisplayOffPolicy.SLEEP.equals(text) && !KioskService.isDeviceOwner(context)
                        ? "a real screen-off needs the device-owner install" : null;
            case "screensaver_mode":
                return ScreensaverPolicy.isMode(text) ? null
                        : "must be off, dim, film, url or pictures";
            case "screensaver_on_wake":
                return ScreensaverPolicy.isOnWake(text) ? null : "must be ambient or dashboard";
            case "screensaver_transition":
                return ScreensaverPolicy.isTransition(text) ? null : "must be none, fade or slide";
            case "screensaver_picture_fit":
                return ScreensaverPolicy.isFit(text) ? null : "must be fit, fill, stretch or actual";
            case "screensaver_credit_corner":
                return ScreensaverPolicy.isCorner(text) ? null : "not a corner";
            case "screensaver_url":
                return text.isEmpty() ? null : KioskCommandDispatcher.validateDashboardUrl(text);
            case "screensaver_idle_s":
            case "screensaver_off_s":
                return ScreensaverPolicy.parseSeconds(String.valueOf(number)) == null
                        ? ScreensaverPolicy.SECONDS_RULE : null;
            case "screensaver_picture_s":
                return ScreensaverPolicy.parsePictureSeconds(String.valueOf(number)) == null
                        ? ScreensaverPolicy.PICTURE_SECONDS_RULE : null;
            case "screensaver_dim_percent":
                return number >= 1 && number <= 100 ? null : ScreensaverPolicy.DIM_RULE;
            case "settings_sequence":
            case "launcher_sequence": {
                if (!EscapeSequence.isValid(EscapeSequence.parse(text))) {
                    return "not a corner combination this panel can record";
                }
                // The two must differ, as the web admin's box and the recorder insist, or one
                // of the ways out stops opening.
                boolean settings = "settings_sequence".equals(key);
                Object other = imported.get(settings ? "launcher_sequence" : "settings_sequence");
                String otherText = other instanceof String ? ((String) other).trim()
                        : settings ? current.launcherSequence : current.settingsSequence;
                return EscapeSequence.parse(text).equals(EscapeSequence.parse(otherText))
                        ? "the settings and launcher combinations must be different" : null;
            }
            case "automations": {
                List<Automations.Rule> rules = Automations.parse(text);
                for (Automations.Rule rule : rules) {
                    String problem = Automations.validate(rule, Sensors.names());
                    if (problem != null) {
                        return "the rule \"" + rule.name + "\": " + problem;
                    }
                }
                return text.isEmpty() || !rules.isEmpty() ? null : "not a list of rules";
            }
            case "popup_cameras":
                return PopupCameras.parse(text).isEmpty() && !text.equals("[]") && !text.isEmpty()
                        ? "not a list of cameras" : null;
            case "beacon_names":
            case "certificate_pins":
                return null;
            default:
                if (key.startsWith("sensor_")) {
                    return Sensors.byId(key.substring("sensor_".length())) == null
                            ? "no sensor has that name" : null;
                }
                return null;
        }
    }

    private static String permitted(Context context, Object value, String permission) {
        return Boolean.TRUE.equals(value) && context.checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED ? BrowserOptions.PERMISSION_RULE : null;
    }
}
