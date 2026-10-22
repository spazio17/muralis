/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Uptime Kuma: reading a status page and its heartbeats, and each failure. */
public final class UptimeKumaIntegrationTest {

    static final String PAGE = "{\"config\":{},\"publicGroupList\":[{\"name\":\"Services\","
            + "\"monitorList\":[{\"id\":1,\"name\":\"Mail\"},{\"id\":2,\"name\":\"VPN\"},"
            + "{\"id\":3,\"name\":\"Intranet\"},{\"id\":4,\"name\":\"New\"}]}]}";
    static final String BEATS = "{\"heartbeatList\":{\"1\":[{\"status\":1},{\"status\":0}],"
            + "\"2\":[{\"status\":1}],\"3\":[{\"status\":3}]},\"uptimeList\":{}}";

    public static void main(String[] args) throws Exception {
        Integrations.Definition kuma = UptimeKumaIntegration.DEFINITION;
        require(Integrations.DEFINITIONS.get("uptimekuma") == kuma, "registered");
        Map<String, String> settings = new HashMap<>();
        settings.put("url", "http://192.0.2.10:3001");
        settings.put("slug", "home page");
        require(Integrations.check(kuma, settings, Collections.emptySet()) != null,
                "a slug with a space was taken");
        settings.put("slug", "home");
        require(Integrations.check(kuma, settings, Collections.emptySet()) == null,
                "good settings refused");

        Map<String, Integrations.Value> read = UptimeKumaIntegration.read(PAGE, BEATS);
        require(read.get("down").number == 1 && read.get("any_down").flag
                && read.get("up").number == 1 && read.get("down_names").text.equals("Mail"),
                "reading: down " + read.get("down").number);

        Integrations.Instance instance = new Integrations.Instance("uptimekuma1", "uptimekuma",
                "Office", true, settings);
        String[] asked = new String[2];
        Integrations.Result ok = kuma.source.fetch(kuma, instance, Collections.emptyMap(),
                (url, headers) -> {
                    if (url.contains("/heartbeat/")) {
                        asked[1] = url;
                        return new Integrations.Response(200, BEATS, 0);
                    }
                    asked[0] = url;
                    return new Integrations.Response(200, PAGE, 0);
                });
        require(ok.problem == null && asked[0].equals("http://192.0.2.10:3001/api/status-page/home")
                && asked[1].endsWith("/api/status-page/heartbeat/home"), "a good ask");
        Integrations.Result missing = kuma.source.fetch(kuma, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(404, "", 0));
        require(missing.problem.contains("no status page home") && !missing.credentialsRefused,
                "an unknown page");
        Integrations.Result html = kuma.source.fetch(kuma, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(200, "<html>", 0));
        require(html.problem.contains("could not be read"), "an answer of another shape");
        System.out.println("UptimeKumaIntegrationTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
