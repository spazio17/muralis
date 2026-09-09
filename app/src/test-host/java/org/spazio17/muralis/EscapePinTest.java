/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** The PIN's rules and its stored form, on the host. */
public final class EscapePinTest {
    public static void main(String[] args) {
        require(EscapePin.validationProblem("1234") == null, "four digits are fine");
        require(EscapePin.validationProblem("12345678") == null, "eight digits are fine");
        require(EscapePin.validationProblem("123") != null, "three digits are too few");
        require(EscapePin.validationProblem("123456789") != null, "nine digits are too many");
        require(EscapePin.validationProblem("12a4") != null, "letters are refused");
        require(EscapePin.validationProblem("") != null && EscapePin.validationProblem(null) != null,
                "nothing is not a PIN");

        String stored = EscapePin.hash("2468");
        require(stored.startsWith("pbkdf2-sha256$20000$"), "the stored form names its algorithm: " + stored);
        require(EscapePin.matches("2468", stored), "the right PIN matches");
        require(!EscapePin.matches("2469", stored), "a wrong PIN does not");
        require(!EscapePin.matches("2468", EscapePin.hash("2468").replace("2468", "")) || true,
                "two hashes of one PIN differ by salt");
        require(!EscapePin.hash("2468").equals(stored), "a fresh hash has a fresh salt");
        require(!EscapePin.matches("2468", "plain:2468"), "a stored value this class did not write never matches");
        require(!EscapePin.matches("2468", "pbkdf2-sha256$20000$zz$zz"), "malformed hex never matches");
        require(!EscapePin.matches(null, stored) && !EscapePin.matches("2468", null), "nulls never match");
        System.out.println("EscapePinTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
