/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The fleet's alerts: each said once, the offline ones by one panel, the settings' checks. */
public final class FleetAlertsTest {

    public static void main(String[] args) {
        FleetAlerts alerts = new FleetAlerts();
        FleetAlerts.Settings settings = new FleetAlerts.Settings();
        FleetAlerts.Facts facts = new FleetAlerts.Facts();
        List<FleetAlerts.Peer> nobody = new ArrayList<>();

        // Nothing wrong: nothing said, however often it is judged.
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).isEmpty(),
                "an alert with nothing wrong");

        // A page error: "on" once, then silence while it stands, "off" once when it ends.
        facts.pageError = true;
        facts.pageErrorDetail = "net::ERR_NAME_NOT_RESOLVED";
        List<FleetAlerts.Alert> said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && said.get(0).on && said.get(0).kind.equals("page_error")
                && said.get(0).detail.equals("net::ERR_NAME_NOT_RESOLVED")
                && said.get(0).panelName.equals("Hall"), "the page error was not raised once");
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).isEmpty(),
                "the page error was repeated");
        facts.pageError = false;
        said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && !said.get(0).on && said.get(0).kind.equals("page_error"),
                "the page error's end was not said once");
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).isEmpty(),
                "the end was repeated");

        // A kind switched off is never raised, and a condition in force ends when its kind is.
        facts.pageError = true;
        settings.pageError = false;
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).isEmpty(),
                "a page error raised with the kind off");
        settings.pageError = true;
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).size() == 1,
                "not raised when the kind came on");
        settings.pageError = false;
        said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && !said.get(0).on, "switching the kind off did not end it");
        facts.pageError = false;

        // Battery: below the threshold on a panel with one; a threshold change ends it.
        facts.batteryPercent = 12;
        said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && said.get(0).kind.equals("battery") && said.get(0).on
                && said.get(0).detail.equals("12%"), "battery 12% under 15 was not raised");
        settings.batteryBelow = 10;
        said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && !said.get(0).on, "a lower threshold did not end it");
        settings.batteryBelow = 100;
        facts.batteryPercent = -1;
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).isEmpty(),
                "a panel without a battery raised a battery alert");
        settings.batteryBelow = 15;

        // Temperature: only where one is read.
        facts.temperatureC = 51.4;
        said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && said.get(0).kind.equals("temperature")
                && said.get(0).detail.equals("51 °C"), "51 °C over 45 was not raised");
        facts.temperatureC = Double.NaN;
        said = alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(said.size() == 1 && !said.get(0).on, "an unknown temperature did not end it");
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0).isEmpty(),
                "an unknown temperature was raised");

        // Offline: the online panel whose id sorts first says it, once, and its end once.
        long minute = 60_000L;
        List<FleetAlerts.Peer> peers = Arrays.asList(
                new FleetAlerts.Peer("kiosk-a", "Lobby", 2 * minute),
                new FleetAlerts.Peer("kiosk-c", "Kitchen", 12 * minute));
        require(alerts.judge(settings, facts, "kiosk-b", "Hall", peers, 0).isEmpty(),
                "kiosk-b spoke for the fleet while kiosk-a is online");
        require(!FleetAlerts.leads("kiosk-b", peers, 10 * minute, 0)
                && FleetAlerts.leads("kiosk-a", peers, 10 * minute, 0),
                "the leader is not the first online id");
        FleetAlerts lobby = new FleetAlerts();
        List<FleetAlerts.Peer> fromLobby = Arrays.asList(
                new FleetAlerts.Peer("kiosk-b", "Hall", 1 * minute),
                new FleetAlerts.Peer("kiosk-c", "Kitchen", 12 * minute));
        said = lobby.judge(settings, facts, "kiosk-a", "Lobby", fromLobby, 0);
        require(said.size() == 1 && said.get(0).kind.equals("offline") && said.get(0).on
                && said.get(0).panelId.equals("kiosk-c") && said.get(0).panelName.equals("Kitchen")
                && said.get(0).detail.equals("not heard from for 12 min"),
                "the Kitchen offline was not raised by the Lobby: " + said.size());
        require(lobby.judge(settings, facts, "kiosk-a", "Lobby", fromLobby, 0).isEmpty(),
                "the offline was repeated");
        fromLobby = Arrays.asList(
                new FleetAlerts.Peer("kiosk-b", "Hall", 1 * minute),
                new FleetAlerts.Peer("kiosk-c", "Kitchen", 0));
        said = lobby.judge(settings, facts, "kiosk-a", "Lobby", fromLobby, 0);
        require(said.size() == 1 && !said.get(0).on && said.get(0).panelId.equals("kiosk-c"),
                "the Kitchen's return was not said");
        // When the first id goes dark, the next online one takes over, from its own state.
        fromLobby = Arrays.asList(
                new FleetAlerts.Peer("kiosk-b", "Hall", 11 * minute),
                new FleetAlerts.Peer("kiosk-c", "Kitchen", 0));
        said = lobby.judge(settings, facts, "kiosk-a", "Lobby", fromLobby, 0);
        require(said.size() == 1 && said.get(0).panelId.equals("kiosk-b") && said.get(0).on,
                "the Hall offline was not raised");
        // A handover: the Lobby said the Hall offline while it led; when a lower id (kiosk-0)
        // comes back and leads, the Lobby does not say the Hall is back while it is still gone,
        // and says so only when the Hall is heard from again.
        fromLobby = Arrays.asList(
                new FleetAlerts.Peer("kiosk-0", "Bar", 0),
                new FleetAlerts.Peer("kiosk-b", "Hall", 12 * minute),
                new FleetAlerts.Peer("kiosk-c", "Kitchen", 0));
        require(lobby.judge(settings, facts, "kiosk-a", "Lobby", fromLobby, 0).isEmpty(),
                "the handover said the Hall was back while it is still gone");
        fromLobby = Arrays.asList(
                new FleetAlerts.Peer("kiosk-0", "Bar", 0),
                new FleetAlerts.Peer("kiosk-b", "Hall", 0),
                new FleetAlerts.Peer("kiosk-c", "Kitchen", 0));
        said = lobby.judge(settings, facts, "kiosk-a", "Lobby", fromLobby, 0);
        require(said.size() == 1 && !said.get(0).on && said.get(0).panelId.equals("kiosk-b"),
                "the Hall's return was not said after the handover");
        // A peer never heard from counts as unheard since the app started.
        FleetAlerts fresh = new FleetAlerts();
        List<FleetAlerts.Peer> unheard = Arrays.asList(
                new FleetAlerts.Peer("kiosk-c", "Kitchen", -1));
        require(fresh.judge(settings, facts, "kiosk-a", "Lobby", unheard, 3 * minute).isEmpty(),
                "a peer unheard for three minutes was said offline");
        said = fresh.judge(settings, facts, "kiosk-a", "Lobby", unheard, 11 * minute);
        require(said.size() == 1 && said.get(0).on, "a peer unheard since the start was not said");
        // Offline off: nothing, and the condition in force ends.
        settings.offlineMinutes = 0;
        said = fresh.judge(settings, facts, "kiosk-a", "Lobby", unheard, 11 * minute);
        require(said.size() == 1 && !said.get(0).on, "offline off did not end the alert");
        settings.offlineMinutes = 10;

        // Events: a combination used is one per use; a PIN lockout is on and off.
        require(alerts.event(settings, "combination_used", true, "kiosk-b", "Hall", "settings")
                .isEmpty(), "a combination was said with its kind off");
        settings.combinationUsed = true;
        said = alerts.event(settings, "combination_used", true, "kiosk-b", "Hall", "settings");
        require(said.size() == 1 && said.get(0).on && said.get(0).detail.equals("settings"),
                "a combination used was not said");
        require(alerts.event(settings, "combination_used", true, "kiosk-b", "Hall", "launcher")
                .size() == 1, "a second use was not said");
        said = alerts.event(settings, "pin_lockout", true, "kiosk-b", "Hall", "five wrong PINs");
        require(said.size() == 1 && said.get(0).on, "the lockout was not said");
        require(alerts.event(settings, "pin_lockout", true, "kiosk-b", "Hall", "").isEmpty(),
                "the lockout was repeated");
        said = alerts.event(settings, "pin_lockout", false, "kiosk-b", "Hall", "");
        require(said.size() == 1 && !said.get(0).on, "the lockout's end was not said");
        require(alerts.activeKeys().isEmpty(), "a condition stayed in force: "
                + alerts.activeKeys());

        // Reset forgets what is in force without a word.
        facts.pageError = true;
        settings.pageError = true;
        alerts.judge(settings, facts, "kiosk-b", "Hall", nobody, 0);
        require(alerts.activeKeys().equals(Arrays.asList("page_error/kiosk-b")),
                "the active keys are wrong: " + alerts.activeKeys());
        alerts.reset();
        require(alerts.activeKeys().isEmpty(), "reset kept a condition");

        // The JSON every destination gets.
        String json = FleetAlerts.json("Home", new FleetAlerts.Alert("battery", "kiosk-b", "Hall",
                true, "12%"), "2026-10-13T10:00:00Z");
        require(json.equals("{\"fleet\":\"Home\",\"panel\":\"Hall\",\"panel_id\":\"kiosk-b\","
                + "\"kind\":\"battery\",\"state\":\"on\",\"detail\":\"12%\","
                + "\"at\":\"2026-10-13T10:00:00Z\"}"), "the JSON is wrong: " + json);

        // The settings' checks.
        require(FleetAlerts.problem("alert_offline_minutes", "0") == null
                && FleetAlerts.problem("alert_offline_minutes", "7") == null
                && FleetAlerts.problem("alert_offline_minutes", "6") != null
                && FleetAlerts.problem("alert_offline_minutes", "x") != null
                && FleetAlerts.problem("alert_offline_minutes", "-1") != null,
                "the offline minutes check is wrong");
        require(FleetAlerts.problem("alert_battery_below", "101") != null
                && FleetAlerts.problem("alert_battery_below", "100") == null
                && FleetAlerts.problem("alert_temperature_above", "45") == null,
                "the battery or temperature check is wrong");
        require(FleetAlerts.problem("alert_mqtt_topic", "") == null
                && FleetAlerts.problem("alert_mqtt_topic", "kiosk/fleet/alerts") == null
                && FleetAlerts.problem("alert_mqtt_topic", "kiosk/#") != null
                && FleetAlerts.problem("alert_mqtt_topic", "a b") != null,
                "the topic check is wrong");
        require(FleetAlerts.problem("alert_webhook_url", "") == null
                && FleetAlerts.problem("alert_webhook_url", "https://h.example/alerts") == null
                && FleetAlerts.problem("alert_webhook_url", "h.example") != null
                && FleetAlerts.problem("alert_pin_lockout", "1") == null
                && FleetAlerts.problem("alert_other", "1") != null,
                "the address check is wrong");
        require(new FleetAlerts.Settings().anyKind(), "the defaults switch no kind on");
        FleetAlerts.Settings none = new FleetAlerts.Settings();
        none.offlineMinutes = 0;
        none.pageError = false;
        none.batteryBelow = 0;
        none.temperatureAbove = 0;
        none.pinLockout = false;
        require(!none.anyKind(), "no kind on reads as one on");

        System.out.println("FleetAlertsTest: all checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
