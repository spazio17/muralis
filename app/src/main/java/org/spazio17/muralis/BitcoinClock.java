/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bitcoin clock's options: how its full-screen page looks when the dashboard shows the
 * integration (the fourth Dashboard choice, 2026-10-26). A look to start from, the background,
 * the text and accent colours, the digit font, the size and place of the row of cells, a date
 * and time strip, which screens rotate and in what order, how long each stays, and the flash on a
 * new block. Stored per integration as one JSON object; pure, so reading and checking it are
 * host-tested. The page itself is res/raw/bitcoin_clock.html.
 */
final class BitcoinClock {
    /** The integration whose page this is. */
    static final String DEFINITION = "bitcoin";

    /** The screens, key then word, in their default order; the first six are on by default. */
    static final String[][] SCREEN_WORDS = {
            {"height", "Block height"}, {"price", "Price"}, {"sats", "Sats per unit"},
            {"fee", "Fee"}, {"halving", "Halving"}, {"time", "Time"},
            {"halving_date", "Halving date"}, {"market_cap", "Market cap"}, {"supply", "Supply"}};
    private static final int ON_BY_DEFAULT = 6;

    static final String[][] LOOK_WORDS = {
            {"night", "Night"}, {"paper", "Paper"}, {"orange", "Orange"}};
    static final String[][] FONT_WORDS = {
            {"antonio", "Antonio"}, {"oswald", "Oswald"}, {"azeret_mono", "Azeret Mono"}};
    static final String[][] SIZE_WORDS = {
            {"s", "S"}, {"m", "M"}, {"l", "L"}, {"full", "Full"}};
    static final String[][] POSITION_WORDS = {
            {"top", "Top"}, {"middle", "Middle"}, {"bottom", "Bottom"}};
    static final String[][] STRIP_WORDS = {
            {"off", "Off"}, {"top", "Top"}, {"bottom", "Bottom"}};

    /** Each look's background, cells, text and accent, as r,g,b. */
    private static final Map<String, String[]> LOOKS = new LinkedHashMap<>();

    static {
        LOOKS.put("night", new String[] {"17,18,20", "34,35,38", "242,242,242", "247,147,26"});
        LOOKS.put("paper", new String[] {"245,245,240", "255,255,255", "20,20,20", "224,67,0"});
        LOOKS.put("orange", new String[] {"247,147,26", "255,170,70", "20,20,20", "255,255,255"});
    }

    static final int MIN_SECONDS = 5;
    static final int MAX_SECONDS = 3600;
    static final int MAX_DIM = 90;
    static final String SECONDS_RULE = "time per screen must be " + MIN_SECONDS + " to "
            + MAX_SECONDS + " seconds";
    static final String SCREENS_RULE = "at least one screen must be on";

    /** One screen in the rotation: its key and whether it is shown. */
    static final class Screen {
        final String key;
        final boolean on;

        Screen(String key, boolean on) {
            this.key = key;
            this.on = on;
        }
    }

    /** The options as stored; every field is checked, so the page can trust them. */
    static final class Options {
        String look = "night";
        String background = LOOKS.get("night")[0];
        String cells = LOOKS.get("night")[1];
        String text = LOOKS.get("night")[2];
        String accent = LOOKS.get("night")[3];
        /** How dark the background picture is made, 0 to {@link #MAX_DIM} percent. */
        int dim = 50;
        /** An uploaded picture behind the clock, its address; empty for the colour alone. */
        String picture = "";
        String font = "antonio";
        String size = "full";
        String position = "middle";
        String strip = "off";
        int seconds = 30;
        boolean flash = true;
        List<Screen> screens = defaultScreens();

        Options copy() {
            return parse(toJson());
        }

