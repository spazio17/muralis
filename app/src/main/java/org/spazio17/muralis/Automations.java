/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The panel's own automations: "when a sensor says this, then do that", the shape Apple Home,
 * Alexa, Google Home, SmartThings and Homey all write an automation in (Juri, 2026-09-27).
 *
 * <p>Pure: no Android imports, so the vocabulary, the storage format and the clock that decides
 * when a rule fires are host-tested. A rule names a sensor, one of that sensor's events, a
 * level and a duration where the event needs them, an action with its one argument, and an
 * optional window of the day. Nothing has to leave the panel for a rule to run; the same rules
 * are published like every other setting so a smart home can read them.
 */
final class Automations {
    private Automations() {
    }

    /** One thing a sensor can be asked about. */
    static final class Event {
        final String id;
        final String label;
        /** The unit of the level the event compares against, or null when it has none. */
        final String levelUnit;
        /** Whether "for N minutes" applies: the condition must hold that long before it fires. */
        final boolean holds;

        Event(String id, String label, String levelUnit, boolean holds) {
            this.id = id;
            this.label = label;
            this.levelUnit = levelUnit;
            this.holds = holds;
        }
    }

    /** What a rule does. */
    static final class Action {
        final String id;
        final String label;
        /** The label of the one text the action needs, or null for none. */
        final String argumentLabel;

        Action(String id, String label, String argumentLabel) {
            this.id = id;
            this.label = label;
            this.argumentLabel = argumentLabel;
        }
    }

    /** The events every sensor offers, by sensor id, in the order a page lists them. */
    static final Map<String, List<Event>> EVENTS = events();

    private static Map<String, List<Event>> events() {
        Map<String, List<Event>> map = new LinkedHashMap<>();
        map.put("proximity", Arrays.asList(
                // Android's own words for the sensor (SensorManager: "near/far"); Home
                // Assistant has no binary class for it, its nearest, occupancy, is about a room.
                new Event("near", "Near", null, false),
                new Event("far", "Far", null, false)));
        map.put("light", Arrays.asList(
                new Event("darker", "Darker than", "lx", true),
                new Event("brighter", "Brighter than", "lx", false)));
        map.put("movement", Arrays.asList(
                new Event("picked_up", "Picked up", null, false),
                new Event("still", "Still again", null, false)));
        map.put("audio", Arrays.asList(
                new Event("playing", "Sound starts playing", null, false),
                new Event("stopped", "Sound stops", null, false)));
        map.put("camera", Arrays.asList(
                new Event("motion", "Motion in front of the panel", null, false),
                // "Nothing moving", not "Nothing moving for": the sentence adds "for 5 min"
                // itself, and read "for for" (review, 2026-10-01).
                new Event("no_motion", "Nothing moving", null, true)));
        map.put("microphone", Collections.singletonList(
                new Event("louder", "Louder than", "%", false)));
        map.put("display", Arrays.asList(
                new Event("on", "Display switched on", null, false),
                new Event("off", "Display switched off", null, false)));
        map.put("screensaver", Arrays.asList(
                new Event("started", "Screensaver started", null, false),
                new Event("ended", "Screensaver ended", null, false)));
        map.put("battery", Arrays.asList(
                new Event("below", "Battery below", "%", false),
                new Event("plugged", "Charger plugged in", null, false),
                new Event("unplugged", "Charger unplugged", null, false)));
        map.put("network", Arrays.asList(
                new Event("connected", "Network connected", null, false),
                new Event("lost", "Network lost", null, false)));
        map.put("processor", Collections.singletonList(
                new Event("hotter", "Hotter than", "°C", false)));
        return Collections.unmodifiableMap(map);
    }

    static final List<Action> ACTIONS = Collections.unmodifiableList(Arrays.asList(
            new Action("display_on", "Display on", null),
            new Action("display_off", "Display off", null),
            new Action("dashboard", "Show the dashboard", null),
            new Action("screensaver", "Start the screensaver", null),
            new Action("play_sound", "Play a sound", "Sound address"),
            new Action("say", "Say a sentence", "Sentence"),
            new Action("reload", "Reload the page", null)));

    static Event event(String sensor, String id) {
        List<Event> list = EVENTS.get(sensor);
        if (list == null) {
            return null;
        }
        for (Event event : list) {
            if (event.id.equals(id)) {
                return event;
            }
        }
        return null;
    }

    static Action action(String id) {
        for (Action action : ACTIONS) {
            if (action.id.equals(id)) {
                return action;
            }
        }
        return null;
    }

