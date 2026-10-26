/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;
import org.spazio17.muralis.TinyJson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uptime Kuma: how many monitors of one public status page are down, from the page's own JSON
 * (/api/status-page/<slug> for the names, /api/status-page/heartbeat/<slug> for the latest beat
 * of each). No key: a status page is public. Uptime Kuma calls this API internal, so a version that
 * changes it shows up here as "could not be read", never as "all up".
 */
public final class UptimeKumaIntegration implements Integrations.Source {
    static final String ID = "uptimekuma";

    public static final Integrations.Definition DEFINITION = new Integrations.Definition(ID,
            "Uptime Kuma", "free",
            "Which monitors are down.",
            Arrays.asList(
                    new Integrations.Field("url", "Uptime Kuma address", Integrations.Scope.SHARED,
                            false, true, Integrations.Kind.URL, Collections.emptyList(), "", 0, 0,
                            "Where Uptime Kuma answers: http://192.168.1.10:3001"),
                    new Integrations.Field("slug", "Status page", Integrations.Scope.SHARED, false,
                            true, Integrations.Kind.TEXT, Collections.emptyList(), "", 0, 0,
                            "The end of the status page's address: /status/home is home."),
                    new Integrations.Field("interval_s", "Ask every (seconds)",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.NUMBER,
                            Collections.emptyList(), "60", 60, 86_400, "")),
            Arrays.asList(
                    new Integrations.ValueSpec("down", "Monitors down", Integrations.Type.NUMBER,
                            ""),
                    new Integrations.ValueSpec("any_down", "Any monitor down",
                            Integrations.Type.FLAG, ""),
                    new Integrations.ValueSpec("up", "Monitors up", Integrations.Type.NUMBER, ""),
                    new Integrations.ValueSpec("down_names", "Down", Integrations.Type.TEXT, "")),
            // Uptime Kuma caches the status page five minutes and its beats one: asking more
            // often than a minute brings nothing new.
            60_000L, 60_000L, new UptimeKumaIntegration());

    @Override
    public String check(Integrations.Definition definition, Map<String, String> settings) {
        String slug = settings.getOrDefault("slug", "");
        return slug.isEmpty() || slug.matches("[A-Za-z0-9_-]{1,64}") ? null
                : "the status page is letters, digits and dashes";
    }

    @Override
    public Integrations.Result fetch(Integrations.Definition definition,
            Integrations.Instance instance, Map<String, String> secrets, Integrations.Http http) {
        // The page's own address pasted ("…/status/home") is Uptime Kuma's address plus that.
        String base = instance.setting(definition, "url").replaceAll("/+$", "")
                .replaceAll("/status/[A-Za-z0-9_-]+$", "");
        String slug = instance.setting(definition, "slug");
        Map<String, String> headers = Collections.singletonMap("Accept", "application/json");
        try {
            Integrations.Response page = http.get(base + "/api/status-page/" + slug, headers);
            if (page.status == 404) {
                return Integrations.Result.failed("Uptime Kuma has no status page " + slug, 0);
            }
            if (page.status < 200 || page.status > 299) {
                return Integrations.Result.failed("Uptime Kuma answered HTTP " + page.status,
                        page.retryAfterMs);
            }
            Integrations.Response beats = http.get(base + "/api/status-page/heartbeat/" + slug,
                    headers);
            if (beats.status < 200 || beats.status > 299) {
                return Integrations.Result.failed("Uptime Kuma answered HTTP " + beats.status,
                        beats.retryAfterMs);
            }
            return Integrations.Result.of(read(page.body, beats.body));
        } catch (java.io.IOException unreachable) {
            return Integrations.Result.failed("Uptime Kuma did not answer"
                    + Integrations.why(unreachable), 0);
        } catch (IllegalArgumentException unreadable) {
            return Integrations.Result.failed("Uptime Kuma's answer could not be read", 0);
        }
    }

    /**
     * The values from the page (monitor names by id) and its heartbeats (the latest status of
     * each: 1 up, 0 down, 2 pending, 3 maintenance). A monitor with no beat yet counts as
     * neither. Throws on an answer of another shape.
     */
    static Map<String, Integrations.Value> read(String pageBody, String beatsBody) {
        Map<String, Object> page = TinyJson.object(TinyJson.parse(pageBody));
        Map<String, Object> beats = TinyJson.object(TinyJson.parse(beatsBody));
        List<Object> groups = page == null ? null : TinyJson.array(page.get("publicGroupList"));
        Map<String, Object> lists = beats == null ? null
                : TinyJson.object(beats.get("heartbeatList"));
        if (groups == null || lists == null) {
            throw new IllegalArgumentException("not a status page");
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (Object group : groups) {
            Map<String, Object> one = TinyJson.object(group);
            List<Object> monitors = one == null ? null : TinyJson.array(one.get("monitorList"));
            for (Object monitor : monitors == null ? Collections.emptyList() : monitors) {
                Map<String, Object> entry = TinyJson.object(monitor);
                if (entry != null && entry.get("id") instanceof Double) {
                    names.put(Long.toString((long) (double) (Double) entry.get("id")),
                            TinyJson.string(entry, "name", "monitor"));
                }
            }
        }
        int up = 0;
        List<String> down = new ArrayList<>();
        for (Map.Entry<String, String> monitor : names.entrySet()) {
            List<Object> list = TinyJson.array(lists.get(monitor.getKey()));
            if (list == null || list.isEmpty()) {
                continue;
            }
            Map<String, Object> last = TinyJson.object(list.get(list.size() - 1));
            Object status = last == null ? null : last.get("status");
            if (!(status instanceof Double)) {
                continue;
            }
            int code = (int) (double) (Double) status;
            if (code == 1) {
                up++;
            } else if (code == 0) {
                down.add(monitor.getValue());
            }
        }
        String joined = String.join(", ", down);
        Map<String, Integrations.Value> values = new LinkedHashMap<>();
        values.put("down", Integrations.Value.of(down.size()));
        values.put("any_down", Integrations.Value.of(!down.isEmpty()));
        values.put("up", Integrations.Value.of(up));
        values.put("down_names", Integrations.Value.of(joined.length() > 255
                ? joined.substring(0, 255) : joined));
        return values;
    }
}
