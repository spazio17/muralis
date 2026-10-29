/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;
import org.spazio17.muralis.TinyJson;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Value from a web address": one number, text or on/off read from any address that answers
 * JSON or plain text (a status page, Pi-hole, a solar inverter, a queue counter). The value
 * feeds the automations and MQTT like a sensor of the panel's own.
 *
 * <p>Pure, with no Android imports, so the reading of the answer is host-tested; the request goes
 * through the runner's {@link Integrations.Http}, with its limits.
 */
public final class WebValueIntegration implements Integrations.Source {
    static final String ID = "webvalue";
    static final String VALUE = "value";
    static final String NUMBER = "Number";
    static final String TEXT = "Text";
    static final String ON_OFF = "On or off";
    /** The longest text value kept, what a state published over MQTT discovery takes. */
    static final int MAX_TEXT = 255;

    public static final Integrations.Definition DEFINITION = new Integrations.Definition(ID,
            "Value from a web address", "free",
            "One value from a web address.",
            Arrays.asList(
                    new Integrations.Field("url", "Address", Integrations.Scope.SHARED, false,
                            true, Integrations.Kind.URL, Collections.emptyList(), "", 0, 0,
                            "It answers JSON or plain text."),
                    new Integrations.Field("path", "Where the value is", Integrations.Scope.SHARED,
                            false, false, Integrations.Kind.TEXT, Collections.emptyList(), "", 0,
                            0, "For example data.temperature or items.0.name; empty for the whole "
                                    + "answer."),
                    new Integrations.Field("type", "The value is", Integrations.Scope.SHARED,
                            false, false, Integrations.Kind.CHOICE,
                            Arrays.asList(NUMBER, TEXT, ON_OFF), NUMBER, 0, 0, ""),
                    new Integrations.Field("unit", "Unit", Integrations.Scope.SHARED, false, false,
                            Integrations.Kind.TEXT, Collections.emptyList(), "", 0, 0,
                            "For a number, for example °C or W."),
                    new Integrations.Field("header_name", "Header for a key",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.TEXT,
                            Collections.emptyList(), "", 0, 0,
                            "For example Authorization or X-API-Key; empty for none."),
                    new Integrations.Field("header_value", "Key", Integrations.Scope.SHARED, true,
                            false, Integrations.Kind.TEXT, Collections.emptyList(), "", 0, 0, ""),
                    new Integrations.Field("interval_seconds", "Ask every (seconds)",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.NUMBER,
                            Collections.emptyList(), "60", 10, 86_400, "")),
            Collections.singletonList(new Integrations.ValueSpec(VALUE, "Value",
                    Integrations.Type.NUMBER, "")),
            60_000L, 10_000L, new WebValueIntegration());

    @Override
    public List<Integrations.ValueSpec> values(Integrations.Definition definition,
            Integrations.Instance instance) {
        String type = instance.setting(definition, "type");
        return Collections.singletonList(new Integrations.ValueSpec(VALUE, "Value",
                typeOf(type), NUMBER.equals(type) ? instance.setting(definition, "unit") : ""));
    }

    private static Integrations.Type typeOf(String type) {
        return TEXT.equals(type) ? Integrations.Type.TEXT
                : ON_OFF.equals(type) ? Integrations.Type.FLAG : Integrations.Type.NUMBER;
    }

    @Override
    public String check(Integrations.Definition definition, Map<String, String> settings) {
        String header = settings.getOrDefault("header_name", "");
        if (!header.isEmpty() && !header.matches("[A-Za-z0-9-]{1,64}")) {
            return "the header name takes letters, digits and dashes";
        }
        if (header.equalsIgnoreCase("Host") || header.equalsIgnoreCase("Content-Length")) {
            return "the header cannot be " + header;
        }
        if (settings.getOrDefault("unit", "").length() > 12) {
            return "the unit is longer than 12 characters";
        }
        String path = settings.getOrDefault("path", "");
        if (!path.isEmpty() && !path.matches("[^.\\s]+(\\.[^.\\s]+)*")) {
            return "the place of the value is names joined by dots, with no spaces";
        }
        return null;
    }

