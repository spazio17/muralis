/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bitcoin clock's options: how its full-screen page looks when the dashboard shows the
 * integration (the fourth Dashboard choice, 2026-10-26). Its style (cells, flip cards, seven
 * segments, nixie tubes or plain numerals, 2026-10-27), the style's four colours, the font, the
 * size and place of the row of cells, where the label goes (the first tile, above or below the
 * row, or nowhere), a date and time strip, which screens rotate and in what
 * order, how long each stays, and the flash on a new block. Stored per integration as one JSON object; pure, so reading and checking it are
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

    static final String[][] STYLE_WORDS = {
            {"cells", "Cells"}, {"flip", "Flip"}, {"digital", "Digital"}, {"nixie", "Nixie"},
            {"minimal", "Minimal"}};
    static final String[][] FONT_WORDS = {
            {"antonio", "Antonio"}, {"oswald", "Oswald"}, {"azeret_mono", "Azeret Mono"}};
    static final String[][] SIZE_WORDS = {
            {"s", "S"}, {"m", "M"}, {"l", "L"}, {"full", "Full"}};
    static final String[][] POSITION_WORDS = {
            {"top", "Top"}, {"middle", "Middle"}, {"bottom", "Bottom"}};
    /** Where the label naming the value goes: its own first tile, above or below the row, or off. */
    static final String[][] LABEL_WORDS = {
            {"tile", "First tile"}, {"top", "Top"}, {"bottom", "Bottom"}, {"off", "Off"}};
    static final String[][] STRIP_WORDS = {
            {"off", "Off"}, {"top", "Top"}, {"bottom", "Bottom"}};

    /**
     * Each style's default background, cells, text and accent, as r,g,b. Every style has the
     * same four colours under its own names ({@link #colourWords}): the cells are a flip card,
     * the unlit segments, a tube's glass or the label; the text is the digits, the lit segments
     * or the glow; the accent is the flash on a new block.
     */
    private static final Map<String, String[]> STYLES = new LinkedHashMap<>();

    static {
        STYLES.put("cells", new String[] {"17,18,20", "34,35,38", "242,242,242", "247,147,26"});
        STYLES.put("flip", new String[] {"17,18,20", "30,30,32", "241,239,232", "247,147,26"});
        STYLES.put("digital", new String[] {"10,10,10", "40,12,10", "255,59,48", "247,147,26"});
        STYLES.put("nixie", new String[] {"6,5,4", "15,11,9", "255,84,24", "255,106,30"});
        STYLES.put("minimal", new String[] {"0,0,0", "154,160,166", "255,255,255", "247,147,26"});
    }

    /**
     * The swatches each colour offers on Clock options, as r,g,b, before its own box: the
     * background, the cells (or cards, unlit segments, glass, label), the digits, the flash (or
     * Nixie's lamp).
     */
    static final String[][] SWATCHES = {
            {"17,18,20", "0,0,0", "245,245,240", "22,34,60", "40,28,20"},
            {"34,35,38", "255,255,255", "40,12,10", "15,11,9", "154,160,166"},
            {"242,242,242", "20,20,20", "255,59,48", "255,84,24", "0,220,120"},
            {"247,147,26", "224,67,0", "0,90,255", "0,200,0", "255,106,30"}};

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
        String style = "cells";
        String background = STYLES.get("cells")[0];
        String cells = STYLES.get("cells")[1];
        String text = STYLES.get("cells")[2];
        String accent = STYLES.get("cells")[3];
        /** How dark the background picture is made, 0 to {@link #MAX_DIM} percent. */
        int dim = 50;
        /** An uploaded picture behind the clock, its address; empty for the colour alone. */
        String picture = "";
        String font = "antonio";
        String size = "full";
        String position = "middle";
        String label = "tile";
        String strip = "off";
        int seconds = 30;
        boolean flash = true;
        List<Screen> screens = defaultScreens();

        Options copy() {
            return parse(toJson());
        }

        String toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("style", style);
            out.put("background", background);
            out.put("cells", cells);
            out.put("text", text);
            out.put("accent", accent);
            out.put("dim", dim);
            out.put("picture", picture);
            out.put("font", font);
            out.put("size", size);
            out.put("position", position);
            out.put("label", label);
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
        options.style = word(in.get("style"), STYLE_WORDS, options.style);
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
        options.label = word(in.get("label"), LABEL_WORDS, options.label);
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

    /**
     * A style with its own default colours over the options, the rest kept; null when the style
     * is unknown. A style change always starts from its defaults (Juri, 2026-10-27).
     */
    static Options withStyle(Options options, String style) {
        String[] colours = STYLES.get(style);
        if (colours == null) {
            return null;
        }
        Options next = options.copy();
        next.style = style;
        next.background = colours[0];
        next.cells = colours[1];
        next.text = colours[2];
        next.accent = colours[3];
        return next;
    }

    /** The names a style gives its four colours: background, cells, text and accent. */
    static String[] colourWords(String style) {
        switch (style) {
            case "flip": return new String[] {"Background", "Cards", "Digits", "Flash"};
            case "digital": return new String[] {"Background", "Unlit", "Lit", "Flash"};
            case "nixie": return new String[] {"Background", "Glass", "Glow", "Lamp"};
            case "minimal": return new String[] {"Background", "Label", "Digits", "Flash"};
            default: return new String[] {"Background", "Cells", "Digits", "Flash"};
        }
    }

    /**
     * Whether the fourth colour shows anywhere: Nixie's is the lamp beside its label, so with the
     * label off it has nothing to light.
     */
    static boolean usesAccent(String style, String label) {
        return !("nixie".equals(style) && "off".equals(label));
    }

    /**
     * Whether the style draws its digits in the chosen font; Flip, Digital and Nixie bring their
     * own, as the real objects do.
     */
    static boolean usesFont(String style) {
        return "cells".equals(style) || "minimal".equals(style);
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
        String[][][] tables = {STYLE_WORDS, FONT_WORDS, SIZE_WORDS, POSITION_WORDS, LABEL_WORDS,
            STRIP_WORDS};
        String[] keys = {"style", "font", "size", "position", "label", "strip"};
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
     * A new style sets its four default colours first, and colours sent changed beside it win,
     * so a person can switch the style and change one colour in the same save.
     */
    static Options apply(Options stored, Map<String, String> form) {
        Options next = stored.copy();
        String style = form.get("style");
        if (style != null && !style.equals(stored.style)) {
            next = withStyle(next, style);
        }
        // A colour the form sends unchanged leaves the style's colour in place, so a form that
        // always carries its four boxes can still switch the style.
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
        next.label = form.getOrDefault("label", next.label);
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

    /** The form's colour when it differs from the stored one, else what the style set. */
    private static String changed(String sent, String stored, String fromStyle) {
        if (sent == null) {
            return fromStyle;
        }
        String colour = StatusLight.formatColor(StatusLight.parseColor(sent));
        return colour.equals(stored) ? fromStyle : colour;
    }

    static String screenName(String key) {
        String name = keyWord(key, SCREEN_WORDS);
        return name == null ? key : name;
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
