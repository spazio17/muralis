/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.HashMap;
import java.util.Map;

/** The Bitcoin clock's options: defaults, reading, checking a form, styles and the screens. */
public final class BitcoinClockTest {

    public static void main(String[] args) {
        BitcoinClock.Options defaults = BitcoinClock.parse(null);
        require(defaults.style.equals("cells") && defaults.size.equals("full")
                && defaults.seconds == 30 && defaults.flash && defaults.font.equals("antonio"),
                "the defaults: Cells, full width, 30 s");
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
        form.put("style", "digital");
        require(BitcoinClock.problem(form) == null, "a good form refused");
        form.put("picture", "file:///x\"y.jpg");
        require(BitcoinClock.problem(form) != null, "a picture address with a quote was taken");
        form.remove("picture");

        BitcoinClock.Options next = BitcoinClock.apply(defaults, form);
        require(next.style.equals("digital") && next.background.equals("10,10,10")
                && next.accent.equals("0,90,255") && next.seconds == 60 && next.size.equals("m"),
                "a style then a colour in one save: " + next.toJson());
        require(BitcoinClock.orderOf(next.screens).startsWith("fee,-height,price,-sats"),
                "the order: " + BitcoinClock.orderOf(next.screens));
        require(defaults.style.equals("cells"), "apply changed the stored options");
        Map<String, String> unchanged = new HashMap<>();
        unchanged.put("style", "nixie");
        unchanged.put("background", defaults.background);
        unchanged.put("text", defaults.text);
        require(BitcoinClock.apply(defaults, unchanged).text.equals("255,84,24"),
                "a style with its old colours sent beside it did not switch the colours");
        require(BitcoinClock.withStyle(defaults, "neon") == null, "an unknown style");
        require(BitcoinClock.parse("{\"look\":\"paper\"}").toJson().equals(defaults.toJson()),
                "a stored look from before the styles did not read as Cells");
        require(!BitcoinClock.usesFont("nixie") && !BitcoinClock.usesFont("flip")
                && BitcoinClock.usesFont("minimal")
                && BitcoinClock.colourWords("digital")[1].equals("Unlit"), "the style words");
        require(defaults.label.equals("tile"), "the label is the first tile by default");
        Map<String, String> label = new HashMap<>();
        label.put("label", "off");
        require(BitcoinClock.problem(label) == null
                && BitcoinClock.apply(defaults, label).label.equals("off"), "the label hidden");
        label.put("label", "left");
        require(BitcoinClock.problem(label).startsWith("label must be"), "a bad label place");
        System.out.println("BitcoinClockTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
