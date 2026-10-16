/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.HashMap;
import java.util.Map;

/** The settings file (block 8): what it carries, what it never does, and its passwords. */
public final class SettingsTransferTest {
    public static void main(String[] args) {
        Map<String, Object> stored = new HashMap<>();
        stored.put("dashboard_url", "https://ha.local:8123/");
        stored.put("mqtt_port", 1883);
        stored.put("alert_battery_below", 15L);
        stored.put("screensaver_mode", "pictures");
        stored.put("sensor_light", true);
        stored.put("sensor_option_camera_name", "Door");
        stored.put("device_id", "kiosk-1234");
        stored.put("fleet_id", "fleet-ab");
        stored.put("http_certificate", "uploaded");
        stored.put("kiosk_stopped", true);
        Map<String, String> secrets = new HashMap<>();
        secrets.put("mqtt_password", "broker-secret");
        secrets.put("http_admin_password", "admin-secret");
        secrets.put("something_else", "never");

        String plain = SettingsTransfer.export(stored, null, null, "0.7.0");
        require(plain.contains("\"ambient_mode\":\"pictures\"") && !plain.contains("screensaver_"),
                "Ambient's settings under their own name: " + plain);
        require(!plain.contains("kiosk-1234") && !plain.contains("fleet-ab")
                && !plain.contains("uploaded") && !plain.contains("kiosk_stopped")
                && !plain.contains("secret") && !plain.contains("\"secrets\""),
                "never the name, the fleet, certificates, state or passwords: " + plain);
        SettingsTransfer.Contents back = SettingsTransfer.read(plain, null);
        require(back.problem == null && "pictures".equals(back.settings.get("screensaver_mode"))
                && Integer.valueOf(1883).equals(back.settings.get("mqtt_port"))
                && Long.valueOf(15).equals(back.settings.get("alert_battery_below"))
                && Boolean.TRUE.equals(back.settings.get("sensor_light"))
                && "Door".equals(back.settings.get("sensor_option_camera_name"))
                && !back.hasSecrets, "the settings come back with their stored types: "
                + back.settings);

        String sealed = SettingsTransfer.export(stored, secrets, "a passphrase", "0.7.0");
        require(sealed.contains("\"secrets\"") && !sealed.contains("broker-secret")
                && !sealed.contains("admin-secret"), "passwords travel encrypted only");
        SettingsTransfer.Contents opened = SettingsTransfer.read(sealed, "a passphrase");
        require(opened.problem == null && "broker-secret".equals(opened.secrets.get("mqtt_password"))
                && "admin-secret".equals(opened.secrets.get("http_admin_password"))
                && !opened.secrets.containsKey("something_else"), "opened with the passphrase");
        require(SettingsTransfer.read(sealed, "wrong passphrase").problem != null
                && SettingsTransfer.read(sealed, null).problem != null,
                "a wrong or missing passphrase is a problem, not a half import");
        require(SettingsTransfer.read(sealed.replace("\"data\":\"", "\"data\":\"AA"), "a passphrase")
                .problem != null, "an altered block does not open");

        require(SettingsTransfer.read("{}", null).problem != null
                && SettingsTransfer.read("not json", null).problem != null
                && SettingsTransfer.read("{\"kind\":\"muralis-settings\",\"version\":2}", null)
                        .problem != null, "other files are refused with a reason");
        SettingsTransfer.Contents odd = SettingsTransfer.read("{\"kind\":\"muralis-settings\","
                + "\"version\":1,\"settings\":{\"mqtt_port\":\"1883\",\"device_id\":\"x\","
                + "\"fleet_id\":\"y\",\"http_port\":8443.5,\"stats_overlay\":false}}", null);
        require(odd.settings.size() == 1 && Boolean.FALSE.equals(odd.settings.get("stats_overlay")),
                "wrong types and keys a file may not carry are left out: " + odd.settings);
        require(SettingsTransfer.unpacked(SettingsTransfer.packed(plain)).equals(plain),
                "the packed file for a QR comes back whole");
        require(SettingsTransfer.unpacked("not base64!") == null
                && SettingsTransfer.unpacked("AAAA") == null, "a broken pack is null");
        stored.put("sensor_option_beacon_x", "Keys");
        require(!SettingsTransfer.export(stored, null, null, "0.7.0").contains("beacon_x"),
                "old loose beacon names stay out");
        String readable = TinyJson.writeIndented(TinyJson.parse(plain));
        require(readable.contains("\n  \"settings\": {\n    \"")
                && SettingsTransfer.read(readable, null).problem == null
                && TinyJson.write(TinyJson.parse(readable)).equals(plain),
                "the indented file reads back as the compact one: " + readable);
        System.out.println("SettingsTransferTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
