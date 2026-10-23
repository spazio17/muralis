/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;

import java.util.Collections;

/** "Value from a web address", host-tested with the engine's model (scripts/test-host.sh). */
public final class WebValueIntegrationTest {

    public static void main(String[] args) throws Exception {
        webValue();
        System.out.println("WebValueIntegrationTest passed");
    }

    /** "Value from a web address": its checks, reading the answer, and each kind of failure. */
    private static void webValue() throws Exception {
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
