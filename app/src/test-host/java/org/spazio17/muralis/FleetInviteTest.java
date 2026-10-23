/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.security.SecureRandom;

/** The invitation for a new tablet: ten minutes, single use, Cancel, five failures, its extra. */
public final class FleetInviteTest {

    public static void main(String[] args) {
        long now = 5_000_000L;
        FleetInvite invite = new FleetInvite(new SecureRandom());
        require(!invite.isOpen(now) && invite.take(now) == null, "open before anyone opened it");

        String code = invite.open(now);
        require(code != null && code.matches("[0-9]{8}"), "the code is not eight digits");
        require(invite.isOpen(now + FleetInvite.LIFETIME_MS - 1), "closed before its time");
        require(!invite.isOpen(now + FleetInvite.LIFETIME_MS), "open after its time");
        require(invite.result().equals("Invitation ran out."), "expiry not said");

        code = invite.open(now);
        require(invite.result().isEmpty(), "an old result shown on a new invitation");
        require(code.equals(invite.take(now)), "a knock did not get the code");
        require(invite.take(now + 10_000) == null, "a second knock while an adoption runs");
        invite.failed("no answer");
        require(invite.take(now + 1_000) == null, "knocks closer than the spacing");
        require(code.equals(invite.take(now + 10_000)), "a knock after a failure was refused");
        invite.joined("kitchen-panel");
        require(!invite.isOpen(now + 10_000) && invite.result().equals("kitchen-panel joined the fleet."),
                "single use: still open after a join");

        invite.open(now);
        long at = now;
        for (int i = 0; i < FleetInvite.MAX_FAILED; i++) {
            at += FleetInvite.KNOCK_SPACING_MS;
            require(invite.take(at) != null, "knock " + i + " refused");
            invite.failed("wrong code");
        }
        require(!invite.isOpen(at) && invite.result().equals("Closed after 5 failed tries."),
                "not closed after five failed tries");

        invite.open(now);
        invite.cancel();
        require(!invite.isOpen(now) && invite.result().isEmpty(), "Cancel left it open or said something");

        String extra = FleetInvite.extra("hallway-panel", "192.0.2.42", 8081,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "12345678",
                1_800_000_000_000L);
        FleetJoin.Invitation read = FleetJoin.parseQr(extra);
        require(read != null && read.code.equals("12345678") && read.port == 8081,
                "the extra does not read back as a Join invitation");
        require(FleetInvite.until(extra) == 1_800_000_000_000L && FleetInvite.until("x") == 0,
                "the end is not read back");

        FleetJoin join = new FleetJoin(new SecureRandom());
        require("12345678".equals(join.openGiven("12345678", now, 5 * 60_000L))
                && join.isOpen(now + 5 * 60_000L - 1) && !join.isOpen(now + 5 * 60_000L),
                "a given code does not last as long as the invitation");
        require(join.openGiven("1234", now, 60_000L) == null
                && join.openGiven("12345678", now, 0) == null,
                "a malformed or spent invitation opened a code");
        join.openGiven("12345678", now, 10 * FleetInvite.LIFETIME_MS);
        require(!join.isOpen(now + FleetInvite.LIFETIME_MS), "a given code outlived the invitation");
        System.out.println("FleetInviteTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