    /** One stored automation. Mutable, since the editors build it up field by field. */
    static final class Rule {
        String id = "";
        String name = "";
        String sensor = "";
        String event = "";
        /** The level the event compares against, NaN where the event has none. */
        double level = Double.NaN;
        /** Minutes the condition must hold, 0 for at once. */
        int minutes;
        String action = "";
        String argument = "";
        boolean enabled = true;
        /** Minutes of the day the rule is confined to; -1 for the whole day. */
        int onlyFrom = -1;
        int onlyTo = -1;

        Rule copy() {
            Rule other = new Rule();
            other.id = id;
            other.name = name;
            other.sensor = sensor;
            other.event = event;
            other.level = level;
            other.minutes = minutes;
            other.action = action;
            other.argument = argument;
            other.enabled = enabled;
            other.onlyFrom = onlyFrom;
            other.onlyTo = onlyTo;
            return other;
        }

        boolean windowed() {
            return onlyFrom >= 0 && onlyTo >= 0;
        }

        /**
         * Whether the other rule waits for the same thing: the sensor, its event, the level
         * and the minutes. A rule whose condition changed starts over in the engine, so an edit
         * that makes it true at once does not fire at once (review, 2026-10-01).
         */
        boolean sameCondition(Rule other) {
            return sensor.equals(other.sensor) && event.equals(other.event)
                    && (Double.isNaN(level) ? Double.isNaN(other.level) : level == other.level)
                    && minutes == other.minutes;
        }
    }

    /** The shape of a rule's id: newId's alphabet, the shipped "wake" and the old six-letter ids. */
    static final String ID_SHAPE = "[a-z0-9]{1,24}";

    static final int MAX_RULES = 64;
    static final int MAX_NAME = 60;
    static final int MAX_ARGUMENT = 500;
    static final int MAX_MINUTES = 24 * 60;

    /** The one automation that ships, switched on, so the panel is not empty (Juri, 2026-09-27). */
    static List<Rule> defaults() {
        Rule wake = new Rule();
        wake.id = "wake";
        wake.name = "Wake by hand";
        wake.sensor = "proximity";
        wake.event = "near";
        wake.action = "display_on";
        List<Rule> list = new ArrayList<>();
        list.add(wake);
        return list;
    }

