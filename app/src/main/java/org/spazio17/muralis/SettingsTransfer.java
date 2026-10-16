/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The settings file: every setting a person sets, as one JSON document the web admin
 * exports and imports, and the provisioning QR may carry. Never the panel's name or keys, its
 * fleet, its certificates or their keys, its purchase or its pictures; the fingerprints of the
 * certificates a person trusted (pages, the broker) are settings and travel. Passwords only when asked for, and
 * then encrypted with a passphrase (AES-GCM, the key from PBKDF2). Pure, so the form, the
 * allowed keys and the encryption are host-tested; each value's own check is the caller's.
 */
final class SettingsTransfer {
    static final String KIND = "muralis-settings";
    static final int VERSION = 1;
    static final int MIN_PASSPHRASE = 8;
    private static final int ITERATIONS = 210_000;

    /** The stored keys a file may carry, with their type: b boolean, i int, l long, s text. */
    static final Map<String, Character> KEYS = keys();

    /** The secrets a file may carry when the person asks for them. */
    static final String[] SECRETS = {"mqtt_username", "mqtt_password", "http_admin_password",
            "browser_user", "browser_password", "escape_pin_hash"};

    private static Map<String, Character> keys() {
        Map<String, Character> keys = new LinkedHashMap<>();
        for (String key : new String[] {"dashboard_url", "mqtt_host", "browser_user_agent",
                "screensaver_url", "alert_mqtt_topic", "alert_webhook_url",
                "orientation", "display_off_method", "screensaver_mode",
                "screensaver_on_wake", "screensaver_transition", "screensaver_picture_fit",
                "screensaver_credit_corner", "settings_sequence", "launcher_sequence",
                "automations", "beacon_names", "popup_cameras", "certificate_pins", "mqtt_tls_pin", "app_lock"}) {
            keys.put(key, 's');
        }
        for (String key : new String[] {"mqtt_port", "http_port", "fleet_port",
                "browser_text_size", "browser_page_zoom", "browser_back_after_s",
                "status_light_color", "status_light_percent", "screensaver_idle_s",
                "screensaver_off_s", "screensaver_dim_percent", "screensaver_picture_s"}) {
            keys.put(key, 'i');
        }
        for (String key : new String[] {"alert_offline_minutes", "alert_battery_below",
                "alert_temperature_above"}) {
            keys.put(key, 'l');
        }
        for (String key : new String[] {"stats_overlay", "browser_dark", "browser_page_camera",
                "browser_page_microphone", "status_light_on", "volume_buttons_disabled",
                "screensaver_shuffle", "screensaver_one_per_cycle", "screensaver_credit",
                "web_admin_enabled", "alert_page_error", "alert_combination_used",
                "alert_pin_lockout", "mqtt_tls"}) {
            keys.put(key, 'b');
        }
        return java.util.Collections.unmodifiableMap(keys);
    }

    /** The type of a stored key a file may carry, or null: the sensors' switches and options too. */
    static Character typeOf(String key) {
        Character known = KEYS.get(key);
        if (known != null) {
            return known;
        }
        if (key.startsWith("sensor_option_beacon_")) {
            // An older build's loose beacon names; the names travel in beacon_names.
            return null;
        }
        if (key.startsWith("sensor_option_")) {
            return 's';
        }
        if (key.startsWith("sensor_") && key.matches("sensor_[a-z_]{1,40}")) {
            return 'b';
        }
        return null;
    }

    /** The name a person reads in the file: Ambient's settings as Ambient, the rest as stored. */
    static String publicName(String key) {
        return key.startsWith("screensaver_") ? "ambient_" + key.substring(12) : key;
    }

    static String storedName(String name) {
        return name.startsWith("ambient_") ? "screensaver_" + name.substring(8) : name;
    }

