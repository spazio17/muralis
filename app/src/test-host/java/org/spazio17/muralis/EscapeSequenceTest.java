/*
 * Copyright 2026 Muralis contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EscapeSequenceTest {
    public static void main(String[] args) {
        testParsing();
        testValidation();
        testDescribe();
        testMatching();
        testTrimming();
        System.out.println("EscapeSequenceTest passed");
    }

    private static void testParsing() {
        List<String> parsed = EscapeSequence.parse("BL,BL,BR");
        require(parsed.equals(Arrays.asList("BL", "BL", "BR")), "simple parse failed: " + parsed);
        require(EscapeSequence.parse(" bl , br ").equals(Arrays.asList("BL", "BR")),
                "parse should tolerate spacing and case");
        require(EscapeSequence.parse("BL,XX,BR").isEmpty(),
                "an unknown zone must invalidate the whole sequence, not be skipped");
        require(EscapeSequence.parse(null).isEmpty(), "null must not throw");
        require(EscapeSequence.parse("").isEmpty(), "empty must parse to empty");

        String formatted = EscapeSequence.format(Arrays.asList("BL", "TR"));
        require(formatted.equals("BL,TR"), "format wrong: " + formatted);
        require(EscapeSequence.parse(formatted).equals(Arrays.asList("BL", "TR")),
                "format/parse round trip failed");
    }

    private static void testValidation() {
        require(!EscapeSequence.isValid(Arrays.asList("BL", "BR")),
                "two taps is short enough to happen by accident and must be rejected");
        require(EscapeSequence.isValid(Arrays.asList("BL", "BL", "BR")),
                "three taps should be allowed");
        List<String> tooLong = new ArrayList<>();
        for (int i = 0; i < EscapeSequence.MAX_LENGTH + 1; i++) {
            tooLong.add("BL");
        }
        require(!EscapeSequence.isValid(tooLong), "over-long sequence should be rejected");
        require(!EscapeSequence.isValid(Arrays.asList("BL", "NOPE", "BR")),
                "unknown zone should be rejected");
    }

    private static void testDescribe() {
        String described = EscapeSequence.describe(Arrays.asList("BL", "BL", "BR", "BL"));
        require(described.equals("bottom-left x2, bottom-right, bottom-left"),
                "describe wrong: " + described);
        require(EscapeSequence.describe(new ArrayList<>()).equals("not set"),
                "empty sequence should describe itself as unset");
    }

    private static void testMatching() {
        List<String> sequence = Arrays.asList("BL", "BL", "BR");

        // Performed cleanly, one second apart.
        require(EscapeSequence.matchesTail(taps("BL:0", "BL:1000", "BR:2000"), sequence,
                EscapeSequence.MAX_GAP_MS), "clean sequence did not match");

        // Wrong order must not match.
        require(!EscapeSequence.matchesTail(taps("BL:0", "BR:1000", "BL:2000"), sequence,
                EscapeSequence.MAX_GAP_MS), "wrong order matched");

        // A long pause in the middle means the user stopped; that is not the sequence.
        require(!EscapeSequence.matchesTail(taps("BL:0", "BL:1000", "BR:9000"), sequence,
                EscapeSequence.MAX_GAP_MS), "a 8s pause should break the sequence");

        // Fumbling first, then getting it right, must work, the tail is what counts.
        require(EscapeSequence.matchesTail(
                taps("TR:0", "BR:500", "BL:1000", "BL:1500", "BR:2000"), sequence,
                EscapeSequence.MAX_GAP_MS), "tail match after a fumble failed");

        // Not enough taps yet.
        require(!EscapeSequence.matchesTail(taps("BL:0", "BR:500"), sequence,
                EscapeSequence.MAX_GAP_MS), "partial sequence matched");
        require(!EscapeSequence.matchesTail(taps("BL:0"), new ArrayList<>(),
                EscapeSequence.MAX_GAP_MS), "an unset sequence must never match");

        // A single-corner repeat, which is what the old fixed gesture was.
        List<String> nine = new ArrayList<>();
        List<EscapeSequence.Tap> nineTaps = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add("BL");
            nineTaps.add(new EscapeSequence.Tap("BL", i * 300L));
        }
        require(EscapeSequence.matchesTail(nineTaps, nine, EscapeSequence.MAX_GAP_MS),
                "the legacy nine-tap gesture should still match");

        // Time going backwards must not be read as a zero gap.
        require(!EscapeSequence.matchesTail(taps("BL:5000", "BL:4000", "BR:3000"), sequence,
                EscapeSequence.MAX_GAP_MS), "backwards timestamps matched");
    }

    private static void testTrimming() {
        List<EscapeSequence.Tap> taps = taps("BL:0", "BL:1000", "BR:2000");
        // Long after the fact, nothing should survive.
        require(EscapeSequence.trim(taps, 1_000_000L, EscapeSequence.MAX_GAP_MS, 12).isEmpty(),
                "stale taps were not dropped");
        // Recent taps survive.
        require(EscapeSequence.trim(taps, 2_000L, EscapeSequence.MAX_GAP_MS, 12).size() == 3,
                "recent taps were dropped");
        // The buffer is capped even when every tap is recent.
        List<EscapeSequence.Tap> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new EscapeSequence.Tap("BL", 10_000L + i));
        }
        require(EscapeSequence.trim(many, 10_040L, EscapeSequence.MAX_GAP_MS, 12).size() == 12,
                "buffer was not capped");
    }

    /** "ZONE:millis" shorthand, so the tests read like the gesture they describe. */
    private static List<EscapeSequence.Tap> taps(String... spec) {
        List<EscapeSequence.Tap> taps = new ArrayList<>();
        for (String item : spec) {
            String[] parts = item.split(":");
            taps.add(new EscapeSequence.Tap(parts[0], Long.parseLong(parts[1])));
        }
        return taps;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
