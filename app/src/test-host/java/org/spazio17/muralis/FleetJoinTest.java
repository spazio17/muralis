/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.security.SecureRandom;

/** The join code's rules: eight digits, single use, ten minutes, five wrong codes, a lockout. */
public final class FleetJoinTest {

    public static void main(String[] args) {
        long now = 1_000_000L;

        FleetJoin join = new FleetJoin(new SecureRandom());
        require(!join.isOpen(now), "a join was open before anyone opened one");
        require(join.attempt("12345678", now) == FleetJoin.Outcome.CLOSED,
                "a code was taken with no join open");

        String code = join.open(now);
        require(code != null && code.matches("[0-9]{8}"), "the code is not eight digits: " + code);
        require(join.isOpen(now), "an opened join is not open");
        require(join.attempt(code, now + 1) == FleetJoin.Outcome.ADMITTED,
                "the right code was refused");
        require(!join.isOpen(now + 2), "a used code stayed open");
        require(join.attempt(code, now + 3) == FleetJoin.Outcome.CLOSED,
                "a used code was taken twice");

        // Ten minutes, then closed.
        code = join.open(now);
        require(join.isOpen(now + FleetJoin.CODE_LIFETIME_MS - 1), "closed before ten minutes");
        require(!join.isOpen(now + FleetJoin.CODE_LIFETIME_MS), "open after ten minutes");
        require(join.attempt(code, now + FleetJoin.CODE_LIFETIME_MS) == FleetJoin.Outcome.CLOSED,
                "an expired code was taken");

        // A new code replaces the open one.
        String first = join.open(now);
        String second = join.open(now + 1);
        if (!first.equals(second)) {
            require(join.attempt(first, now + 2) == FleetJoin.Outcome.WRONG,
                    "a replaced code still counted");
        }
        join.close();
        require(!join.isOpen(now + 3), "close() left the join open");

        // Wrong codes: four are told, the fifth locks for ten minutes and ends the code.
        FleetJoin guessed = new FleetJoin(new SecureRandom());
        code = guessed.open(now);
        String wrong = code.equals("00000000") ? "00000001" : "00000000";
        for (int i = 1; i < FleetJoin.MAX_WRONG_CODES; i++) {
            require(guessed.attempt(wrong, now + i) == FleetJoin.Outcome.WRONG,
                    "wrong code " + i + " was not just wrong");
            require(guessed.wrongCodes() == i, "wrong codes miscounted");
        }
        require(guessed.attempt(wrong, now + 10) == FleetJoin.Outcome.LOCKED,
                "the fifth wrong code did not lock");
        require(guessed.attempt(code, now + 11) == FleetJoin.Outcome.LOCKED,
                "the right code was taken during the lockout");
        require(guessed.open(now + 12) == null, "a new code opened during the lockout");
        require(guessed.lockedUntil(now + 12) == now + 10 + FleetJoin.LOCKOUT_MS,
                "the lockout does not end ten minutes after the fifth wrong code");
        require(!guessed.isOpen(now + 12), "a locked join admits unknown keys");
        long after = now + 10 + FleetJoin.LOCKOUT_MS;
        require(guessed.lockedUntil(after) == 0, "the lockout did not end");
        code = guessed.open(after);
        require(code != null, "no new code after the lockout");
        require(guessed.wrongCodes() == 0, "the count did not start again");
        require(guessed.attempt(code, after + 1) == FleetJoin.Outcome.ADMITTED,
                "the right code was refused after the lockout");

        // Odd offers are wrong codes, never an exception.
        code = guessed.open(after + 2);
        require(guessed.attempt(null, after + 3) == FleetJoin.Outcome.WRONG, "null was not wrong");
        require(guessed.attempt("", after + 4) == FleetJoin.Outcome.WRONG, "empty was not wrong");
        require(guessed.attempt(code + "0", after + 5) == FleetJoin.Outcome.WRONG,
                "a longer code was taken");
        require(guessed.attempt(" " + code, after + 6) == FleetJoin.Outcome.WRONG,
                "a padded code was taken");

        require(FleetJoin.grouped("12345678").equals("1234 5678"), "grouped wrongly");
        require(FleetJoin.qrContent("kiosk-1", "192.0.2.5", 8081, "ab", "12345678").equals(
                "muralis-join:2;id=kiosk-1;addr=192.0.2.5;port=8081;spki=ab;code=12345678"),
                "the QR content is not the design's");

        require(FleetJoin.validateFleet("home-1", "Home") == null, "a plain fleet was refused");
        require(FleetJoin.validateFleet("", "Home") != null, "an empty id was taken");
        require(FleetJoin.validateFleet("a b", "Home") != null, "an id with a space was taken");
        require(FleetJoin.validateFleet(repeat('a', 65), "Home") != null, "a 65-character id");
        require(FleetJoin.validateFleet(repeat('a', 64), "Home") == null, "a 64-character id");
        require(FleetJoin.validateFleet("home", " ") != null, "a blank name was taken");
        require(FleetJoin.validateFleet("home", repeat('n', 65)) != null, "a 65-character name");
        require(FleetJoin.validateFleet("home", "Casa Müller 🏠") == null,
                "accents and emoji were refused");
        require(FleetJoin.validateFleet("home", "Home\nTwo") != null, "a line break was taken");
        require(FleetJoin.validateFleet(null, null) != null, "nulls were taken");

        System.out.println("FleetJoinTest passed");
    }

    private static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(c);
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