    /**
     * The file packed for a QR's admin extras: deflated, then Base64, which takes a typical
     * file from about 1.8 KB to 1.1 KB and spares the JSON escapes a nested text would cost.
     */
    static String packed(String file) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9);
        deflater.setInput(file.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** The file back from {@link #packed}, or null when it is not one; at most 256 KB. */
    static String unpacked(String packed) {
        try {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(Base64.getDecoder().decode(packed));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    inflater.end();
                    return null;
                }
                out.write(buffer, 0, read);
                if (out.size() > 256 * 1024) {
                    inflater.end();
                    return null;
                }
            }
            inflater.end();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | java.util.zip.DataFormatException broken) {
            return null;
        }
    }

    /** What a file read back holds, or why it cannot be read. */
    static final class Contents {
        final Map<String, Object> settings;
        final Map<String, String> secrets;
        final String problem;
        final boolean hasSecrets;

        Contents(Map<String, Object> settings, Map<String, String> secrets, String problem,
                boolean hasSecrets) {
            this.settings = settings;
            this.secrets = secrets;
            this.problem = problem;
            this.hasSecrets = hasSecrets;
        }
    }

    /**
     * The file: the settings out of {@code stored} (the preferences as Android gives them back)
     * that a file may carry, and, when {@code secrets} is given with a passphrase, those
     * encrypted. {@code app} is the app's version, for the person reading the file.
     */
    static String export(Map<String, ?> stored, Map<String, String> secrets, String passphrase,
            String app) {
        Map<String, Object> settings = new java.util.TreeMap<>();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            Character type = typeOf(entry.getKey());
            Object value = entry.getValue();
            if (type != null && fits(type, value)) {
                settings.put(publicName(entry.getKey()),
                        value instanceof Integer ? (long) (Integer) value : value);
            }
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("kind", KIND);
        file.put("version", (long) VERSION);
        file.put("app", app);
        file.put("settings", settings);
        if (secrets != null && passphrase != null) {
            Map<String, Object> plain = new java.util.TreeMap<>();
            for (String key : SECRETS) {
                String value = secrets.get(key);
                if (value != null && !value.isEmpty()) {
                    plain.put(key, value);
                }
            }
            file.put("secrets", seal(TinyJson.write(plain), passphrase));
        }
        return TinyJson.write(file);
    }

    private static boolean fits(char type, Object value) {
        switch (type) {
            case 'b': return value instanceof Boolean;
            case 'i': return value instanceof Integer;
            case 'l': return value instanceof Long || value instanceof Integer;
            default: return value instanceof String;
        }
    }

    /**
     * A file read back: its settings by stored key with their stored type, unknown keys and
     * values of the wrong type left out, and its secrets opened with the passphrase when it has
     * any. A file that is not one, or a wrong passphrase, is a problem, never a half import.
     */
    static Contents read(String text, String passphrase) {
        Map<String, Object> root;
        try {
            root = TinyJson.object(TinyJson.parse(text));
        } catch (IllegalArgumentException notJson) {
            return problem("the file is not a Muralis settings file");
        }
        if (!KIND.equals(TinyJson.string(root, "kind", ""))) {
            return problem("the file is not a Muralis settings file");
        }
        if (TinyJson.number(root, "version", 0) != VERSION) {
            return problem("the file comes from another version of the settings file");
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : TinyJson.object(root.get("settings")).entrySet()) {
            String key = storedName(entry.getKey());
            Character type = typeOf(key);
            Object value = entry.getValue();
            if (type == null) {
                continue;
            }
            if (type == 'b' && value instanceof Boolean || type == 's' && value instanceof String) {
                settings.put(key, value);
            } else if ((type == 'i' || type == 'l') && value instanceof Double
                    && (Double) value == Math.rint((Double) value)
                    && Math.abs((Double) value) < 1e12) {
                double number = (Double) value;
                settings.put(key, type == 'i' ? (Object) (int) number : (Object) (long) number);
            }
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        Object sealed = root.get("secrets");
        boolean hasSecrets = sealed instanceof Map;
        if (hasSecrets) {
            if (passphrase == null || passphrase.isEmpty()) {
                return new Contents(settings, secrets, "the file carries passwords: type its"
                        + " passphrase", true);
            }
            String opened = open(TinyJson.object(sealed), passphrase);
            if (opened == null) {
                return new Contents(settings, secrets, "the passphrase does not open the file's"
                        + " passwords", true);
            }
            for (Map.Entry<String, Object> entry
                    : TinyJson.object(TinyJson.parse(opened)).entrySet()) {
                if (java.util.Arrays.asList(SECRETS).contains(entry.getKey())
                        && entry.getValue() instanceof String) {
                    secrets.put(entry.getKey(), (String) entry.getValue());
                }
            }
        }
        return new Contents(settings, secrets, null, hasSecrets);
    }

    private static Contents problem(String why) {
        return new Contents(new LinkedHashMap<>(), new LinkedHashMap<>(), why, false);
    }

    private static Map<String, Object> seal(String plain, String passphrase) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            random.nextBytes(salt);
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
            byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> sealed = new LinkedHashMap<>();
            sealed.put("cipher", "AES-256-GCM, key from PBKDF2-HMAC-SHA256, " + ITERATIONS
                    + " iterations");
            sealed.put("salt", Base64.getEncoder().encodeToString(salt));
            sealed.put("iv", Base64.getEncoder().encodeToString(iv));
            sealed.put("data", Base64.getEncoder().encodeToString(data));
            return sealed;
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The secrets' text, or null when the passphrase is wrong or the block was altered. */
    private static String open(Map<String, Object> sealed, String passphrase) {
        try {
            byte[] salt = Base64.getDecoder().decode(TinyJson.string(sealed, "salt", ""));
            byte[] iv = Base64.getDecoder().decode(TinyJson.string(sealed, "iv", ""));
            byte[] data = Base64.getDecoder().decode(TinyJson.string(sealed, "data", ""));
            if (salt.length != 16 || iv.length != 12) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException wrong) {
            return null;
        }
    }

    private static SecretKeySpec key(String passphrase, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256);
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
                .getEncoded();
        return new SecretKeySpec(raw, "AES");
    }
}