    /** Reads the stored list; null (nothing stored yet) is the shipped default, malformed is empty. */
    static List<Rule> parse(String json) {
        if (json == null) {
            return defaults();
        }
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length() && rules.size() < MAX_RULES; index++) {
                JSONObject one = array.optJSONObject(index);
                if (one == null) {
                    continue;
                }
                Rule rule = new Rule();
                rule.id = one.optString("id", "");
                rule.name = one.optString("name", "");
                rule.sensor = one.optString("sensor", "");
                rule.event = one.optString("event", "");
                rule.level = one.has("level") ? one.optDouble("level", Double.NaN) : Double.NaN;
                rule.minutes = one.optInt("minutes", 0);
                rule.action = one.optString("action", "");
                rule.argument = one.optString("argument", "");
                rule.enabled = one.optBoolean("enabled", true);
                rule.onlyFrom = one.optInt("only_from", -1);
                rule.onlyTo = one.optInt("only_to", -1);
                if (!rule.id.isEmpty()) {
                    rules.add(rule);
                }
            }
        } catch (JSONException malformed) {
            return new ArrayList<>();
        }
        return rules;
    }

    static String store(List<Rule> rules) {
        return toJson(rules, false).toString();
    }

    /** The list as the status document carries it: every field plus the sentence. */
    static JSONArray toJson(List<Rule> rules, boolean withSentence) {
        JSONArray array = new JSONArray();
        try {
            for (Rule rule : rules) {
                JSONObject one = new JSONObject();
                one.put("id", rule.id);
                one.put("name", rule.name);
                one.put("sensor", rule.sensor);
                one.put("event", rule.event);
                if (!Double.isNaN(rule.level)) {
                    one.put("level", rule.level);
                }
                one.put("minutes", rule.minutes);
                one.put("action", rule.action);
                one.put("argument", rule.argument);
                one.put("enabled", rule.enabled);
                one.put("only_from", rule.onlyFrom);
                one.put("only_to", rule.onlyTo);
                if (withSentence) {
                    one.put("sentence", sentence(rule));
                }
                array.put(one);
            }
        } catch (JSONException impossible) {
            // Keys are constants and values are primitives.
        }
        return array;
    }

    /** Why a rule cannot be stored, or null. Also fills the name from the sentence when blank. */
    static String validate(Rule rule, Map<String, String> sensorNames) {
        // The id is written into the pages' ids and links, the discovery templates and a
        // comma-joined list, so only newId's shape passes (review, 2026-10-01).
        if (!rule.id.isEmpty() && !rule.id.matches(ID_SHAPE)) {
            return "the id may only use lowercase letters and digits, 24 at most";
        }
        if (rule.sensor.isEmpty() || !sensorNames.containsKey(rule.sensor)) {
            return "choose a sensor";
        }
        Event event = event(rule.sensor, rule.event);
        if (event == null) {
            return "choose what the sensor should notice";
        }
        if (event.levelUnit != null && (Double.isNaN(rule.level) || rule.level < 0
                || rule.level > 1_000_000)) {
            return "the level must be a number";
        }
        if (event.levelUnit == null) {
            rule.level = Double.NaN;
        }
        if (!event.holds) {
            rule.minutes = 0;
        } else if (rule.minutes < 0 || rule.minutes > MAX_MINUTES) {
            return "the minutes must be between 0 and " + MAX_MINUTES;
        }
        Action action = action(rule.action);
        if (action == null) {
            return "choose what to do";
        }
        if (action.argumentLabel == null) {
            rule.argument = "";
        } else {
            // Control characters out, as from the name: they say nothing in a sentence or an
            // address, and each one costs six bytes in the JSON document (review, 2026-10-01).
            rule.argument = rule.argument.replaceAll("\\p{Cntrl}", " ").trim();
            if (rule.argument.isEmpty()) {
                return "the " + action.argumentLabel.toLowerCase(java.util.Locale.ROOT)
                        + " is missing";
            }
            if (rule.argument.length() > MAX_ARGUMENT) {
                return "the " + action.argumentLabel.toLowerCase(java.util.Locale.ROOT)
                        + " is too long";
            }
            if (action.id.equals("play_sound") && !rule.argument.startsWith("http://")
                    && !rule.argument.startsWith("https://")) {
                return "the sound address must start with http:// or https://";
            }
        }
        if (rule.windowed() && (rule.onlyFrom > 24 * 60 || rule.onlyTo > 24 * 60)) {
            return "the time window is not a time of day";
        }
        if (rule.windowed() && rule.onlyFrom == rule.onlyTo) {
            return "the time window ends when it starts";
        }
        if (!rule.windowed()) {
            rule.onlyFrom = -1;
            rule.onlyTo = -1;
        }
        // Control characters out of the name, which is logged on every run: an embedded
        // newline would forge a log line.
        rule.name = rule.name.replaceAll("\\p{Cntrl}", " ").trim();
        if (rule.name.isEmpty()) {
            rule.name = sentence(rule, sensorNames);
        }
        if (rule.name.codePointCount(0, rule.name.length()) > MAX_NAME) {
            return "the name is too long, " + MAX_NAME + " characters at most";
        }
        return null;
    }

    /** "Near, then Display on": the row under a rule's name. */
    static String sentence(Rule rule) {
        return sentence(rule, Collections.<String, String>emptyMap());
    }

    static String sentence(Rule rule, Map<String, String> sensorNames) {
        Event event = event(rule.sensor, rule.event);
        Action action = action(rule.action);
        StringBuilder text = new StringBuilder();
        if (event == null) {
            text.append(sensorNames.containsKey(rule.sensor) ? sensorNames.get(rule.sensor)
                    : rule.sensor);
        } else {
            text.append(event.label);
            if (event.levelUnit != null && !Double.isNaN(rule.level)) {
                text.append(' ').append(number(rule.level)).append(' ').append(event.levelUnit);
            }
            if (event.holds && rule.minutes > 0) {
                text.append(" for ").append(rule.minutes).append(" min");
            }
        }
        text.append(", then ");
        text.append(action == null ? rule.action : action.label);
        if (action != null && action.argumentLabel != null && !rule.argument.isEmpty()) {
            text.append(" \"").append(shortened(rule.argument, 40)).append('"');
        }
        return text.toString();
    }

    /** The first {@code max} characters as a person counts them, so an emoji is never split. */
    static String shortened(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max)) + "…";
    }

    /**
     * The stored rule as the editors' stale-form baseline: everything but the switch, which is
     * flipped beside an open editor on any surface and must not block its Save (review,
     * 2026-10-01).
     */
    static String baseline(Rule stored) {
        Rule copy = stored.copy();
        copy.enabled = true;
        return toJson(Collections.singletonList(copy), false).toString();
    }

    static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** "07:00" for minutes of the day. */
    static String clock(int minutesOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutesOfDay / 60,
                minutesOfDay % 60);
    }

    /** Minutes of the day from "07:00", or -1 for anything else. */
    static int minutesOf(String clock) {
        if (clock == null) {
            return -1;
        }
        String[] parts = clock.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            if (hours < 0 || hours > 24 || minutes < 0 || minutes > 59) {
                return -1;
            }
            return hours * 60 + minutes;
        } catch (NumberFormatException notATime) {
            return -1;
        }
    }

    /** Whether {@code minuteOfDay} falls in the rule's window, wrapping past midnight. */
    static boolean inWindow(Rule rule, int minuteOfDay) {
        if (!rule.windowed()) {
            return true;
        }
        if (rule.onlyFrom <= rule.onlyTo) {
            return minuteOfDay >= rule.onlyFrom && minuteOfDay < rule.onlyTo;
        }
        return minuteOfDay >= rule.onlyFrom || minuteOfDay < rule.onlyTo;
    }

    /** What a sensor reports at one moment, in the three shapes an event can ask about. */
    static final class Sample {
        final double number;
        final Boolean flag;
        /** A second figure for sensors that carry two: the processor's temperature. */
        final double extra;

        Sample(double number, Boolean flag, double extra) {
            this.number = number;
            this.flag = flag;
            this.extra = extra;
        }

        static Sample of(boolean flag) {
            return new Sample(Double.NaN, flag, Double.NaN);
        }

        static Sample of(double number) {
            return new Sample(number, null, Double.NaN);
        }

        static Sample of(double number, boolean flag) {
            return new Sample(number, flag, Double.NaN);
        }
    }

    /** Whether the state the event names holds in this sample; null when the sample cannot say. */
    static Boolean holds(Rule rule, Sample sample) {
        Event event = event(rule.sensor, rule.event);
        if (event == null) {
            return null;
        }
        boolean flag = Boolean.TRUE.equals(sample.flag);
        boolean hasFlag = sample.flag != null;
        boolean hasNumber = !Double.isNaN(sample.number);
        switch (rule.event) {
            case "near": case "picked_up": case "playing": case "motion": case "on":
            case "started": case "plugged": case "connected":
                return hasFlag ? flag : null;
            case "far": case "still": case "stopped": case "off": case "ended":
            case "unplugged": case "lost": case "no_motion":
                return hasFlag ? !flag : null;
            case "darker": case "below":
                return hasNumber ? sample.number < rule.level : null;
            case "brighter": case "louder":
                return hasNumber ? sample.number > rule.level : null;
            case "in_reach":
                return hasNumber ? sample.number > 0 : null;
            case "out_of_reach":
                return hasNumber ? sample.number == 0 : null;
            case "hotter":
                return Double.isNaN(sample.extra) ? null : sample.extra > rule.level;
            default:
                return null;
        }
    }

    interface Runner {
        void run(Rule rule);
    }

    /**
     * The clock: fed every reading, fires a rule once when its condition becomes true (after its
     * minutes, where it has them) and arms it again when the condition ends. The first reading
     * after the rules are loaded only seeds the state, so a rule made while its condition
     * already holds does not fire on the spot.
     */
    static final class Engine {
        private static final class State {
            boolean seeded;
            boolean was;
            boolean fired;
            long trueSinceMs;
        }

        private final Runner runner;
        private List<Rule> rules = new ArrayList<>();
        private final Map<String, State> states = new HashMap<>();

        Engine(Runner runner) {
            this.runner = runner;
        }

        synchronized void rules(List<Rule> fresh) {
            // A rule whose condition changed is seeded again on its next sample, as a new rule
            // is: "darker than 5 lx" edited to 50 lx in a 20 lx room fired the moment it was
            // saved (review, 2026-10-01).
            for (Rule rule : fresh) {
                Rule before = find(rules, rule.id);
                if (before != null && !before.sameCondition(rule)) {
                    states.remove(rule.id);
                }
            }
            rules = new ArrayList<>(fresh);
            states.keySet().retainAll(ids(fresh));
        }

        /**
         * Seeds the rules of {@code sensor} that have seen nothing yet with the state as it
         * stands, and fires nothing: for a sensor fed on its events, so a new or edited rule,
         * or any rule after a start, fires on the first event that changes the state rather
         * than spending that event on its seed (review, 2026-10-01).
         */
        synchronized void seed(String sensor, Sample sample, long nowMs) {
            for (Rule rule : rules) {
                if (!rule.sensor.equals(sensor) || states.containsKey(rule.id)
                        && states.get(rule.id).seeded) {
                    continue;
                }
                Boolean holds = Automations.holds(rule, sample);
                if (holds == null) {
                    continue;
                }
                State state = new State();
                state.seeded = true;
                state.was = holds;
                state.fired = holds;
                state.trueSinceMs = nowMs;
                states.put(rule.id, state);
            }
        }

        /**
         * The clock's tick without a reading: advances "for N minutes" for every rule whose
         * condition held at its last sample, so a steady room, which sends no light event,
         * still fires "darker for 10 min". Nothing else changes; a reading comes from the
         * sensor's own callback, once, so a tick cannot hand the engine an older reading than
         * the callback did and fire a rule twice (review, 2026-10-01).
         */
        void tick(long nowMs, int minuteOfDay) {
            for (Rule rule : due(nowMs, minuteOfDay)) {
                runner.run(rule);
            }
        }

        private synchronized List<Rule> due(long nowMs, int minuteOfDay) {
            List<Rule> due = new ArrayList<>();
            for (Rule rule : rules) {
                State state = states.get(rule.id);
                if (state == null || !state.seeded || !state.was || state.fired
                        || rule.minutes <= 0
                        || nowMs - state.trueSinceMs < rule.minutes * 60_000L) {
                    continue;
                }
                state.fired = true;
                if (rule.enabled && inWindow(rule, minuteOfDay)) {
                    due.add(rule);
                }
            }
            return due;
        }

        private static List<String> ids(List<Rule> list) {
            List<String> ids = new ArrayList<>();
            for (Rule rule : list) {
                ids.add(rule.id);
            }
            return ids;
        }

        synchronized List<Rule> rules() {
            return Collections.unmodifiableList(rules);
        }

        /** Forgets what a sensor's rules have seen: the sensor went off, so they start over. */
        synchronized void reset(String sensor) {
            for (Rule rule : rules) {
                if (rule.sensor.equals(sensor)) {
                    states.remove(rule.id);
                }
            }
        }

        /**
         * A reading from {@code sensor}; runs whatever it makes true. Decided under the lock,
         * run outside it: an action that takes its time (the speech engine starting) must not
         * hold up the sensor callbacks behind the engine.
         */
        void sample(String sensor, Sample sample, long nowMs, int minuteOfDay) {
            for (Rule rule : decide(sensor, sample, nowMs, minuteOfDay)) {
                runner.run(rule);
            }
        }

        private synchronized List<Rule> decide(String sensor, Sample sample, long nowMs,
                int minuteOfDay) {
            List<Rule> due = new ArrayList<>();
            for (Rule rule : rules) {
                if (!rule.sensor.equals(sensor)) {
                    continue;
                }
                Boolean holds = Automations.holds(rule, sample);
                if (holds == null) {
                    continue;
                }
                State state = states.get(rule.id);
                if (state == null) {
                    state = new State();
                    states.put(rule.id, state);
                }
                if (!state.seeded) {
                    state.seeded = true;
                    state.was = holds;
                    state.fired = holds;
                    state.trueSinceMs = nowMs;
                    continue;
                }
                if (!holds) {
                    state.was = false;
                    state.fired = false;
                    continue;
                }
                if (!state.was) {
                    state.was = true;
                    state.trueSinceMs = nowMs;
                    state.fired = false;
                }
                if (state.fired) {
                    continue;
                }
                if (rule.minutes > 0 && nowMs - state.trueSinceMs < rule.minutes * 60_000L) {
                    continue;
                }
                state.fired = true;
                if (rule.enabled && inWindow(rule, minuteOfDay)) {
                    due.add(rule);
                }
            }
            return due;
        }
    }

    /**
     * Held by everyone who reads the stored rules, changes them and writes them back: the web
     * save, the delete and a switch flipped from MQTT run on different threads, and two
     * load-modify-store passes at once would lose one of the two changes.
     */
    static final Object STORE = new Object();

    /** A short id for a new rule that no existing rule has. */
    static String newId(List<Rule> existing) {
        java.util.Random random = new java.util.Random();
        while (true) {
            StringBuilder id = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                id.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(random.nextInt(31)));
            }
            boolean taken = false;
            for (Rule rule : existing) {
                if (rule.id.contentEquals(id)) {
                    taken = true;
                }
            }
            if (!taken) {
                return id.toString();
            }
        }
    }

    static Rule find(List<Rule> rules, String id) {
        for (Rule rule : rules) {
            if (rule.id.equals(id)) {
                return rule;
            }
        }
        return null;
    }
}
