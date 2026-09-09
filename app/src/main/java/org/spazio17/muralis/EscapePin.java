/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The optional second lock behind a tap combination (2026-09-09). A combination can be watched
 * and repeated; a PIN has to be known. Four to eight digits, stored as a salted PBKDF2 hash so a
 * dump of the preferences does not hand it over, compared in constant time. What actually
 * defends a short PIN is the lockout in the activity (AuthThrottle's schedule), not the hash;
 * the iteration count is kept modest so the check does not stall a low-end tablet on a button
 * press.
 *
 * <p>Pure Java, host-tested.
 */
final class EscapePin {
    static final int MIN_LENGTH = 4;
    static final int MAX_LENGTH = 8;
    private static final int ITERATIONS = 20_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";

    private EscapePin() {
    }

    /** Null when the PIN is acceptable, otherwise the refusal in one sentence. */
    static String validationProblem(String pin) {
        if (pin == null || pin.length() < MIN_LENGTH || pin.length() > MAX_LENGTH) {
            return "the PIN must be " + MIN_LENGTH + " to " + MAX_LENGTH + " digits";
        }
        for (int i = 0; i < pin.length(); i++) {
            if (pin.charAt(i) < '0' || pin.charAt(i) > '9') {
                return "the PIN must be " + MIN_LENGTH + " to " + MAX_LENGTH + " digits";
            }
        }
        return null;
    }

    /** The stored form: {@code pbkdf2-sha256$iterations$salt$hash}, hex throughout. */
    static String hash(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return PREFIX + "$" + ITERATIONS + "$" + hex(salt) + "$" + hex(derive(pin, salt, ITERATIONS));
    }

    /** Constant-time; false for anything stored that is not a hash this class wrote. */
    static boolean matches(String pin, String stored) {
        if (pin == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = unhex(parts[2]);
            byte[] expected = unhex(parts[3]);
            return MessageDigest.isEqual(expected, derive(pin, salt, iterations));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS))
                    .getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return text.toString();
    }

    private static byte[] unhex(String text) {
        if (text.length() % 2 != 0) {
            throw new IllegalArgumentException("odd hex");
        }
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
