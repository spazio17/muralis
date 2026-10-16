/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Certificates a person trusted for a page Android itself does not trust: one
 * SHA-256 fingerprint per host, asked about once and then accepted for that host alone, never
 * any other certificate on it and never every certificate. Pure, so the store and the
 * fingerprint's form are host-tested; the WebView side is {@code KioskActivity}'s.
 */
final class CertificatePins {
    /** Enough for a home's servers; more is a list nobody reads. */
    static final int MAX_PINS = 32;

    /** A certificate a page offered that is waiting for a person's answer. */
    static final class Waiting {
        final String host;
        final String fingerprint;
        final String issuedTo;
        final String validUntil;

        Waiting(String host, String fingerprint, String issuedTo, String validUntil) {
            this.host = host;
            this.fingerprint = fingerprint;
            this.issuedTo = issuedTo == null ? "" : issuedTo;
            this.validUntil = validUntil == null ? "" : validUntil;
        }
    }

    /** The last certificate refused and not yet answered; the panel and the web admin show it. */
    static volatile Waiting waiting;

    private CertificatePins() {
    }

    /** The stored form, one "host fingerprint" a line, to a map in that order. */
    static Map<String, String> parse(String stored) {
        Map<String, String> pins = new LinkedHashMap<>();
        if (stored == null) {
            return pins;
        }
        for (String line : stored.split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length == 2 && isHost(parts[0]) && isFingerprint(parts[1])) {
                pins.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
            }
        }
        return pins;
    }

    static String format(Map<String, String> pins) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> pin : pins.entrySet()) {
            out.append(pin.getKey()).append(' ').append(pin.getValue()).append('\n');
        }
        return out.toString();
    }

    /** The certificate's SHA-256 as Android and browsers print it: hex pairs with colons. */
    static String fingerprint(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                if (out.length() > 0) {
                    out.append(':');
                }
                out.append(String.format(Locale.ROOT, "%02X", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean trusted(Map<String, String> pins, String host, String fingerprint) {
        return host != null && fingerprint != null
                && fingerprint.equals(pins.get(host.toLowerCase(Locale.ROOT)));
    }

    /**
     * The pins with one more: refused (null) when the list is full and the host is new, so a
     * page cannot push the person's own pins out.
     */
    static Map<String, String> with(Map<String, String> pins, String host, String fingerprint) {
        String key = host.toLowerCase(Locale.ROOT);
        if (!pins.containsKey(key) && pins.size() >= MAX_PINS) {
            return null;
        }
        Map<String, String> next = new LinkedHashMap<>(pins);
        next.put(key, fingerprint);
        return next;
    }

    static boolean isHost(String value) {
        return value != null && value.matches("[A-Za-z0-9.:\\[\\]-]{1,253}");
    }

    static boolean isFingerprint(String value) {
        return value != null && value.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
    }
}
