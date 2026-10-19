/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * An invitation for a new tablet: the QR a fleet panel shows that sets a factory-reset tablet up
 * as device owner and joins it to the fleet in one scan. It carries an eight-digit code and this
 * panel's address, member port and key hash in the provisioning extras; the new tablet opens its
 * Join screen with that code and knocks on this panel's member port, and this panel then adds it
 * as a scanned Join QR adds one (Spake2, the code never travels). As the Android Management API's
 * enrolment token: ten minutes (a tablet needs one or two), single use, cancelled with the page, closed after five failed proofs.
 *
 * <p>Times are on the join clock (FleetServer.joinClock), which a change of the date cannot move.
 * Pure, with no Android imports, so the rules are host-tested; one per process ({@link #shared}),
 * in memory only, so a restart ends an invitation as it ends a Join code.
 */
final class FleetInvite {
    static final long LIFETIME_MS = 10 * 60_000L;
    static final int MAX_FAILED = 5;
    /** Between two knocks that start an adoption, so a flood of them cannot run one per call. */
    static final long KNOCK_SPACING_MS = 2_000L;

    static final FleetInvite shared = new FleetInvite(new SecureRandom());

    private final SecureRandom random;
    private String code;
    private long expiresAt;
    private int failed;
    private boolean adding;
    private long lastKnock = Long.MIN_VALUE / 2;
    /** What the last invitation came to, for the page that opened it; "" while one is open. */
    private String result = "";

    FleetInvite(SecureRandom random) {
        this.random = random;
    }

    /** Opens a new invitation, replacing any open one, and returns its code. */
    synchronized String open(long now) {
        code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        expiresAt = now + LIFETIME_MS;
        failed = 0;
        adding = false;
        lastKnock = Long.MIN_VALUE / 2;
        result = "";
        return code;
    }

    /** Ends the open invitation, as Cancel invitation does; nothing is said afterwards. */
    synchronized void cancel() {
        code = null;
        adding = false;
        result = "";
    }

    /** The open invitation's code, or null; an expired one is closed here and says so. */
    synchronized String code(long now) {
        if (code != null && now >= expiresAt) {
            code = null;
            adding = false;
            result = "Invitation ran out.";
        }
        return code;
    }

    synchronized boolean isOpen(long now) {
        return code(now) != null;
    }

    synchronized long expiresAt() {
        return expiresAt;
    }

    /**
     * A knock arrived: the code to prove to the knocking tablet, or null when no invitation is
     * open, an adoption already runs, or the last one started too recently.
     */
    synchronized String take(long now) {
        if (code(now) == null || adding || now - lastKnock < KNOCK_SPACING_MS) {
            return null;
        }
        adding = true;
        lastKnock = now;
        return code;
    }

    /** The knocking tablet joined: the invitation is used up. */
    synchronized void joined(String id) {
        code = null;
        adding = false;
        result = id + " joined the fleet.";
    }

    /** The adoption a knock started failed; after {@link #MAX_FAILED} the invitation closes. */
    synchronized void failed(String why) {
        adding = false;
        if (code == null) {
            return;
        }
        if (++failed >= MAX_FAILED) {
            code = null;
            // The reason goes to the log (FleetServer); the page says only what happened.
            result = "Closed after " + MAX_FAILED + " failed tries.";
        }
    }

    /** What the last invitation came to, or "" while one is open or none was made. */
    synchronized String result() {
        return result;
    }

    /** The extras' text for a new tablet: the Join QR's fields and when the invitation ends. */
    static String extra(String id, String address, int port, String spki, String code,
            long untilWallMs) {
        return FleetJoin.qrContent(id, address, port, spki, code) + ";until=" + untilWallMs;
    }

    /** When an invitation's extra ends, in wall-clock milliseconds, or 0 when it says nothing. */
    static long until(String extra) {
        if (extra == null) {
            return 0;
        }
        for (String part : extra.split(";")) {
            if (part.startsWith("until=") && part.substring(6).matches("[0-9]{1,15}")) {
                return Long.parseLong(part.substring(6));
            }
        }
        return 0;
    }
}
