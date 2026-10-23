/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a settings file read back by {@link SettingsTransfer}, from the web admin's
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
                changes.put(entry.getKey(),
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
        List<Integrations.Instance> integrationsBefore = KioskConfig.integrationsOf(context);
        List<Integrations.Instance> integrationsAfter = integrationsBefore;
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
                    if ("integrations".equals(key)) {
                        integrationsAfter = Integrations.parseInstances((String) value);
                    }
                }
            }
            if (problem != null) {
                refused.add(key + ": " + problem);
            }
        }
        for (Map.Entry<String, String> secret : contents.secrets.entrySet()) {
            if ("http_admin_password".equals(secret.getKey())
                    && secret.getValue().length() < HttpAdminServer.MIN_ADMIN_PASSWORD_LENGTH) {
                refused.add("http_admin_password: shorter than "
                        + HttpAdminServer.MIN_ADMIN_PASSWORD_LENGTH + " characters");
                continue;
            }
            if ("escape_pin_hash".equals(secret.getKey()) && !EscapePin.wellFormed(secret.getValue())) {
                refused.add("escape_pin_hash: not a PIN this app wrote");
                continue;
            }
            if ("integration_secrets".equals(secret.getKey())) {
                // Each as the integration keeps it; a name out of shape is left out.
                java.util.Map<String, Object> all;
                try {
                    all = TinyJson.object(TinyJson.parse(secret.getValue()));
                } catch (IllegalArgumentException unreadable) {
                    all = null;
                }
                if (all == null) {
                    refused.add("integration_secrets: not readable");
                    continue;
                }
                SecretStore store = new SecretStore(KioskConfig.storageContext(context));
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (Integrations.Instance instance : integrationsAfter) {
                    ids.add(instance.id);
                }
                for (java.util.Map.Entry<String, Object> one : all.entrySet()) {
                    // Only the keys of integrations the panel holds after the import.
                    if (one.getKey().matches(Integrations.INSTANCE_SHAPE
                            + "_[a-z0-9_]{1,24}") && one.getValue() instanceof String
                            && ids.contains(one.getKey().substring(0, one.getKey().indexOf('_')))) {
                        store.put("integration_" + one.getKey(), (String) one.getValue());
                    }
                }
                continue;
            }
            if ("camera_logins".equals(secret.getKey())) {
                // In the form the panel writes it, anything unreadable left out.
                editor.cameraLogins(Cameras.parseLogins(secret.getValue()));
                continue;
            }
            editor.importedSecret(secret.getKey(), secret.getValue());
        }
        // The keys of integrations the imported list dropped go with them, as on Remove.
        java.util.Set<String> keptIds = new java.util.HashSet<>();
        for (Integrations.Instance instance : integrationsAfter) {
            keptIds.add(instance.id);
        }
        SecretStore integrationKeys = new SecretStore(KioskConfig.storageContext(context));
        for (Integrations.Instance gone : integrationsBefore) {
            if (!keptIds.contains(gone.id)) {
                IntegrationSettings.clearSecrets(integrationKeys, gone.id, gone.definition);
            }
        }
        editor.apply();
        if (alertsChanged) {
            KioskService.alertsChanged(context);
        }
        KioskService.refreshSensorsSoon(context);
        KioskService.refreshIntegrationsSoon(context, null);
        KioskService.reloadConfiguration(context);
        KioskService.settingsImported(context);
        KioskService.publishTelemetrySoon(context);
        return refused;
    }

    /** A JSON text value in the form the panel writes it, so a reformatted file stores the same. */
    private static Object normalised(String key, Object value) {
        switch (key) {
            case "cameras":
                return Cameras.format(Cameras.parse((String) value));
            case "camera_groups":
                return Cameras.formatGroups(Cameras.parseGroups((String) value));
            case "certificate_pins":
                return CertificatePins.format(CertificatePins.parse((String) value));
            case "integrations":
                return Integrations.storeInstances(Integrations.parseInstances((String) value));
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
                if (context.getPackageName().equals(text)) {
                    return "Muralis cannot lock the panel to itself";
                }
                if (!text.isEmpty() && AppLock.doors(context).contains(text)) {
                    return "it opens Android's settings, a home screen or the Play Store";
                }
                return text.isEmpty()
                        || context.getPackageManager().getLaunchIntentForPackage(text) != null
                        ? null : "no installed app " + text + " has anything to open";
            case "dashboard_shows":
                return dashboardShowsProblem(context, text, imported);
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
            case "network_speaker_port": {
                String problem = KioskCommandDispatcher.validateSpeakerPort(number,
                        current.networkSpeakerPort, adminPort, memberPort);
                if (problem == null && number != current.networkSpeakerPort
                        && !SettingProbe.portFree(number)) {
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
            case "ambient_mode":
                return ScreensaverPolicy.isMode(text) ? null
                        : "must be off, dim, film, url or pictures";
            case "ambient_on_wake":
                return ScreensaverPolicy.isOnWake(text) ? null : "must be ambient or dashboard";
            case "ambient_transition":
                return ScreensaverPolicy.isTransition(text) ? null : "must be cut, fade or slide";
            case "ambient_picture_fit":
                return ScreensaverPolicy.isFit(text) ? null : "must be fit, fill, stretch or actual";
            case "ambient_credit_corner":
                return ScreensaverPolicy.isCorner(text) ? null : "not a corner";
            case "ambient_url":
                return text.isEmpty() ? null : KioskCommandDispatcher.validateDashboardUrl(text);
            case "ambient_start_after_s":
            case "ambient_off_s":
                return ScreensaverPolicy.parseSeconds(String.valueOf(number)) == null
                        ? ScreensaverPolicy.SECONDS_RULE : null;
            case "ambient_picture_s":
                return ScreensaverPolicy.parsePictureSeconds(String.valueOf(number)) == null
                        ? ScreensaverPolicy.PICTURE_SECONDS_RULE : null;
            case "ambient_dim_percent":
                return number >= 1 && number <= 100 ? null : ScreensaverPolicy.DIM_RULE;
            case "settings_sequence":
            case "launcher_sequence": {
                if (!EscapeSequence.isValid(EscapeSequence.parse(text))) {
                    return "not a corner combination this panel can record";
                }
                // The two must differ, as the web admin's box and the recorder insist, or one
                // of the ways out stops opening.
                // The other one from the file only when it is itself valid, since a refused one
                // leaves the stored one in place.
                boolean settings = "settings_sequence".equals(key);
                Object other = imported.get(settings ? "launcher_sequence" : "settings_sequence");
                String otherText = other instanceof String
                        && EscapeSequence.isValid(EscapeSequence.parse(((String) other).trim()))
                        ? ((String) other).trim()
                        : settings ? current.launcherSequence : current.settingsSequence;
                return EscapeSequence.parse(text).equals(EscapeSequence.parse(otherText))
                        ? "the settings and launcher combinations must be different" : null;
            }
            case "automations": {
                List<Automations.Rule> rules = Automations.parse(text);
                for (Automations.Rule rule : rules) {
                    if (Integrations.isSensorId(rule.sensor)
                            && Integrations.lookup.valueOf(rule.sensor) == null) {
                        // A rule on an integration this panel does not hold (yet) is kept as
                        // it came, as one is when its integration is removed: it waits, and
                        // never fires until the integration is added here.
                        continue;
                    }
                    String problem = Automations.validate(rule, Sensors.names());
                    if (problem != null) {
                        return "the rule \"" + rule.name + "\": " + problem;
                    }
                }
                return text.isEmpty() || !rules.isEmpty() ? null : "not a list of rules";
            }
            case "cameras":
                return Cameras.parse(text).isEmpty() && !text.equals("[]") && !text.isEmpty()
                        ? "not a list of cameras" : null;
            case "integrations": {
                // Each integration this version knows by the checks its own Save takes; its
                // secrets come in the sealed block, so a secret box counts as kept. One this
                // version does not know is kept as it came, as the panel keeps such entries.
                List<Integrations.Instance> instances = Integrations.parseInstances(text);
                if (instances.isEmpty() && !text.isEmpty() && !text.equals("[]")) {
                    return "not a list of integrations";
                }
                if (instances.size() > Integrations.MAX_INSTANCES) {
                    return "more than " + Integrations.MAX_INSTANCES + " integrations";
                }
                for (Integrations.Instance instance : instances) {
                    Integrations.Definition definition =
                            Integrations.DEFINITIONS.get(instance.definition);
                    if (definition == null) {
                        continue;
                    }
                    java.util.Set<String> secretFields = new java.util.HashSet<>();
                    for (Integrations.Field field : definition.fields) {
                        if (field.secret) {
                            secretFields.add(field.id);
                        }
                    }
                    String problem = Integrations.checkName(instance.name);
                    if (problem == null) {
                        problem = Integrations.check(definition, instance.settings, secretFields);
                    }
                    if (problem != null) {
                        return "the integration \"" + instance.name + "\": " + problem;
                    }
                }
                return null;
            }
            case "camera_groups":
                return Cameras.parseGroups(text).isEmpty() && !text.equals("[]") && !text.isEmpty()
                        ? "not a list of camera groups" : null;
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

    /**
     * What the dashboard shows, by the rule dashboard.show keeps: the app and the cameras it
     * names must be chosen, in the file or on the panel already. A refused app or camera in
     * the same file is not checked again here; the panel then reads the choice as the page.
     */
    private static String dashboardShowsProblem(Context context, String value,
            Map<String, Object> imported) {
        if (!KioskConfig.isDashboardShows(value)) {
            return "must be page, app or cameras";
        }
        String app = imported.get("app_lock") instanceof String
                ? ((String) imported.get("app_lock")).trim() : KioskConfig.chosenAppOf(context);
        List<Cameras.Camera> cameras = imported.get("cameras") instanceof String
                ? Cameras.parse((String) imported.get("cameras")) : KioskConfig.camerasOf(context);
        List<Cameras.Group> groups = imported.get("camera_groups") instanceof String
                ? Cameras.parseGroups((String) imported.get("camera_groups"))
                : KioskConfig.cameraGroupsOf(context);
        String chosen = imported.get("dashboard_cameras") instanceof String
                ? ((String) imported.get("dashboard_cameras")).trim()
                : KioskConfig.dashboardCamerasOf(context);
        boolean exists = !chosen.isEmpty() && (Cameras.find(cameras, chosen) != null
                || Cameras.findGroup(groups, chosen) != null);
        return KioskCommandDispatcher.dashboardShowProblem(value, app,
                !cameras.isEmpty() || !groups.isEmpty(), exists);
    }

    private static String permitted(Context context, Object value, String permission) {
        return Boolean.TRUE.equals(value) && context.checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED ? BrowserOptions.PERMISSION_RULE : null;
    }
}
