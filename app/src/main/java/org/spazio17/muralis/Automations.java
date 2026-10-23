/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The panel's own automations: "when a sensor says this, then do that", the shape Apple Home,
 * Alexa, Google Home, SmartThings and Homey all write an automation in (2026-09-27).
 *
 * <p>Pure: no Android imports, so the vocabulary, the storage format and the clock that decides
 * when a rule fires are host-tested. A rule has one or more Whens (a sensor, one of that
 * sensor's events, a level and a duration where the event needs them), each with its own
 * actions run in order, a Wait among them pausing the rest; and optional If conditions,
 * a time of day on some days or a state a sensor is in, checked once when a When starts the
 * rule. Nothing has to leave the panel for a rule to run; the same rules are published like
 * every other setting so a smart home can read them.
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
        /** Whether the rule names what it waits for in its match: an NFC tag id, a text. */
        final boolean matches;
        /** Whether it is a one-shot event (a tag read), not a state a reading holds. */
        final boolean oneShot;
        /** Whether it fires on every new value, whatever the value: an integration's Changes. */
        final boolean changes;
        /** The words for the state in an If condition ("Something near"), not the edge. */
        final String stateLabel;

        /** {@code tag}: an NFC tag read, one-shot and matched by its id. */
        Event(String id, String label, String levelUnit, boolean holds, boolean tag) {
            this(id, label, levelUnit, holds, tag, tag, false, label);
        }

        private Event(String id, String label, String levelUnit, boolean holds, boolean matches,
                boolean oneShot, boolean changes, String stateLabel) {
            this.id = id;
            this.label = label;
            this.levelUnit = levelUnit;
            this.holds = holds;
            this.matches = matches;
            this.oneShot = oneShot;
            this.changes = changes;
            this.stateLabel = stateLabel;
        }

        /** A value that becomes the text the rule names: a state, compared on every reading. */
        static Event text(String id, String label) {
            return new Event(id, label, null, true, true, false, false, label);
        }

        /** Any new value, compared with the one before; never a state, so never a condition. */
        static Event changes(String id, String label) {
            return new Event(id, label, null, false, false, false, true, label);
        }

        /** The same event, with its own words as an If state. */
        Event withState(String state) {
            return new Event(id, label, levelUnit, holds, matches, oneShot, changes, state);
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
                // Android's own words for the sensor (SensorManager: "near/far"); MQTT discovery
                // has no binary class for it, its nearest, occupancy, is about a room.
                new Event("near", "Near", null, false, false).withState("Something near"),
                new Event("far", "Far", null, false, false).withState("Nothing near")));
        map.put("illuminance", Arrays.asList(
                new Event("darker", "Darker than", "lx", true, false),
                new Event("brighter", "Brighter than", "lx", false, false)));
        map.put("movement", Arrays.asList(
                new Event("picked_up", "Picked up", null, false, false).withState("Being moved"),
                new Event("still", "Still again", null, false, false).withState("Still")));
        map.put("bluetooth_beacons", Arrays.asList(
                new Event("in_reach", "The first beacon comes in reach", null, false, false)
                        .withState("A beacon in reach"),
                new Event("out_of_reach", "The last beacon goes out of reach", null, false, false)
                        .withState("No beacon in reach")));
        map.put("nfc_tag", Collections.singletonList(
                new Event("tag", "A tag is read", null, false, true)));
        map.put("audio", Arrays.asList(
                new Event("playing", "Sound starts playing", null, false, false)
                        .withState("Sound playing"),
                new Event("stopped", "Sound stops", null, false, false)
                        .withState("No sound playing")));
        map.put("motion", Arrays.asList(
                new Event("motion", "Motion in front of the panel", null, false, false),
                // "Nothing moving", not "Nothing moving for": the sentence adds "for 5 min"
                // itself, and read "for for" (review, 2026-10-01).
                new Event("no_motion", "Nothing moving", null, true, false)));
        map.put("sound_level", Collections.singletonList(
                new Event("louder", "Louder than", "%", false, false)));
        map.put("display", Arrays.asList(
                new Event("on", "Display switched on", null, false, false)
                        .withState("Display on"),
                new Event("off", "Display switched off", null, false, false)
                        .withState("Display off")));
        map.put("ambient", Arrays.asList(
                new Event("started", "Ambient started", null, false, false)
                        .withState("Ambient showing"),
                new Event("ended", "Ambient ended", null, false, false)
                        .withState("Ambient not showing")));
        map.put("battery", Arrays.asList(
                new Event("below", "Battery below", "%", false, false),
                new Event("above", "Battery above", "%", false, false),
                new Event("plugged", "Charger plugged in", null, false, false),
                new Event("unplugged", "Charger unplugged", null, false, false)));
        map.put("network", Arrays.asList(
                new Event("connected", "Network connected", null, false, false),
                new Event("lost", "Network lost", null, false, false).withState("No network")));
        map.put("processor", Collections.singletonList(
                new Event("hotter", "Hotter than", "°C", false, false)));
        // Not a sensor: a camera's event, camera.trigger from a doorbell or a recorder. One-shot
        // and named, as a tag is: the rule waits for one camera or one group, and a camera in a
        // group fires the group's rules too.
        map.put(CAMERAS, Collections.singletonList(
                new Event("triggered", "A camera is triggered", null, false, true)));
        // Not a sensor: the panel's clock. The level is the minute of the day, and
        // the engine's tick fires it, not a reading; see Engine.dueAt.
        map.put(TIME, Collections.singletonList(
                new Event("at", "At", TIME_UNIT, false, false)));
        return Collections.unmodifiableMap(map);
    }

    /** The action that pauses the actions after it. */
    static final String WAIT = "wait";

    static final List<Action> ACTIONS = Collections.unmodifiableList(Arrays.asList(
            new Action("display_on", "Display on", null),
            new Action("display_off", "Display off", null),
            new Action("dashboard", "Open dashboard", null),
            new Action("ambient", "Start Ambient", null),
            new Action("play_sound", "Play a sound", "Sound address"),
            new Action("say", "Say a sentence", "Sentence"),
            new Action("reload", "Reload dashboard", null),
            new Action("light_on", "Status light on", "Colour (r,g,b)"),
            new Action("light_off", "Status light off", null),
            new Action("open_page", "Open a page", "Address"),
            new Action("brightness", "Set brightness", "Percent"),
            new Action("ambient_mode", "Ambient mode", "Mode"),
            new Action("mqtt_publish", "Publish an MQTT message", "Topic and message"),
            new Action("call_url", "Call an address", "Address"),
            new Action("popup", "Show a pop-up", "Message"),
            new Action("camera", "Show a camera", "Camera or group"),
            // A pause before the next action; its length is the step's seconds, not an argument.
            new Action(WAIT, "Wait", null)));

    /** The When choice that is a camera's event, not a sensor. */
    static final String CAMERAS = "cameras";
    static final String CAMERAS_NAME = "A camera";

    /** How "Show a camera" shows it. */
    static final String SHOW_POPUP = "popup";
    static final String SHOW_FULLSCREEN = "fullscreen";
    static final int DEFAULT_COOLDOWN = 60;
    static final int MAX_COOLDOWN = 3600;

    /** Whether an action shows something for some seconds, with or without a chime. */
    static boolean shows(String action) {
        return "popup".equals(action) || "camera".equals(action);
    }

    /** Whether the action is a Wait, which pauses rather than does something. */
    static boolean waits(String action) {
        return WAIT.equals(action);
    }

    /**
     * Whether an event names a state a sensor is in, so an If condition can ask about it:
     * not a one-shot event, not a change, not a time of day (the time condition covers that).
     */
    static boolean conditionEvent(Event event) {
        return !event.oneShot && !event.changes && !TIME_UNIT.equals(event.levelUnit);
    }

    /** Whether a When on the event has a "Not again within". */
    static boolean cools(Event event) {
        return event.oneShot || event.changes;
    }

    /** The "Not again within" a When starts with: none for a Changes, a minute for a tag. */
    static int defaultCooldown(Event event) {
        return event != null && event.changes ? 0 : DEFAULT_COOLDOWN;
    }

    /** The When choice that is the clock, not a sensor, and its "level", a time of day. */
    static final String TIME = "time";
    static final String TIME_NAME = "Time of day";
    static final String TIME_UNIT = "time";

    static Event event(String sensor, String id) {
        List<Event> list = events(sensor);
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

    /**
     * The events a sensor offers: the device's own from {@link #EVENTS}, an integration's value
     * by its type ({@link Integrations#events}); null for neither.
     */
    static List<Event> events(String sensor) {
        List<Event> own = EVENTS.get(sensor);
        if (own != null || !Integrations.isSensorId(sensor)) {
            return own;
        }
        Integrations.ValueSpec spec = Integrations.lookup.valueOf(sensor);
        return spec == null ? null : Integrations.events(spec);
    }

    static Action action(String id) {
        for (Action action : ACTIONS) {
            if (action.id.equals(id)) {
                return action;
            }
        }
        return null;
    }

    /** One action of a When, in its place in the order. Mutable, as the editors build it. */
    static final class Step {
        String action = "";
        String argument = "";
        /** How long a pop-up or a camera shows (0 until touched or closed), or a Wait lasts. */
        int seconds = Popup.DEFAULT_SECONDS;
        /** Whether a pop-up or a camera plays the notification sound once. */
        boolean chime;
        /** "Show a camera" as a pop-up or full screen. */
        String show = SHOW_POPUP;

        Step copy() {
            Step other = new Step();
            other.action = action;
            other.argument = argument;
            other.seconds = seconds;
            other.chime = chime;
            other.show = show;
            return other;
        }
    }

    /** One thing that starts a rule, and the actions it runs then, in order. */
    static final class When {
        String sensor = "";
        String event = "";
        /** The level the event compares against, NaN where the event has none. */
        double level = Double.NaN;
        /** Minutes the condition must hold, 0 for at once. */
        int minutes;
        /**
         * What the When waits for by name: the tag id an NFC When waits for (empty for any
         * tag), the camera or group a camera When waits for, or the text an integration's value
         * must become.
         */
        String match = "";
        /** Seconds a tag, camera or Changes When is not run again after it ran; 0 for always. */
        int cooldown = DEFAULT_COOLDOWN;
        final List<Step> then = new ArrayList<>();

        When copy() {
            When other = new When();
            other.sensor = sensor;
            other.event = event;
            other.level = level;
            other.minutes = minutes;
            other.match = match;
            other.cooldown = cooldown;
            for (Step step : then) {
                other.then.add(step.copy());
            }
            return other;
        }

        /**
         * Whether the other When waits for the same thing: the sensor, its event, the level,
         * the minutes, the match and the "Not again within". A When whose trigger changed starts
         * over in the engine, so an edit that makes it true at once does not fire at once
         * (review, 2026-10-01); its actions may change without that.
         */
        boolean sameTrigger(When other) {
            return sensor.equals(other.sensor) && event.equals(other.event)
                    && (Double.isNaN(level) ? Double.isNaN(other.level) : level == other.level)
                    && minutes == other.minutes && match.equals(other.match)
                    && cooldown == other.cooldown;
        }
    }

    /**
     * One If condition: the time of day and the days of the week when the sensor is
     * {@link #TIME}, else a state of a sensor (an event id that {@link #conditionEvent} admits)
     * with the level or text the state compares against.
     */
    static final class Condition {
        String sensor = "";
        String state = "";
        double level = Double.NaN;
        String match = "";
        /** Minutes of the day of a time condition's window; -1 for the whole day. */
        int from = -1;
        int to = -1;
        /** The days of a time condition, bit 0 Monday to bit 6 Sunday. */
        int days = ALL_DAYS;

        Condition copy() {
            Condition other = new Condition();
            other.sensor = sensor;
            other.state = state;
            other.level = level;
            other.match = match;
            other.from = from;
            other.to = to;
            other.days = days;
            return other;
        }

        boolean timed() {
            return from >= 0 && to >= 0;
        }
    }

    /** One stored automation. Mutable, since the editors build it up field by field. */
    static final class Rule {
        String id = "";
        String name = "";
        boolean enabled = true;
        /**
         * When the rule last changed, in milliseconds since 1970, set by every store (see
         * {@link #stamp}); 0 for a rule stored before it was kept. The fleet of 0.7 merges two
         * copies of a rule by it, the newer winning (2026-09-30).
         */
        long changedAt;
        /** Any one of these starts the rule. */
        final List<When> whens = new ArrayList<>();
        final List<Condition> conditions = new ArrayList<>();
        /** Whether one condition holding is enough; false asks for all of them. */
        boolean anyCondition;

        Rule copy() {
            Rule other = new Rule();
            other.id = id;
            other.name = name;
            other.enabled = enabled;
            other.changedAt = changedAt;
            for (When when : whens) {
                other.whens.add(when.copy());
            }
            for (Condition condition : conditions) {
                other.conditions.add(condition.copy());
            }
            other.anyCondition = anyCondition;
            return other;
        }

        /** Every sensor the rule reads, its Whens' and its conditions', in that order. */
        Set<String> sensors() {
            Set<String> sensors = new LinkedHashSet<>();
            for (When when : whens) {
                sensors.add(when.sensor);
            }
            for (Condition condition : conditions) {
                sensors.add(condition.sensor);
            }
            return sensors;
        }
    }

    /** The rule's first When, added when it has none: the panel's editor draws a new rule so. */
    static When firstWhen(Rule rule) {
        if (rule.whens.isEmpty()) {
            rule.whens.add(new When());
        }
        return rule.whens.get(0);
    }

    /** The When's first action, added when it has none; see {@link #firstWhen}. */
    static Step firstStep(When when) {
        if (when.then.isEmpty()) {
            when.then.add(new Step());
        }
        return when.then.get(0);
    }

    /** The rule's first time condition, or null; see {@link #firstWhen}. */
    static Condition firstTimeCondition(Rule rule) {
        for (Condition condition : rule.conditions) {
            if (TIME.equals(condition.sensor)) {
                return condition;
            }
        }
        return null;
    }

    /**
     * Sets the panel editor's "Between these times" on the first time condition: a window from
     * {@code from} to {@code to}, or, with -1, none. A condition left with neither times nor
     * chosen days says nothing and goes; one with days keeps them.
     */
    static void setWindow(Rule rule, int from, int to) {
        Condition time = firstTimeCondition(rule);
        if (from < 0 && to < 0) {
            if (time != null && time.days == ALL_DAYS) {
                rule.conditions.remove(time);
            } else if (time != null) {
                time.from = -1;
                time.to = -1;
            }
            return;
        }
        if (time == null) {
            time = new Condition();
            time.sensor = TIME;
            rule.conditions.add(time);
        }
        time.from = from;
        time.to = to;
    }

    /**
     * Applies one change of shape the web editor's buttons ask for to a draft, the parts kept
     * as objects so a moved action carries what was typed in it: "add_when", "remove_when:1",
     * "add_action:1", "remove_action:1:2", "up:1:2", "down:1:2", "add_condition",
     * "remove_condition:0" (indexes from 0). Whether the rule changed: an op that names no part,
     * goes past a limit or would leave a When with no action, or a rule with no When, changes
     * nothing.
     */
    static boolean applyOp(Rule rule, String op) {
        String[] parts = op == null ? new String[0] : op.split(":", -1);
        int[] at = new int[parts.length == 0 ? 0 : parts.length - 1];
        try {
            for (int index = 0; index < at.length; index++) {
                at[index] = Integer.parseInt(parts[index + 1]);
            }
        } catch (NumberFormatException notAnIndex) {
            return false;
        }
        String name = parts.length == 0 ? "" : parts[0];
        switch (name + "/" + at.length) {
            case "add_when/0":
                if (rule.whens.size() >= MAX_WHENS || stepCount(rule) >= MAX_STEPS) {
                    return false;
                }
                // With one action, as a When cannot be stored without one.
                When when = new When();
                when.then.add(new Step());
                rule.whens.add(when);
                return true;
            case "remove_when/1":
                if (rule.whens.size() < 2 || !within(rule.whens, at[0])) {
                    return false;
                }
                rule.whens.remove(at[0]);
                return true;
            case "add_action/1":
                if (!within(rule.whens, at[0])
                        || rule.whens.get(at[0]).then.size() >= MAX_STEPS_PER_WHEN
                        || stepCount(rule) >= MAX_STEPS) {
                    return false;
                }
                rule.whens.get(at[0]).then.add(new Step());
                return true;
            case "remove_action/2":
            case "up/2":
            case "down/2": {
                if (!within(rule.whens, at[0]) || !within(rule.whens.get(at[0]).then, at[1])) {
                    return false;
                }
                List<Step> then = rule.whens.get(at[0]).then;
                int step = at[1];
                if (name.equals("remove_action")) {
                    if (then.size() < 2) {
                        return false;
                    }
                    then.remove(step);
                    return true;
                }
                int other = name.equals("up") ? step - 1 : step + 1;
                if (!within(then, other)) {
                    return false;
                }
                Collections.swap(then, step, other);
                return true;
            }
            case "add_condition/0":
                if (rule.conditions.size() >= MAX_CONDITIONS) {
                    return false;
                }
                // A time of day, the first choice of the condition's menu: every day, all day,
                // until times or days are chosen.
                Condition condition = new Condition();
                condition.sensor = TIME;
                rule.conditions.add(condition);
                return true;
            case "remove_condition/1":
                if (!within(rule.conditions, at[0])) {
                    return false;
                }
                rule.conditions.remove(at[0]);
                return true;
            default:
                return false;
        }
    }

    private static boolean within(List<?> list, int index) {
        return index >= 0 && index < list.size();
    }

    /** The actions of every When together, which {@link #MAX_STEPS} bounds. */
    static int stepCount(Rule rule) {
        int steps = 0;
        for (When when : rule.whens) {
            steps += when.then.size();
        }
        return steps;
    }

    /** The shape of a rule's id: newId's alphabet, the shipped "wake" and the old six-letter ids. */
    static final String ID_SHAPE = "[a-z0-9]{1,24}";

    static final int MAX_RULES = 64;
    static final int MAX_NAME = 60;
    static final int MAX_ARGUMENT = 500;
    static final int MAX_MINUTES = 24 * 60;
    static final int MAX_WHENS = 6;
    static final int MAX_STEPS_PER_WHEN = 10;
    static final int MAX_STEPS = 20;
    static final int MAX_CONDITIONS = 6;
    static final int MIN_WAIT = 1;
    static final int MAX_WAIT = 3600;
    /** The stored list's length at most, so the settings and the documents stay bounded. */
    static final int MAX_STORED_CHARS = 200_000;
    /** How late a Wait may end and the rest still run; later, the panel slept through it. */
    static final long LATE_MS = 30_000;
    /** Every day of the week, bit 0 Monday to bit 6 Sunday. */
    static final int ALL_DAYS = 0x7F;

    /** The one automation that ships, switched on, so the panel is not empty (2026-09-27). */
    static List<Rule> defaults() {
        Rule wake = new Rule();
        wake.id = "wake";
        wake.name = "Wake by hand";
        When when = new When();
        when.sensor = "proximity";
        when.event = "near";
        Step step = new Step();
        step.action = "display_on";
        when.then.add(step);
        wake.whens.add(when);
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
                Rule rule = parseRule(one);
                if (!rule.id.isEmpty()) {
                    rules.add(rule);
                }
            }
        } catch (JSONException malformed) {
            return new ArrayList<>();
        }
        return rules;
    }

    /**
     * One rule as stored or posted; an empty id is left for the caller to decide on. A rule in
     * the flat shape of before (one sensor, one action, a window) is read as one When with one
     * action and a time condition every day.
     */
    static Rule parseRule(JSONObject one) {
        Rule rule = new Rule();
        rule.id = one.optString("id", "");
        rule.name = one.optString("name", "");
        rule.enabled = one.optBoolean("enabled", true);
        rule.changedAt = one.optLong("changed_at", 0);
        if (one.has("sensor") && !one.has("when")) {
            parseFlat(one, rule);
            return rule;
        }
        JSONArray whens = one.optJSONArray("when");
        for (int index = 0; whens != null && index < whens.length(); index++) {
            JSONObject entry = whens.optJSONObject(index);
            if (entry != null) {
                rule.whens.add(parseWhen(entry));
            }
        }
        JSONArray conditions = one.optJSONArray("if");
        for (int index = 0; conditions != null && index < conditions.length(); index++) {
            JSONObject entry = conditions.optJSONObject(index);
            if (entry != null) {
                rule.conditions.add(parseCondition(entry));
            }
        }
        rule.anyCondition = one.optBoolean("if_any", false);
        return rule;
    }

    private static void parseFlat(JSONObject one, Rule rule) {
        When when = new When();
        when.sensor = one.optString("sensor", "");
        when.event = one.optString("event", "");
        when.level = one.has("level") ? one.optDouble("level", Double.NaN) : Double.NaN;
        when.minutes = one.optInt("minutes", 0);
        when.match = one.optString("match", "");
        // A rule stored before the field keeps running every time, as it did.
        when.cooldown = one.optInt("cooldown", 0);
        Step step = new Step();
        step.action = one.optString("action", "");
        step.argument = one.optString("argument", "");
        step.seconds = one.optInt("seconds", Popup.DEFAULT_SECONDS);
        step.chime = one.optBoolean("chime", false);
        step.show = one.optString("show", SHOW_POPUP);
        when.then.add(step);
        rule.whens.add(when);
        int from = one.optInt("only_from", -1);
        int to = one.optInt("only_to", -1);
        if (from >= 0 && to >= 0) {
            Condition window = new Condition();
            window.sensor = TIME;
            window.from = from;
            window.to = to;
            rule.conditions.add(window);
        }
    }

    private static When parseWhen(JSONObject one) {
        When when = new When();
        when.sensor = one.optString("sensor", "");
        when.event = one.optString("event", "");
        when.level = one.has("level") ? one.optDouble("level", Double.NaN) : Double.NaN;
        when.minutes = one.optInt("minutes", 0);
        when.match = one.optString("match", "");
        when.cooldown = one.has("cooldown") ? one.optInt("cooldown", 0)
                : defaultCooldown(event(when.sensor, when.event));
        JSONArray steps = one.optJSONArray("then");
        for (int index = 0; steps != null && index < steps.length(); index++) {
            JSONObject entry = steps.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            Step step = new Step();
            step.action = entry.optString("action", "");
            step.argument = entry.optString("argument", "");
            step.seconds = entry.optInt("seconds", Popup.DEFAULT_SECONDS);
            step.chime = entry.optBoolean("chime", false);
            step.show = entry.optString("show", SHOW_POPUP);
            when.then.add(step);
        }
        return when;
    }

    private static Condition parseCondition(JSONObject one) {
        Condition condition = new Condition();
        condition.sensor = one.optString("sensor", "");
        condition.state = one.optString("state", "");
        condition.level = one.has("level") ? one.optDouble("level", Double.NaN) : Double.NaN;
        condition.match = one.optString("match", "");
        condition.from = one.optInt("from", -1);
        condition.to = one.optInt("to", -1);
        JSONArray days = one.optJSONArray("days");
        if (days != null) {
            // ISO weekdays, 1 Monday to 7 Sunday; anything else is left out.
            condition.days = 0;
            for (int index = 0; index < days.length(); index++) {
                int day = days.optInt(index, 0);
                if (day >= 1 && day <= 7) {
                    condition.days |= 1 << (day - 1);
                }
            }
        }
        return condition;
    }

    static String store(List<Rule> rules) {
        return toJson(rules).toString();
    }

    /** The list as stored, posted and given by GET /api/automations; keys only where they apply. */
    static JSONArray toJson(List<Rule> rules) {
        JSONArray array = new JSONArray();
        try {
            for (Rule rule : rules) {
                array.put(toJson(rule));
            }
        } catch (JSONException impossible) {
            // Keys are constants and values are primitives.
        }
        return array;
    }

    private static JSONObject toJson(Rule rule) throws JSONException {
        JSONObject one = new JSONObject();
        one.put("id", rule.id);
        one.put("name", rule.name);
        one.put("enabled", rule.enabled);
        one.put("changed_at", rule.changedAt);
        JSONArray whens = new JSONArray();
        for (When when : rule.whens) {
            JSONObject entry = new JSONObject();
            entry.put("sensor", when.sensor);
            entry.put("event", when.event);
            if (!Double.isNaN(when.level)) {
                entry.put("level", when.level);
            }
            entry.put("minutes", when.minutes);
            entry.put("match", when.match);
            entry.put("cooldown", when.cooldown);
            JSONArray steps = new JSONArray();
            for (Step step : when.then) {
                steps.put(toJson(step));
            }
            entry.put("then", steps);
            whens.put(entry);
        }
        one.put("when", whens);
        JSONArray conditions = new JSONArray();
        for (Condition condition : rule.conditions) {
            conditions.put(toJson(condition));
        }
        one.put("if", conditions);
        one.put("if_any", rule.anyCondition);
        return one;
    }

    private static JSONObject toJson(Step step) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("action", step.action);
        Action action = action(step.action);
        // An action this version does not know keeps what it carried.
        if (action == null ? !step.argument.isEmpty() : action.argumentLabel != null) {
            entry.put("argument", step.argument);
        }
        if (action == null || shows(step.action) || waits(step.action)) {
            entry.put("seconds", step.seconds);
        }
        if (action == null || shows(step.action)) {
            entry.put("chime", step.chime);
        }
        if (action == null || "camera".equals(step.action)) {
            entry.put("show", step.show);
        }
        return entry;
    }

    private static JSONObject toJson(Condition condition) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("sensor", condition.sensor);
        if (TIME.equals(condition.sensor)) {
            if (condition.from >= 0) {
                entry.put("from", condition.from);
            }
            if (condition.to >= 0) {
                entry.put("to", condition.to);
            }
            JSONArray days = new JSONArray();
            for (int day = 1; day <= 7; day++) {
                if ((condition.days & 1 << (day - 1)) != 0) {
                    days.put(day);
                }
            }
            entry.put("days", days);
            return entry;
        }
        entry.put("state", condition.state);
        if (!Double.isNaN(condition.level)) {
            entry.put("level", condition.level);
        }
        if (!condition.match.isEmpty()) {
            entry.put("match", condition.match);
        }
        return entry;
    }

    /**
     * The rules as the status document carries them (MQTT state, /api/stats): only what the
     * switches, the lists and discovery read, so the document stays small however long the
     * rules are. The digest moves with any change but the switch.
     */
    static JSONArray statusJson(List<Rule> rules) {
        JSONArray array = new JSONArray();
        try {
            for (Rule rule : rules) {
                JSONObject one = new JSONObject();
                one.put("id", rule.id);
                one.put("name", rule.name);
                one.put("enabled", rule.enabled);
                one.put("sentence", sentence(rule));
                JSONArray sensors = new JSONArray();
                for (String sensor : rule.sensors()) {
                    sensors.put(sensor);
                }
                one.put("sensors", sensors);
                one.put("digest", Integer.toHexString(baseline(rule).hashCode()));
                array.put(one);
            }
        } catch (JSONException impossible) {
            // Keys are constants and values are primitives.
        }
        return array;
    }

    /**
     * Why a rule cannot be stored, or null. Also tidies it: fields a When, an action or a
     * condition has no use for are reset, and a blank name becomes the sentence.
     */
    static String validate(Rule rule, Map<String, String> sensorNames) {
        // The id is written into the pages' ids and links, the discovery templates and a
        // comma-joined list, so only newId's shape passes (review, 2026-10-01).
        if (!rule.id.isEmpty() && !rule.id.matches(ID_SHAPE)) {
            return "the id may only use lowercase letters and digits, 24 at most";
        }
        if (rule.whens.isEmpty()) {
            return "add a When";
        }
        if (rule.whens.size() > MAX_WHENS) {
            return "at most " + MAX_WHENS + " Whens";
        }
        int steps = 0;
        for (int index = 0; index < rule.whens.size(); index++) {
            When when = rule.whens.get(index);
            // "when 2: ..." only where there are several to tell apart.
            String where = rule.whens.size() > 1 ? "when " + (index + 1) : "";
            String problem = checkWhen(when, sensorNames);
            if (problem != null) {
                return prefixed(where, problem);
            }
            if (when.then.isEmpty()) {
                return prefixed(where, "add an action");
            }
            if (when.then.size() > MAX_STEPS_PER_WHEN) {
                return prefixed(where, "a When has at most " + MAX_STEPS_PER_WHEN + " actions");
            }
            steps += when.then.size();
            if (steps > MAX_STEPS) {
                return "at most " + MAX_STEPS + " actions in one automation";
            }
            for (int step = 0; step < when.then.size(); step++) {
                String place = when.then.size() > 1
                        ? (where.isEmpty() ? "" : where + ", ") + "action " + (step + 1) : where;
                problem = checkStep(when.then.get(step), sensorNames);
                if (problem == null && waits(when.then.get(step).action)
                        && step == when.then.size() - 1) {
                    // A Wait first is a delay before the rest; a Wait last waits for nothing.
                    problem = "a wait needs an action after it";
                }
                if (problem != null) {
                    return prefixed(place, problem);
                }
            }
        }
        if (rule.conditions.size() > MAX_CONDITIONS) {
            return "at most " + MAX_CONDITIONS + " conditions";
        }
        for (int index = 0; index < rule.conditions.size(); index++) {
            String problem = checkCondition(rule.conditions.get(index), sensorNames);
            if (problem != null) {
                return prefixed(rule.conditions.size() > 1 ? "if " + (index + 1) : "",
                        problem);
            }
        }
        // An If on a When's own sensor that contradicts it makes that When dead: it is checked
        // against the very reading that started the When (Engine.decide records it first).
        for (int whenIndex = 0; whenIndex < rule.whens.size(); whenIndex++) {
            for (int index = 0; index < rule.conditions.size(); index++) {
                if (neverTogether(rule.whens.get(whenIndex), rule.conditions.get(index))) {
                    return (rule.conditions.size() > 1 ? "if " + (index + 1) : "the If")
                            + " can never hold when "
                            + (rule.whens.size() > 1 ? "when " + (whenIndex + 1) : "this When")
                            + " starts";
                }
            }
        }
        // Control characters out of the name, which is logged on every run: an embedded
        // newline would forge a log line.
        rule.name = rule.name.replaceAll("\\p{Cntrl}", " ").trim();
        if (rule.name.isEmpty()) {
            // One short of the limit, for the ellipsis a cut sentence ends in.
            rule.name = shortened(sentence(rule, sensorNames), MAX_NAME - 1);
        }
        if (rule.name.codePointCount(0, rule.name.length()) > MAX_NAME) {
            return "the name is too long, " + MAX_NAME + " characters at most";
        }
        return null;
    }

    /**
     * Whether a condition on the When's own sensor can never hold in a reading that starts the
     * When ("When Display switched off, If Display on"). Both are states of one sample, so they
     * are tried together on samples that cover every region the two levels cut the numbers into
     * (each level, either side of it, the middle between them), both flags and the texts named.
     * A When that is not a state (a tag, a camera, a Changes, a time) is never judged.
     */
    static boolean neverTogether(When when, Condition condition) {
        Event event = event(when.sensor, when.event);
        if (!when.sensor.equals(condition.sensor) || event == null || !conditionEvent(event)) {
            return false;
        }
        List<Double> numbers = new ArrayList<>(Arrays.asList(-1.0, 0.0, 1.0));
        for (double level : new double[] {when.level, condition.level}) {
            if (!Double.isNaN(level)) {
                numbers.addAll(Arrays.asList(level - 1, level, level + 1));
            }
        }
        if (!Double.isNaN(when.level) && !Double.isNaN(condition.level)) {
            numbers.add((when.level + condition.level) / 2);
        }
        List<String> texts = Arrays.asList(null, when.match, condition.match, "\u0000");
        for (Boolean flag : new Boolean[] {null, true, false}) {
            for (double number : numbers) {
                for (String text : texts) {
                    Sample sample = new Sample(number, flag, number, text);
                    if (Boolean.TRUE.equals(holds(when, sample))
                            && Boolean.TRUE.equals(holds(condition, sample))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String prefixed(String where, String problem) {
        return where.isEmpty() ? problem : where + ": " + problem;
    }

    /** Why a When cannot be stored, or null; tidies what its event has no use for. */
    private static String checkWhen(When when, Map<String, String> sensorNames) {
        if (when.sensor.isEmpty() || !sensorNames.containsKey(when.sensor)) {
            return "choose a sensor";
        }
        Event event = event(when.sensor, when.event);
        if (event == null) {
            return "choose what the sensor should notice";
        }
        if (TIME_UNIT.equals(event.levelUnit)) {
            if (Double.isNaN(when.level) || when.level < 0 || when.level >= 24 * 60
                    || when.level != Math.rint(when.level)) {
                return "the time must be a time of day, 00:00 to 23:59";
            }
        } else if (event.levelUnit != null && !levelFits(when.sensor, when.level)) {
            return "the level must be a number";
        }
        if (event.levelUnit == null) {
            when.level = Double.NaN;
        }
        if (!event.holds) {
            when.minutes = 0;
        } else if (when.minutes < 0 || when.minutes > MAX_MINUTES) {
            return "the minutes must be between 0 and " + MAX_MINUTES;
        }
        // A tag id as homeAssistantTagId and the chip ids are shaped, 64 characters at most, and
        // a text as long; either is written into the sentence and the pages.
        when.match = when.match.replaceAll("\\p{Cntrl}", "").trim();
        if (when.match.length() > 64) {
            return event.oneShot ? "the tag id is too long" : "the text is too long";
        }
        if (!event.matches) {
            when.match = "";
        }
        if (!cools(event)) {
            when.cooldown = 0;
        } else if (when.cooldown < 0 || when.cooldown > MAX_COOLDOWN) {
            return "\"not again within\" must be 0 to " + MAX_COOLDOWN + " seconds";
        }
        if (CAMERAS.equals(when.sensor) && when.match.isEmpty()) {
            return "choose the camera or group";
        }
        return null;
    }

    /**
     * Whether a level is a number the sensor can report: an integration's may be below zero (a
     * temperature, a price, a signal in dBm); the device's own levels never are.
     */
    private static boolean levelFits(String sensor, double level) {
        return !Double.isNaN(level)
                && level >= (Integrations.isSensorId(sensor) ? -1_000_000 : 0)
                && level <= 1_000_000;
    }

    /** Why an action cannot be stored, or null; tidies what the action has no use for. */
    private static String checkStep(Step step, Map<String, String> sensorNames) {
        Action action = action(step.action);
        if (action == null) {
            return "choose what to do";
        }
        if (waits(action.id)) {
            if (step.seconds < MIN_WAIT || step.seconds > MAX_WAIT) {
                return "the wait must be " + MIN_WAIT + " to " + MAX_WAIT + " seconds";
            }
            step.chime = false;
        } else if (shows(action.id)) {
            if (step.seconds < 0 || step.seconds > Popup.MAX_SECONDS) {
                return "the time must be 0 to " + Popup.MAX_SECONDS + " seconds";
            }
        } else {
            step.seconds = Popup.DEFAULT_SECONDS;
            step.chime = false;
        }
        if (action.id.equals("camera")) {
            if (!SHOW_POPUP.equals(step.show) && !SHOW_FULLSCREEN.equals(step.show)) {
                return "show the camera as a pop-up or full screen";
            }
        } else {
            step.show = SHOW_POPUP;
        }
        if (action.argumentLabel == null) {
            step.argument = "";
            return null;
        }
        // Control characters out, as from the name: they say nothing in a sentence or an
        // address, and each one costs six bytes in the JSON document (review, 2026-10-01).
        step.argument = step.argument.replaceAll("\\p{Cntrl}", " ").trim();
        // Status light on with no colour switches it on in the colour it has.
        if (step.argument.isEmpty() && !action.id.equals("light_on")) {
            return "the " + action.argumentLabel.toLowerCase(java.util.Locale.ROOT)
                    + " is missing";
        }
        if (step.argument.length() > MAX_ARGUMENT) {
            return "the " + action.argumentLabel.toLowerCase(java.util.Locale.ROOT)
                    + " is too long";
        }
        if (action.id.equals("play_sound") && !step.argument.startsWith("http://")
                && !step.argument.startsWith("https://")) {
            return "the sound address must start with http:// or https://";
        }
        String problem = checkArgument(action.id, step.argument);
        if (problem != null) {
            return problem;
        }
        if (action.id.equals("light_on") && !step.argument.isEmpty()) {
            Integer color = StatusLight.parseColor(step.argument);
            if (color == null) {
                return "the " + StatusLight.COLOR_RULE;
            }
            step.argument = StatusLight.formatColor(color);
        }
        return null;
    }

    /** Why a condition cannot be stored, or null; tidies what its kind has no use for. */
    private static String checkCondition(Condition condition, Map<String, String> sensorNames) {
        if (condition.sensor.isEmpty() || !TIME.equals(condition.sensor)
                && !sensorNames.containsKey(condition.sensor)) {
            return "choose a sensor for the condition";
        }
        if (TIME.equals(condition.sensor)) {
            condition.state = "";
            condition.level = Double.NaN;
            condition.match = "";
            if ((condition.from < 0) != (condition.to < 0)) {
                return "the times must be like 07:00";
            }
            if (condition.from < 0) {
                // No times: the days alone.
                condition.from = -1;
                condition.to = -1;
            } else if (condition.from > 24 * 60 || condition.to > 24 * 60) {
                return "the time window is not a time of day";
            } else if (condition.from == condition.to) {
                return "the time window ends when it starts";
            }
            if ((condition.days & ALL_DAYS) == 0) {
                return "choose at least one day";
            }
            condition.days &= ALL_DAYS;
            return null;
        }
        Event event = event(condition.sensor, condition.state);
        if (event == null || !conditionEvent(event)) {
            return "choose the state";
        }
        if (event.levelUnit != null && !levelFits(condition.sensor, condition.level)) {
            return "the level must be a number";
        }
        if (event.levelUnit == null) {
            condition.level = Double.NaN;
        }
        condition.match = condition.match.replaceAll("\\p{Cntrl}", "").trim();
        if (condition.match.length() > 64) {
            return "the text is too long";
        }
        if (!event.matches) {
            condition.match = "";
        }
        condition.from = -1;
        condition.to = -1;
        condition.days = ALL_DAYS;
        return null;
    }

    /**
     * Why the whole list cannot be stored, or null: too many rules, or too long together. Every
     * writer of a changed list asks this before storing it.
     */
    static String checkList(List<Rule> rules) {
        if (rules.size() > MAX_RULES) {
            return "at most " + MAX_RULES + " automations";
        }
        if (store(rules).length() > MAX_STORED_CHARS) {
            return "the automations are too long together: shorten or remove some";
        }
        return null;
    }

    /**
     * The new actions' arguments, checked when the rule is saved so a rule cannot
     * wait all night to fail: null when it can run.
     */
    static String checkArgument(String action, String argument) {
        switch (action) {
            case "open_page":
            case "call_url":
                return argument.startsWith("http://") || argument.startsWith("https://")
                        ? null : "the address must start with http:// or https://";
            case "brightness":
                return argument.matches("\\d{1,3}") && Integer.parseInt(argument) <= 100
                        ? null : "the brightness must be a whole number from 0 to 100";
            case "ambient_mode":
                return ScreensaverPolicy.isMode(argument)
                        ? null : "the mode must be off, dim, film, url or pictures";
            case "mqtt_publish": {
                int space = argument.indexOf(' ');
                String topic = space < 0 ? argument : argument.substring(0, space);
                return topic.isEmpty() || topic.contains("#") || topic.contains("+")
                        || topic.startsWith("$") || space < 0
                        ? "the message must be a topic without wildcards, a space, then the text"
                        : null;
            }
            default:
                return null;
        }
    }

    /** "Near, then Display on": the row under a rule's name. */
    static String sentence(Rule rule) {
        return sentence(rule, Collections.<String, String>emptyMap());
    }

    /**
     * Each When as "trigger, then action" (the first action and how many more), joined by
     * "; ", then the conditions: a single time condition in words, others counted.
     */
    static String sentence(Rule rule, Map<String, String> sensorNames) {
        StringBuilder text = new StringBuilder();
        for (When when : rule.whens) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(sentence(when, sensorNames));
        }
        if (rule.conditions.size() == 1 && TIME.equals(rule.conditions.get(0).sensor)) {
            text.append(", if ").append(timeWords(rule.conditions.get(0)));
        } else if (!rule.conditions.isEmpty()) {
            int count = rule.conditions.size();
            text.append(", if ").append(count)
                    .append(count == 1 ? " condition holds" : " conditions hold");
        }
        return text.toString();
    }

    private static String sentence(When when, Map<String, String> sensorNames) {
        Event event = event(when.sensor, when.event);
        StringBuilder text = new StringBuilder();
        if (event == null) {
            text.append(sensorNames.containsKey(when.sensor) ? sensorNames.get(when.sensor)
                    : when.sensor);
        } else {
            text.append(event.label);
            if (TIME_UNIT.equals(event.levelUnit) && !Double.isNaN(when.level)) {
                text.append(' ').append(clock((int) when.level));
            } else if (event.levelUnit != null && !Double.isNaN(when.level)) {
                text.append(' ').append(number(when.level));
                if (!event.levelUnit.isEmpty()) {
                    text.append(' ').append(event.levelUnit);
                }
            }
            if (event.holds && when.minutes > 0) {
                text.append(" for ").append(when.minutes).append(" min");
            }
            if (event.matches && !when.match.isEmpty()) {
                text.append(": ").append(when.match);
            }
        }
        if (!when.then.isEmpty()) {
            text.append(", then ").append(sentence(when.then.get(0)));
            if (when.then.size() > 1) {
                text.append(" and ").append(when.then.size() - 1).append(" more");
            }
        }
        if (event != null && cools(event) && when.cooldown > 0) {
            text.append(", not again within ").append(when.cooldown).append(" s");
        }
        return text.toString();
    }

    private static String sentence(Step step) {
        Action action = action(step.action);
        if (action == null) {
            return step.action;
        }
        if (waits(action.id)) {
            return "Wait " + step.seconds + " s";
        }
        StringBuilder text = new StringBuilder(action.label);
        if (action.argumentLabel != null && !step.argument.isEmpty()) {
            text.append(" \"").append(shortened(step.argument, 40)).append('"');
        }
        if (shows(action.id)) {
            if (action.id.equals("camera") && SHOW_FULLSCREEN.equals(step.show)) {
                text.append(" full screen");
            }
            text.append(step.seconds == 0 ? " until closed" : " for " + step.seconds + " s");
            if (step.chime) {
                text.append(" with a chime");
            }
        }
        return text.toString();
    }

    private static final String[] DAY_NAMES = {
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    /** "08:00 to 18:00, Monday to Friday", "08:00 to 18:00", "Saturday, Sunday". */
    static String timeWords(Condition condition) {
        if (!condition.timed()) {
            return dayWords(condition.days);
        }
        String window = clock(condition.from) + " to " + clock(condition.to);
        return (condition.days & ALL_DAYS) == ALL_DAYS ? window
                : window + ", " + dayWords(condition.days);
    }

    /** "every day", "Monday to Friday" for three or more in a row, else the days named. */
    static String dayWords(int days) {
        if ((days & ALL_DAYS) == ALL_DAYS) {
            return "every day";
        }
        List<Integer> chosen = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            if ((days & 1 << day) != 0) {
                chosen.add(day);
            }
        }
        if (chosen.isEmpty()) {
            return "no day";
        }
        int first = chosen.get(0);
        int last = chosen.get(chosen.size() - 1);
        if (chosen.size() >= 3 && last - first + 1 == chosen.size()) {
            return DAY_NAMES[first] + " to " + DAY_NAMES[last];
        }
        StringBuilder text = new StringBuilder();
        for (int day : chosen) {
            text.append(text.length() == 0 ? "" : ", ").append(DAY_NAMES[day]);
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
        // The change time moves with the switch too.
        copy.changedAt = 0;
        return toJson(Collections.singletonList(copy)).toString();
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

    /**
     * Whether a time condition holds at {@code minuteOfDay} on {@code isoDay} (1 Monday to 7
     * Sunday). A window wraps past midnight, and its day is the day it started: Friday 23:00 to
     * 06:00 covers Saturday 02:00. Without times only the day counts.
     */
    static boolean inWindow(Condition condition, int minuteOfDay, int isoDay) {
        int day = isoDay;
        if (condition.timed()) {
            if (condition.from <= condition.to) {
                if (minuteOfDay < condition.from || minuteOfDay >= condition.to) {
                    return false;
                }
            } else if (minuteOfDay < condition.to) {
                // After midnight: the window began the day before.
                day = isoDay == 1 ? 7 : isoDay - 1;
            } else if (minuteOfDay < condition.from) {
                return false;
            }
        }
        return (condition.days & 1 << (day - 1)) != 0;
    }

    /** What a sensor reports at one moment, in the three shapes an event can ask about. */
    static final class Sample {
        final double number;
        final Boolean flag;
        /** A second figure for sensors that carry two: the processor's temperature. */
        final double extra;
        /** An integration's text value, or null. */
        final String text;

        Sample(double number, Boolean flag, double extra) {
            this(number, flag, extra, null);
        }

        private Sample(double number, Boolean flag, double extra, String text) {
            this.number = number;
            this.flag = flag;
            this.extra = extra;
            this.text = text;
        }

        static Sample ofText(String text) {
            return new Sample(Double.NaN, null, Double.NaN, text);
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

    /** Whether the state a When's event names holds in this sample; null when it cannot say. */
    static Boolean holds(When when, Sample sample) {
        Event event = event(when.sensor, when.event);
        if (event == null || event.oneShot || event.changes) {
            return null;
        }
        return holds(when.event, when.level, when.match, sample);
    }

    /** Whether a condition's state holds in this sample; null when it cannot say. */
    static Boolean holds(Condition condition, Sample sample) {
        Event event = event(condition.sensor, condition.state);
        if (event == null || !conditionEvent(event)) {
            return null;
        }
        return holds(condition.state, condition.level, condition.match, sample);
    }

    /** Whether the state {@code event} names holds in this sample; null when it cannot say. */
    static Boolean holds(String event, double level, String match, Sample sample) {
        boolean flag = Boolean.TRUE.equals(sample.flag);
        boolean hasFlag = sample.flag != null;
        boolean hasNumber = !Double.isNaN(sample.number);
        switch (event) {
            case "near": case "picked_up": case "playing": case "motion": case "on":
            case "started": case "plugged": case "connected":
                return hasFlag ? flag : null;
            case "far": case "still": case "stopped": case "off": case "ended":
            case "unplugged": case "lost": case "no_motion":
                return hasFlag ? !flag : null;
            case "darker": case "below":
                return hasNumber ? sample.number < level : null;
            case "brighter": case "louder": case "above":
                return hasNumber ? sample.number > level : null;
            case "in_reach":
                return hasNumber ? sample.number > 0 : null;
            case "out_of_reach":
                return hasNumber ? sample.number == 0 : null;
            case "hotter":
                return Double.isNaN(sample.extra) ? null : sample.extra > level;
            // An integration's value (Integrations.events): unavailable is an empty sample, on
            // which nothing decides, so the last available value stands and nothing fires.
            case "int_above":
                return hasNumber ? sample.number > level : null;
            case "int_below":
                return hasNumber ? sample.number < level : null;
            case "int_on":
                return hasFlag ? flag : null;
            case "int_off":
                return hasFlag ? !flag : null;
            case "int_becomes":
                return sample.text == null ? null : sample.text.equalsIgnoreCase(match);
            default:
                return null;
        }
    }

    /** Whether a sample carries a value: an unavailable integration's is empty. */
    private static boolean available(Sample sample) {
        return !Double.isNaN(sample.number) || sample.text != null;
    }

    /** Does one action of a rule; the engine decided when. */
    interface Runner {
        void run(Rule rule, Step step);

        /** Something worth a line in the log about a rule's run. */
        default void note(Rule rule, String why) {
        }
    }

    /** Runs a task after a delay, on the one thread the rules' actions run on. */
    interface Scheduler {
        void after(long delayMs, Runnable task);
    }

    /** The time as the engine asks for it, so a host test can set it. */
    interface Clock {
        /** A monotonic clock in milliseconds that keeps counting while the device sleeps. */
        long nowMs();

        int minuteOfDay();

        /** 1 Monday to 7 Sunday. */
        int isoDay();

        long epochDay();
    }

    /**
     * The clock: fed every reading, starts a rule once when one of its Whens becomes true (after
     * its minutes, where it has them) and arms that When again when the state ends. The first
     * reading after the rules are loaded only seeds the state, so a rule made while its
     * condition already holds does not fire on the spot. A started rule runs its When's actions
     * in order on the scheduler's thread (several Whens due at once each start their list at
     * once, in When order); a Wait schedules the rest of its own list. A rule started again
     * while it runs starts
     * over: the older run stops before its next action, and the actions already done stay done.
     */
    static final class Engine {
        private static final class State {
            boolean seeded;
            boolean was;
            boolean fired;
            long trueSinceMs;
            /** A "Time of day" When: the day it last ran, or would have. */
            long lastDay;
            /** A Changes When: whether it has a value to compare with, and that value. */
            boolean hasLast;
            double lastNumber = Double.NaN;
            String lastText;
        }

        /** A run a pass starts: the rule, the Whens due in it in When order, and its number. */
        private static final class Start {
            final Rule rule;
            final List<Integer> whens = new ArrayList<>();
            final long generation;
            final long atMs;

            Start(Rule rule, long generation, long atMs) {
                this.rule = rule;
                this.generation = generation;
                this.atMs = atMs;
            }
        }

        private final Runner runner;
        private final Scheduler scheduler;
        private final Clock clock;
        private List<Rule> rules = new ArrayList<>();
        /** Per When, keyed by {@link #key}. */
        private final Map<String, State> states = new HashMap<>();
        /** When each cooling When last ran, for its "not again within"; forgotten on a restart. */
        private final Map<String, Long> ranAt = new HashMap<>();
        /** The last reading of each sensor, unavailable ones included, for the conditions. */
        private final Map<String, Sample> lastSamples = new HashMap<>();
        /**
         * The run each rule has going, by rule id: a run asks before every action whether its
         * number is still here, so a restart, an edit, a switch off or a delete stops it before
         * its next action, in a Wait or not.
         */
        private final Map<String, Long> running = new HashMap<>();
        private long generations;

        Engine(Runner runner, Scheduler scheduler, Clock clock) {
            this.runner = runner;
            this.scheduler = scheduler;
            this.clock = clock;
        }

        private static String key(Rule rule, int index) {
            return rule.id + "#" + index;
        }

        synchronized void rules(List<Rule> fresh) {
            // A When whose trigger changed is seeded again on its next sample, as a new one is:
            // "darker than 5 lx" edited to 50 lx in a 20 lx room fired the moment it was saved
            // (review, 2026-10-01). Whens are matched by their place in the list.
            for (Rule rule : fresh) {
                Rule before = find(rules, rule.id);
                if (before == null) {
                    continue;
                }
                int most = Math.max(before.whens.size(), rule.whens.size());
                for (int index = 0; index < most; index++) {
                    if (index >= before.whens.size() || index >= rule.whens.size()
                            || !rule.whens.get(index).sameTrigger(before.whens.get(index))) {
                        states.remove(key(rule, index));
                        ranAt.remove(key(rule, index));
                    }
                }
                // A run going stops before its next action when the rule changed or was
                // switched off.
                if (!rule.enabled || !baseline(before).equals(baseline(rule))) {
                    running.remove(rule.id);
                }
            }
            Set<String> keys = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (Rule rule : fresh) {
                ids.add(rule.id);
                for (int index = 0; index < rule.whens.size(); index++) {
                    keys.add(key(rule, index));
                }
            }
            states.keySet().retainAll(keys);
            ranAt.keySet().retainAll(keys);
            running.keySet().retainAll(ids);
            rules = new ArrayList<>(fresh);
        }

        synchronized List<Rule> rules() {
            return Collections.unmodifiableList(rules);
        }

        /**
         * Seeds the Whens of {@code sensor} that have seen nothing yet with the state as it
         * stands, and fires nothing: for a sensor fed on its events, so a new or edited rule,
         * or any rule after a start, fires on the first event that changes the state rather
         * than spending that event on its seed (review, 2026-10-01).
         */
        synchronized void seed(String sensor, Sample sample) {
            lastSamples.put(sensor, sample);
            long now = clock.nowMs();
            for (Rule rule : rules) {
                for (int index = 0; index < rule.whens.size(); index++) {
                    When when = rule.whens.get(index);
                    Event event = Automations.event(when.sensor, when.event);
                    if (!when.sensor.equals(sensor) || event == null || event.oneShot) {
                        continue;
                    }
                    String key = key(rule, index);
                    State state = states.get(key);
                    if (event.changes) {
                        if ((state == null || !state.hasLast) && available(sample)) {
                            state = state == null ? new State() : state;
                            remember(state, sample);
                            states.put(key, state);
                        }
                        continue;
                    }
                    if (state != null && state.seeded) {
                        continue;
                    }
                    Boolean holds = Automations.holds(when, sample);
                    if (holds == null) {
                        continue;
                    }
                    state = new State();
                    state.seeded = true;
                    state.was = holds;
                    state.fired = holds;
                    state.trueSinceMs = now;
                    states.put(key, state);
                }
            }
        }

        /** The last reading of a sensor that feeds no When, only conditions: the display. */
        synchronized void note(String sensor, Sample sample) {
            lastSamples.put(sensor, sample);
        }

        /**
         * The clock's tick without a reading: advances "for N minutes" for every When whose
         * state held at its last sample, so a steady room, which sends no light event, still
         * fires "darker for 10 min"; and runs a "Time of day" When at its minute. A reading
         * comes from the sensor's own callback, once, so a tick cannot hand the engine an older
         * reading than the callback did and fire a rule twice (review, 2026-10-01).
         */
        void tick() {
            for (Start start : due()) {
                launch(start);
            }
        }

        /** How late a tick may see a time When's minute and still run it; the tick is 2 s. */
        static final int LATE_MINUTES = 2;

        private synchronized List<Start> due() {
            long now = clock.nowMs();
            Map<String, Start> due = new LinkedHashMap<>();
            for (Rule rule : rules) {
                for (int index = 0; index < rule.whens.size(); index++) {
                    When when = rule.whens.get(index);
                    boolean ready = TIME.equals(when.sensor) ? dueAt(key(rule, index), when)
                            : heldLongEnough(states.get(key(rule, index)), when, now);
                    if (ready && allowed(rule)) {
                        join(due, rule, index, now);
                    }
                }
            }
            return new ArrayList<>(due.values());
        }

        private static boolean heldLongEnough(State state, When when, long now) {
            if (state == null || !state.seeded || !state.was || state.fired
                    || when.minutes <= 0 || now - state.trueSinceMs < when.minutes * 60_000L) {
                return false;
            }
            state.fired = true;
            return true;
        }

        /**
         * A "Time of day" When fires once on a day when the minute of the day has reached its
         * time, a tick up to {@link #LATE_MINUTES} late included; a later one means the panel
         * slept or was stopped through the time, and that day's run is dropped rather than
         * replayed at wake, so a panel never fires a pile of missed rules at once (2026-10-15).
         * The first tick after a start seeds it as already run today when its time has passed,
         * so a restart never repeats a morning's run and a rule made at noon for 07:00 waits
         * for tomorrow; a day number that went back (the clock set right) seeds it again, so
         * the same day never runs twice.
         */
        private boolean dueAt(String key, When when) {
            if (Double.isNaN(when.level)) {
                return false;
            }
            long epochDay = clock.epochDay();
            int minuteOfDay = clock.minuteOfDay();
            int at = (int) when.level;
            State state = states.get(key);
            if (state == null || !state.seeded || epochDay < state.lastDay) {
                state = new State();
                state.seeded = true;
                state.lastDay = minuteOfDay >= at ? epochDay : epochDay - 1;
                states.put(key, state);
                return false;
            }
            if (epochDay > state.lastDay && minuteOfDay >= at) {
                state.lastDay = epochDay;
                return minuteOfDay - at <= LATE_MINUTES;
            }
            return false;
        }

        /** Forgets what a sensor's Whens have seen: the sensor went off, so they start over. */
        synchronized void reset(String sensor) {
            lastSamples.remove(sensor);
            for (Rule rule : rules) {
                for (int index = 0; index < rule.whens.size(); index++) {
                    if (rule.whens.get(index).sensor.equals(sensor)) {
                        states.remove(key(rule, index));
                    }
                }
            }
        }

        /**
         * A reading from {@code sensor}; starts whatever it makes true. Decided under the lock,
         * run outside it on the scheduler's thread: an action that takes its time (the speech
         * engine starting) must not hold up the sensor callbacks behind the engine.
         */
        void sample(String sensor, Sample sample) {
            for (Start start : decide(sensor, sample)) {
                launch(start);
            }
        }

        private synchronized List<Start> decide(String sensor, Sample sample) {
            // Recorded before any condition is asked, so an If on this same sensor sees the
            // reading that starts the When, never the one before it.
            lastSamples.put(sensor, sample);
            long now = clock.nowMs();
            Map<String, Start> due = new LinkedHashMap<>();
            for (Rule rule : rules) {
                for (int index = 0; index < rule.whens.size(); index++) {
                    When when = rule.whens.get(index);
                    if (!when.sensor.equals(sensor)) {
                        continue;
                    }
                    Event event = Automations.event(when.sensor, when.event);
                    if (event == null || event.oneShot) {
                        continue;
                    }
                    String key = key(rule, index);
                    if (event.changes) {
                        if (changed(key, when, sample, now) && allowed(rule)) {
                            ranAt.put(key, now);
                            join(due, rule, index, now);
                        }
                        continue;
                    }
                    // The edge is spent whether or not the rule may run now (switched off, a
                    // condition not holding): a rule never runs later for what it missed.
                    if (edge(key, when, sample, now) && allowed(rule)) {
                        join(due, rule, index, now);
                    }
                }
            }
            return new ArrayList<>(due.values());
        }

        /** Whether the sample makes the When's state true, after its minutes; once per spell. */
        private boolean edge(String key, When when, Sample sample, long now) {
            Boolean holds = Automations.holds(when, sample);
            if (holds == null) {
                return false;
            }
            State state = states.get(key);
            if (state == null) {
                state = new State();
                states.put(key, state);
            }
            if (!state.seeded) {
                state.seeded = true;
                state.was = holds;
                state.fired = holds;
                state.trueSinceMs = now;
                return false;
            }
            if (!holds) {
                state.was = false;
                state.fired = false;
                return false;
            }
            if (!state.was) {
                state.was = true;
                state.trueSinceMs = now;
                state.fired = false;
            }
            if (state.fired
                    || when.minutes > 0 && now - state.trueSinceMs < when.minutes * 60_000L) {
                return false;
            }
            state.fired = true;
            return true;
        }

        /**
         * Whether a Changes When sees a new value: an unavailable reading is passed over and the
         * last value stands; the first value after a load, an edit, a reset or a start only
         * seeds. Numbers differ when not equal, texts when not equal ignoring case. The last
         * value moves on every reading, also one the "not again within" keeps from running.
         */
        private boolean changed(String key, When when, Sample sample, long now) {
            if (!available(sample)) {
                return false;
            }
            State state = states.get(key);
            if (state == null) {
                state = new State();
                states.put(key, state);
            }
            if (!state.hasLast) {
                remember(state, sample);
                return false;
            }
            boolean differs = sample.text != null
                    ? state.lastText == null || !sample.text.equalsIgnoreCase(state.lastText)
                    : sample.number != state.lastNumber;
            remember(state, sample);
            if (!differs) {
                return false;
            }
            Long last = ranAt.get(key);
            return last == null || when.cooldown <= 0 || now - last >= when.cooldown * 1000L;
        }

        private static void remember(State state, Sample sample) {
            state.hasLast = true;
            state.lastNumber = sample.number;
            state.lastText = sample.text;
        }

        /**
         * A one-shot event that any of {@code ids} names (a camera, and the groups it is in; a
         * tag): starts the enabled rules with a When waiting for one of them, whose conditions
         * hold, out of that When's "not again within"; returns how many started and, of those
         * left out, how many only because they ran too recently, so the caller can say which.
         */
        int[] event(String sensor, Set<String> ids) {
            int[] outcome = new int[2];
            for (Start start : dueFor(sensor, ids, outcome)) {
                launch(start);
            }
            return outcome;
        }

        private synchronized List<Start> dueFor(String sensor, Set<String> ids, int[] outcome) {
            long now = clock.nowMs();
            Map<String, Start> due = new LinkedHashMap<>();
            for (Rule rule : rules) {
                if (!rule.enabled) {
                    continue;
                }
                for (int index = 0; index < rule.whens.size(); index++) {
                    When when = rule.whens.get(index);
                    Event event = Automations.event(when.sensor, when.event);
                    if (!when.sensor.equals(sensor) || event == null || !event.oneShot) {
                        continue;
                    }
                    boolean named = when.match.isEmpty();
                    for (String id : ids) {
                        named |= when.match.equalsIgnoreCase(id);
                    }
                    if (!named || !conditionsHold(rule)) {
                        continue;
                    }
                    String key = key(rule, index);
                    Long last = ranAt.get(key);
                    if (last != null && when.cooldown > 0
                            && now - last < when.cooldown * 1000L) {
                        outcome[1]++;
                        continue;
                    }
                    ranAt.put(key, now);
                    outcome[0]++;
                    join(due, rule, index, now);
                }
            }
            return new ArrayList<>(due.values());
        }

        /** Whether the rule may start now: switched on and its conditions holding. */
        private boolean allowed(Rule rule) {
            return rule.enabled && conditionsHold(rule);
        }

        /**
         * The If conditions against the last readings, checked once at the start: all of
         * them, or any one with anyCondition; none is always. A sensor with no reading, or an
         * unavailable one, does not hold.
         */
        private boolean conditionsHold(Rule rule) {
            if (rule.conditions.isEmpty()) {
                return true;
            }
            for (Condition condition : rule.conditions) {
                boolean holds;
                if (TIME.equals(condition.sensor)) {
                    holds = inWindow(condition, clock.minuteOfDay(), clock.isoDay());
                } else {
                    Sample sample = lastSamples.get(condition.sensor);
                    holds = sample != null
                            && Boolean.TRUE.equals(Automations.holds(condition, sample));
                }
                if (holds == rule.anyCondition) {
                    // The first that holds decides "any", the first that fails decides "all".
                    return holds;
                }
            }
            return !rule.anyCondition;
        }

        /**
         * Adds a When that is due to this pass's starts. The first When of a rule in a pass marks
         * a new run, which ends any run the rule had going; a further When of the same rule in
         * the same pass (one reading crossing two Whens, a camera and its group) joins that run
         * with its own list, so none is lost to its sibling. Under the lock.
         */
        private void join(Map<String, Start> pass, Rule rule, int when, long now) {
            Start start = pass.get(rule.id);
            if (start == null) {
                long generation = ++generations;
                running.put(rule.id, generation);
                start = new Start(rule, generation, now);
                pass.put(rule.id, start);
            }
            start.whens.add(when);
        }

        /**
         * Starts every list of the run at once, in When order on the rules thread: list 1's
         * actions up to its first Wait, then list 2's, and so on. A Wait delays only the rest of
         * its own list.
         */
        private void launch(Start start) {
            List<Integer> whens = new ArrayList<>(start.whens);
            scheduler.after(0, () -> {
                for (int when : whens) {
                    proceed(start.rule, start.generation, when, 0, start.atMs);
                }
            });
        }

        /**
         * Runs one When's actions from {@code from} on, until a Wait, which schedules the rest
         * of that list. Asks before every action whether the run is still the rule's own, so a
         * newer start, an edit, a switch off or a delete stops every list of the older run
         * before its next action; drops the rest of the list when it comes more than {@link
         * #LATE_MS} after it was due: the panel slept through the Wait, and it never catches up.
         */
        private void proceed(Rule rule, long generation, int whenIndex, int from, long dueAtMs) {
            boolean late;
            synchronized (this) {
                if (!Long.valueOf(generation).equals(running.get(rule.id))) {
                    return;
                }
                late = clock.nowMs() - dueAtMs > LATE_MS;
            }
            if (late) {
                runner.note(rule, "the rest dropped: the panel was asleep through the wait");
                return;
            }
            List<Step> steps = rule.whens.get(whenIndex).then;
            for (int index = from; index < steps.size(); index++) {
                synchronized (this) {
                    if (!Long.valueOf(generation).equals(running.get(rule.id))) {
                        return;
                    }
                }
                Step step = steps.get(index);
                if (waits(step.action)) {
                    long delay = step.seconds * 1000L;
                    long dueAt = clock.nowMs() + delay;
                    int next = index + 1;
                    scheduler.after(delay,
                            () -> proceed(rule, generation, whenIndex, next, dueAt));
                    return;
                }
                try {
                    runner.run(rule, step);
                } catch (RuntimeException failed) {
                    // One action failing does not stop the ones after it.
                    runner.note(rule, "action " + (index + 1) + " failed: " + failed);
                }
            }
            // The run number stays when a list ends: another list of the same run may still be
            // in its Wait, and only a newer start, an edit, a switch off or a delete ends it.
        }
    }

    /**
     * Held by everyone who reads the stored rules, changes them and writes them back: the web
     * save, the delete and a switch flipped from MQTT run on different threads, and two
     * load-modify-store passes at once would lose one of the two changes.
     */
    static final Object STORE = new Object();

    /**
     * An id for a new rule that no existing rule has. Twelve letters and digits, about 59 bits:
     * unique across a fleet too, where a rule made on one panel and one pushed from the fleet
     * console must never share an id; six were unique on one panel only (2026-09-30). Older
     * ids stay valid.
     */
    static String newId(List<Rule> existing) {
        java.util.Random random = new java.security.SecureRandom();
        while (true) {
            StringBuilder id = new StringBuilder();
            for (int index = 0; index < 12; index++) {
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

    /** Deletion markers kept, the newest; a marker older than these no longer travels. */
    static final int MAX_DELETED = 100;

    /**
     * The list about to be stored, each rule's change time set: now for a rule that is new or
     * differs from its stored copy, the stored time for one that does not. Every writer of the
     * rules stores through this (KioskConfig.storeAutomations), so no path can forget it.
     */
    static List<Rule> stamp(List<Rule> stored, List<Rule> next, long now) {
        for (Rule rule : next) {
            Rule before = find(stored, rule.id);
            rule.changedAt = before != null && sameRule(before, rule) ? before.changedAt : now;
        }
        return next;
    }

    /** Whether two copies say the same, their change times aside. */
    private static boolean sameRule(Rule one, Rule other) {
        Rule a = one.copy();
        Rule b = other.copy();
        a.changedAt = 0;
        b.changedAt = 0;
        return toJson(Collections.singletonList(a)).toString()
                .equals(toJson(Collections.singletonList(b)).toString());
    }

    /**
     * The deletion markers after a store: the stored ones, a marker for every rule the store
     * leaves out, none for a rule that is back, the newest {@link #MAX_DELETED}. Each is
     * {"id", "deleted_at"}, so a deletion can travel to the other panels of a fleet as a
     * change travels (2026-09-30).
     */
    static String deleted(String storedMarkers, List<Rule> stored, List<Rule> next, long now) {
        JSONArray markers = new JSONArray();
        try {
            JSONArray old = storedMarkers == null || storedMarkers.isEmpty() ? new JSONArray()
                    : new JSONArray(storedMarkers);
            List<JSONObject> kept = new ArrayList<>();
            for (int index = 0; index < old.length(); index++) {
                JSONObject one = old.optJSONObject(index);
                if (one != null && find(next, one.optString("id", "")) == null) {
                    kept.add(one);
                }
            }
            for (Rule rule : stored) {
                if (find(next, rule.id) == null) {
                    JSONObject one = new JSONObject();
                    one.put("id", rule.id);
                    one.put("deleted_at", now);
                    kept.add(one);
                }
            }
            for (int index = Math.max(0, kept.size() - MAX_DELETED); index < kept.size(); index++) {
                markers.put(kept.get(index));
            }
        } catch (JSONException malformed) {
            return new JSONArray().toString();
        }
        return markers.toString();
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
