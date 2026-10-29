/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fleet's shared settings on this panel: which sections this panel shares, what
 * each section's values are here, and how a section the fleet shares is applied, through the
 * same commands and checks a person's own change goes through. The sections are
 * {@link FleetDocument#SECTIONS}; which ones are shared, and their values, live in the fleet
 * document, so every panel applies the same and a change on any panel reaches all of them
 * ({@link FleetServer#syncProfileSoon}).
 */
final class FleetProfile {
    private static final String TAG = "MuralisFleet";
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 8;

    private FleetProfile() {
    }

    /** This panel's current values of {@code section}, as the profile carries them. */
    static Map<String, Object> read(Context context, String section) {
        return read(context, section, KioskConfig.load(context));
    }

    /** The same from a config already loaded, for a page that reads every section. */
    static Map<String, Object> read(Context context, String section, KioskConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        switch (section) {
            case "dashboard":
                // What the dashboard shows, and the one choice that kind needs; the choices of
                // the other kinds are "" so a panel and its group compare equal.
                values.put("dashboard_shows", KioskConfig.dashboardShowsOf(context));
                values.put("dashboard_url", config.dashboardUrl);
                values.put("dashboard_app", KioskConfig.appLockOf(context));
                values.put("dashboard_cameras", KioskConfig.shownCamerasOf(context));
                values.put("dashboard_integration", KioskConfig.shownIntegrationOf(context));
                break;
            case "mqtt":
                values.put("mqtt_host", config.mqttHost);
                values.put("mqtt_port", (long) config.mqttPort);
                values.put("mqtt_username", config.mqttUsername);
                values.put("mqtt_password", config.mqttPassword);
                values.put("mqtt_tls", config.mqttTls);
                values.put("mqtt_tls_pin", config.mqttTlsPin);
                break;
            case "web_admin":
                values.put("web_admin_enabled", config.webAdminEnabled);
                values.put("http_port", (long) config.httpPort);
                values.put("http_admin_password", config.httpAdminPassword);
                break;
            case "display":
                values.put("orientation", config.orientation);
                values.put("display_off_method", config.displayOffMethod);
                // Not the brightness level: read back from the system it rounds, and two
                // panels would then correct each other forever.
                values.put("auto_brightness", KioskService.isAutoBrightnessOn(context));
                break;
            case "ambient": {
                ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
                values.put("mode", saver.mode);
                values.put("start_after_seconds", (long) saver.idleSeconds);
                values.put("off_seconds", (long) saver.offSeconds);
                values.put("url", saver.url);
                values.put("dim_percent", (long) saver.dimPercent);
                values.put("on_wake", saver.onWake);
                values.put("picture_seconds", (long) saver.pictureSeconds);
                values.put("transition", saver.transition);
                values.put("picture_fit", saver.pictureFit);
                values.put("shuffle", saver.shuffle);
                values.put("one_per_start", saver.onePerCycle);
                values.put("credit", saver.credit);
                values.put("credit_corner", saver.creditCorner);
                break;
            }
            case "speaker":
                values.put("network_speaker", config.networkSpeaker);
                values.put("network_speaker_port", (long) config.networkSpeakerPort);
                break;
            case "integrations": {
                java.util.List<Integrations.Instance> instances =
                        KioskConfig.integrationsOf(context);
                values.put("instances", Integrations.storeInstances(
                        IntegrationSettings.sharedPart(instances)));
                values.put("integration_secrets", IntegrationSettings.sharedSecrets(context));
                // A page's look (the Bitcoin clock's options) by instance id, so a clock looks
                // the same on every panel that shares it.
                Map<String, Object> looks = new LinkedHashMap<>();
                for (Integrations.Instance instance : instances) {
                    if (Integrations.hasPage(instance.definition)) {
                        looks.put(instance.id,
                                KioskConfig.clockOptionsOf(context, instance.id).toJson());
                    }
                }
                values.put("page_options", TinyJson.write(looks));
                break;
            }
            case "alerts":
                values.put("alert_offline_minutes", config.alerts.offlineMinutes);
                values.put("alert_back_online", config.alerts.backOnline);
                values.put("alert_page_error", config.alerts.pageError);
                values.put("alert_battery_below", config.alerts.batteryBelow);
                values.put("alert_temperature_above", config.alerts.temperatureAbove);
                values.put("alert_storage_below_mb", config.alerts.storageBelowMb);
                values.put("alert_update_available", config.alerts.updateAvailable);
                values.put("alert_shared_refused", config.alerts.sharedRefused);
                values.put("alert_combination_used", config.alerts.combinationUsed);
                values.put("alert_pin_lockout", config.alerts.pinLockout);
                values.put("alert_quiet_display_off", config.alerts.quietDisplayOff);
                values.put("alert_ntfy_url", config.alerts.ntfyUrl);
                values.put("alert_ntfy_token", config.alerts.ntfyToken);
                values.put("alert_chat_url", config.alerts.chatUrl);
                values.put("alert_mqtt_topic", config.alerts.mqttTopic);
                values.put("alert_webhook_url", config.alerts.webhookUrl);
                break;
            default:
                break;
        }
        return values;
    }

    /** Whether two value maps say the same, numbers compared as numbers. */
    static boolean same(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return a == b;
        }
        return FleetDocument.canonical(a).equals(FleetDocument.canonical(b));
    }

    /**
     * Applies the fleet's values of {@code section} on this panel, each through the command or
     * the check a person's own change takes, and returns the refusals, one line each, in the
     * words those checks use. Only what differs is touched. Needs the service running for the
     * display and Ambient commands; without it those are reported as not applied.
     */
    static List<String> apply(Context context, String section, Map<String, Object> values) {
        List<String> refused = new ArrayList<>();
        Map<String, Object> now = read(context, section);
        KioskService service = KioskService.liveService();
        switch (section) {
            case "dashboard":
                applyDashboard(context, service, now, values, refused);
                break;
            case "mqtt": {
                long port = number(values, "mqtt_port", -1);
                if (port < 1 || port > 65535) {
                    refused.add("mqtt_port: the broker port must be between 1 and 65535");
                    break;
                }
                String pin = text(values, "mqtt_tls_pin");
                if (!pin.isEmpty() && !CertificatePins.isFingerprint(pin)) {
                    refused.add("mqtt_tls_pin: a trusted certificate is its SHA-256, 32 pairs of"
                            + " hex digits with colons");
                    break;
                }
                KioskConfig.Editor editor = KioskConfig.edit(context)
                        .mqttHost(text(values, "mqtt_host"))
                        .mqttPort((int) port)
                        .mqttUsername(text(values, "mqtt_username"))
                        .mqttTls(flag(values, "mqtt_tls", false))
                        .mqttTlsPin(pin);
                String password = text(values, "mqtt_password");
                if (!password.isEmpty() && !password.equals(text(now, "mqtt_password"))) {
                    editor.mqttPassword(password);
                }
                editor.apply();
                KioskService.reloadConfiguration(context);
                break;
            }
            case "web_admin": {
                long port = number(values, "http_port", -1);
                KioskConfig config = KioskConfig.load(context);
                String problem = KioskCommandDispatcher.validateAdminPort((int) port,
                        config.httpPort, config.fleetPort);
                if (problem != null) {
                    refused.add("http_port: " + problem);
                } else if (port != config.httpPort && !SettingProbe.portFree((int) port)) {
                    refused.add("http_port: port " + port
                            + " is already in use by another service on this device");
                } else {
                    KioskConfig.edit(context).httpPort((int) port).apply();
                }
                String password = text(values, "http_admin_password");
                if (!password.isEmpty() && !password.equals(text(now, "http_admin_password"))) {
                    if (password.length() < MIN_ADMIN_PASSWORD_LENGTH) {
                        refused.add("http_admin_password: it must be at least "
                                + MIN_ADMIN_PASSWORD_LENGTH + " characters");
                    } else {
                        KioskConfig.edit(context).httpAdminPassword(password).apply();
                    }
                }
                boolean enabled = flag(values, "web_admin_enabled", config.webAdminEnabled);
                KioskConfig.edit(context).webAdminEnabled(enabled).apply();
                KioskService.reloadConfiguration(context);
                break;
            }
            case "display":
                command(service, "display.orientation", null, text(values, "orientation"), null,
                        "orientation", now, values, refused);
                command(service, "display.off_method", null, text(values, "display_off_method"),
                        null, "display_off_method", now, values, refused);
                command(service, "display.auto_brightness", null, null,
                        flag(values, "auto_brightness", false), "auto_brightness", now, values,
                        refused);
                break;
            case "ambient":
                for (String key : new String[] {"mode", "start_after_seconds", "off_seconds",
                        "picture_seconds", "dim_percent", "url", "on_wake", "transition",
                        "picture_fit", "credit_corner"}) {
                    Object value = values.get(key);
                    command(service, "ambient." + key, null,
                            value instanceof Number ? String.valueOf(((Number) value).longValue())
                                    : text(values, key), null, key, now, values, refused);
                }
                for (String key : new String[] {"shuffle", "one_per_start", "credit"}) {
                    command(service, "ambient." + key, null, null, flag(values, key, false),
                            key, now, values, refused);
                }
                break;
            case "speaker": {
                // The port first, by the checks a person's own Save takes, then the switch,
                // which refuses while its port is taken by another service.
                long port = number(values, "network_speaker_port", -1);
                if (port != number(now, "network_speaker_port", -1)) {
                    String problem = KioskService.saveNetworkSpeakerPort(context,
                            Long.toString(port));
                    if (problem != null) {
                        refused.add("network_speaker_port: " + problem);
                    }
                }
                boolean on = flag(values, "network_speaker", false);
                if (on != flag(now, "network_speaker", false)) {
                    String problem = KioskService.setNetworkSpeaker(context, on);
                    if (problem != null) {
                        refused.add("network_speaker: " + problem);
                    }
                }
                break;
            }
            case "integrations": {
                refused.addAll(IntegrationSettings.applyShared(context,
                        text(values, "instances"), text(values, "integration_secrets")));
                // Each shared page's look; the page reads its options every few seconds.
                Map<String, Object> looks = text(values, "page_options").isEmpty() ? null
                        : TinyJson.object(TinyJson.parse(text(values, "page_options")));
                for (Integrations.Instance instance : KioskConfig.integrationsOf(context)) {
                    Object look = looks == null ? null : looks.get(instance.id);
                    if (look instanceof String && Integrations.hasPage(instance.definition)) {
                        KioskConfig.storeClockOptions(context, instance.id,
                                BitcoinClock.parse((String) look));
                    }
                }
                break;
            }
            case "alerts":
                for (Field field : fields(context, section)) {
                    if (!values.containsKey(field.key)
                            || sameValue(now.get(field.key), values.get(field.key))) {
                        continue;
                    }
                    Object value = values.get(field.key);
                    String problem = KioskConfig.saveAlertSetting(context, field.key,
                            value instanceof Boolean ? ((Boolean) value ? "1" : "0") : shown(value));
                    if (problem != null) {
                        refused.add(field.key + ": " + problem);
                    }
                }
                KioskService.alertsChanged(context);
                break;
            default:
                break;
        }
        return refused;
    }

    /** One command, only when the value differs from this panel's; a refusal named by key. */
    private static void command(KioskService service, String name, String url, String value,
            Boolean enabled, String key, Map<String, Object> now, Map<String, Object> values,
            List<String> refused) {
        if (!values.containsKey(key) || sameValue(now.get(key), values.get(key))) {
            return;
        }
        if (service == null) {
            refused.add(key + ": the panel's service is not running");
            return;
        }
        KioskCommandDispatcher.Result result = service.dispatch(name,
                new KioskCommandDispatcher.CommandArgs(-1, url, enabled, value));
        if (!"accepted".equals(result.status)) {
            refused.add(key + ": " + result.detail);
        }
    }

    /**
     * The page address, then the app, cameras or integration the group chose, then what the
     * dashboard shows, so the switch never lands on a choice not made yet. The integration
     * section is applied first (FleetServer.syncProfile), so a shared integration exists here.
     */
    private static void applyDashboard(Context context, KioskService service,
            Map<String, Object> now, Map<String, Object> values, List<String> refused) {
        if (!text(values, "dashboard_url").isEmpty()) {
            // Stored but not shown at the welcome screen: KioskService.setDashboardUrl.
            command(service, "dashboard.url", url(values, "dashboard_url"), null, null,
                    "dashboard_url", now, values, refused);
        }
        String shows = text(values, "dashboard_shows");
        int before = refused.size();
        if (KioskCommandDispatcher.DASHBOARD_APP.equals(shows)
                && !sameValue(now.get("dashboard_app"), values.get("dashboard_app"))) {
            String app = text(values, "dashboard_app");
            if (context.getPackageManager().getLaunchIntentForPackage(app) == null) {
                refused.add("dashboard_app: " + app + " is not installed on this panel");
            } else if (AppLock.doors(context).contains(app)) {
                refused.add("dashboard_app: " + app + " opens Android's settings, a home screen"
                        + " or the Play Store");
            } else {
                KioskConfig.edit(context).appLock(app).apply();
                KioskService.appLockChanged();
            }
        } else if (KioskCommandDispatcher.DASHBOARD_CAMERAS.equals(shows)) {
            command(service, "dashboard.cameras", null, text(values, "dashboard_cameras"), null,
                    "dashboard_cameras", now, values, refused);
        } else if (KioskCommandDispatcher.DASHBOARD_INTEGRATION.equals(shows)) {
            command(service, "dashboard.integration", null,
                    text(values, "dashboard_integration"), null, "dashboard_integration", now,
                    values, refused);
        }
        if (refused.size() == before) {
            command(service, "dashboard.show", null, shows, null, "dashboard_shows", now, values,
                    refused);
        }
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).longValue() == ((Number) b).longValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String url(Map<String, Object> values, String key) {
        return values.containsKey(key) ? text(values, key) : null;
    }

    private static long number(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static boolean flag(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /**
     * What the Fleet section and {@code /api/stats} say: per section, which groups share it
     * and which one's values apply here, with what this panel could not apply of them; the
     * groups there are, which of them this panel is in; and the names offered for a new one.
     */
    static JSONObject state(Context context) {
        Map<String, Object> document = KioskConfig.fleetDocumentOf(context);
        JSONObject state = new JSONObject();
        try {
            JSONObject sections = new JSONObject();
            for (String section : FleetDocument.SECTIONS) {
                Map<String, Long> by = FleetDocument.sharedBy(document, section);
                org.json.JSONArray sharedBy = new org.json.JSONArray();
                for (String group : by.keySet()) {
                    sharedBy.put(group);
                }
                sections.put(section, new JSONObject()
                        .put("shared_by", sharedBy)
                        .put("group", FleetServer.profileSource(section))
                        .put("refused", new org.json.JSONArray(
                                KioskRuntimeState.fleetProfileRefused(section))));
            }
            org.json.JSONArray groups = new org.json.JSONArray();
            Map<String, Object> me = FleetDocument.memberByKey(document,
                    KioskRuntimeState.fleetKey());
            List<String> mine = FleetDocument.memberGroups(document, me);
            state.put("follows_fleet", FleetDocument.followsFleet(me));
            for (Map.Entry<String, String> group : FleetDocument.groups(document).entrySet()) {
                groups.put(new JSONObject().put("id", group.getKey()).put("name", group.getValue())
                        .put("in", mine.contains(group.getKey())));
            }
            state.put("sections", sections);
            state.put("groups", groups);
            state.put("suggestions", new org.json.JSONArray(
                    java.util.Arrays.asList(FleetDocument.GROUP_SUGGESTIONS)));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return state;
    }

    /** Whether a section's values here come from the whole fleet, which a panel may stop following. */
    static boolean fromWholeFleet(String section) {
        return FleetDocument.EVERYONE.equals(FleetServer.profileSource(section));
    }

    /**
     * The scope the Alerts page edits: the one this panel takes them from, else any that shares
     * them, else the whole fleet, so a Save never makes a second copy beside a group's.
     */
    static String alertsScope(Map<String, Object> document) {
        String source = FleetServer.profileSource("alerts");
        if (!source.isEmpty()) {
            return source;
        }
        if (document != null) {
            for (String scope : FleetDocument.sharedBy(document, "alerts").keySet()) {
                return scope;
            }
        }
        return FleetDocument.EVERYONE;
    }

    /**
     * A line for a person under a section a group of this panel's shares, or null: which group
     * sets it here, which other groups share it too, and what was not applied.
     */
    static String sharedLine(Context context, String section) {
        Map<String, Object> document = KioskConfig.fleetDocumentOf(context);
        String group = FleetServer.profileSource(section);
        if (group.isEmpty() || document == null) {
            return null;
        }
        List<String> others = new ArrayList<>();
        for (String other : FleetDocument.sharedBy(document, section).keySet()) {
            if (!other.equals(group)) {
                others.add(FleetDocument.EVERYONE.equals(other) ? "the whole fleet"
                        : FleetDocument.groupName(document, other));
            }
        }
        boolean pro = FleetServer.proStatus(context).pro;
        String line = FleetDocument.EVERYONE.equals(group)
                ? "From the whole fleet: " + (pro ? "a change here changes every panel."
                        : "without Pro, a change here stays on this panel.")
                : "From the group " + FleetDocument.groupName(document, group) + ": "
                        + (pro ? "a change here changes every panel of it."
                                : "without Pro, a change here stays on this panel.");
        if (!others.isEmpty()) {
            line += " Also shared by " + String.join(", ", others) + ".";
        }
        List<String> refused = KioskRuntimeState.fleetProfileRefused(section);
        if (!refused.isEmpty()) {
            line += " Not applied here: " + String.join("; ", refused) + ".";
            Log.d(TAG, line);
        }
        return line;
    }

    /** For the welcome screen: the fleet this panel joined and what it sets here. */
    static String joinedLine(Context context) {
        String line = "Joined the fleet " + KioskConfig.fleetNameOf(context) + ".";
        if (KioskConfig.fleetDocumentOf(context) == null) {
            return line + " Its list of panels is on its way.";
        }
        List<String> set = new ArrayList<>();
        List<String> notApplied = new ArrayList<>();
        for (String section : FleetDocument.SECTIONS) {
            if (FleetServer.profileSource(section).isEmpty()) {
                continue;
            }
            String label = FleetDocument.sectionLabel(section).toLowerCase(java.util.Locale.ROOT);
            (KioskRuntimeState.fleetProfileRefused(section).isEmpty() ? set : notApplied).add(label);
        }
        if (!set.isEmpty()) {
            line += " It sets here: " + String.join(", ", set) + ".";
        } else if (notApplied.isEmpty()) {
            line += " It shares no settings yet.";
        }
        if (!notApplied.isEmpty()) {
            line += " Not applied here: " + String.join(", ", notApplied)
                    + " (see All panels).";
        }
        return line;
    }

    /** The facts last read, for the stored list they came from, so a page load parses nothing. */
    private static String factsFor = null;
    private static String[] facts = {"", "", ""};

    /**
     * {@code url} with this panel's facts in place of its placeholders: the name the fleet
     * knows it by (its own name outside a fleet), the first of its groups by name (empty outside
     * one) and the fleet's name (empty outside a fleet). The facts are read from the list once
     * per change of it. See FleetDocument.fillPlaceholders.
     */
    static String fillPlaceholders(Context context, String url) {
        if (url == null || (url.indexOf('{') < 0 && !url.contains("%7B"))) {
            return url;
        }
        String stored = KioskConfig.fleetDocumentText(context);
        String own = KioskConfig.deviceIdOf(context);
        String[] now;
        synchronized (FleetProfile.class) {
            if (!stored.equals(factsFor)) {
                Map<String, Object> document = stored.isEmpty() ? null
                        : FleetDocument.parse(stored);
                Map<String, Object> me = FleetDocument.memberByKey(document,
                        KioskConfig.fleetSelfKeyOf(context));
                String name = me == null ? own : TinyJson.string(me, FleetDocument.NAME, own);
                String group = "";
                for (String id : FleetDocument.memberGroups(document, me)) {
                    String candidate = FleetDocument.groupName(document, id);
                    if (!FleetDocument.EVERYONE.equals(id)
                            && (group.isEmpty() || candidate.compareTo(group) < 0)) {
                        group = candidate;
                    }
                }
                facts = new String[] {name, group, KioskConfig.fleetNameOf(context)};
                factsFor = stored;
            }
            now = facts;
        }
        return FleetDocument.fillPlaceholders(url, now[0].isEmpty() ? own : now[0], now[1], now[2]);
    }

    /**
     * This panel's name as the fleet knows it: the name in the fleet's list, or the device id
     * outside a fleet. What Identify shows, and what {@code {panel.name}} fills in.
     */
    static String panelName(Context context) {
        String own = KioskConfig.deviceIdOf(context);
        String stored = KioskConfig.fleetDocumentText(context);
        Map<String, Object> me = stored.isEmpty() ? null : FleetDocument.memberByKey(
                FleetDocument.parse(stored), KioskConfig.fleetSelfKeyOf(context));
        String name = me == null ? own : TinyJson.string(me, FleetDocument.NAME, own);
        return name.isEmpty() ? own : name;
    }

    /** One value of a section as the Fleet page draws and reads it. */
    static final class Field {
        final String key;
        final String label;
        /** text, host, url, number, password, switch, or select. */
        final String type;
        /** For a select: value, label, value, label, ... */
        final String[] options;
        /** Shown only while the select {@link #whenKey} holds one of {@link #whenValues}. */
        String whenKey;
        String[] whenValues;

        Field(String key, String label, String type, String... options) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.options = options;
        }

        Field when(String key, String... values) {
            whenKey = key;
            whenValues = values;
            return this;
        }

        /** Whether the field shows while its select holds {@code value}; always without one. */
        boolean shownFor(String value) {
            return whenKey == null || java.util.Arrays.asList(whenValues).contains(value);
        }
    }

    /** A word table as a select's options: key, word, key, word, ... */
    private static String[] flat(String[][] words) {
        String[] options = new String[words.length * 2];
        for (int index = 0; index < words.length; index++) {
            options[index * 2] = words[index][0];
            options[index * 2 + 1] = words[index][1];
        }
        return options;
    }

    /** The first ready-made alert: the Alerts form's part "Ready-made alerts" starts here. */
    static final String ALERTS_READY_MADE = "alert_offline_minutes";

    /** Whether the alerts {@code values} send anywhere: a channel set, or a secret one kept. */
    static boolean alertChannelSet(Map<String, Object> values) {
        for (String key : new String[] {"alert_ntfy_url", "alert_chat_url", "alert_mqtt_topic",
            "alert_webhook_url"}) {
            if (!shown(values.get(key)).isEmpty()
                    || Boolean.TRUE.equals(values.get(key + "_set"))) {
                return true;
            }
        }
        return false;
    }

    /** The values of each section, in the order the Fleet page shows them. */
    static List<Field> fields(Context context, String section) {
        List<Field> fields = new ArrayList<>();
        switch (section) {
            case "dashboard":
                // The choices are this panel's: the apps installed here, its cameras and its
                // integrations with a page. A panel without the one chosen refuses it.
                fields.add(new Field("dashboard_shows", "Shows", "select",
                        flat(KioskCommandDispatcher.DASHBOARD_WORDS)));
                fields.add(new Field("dashboard_url", "Dashboard URL", "url")
                        .when("dashboard_shows", KioskCommandDispatcher.DASHBOARD_PAGE));
                List<String> apps = new ArrayList<>();
                for (AppLock.App app : AppLock.launchable(context, true)) {
                    apps.add(app.packageName);
                    apps.add(app.label);
                }
                fields.add(new Field("dashboard_app", "App", "select", apps.toArray(new String[0]))
                        .when("dashboard_shows", KioskCommandDispatcher.DASHBOARD_APP));
                List<String> cameras = new ArrayList<>();
                for (Cameras.Camera camera : KioskConfig.camerasOf(context)) {
                    cameras.add(camera.name);
                    cameras.add(camera.name);
                }
                for (Cameras.Group group : KioskConfig.cameraGroupsOf(context)) {
                    cameras.add(group.name);
                    cameras.add(group.name);
                }
                fields.add(new Field("dashboard_cameras", "Camera or group", "select",
                        cameras.toArray(new String[0]))
                        .when("dashboard_shows", KioskCommandDispatcher.DASHBOARD_CAMERAS));
                List<String> pages = new ArrayList<>();
                for (Integrations.Instance instance : KioskConfig.integrationsOf(context)) {
                    if (Integrations.hasPage(instance.definition)) {
                        pages.add(instance.id);
                        pages.add(instance.name);
                    }
                }
                fields.add(new Field("dashboard_integration", "Integration", "select",
                        pages.toArray(new String[0]))
                        .when("dashboard_shows", KioskCommandDispatcher.DASHBOARD_INTEGRATION));
                break;
            case "mqtt":
                fields.add(new Field("mqtt_host", "Broker host", "host"));
                fields.add(new Field("mqtt_port", "Broker port", "number"));
                fields.add(new Field("mqtt_username", "Username", "text"));
                fields.add(new Field("mqtt_password", "Password", "password"));
                fields.add(new Field("mqtt_tls", "Encrypted (TLS)", "switch"));
                fields.add(new Field("mqtt_tls_pin", "Trusted broker certificate (SHA-256)",
                        "text"));
                break;
            case "web_admin":
                fields.add(new Field("web_admin_enabled", "Web admin on", "switch"));
                fields.add(new Field("http_port", "Port", "number"));
                fields.add(new Field("http_admin_password", "Admin password", "password"));
                break;
            case "display":
                fields.add(new Field("orientation", "Orientation", "select",
                        flat(KioskCommandDispatcher.ORIENTATION_WORDS)));
                fields.add(new Field("display_off_method", "Display off method", "select",
                        flat(KioskCommandDispatcher.DISPLAY_OFF_WORDS)));
                fields.add(new Field("auto_brightness", "Automatic brightness", "switch"));
                break;
            case "speaker":
                fields.add(new Field("network_speaker", "Network speaker on", "switch"));
                fields.add(new Field("network_speaker_port", "Port", "number"));
                break;
            case "ambient":
                // Inside Ambient's own page a label drops the prefix (the names rule, 2026-10-23).
                fields.add(new Field("mode", "Mode", "select",
                        flat(KioskCommandDispatcher.AMBIENT_MODE_WORDS)));
                // Each option only for the modes it applies to, as on Ambient's own page; Off
                // has none.
                String[] running = {ScreensaverPolicy.DIM, ScreensaverPolicy.FILM,
                    ScreensaverPolicy.URL, ScreensaverPolicy.PICTURES};
                String pictures = ScreensaverPolicy.PICTURES;
                fields.add(new Field("start_after_seconds",
                        "Start after (seconds, 0 = never)", "number").when("mode", running));
                fields.add(new Field("off_seconds",
                        "Before display off (seconds, 0 = never)", "number")
                        .when("mode", running));
                fields.add(new Field("url", "Web page", "url")
                        .when("mode", ScreensaverPolicy.URL));
                fields.add(new Field("dim_percent", "Dim brightness (percent)", "number")
                        .when("mode", ScreensaverPolicy.DIM));
                fields.add(new Field("on_wake", "On wake", "select",
                        flat(KioskCommandDispatcher.AMBIENT_ON_WAKE_WORDS)).when("mode",
                                ScreensaverPolicy.URL, ScreensaverPolicy.DIM, pictures));
                fields.add(new Field("picture_seconds", "Picture duration (seconds)",
                        "number").when("mode", pictures));
                fields.add(new Field("transition", "Transition", "select",
                        flat(KioskCommandDispatcher.AMBIENT_TRANSITION_WORDS))
                        .when("mode", pictures));
                fields.add(new Field("picture_fit", "Picture fit", "select",
                        flat(KioskCommandDispatcher.AMBIENT_FIT_WORDS)).when("mode", pictures));
                fields.add(new Field("shuffle", "Shuffle", "switch").when("mode", pictures));
                fields.add(new Field("one_per_start", "One picture per start", "switch")
                        .when("mode", pictures));
                fields.add(new Field("credit", "Credit line", "switch").when("mode", pictures));
                fields.add(new Field("credit_corner", "Credit corner", "select",
                        flat(KioskCommandDispatcher.AMBIENT_CORNER_WORDS))
                        .when("mode", pictures));
                break;
            case "alerts":
                // Where the alerts go, then the ready-made alerts (the lighter plan, 2026-10-29).
                fields.add(new Field("alert_ntfy_url", "Phone app, ntfy topic address", "url"));
                fields.add(new Field("alert_ntfy_token", "ntfy access token", "password"));
                fields.add(new Field("alert_chat_url",
                        "Chat webhook (Slack, Discord, Google Chat, Teams)", "password"));
                fields.add(new Field("alert_mqtt_topic", "MQTT topic", "text"));
                fields.add(new Field("alert_webhook_url", "Web address", "url"));
                fields.add(new Field(ALERTS_READY_MADE, "Panel offline after (minutes, 0 off)",
                        "number"));
                fields.add(new Field("alert_back_online", "Back online", "switch"));
                fields.add(new Field("alert_page_error", "Page failed to load", "switch"));
                fields.add(new Field("alert_battery_below", "Battery below (percent, 0 off)",
                        "number"));
                fields.add(new Field("alert_temperature_above",
                        "Temperature above (°C, 0 off)", "number"));
                fields.add(new Field("alert_storage_below_mb",
                        "Free storage below (MB, 0 off)", "number"));
                fields.add(new Field("alert_update_available", "Update available", "switch"));
                fields.add(new Field("alert_shared_refused", "Shared settings refused",
                        "switch"));
                fields.add(new Field("alert_combination_used", "Combination used", "switch"));
                fields.add(new Field("alert_pin_lockout", "PIN locked out", "switch"));
                fields.add(new Field("alert_quiet_display_off",
                        "Quiet while a panel's display is off", "switch"));
                break;
            default:
                break;
        }
        return fields;
    }

    /**
     * A section's values as a form posted them, typed by {@link #fields}, over {@code current}
     * (a blank password keeps the current one); the first refusal, or null, in {@code refused}.
     */
    static Map<String, Object> fromForm(Context context, String section,
            Map<String, String> form, Map<String, Object> current, String[] refused) {
        Map<String, Object> values = new LinkedHashMap<>(current == null
                ? new LinkedHashMap<String, Object>() : current);
        List<Field> fields = fields(context, section);
        for (Field field : fields) {
            String posted = form.get(field.key);
            switch (field.type) {
                case "switch":
                    values.put(field.key, posted != null && ("1".equals(posted)
                            || "on".equals(posted) || "true".equals(posted)));
                    break;
                case "number":
                    if (posted == null || posted.trim().isEmpty()) {
                        break;
                    }
                    try {
                        values.put(field.key, Long.parseLong(posted.trim()));
                    } catch (NumberFormatException notANumber) {
                        refused[0] = field.label + " must be a number";
                        return null;
                    }
                    break;
                case "password":
                    if ("1".equals(form.get(field.key + "_clear"))) {
                        values.put(field.key, "");
                    } else if (posted != null && !posted.isEmpty()) {
                        values.put(field.key, posted);
                    } else if (!values.containsKey(field.key)) {
                        values.put(field.key, "");
                    }
                    break;
                case "url":
                    if (posted != null) {
                        String url = posted.trim();
                        // The dashboard's address is needed only while the dashboard shows it.
                        boolean needed = "dashboard_url".equals(field.key)
                                && KioskCommandDispatcher.DASHBOARD_PAGE.equals(
                                        form.get("dashboard_shows"));
                        String problem = url.isEmpty() && !needed ? null
                                : KioskCommandDispatcher.validateDashboardUrl(url);
                        if (problem != null) {
                            refused[0] = field.label + ": " + problem;
                            return null;
                        }
                        values.put(field.key, url);
                    }
                    break;
                default:
                    if (posted != null) {
                        values.put(field.key, posted.trim());
                    }
            }
        }
        for (Field field : fields) {
            if (!field.shownFor(shown(values.get(field.whenKey)))) {
                // A dashboard choice for another kind is not shared: the panels read it as "".
                // Any other hidden value is kept as it is, for when its mode is chosen again.
                if ("dashboard".equals(section) && !"dashboard_url".equals(field.key)) {
                    values.put(field.key, "");
                }
                continue;
            }
            if ("select".equals(field.type) && shown(values.get(field.key)).isEmpty()) {
                refused[0] = field.label + ": choose one";
                return null;
            }
            if (!values.containsKey(field.key) && !"password".equals(field.type)) {
                refused[0] = field.label + " is missing";
                return null;
            }
            if ("alerts".equals(section)) {
                Object value = values.get(field.key);
                String problem = FleetAlerts.problem(field.key,
                        value instanceof Boolean ? ((Boolean) value ? "1" : "0") : shown(value));
                if (problem != null) {
                    refused[0] = field.label + ": " + problem;
                    return null;
                }
            }
        }
        return values;
    }

    /**
     * One short line saying what a scope shares of {@code section}, for the Fleet page's list of
     * shared settings: never a secret, an address without its query (which may hold one).
     */
    static String summary(Context context, String section, Map<String, Object> values) {
        if (values == null) {
            return "Not shared";
        }
        switch (section) {
            case "dashboard": {
                String shows = shown(values.get("dashboard_shows"));
                if (KioskCommandDispatcher.DASHBOARD_APP.equals(shows)) {
                    return "App: " + AppLock.labelOf(context, shown(values.get("dashboard_app")));
                }
                if (KioskCommandDispatcher.DASHBOARD_CAMERAS.equals(shows)) {
                    return "Cameras: " + shown(values.get("dashboard_cameras"));
                }
                if (KioskCommandDispatcher.DASHBOARD_INTEGRATION.equals(shows)) {
                    return BitcoinClockPage.nameOf(context,
                            shown(values.get("dashboard_integration")));
                }
                String address = shown(values.get("dashboard_url"));
                int query = address.indexOf('?');
                return address.isEmpty() ? "No address" : query < 0 ? address
                        : address.substring(0, query);
            }
            case "mqtt": {
                String host = shown(values.get("mqtt_host"));
                return host.isEmpty() ? "No broker" : host + ", port "
                        + shown(values.get("mqtt_port"))
                        + (Boolean.TRUE.equals(values.get("mqtt_tls")) ? ", encrypted" : "");
            }
            case "web_admin":
                return Boolean.FALSE.equals(values.get("web_admin_enabled")) ? "Off"
                        : "Port " + shown(values.get("http_port"));
            case "display":
                return word(context, "display", "orientation", values);
            case "ambient":
                return word(context, "ambient", "mode", values);
            case "speaker":
                return Boolean.TRUE.equals(values.get("network_speaker")) ? "On" : "Off";
            case "integrations": {
                int count;
                try {
                    List<Object> instances = TinyJson.array(TinyJson.parse(
                            shown(values.get("instances"))));
                    count = instances == null ? 0 : instances.size();
                } catch (IllegalArgumentException unreadable) {
                    count = 0;
                }
                return count == 1 ? "1 integration" : count + " integrations";
            }
            case "alerts": {
                long minutes = values.get("alert_offline_minutes") instanceof Number
                        ? ((Number) values.get("alert_offline_minutes")).longValue() : 0;
                return minutes > 0 ? "Offline after " + minutes + " min" : "Offline alert off";
            }
            default:
                return "Shared";
        }
    }

    /** A menu's words for the value it holds, the value itself when the menu has no such one. */
    private static String word(Context context, String section, String key,
            Map<String, Object> values) {
        String value = shown(values.get(key));
        for (Field field : fields(context, section)) {
            if (field.key.equals(key) && field.options != null) {
                for (int i = 0; i + 1 < field.options.length; i += 2) {
                    if (field.options[i].equals(value)) {
                        return field.options[i + 1];
                    }
                }
            }
        }
        return value;
    }

    /** A value as a box shows it: a number whole (the list reads them back as decimals). */
    static String shown(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return String.valueOf(((Number) value).longValue());
        }
        return String.valueOf(value);
    }
}
