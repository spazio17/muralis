/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
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
 * The fleet's shared settings on this panel (block 12d): which sections this panel shares, what
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
                values.put("dashboard_url", config.dashboardUrl);
                break;
            case "mqtt":
                values.put("mqtt_host", config.mqttHost);
                values.put("mqtt_port", (long) config.mqttPort);
                values.put("mqtt_username", config.mqttUsername);
                values.put("mqtt_password", config.mqttPassword);
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
            case "screensaver": {
                ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
                values.put("mode", saver.mode);
                values.put("idle_seconds", (long) saver.idleSeconds);
                values.put("off_seconds", (long) saver.offSeconds);
                values.put("url", saver.url);
                values.put("dim_percent", (long) saver.dimPercent);
                values.put("on_wake", saver.onWake);
                values.put("source", saver.source);
                values.put("picture_seconds", (long) saver.pictureSeconds);
                values.put("transition", saver.transition);
                values.put("picture_fit", saver.pictureFit);
                values.put("shuffle", saver.shuffle);
                values.put("one_per_cycle", saver.onePerCycle);
                values.put("credit", saver.credit);
                values.put("credit_corner", saver.creditCorner);
                break;
            }
            case "alerts":
                values.put("alert_offline_minutes", config.alerts.offlineMinutes);
                values.put("alert_page_error", config.alerts.pageError);
                values.put("alert_battery_below", config.alerts.batteryBelow);
                values.put("alert_temperature_above", config.alerts.temperatureAbove);
                values.put("alert_combination_used", config.alerts.combinationUsed);
                values.put("alert_pin_lockout", config.alerts.pinLockout);
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
     * display and screensaver commands; without it those are reported as not applied.
     */
    static List<String> apply(Context context, String section, Map<String, Object> values) {
        List<String> refused = new ArrayList<>();
        Map<String, Object> now = read(context, section);
        KioskService service = KioskService.liveService();
        switch (section) {
            case "dashboard":
                // Stored but not shown at the welcome screen: KioskService.setDashboardUrl.
                command(service, "kiosk.set_url", url(values, "dashboard_url"), null, null,
                        "dashboard_url", now, values, refused);
                break;
            case "mqtt": {
                long port = number(values, "mqtt_port", -1);
                if (port < 1 || port > 65535) {
                    refused.add("mqtt_port: the broker port must be between 1 and 65535");
                    break;
                }
                KioskConfig.Editor editor = KioskConfig.edit(context)
                        .mqttHost(text(values, "mqtt_host"))
                        .mqttPort((int) port)
                        .mqttUsername(text(values, "mqtt_username"));
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
            case "screensaver":
                for (String key : new String[] {"mode", "source", "idle_seconds", "off_seconds",
                        "picture_seconds", "dim_percent", "url", "on_wake", "transition",
                        "picture_fit", "credit_corner"}) {
                    Object value = values.get(key);
                    command(service, "screensaver." + key, null,
                            value instanceof Number ? String.valueOf(((Number) value).longValue())
                                    : text(values, key), null, key, now, values, refused);
                }
                for (String key : new String[] {"shuffle", "one_per_cycle", "credit"}) {
                    command(service, "screensaver." + key, null, null, flag(values, key, false),
                            key, now, values, refused);
                }
                break;
            case "alerts":
                for (Field field : fields(section)) {
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
        String line = FleetDocument.EVERYONE.equals(group)
                ? "Set by the whole fleet. A change here changes every panel."
                : "Set by the group " + FleetDocument.groupName(document, group)
                        + ". A change here changes every panel of it.";
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
                    + " (see the Fleet section).";
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

    /** One value of a section as the Fleet page draws and reads it. */
    static final class Field {
        final String key;
        final String label;
        /** text, host, url, number, password, switch, or select. */
        final String type;
        /** For a select: value, label, value, label, ... */
        final String[] options;

        Field(String key, String label, String type, String... options) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.options = options;
        }
    }

    /** The values of each section, in the order the Fleet page shows them. */
    static List<Field> fields(String section) {
        List<Field> fields = new ArrayList<>();
        switch (section) {
            case "dashboard":
                fields.add(new Field("dashboard_url", "Dashboard URL", "url"));
                break;
            case "mqtt":
                fields.add(new Field("mqtt_host", "Broker host", "host"));
                fields.add(new Field("mqtt_port", "Broker port", "number"));
                fields.add(new Field("mqtt_username", "Username", "text"));
                fields.add(new Field("mqtt_password", "Password", "password"));
                break;
            case "web_admin":
                fields.add(new Field("web_admin_enabled", "Local web admin on", "switch"));
                fields.add(new Field("http_port", "Port", "number"));
                fields.add(new Field("http_admin_password", "Admin password", "password"));
                break;
            case "display":
                fields.add(new Field("orientation", "Orientation", "select",
                        KioskConfig.ORIENTATION_AUTO, "Auto-rotate",
                        KioskConfig.ORIENTATION_LANDSCAPE, "Landscape",
                        KioskConfig.ORIENTATION_PORTRAIT, "Portrait"));
                fields.add(new Field("display_off_method", "Display off", "select",
                        DisplayOffPolicy.AUTO, "Automatic", DisplayOffPolicy.SLEEP, "Sleep",
                        DisplayOffPolicy.FILM, "Black overlay"));
                fields.add(new Field("auto_brightness", "Automatic brightness", "switch"));
                break;
            case "screensaver":
                fields.add(new Field("mode", "Mode", "select", ScreensaverPolicy.OFF, "Off",
                        ScreensaverPolicy.DIM, "Dimmed page", ScreensaverPolicy.FILM, "Black",
                        ScreensaverPolicy.URL, "Web page", ScreensaverPolicy.PICTURES, "Pictures"));
                fields.add(new Field("idle_seconds", "After (seconds)", "number"));
                fields.add(new Field("off_seconds", "Display off after (seconds, 0 never)",
                        "number"));
                fields.add(new Field("url", "Web page address", "url"));
                fields.add(new Field("dim_percent", "Dim to (percent)", "number"));
                fields.add(new Field("on_wake", "On a touch", "select",
                        ScreensaverPolicy.WAKE_SCREENSAVER, "Show the screensaver first",
                        ScreensaverPolicy.WAKE_DASHBOARD, "Show the page at once"));
                fields.add(new Field("source", "Pictures from", "select", PictureSources.LOCAL,
                        "This panel's playlist", PictureSources.BING, "Bing image of the day",
                        PictureSources.WIKIMEDIA, "Wikimedia Commons picture of the day"));
                fields.add(new Field("picture_seconds", "Seconds per picture", "number"));
                fields.add(new Field("transition", "Transition", "select",
                        ScreensaverPolicy.TRANSITION_NONE, "None", ScreensaverPolicy.TRANSITION_FADE,
                        "Fade", ScreensaverPolicy.TRANSITION_SLIDE, "Slide"));
                fields.add(new Field("picture_fit", "Picture fit", "select",
                        ScreensaverPolicy.FIT_WHOLE, "Whole picture", ScreensaverPolicy.FIT_FILL,
                        "Fill the screen", ScreensaverPolicy.FIT_STRETCH, "Stretch",
                        ScreensaverPolicy.FIT_ACTUAL, "Actual size"));
                fields.add(new Field("shuffle", "Shuffle", "switch"));
                fields.add(new Field("one_per_cycle", "One picture per cycle", "switch"));
                fields.add(new Field("credit", "Show the credit", "switch"));
                fields.add(new Field("credit_corner", "Credit corner", "select",
                        ScreensaverPolicy.CORNER_BOTTOM_LEFT, "Bottom left",
                        ScreensaverPolicy.CORNER_BOTTOM_RIGHT, "Bottom right",
                        ScreensaverPolicy.CORNER_TOP_LEFT, "Top left",
                        ScreensaverPolicy.CORNER_TOP_RIGHT, "Top right"));
                break;
            case "alerts":
                fields.add(new Field("alert_offline_minutes",
                        "Panel offline after (minutes, 0 off)", "number"));
                fields.add(new Field("alert_page_error", "Page failed to load", "switch"));
                fields.add(new Field("alert_battery_below", "Battery below (percent, 0 off)",
                        "number"));
                fields.add(new Field("alert_temperature_above",
                        "Temperature above (°C, 0 off)", "number"));
                fields.add(new Field("alert_combination_used", "Combination used", "switch"));
                fields.add(new Field("alert_pin_lockout", "PIN locked out", "switch"));
                fields.add(new Field("alert_mqtt_topic", "MQTT topic (empty off)", "text"));
                fields.add(new Field("alert_webhook_url", "Web address (empty off)", "url"));
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
    static Map<String, Object> fromForm(String section, Map<String, String> form,
            Map<String, Object> current, String[] refused) {
        Map<String, Object> values = new LinkedHashMap<>(current == null
                ? new LinkedHashMap<String, Object>() : current);
        for (Field field : fields(section)) {
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
                    if (posted != null && !posted.isEmpty()) {
                        values.put(field.key, posted);
                    } else if (!values.containsKey(field.key)) {
                        values.put(field.key, "");
                    }
                    break;
                case "url":
                    if (posted != null) {
                        String url = posted.trim();
                        String problem = url.isEmpty() && !"dashboard_url".equals(field.key) ? null
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
        for (Field field : fields(section)) {
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
