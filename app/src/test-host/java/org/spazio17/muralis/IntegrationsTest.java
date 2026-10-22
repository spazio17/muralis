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

        instances();
        webValue();
        System.out.println("IntegrationsTest passed");
    }

    /** Stored instances: a round trip, malformed entries dropped one by one, fresh ids. */
    private static void instances() {
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("url", "http://pi.hole/admin/api.php");
        settings.put("path", "ads_blocked_today");
        Integrations.Instance one = new Integrations.Instance("webvalue1", "webvalue", "Pi-hole",
                true, settings);
        List<Integrations.Instance> back = Integrations.parseInstances(
                Integrations.storeInstances(java.util.Arrays.asList(one,
                        new Integrations.Instance("later1", "from_a_newer_app", "x", false,
                                Collections.emptyMap()))));
        require(back.size() == 2 && back.get(0).settings.equals(settings)
                && back.get(0).name.equals("Pi-hole") && !back.get(1).enabled
                && back.get(1).definition.equals("from_a_newer_app"),
                "instances did not round trip");
        List<Integrations.Instance> bad = Integrations.parseInstances("[{\"id\":\"Bad_Id\","
                + "\"definition\":\"webvalue\"},{\"id\":\"ok1\",\"definition\":\"webvalue\"},"
                + "{\"id\":\"ok1\",\"definition\":\"webvalue\"},7,{\"id\":\"nodef\"}]");
        require(bad.size() == 1 && bad.get(0).id.equals("ok1") && bad.get(0).enabled,
                "malformed instances were kept: " + bad.size());
        require(Integrations.parseInstances("not json").isEmpty()
                && Integrations.parseInstances(null).isEmpty(), "unreadable list");
        java.util.Set<String> taken = new java.util.HashSet<>(
                java.util.Arrays.asList("webvalue1", "webvalue2"));
        String fresh = Integrations.newInstanceId("webvalue", taken);
        require(fresh.equals("webvalue3") && fresh.matches(Integrations.INSTANCE_SHAPE),
                "fresh id: " + fresh);
        require(Integrations.newInstanceId("Ümlaut_and_more_letters", taken)
                .matches(Integrations.INSTANCE_SHAPE), "a fresh id out of shape");
        require(Integrations.checkName("") != null && Integrations.checkName("a\nb") != null
                && Integrations.checkName("x".repeat(41)) != null
                && Integrations.checkName("Kitchen") == null, "names");
    }

    /** "Value from a web address": its checks, reading the answer, and each kind of failure. */
    private static void webValue() {
        Integrations.Definition web = WebValueIntegration.DEFINITION;
        require(Integrations.DEFINITIONS.get("webvalue") == web, "not registered");
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        require(Integrations.check(web, settings, Collections.emptySet()) != null,
                "an address is needed");
        settings.put("url", "ftp://x");
        require(Integrations.check(web, settings, Collections.emptySet())
                .contains("http://"), "a non-web address was taken");
        settings.put("url", "http://192.168.1.5/status");
        require(Integrations.check(web, settings, Collections.emptySet()) == null,
                "a plain address was refused");
        settings.put("interval_s", "5");
        require(Integrations.check(web, settings, Collections.emptySet())
                .contains("between 10"), "an interval below the floor was taken");
        settings.put("interval_s", "30");
        settings.put("type", "Bogus");
        require(Integrations.check(web, settings, Collections.emptySet()) != null,
                "an unknown type was taken");
        settings.put("type", WebValueIntegration.ON_OFF);
        settings.put("header_name", "X API");
        require(Integrations.check(web, settings, Collections.emptySet()) != null,
                "a header with a space was taken");
        settings.put("header_name", "X-Api-Key");
        settings.put("nonsense", "1");
        require(Integrations.check(web, settings, Collections.emptySet()) != null,
                "an unknown setting was taken");
        settings.remove("nonsense");
        require(Integrations.check(web, settings, Collections.emptySet()) == null,
                "good settings refused");

        require(WebValueIntegration.read("{\"a\":{\"b\":[1,{\"c\":\"x\"}]}}", "a.b.1.c")
                .equals("x"), "path into an array");
        require(WebValueIntegration.read(" 42 \n", "").equals("42"), "the whole answer");
        try {
            WebValueIntegration.read("{\"a\":1}", "a.b");
            require(false, "a missing path was found");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("nothing at a.b"), expected.getMessage());
        }
        try {
            WebValueIntegration.read("<html>", "a");
            require(false, "html was read as JSON");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().equals("the answer is not JSON"), "not JSON");
        }
        require(WebValueIntegration.convert("21.5", Integrations.Type.NUMBER).number == 21.5
                && WebValueIntegration.convert(3.0, Integrations.Type.TEXT).text.equals("3")
                && WebValueIntegration.convert("Up", Integrations.Type.FLAG).flag
                && !WebValueIntegration.convert(0.0, Integrations.Type.FLAG).flag
                && WebValueIntegration.convert("x".repeat(300), Integrations.Type.TEXT)
                        .text.length() == WebValueIntegration.MAX_TEXT, "conversions");
        for (Object[] wrong : new Object[][] {{"abc", Integrations.Type.NUMBER},
                {"maybe", Integrations.Type.FLAG}, {2.0, Integrations.Type.FLAG},
                {null, Integrations.Type.TEXT}}) {
            try {
                WebValueIntegration.convert(wrong[0], (Integrations.Type) wrong[1]);
                require(false, "converted " + wrong[0]);
            } catch (IllegalArgumentException expected) {
                // Said in words.
            }
        }

        java.util.Map<String, String> set = new java.util.HashMap<>();
        set.put("url", "http://svc/x");
        set.put("path", "value");
        set.put("unit", "W");
        set.put("header_name", "X-Key");
        Integrations.Instance instance = new Integrations.Instance("w1", "webvalue", "Solar",
                true, set);
        require(web.valuesOf(instance).get(0).type == Integrations.Type.NUMBER
                && web.valuesOf(instance).get(0).unit.equals("W")
                && web.intervalOf(instance) == 60_000L, "per-instance value and interval");
        java.util.Map<String, String> sent = new java.util.HashMap<>();
        Integrations.Result ok = web.source.fetch(web, instance,
                Collections.singletonMap("header_value", "s3cret"), (url, headers) -> {
                    sent.putAll(headers);
                    return new Integrations.Response(200, "{\"value\": 512}", 0);
                });
        require(ok.problem == null && ok.values.get("value").number == 512
                && "s3cret".equals(sent.get("X-Key")), "a good answer");
        Integrations.Result refused = web.source.fetch(web, instance,
                Collections.singletonMap("header_value", "wrong"),
                (url, headers) -> new Integrations.Response(401, "", 0));
        require(refused.credentialsRefused, "401 with a key is refused credentials");
        // With no key there is nothing to replace: a failure, asked again, never a stop.
        Integrations.Result noKey = web.source.fetch(web, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(403, "", 0));
        require(!noKey.credentialsRefused && noKey.problem.contains("403"),
                "403 without a key stopped the integration");
        Integrations.Result limited = web.source.fetch(web, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(429, "", 30_000));
        require(!limited.credentialsRefused && limited.retryAfterMs == 30_000
                && limited.problem.contains("429"), "429 keeps its Retry-After");
        Integrations.Result down = web.source.fetch(web, instance, Collections.emptyMap(),
                (url, headers) -> { throw new java.io.IOException("timeout"); });
        require(down.problem.contains("did not answer") && !down.credentialsRefused,
                "an unreachable address");
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
