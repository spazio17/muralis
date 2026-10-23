/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The automations on the host: the vocabulary, the storage round trip, the rule book, and
 * the engine's seed-then-arm logic, its conditions and its Waits, each a case that would
 * otherwise need a sensor and a stopwatch beside a tablet.
 */
public final class AutomationsTest {
    private static final long T0 = 1_000_000L;

    public static void main(String[] args) throws Exception {
        testStoreRoundTrip();
        testChangeTimesAndMarkers();
        testValidateRefusals();
        testWindow();
        testEngineSeedsThenArms();
        testEngineHoldsForMinutes();
        testEngineResetAndDisabled();
        testEngineReseedsAnEditedRule();
        testEngineTickAdvancesTheMinutes();
        testEngineSeedLetsTheFirstEventFire();
        testIdShape();
        testTagEvents();
        testCameraEvents();
        testTimeOfDay();
        testBatteryAboveAndNewActions();
        testOldShapeConverts();
        testStoreRoundTripNewShape();
        testValidateNewRefusals();
        testTwoWhensOneRule();
        testChangesTrigger();
        testConditionsAtStart();
        testWaitAndRestart();
        testWaitCancelledByEditDisableDelete();
        testLateContinuationDropped();
        testWhenStateReseededOnlyForChangedWhen();
        testIfOnTheWhensOwnSensor();
        testWhensDueTogether();
        testEditorOps();
        System.out.println("AutomationsTest passed");
    }

    private static final String KUMA = "int_uptimekuma1_any_down";
    private static final String HEIGHT = "int_bitcoin1_height";
    private static final String ROOM = "int_cal1_room";

    private static Map<String, String> names() {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("proximity", "Proximity");
        names.put("illuminance", "Illuminance");
        names.put("nfc_tag", "NFC tag");
        names.put("battery", "Battery");
        names.put("display", "Display");
        names.put(Automations.TIME, Automations.TIME_NAME);
        names.put(Automations.CAMERAS, Automations.CAMERAS_NAME);
        names.put(KUMA, "Any monitor down");
        names.put(HEIGHT, "Block height");
        names.put(ROOM, "Room");
        return names;
    }

    /** The integration values the tests name, as the runner would offer them. */
    private static void integrations() {
        Integrations.ValueSpec down = new Integrations.ValueSpec("any_down", "Any monitor down",
                Integrations.Type.FLAG, "");
        Integrations.ValueSpec height = new Integrations.ValueSpec("height", "Block height",
                Integrations.Type.NUMBER, "");
        Integrations.ValueSpec room = new Integrations.ValueSpec("room", "Room",
                Integrations.Type.TEXT, "");
        Integrations.lookup = id -> id.equals(KUMA) ? down : id.equals(HEIGHT) ? height
                : id.equals(ROOM) ? room : null;
    }

    private static Automations.Rule rule(String sensor, String event, String action) {
        Automations.Rule rule = new Automations.Rule();
        rule.id = "r1";
        rule.name = "Test";
        rule.whens.add(when(sensor, event, action));
        return rule;
    }

    private static Automations.When when(String sensor, String event, String... actions) {
        Automations.When when = new Automations.When();
        when.sensor = sensor;
        when.event = event;
        for (String action : actions) {
            when.then.add(step(action));
        }
        return when;
    }

    private static Automations.Step step(String action) {
        Automations.Step step = new Automations.Step();
        step.action = action;
        return step;
    }

    private static Automations.Step waitFor(int seconds) {
        Automations.Step step = step(Automations.WAIT);
        step.seconds = seconds;
        return step;
    }

    private static Automations.When w(Automations.Rule rule) {
        return rule.whens.get(0);
    }

    private static Automations.Step s(Automations.Rule rule) {
        return rule.whens.get(0).then.get(0);
    }

    private static Automations.Condition window(int from, int to, int days) {
        Automations.Condition condition = new Automations.Condition();
        condition.sensor = Automations.TIME;
        condition.from = from;
        condition.to = to;
        condition.days = days;
        return condition;
    }

    private static Automations.Condition state(String sensor, String state) {
        Automations.Condition condition = new Automations.Condition();
        condition.sensor = sensor;
        condition.state = state;
        return condition;
    }

    /**
     * The engine with a clock the test sets and a scheduler it drives: every task due by the
     * clock runs on settle, in the order it is due, as the rules thread would run it.
     */
    private static final class Rig implements Automations.Clock, Automations.Scheduler,
            Automations.Runner {
        long now = T0;
        int minute = 600;
        int isoDay = 3;
        long epochDay = 20_000;
        final List<Object[]> tasks = new ArrayList<>();
        /** The rule id of every action run, one entry per action. */
        final List<String> ran = new ArrayList<>();
        /** "id:action" of every action run. */
        final List<String> steps = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Automations.Engine engine = new Automations.Engine(this, this, this);

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public int minuteOfDay() {
            return minute;
        }

        @Override
        public int isoDay() {
            return isoDay;
        }

        @Override
        public long epochDay() {
            return epochDay;
        }

        @Override
        public void after(long delayMs, Runnable task) {
            tasks.add(new Object[] {now + delayMs, task});
        }

        @Override
        public void run(Automations.Rule rule, Automations.Step step) {
            ran.add(rule.id);
            steps.add(rule.id + ":" + step.action);
        }

        @Override
        public void note(Automations.Rule rule, String why) {
            notes.add(rule.id + ": " + why);
        }

        void settle() {
            while (true) {
                Object[] next = null;
                for (Object[] task : tasks) {
                    if ((Long) task[0] <= now && (next == null || (Long) task[0] < (Long) next[0])) {
                        next = task;
                    }
                }
                if (next == null) {
                    return;
                }
                tasks.remove(next);
                ((Runnable) next[1]).run();
            }
        }

        void at(long ms) {
            now = ms;
            settle();
        }

        void sample(String sensor, Automations.Sample sample, long ms, int minuteOfDay) {
            now = ms;
            minute = minuteOfDay;
            engine.sample(sensor, sample);
            settle();
        }

        void tick(long ms, int minuteOfDay) {
            now = ms;
            minute = minuteOfDay;
            engine.tick();
            settle();
        }

        void clock(long day, int minuteOfDay) {
            epochDay = day;
            minute = minuteOfDay;
            engine.tick();
            settle();
        }

        int[] event(String sensor, java.util.Set<String> ids, long ms) {
            now = ms;
            int[] outcome = engine.event(sensor, ids);
            settle();
            return outcome;
        }
    }

    /** The clock's rules: once a day, late ticks, a seed after the time, the clock back. */
    private static void testTimeOfDay() {
        Rig rig = new Rig();
        Automations.Rule night = rule(Automations.TIME, "at", "display_off");
        w(night).level = 22 * 60;
        require(Automations.validate(night.copy(), names()) == null, "22:00 is a time");
        Automations.Rule bad = rule(Automations.TIME, "at", "display_off");
        w(bad).level = 24 * 60;
        require(Automations.validate(bad, names()) != null, "24:00 is not a time");
        require(Automations.sentence(night).startsWith("At 22:00"), Automations.sentence(night));
        rig.engine.rules(Collections.singletonList(night));
        long day = 20_000;
        rig.clock(day, 21 * 60);          // the seed, before the time
        rig.clock(day, 21 * 60 + 59);
        require(rig.ran.isEmpty(), "not before the time");
        rig.clock(day, 22 * 60 + 1);      // a tick a minute late still fires
        require(rig.ran.size() == 1, "fires once at the time, a tick a minute late included");
        rig.clock(day, 23 * 60);
        require(rig.ran.size() == 1, "once a day");
        rig.clock(day + 1, 5);            // across midnight, not yet
        require(rig.ran.size() == 1, "after midnight it waits for 22:00 again");
        rig.clock(day + 1, 22 * 60);
        require(rig.ran.size() == 2, "the next day at the time");
        rig.clock(day, 22 * 60 + 30);     // the clock set back a day
        require(rig.ran.size() == 2, "the clock moved back does not run a day twice");
        // A restart after the time: the seed counts today as run.
        Rig after = new Rig();
        after.engine.rules(Collections.singletonList(night));
        after.clock(day + 5, 23 * 60);
        after.clock(day + 5, 23 * 60 + 30);
        require(after.ran.isEmpty(), "a restart after the time does not repeat it");
        after.clock(day + 6, 22 * 60);
        require(after.ran.size() == 1, "and runs the next day");
        // A panel that slept through the time: that run is dropped, never replayed at wake.
        Rig sleeper = new Rig();
        sleeper.engine.rules(Collections.singletonList(night));
        sleeper.clock(day, 21 * 60);
        sleeper.clock(day, 22 * 60 + 7);
        require(sleeper.ran.isEmpty(), "a run seen seven minutes late is dropped, not replayed");
        sleeper.clock(day + 1, 22 * 60);
        require(sleeper.ran.size() == 1, "the next day it runs at its time again");
    }

