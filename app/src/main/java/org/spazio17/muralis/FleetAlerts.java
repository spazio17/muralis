/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fleet's alerts (fleet design, section 10): what each panel judges about itself and, for
 * one panel of the fleet, about the others, and when an alert is said. A condition (a page
 * error, a battery below the threshold, a temperature above it, a panel offline) raises "on"
 * once when it starts and "off" once when it ends, never repeated while unchanged; an event (a
 * combination used) is said once per use, with no state to end; a PIN lockout is "on" when it
 * starts and "off" when it ends. Offline is judged from the heartbeats' peers, and said once per
 * fleet: only the online panel whose id sorts first among the online panels raises it, which
 * every panel can compute from its own peers.
 *
 * <p>Pure, with no Android imports: the decisions are host-tested. The delivery (a
 * notification, an MQTT publish, a web address) is the service's.
 */
final class FleetAlerts {
    static final String OFFLINE = "offline";
    static final String PAGE_ERROR = "page_error";
    static final String BATTERY = "battery";
    static final String TEMPERATURE = "temperature";
    static final String COMBINATION_USED = "combination_used";
    static final String PIN_LOCKOUT = "pin_lockout";

    /** The panels tell each other every three minutes; see FleetServer.HEARTBEAT_INTERVAL_MS. */
    static final int HEARTBEAT_MINUTES = 3;
    /**
     * The least an offline threshold may be: two heartbeats and a minute, so one slow round
     * never raises a false alarm.
     */
    static final int MIN_OFFLINE_MINUTES = 2 * HEARTBEAT_MINUTES + 1;

    /** The eight settings, as stored and as the Fleet page shares them. */
    static final class Settings {
        long offlineMinutes = 10;
        boolean pageError = true;
        long batteryBelow = 15;
        long temperatureAbove = 45;
        boolean combinationUsed;
        boolean pinLockout = true;
        String mqttTopic = "";
        String webhookUrl = "";

        /** Whether any kind is switched on, which is when a notification permission is asked. */
        boolean anyKind() {
            return offlineMinutes > 0 || pageError || batteryBelow > 0 || temperatureAbove > 0
                    || combinationUsed || pinLockout;
        }
    }

    /** What the telemetry sampler knows of this panel. */
    static final class Facts {
        boolean pageError;
        String pageErrorDetail = "";
        /** Below zero when unknown or when the panel has no battery. */
        double batteryPercent = -1;
        /** NaN where the panel reads none. */
        double temperatureC = Double.NaN;
    }

    /** Another panel as last heard from. */
    static final class Peer {
        final String id;
        final String name;
        /** Below zero when not heard from since the app started. */
        final long seenAgoMs;

        Peer(String id, String name, long seenAgoMs) {
            this.id = id;
            this.name = name;
            this.seenAgoMs = seenAgoMs;
        }
    }

    /** One alert to deliver. */
    static final class Alert {
        final String kind;
        final String panelId;
        final String panelName;
        final boolean on;
        final String detail;

        Alert(String kind, String panelId, String panelName, boolean on, String detail) {
            this.kind = kind;
            this.panelId = panelId;
            this.panelName = panelName;
            this.on = on;
            this.detail = detail;
        }

        /** One notification per kind and panel: its key. */
        String key() {
            return kind + "/" + panelId;
        }
    }

    /** Why {@code value} is not a value of the setting {@code key}, or null when it is. */
    static String problem(String key, String value) {
        String text = value == null ? "" : value.trim();
        switch (key) {
            case "alert_offline_minutes":
            case "alert_battery_below":
            case "alert_temperature_above": {
                long number;
                try {
                    number = Long.parseLong(text);
                } catch (NumberFormatException notANumber) {
                    return "it must be a whole number, 0 for off";
                }
                if (number < 0) {
                    return "it must be 0 or more";
                }
                if (key.equals("alert_offline_minutes") && number > 0
                        && number < MIN_OFFLINE_MINUTES) {
                    return "at least " + MIN_OFFLINE_MINUTES + ": the panels tell each other every "
                            + HEARTBEAT_MINUTES + " minutes, and one may be late";
                }
                if (key.equals("alert_battery_below") && number > 100) {
                    return "a percentage, 100 at most";
                }
                if (key.equals("alert_temperature_above") && number > 150) {
                    return "a temperature in degrees Celsius, 150 at most";
                }
                return null;
            }
            case "alert_mqtt_topic":
                if (text.isEmpty()) {
                    return null;
                }
                if (text.length() > 200 || text.contains("#") || text.contains("+")
                        || text.contains(" ") || text.startsWith("$") || text.startsWith("/")) {
                    return "a plain topic such as kiosk/fleet/alerts, without wildcards";
                }
                return null;
            case "alert_webhook_url":
                if (text.isEmpty() || text.startsWith("http://") || text.startsWith("https://")) {
                    return null;
                }
                return "a web address starting with http:// or https://, or empty for off";
            case "alert_page_error":
            case "alert_combination_used":
            case "alert_pin_lockout":
                return null;
            default:
                return "no such alert setting";
        }
    }

    /** The conditions in force, by {@link Alert#key()}, with their detail. */
    private final Map<String, String> active = new LinkedHashMap<>();

