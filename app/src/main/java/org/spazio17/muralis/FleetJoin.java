/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The join code a panel shows on its Join screen (the fleet design, section 5): eight digits,
 * single use, ten minutes, opened from the Fleet section of either surface. While a code is open
 * the member endpoint lets an unknown key through the handshake, for the join route only, and the
 * code is what authenticates that caller; five wrong codes close it and refuse a new one for ten
 * minutes.
 *
 * <p>Times are on a clock that a change of the device's date cannot move (FleetServer.joinClock).
 *
 * <p>Pure, with no Android imports, so the rules are covered by host tests. One per process
 * ({@link #shared}), kept in memory only: a restart ends an open code and a lockout alike, and
 * the person at the panel opens a new code; without that person no unknown key gets in.
 */
final class FleetJoin {
    static final long CODE_LIFETIME_MS = 10 * 60_000L;
    static final int MAX_WRONG_CODES = 5;
    static final long LOCKOUT_MS = 10 * 60_000L;
    private static final int NAME_LIMIT = 64;

    /** The one the panel, the web admin and the member endpoint share. */
    static final FleetJoin shared = new FleetJoin(new SecureRandom());

    /** What a join attempt came to. */
    enum Outcome {
        ADMITTED,
        WRONG,
        /** Five wrong codes, now or earlier: no code is taken until the lockout ends. */
        LOCKED,
        /** No code is open, or it expired. */
        CLOSED
    }

    private final SecureRandom random;
    private String code;
    private long expiresAt;
    private int wrong;
    private long lockedUntil;

    FleetJoin(SecureRandom random) {
        this.random = random;
    }

    /** Opens a new code, replacing any open one; null while joining is locked out. */
    synchronized String open(long now) {
        if (now < lockedUntil) {
            return null;
        }
        code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        expiresAt = now + CODE_LIFETIME_MS;
        wrong = 0;
        return code;
    }

    /** Ends the open code, as closing the Join screen does. */
    synchronized void close() {
        code = null;
    }

    /** The open code, or null; an expired one is closed here. */
    synchronized String code(long now) {
        if (code != null && now >= expiresAt) {
            code = null;
        }
        return code;
    }

    /** Whether an unknown key may complete a handshake now, for the join route only. */
    boolean isOpen(long now) {
        return code(now) != null;
    }

    synchronized long expiresAt() {
        return expiresAt;
    }

    /** When a new code may be opened again, or 0 when it may now. */
    synchronized long lockedUntil(long now) {
        return now < lockedUntil ? lockedUntil : 0;
    }

    /** Wrong codes tried against the open code so far, for the Join screen's sentence. */
    synchronized int wrongCodes() {
        return wrong;
    }

    /** Checks an offered code in constant time; a right one is used up. */
    synchronized Outcome attempt(String offered, long now) {
        if (now < lockedUntil) {
            return Outcome.LOCKED;
        }
        String current = code(now);
        if (current == null) {
            return Outcome.CLOSED;
        }
        byte[] expected = current.getBytes(StandardCharsets.US_ASCII);
        byte[] given = (offered == null ? "" : offered).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expected, given)) {
            code = null;
            wrong = 0;
            return Outcome.ADMITTED;
        }
        wrong++;
        if (wrong >= MAX_WRONG_CODES) {
            code = null;
            wrong = 0;
            lockedUntil = now + LOCKOUT_MS;
            return Outcome.LOCKED;
        }
        return Outcome.WRONG;
    }

    /** "1234 5678", as the Join screen shows it under the QR. */
    static String grouped(String code) {
        return code.length() == 8 ? code.substring(0, 4) + " " + code.substring(4) : code;
    }

    /** What the Join screen's QR carries (fleet design, section 5). */
    static String qrContent(String id, String address, int port, String spki, String code) {
        return "muralis-join:2;id=" + id + ";addr=" + address + ";port=" + port + ";spki=" + spki
                + ";code=" + code;
    }

    /**
     * Null when a joining manager's fleet id and name can be stored, the reason otherwise: an id
     * of letters, digits, dot, underscore and hyphen, and a name of up to 64 characters with no
     * control characters, as device ids and names are checked.
     */
    static String validateFleet(String id, String name) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
            return "the fleet id may only use letters, digits, dot, underscore and hyphen, up to 64"
                    + " characters";
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_LIMIT) {
            return "the fleet name must have 1 to " + NAME_LIMIT + " characters";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                return "the fleet name must not contain control characters";
            }
        }
        return null;
    }
}
