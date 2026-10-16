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
        KioskConfig config = KioskConfig.load(context);
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
     * What the Fleet section and {@code /api/stats} say per section: whether the fleet shares
     * it, and what this panel could not apply of it.
     */
    static JSONObject state(Context context) {
        Map<String, Object> document = KioskConfig.fleetDocumentOf(context);
        JSONObject state = new JSONObject();
        try {
            for (String section : FleetDocument.SECTIONS) {
                state.put(section, new JSONObject()
                        .put("shared", FleetDocument.sectionValues(document, section) != null)
                        .put("refused", new org.json.JSONArray(
                                KioskRuntimeState.fleetProfileRefused(section))));
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return state;
    }

    /** A line for a person under a shared section, or null when the fleet does not share it. */
    static String sharedLine(Context context, String section) {
        Map<String, Object> document = KioskConfig.fleetDocumentOf(context);
        if (FleetDocument.sectionValues(document, section) == null) {
            return null;
        }
        List<String> refused = KioskRuntimeState.fleetProfileRefused(section);
        String line = "Set by the fleet " + KioskConfig.fleetNameOf(context)
                + ". A change here changes every panel.";
        if (!refused.isEmpty()) {
            line += " Not applied here: " + String.join("; ", refused) + ".";
            Log.d(TAG, line);
        }
        return line;
    }
}
