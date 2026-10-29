/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fleet's alerts: what each panel judges about itself and, for
 * one panel of the fleet, about the others, and when an alert is said. A condition (a page
 * error, a battery below the threshold, a temperature above it, a panel offline) raises "on"
 * once when it starts and "off" once when it ends, never repeated while unchanged; an event (a
 * combination used) is said once per use, with no state to end; a PIN lockout is "on" when it
 * starts and "off" when it ends. Offline is judged from the heartbeats' peers, and said once per
 * fleet: only the online panel whose id sorts first among the online panels raises it, which
 * every panel can compute from its own peers. An update available is said once per newer
 * version. While a panel's display is off, its page error, its going offline and its coming
 * back are held back when the settings ask for quiet (the rework of 2026-10-29); the way out,
 * the PIN and refused shared settings never are.
 *
 * <p>Where an alert goes: a phone through ntfy, a chat (Slack, Discord, Google Chat or Teams,
 * told apart by the address), an MQTT topic and any web address as a JSON POST. Nothing shows
 * on a panel's own screen, which keeps its dashboard (Juri, 2026-10-29). An automation's "Send
 * an alert" takes the same way. The bodies are built here; sending is the service's.
 *
 * <p>Pure, with no Android imports: the decisions and the bodies are host-tested.
 */
final class FleetAlerts {
    static final String OFFLINE = "offline";
    static final String PAGE_ERROR = "page_error";
    static final String BATTERY = "battery";
    static final String TEMPERATURE = "temperature";
    static final String COMBINATION_USED = "combination_used";
    static final String PIN_LOCKOUT = "pin_lockout";
    static final String STORAGE = "storage";
    static final String UPDATE = "update";
    static final String SHARED_REFUSED = "shared_refused";
    /** The alert "Send a test alert" sends. */
    static final String TEST = "test";
    /** The alert an automation's "Send an alert" sends: the detail is its words. */
    static final String AUTOMATION = "automation";

    /** The chats an address is told apart as, by its host; see {@link #chatKind}. */
    static final String SLACK = "Slack";
    static final String DISCORD = "Discord";
    static final String GOOGLE_CHAT = "Google Chat";
    static final String TEAMS = "Teams";

    /** The panels tell each other every three minutes; see FleetServer.HEARTBEAT_INTERVAL_MS. */
    static final int HEARTBEAT_MINUTES = 3;
    /**
     * The least an offline threshold may be: two heartbeats and a minute, so one slow round
     * never raises a false alarm.
     */
    static final int MIN_OFFLINE_MINUTES = 2 * HEARTBEAT_MINUTES + 1;

    /** How many ready-made alerts there are, each with its switch or threshold. */
    static final int READY_MADE = 10;

    /** The settings, as stored and as the Fleet page shares them. */
    static final class Settings {
        long offlineMinutes = 10;
        boolean backOnline = true;
        boolean pageError = true;
        long batteryBelow = 15;
        long temperatureAbove = 45;
        /** Android's own low-storage mark is 10% capped at 500 MB; no signage product has one. */
        long storageBelowMb = 500;
        boolean updateAvailable;
        boolean sharedRefused = true;
        boolean combinationUsed;
        boolean pinLockout = true;
        boolean quietDisplayOff = true;
        /** A topic's whole address, such as https://ntfy.sh/hall-alerts. */
        String ntfyUrl = "";
        /** Secret: for a protected topic only. */
        String ntfyToken = "";
        /** Secret: a chat's incoming webhook carries its own key. */
        String chatUrl = "";
        String mqttTopic = "";
        String webhookUrl = "";

        /** Whether an alert has anywhere to go. */
        boolean anyChannel() {
            return !ntfyUrl.isEmpty() || !chatUrl.isEmpty() || !mqttTopic.isEmpty()
                    || !webhookUrl.isEmpty();
        }

        /** How many of the {@link #READY_MADE} ready-made alerts are on. */
        int readyMadeOn() {
            boolean[] on = {offlineMinutes > 0, backOnline, pageError, batteryBelow > 0,
                temperatureAbove > 0, storageBelowMb > 0, updateAvailable, sharedRefused,
                combinationUsed, pinLockout};
            int count = 0;
            for (boolean one : on) {
                count += one ? 1 : 0;
            }
            return count;
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
        /** Free on the data partition, below zero when unknown. */
        long storageFreeMb = -1;
        /** The newest Muralis in the fleet when newer than this panel's, else empty. */
        String updateTo = "";
        /** The shared sections this panel refused, in words, empty for none. */
        String refused = "";
        boolean displayOff;
    }

    /** Another panel as last heard from. */
    static final class Peer {
        final String id;
        final String name;
        /** Below zero when not heard from since the app started. */
        final long seenAgoMs;
        /** Whether its display was off when last heard from. */
        final boolean displayOff;

        Peer(String id, String name, long seenAgoMs) {
            this(id, name, seenAgoMs, false);
        }

