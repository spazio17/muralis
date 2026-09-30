/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The automations on the host: the vocabulary, the storage round trip, the rule book, and
 * the engine's seed-then-arm logic, each a case that would otherwise need a sensor and a
 * stopwatch beside a tablet.
 */
public final class AutomationsTest {
    private static final long T0 = 1_000_000L;

    public static void main(String[] args) {
        testStoreRoundTrip();
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
        System.out.println("AutomationsTest passed");
    }

    private static Map<String, String> names() {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("proximity", "Proximity");
        names.put("light", "Light");
        names.put("nfc", "NFC");
        names.put("battery", "Battery");
        return names;
    }

    private static Automations.Rule rule(String sensor, String event, String action) {
        Automations.Rule rule = new Automations.Rule();
        rule.id = "r1";
        rule.name = "Test";
        rule.sensor = sensor;
        rule.event = event;
        rule.action = action;
        return rule;
    }

    private static void testStoreRoundTrip() {
        List<Automations.Rule> rules = Automations.defaults();
        require(rules.size() == 1 && rules.get(0).id.equals("wake"), "the shipped rule is Wake by hand");
        Automations.Rule window = rule("light", "darker", "say");
        window.level = 12.5;
        window.minutes = 3;
        window.argument = "It is dark";
        window.onlyFrom = 22 * 60;
        window.onlyTo = 6 * 60;
        window.enabled = false;
        rules.add(window);
        List<Automations.Rule> back = Automations.parse(Automations.store(rules));
        require(back.size() == 2, "two rules stored and read back");
        Automations.Rule read = back.get(1);
        require(read.level == 12.5 && read.minutes == 3 && read.argument.equals("It is dark")
                && read.onlyFrom == 22 * 60 && read.onlyTo == 6 * 60 && !read.enabled,
                "every field survives the round trip");
        require(Double.isNaN(back.get(0).level), "a rule without a level reads NaN");
        require(Automations.parse(null).size() == 1, "nothing stored gives the shipped rule");
        require(Automations.parse("not json").isEmpty(), "a broken document gives no rules");
        require(Automations.find(back, "r1") == read && Automations.find(back, "zz") == null, "find by id");
        String fresh = Automations.newId(back);
        require(fresh.length() == 6 && Automations.find(back, fresh) == null, "a new id is free");
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
        Automations.Rule minutes = rule("light", "darker", "display_on");
        minutes.level = 5;
        minutes.minutes = -1;
        require(Automations.validate(minutes, names()) != null, "negative minutes refused");
        minutes.minutes = Automations.MAX_MINUTES + 1;
        require(Automations.validate(minutes, names()) != null, "minutes over a day refused");
        minutes.minutes = 0;
        require(Automations.validate(minutes, names()) == null, "zero minutes is at once");
        Automations.Rule noLevel = rule("light", "darker", "display_on");
        require(Automations.validate(noLevel, names()) != null, "an event with a level needs one");
        Automations.Rule say = rule("proximity", "near", "say");
        require(Automations.validate(say, names()) != null, "say needs a sentence");
        say.argument = "Hello";
        require(Automations.validate(say, names()) == null, "say with a sentence passes");
        Automations.Rule sound = rule("proximity", "near", "play_sound");
        sound.argument = "ftp://x/y.mp3";
        require(Automations.validate(sound, names()) != null, "a sound needs http or https");
        Automations.Rule equal = rule("proximity", "near", "display_on");
        equal.onlyFrom = 7 * 60;
        equal.onlyTo = 7 * 60;
        require(Automations.validate(equal, names()) != null, "a window ending when it starts is refused");
        Automations.Rule absent = rule("pressure", "x", "display_on");
        require(Automations.validate(absent, names()) != null, "a sensor this panel lacks is refused");
    }

    private static void testWindow() {
        Automations.Rule night = rule("proximity", "near", "display_on");
        night.onlyFrom = Automations.minutesOf("22:00");
        night.onlyTo = Automations.minutesOf("06:00");
        require(Automations.inWindow(night, 23 * 60) && Automations.inWindow(night, 5 * 60)
                && !Automations.inWindow(night, 12 * 60), "a window across midnight");
        require(Automations.inWindow(night, 22 * 60) && !Automations.inWindow(night, 6 * 60),
                "the start is inside, the end is outside");
        require(Automations.minutesOf("7am") < 0 && Automations.minutesOf("25:00") < 0,
                "times are hh:mm");
        require(Automations.clock(6 * 60 + 5).equals("06:05"), "clock prints hh:mm");
    }

    private static final class Runs implements Automations.Runner {
        final List<String> ran = new ArrayList<>();

        @Override
        public void run(Automations.Rule rule) {
            ran.add(rule.id);
        }
    }

