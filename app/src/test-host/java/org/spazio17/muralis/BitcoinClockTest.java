/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.HashMap;
import java.util.Map;

/** The Bitcoin clock's options: defaults, reading, checking a form, looks and the screens. */
public final class BitcoinClockTest {

    public static void main(String[] args) {
        BitcoinClock.Options defaults = BitcoinClock.parse(null);
        require(defaults.look.equals("night") && defaults.size.equals("full")
                && defaults.seconds == 30 && defaults.flash && defaults.font.equals("antonio"),
                "the defaults: Night, full width, 30 s");
        require(BitcoinClock.orderOf(defaults.screens).equals(
                "height,price,sats,fee,halving,time,-halving_date,-market_cap,-supply"),
                "the default screens: " + BitcoinClock.orderOf(defaults.screens));

        BitcoinClock.Options round = BitcoinClock.parse(defaults.toJson());
        require(round.toJson().equals(defaults.toJson()), "a round trip changed the options");
        require(BitcoinClock.parse("{not json").toJson().equals(defaults.toJson()),
                "an unreadable value did not read as the defaults");
        BitcoinClock.Options odd = BitcoinClock.parse("{\"seconds\":2,\"font\":\"comic\","
                + "\"text\":\"300,0,0\",\"screens\":[{\"key\":\"fee\",\"on\":true},"
                + "{\"key\":\"weather\",\"on\":true}]}");
        require(odd.seconds == 30 && odd.font.equals("antonio") && odd.text.equals("242,242,242")
                && odd.screens.get(0).key.equals("fee") && odd.screens.get(0).on
                && odd.screens.size() == BitcoinClock.SCREEN_WORDS.length,
                "values out of range were taken: " + odd.toJson());
        require(BitcoinClock.parse("{\"screens\":[{\"key\":\"fee\",\"on\":false}]}").screens
                .get(0).key.equals("height"), "a list with nothing on was taken");

        Map<String, String> form = new HashMap<>();
        form.put("seconds", "4");
        require(BitcoinClock.SECONDS_RULE.equals(BitcoinClock.problem(form)), "4 s taken");
        form.put("seconds", "3601");
        require(BitcoinClock.problem(form) != null, "3601 s taken");
        form.put("seconds", "60");
        form.put("accent", "1,2");
        require(BitcoinClock.problem(form).startsWith("the accent colour"), "a bad colour");
        form.put("accent", "0, 90, 255");
        form.put("screens", "-height,-price");
        require(BitcoinClock.SCREENS_RULE.equals(BitcoinClock.problem(form)), "no screen on");
        form.put("screens", "fee,-height,bogus,price");
        form.put("size", "huge");
        require(BitcoinClock.problem(form).startsWith("size must be"), "a bad size");
        form.put("size", "m");
        form.put("look", "paper");
        require(BitcoinClock.problem(form) == null, "a good form refused");
        form.put("picture", "file:///x\"y.jpg");
        require(BitcoinClock.problem(form) != null, "a picture address with a quote was taken");
        form.remove("picture");

        BitcoinClock.Options next = BitcoinClock.apply(defaults, form);
        require(next.look.equals("paper") && next.background.equals("245,245,240")
                && next.accent.equals("0,90,255") && next.seconds == 60 && next.size.equals("m"),
                "a look then a colour in one save: " + next.toJson());
        require(BitcoinClock.orderOf(next.screens).startsWith("fee,-height,price,-sats"),
                "the order: " + BitcoinClock.orderOf(next.screens));
        require(defaults.look.equals("night"), "apply changed the stored options");
        Map<String, String> unchanged = new HashMap<>();
        unchanged.put("look", "orange");
        unchanged.put("background", defaults.background);
        unchanged.put("text", defaults.text);
        require(BitcoinClock.apply(defaults, unchanged).background.equals("247,147,26"),
                "a look with its old colours sent beside it did not switch the colours");
        require(BitcoinClock.withLook(defaults, "neon") == null, "an unknown look");
        System.out.println("BitcoinClockTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