    private static void testBatteryAboveAndNewActions() {
        Automations.Rule above = rule("battery", "above", "call_url");
        w(above).level = 80;
        s(above).argument = "http://plug.local/off";
        require(Automations.validate(above, names()) == null, "battery above with an address");
        require(Boolean.TRUE.equals(Automations.holds(w(above), Automations.Sample.of(81.0)))
                && Boolean.FALSE.equals(Automations.holds(w(above), Automations.Sample.of(80.0))),
                "above means more than the level");
        String[][] cases = {
                {"open_page", "https://home.local/night", null}, {"open_page", "home.local", "x"},
                {"call_url", "ftp://x", "x"}, {"brightness", "0", null}, {"brightness", "100", null},
                {"brightness", "101", "x"}, {"brightness", "half", "x"},
                {"ambient_mode", "dim", null}, {"ambient_mode", "disco", "x"},
                {"mqtt_publish", "home/plug off", null}, {"mqtt_publish", "home/#", "x"},
                {"mqtt_publish", "home/+/x on", "x"}, {"mqtt_publish", "nospace", "x"},
        };
        for (String[] c : cases) {
            String problem = Automations.checkArgument(c[0], c[1]);
            require((problem == null) == (c[2] == null), c[0] + " " + c[1] + ": " + problem);
        }
    }

    private static void testStoreRoundTrip() {
        List<Automations.Rule> rules = Automations.defaults();
        require(rules.size() == 1 && rules.get(0).id.equals("wake"), "the shipped rule is Wake by hand");
        Automations.Rule window = rule("illuminance", "darker", "say");
        w(window).level = 12.5;
        w(window).minutes = 3;
        s(window).argument = "It is dark";
        Automations.setWindow(window, 22 * 60, 6 * 60);
        window.enabled = false;
        rules.add(window);
        List<Automations.Rule> back = Automations.parse(Automations.store(rules));
        require(back.size() == 2, "two rules stored and read back");
        Automations.Rule read = back.get(1);
        Automations.Condition time = Automations.firstTimeCondition(read);
        require(w(read).level == 12.5 && w(read).minutes == 3 && s(read).argument.equals("It is dark")
                && time != null && time.from == 22 * 60 && time.to == 6 * 60
                && time.days == Automations.ALL_DAYS && !read.enabled,
                "every field survives the round trip");
        require(Double.isNaN(w(back.get(0)).level), "a rule without a level reads NaN");
        require(Automations.parse(null).size() == 1, "nothing stored gives the shipped rule");
        require(Automations.parse("not json").isEmpty(), "a broken document gives no rules");
        require(Automations.find(back, "r1") == read && Automations.find(back, "zz") == null, "find by id");
        String fresh = Automations.newId(back);
        require(fresh.length() == 12 && Automations.find(back, fresh) == null, "a new id is free");
    }

    /** The shape the fleet of 0.7 merges by: change times and deletion markers. */
    private static void testChangeTimesAndMarkers() {
        List<Automations.Rule> stored = Automations.defaults();
        stored.get(0).changedAt = 1_000;
        Automations.Rule added = rule("illuminance", "darker", "say");
        w(added).level = 5;
        List<Automations.Rule> next = new ArrayList<>();
        next.add(stored.get(0).copy());
        next.add(added);
        Automations.stamp(stored, next, 2_000);
        require(next.get(0).changedAt == 1_000, "an unchanged rule keeps its time");
        require(next.get(1).changedAt == 2_000, "a new rule takes the store's time");
        List<Automations.Rule> edited = Automations.parse(Automations.store(next));
        require(edited.get(1).changedAt == 2_000, "the time survives the round trip");
        edited.get(1).name = "Renamed";
        Automations.stamp(next, edited, 3_000);
        require(edited.get(1).changedAt == 3_000, "an edited rule takes the new time");
        require(edited.get(0).changedAt == 1_000, "the other rule keeps its time");
        List<Automations.Rule> fewer = new ArrayList<>();
        fewer.add(edited.get(1));
        String markers = Automations.deleted("[]", edited, fewer, 4_000);
        require(markers.contains("\"wake\"") && markers.contains("4000"),
                "a deleted rule leaves a marker with its time");
        String back = Automations.deleted(markers, fewer, edited, 5_000);
        require(!back.contains("\"wake\""), "a rule that comes back loses its marker");
        StringBuilder many = new StringBuilder("[");
        for (int index = 0; index < Automations.MAX_DELETED + 10; index++) {
            many.append(index == 0 ? "" : ",").append("{\"id\":\"d").append(index)
                    .append("\",\"deleted_at\":").append(index).append('}');
        }
        String capped = Automations.deleted(many.append(']').toString(), fewer, fewer, 6_000);
        require(!capped.contains("\"d0\"") && capped.contains("\"d" + (Automations.MAX_DELETED + 9)
                + "\""), "only the newest markers are kept");
    }

    private static void testValidateRefusals() {
        require(Automations.validate(rule("proximity", "near", "display_on"), names()) == null,
                "the shipped shape passes");
        Automations.Rule longName = rule("proximity", "near", "display_on");
        longName.name = new String(new char[61]).replace('\0', 'n');
        require(Automations.validate(longName, names()) != null, "a name over 60 is refused");
        Automations.Rule emoji = rule("proximity", "near", "display_on");
        emoji.name = "👋".repeat(60);
        require(Automations.validate(emoji, names()) == null, "sixty emoji are sixty characters");
        Automations.Rule unnamed = rule("proximity", "near", "display_on");
        unnamed.name = "  ";
        require(Automations.validate(unnamed, names()) == null && !unnamed.name.trim().isEmpty(),
                "a blank name becomes the sentence");
        Automations.Rule minutes = rule("illuminance", "darker", "display_on");
        w(minutes).level = 5;
        w(minutes).minutes = -1;
        require(Automations.validate(minutes, names()) != null, "negative minutes refused");
        w(minutes).minutes = Automations.MAX_MINUTES + 1;
        require(Automations.validate(minutes, names()) != null, "minutes over a day refused");
        w(minutes).minutes = 0;
        require(Automations.validate(minutes, names()) == null, "zero minutes is at once");
        Automations.Rule noLevel = rule("illuminance", "darker", "display_on");
        require(Automations.validate(noLevel, names()) != null, "an event with a level needs one");
        Automations.Rule light = rule("proximity", "near", "light_on");
        require(Automations.validate(light, names()) == null && s(light).argument.isEmpty(),
                "status light on with no colour keeps the stored one");
        s(light).argument = " 0, 0 ,255 ";
        require(Automations.validate(light, names()) == null && s(light).argument.equals("0,0,255"),
                "a colour is stored as r,g,b");
        s(light).argument = "0,0,256";
        require(Automations.validate(light, names()) != null, "a channel over 255 is refused");
        Automations.Rule say = rule("proximity", "near", "say");
        require("the sentence is missing".equals(Automations.validate(say, names())),
                "say needs a sentence, in today's words");
        s(say).argument = "Hello";
        require(Automations.validate(say, names()) == null, "say with a sentence passes");
        Automations.Rule sound = rule("proximity", "near", "play_sound");
        s(sound).argument = "ftp://x/y.mp3";
        require(Automations.validate(sound, names()) != null, "a sound needs http or https");
        Automations.Rule equal = rule("proximity", "near", "display_on");
        Automations.setWindow(equal, 7 * 60, 7 * 60);
        require("the time window ends when it starts".equals(Automations.validate(equal, names())),
                "a window ending when it starts is refused");
        Automations.Rule absent = rule("pressure", "x", "display_on");
        require("choose a sensor".equals(Automations.validate(absent, names())),
                "a sensor this panel lacks is refused");
    }