    @Override
    public Integrations.Result fetch(Integrations.Definition definition,
            Integrations.Instance instance, Map<String, String> secrets, Integrations.Http http) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain;q=0.9, */*;q=0.5");
        String header = instance.setting(definition, "header_name");
        String key = secrets.getOrDefault("header_value", "");
        if (!header.isEmpty() && !key.isEmpty()) {
            headers.put(header, key);
        }
        Integrations.Response response;
        try {
            response = http.get(instance.setting(definition, "url"), headers);
        } catch (java.io.IOException unreachable) {
            return Integrations.Result.failed("the address did not answer"
                    + Integrations.why(unreachable), 0);
        }
        // Refused credentials stop the asking until a new key is saved; with no key set there
        // is nothing to replace, so a 401 or 403 is a failure like any other, tried again.
        if ((response.status == 401 || response.status == 403) && !key.isEmpty()) {
            return Integrations.Result.refused("the address refused the key (HTTP "
                    + response.status + ")");
        }
        if (response.status < 200 || response.status > 299) {
            return Integrations.Result.failed("the address answered HTTP " + response.status,
                    response.retryAfterMs);
        }
        String type = instance.setting(definition, "type");
        Object found;
        try {
            found = read(response.body, instance.setting(definition, "path"));
        } catch (IllegalArgumentException unreadable) {
            return Integrations.Result.failed(unreadable.getMessage(), 0);
        }
        Integrations.Value value;
        try {
            value = convert(found, typeOf(type));
        } catch (IllegalArgumentException wrongType) {
            return Integrations.Result.failed(wrongType.getMessage(), 0);
        }
        return Integrations.Result.of(Collections.singletonMap(VALUE, value));
    }

    /**
     * The value at {@code path} in the answer: names joined by dots, a number naming an array's
     * item; an empty path is the whole answer as text. Throws with the reason in words.
     */
    static Object read(String body, String path) {
        if (path.isEmpty()) {
            return body.trim();
        }
        Object at;
        try {
            at = TinyJson.parse(body);
        } catch (IllegalArgumentException notJson) {
            throw new IllegalArgumentException("the answer is not JSON");
        }
        for (String step : path.split("\\.")) {
            Map<String, Object> object = TinyJson.object(at);
            List<Object> array = TinyJson.array(at);
            if (object != null && object.containsKey(step)) {
                at = object.get(step);
            } else if (array != null && step.matches("\\d{1,6}")
                    && Integer.parseInt(step) < array.size()) {
                at = array.get(Integer.parseInt(step));
            } else {
                throw new IllegalArgumentException("nothing at " + path + " in the answer");
            }
        }
        return at;
    }

    /** The found value in the type the instance asked for; throws with the reason in words. */
    static Integrations.Value convert(Object found, Integrations.Type type) {
        switch (type) {
            case TEXT: {
                if (found == null || found instanceof Map || found instanceof List) {
                    throw new IllegalArgumentException("the value is not a text");
                }
                String text = found instanceof Double ? plain((Double) found) : found.toString();
                return Integrations.Value.of(text.length() > MAX_TEXT
                        ? text.substring(0, MAX_TEXT) : text);
            }
            case FLAG: {
                Boolean flag = flagOf(found);
                if (flag == null) {
                    throw new IllegalArgumentException("the value is not on or off");
                }
                return Integrations.Value.of(flag);
            }
            default: {
                if (found instanceof Double) {
                    return Integrations.Value.of((Double) found);
                }
                if (found instanceof String) {
                    String text = ((String) found).trim();
                    if (text.matches("-?\\d{1,15}(\\.\\d{1,15})?([eE][-+]?\\d{1,3})?")) {
                        return Integrations.Value.of(Double.parseDouble(text));
                    }
                }
                throw new IllegalArgumentException("the value is not a number");
            }
        }
    }

    private static Boolean flagOf(Object found) {
        if (found instanceof Boolean) {
            return (Boolean) found;
        }
        if (found instanceof Double) {
            double number = (Double) found;
            return number == 1 ? Boolean.TRUE : number == 0 ? Boolean.FALSE : null;
        }
        if (found instanceof String) {
            switch (((String) found).trim().toLowerCase(Locale.ROOT)) {
                case "1": case "true": case "on": case "yes": case "up":
                    return Boolean.TRUE;
                case "0": case "false": case "off": case "no": case "down":
                    return Boolean.FALSE;
                default:
                    return null;
            }
        }
        return null;
    }

    /** A whole number without ".0", as a person would write it. */
    private static String plain(double number) {
        return number == Math.rint(number) && Math.abs(number) < 1e15
                ? Long.toString((long) number) : Double.toString(number);
    }
}