    private static void testEngineSeedsThenArms() {
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        engine.rules(Collections.singletonList(rule("proximity", "near", "display_on")));
        engine.sample("proximity", Automations.Sample.of(0, true), T0, 600);
        require(runs.ran.isEmpty(), "a rule made while its condition holds does not fire on the spot");
        engine.sample("proximity", Automations.Sample.of(5, false), T0 + 1000, 600);
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 2000, 600);
        require(runs.ran.equals(Collections.singletonList("r1")), "false then true fires once");
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 3000, 600);
        require(runs.ran.size() == 1, "the same true again does not fire again");
        engine.sample("light", Automations.Sample.of(3), T0 + 4000, 600);
        require(runs.ran.size() == 1, "another sensor's sample is not this rule's");
    }

    private static void testEngineHoldsForMinutes() {
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        Automations.Rule dark = rule("light", "darker", "display_off");
        dark.level = 10;
        dark.minutes = 2;
        engine.rules(Collections.singletonList(dark));
        engine.sample("light", Automations.Sample.of(100), T0, 600);
        engine.sample("light", Automations.Sample.of(2), T0 + 1000, 600);
        engine.sample("light", Automations.Sample.of(2), T0 + 60_000, 600);
        require(runs.ran.isEmpty(), "not before the minutes have passed");
        engine.sample("light", Automations.Sample.of(2), T0 + 1000 + 2 * 60_000, 600);
        require(runs.ran.equals(Collections.singletonList("r1")), "fires once the minutes have held");
        engine.sample("light", Automations.Sample.of(50), T0 + 200_000, 600);
        engine.sample("light", Automations.Sample.of(2), T0 + 201_000, 600);
        engine.sample("light", Automations.Sample.of(2), T0 + 230_000, 600);
        require(runs.ran.size() == 1, "a new dark spell starts its own count");
    }

    private static void testEngineReseedsAnEditedRule() {
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        Automations.Rule dark = rule("light", "darker", "display_off");
        dark.level = 5;
        engine.rules(Collections.singletonList(dark));
        engine.sample("light", Automations.Sample.of(20), T0, 600);
        engine.sample("light", Automations.Sample.of(20), T0 + 1000, 600);
        Automations.Rule edited = dark.copy();
        edited.level = 50;
        engine.rules(Collections.singletonList(edited));
        engine.sample("light", Automations.Sample.of(20), T0 + 2000, 600);
        require(runs.ran.isEmpty(), "an edited rule whose condition now holds is seeded, not fired");
        engine.sample("light", Automations.Sample.of(80), T0 + 3000, 600);
        engine.sample("light", Automations.Sample.of(20), T0 + 4000, 600);
        require(runs.ran.equals(Collections.singletonList("r1")), "and fires on the next false-to-true");
        Automations.Rule renamed = edited.copy();
        renamed.name = "Another name";
        engine.rules(Collections.singletonList(renamed));
        engine.sample("light", Automations.Sample.of(20), T0 + 5000, 600);
        require(runs.ran.size() == 1, "a rename keeps the state: the same true does not fire again");
    }

    private static void testEngineSeedLetsTheFirstEventFire() {
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        engine.rules(Collections.singletonList(rule("proximity", "near", "display_on")));
        engine.seed("proximity", Automations.Sample.of(5, false), T0);
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 600);
        require(runs.ran.equals(Collections.singletonList("r1")),
                "seeded with the state as it stands, the first event that changes it fires");
        engine.seed("proximity", Automations.Sample.of(5, false), T0 + 2000);
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 3000, 600);
        require(runs.ran.size() == 1, "a seed never resets a rule that has already seen a state");
    }

    private static void testEngineTickAdvancesTheMinutes() {
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        Automations.Rule dark = rule("light", "darker", "display_off");
        dark.level = 5;
        dark.minutes = 2;
        engine.rules(Collections.singletonList(dark));
        engine.sample("light", Automations.Sample.of(100), T0, 600);
        engine.sample("light", Automations.Sample.of(2), T0 + 1000, 600);
        engine.tick(T0 + 60_000, 600);
        require(runs.ran.isEmpty(), "a tick before the minutes have passed fires nothing");
        engine.tick(T0 + 1000 + 2 * 60_000, 600);
        require(runs.ran.equals(Collections.singletonList("r1")),
                "a steady room sends no event, so the tick fires the rule once its minutes held");
        engine.tick(T0 + 1000 + 3 * 60_000, 600);
        require(runs.ran.size() == 1, "and not again on the next tick");
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
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        Automations.Rule wake = rule("proximity", "near", "display_on");
        Automations.Rule night = rule("proximity", "near", "say");
        night.id = "r2";
        night.argument = "x";
        night.onlyFrom = 22 * 60;
        night.onlyTo = 6 * 60;
        Automations.Rule off = rule("proximity", "near", "reload");
        off.id = "r3";
        off.enabled = false;
        engine.rules(java.util.Arrays.asList(wake, night, off));
        engine.sample("proximity", Automations.Sample.of(5, false), T0, 12 * 60);
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 1000, 12 * 60);
        require(runs.ran.equals(Collections.singletonList("r1")),
                "at noon only the unwindowed enabled rule runs");
        engine.reset("proximity");
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 2000, 23 * 60);
        require(runs.ran.size() == 1, "after a reset the first sample seeds again, firing nothing");
        engine.sample("proximity", Automations.Sample.of(5, false), T0 + 3000, 23 * 60);
        engine.sample("proximity", Automations.Sample.of(0, true), T0 + 4000, 23 * 60);
        require(runs.ran.size() == 3 && runs.ran.contains("r2") && !runs.ran.contains("r3"),
                "at night the windowed rule runs too, the disabled one never");
    }

    private static void testTagEvents() {
        Runs runs = new Runs();
        Automations.Engine engine = new Automations.Engine(runs);
        Automations.Rule any = rule("nfc", "tag", "display_on");
        Automations.Rule one = rule("nfc", "tag", "reload");
        one.id = "r2";
        one.tag = "04A32B9C";
        engine.rules(java.util.Arrays.asList(any, one));
        engine.event("nfc", "FFFF", 600);
        require(runs.ran.equals(Collections.singletonList("r1")), "any tag runs the open rule alone");
        engine.event("nfc", "04a32b9c", 600);
        require(runs.ran.size() == 3 && runs.ran.get(2).equals("r2"), "the named tag runs both, id case-blind");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