    private static void testWindow() {
        Automations.Condition night = window(Automations.minutesOf("22:00"),
                Automations.minutesOf("06:00"), Automations.ALL_DAYS);
        require(Automations.inWindow(night, 23 * 60, 3) && Automations.inWindow(night, 5 * 60, 3)
                && !Automations.inWindow(night, 12 * 60, 3), "a window across midnight");
        require(Automations.inWindow(night, 22 * 60, 3) && !Automations.inWindow(night, 6 * 60, 3),
                "the start is inside, the end is outside");
        // Friday 23:00 to 06:00 covers Saturday 02:00, and not Saturday 23:30.
        Automations.Condition friday = window(23 * 60, 6 * 60, 1 << 4);
        require(Automations.inWindow(friday, 23 * 60 + 30, 5)
                && Automations.inWindow(friday, 2 * 60, 6)
                && !Automations.inWindow(friday, 23 * 60 + 30, 6)
                && !Automations.inWindow(friday, 2 * 60, 5), "the window's day is the day it began");
        Automations.Condition weekend = window(-1, -1, 1 << 5 | 1 << 6);
        require(Automations.inWindow(weekend, 0, 7) && !Automations.inWindow(weekend, 12 * 60, 1),
                "without times only the day counts");
        require(Automations.minutesOf("7am") < 0 && Automations.minutesOf("25:00") < 0,
                "times are hh:mm");
        require(Automations.clock(6 * 60 + 5).equals("06:05"), "clock prints hh:mm");
    }

