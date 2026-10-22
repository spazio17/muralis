/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Integrations' model and how the automations act on their values (no backlog, no unavailable). */
public final class IntegrationsTest {

    public static void main(String[] args) throws Exception {
        // Sensor ids: only what MQTT topics and discovery object ids take.
        String down = Integrations.sensorId("mon1", "down_count");
        require(down.equals("int_mon1_down_count") && down.matches("[a-z0-9_]+"), "id shape");
        String[] parts = Integrations.parseSensorId(down);
        require(parts != null && parts[0].equals("mon1") && parts[1].equals("down_count"),
                "the id does not read back");
        require(Integrations.parseSensorId("int_mon1") == null
                && Integrations.parseSensorId("int_MON_x") == null
                && Integrations.parseSensorId("light") == null
                && Integrations.parseSensorId("int_" + "a".repeat(13) + "_x") == null,
                "a malformed id was taken");

        Integrations.ValueSpec count = new Integrations.ValueSpec("down_count", "Monitors down",
                Integrations.Type.NUMBER, "");
        Integrations.ValueSpec any = new Integrations.ValueSpec("any_down", "Any monitor down",
                Integrations.Type.FLAG, "");
        Integrations.ValueSpec room = new Integrations.ValueSpec("room", "Room",
                Integrations.Type.TEXT, "");
        Integrations.lookup = id -> id.equals(down) ? count
                : id.equals(Integrations.sensorId("mon1", "any_down")) ? any
                : id.equals(Integrations.sensorId("cal1", "room")) ? room : null;
        require(Automations.event(down, "int_above") != null
                && Automations.event(down, "int_on") == null
                && Automations.event("int_zzz_x", "int_above") == null,
                "events by the value's type");

        List<String> ran = new ArrayList<>();
        Automations.Engine engine = new Automations.Engine(rule -> ran.add(rule.id));
        Automations.Rule red = rule("red", down, "int_above", 0, "");
        Automations.Rule busy = rule("busy", Integrations.sensorId("cal1", "room"), "int_becomes",
                Double.NaN, "Busy");
        engine.rules(java.util.Arrays.asList(red, busy));

        // The first value only sets the baseline: a service already down at start fires nothing.
        feed(engine, down, Integrations.Value.of(2));
        require(ran.isEmpty(), "the first value fired");
        feed(engine, down, Integrations.Value.of(0));
        feed(engine, down, Integrations.Value.unavailable(Integrations.Type.NUMBER));
        require(ran.isEmpty(), "unavailable fired");
        feed(engine, down, Integrations.Value.of(3));
        require(ran.equals(Collections.singletonList("red")),
                "0, unavailable, 3 did not fire once: " + ran);
        feed(engine, down, Integrations.Value.unavailable(Integrations.Type.NUMBER));
        feed(engine, down, Integrations.Value.of(3));
        require(ran.size() == 1, "3, unavailable, 3 fired again");

        // Text: once on the change to the matched text, not again while it stays.
        String roomId = Integrations.sensorId("cal1", "room");
        feed(engine, roomId, Integrations.Value.of("Free"));
        feed(engine, roomId, Integrations.Value.of("busy"));
        feed(engine, roomId, Integrations.Value.of("Busy"));
        require(ran.equals(java.util.Arrays.asList("red", "busy")), "becomes: " + ran);

        // Validation: a text rule keeps its match, an over-long one is refused in its own words.
        java.util.Map<String, String> names = Collections.singletonMap(roomId, "Room");
        Automations.Rule text = rule("t", roomId, "int_becomes", Double.NaN, "Busy");
        text.action = "light_off";
        require(Automations.validate(text, names) == null && text.match.equals("Busy"),
                "a text rule lost its match");
        text.match = "x".repeat(65);
        require("the text is too long".equals(Automations.validate(text, names)),
                "the text limit says tag");

        // A count has a level and no unit: the sentence carries no stray space, and a level below
        // zero is a number an integration may report.
        java.util.Map<String, String> counted = Collections.singletonMap(down, "Monitors down");
        Automations.Rule over = rule("o", down, "int_above", 0, "");
        require(Automations.validate(over, counted) == null
                && over.name.endsWith("Rises above 0, then Status light on"),
                "the unitless sentence: " + over.name);
        over.level = -5;
        require(Automations.validate(over, counted) == null, "a negative level was refused");

        // A stored rule keeps its match, and an older rule's "tag" still reads.
        Automations.Rule stored = Automations.parseRule(new org.json.JSONObject()
                .put("id", "nfc").put("sensor", "nfc").put("event", "tag").put("tag", "04A3")
                .put("action", "display_on"));
        require(stored.match.equals("04A3"), "an older NFC rule lost its tag");
        System.out.println("IntegrationsTest passed");
    }

    private static Automations.Rule rule(String id, String sensor, String event, double level,
            String match) {
        Automations.Rule rule = new Automations.Rule();
        rule.id = id;
        rule.sensor = sensor;
        rule.event = event;
        rule.level = level;
        rule.match = match;
        rule.action = "light_on";
        return rule;
    }

    private static void feed(Automations.Engine engine, String sensor, Integrations.Value value) {
        engine.sample(sensor, value.sample(), 1_000L, 600);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
