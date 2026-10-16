/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.security.SecureRandom;

/** The join code's rules: eight digits, single use, ten minutes, proven by SPAKE2, five tries, a lockout. */
public final class FleetJoinTest {

    public static void main(String[] args) {
        long now = 1_000_000L;

        java.math.BigInteger one = java.math.BigInteger.ONE;
        require(Spake2.G.modPow(Spake2.Q, Spake2.P).equals(one)
                && Spake2.M.modPow(Spake2.Q, Spake2.P).equals(one)
                && Spake2.N.modPow(Spake2.Q, Spake2.P).equals(one)
                && !Spake2.M.equals(one) && !Spake2.N.equals(one) && !Spake2.M.equals(Spake2.N),
                "the group's elements are not in the subgroup of order q");
        FleetJoin join = new FleetJoin(new SecureRandom());
        require(!join.isOpen(now), "a join was open before anyone opened one");
        require(tryCode(join, "12345678", now) == FleetJoin.Outcome.CLOSED,
                "a code was taken with no join open");
        require(join.tag(now).isEmpty(), "a tag with no code open");

        String code = join.open(now);
        require(code != null && code.matches("[0-9]{8}"), "the code is not eight digits: " + code);
        require(join.isOpen(now), "an opened join is not open");
        require(join.tag(now).equals(code.substring(0, 2)), "the tag is not the first two digits");
        require(tryCode(join, code, now + 1) == FleetJoin.Outcome.ADMITTED,
                "the right code was refused");
        require(!join.isOpen(now + 2), "a used code stayed open");
        require(tryCode(join, code, now + 3) == FleetJoin.Outcome.CLOSED,
                "a used code was taken twice");

        // Ten minutes, then closed.
        code = join.open(now);
        require(join.isOpen(now + FleetJoin.CODE_LIFETIME_MS - 1), "closed before ten minutes");
        require(!join.isOpen(now + FleetJoin.CODE_LIFETIME_MS), "open after ten minutes");
        require(tryCode(join, code, now + FleetJoin.CODE_LIFETIME_MS) == FleetJoin.Outcome.CLOSED,
                "an expired code was taken");

        // A new code replaces the open one.
        String first = join.open(now);
        String second = join.open(now + 1);
        if (!first.equals(second)) {
            require(tryCode(join, first, now + 2) == FleetJoin.Outcome.WRONG,
                    "a replaced code still counted");
        }
        join.close();
        require(!join.isOpen(now + 3), "close() left the join open");

        // The confirmation belongs to the caller that ran the exchange, and to one join.
        FleetJoin bound = new FleetJoin(new SecureRandom());
        code = bound.open(now);
        Spake2 adopter = new Spake2(true, code, new SecureRandom());
        FleetJoin.Answer answer = bound.exchange(adopter.share(), CALLER, PANEL, now + 1);
        Spake2.Confirmations mine = adopter.finish(answer.share, CALLER, PANEL);
        require(mine != null && Spake2.matches(mine.panel, answer.confirmation),
                "the adopter did not recognise the panel holding the same code");
        require(bound.confirm(Spake2.hex(mine.adopter), "other", now + 2)
                == FleetJoin.Outcome.WRONG, "another key used the caller's confirmation");
        require(bound.confirm(Spake2.hex(mine.adopter), CALLER, now + 3)
                == FleetJoin.Outcome.WRONG, "a confirmation counted after its exchange ended");
        require(bound.exchange("1", CALLER, PANEL, now + 4).refused == FleetJoin.Outcome.WRONG,
                "a share outside the group was taken");
        require(bound.exchange("zz", CALLER, PANEL, now + 5).refused == FleetJoin.Outcome.WRONG,
                "a malformed share was taken");
        require(bound.exchange(Spake2.P.subtract(java.math.BigInteger.ONE).toString(16), CALLER,
                PANEL, now + 6).refused == FleetJoin.Outcome.WRONG, "p - 1 was taken as a share");

        // A wrong code: the adopter sees the panel's confirmation fail, and the panel never learns
        // the code; relayed to other key hashes, the confirmations do not match either.
        FleetJoin other = new FleetJoin(new SecureRandom());
        code = other.open(now);
        String wrongCode = code.equals("00000000") ? "00000001" : "00000000";
        Spake2 guesser = new Spake2(true, wrongCode, new SecureRandom());
        answer = other.exchange(guesser.share(), CALLER, PANEL, now + 1);
        Spake2.Confirmations guessed = guesser.finish(answer.share, CALLER, PANEL);
        require(!Spake2.matches(guessed.panel, answer.confirmation),
                "a wrong code looked right to the adopter");
        Spake2 relayed = new Spake2(true, code, new SecureRandom());
        answer = other.exchange(relayed.share(), CALLER, PANEL, now + 2);
        require(!Spake2.matches(relayed.finish(answer.share, CALLER, "another panel").panel,
                answer.confirmation), "an exchange held for another panel's key");

        // Tries: five exchanges are told, the sixth locks for ten minutes and ends the code.
        FleetJoin locked = new FleetJoin(new SecureRandom());
        code = locked.open(now);
        String wrong = code.equals("00000000") ? "00000001" : "00000000";
        for (int i = 1; i <= FleetJoin.MAX_WRONG_CODES; i++) {
            require(tryCode(locked, wrong, now + i) == FleetJoin.Outcome.WRONG,
                    "wrong code " + i + " was not just wrong");
            require(locked.wrongCodes() == i, "wrong codes miscounted");
        }
        require(tryCode(locked, wrong, now + 10) == FleetJoin.Outcome.LOCKED,
                "the sixth try did not lock");
        require(tryCode(locked, code, now + 11) == FleetJoin.Outcome.LOCKED,
                "the right code was taken during the lockout");
        require(locked.open(now + 12) == null, "a new code opened during the lockout");
        require(locked.lockedUntil(now + 12) == now + 10 + FleetJoin.LOCKOUT_MS,
                "the lockout does not end ten minutes after the last try");
        require(!locked.isOpen(now + 12), "a locked join admits unknown keys");
        long after = now + 10 + FleetJoin.LOCKOUT_MS;
        require(locked.lockedUntil(after) == 0, "the lockout did not end");
        code = locked.open(after);
        require(code != null, "no new code after the lockout");
        require(locked.wrongCodes() == 0, "the count did not start again");
        require(tryCode(locked, code, after + 1) == FleetJoin.Outcome.ADMITTED,
                "the right code was refused after the lockout");

        // Odd confirmations are wrong, never an exception.
        code = locked.open(after + 2);
        require(locked.confirm(null, CALLER, after + 3) == FleetJoin.Outcome.WRONG,
                "null was not wrong");
        require(locked.confirm("", CALLER, after + 4) == FleetJoin.Outcome.WRONG,
                "empty was not wrong");

        require(FleetJoin.grouped("12345678").equals("1234 5678"), "grouped wrongly");
        require(FleetJoin.qrContent("kiosk-1", "192.0.2.5", 8081, "ab", "12345678").equals(
                "muralis-join:2;id=kiosk-1;addr=192.0.2.5;port=8081;spki=ab;code=12345678"),
                "the QR content is not the design's");

        String key = repeat('a', 64);
        FleetJoin.Invitation read = FleetJoin.parseQr(FleetJoin.qrContent("kiosk-1", "192.0.2.5",
                8081, key, "12345678"));
        require(read != null && read.id.equals("kiosk-1") && read.address.equals("192.0.2.5")
                && read.port == 8081 && read.spki.equals(key) && read.code.equals("12345678"),
                "the design's QR did not read back");
        FleetJoin.Invitation two = FleetJoin.parseQr("muralis-join:2;id=k;addr=192.0.2.5,192.0.2.6;"
                + "port=8081;spki=" + key + ";code=12345678");
        require(two != null && two.address.equals("192.0.2.5"), "the first of two addresses");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=2001:db8::5;port=8081;spki=" + key
                + ";code=12345678") != null, "an IPv6 address was refused");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=999.300.1.1;port=8081;spki=" + key
                + ";code=12345678") == null, "an address past 255 was taken, which DNS would look up");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=255.0.10.250;port=8081;spki=" + key
                + ";code=12345678") != null, "a real IPv4 address was refused");
        require(FleetJoin.parseQr(null) == null, "null read as a code");
        require(FleetJoin.parseQr("https://example.com") == null, "another QR read as a code");
        require(FleetJoin.parseQr("muralis-join:1;id=k;addr=192.0.2.5;port=8081;spki=" + key
                + ";code=12345678") == null, "another version read as a code");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=192.0.2.5;port=8081;spki=" + key)
                == null, "a QR with no code read");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=192.0.2.5;port=70000;spki=" + key
                + ";code=12345678") == null, "port 70000 read");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=192.0.2.5;port=8081;spki=ab;"
                + "code=12345678") == null, "a short key read");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=host.example;port=8081;spki=" + key
                + ";code=12345678") == null, "a host name read as an address");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=192.0.2.5;port=8081;spki=" + key
                + ";code=1234") == null, "a short code read");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=,;port=8081;spki=" + key
                + ";code=12345678") == null, "an empty address list read, or crashed");
        require(FleetJoin.parseQr("muralis-join:2;id=k;addr=cafe.be;port=8081;spki=" + key
                + ";code=12345678") == null, "a host name of hex letters read as an address");

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

    private static final String CALLER = repeat('c', 64);
    private static final String PANEL = repeat('b', 64);

    /** One whole try, as an adopter runs it: the exchange, then the confirmation if it holds. */
    private static FleetJoin.Outcome tryCode(FleetJoin join, String code, long now) {
        Spake2 adopter = new Spake2(true, code, new SecureRandom());
        FleetJoin.Answer answer = join.exchange(adopter.share(), CALLER, PANEL, now);
        if (answer.refused != null) {
            return answer.refused;
        }
        Spake2.Confirmations mine = adopter.finish(answer.share, CALLER, PANEL);
        if (mine == null || !Spake2.matches(mine.panel, answer.confirmation)) {
            return FleetJoin.Outcome.WRONG;
        }
        return join.confirm(Spake2.hex(mine.adopter), CALLER, now);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