        Peer(String id, String name, long seenAgoMs, boolean displayOff) {
            this.id = id;
            this.name = name;
            this.seenAgoMs = seenAgoMs;
            this.displayOff = displayOff;
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

        /** One condition per kind and panel: its key. */
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
            case "alert_temperature_above":
            case "alert_storage_below_mb": {
                long number;
                try {
                    number = Long.parseLong(text);
                } catch (NumberFormatException notANumber) {
                    return "must be a whole number, 0 for off";
                }
                if (number < 0) {
                    return "must be 0 or more";
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
                if (key.equals("alert_storage_below_mb") && number > 1_000_000) {
                    return "megabytes, 1000000 at most";
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
            case "alert_ntfy_url":
                if (text.isEmpty()) {
                    return null;
                }
                if ((!text.startsWith("http://") && !text.startsWith("https://"))
                        || text.contains(" ") || !text.matches("https?://[^/]+/[^/?#]+/?")) {
                    return "the topic's whole address, such as https://ntfy.sh/hall-alerts";
                }
                return null;
            case "alert_chat_url":
                if (text.isEmpty() || chatKind(text) != null) {
                    return null;
                }
                return "a Slack, Discord, Google Chat or Teams (Power Automate) webhook address;"
                        + " any other service goes under Web address";
            case "alert_ntfy_token":
            case "alert_back_online":
            case "alert_update_available":
            case "alert_shared_refused":
            case "alert_quiet_display_off":
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

    /** The keys of the conditions in force, for the host tests to read the judgement. */
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
        // Held back, not ended, while quiet: nothing is said either way until the display is on.
        if (!(settings.quietDisplayOff && facts.displayOff)) {
            condition(alerts, PAGE_ERROR, selfId, selfName,
                    settings.pageError && facts.pageError, facts.pageErrorDetail);
        }
        boolean battery = settings.batteryBelow > 0 && facts.batteryPercent >= 0
                && facts.batteryPercent < settings.batteryBelow;
        condition(alerts, BATTERY, selfId, selfName, battery,
                facts.batteryPercent < 0 ? "" : Math.round(facts.batteryPercent) + "%");
        boolean hot = settings.temperatureAbove > 0 && !Double.isNaN(facts.temperatureC)
                && facts.temperatureC > settings.temperatureAbove;
        condition(alerts, TEMPERATURE, selfId, selfName, hot,
                Double.isNaN(facts.temperatureC) ? "" : Math.round(facts.temperatureC) + " °C");
        condition(alerts, STORAGE, selfId, selfName, settings.storageBelowMb > 0
                && facts.storageFreeMb >= 0 && facts.storageFreeMb < settings.storageBelowMb,
                facts.storageFreeMb < 0 ? "" : facts.storageFreeMb + " MB free");
        condition(alerts, SHARED_REFUSED, selfId, selfName,
                settings.sharedRefused && !facts.refused.isEmpty(), facts.refused);
        // Said once per newer version: a still newer one is said again, the same one never.
        String update = UPDATE + "/" + selfId;
        boolean newer = settings.updateAvailable && !facts.updateTo.isEmpty();
        if (newer && active.containsKey(update) && !facts.updateTo.equals(active.get(update))) {
            active.put(update, facts.updateTo);
            alerts.add(new Alert(UPDATE, selfId, selfName, true, facts.updateTo));
        } else {
            condition(alerts, UPDATE, selfId, selfName, newer, facts.updateTo);
        }
        long limit = settings.offlineMinutes * 60_000L;
        boolean leads = settings.offlineMinutes > 0 && leads(selfId, peers, limit, upMs);
        for (Peer peer : peers) {
            long unheard = peer.seenAgoMs < 0 ? upMs : peer.seenAgoMs;
            // A peer this panel said offline stays said until it is heard from again, whoever
            // leads now: a handover never says a panel is back while it is still gone.
            String key = OFFLINE + "/" + peer.id;
            boolean offline = settings.offlineMinutes > 0 && unheard >= limit
                    && (leads || active.containsKey(key));
            if (settings.quietDisplayOff && peer.displayOff && !active.containsKey(key)) {
                // Last heard with the display off: silence is expected, and nothing is said.
                continue;
            }
            int before = alerts.size();
            condition(alerts, OFFLINE, peer.id, peer.name, offline,
                    "not heard from for " + (unheard / 60_000L) + " min");
            if (!settings.backOnline && alerts.size() > before && !alerts.get(before).on) {
                alerts.remove(before);
            }
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

    /** An alert's heading, for a person: what and where. */
    static String title(Alert alert) {
        switch (alert.kind) {
            case AUTOMATION: return alert.panelName + ": " + alert.detail;
            case OFFLINE: return alert.on ? alert.panelName + " is offline"
                    : alert.panelName + " is back";
            case PAGE_ERROR: return alert.on ? alert.panelName + ": the page failed to load"
                    : alert.panelName + ": the page loads again";
            case BATTERY: return alert.on ? alert.panelName + ": battery low"
                    : alert.panelName + ": battery fine again";
            case TEMPERATURE: return alert.on ? alert.panelName + ": too hot"
                    : alert.panelName + ": cooled down";
            case STORAGE: return alert.on ? alert.panelName + ": storage almost full"
                    : alert.panelName + ": storage fine again";
            case UPDATE: return alert.on ? alert.panelName + ": update available"
                    : alert.panelName + ": up to date";
            case SHARED_REFUSED: return alert.on ? alert.panelName + ": shared settings refused"
                    : alert.panelName + ": shared settings applied";
            case TEST: return "Test from " + alert.panelName;
            case COMBINATION_USED: return alert.panelName + ": a combination was used";
            case PIN_LOCKOUT: return alert.on ? alert.panelName + ": PIN locked out"
                    : alert.panelName + ": PIN lockout over";
            default: return alert.panelName + ": " + alert.kind;
        }
    }

    /** The detail said under the heading: none for an automation's, whose words are the heading. */
    static String detailOf(Alert alert) {
        return alert.detail == null || AUTOMATION.equals(alert.kind) ? "" : alert.detail;
    }

    /** One line for a chat: what, where, and the detail. */
    static String text(String fleetName, Alert alert) {
        String detail = detailOf(alert).isEmpty() ? "" : " (" + detailOf(alert) + ")";
        return title(alert) + detail + (fleetName.isEmpty() ? "" : ", " + fleetName);
    }

    /**
     * Which chat a webhook address belongs to, by its host and path, or null for none known:
     * each answers only its own body (researched 2026-10-29).
     */
    static String chatKind(String url) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(url.trim());
        } catch (java.net.URISyntaxException notAnAddress) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (host.equals("hooks.slack.com") && path.startsWith("/services/")) {
            return SLACK;
        }
        if ((host.equals("discord.com") || host.equals("discordapp.com")
                || host.endsWith(".discord.com")) && path.startsWith("/api/webhooks/")) {
            return DISCORD;
        }
        if (host.equals("chat.googleapis.com") && path.startsWith("/v1/spaces/")) {
            return GOOGLE_CHAT;
        }
        // Power Automate's "When a Teams webhook request is received": the old Logic Apps
        // addresses and the Power Platform ones that replace them.
        if ((host.endsWith(".logic.azure.com") && path.contains("/workflows/"))
                || (host.endsWith(".powerplatform.com") && path.contains("/powerautomate/"))) {
            return TEAMS;
        }
        return null;
    }

    /**
     * The body {@code kind} takes: Slack's and Google Chat's text, Discord's content, and for
     * Teams an Adaptive Card in a message, which the Power Automate template loops over (a plain
     * text makes that flow fail).
     */
    static String chatBody(String kind, String fleetName, Alert alert) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (DISCORD.equals(kind)) {
            String text = text(fleetName, alert);
            body.put("content", text.length() > 2000 ? text.substring(0, 2000) : text);
        } else if (TEAMS.equals(kind)) {
            List<Object> blocks = new ArrayList<>();
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("type", "TextBlock");
            head.put("text", title(alert));
            head.put("weight", "Bolder");
            head.put("wrap", true);
            blocks.add(head);
            String detail = detailOf(alert);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "TextBlock");
            line.put("text", detail.isEmpty() ? fleetName : fleetName + ", " + detail);
            line.put("wrap", true);
            blocks.add(line);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
            card.put("type", "AdaptiveCard");
            card.put("version", "1.4");
            card.put("body", blocks);
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
            attachment.put("content", card);
            List<Object> attachments = new ArrayList<>();
            attachments.add(attachment);
            body.put("type", "message");
            body.put("attachments", attachments);
        } else {
            body.put("text", text(fleetName, alert));
        }
        return TinyJson.write(body);
    }

    /**
     * Where ntfy takes a JSON message for the topic at {@code topicUrl}: the server's root.
     * JSON rather than headers, so a name with accents arrives as written.
     */
    static String ntfyServer(String topicUrl) {
        String url = topicUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.substring(0, url.lastIndexOf('/') + 1);
    }

    /** ntfy's JSON message: the topic, the title, the detail, a high priority for a start. */
    static String ntfyBody(String topicUrl, String fleetName, Alert alert) {
        String url = topicUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String detail = detailOf(alert);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", url.substring(url.lastIndexOf('/') + 1));
        body.put("title", title(alert));
        body.put("message", detail.isEmpty() ? fleetName : fleetName.isEmpty() ? detail
                : fleetName + ", " + detail);
        body.put("priority", alert.on && !TEST.equals(alert.kind) ? 4L : 3L);
        List<Object> tags = new ArrayList<>();
        tags.add(alert.on ? "warning" : "white_check_mark");
        body.put("tags", tags);
        return TinyJson.write(body);
    }
}