    /** The keys of the conditions in force, for the notifications to clear when they stop. */
    List<String> activeKeys() {
        return new ArrayList<>(active.keySet());
    }

    /** Forgets every condition, when the alerts stop: nothing is said of what was in force. */
    void reset() {
        active.clear();
    }

    /**
     * The alerts to deliver now: this panel's own conditions, and the peers offline when this
     * panel is the one to say so. {@code upMs} is how long the app has run: a peer never heard
     * from counts as unheard for that long.
     */
    List<Alert> judge(Settings settings, Facts facts, String selfId, String selfName,
            List<Peer> peers, long upMs) {
        List<Alert> alerts = new ArrayList<>();
        condition(alerts, PAGE_ERROR, selfId, selfName, settings.pageError && facts.pageError,
                facts.pageErrorDetail);
        boolean battery = settings.batteryBelow > 0 && facts.batteryPercent >= 0
                && facts.batteryPercent < settings.batteryBelow;
        condition(alerts, BATTERY, selfId, selfName, battery,
                facts.batteryPercent < 0 ? "" : Math.round(facts.batteryPercent) + "%");
        boolean hot = settings.temperatureAbove > 0 && !Double.isNaN(facts.temperatureC)
                && facts.temperatureC > settings.temperatureAbove;
        condition(alerts, TEMPERATURE, selfId, selfName, hot,
                Double.isNaN(facts.temperatureC) ? "" : Math.round(facts.temperatureC) + " °C");
        long limit = settings.offlineMinutes * 60_000L;
        boolean leads = settings.offlineMinutes > 0 && leads(selfId, peers, limit, upMs);
        for (Peer peer : peers) {
            long unheard = peer.seenAgoMs < 0 ? upMs : peer.seenAgoMs;
            // A peer this panel said offline stays said until it is heard from again, whoever
            // leads now: a handover never says a panel is back while it is still gone.
            boolean offline = settings.offlineMinutes > 0 && unheard >= limit
                    && (leads || active.containsKey(OFFLINE + "/" + peer.id));
            condition(alerts, OFFLINE, peer.id, peer.name, offline,
                    "not heard from for " + (unheard / 60_000L) + " min");
        }
        return alerts;
    }

    /**
     * Whether this panel says who is offline: its id sorts first among the online panels, the
     * peers heard from within the limit and itself.
     */
    static boolean leads(String selfId, List<Peer> peers, long limitMs, long upMs) {
        for (Peer peer : peers) {
            long unheard = peer.seenAgoMs < 0 ? upMs : peer.seenAgoMs;
            if (unheard < limitMs && peer.id.compareTo(selfId) < 0) {
                return false;
            }
        }
        return true;
    }

    /** An event of this panel: a combination used ("on" each time), a PIN lockout on or off. */
    List<Alert> event(Settings settings, String kind, boolean on, String selfId, String selfName,
            String detail) {
        List<Alert> alerts = new ArrayList<>();
        if (COMBINATION_USED.equals(kind) && settings.combinationUsed && on) {
            alerts.add(new Alert(kind, selfId, selfName, true, detail));
        } else if (PIN_LOCKOUT.equals(kind) && settings.pinLockout) {
            condition(alerts, kind, selfId, selfName, on, detail);
        }
        return alerts;
    }

    private void condition(List<Alert> alerts, String kind, String panelId, String panelName,
            boolean on, String detail) {
        String key = kind + "/" + panelId;
        boolean was = active.containsKey(key);
        if (on && !was) {
            active.put(key, detail);
            alerts.add(new Alert(kind, panelId, panelName, true, detail));
        } else if (!on && was) {
            active.remove(key);
            alerts.add(new Alert(kind, panelId, panelName, false, detail));
        }
    }

    /** The alert as published and posted: fleet, panel, panel_id, kind, state, detail, at. */
    static String json(String fleetName, Alert alert, String at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fleet", fleetName);
        body.put("panel", alert.panelName);
        body.put("panel_id", alert.panelId);
        body.put("kind", alert.kind);
        body.put("state", alert.on ? "on" : "off");
        body.put("detail", alert.detail == null ? "" : alert.detail);
        body.put("at", at);
        return TinyJson.write(body);
    }

    /** The notification's words, for a person: what and where. */
    static String title(Alert alert) {
        switch (alert.kind) {
            case OFFLINE: return alert.on ? alert.panelName + " is offline"
                    : alert.panelName + " is back";
            case PAGE_ERROR: return alert.on ? alert.panelName + ": the page failed to load"
                    : alert.panelName + ": the page loads again";
            case BATTERY: return alert.on ? alert.panelName + ": battery low"
                    : alert.panelName + ": battery fine again";
            case TEMPERATURE: return alert.on ? alert.panelName + ": too hot"
                    : alert.panelName + ": cooled down";
            case COMBINATION_USED: return alert.panelName + ": a combination was used";
            case PIN_LOCKOUT: return alert.on ? alert.panelName + ": PIN locked out"
                    : alert.panelName + ": PIN lockout over";
            default: return alert.panelName + ": " + alert.kind;
        }
    }
}