    private static void testEngineSeedsThenArms() {
        Rig rig = new Rig();
        rig.engine.rules(Collections.singletonList(rule("proximity", "near", "display_on")));
        rig.sample("proximity", Automations.Sample.of(0, true), T0, 600);
        require(rig.ran.isEmpty(), "a rule made while its condition holds does not fire on the spot");
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 1000, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 2000, 600);
        require(rig.ran.equals(Collections.singletonList("r1")), "false then true fires once");
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 3000, 600);
        require(rig.ran.size() == 1, "the same true again does not fire again");
        rig.sample("illuminance", Automations.Sample.of(3), T0 + 4000, 600);
        require(rig.ran.size() == 1, "another sensor's sample is not this rule's");
    }

    private static void testEngineHoldsForMinutes() {
        Rig rig = new Rig();
        Automations.Rule dark = rule("illuminance", "darker", "display_off");
        w(dark).level = 10;
        w(dark).minutes = 2;
        rig.engine.rules(Collections.singletonList(dark));
        rig.sample("illuminance", Automations.Sample.of(100), T0, 600);
        rig.sample("illuminance", Automations.Sample.of(2), T0 + 1000, 600);
        rig.sample("illuminance", Automations.Sample.of(2), T0 + 60_000, 600);
        require(rig.ran.isEmpty(), "not before the minutes have passed");
        rig.sample("illuminance", Automations.Sample.of(2), T0 + 1000 + 2 * 60_000, 600);
        require(rig.ran.equals(Collections.singletonList("r1")), "fires once the minutes have held");
        rig.sample("illuminance", Automations.Sample.of(50), T0 + 200_000, 600);
        rig.sample("illuminance", Automations.Sample.of(2), T0 + 201_000, 600);
        rig.sample("illuminance", Automations.Sample.of(2), T0 + 230_000, 600);
        require(rig.ran.size() == 1, "a new dark spell starts its own count");
    }

    private static void testEngineReseedsAnEditedRule() {
        Rig rig = new Rig();
        Automations.Rule dark = rule("illuminance", "darker", "display_off");
        w(dark).level = 5;
        rig.engine.rules(Collections.singletonList(dark));
        rig.sample("illuminance", Automations.Sample.of(20), T0, 600);
        rig.sample("illuminance", Automations.Sample.of(20), T0 + 1000, 600);
        Automations.Rule edited = dark.copy();
        w(edited).level = 50;
        rig.engine.rules(Collections.singletonList(edited));
        rig.sample("illuminance", Automations.Sample.of(20), T0 + 2000, 600);
        require(rig.ran.isEmpty(), "an edited rule whose condition now holds is seeded, not fired");
        rig.sample("illuminance", Automations.Sample.of(80), T0 + 3000, 600);
        rig.sample("illuminance", Automations.Sample.of(20), T0 + 4000, 600);
        require(rig.ran.equals(Collections.singletonList("r1")), "and fires on the next false-to-true");
        Automations.Rule renamed = edited.copy();
        renamed.name = "Another name";
        rig.engine.rules(Collections.singletonList(renamed));
        rig.sample("illuminance", Automations.Sample.of(20), T0 + 5000, 600);
        require(rig.ran.size() == 1, "a rename keeps the state: the same true does not fire again");
    }

    private static void testEngineSeedLetsTheFirstEventFire() {
        Rig rig = new Rig();
        rig.engine.rules(Collections.singletonList(rule("proximity", "near", "display_on")));
        rig.now = T0;
        rig.engine.seed("proximity", Automations.Sample.of(5, false));
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(rig.ran.equals(Collections.singletonList("r1")),
                "seeded with the state as it stands, the first event that changes it fires");
        rig.now = T0 + 2000;
        rig.engine.seed("proximity", Automations.Sample.of(5, false));
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 3000, 600);
        require(rig.ran.size() == 1, "a seed never resets a rule that has already seen a state");
    }

    private static void testEngineTickAdvancesTheMinutes() {
        Rig rig = new Rig();
        Automations.Rule dark = rule("illuminance", "darker", "display_off");
        w(dark).level = 5;
        w(dark).minutes = 2;
        rig.engine.rules(Collections.singletonList(dark));
        rig.sample("illuminance", Automations.Sample.of(100), T0, 600);
        rig.sample("illuminance", Automations.Sample.of(2), T0 + 1000, 600);
        rig.tick(T0 + 60_000, 600);
        require(rig.ran.isEmpty(), "a tick before the minutes have passed fires nothing");
        rig.tick(T0 + 1000 + 2 * 60_000, 600);
        require(rig.ran.equals(Collections.singletonList("r1")),
                "a steady room sends no event, so the tick fires the rule once its minutes held");
        rig.tick(T0 + 1000 + 3 * 60_000, 600);
        require(rig.ran.size() == 1, "and not again on the next tick");
    }

    private static void testIdShape() {
        Automations.Rule rule = rule("proximity", "near", "display_on");
        rule.id = "x\" onfocus=\"alert(1)";
        require(Automations.validate(rule, names()) != null, "an id with quotes is refused");
        rule.id = "wake";
        require(Automations.validate(rule, names()) == null, "the shipped id passes");
        rule.id = "";
        rule.name = "line\nbreak";
        require(Automations.validate(rule, names()) == null && rule.name.equals("line break"),
                "a control character in the name becomes a space");
        require(Automations.shortened("ab😀cd", 3).equals("ab😀…"), "the cut never splits an emoji");
    }

    private static void testEngineResetAndDisabled() {
        Rig rig = new Rig();
        Automations.Rule wake = rule("proximity", "near", "display_on");
        Automations.Rule night = rule("proximity", "near", "say");
        night.id = "r2";
        s(night).argument = "x";
        Automations.setWindow(night, 22 * 60, 6 * 60);
        Automations.Rule off = rule("proximity", "near", "reload");
        off.id = "r3";
        off.enabled = false;
        rig.engine.rules(java.util.Arrays.asList(wake, night, off));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 12 * 60);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 12 * 60);
        require(rig.ran.equals(Collections.singletonList("r1")),
                "at noon only the unwindowed enabled rule runs");
        rig.engine.reset("proximity");
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 2000, 23 * 60);
        require(rig.ran.size() == 1, "after a reset the first sample seeds again, firing nothing");
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 3000, 23 * 60);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 4000, 23 * 60);
        require(rig.ran.size() == 3 && rig.ran.contains("r2") && !rig.ran.contains("r3"),
                "at night the windowed rule runs too, the disabled one never");
    }

    private static void testTagEvents() {
        Rig rig = new Rig();
        Automations.Rule any = rule("nfc_tag", "tag", "display_on");
        Automations.Rule one = rule("nfc_tag", "tag", "reload");
        one.id = "r2";
        w(one).match = "04A32B9C";
        w(any).cooldown = 0;
        w(one).cooldown = 0;
        rig.engine.rules(java.util.Arrays.asList(any, one));
        rig.event("nfc_tag", Collections.singleton("FFFF"), T0);
        require(rig.ran.equals(Collections.singletonList("r1")), "any tag runs the open rule alone");
        rig.event("nfc_tag", Collections.singleton("04a32b9c"), T0 + 1000);
        require(rig.ran.size() == 3 && rig.ran.get(2).equals("r2"), "the named tag runs both, id case-blind");
    }

    /** A camera's event: named, a group's rules too, the "not again within", the sentence. */
    private static void testCameraEvents() throws org.json.JSONException {
        Automations.Rule door = rule(Automations.CAMERAS, "triggered", "camera");
        w(door).match = "Door";
        s(door).argument = "Front";
        s(door).show = Automations.SHOW_FULLSCREEN;
        s(door).seconds = 20;
        s(door).chime = true;
        require(Automations.validate(door, names()) == null, "a camera rule validates");
        require(w(door).cooldown == Automations.DEFAULT_COOLDOWN, "a new rule waits 60 s by default");
        require(door.name.equals("Test") && Automations.sentence(door).equals(
                "A camera is triggered: Door, then Show a camera \"Front\" full screen for 20 s"
                        + " with a chime, not again within 60 s"), Automations.sentence(door));
        Automations.Rule none = rule(Automations.CAMERAS, "triggered", "display_on");
        require(Automations.validate(none, names()) != null, "a camera rule names its camera");
        Automations.Rule bad = rule(Automations.CAMERAS, "triggered", "camera");
        w(bad).match = "Door";
        s(bad).argument = "Front";
        s(bad).show = "sideways";
        require(Automations.validate(bad, names()) != null, "show is pop-up or full screen");
        s(bad).show = Automations.SHOW_POPUP;
        s(bad).seconds = 301;
        require(Automations.validate(bad, names()) != null, "the time is 0 to 300 s");
        Automations.Rule light = rule("illuminance", "darker", "display_on");
        w(light).level = 5;
        w(light).cooldown = 30;
        s(light).chime = true;
        require(Automations.validate(light, names()) == null && w(light).cooldown == 0
                && !s(light).chime, "not again within and chime only where they apply");
        Automations.Rule group = rule(Automations.CAMERAS, "triggered", "reload");
        group.id = "r2";
        w(group).match = "Outside";
        w(group).cooldown = 0;
        Rig rig = new Rig();
        rig.engine.rules(java.util.Arrays.asList(door, group));
        java.util.Set<String> doorAndGroups = new java.util.HashSet<>(
                java.util.Arrays.asList("Door", "outside"));
        int[] first = rig.event(Automations.CAMERAS, doorAndGroups, 1_000);
        require(first[0] == 2 && rig.ran.size() == 2, "the camera's rule and its group's run");
        int[] again = rig.event(Automations.CAMERAS, doorAndGroups, 30_000);
        require(again[0] == 1 && again[1] == 1, "inside its 60 s the camera's rule waits");
        int[] later = rig.event(Automations.CAMERAS, doorAndGroups, 62_000);
        require(later[0] == 2, "after it, it runs again");
        int[] other = rig.event(Automations.CAMERAS, Collections.singleton("Garden"), 200_000);
        require(other[0] == 0 && other[1] == 0, "another camera runs neither");
        org.json.JSONObject stored = Automations.toJson(Collections.singletonList(door))
                .optJSONObject(0);
        Automations.Rule back = Automations.parseRule(stored);
        require(s(back).show.equals("fullscreen") && s(back).seconds == 20 && s(back).chime
                && w(back).cooldown == 60, "the fields round-trip");
        // A rule of the flat shape stored before the field runs every time, as it did.
        org.json.JSONObject older = new org.json.JSONObject("{\"id\":\"c\",\"sensor\":\"cameras\","
                + "\"event\":\"triggered\",\"match\":\"Door\",\"action\":\"reload\"}");
        require(w(Automations.parseRule(older)).cooldown == 0,
                "a rule stored before the field runs every time, as it did");
    }

    /** The flat shape of before reads as one When, one action and a time condition. */
    private static void testOldShapeConverts() throws org.json.JSONException {
        String flat = "[{\"id\":\"n1\",\"name\":\"Night\",\"sensor\":\"illuminance\","
                + "\"event\":\"darker\",\"level\":5,\"minutes\":3,\"match\":\"\",\"cooldown\":0,"
                + "\"seconds\":30,\"chime\":false,\"show\":\"popup\",\"action\":\"say\","
                + "\"argument\":\"Dark\",\"enabled\":false,\"only_from\":1320,\"only_to\":360,"
                + "\"changed_at\":77},"
                + "{\"id\":\"wake\",\"name\":\"Wake by hand\",\"sensor\":\"proximity\","
                + "\"event\":\"near\",\"action\":\"display_on\",\"only_from\":-1,\"only_to\":-1}]";
        List<Automations.Rule> rules = Automations.parse(flat);
        require(rules.size() == 2, "both flat rules read");
        Automations.Rule night = rules.get(0);
        require(night.whens.size() == 1 && w(night).sensor.equals("illuminance")
                && w(night).event.equals("darker") && w(night).level == 5 && w(night).minutes == 3
                && w(night).then.size() == 1 && s(night).action.equals("say")
                && s(night).argument.equals("Dark") && !night.enabled && night.changedAt == 77,
                "the flat fields land in the When and its action");
        Automations.Condition time = Automations.firstTimeCondition(night);
        require(night.conditions.size() == 1 && time != null && time.from == 22 * 60
                && time.to == 6 * 60 && time.days == Automations.ALL_DAYS,
                "the window becomes a time condition every day");
        require(rules.get(1).conditions.isEmpty(), "no window, no condition");
        require(Automations.validate(night, names()) == null, "a converted rule validates");
        require(Automations.sentence(night).equals(
                "Darker than 5 lx for 3 min, then Say a sentence \"Dark\", if 22:00 to 06:00"),
                Automations.sentence(night));
        String written = Automations.store(rules);
        require(written.contains("\"when\"") && written.contains("\"if\"")
                && !written.contains("only_from") && !written.contains("\"action\":\"say\",\"argument\":\"Dark\",\"seconds\""),
                "every write uses the new shape: " + written);
        List<Automations.Rule> again = Automations.parse(written);
        require(Automations.store(again).equals(written), "the new shape reads back the same");
        // An old rule still runs in the engine, in its window.
        Rig rig = new Rig();
        Automations.Rule wake = rules.get(1);
        rig.engine.rules(Collections.singletonList(wake));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(rig.steps.equals(Collections.singletonList("wake:display_on")), "a flat rule runs");
    }

    /** The two worked examples: they validate, store as the spec writes them and read back. */
    private static void testStoreRoundTripNewShape() throws org.json.JSONException {
        integrations();
        String kuma = "{\"id\":\"k3m9x2q7v4pa\",\"name\":\"A service is down\",\"enabled\":true,"
                + "\"changed_at\":1761220000000,\"when\":["
                + "{\"sensor\":\"" + KUMA + "\",\"event\":\"int_on\",\"minutes\":0,\"match\":\"\","
                + "\"cooldown\":0,\"then\":[{\"action\":\"light_on\",\"argument\":\"255,0,0\"},"
                + "{\"action\":\"popup\",\"argument\":\"A service is down. Check the status page.\","
                + "\"seconds\":30,\"chime\":true},{\"action\":\"reload\"}]},"
                + "{\"sensor\":\"" + KUMA + "\",\"event\":\"int_off\",\"minutes\":0,\"match\":\"\","
                + "\"cooldown\":0,\"then\":[{\"action\":\"light_off\"},{\"action\":\"reload\"}]}],"
                + "\"if\":[{\"sensor\":\"time\",\"from\":480,\"to\":1080,\"days\":[1,2,3,4,5]}],"
                + "\"if_any\":false}";
        String block = "{\"id\":\"b7tq2w9x4mna\",\"name\":\"New Bitcoin block\",\"enabled\":true,"
                + "\"changed_at\":1761220000000,\"when\":[{\"sensor\":\"" + HEIGHT + "\","
                + "\"event\":\"int_changes\",\"minutes\":0,\"match\":\"\",\"cooldown\":0,"
                + "\"then\":[{\"action\":\"light_on\",\"argument\":\"255,165,0\"},"
                + "{\"action\":\"wait\",\"seconds\":10},{\"action\":\"light_off\"}]}],"
                + "\"if\":[],\"if_any\":false}";
        List<Automations.Rule> rules = Automations.parse("[" + kuma + "," + block + "]");
        require(rules.size() == 2, "both examples read");
        for (Automations.Rule rule : rules) {
            String problem = Automations.validate(rule, names());
            require(problem == null, rule.name + ": " + problem);
        }
        require(Automations.checkList(rules) == null, "the two fit the list");
        Automations.Rule down = rules.get(0);
        require(down.whens.size() == 2 && down.whens.get(0).then.size() == 3
                && down.whens.get(0).then.get(1).chime && down.conditions.get(0).days == 0x1F,
                "the When, the actions and the days read");
        require(Automations.sentence(down).equals("Turns on, then Status light on \"255,0,0\" and"
                + " 2 more; Turns off, then Status light off and 1 more, if 08:00 to 18:00,"
                + " Monday to Friday"), Automations.sentence(down));
        require(Automations.sentence(rules.get(1)).equals(
                "Changes, then Status light on \"255,165,0\" and 2 more"),
                Automations.sentence(rules.get(1)));
        String written = Automations.store(rules);
        require(written.contains("{\"action\":\"wait\",\"seconds\":10}")
                && written.contains("{\"action\":\"light_off\"}")
                && written.contains("\"days\":[1,2,3,4,5]")
                && written.contains("{\"action\":\"reload\"}"),
                "keys only where they apply: " + written);
        require(Automations.store(Automations.parse(written)).equals(written),
                "the new shape round-trips unchanged");
        org.json.JSONArray status = Automations.statusJson(rules);
        org.json.JSONObject first = status.optJSONObject(0);
        require(first.optString("id", "").equals("k3m9x2q7v4pa") && first.optBoolean("enabled")
                && first.optJSONArray("sensors").length() == 2 && first.has("digest")
                && !first.has("when"), "the status block is slim: " + first);
        Automations.Rule renamed = down.copy();
        renamed.enabled = false;
        require(Automations.statusJson(Collections.singletonList(renamed)).optJSONObject(0)
                .optString("digest", "").equals(first.optString("digest", "x")),
                "the digest ignores the switch");
        renamed.name = "Other";
        require(!Automations.statusJson(Collections.singletonList(renamed)).optJSONObject(0)
                .optString("digest", "").equals(first.optString("digest", "x")),
                "and moves with an edit");
    }

    private static void testValidateNewRefusals() {
        integrations();
        Automations.Rule empty = new Automations.Rule();
        require("add a When".equals(Automations.validate(empty, names())), "no When");
        Automations.Rule many = new Automations.Rule();
        for (int index = 0; index < 7; index++) {
            many.whens.add(when("proximity", "near", "display_on"));
        }
        require("at most 6 Whens".equals(Automations.validate(many, names())), "seven Whens");
        Automations.Rule bare = new Automations.Rule();
        bare.whens.add(when("proximity", "near"));
        require("add an action".equals(Automations.validate(bare, names())), "no action");
        Automations.Rule eleven = new Automations.Rule();
        eleven.whens.add(when("proximity", "near"));
        for (int index = 0; index < 11; index++) {
            w(eleven).then.add(step("display_on"));
        }
        require("a When has at most 10 actions".equals(Automations.validate(eleven, names())),
                "eleven actions");
        Automations.Rule total = new Automations.Rule();
        for (int count : new int[] {10, 10, 1}) {
            Automations.When one = when("proximity", "near");
            for (int index = 0; index < count; index++) {
                one.then.add(step("display_on"));
            }
            total.whens.add(one);
        }
        require("at most 20 actions in one automation".equals(Automations.validate(total, names())),
                "twenty-one actions");
        Automations.Rule zero = rule("proximity", "near", "display_on");
        w(zero).then.add(0, waitFor(0));
        require("action 1: the wait must be 1 to 3600 seconds".equals(
                Automations.validate(zero, names())), Automations.validate(zero, names()));
        Automations.Rule trailing = rule("proximity", "near", "display_on");
        w(trailing).then.add(waitFor(10));
        require("action 2: a wait needs an action after it".equals(
                Automations.validate(trailing, names())), "a Wait last is refused");
        Automations.Rule leading = rule("proximity", "near", "display_on");
        w(leading).then.add(0, waitFor(10));
        require(Automations.validate(leading, names()) == null, "a Wait first is a delay");
        Automations.Rule twoWhens = rule("proximity", "near", "display_on");
        Automations.When second = when("proximity", "far", "display_off", "open_page");
        second.then.get(1).argument = "home.local";
        twoWhens.whens.add(second);
        require("when 2, action 2: the address must start with http:// or https://".equals(
                Automations.validate(twoWhens, names())), Automations.validate(twoWhens, names()));
        Automations.Rule twoActions = rule("proximity", "near", "display_on");
        w(twoActions).then.add(step("say"));
        require("action 2: the sentence is missing".equals(
                Automations.validate(twoActions, names())), "one When, two actions");
        Automations.Rule twoSecond = rule("proximity", "near", "display_on");
        twoSecond.whens.add(when("pressure", "x", "display_on"));
        require("when 2: choose a sensor".equals(Automations.validate(twoSecond, names())),
                "two Whens, one action each");

        Automations.Rule conditions = rule("proximity", "near", "display_on");
        for (int index = 0; index < 7; index++) {
            conditions.conditions.add(state("display", "on"));
        }
        require("at most 6 conditions".equals(Automations.validate(conditions, names())),
                "seven conditions");
        String[][] refused = {
                {"", "", "choose a sensor for the condition"},
                {"pressure", "x", "choose a sensor for the condition"},
                {"proximity", "x", "choose the state"},
                {"nfc_tag", "tag", "choose the state"},
                {Automations.CAMERAS, "triggered", "choose the state"},
                {HEIGHT, "int_changes", "choose the state"},
                {"illuminance", "darker", "the level must be a number"},
        };
        for (String[] c : refused) {
            Automations.Rule one = rule("proximity", "near", "display_on");
            one.conditions.add(state(c[0], c[1]));
            require(c[2].equals(Automations.validate(one, names())),
                    c[0] + "/" + c[1] + ": " + Automations.validate(one, names()));
        }
        Automations.Rule twoConditions = rule("proximity", "near", "display_on");
        twoConditions.conditions.add(state("display", "on"));
        twoConditions.conditions.add(state("display", "dim"));
        require("if 2: choose the state".equals(Automations.validate(twoConditions, names())),
                "the condition's place");
        Object[][] times = {
                {480, 1080, 0, "choose at least one day"},
                {480, -1, Automations.ALL_DAYS, "the times must be like 07:00"},
                {480, 480, Automations.ALL_DAYS, "the time window ends when it starts"},
                {1500, 480, Automations.ALL_DAYS, "the time window is not a time of day"},
                {-1, -1, 1 << 6, null},
        };
        for (Object[] c : times) {
            Automations.Rule one = rule("proximity", "near", "display_on");
            one.conditions.add(window((Integer) c[0], (Integer) c[1], (Integer) c[2]));
            String problem = Automations.validate(one, names());
            require(c[3] == null ? problem == null : c[3].equals(problem), c[3] + ": " + problem);
        }
        Automations.Rule kept = rule("proximity", "near", "display_on");
        Automations.Condition level = state("illuminance", "darker");
        level.level = 30;
        level.from = 5;
        kept.conditions.add(level);
        require(Automations.validate(kept, names()) == null && level.level == 30 && level.from == -1,
                "a state condition keeps its level, drops what it has no use for");
        require(Automations.sentence(kept).endsWith(", if 1 condition holds"),
                Automations.sentence(kept));

        List<Automations.Rule> tooMany = new ArrayList<>();
        for (int index = 0; index <= Automations.MAX_RULES; index++) {
            tooMany.add(rule("proximity", "near", "display_on"));
        }
        require("at most 64 automations".equals(Automations.checkList(tooMany)), "65 rules");
        List<Automations.Rule> tooLong = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            Automations.Rule big = new Automations.Rule();
            big.whens.add(when("proximity", "near"));
            for (int count = 0; count < 20; count++) {
                Automations.Step say = step("say");
                say.argument = "x".repeat(Automations.MAX_ARGUMENT);
                w(big).then.add(say);
            }
            tooLong.add(big);
        }
        require("the automations are too long together: shorten or remove some".equals(
                Automations.checkList(tooLong)), "too long together");
        Automations.Rule unnamed = rule("proximity", "near", "say");
        unnamed.name = "";
        s(unnamed).argument = "A sentence long enough to run past the sixty characters of a name";
        require(Automations.validate(unnamed, names()) == null
                && unnamed.name.codePointCount(0, unnamed.name.length()) == Automations.MAX_NAME
                && unnamed.name.endsWith("…"), "a blank name is the sentence, shortened: "
                + unnamed.name);
    }

    /** One rule, two Whens: each runs its own actions. */
    private static void testTwoWhensOneRule() {
        Rig rig = new Rig();
        Automations.Rule both = rule("proximity", "near", "display_on");
        both.whens.add(when("proximity", "far", "display_off", "reload"));
        rig.engine.rules(Collections.singletonList(both));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        require(rig.steps.isEmpty(), "the first reading seeds both");
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(rig.steps.equals(Collections.singletonList("r1:display_on")), "near runs When 1");
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 2000, 600);
        require(rig.steps.equals(java.util.Arrays.asList("r1:display_on", "r1:display_off",
                "r1:reload")), "far runs When 2's actions in order: " + rig.steps);
        require(Automations.sentence(both).equals(
                "Near, then Display on; Far, then Display off and 1 more"),
                Automations.sentence(both));
        require(both.sensors().size() == 1, "one sensor, named once");
    }

    /** Changes: any new value, unavailable passed over, its own "not again within". */
    private static void testChangesTrigger() throws org.json.JSONException {
        integrations();
        Automations.Event changes = Automations.event(HEIGHT, "int_changes");
        require(changes != null && changes.changes && Automations.cools(changes)
                && !Automations.conditionEvent(changes), "a number has Changes");
        require(Automations.event(ROOM, "int_changes") != null, "a text has Changes");
        require(Automations.event(KUMA, "int_changes") == null, "a flag has no Changes");
        require(Automations.defaultCooldown(changes) == 0
                && Automations.defaultCooldown(Automations.event("nfc_tag", "tag")) == 60,
                "a Changes starts at 0 s, a tag at 60 s");
        Automations.Rule parsed = Automations.parseRule(new org.json.JSONObject("{\"id\":\"p\","
                + "\"when\":[{\"sensor\":\"" + HEIGHT + "\",\"event\":\"int_changes\",\"then\":"
                + "[{\"action\":\"reload\"}]},{\"sensor\":\"nfc_tag\",\"event\":\"tag\",\"then\":"
                + "[{\"action\":\"reload\"}]}]}"));
        require(parsed.whens.get(0).cooldown == 0 && parsed.whens.get(1).cooldown == 60,
                "without the key a When takes its event's default");

        Rig rig = new Rig();
        Automations.Rule block = rule(HEIGHT, "int_changes", "reload");
        w(block).cooldown = 10;
        require(Automations.validate(block, names()) == null && w(block).cooldown == 10,
                "a Changes keeps its not again within");
        require(Automations.sentence(block).equals(
                "Changes, then Reload dashboard, not again within 10 s"), Automations.sentence(block));
        Automations.Rule room = rule(ROOM, "int_changes", "display_on");
        room.id = "r2";
        w(room).cooldown = 0;
        rig.engine.rules(java.util.Arrays.asList(block, room));
        rig.sample(HEIGHT, Integrations.Value.unavailable(Integrations.Type.NUMBER).sample(),
                T0, 600);
        rig.sample(HEIGHT, Automations.Sample.of(100), T0 + 1000, 600);
        require(rig.ran.isEmpty(), "the first available value only seeds");
        rig.sample(HEIGHT, Automations.Sample.of(100), T0 + 2000, 600);
        require(rig.ran.isEmpty(), "the same value again is no change");
        rig.sample(HEIGHT, Automations.Sample.of(101), T0 + 3000, 600);
        require(rig.ran.size() == 1, "a new value runs");
        rig.sample(HEIGHT, Automations.Sample.of(102), T0 + 5000, 600);
        require(rig.ran.size() == 1, "inside its 10 s it waits");
        rig.sample(HEIGHT, Integrations.Value.unavailable(Integrations.Type.NUMBER).sample(),
                T0 + 20_000, 600);
        rig.sample(HEIGHT, Automations.Sample.of(102), T0 + 21_000, 600);
        require(rig.ran.size() == 1, "the waited-out value was remembered, unavailable passed over");
        rig.sample(HEIGHT, Automations.Sample.of(103), T0 + 22_000, 600);
        require(rig.ran.size() == 2, "after its 10 s the next change runs");
        rig.sample(ROOM, Automations.Sample.ofText("Free"), T0 + 23_000, 600);
        rig.sample(ROOM, Automations.Sample.ofText("free"), T0 + 24_000, 600);
        require(rig.ran.size() == 2, "a text in other case is no change");
        rig.sample(ROOM, Automations.Sample.ofText("Busy"), T0 + 25_000, 600);
        rig.sample(ROOM, Automations.Sample.ofText("Free"), T0 + 25_500, 600);
        require(rig.ran.size() == 4 && rig.ran.get(3).equals("r2"),
                "a cooldown of 0 runs on every change");
        // A panel asleep across many changes sees one new value when it wakes: one run.
        rig.sample(HEIGHT, Automations.Sample.of(109), T0 + 600_000, 600);
        require(rig.ran.size() == 5, "a sleep across six changes runs once");
        // An edit seeds again.
        Automations.Rule edited = block.copy();
        w(edited).cooldown = 0;
        rig.engine.rules(java.util.Arrays.asList(edited, room));
        rig.sample(HEIGHT, Automations.Sample.of(110), T0 + 700_000, 600);
        require(rig.ran.size() == 5, "after an edit the first value only seeds");
    }

    /** If: checked once at the start, all or any, the edge spent either way. */
    private static void testConditionsAtStart() {
        Rig rig = new Rig();
        Automations.Rule lit = rule("proximity", "near", "light_on");
        lit.conditions.add(state("display", "on"));
        rig.engine.rules(Collections.singletonList(lit));
        rig.engine.note("display", Automations.Sample.of(false));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(rig.ran.isEmpty(), "the display is off: not run");
        rig.engine.note("display", Automations.Sample.of(true));
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 2000, 600);
        require(rig.ran.isEmpty(), "the edge was spent: no late run when the condition comes true");
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 3000, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 4000, 600);
        require(rig.ran.size() == 1, "the next edge with the display on runs");

        // All of these against any of these: the display on, but outside the window.
        Automations.Rule all = rule("proximity", "near", "light_on");
        all.id = "all";
        all.conditions.add(state("display", "on"));
        all.conditions.add(window(8 * 60, 18 * 60, Automations.ALL_DAYS));
        Automations.Rule any = all.copy();
        any.id = "any";
        any.anyCondition = true;
        Rig two = new Rig();
        two.engine.rules(java.util.Arrays.asList(all, any));
        two.engine.note("display", Automations.Sample.of(true));
        two.sample("proximity", Automations.Sample.of(5, false), T0, 20 * 60);
        two.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 20 * 60);
        require(two.ran.equals(Collections.singletonList("any")), "any runs, all does not");
        // A sensor never read, or unavailable, does not hold.
        Automations.Rule unread = rule("proximity", "near", "light_on");
        unread.conditions.add(state("illuminance", "brighter"));
        unread.conditions.get(0).level = 5;
        Rig three = new Rig();
        three.engine.rules(Collections.singletonList(unread));
        three.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        three.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(three.ran.isEmpty(), "a condition on a sensor with no reading does not hold");

        // Not checked again after a Wait.
        Automations.Rule later = rule("proximity", "near", "light_on");
        w(later).then.add(0, waitFor(10));
        later.conditions.add(state("display", "on"));
        Rig four = new Rig();
        four.engine.rules(Collections.singletonList(later));
        four.engine.note("display", Automations.Sample.of(true));
        four.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        four.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        four.engine.note("display", Automations.Sample.of(false));
        four.at(T0 + 11_000);
        require(four.ran.size() == 1, "the conditions held at the start; the Wait does not ask again");
    }

    /** A Wait pauses the rest; a new start while it waits starts over. */
    private static void testWaitAndRestart() {
        Rig rig = new Rig();
        Automations.Rule flash = rule("proximity", "near", "light_on");
        w(flash).then.add(waitFor(10));
        w(flash).then.add(step("light_off"));
        rig.engine.rules(Collections.singletonList(flash));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(rig.steps.equals(Collections.singletonList("r1:light_on")), "the first action at once");
        rig.at(T0 + 6000);
        require(rig.steps.size() == 1, "nothing during the Wait");
        rig.at(T0 + 11_000);
        require(rig.steps.equals(java.util.Arrays.asList("r1:light_on", "r1:light_off")),
                "the rest after it: " + rig.steps);
        // Started again in the middle of a Wait: from the top, the first run's rest dropped.
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 20_000, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 21_000, 600);
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 24_000, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 25_000, 600);
        rig.at(T0 + 31_000);
        require(rig.steps.size() == 4, "the first run's rest is dropped: " + rig.steps);
        rig.at(T0 + 35_000);
        require(rig.steps.equals(java.util.Arrays.asList("r1:light_on", "r1:light_off",
                "r1:light_on", "r1:light_on", "r1:light_off")), "one light off, the restart's: "
                + rig.steps);
        require(rig.notes.isEmpty(), "nothing was late: " + rig.notes);
    }

    /**
     * A run stops before its next action when the rule is edited, switched off or deleted (here
     * in a Wait); not on a reload.
     */
    private static void testWaitCancelledByEditDisableDelete() {
        Automations.Rule flash = rule("proximity", "near", "light_on");
        w(flash).then.add(waitFor(10));
        w(flash).then.add(step("light_off"));
        Automations.Rule edited = flash.copy();
        w(edited).then.get(0).argument = "255,0,0";
        Automations.Rule off = flash.copy();
        off.enabled = false;
        Object[][] cases = {
                {Collections.singletonList(edited), 1, "an edit"},
                {Collections.singletonList(off), 1, "a switch off"},
                {Collections.<Automations.Rule>emptyList(), 1, "a delete"},
                {Collections.singletonList(flash.copy()), 2, "a reload of the same rule"},
        };
        for (Object[] c : cases) {
            Rig rig = new Rig();
            rig.engine.rules(Collections.singletonList(flash));
            rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
            rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
            @SuppressWarnings("unchecked")
            List<Automations.Rule> next = (List<Automations.Rule>) c[0];
            rig.engine.rules(next);
            rig.at(T0 + 20_000);
            require(rig.steps.size() == (Integer) c[1], c[2] + ": " + rig.steps);
        }
    }

    /** A Wait the panel slept through: the rest is dropped and said so, never run late. */
    private static void testLateContinuationDropped() {
        Rig rig = new Rig();
        Automations.Rule flash = rule("proximity", "near", "light_on");
        w(flash).then.add(waitFor(10));
        w(flash).then.add(step("light_off"));
        rig.engine.rules(Collections.singletonList(flash));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        // The thread wakes 31 s after the Wait ended.
        rig.at(T0 + 11_000 + Automations.LATE_MS + 1000);
        require(rig.steps.size() == 1 && rig.notes.size() == 1
                && rig.notes.get(0).contains("the rest dropped"), "late: " + rig.notes);
        // Late within the 30 s: still run.
        Rig near = new Rig();
        near.engine.rules(Collections.singletonList(flash));
        near.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        near.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        near.at(T0 + 11_000 + Automations.LATE_MS);
        require(near.steps.size() == 2 && near.notes.isEmpty(), "on time enough: " + near.steps);
    }

    /** An edit to one When starts that When over; the other keeps what it has seen. */
    private static void testWhenStateReseededOnlyForChangedWhen() {
        Rig rig = new Rig();
        Automations.Rule two = rule("illuminance", "darker", "display_off");
        w(two).level = 5;
        two.whens.add(when("proximity", "near", "display_on"));
        rig.engine.rules(Collections.singletonList(two));
        rig.sample("illuminance", Automations.Sample.of(20), T0, 600);
        rig.sample("proximity", Automations.Sample.of(5, false), T0 + 1000, 600);
        Automations.Rule edited = two.copy();
        w(edited).level = 50;
        rig.engine.rules(Collections.singletonList(edited));
        rig.sample("illuminance", Automations.Sample.of(20), T0 + 2000, 600);
        require(rig.ran.isEmpty(), "the edited When is seeded again, not fired");
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 3000, 600);
        require(rig.steps.equals(Collections.singletonList("r1:display_on")),
                "the untouched When kept its state, so its first edge fires: " + rig.steps);
    }

    /**
     * An If on the When's own sensor: it sees the reading that started the When, and one that
     * can never hold then is refused.
     */
    private static void testIfOnTheWhensOwnSensor() {
        integrations();
        // The reading that starts the When is the one the If is asked about.
        Automations.Rule near = rule("proximity", "near", "display_on");
        near.conditions.add(state("proximity", "near"));
        require(Automations.validate(near, names()) == null, "a redundant If is allowed");
        Automations.Rule between = rule("illuminance", "darker", "display_on");
        w(between).level = 10;
        Automations.Condition brighter = state("illuminance", "brighter");
        brighter.level = 5;
        between.conditions.add(brighter);
        require(Automations.validate(between, names()) == null, "darker than 10, if brighter than 5");
        Rig rig = new Rig();
        rig.engine.rules(java.util.Arrays.asList(near, between));
        rig.sample("proximity", Automations.Sample.of(5, false), T0, 600);
        rig.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        rig.sample("illuminance", Automations.Sample.of(50), T0 + 2000, 600);
        rig.sample("illuminance", Automations.Sample.of(7), T0 + 3000, 600);
        require(rig.ran.size() == 2, "both If see the reading that started their When: " + rig.ran);

        // Refused: the If can never hold when the When starts.
        Automations.Rule display = rule("display", "off", "light_on");
        display.conditions.add(state("display", "on"));
        require("the If can never hold when this When starts".equals(
                Automations.validate(display, names())), Automations.validate(display, names()));
        Automations.Rule kuma = rule(KUMA, "int_on", "light_on");
        kuma.conditions.add(state(KUMA, "int_off"));
        require("the If can never hold when this When starts".equals(
                Automations.validate(kuma, names())), "a value on and off at once");
        Automations.Rule apart = rule("illuminance", "darker", "display_on");
        w(apart).level = 5;
        Automations.Condition over = state("illuminance", "brighter");
        over.level = 10;
        apart.conditions.add(over);
        require(Automations.validate(apart, names()) != null, "darker than 5, if brighter than 10");
        Automations.Rule text = rule(ROOM, "int_becomes", "light_on");
        w(text).match = "Busy";
        Automations.Condition free = state(ROOM, "int_becomes");
        free.match = "Free";
        text.conditions.add(free);
        require(Automations.validate(text, names()) != null, "becomes Busy, if is Free");
        free.match = "busy";
        require(Automations.validate(text, names()) == null, "becomes Busy, if is busy");
        // The places named when there are several.
        Automations.Rule two = rule("proximity", "near", "light_on");
        two.whens.add(when("display", "off", "light_off"));
        two.conditions.add(state("proximity", "near"));
        two.conditions.add(state("display", "on"));
        require("if 2 can never hold when when 2 starts".equals(Automations.validate(two, names())),
                Automations.validate(two, names()));
    }

    /**
     * Several Whens of one rule due on one reading: one run, each action list started at once in
     * When order; a Wait in the first delays only the rest of the first.
     */
    private static void testWhensDueTogether() {
        integrations();
        Automations.Rule both = rule(HEIGHT, "int_changes", "light_on");
        w(both).then.add(step("reload"));
        w(both).cooldown = 0;
        Automations.When above = when(HEIGHT, "int_above", "display_on");
        above.level = 10;
        both.whens.add(above);
        Rig rig = new Rig();
        rig.engine.rules(Collections.singletonList(both));
        rig.sample(HEIGHT, Automations.Sample.of(5), T0, 600);
        rig.sample(HEIGHT, Automations.Sample.of(20), T0 + 1000, 600);
        require(rig.steps.equals(java.util.Arrays.asList("r1:light_on", "r1:reload",
                "r1:display_on")), "both lists, in When order: " + rig.steps);

        Automations.Rule waiting = rule(HEIGHT, "int_changes", "light_on");
        w(waiting).cooldown = 0;
        w(waiting).then.add(waitFor(10));
        w(waiting).then.add(step("light_off"));
        Automations.When over = when(HEIGHT, "int_above", "display_on");
        over.level = 10;
        waiting.whens.add(over);
        Rig later = new Rig();
        later.engine.rules(Collections.singletonList(waiting));
        later.sample(HEIGHT, Automations.Sample.of(5), T0, 600);
        later.sample(HEIGHT, Automations.Sample.of(20), T0 + 1000, 600);
        require(later.steps.equals(java.util.Arrays.asList("r1:light_on", "r1:display_on")),
                "the second list starts at once, the first's Wait delays only its own rest: "
                        + later.steps);
        later.at(T0 + 11_000);
        require(later.steps.equals(java.util.Arrays.asList("r1:light_on", "r1:display_on",
                "r1:light_off")), "then the rest of the first list: " + later.steps);
        // A newer start stops every list of the older run, the one in its Wait included.
        later.sample(HEIGHT, Automations.Sample.of(5), T0 + 20_000, 600);
        later.at(T0 + 25_000);
        later.engine.rules(Collections.<Automations.Rule>emptyList());
        later.at(T0 + 40_000);
        require(later.steps.equals(java.util.Arrays.asList("r1:light_on", "r1:display_on",
                "r1:light_off", "r1:light_on")), "a delete ends the run in its Wait: "
                + later.steps);
    }

    /**
     * The web editor's structure changes: each adds, removes or moves one part, keeps the
     * objects (so a moved action carries what was typed in it), and changes nothing at a limit,
     * at an end, or for an op it does not know. Then the Uptime Kuma rule built by them alone.
     */
    private static void testEditorOps() throws org.json.JSONException {
        integrations();
        Automations.Rule rule = rule("proximity", "near", "display_on");
        Automations.Step first = rule.whens.get(0).then.get(0);
        require(Automations.applyOp(rule, "add_action:0"), "an action is added");
        Automations.Step second = rule.whens.get(0).then.get(1);
        require(Automations.applyOp(rule, "up:0:1"), "the second moves up");
        require(rule.whens.get(0).then.get(0) == second && rule.whens.get(0).then.get(1) == first,
                "the same objects, swapped");
        require(!Automations.applyOp(rule, "up:0:0"), "the first cannot move up");
        require(!Automations.applyOp(rule, "down:0:1"), "the last cannot move down");
        require(Automations.applyOp(rule, "down:0:0"), "the first moves down");
        require(rule.whens.get(0).then.get(0) == first, "back in order");
        require(Automations.applyOp(rule, "remove_action:0:1"), "an action is removed");
        require(rule.whens.get(0).then.size() == 1 && rule.whens.get(0).then.get(0) == first,
                "the other stays");
        require(!Automations.applyOp(rule, "remove_action:0:0"), "a When keeps its last action");
        require(!Automations.applyOp(rule, "remove_when:0"), "a rule keeps its last When");

        require(Automations.applyOp(rule, "add_when"), "a When is added");
        require(rule.whens.size() == 2 && rule.whens.get(1).then.size() == 1,
                "with one action of its own");
        require(Automations.applyOp(rule, "remove_when:0"), "the first When is removed");
        require(rule.whens.size() == 1 && rule.whens.get(0).sensor.isEmpty(),
                "the added one stays");

        require(Automations.applyOp(rule, "add_condition"), "a condition is added");
        Automations.Condition time = rule.conditions.get(0);
        require(Automations.TIME.equals(time.sensor) && !time.timed()
                        && time.days == Automations.ALL_DAYS,
                "a time of day, all day, every day: " + time.sensor);
        require(Automations.applyOp(rule, "remove_condition:0") && rule.conditions.isEmpty(),
                "and removed");
        require(!Automations.applyOp(rule, "remove_condition:0"), "none left to remove");

        for (String nonsense : new String[] {"", "save", "up:x:1", "up:0", "up:5:0", "down:0:-1",
                "add_action:3", "remove_when:1:2", "add_when:0", null}) {
            require(!Automations.applyOp(rule, nonsense), "not an op that applies: " + nonsense);
        }

        // The limits: Whens, actions per When, actions together, conditions.
        Automations.Rule full = rule("proximity", "near", "display_on");
        while (Automations.applyOp(full, "add_action:0")) {
            // up to the limit
        }
        require(w(full).then.size() == Automations.MAX_STEPS_PER_WHEN,
                "actions per When: " + w(full).then.size());
        while (Automations.applyOp(full, "add_when")) {
            // up to the limit
        }
        require(full.whens.size() == Automations.MAX_WHENS, "Whens: " + full.whens.size());
        while (Automations.applyOp(full, "add_action:1")) {
            // up to the limit
        }
        require(Automations.stepCount(full) == Automations.MAX_STEPS,
                "actions together: " + Automations.stepCount(full));
        require(!Automations.applyOp(full, "add_action:2"), "no action past the total");
        while (Automations.applyOp(full, "add_condition")) {
            // up to the limit
        }
        require(full.conditions.size() == Automations.MAX_CONDITIONS,
                "conditions: " + full.conditions.size());

        // The Uptime Kuma rule as a person builds it: a When, two more actions, a second When
        // with one more, a time condition.
        Automations.Rule kuma = new Automations.Rule();
        kuma.name = "A service is down";
        kuma.whens.add(when(KUMA, "int_on", "light_on"));
        require(Automations.applyOp(kuma, "add_action:0") && Automations.applyOp(kuma, "add_action:0")
                && Automations.applyOp(kuma, "add_when") && Automations.applyOp(kuma, "add_action:1")
                && Automations.applyOp(kuma, "add_condition"), "every op applies");
        Automations.When down = kuma.whens.get(0);
        down.cooldown = 0;
        down.then.get(0).argument = "255,0,0";
        down.then.get(1).action = "reload";
        down.then.get(2).action = "popup";
        down.then.get(2).argument = "A service is down. Check the status page.";
        down.then.get(2).seconds = 30;
        down.then.get(2).chime = true;
        // The pop-up before the reload, as the plan's rule has it.
        require(Automations.applyOp(kuma, "up:0:2"), "the pop-up moves up");
        Automations.When up = kuma.whens.get(1);
        up.sensor = KUMA;
        up.event = "int_off";
        up.cooldown = 0;
        up.then.get(0).action = "light_off";
        up.then.get(1).action = "reload";
        Automations.Condition hours = kuma.conditions.get(0);
        hours.from = 8 * 60;
        hours.to = 18 * 60;
        hours.days = 0x1F;
        require(Automations.validate(kuma, names()) == null,
                "valid: " + Automations.validate(kuma, names()));
        org.json.JSONObject stored = Automations.toJson(Collections.singletonList(kuma))
                .optJSONObject(0);
        org.json.JSONArray steps = stored.optJSONArray("when").optJSONObject(0)
                .optJSONArray("then");
        require(steps.optJSONObject(0).optString("action", "").equals("light_on")
                        && steps.optJSONObject(1).optString("action", "").equals("popup")
                        && steps.optJSONObject(2).optString("action", "").equals("reload"),
                "light, pop-up, reload: " + steps);
        require(stored.optJSONArray("if").optJSONObject(0).optJSONArray("days").length() == 5,
                "Monday to Friday: " + stored);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