        String toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("look", look);
            out.put("background", background);
            out.put("cells", cells);
            out.put("text", text);
            out.put("accent", accent);
            out.put("dim", dim);
            out.put("picture", picture);
            out.put("font", font);
            out.put("size", size);
            out.put("position", position);
            out.put("strip", strip);
            out.put("seconds", seconds);
            out.put("flash", flash);
            List<Object> list = new ArrayList<>();
            for (Screen screen : screens) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", screen.key);
                one.put("on", screen.on);
                list.add(one);
            }
            out.put("screens", list);
            return TinyJson.write(out);
        }
    }

    private BitcoinClock() {
    }

    static List<Screen> defaultScreens() {
        List<Screen> screens = new ArrayList<>();
        for (int index = 0; index < SCREEN_WORDS.length; index++) {
            screens.add(new Screen(SCREEN_WORDS[index][0], index < ON_BY_DEFAULT));
        }
        return screens;
    }

    /**
     * The stored options, anything missing or unreadable read as its default, so a damaged or
     * older value never leaves the page without a look. Screens this build does not know are
     * dropped and the ones it adds are appended, off.
     */
    static Options parse(String json) {
        Options options = new Options();
        if (json == null || json.trim().isEmpty()) {
            return options;
        }
        Map<String, Object> in;
        try {
            in = TinyJson.object(TinyJson.parse(json));
        } catch (IllegalArgumentException unreadable) {
            return options;
        }
        options.look = word(in.get("look"), LOOK_WORDS, options.look);
        options.background = colour(in.get("background"), options.background);
        options.cells = colour(in.get("cells"), options.cells);
        options.text = colour(in.get("text"), options.text);
        options.accent = colour(in.get("accent"), options.accent);
        options.dim = number(in.get("dim"), 0, MAX_DIM, options.dim);
        options.picture = in.get("picture") instanceof String
                && isPictureAddress((String) in.get("picture")) ? (String) in.get("picture") : "";
        options.font = word(in.get("font"), FONT_WORDS, options.font);
        options.size = word(in.get("size"), SIZE_WORDS, options.size);
        options.position = word(in.get("position"), POSITION_WORDS, options.position);
        options.strip = word(in.get("strip"), STRIP_WORDS, options.strip);
        options.seconds = number(in.get("seconds"), MIN_SECONDS, MAX_SECONDS, options.seconds);
        options.flash = in.get("flash") instanceof Boolean ? (Boolean) in.get("flash")
                : options.flash;
        Object screens = in.get("screens");
        if (screens instanceof List) {
            List<Screen> read = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (Object one : (List<?>) screens) {
                if (!(one instanceof Map)) {
                    continue;
                }
                Object key = ((Map<?, ?>) one).get("key");
                if (key instanceof String && isScreen((String) key) && !seen.contains(key)) {
                    seen.add((String) key);
                    read.add(new Screen((String) key,
                            Boolean.TRUE.equals(((Map<?, ?>) one).get("on"))));
                }
            }
            for (String[] screen : SCREEN_WORDS) {
                if (!seen.contains(screen[0])) {
                    read.add(new Screen(screen[0], false));
                }
            }
            if (anyOn(read)) {
                options.screens = read;
            }
        }
        return options;
    }

    /** A look's colours over the options, the picture kept; null when the look is unknown. */
    static Options withLook(Options options, String look) {
        String[] colours = LOOKS.get(look);
        if (colours == null) {
            return null;
        }
        Options next = options.copy();
        next.look = look;
        next.background = colours[0];
        next.cells = colours[1];
        next.text = colours[2];
        next.accent = colours[3];
        return next;
    }

    /**
     * Why these options cannot be stored, or null when they can: what a person sends from a
     * form, checked before anything is written, with the reason in plain words.
     */
    static String problem(Map<String, String> form) {
        for (String key : new String[] {"background", "cells", "text", "accent"}) {
            String value = form.get(key);
            if (value != null && StatusLight.parseColor(value) == null) {
                return "the " + (key.equals("cells") ? "cell" : key) + " colour must be three"
                        + " numbers from 0 to 255, like 255,0,0";
            }
        }
        String seconds = form.get("seconds");
        if (seconds != null && !inRange(seconds, MIN_SECONDS, MAX_SECONDS)) {
            return SECONDS_RULE;
        }
        String dim = form.get("dim");
        if (dim != null && !inRange(dim, 0, MAX_DIM)) {
            return "dimming must be 0 to " + MAX_DIM + " percent";
        }
        String[][][] tables = {LOOK_WORDS, FONT_WORDS, SIZE_WORDS, POSITION_WORDS, STRIP_WORDS};
        String[] keys = {"look", "font", "size", "position", "strip"};
        for (int index = 0; index < keys.length; index++) {
            String value = form.get(keys[index]);
            if (value != null && keyOf(value, tables[index]) == null) {
                return keys[index] + " must be one of " + keysOf(tables[index]);
            }
        }
        String picture = form.get("picture");
        if (picture != null && !isPictureAddress(picture)) {
            return "the picture must be one of the uploads";
        }
        String order = form.get("screens");
        if (order != null && !anyOn(screensOf(order))) {
            return SCREENS_RULE;
        }
        return null;
    }

    /**
     * The options with a form's fields over them, the form already checked by {@link #problem}.
     * A look sets the four colours first, and colours sent beside it win, so a person can start
     * from a look and change one colour in the same save.
     */
    static Options apply(Options stored, Map<String, String> form) {
        Options next = stored.copy();
        String look = form.get("look");
        if (look != null && !look.equals(stored.look)) {
            next = withLook(next, look);
        }
        // A colour the form sends unchanged leaves the look's colour in place, so a form that
        // always carries its four boxes can still switch the look.
        next.background = changed(form.get("background"), stored.background, next.background);
        next.cells = changed(form.get("cells"), stored.cells, next.cells);
        next.text = changed(form.get("text"), stored.text, next.text);
        next.accent = changed(form.get("accent"), stored.accent, next.accent);
        if (form.containsKey("dim")) {
            next.dim = Integer.parseInt(form.get("dim").trim());
        }
        if (form.containsKey("picture")) {
            next.picture = form.get("picture");
        }
        next.font = form.getOrDefault("font", next.font);
        next.size = form.getOrDefault("size", next.size);
        next.position = form.getOrDefault("position", next.position);
        next.strip = form.getOrDefault("strip", next.strip);
        if (form.containsKey("seconds")) {
            next.seconds = Integer.parseInt(form.get("seconds").trim());
        }
        if (form.containsKey("flash")) {
            next.flash = "1".equals(form.get("flash")) || "true".equals(form.get("flash"));
        }
        if (form.containsKey("screens")) {
            next.screens = screensOf(form.get("screens"));
        }
        return next;
    }

    /**
     * The screens as a form sends them: their keys in order, separated by commas, each with
     * "-" in front when it is off ("height,price,-fee"). Unknown keys are dropped and missing
     * ones appended, off.
     */
    static List<Screen> screensOf(String order) {
        List<Screen> screens = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String part : order.split(",")) {
            String key = part.trim();
            boolean on = !key.startsWith("-");
            key = on ? key : key.substring(1);
            if (isScreen(key) && !seen.contains(key)) {
                seen.add(key);
                screens.add(new Screen(key, on));
            }
        }
        for (String[] screen : SCREEN_WORDS) {
            if (!seen.contains(screen[0])) {
                screens.add(new Screen(screen[0], false));
            }
        }
        return screens;
    }

    /** The form's spelling of the screens, the inverse of {@link #screensOf}. */
    static String orderOf(List<Screen> screens) {
        StringBuilder order = new StringBuilder();
        for (Screen screen : screens) {
            order.append(order.length() == 0 ? "" : ",").append(screen.on ? "" : "-")
                    .append(screen.key);
        }
        return order.toString();
    }

    /** The form's colour when it differs from the stored one, else what the look set. */
    private static String changed(String sent, String stored, String fromLook) {
        if (sent == null) {
            return fromLook;
        }
        String colour = StatusLight.formatColor(StatusLight.parseColor(sent));
        return colour.equals(stored) ? fromLook : colour;
    }

    static String screenName(String key) {
        String name = keyWord(key, SCREEN_WORDS);
        return name == null ? key : name;
    }

    static List<String> lookKeys() {
        return Collections.unmodifiableList(new ArrayList<>(LOOKS.keySet()));
    }

    /** A picture's address as the library keeps it: short, one line, nothing to escape. */
    static boolean isPictureAddress(String address) {
        return address.length() <= 1024 && !address.matches("(?s).*[\\x00-\\x1f\"'<>\\\\].*");
    }

    private static boolean isScreen(String key) {
        return keyWord(key, SCREEN_WORDS) != null;
    }

    private static boolean anyOn(List<Screen> screens) {
        for (Screen screen : screens) {
            if (screen.on) {
                return true;
            }
        }
        return false;
    }

    private static String keyWord(String key, String[][] table) {
        for (String[] row : table) {
            if (row[0].equals(key)) {
                return row[1];
            }
        }
        return null;
    }

    private static String keyOf(String value, String[][] table) {
        return keyWord(value, table) == null ? null : value;
    }

    private static String keysOf(String[][] table) {
        StringBuilder keys = new StringBuilder();
        for (String[] row : table) {
            keys.append(keys.length() == 0 ? "" : ", ").append(row[0]);
        }
        return keys.toString();
    }

    private static String word(Object value, String[][] table, String fallback) {
        return value instanceof String && keyWord((String) value, table) != null
                ? (String) value : fallback;
    }

    private static String colour(Object value, String fallback) {
        Integer rgb = value instanceof String ? StatusLight.parseColor((String) value) : null;
        return rgb == null ? fallback : StatusLight.formatColor(rgb);
    }

    private static int number(Object value, int min, int max, int fallback) {
        if (!(value instanceof Double)) {
            return fallback;
        }
        double number = (Double) value;
        return number >= min && number <= max && number == Math.rint(number)
                ? (int) number : fallback;
    }

    private static boolean inRange(String value, int min, int max) {
        String trimmed = value.trim();
        if (!trimmed.matches("\\d{1,7}")) {
            return false;
        }
        int number = Integer.parseInt(trimmed);
        return number >= min && number <= max;
    }
}
