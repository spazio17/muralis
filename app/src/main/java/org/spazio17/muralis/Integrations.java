/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integrations: extensions built into the app that connect to a service the person chose and
 * feed the panel new values, which the automations act on (a monitor down turns the status light
 * red). Each is a {@link Definition}, data and never downloaded code, added as one or more
 * instances (two monitor accounts, one room per panel).
 *
 * <p>An instance's values are sensors like the device's own, with ids
 * {@code int_<instance>_<value>} (only what MQTT topics, discovery object ids and the pages'
 * ids take), so the automations, the status document and MQTT discovery take them as
 * they take the light or the battery. A value is typed (a number, on or off, a text, a time) and
 * may be unavailable, which never fires a rule: a service that does not answer is not "all up".
 *
 * <p>Pure, with no Android imports, so the rules are host-tested; IntegrationRunner asks the
 * services, IntegrationSettings stores what the two settings surfaces send.
 */
public final class Integrations {
    private Integrations() {
    }

    /** What a value is, which decides what a rule can ask about it. */
    public enum Type { NUMBER, FLAG, TEXT, TIME }

    /** Whether a setting is the same on every panel of a fleet or belongs to one panel. */
    public enum Scope { SHARED, PANEL }

    /** How a setting's box is drawn and what it takes. */
    public enum Kind { TEXT, URL, NUMBER, CHOICE }

    /** The longest value a setting takes, secrets included. */
    public static final int MAX_SETTING_LENGTH = 2048;

    /** One setting of an integration, drawn as a box on both surfaces. */
    public static final class Field {
        public final String id;
        public final String label;
        public final Scope scope;
        /** Kept in SecretStore, never shown, logged or put in diagnostics or the status. */
        public final boolean secret;
        public final boolean required;
        public final Kind kind;
        /** A CHOICE's values, in the order the menu shows them; empty for the other kinds. */
        public final List<String> options;
        /** What an empty box means, "" for nothing. */
        public final String fallback;
        /** A NUMBER's bounds. */
        public final long min;
        public final long max;
        /** One line under the box, "" for none. */
        public final String hint;

        public Field(String id, String label, Scope scope, boolean secret, boolean required) {
            this(id, label, scope, secret, required, Kind.TEXT, Collections.emptyList(), "", 0, 0,
                    "");
        }

        public Field(String id, String label, Scope scope, boolean secret, boolean required, Kind kind,
                List<String> options, String fallback, long min, long max, String hint) {
            this.id = id;
            this.label = label;
            this.scope = scope;
            this.secret = secret;
            this.required = required;
            this.kind = kind;
            this.options = Collections.unmodifiableList(options);
            this.fallback = fallback;
            this.min = min;
            this.max = max;
            this.hint = hint;
        }

        /** Why this value is refused, or null; an empty value is checked by required alone. */
        String check(String value) {
            if (value.length() > MAX_SETTING_LENGTH) {
                return label + " is longer than " + MAX_SETTING_LENGTH + " characters";
            }
            if (value.isEmpty()) {
                return required && fallback.isEmpty() ? label + " is needed" : null;
            }
            switch (kind) {
                case URL:
                    if (!value.startsWith("http://") && !value.startsWith("https://")) {
                        return label + " must start with http:// or https://";
                    }
                    if (value.matches(".*[\\s\\p{Cntrl}].*")) {
                        return label + " must not contain spaces";
                    }
                    return null;
                case NUMBER:
                    if (!value.matches("-?\\d{1,12}")) {
                        return label + " must be a whole number";
                    }
                    long number = Long.parseLong(value);
                    return number < min || number > max
                            ? label + " must be between " + min + " and " + max : null;
                case CHOICE:
                    return options.contains(value) ? null
                            : label + " must be one of " + String.join(", ", options);
                default:
                    return value.matches(".*\\p{Cntrl}.*")
                            ? label + " must be one line of text" : null;
            }
        }
    }

    /** One value an integration reports, declared by its definition. */
    public static final class ValueSpec {
        public final String id;
        public final String label;
        public final Type type;
        /** The unit of a number, "" for none. */
        public final String unit;

        public ValueSpec(String id, String label, Type type, String unit) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.unit = unit;
        }
    }

    /** One integration as the app ships it. */
    public static final class Definition {
        public final String id;
        public final String name;
        /** The edition it needs: "free", "pro" or "business". */
        public final String edition;
        public final List<Field> fields;
        public final List<ValueSpec> values;
        /** How often it asks its service, and never more often than the floor. */
        public final long intervalMs;
        public final long floorMs;
        /** One sentence for the Integrations page: what it brings to the panel. */
        public final String summary;
        /** What asks the service; null in a definition used only to describe values. */
        public final Source source;
        /**
         * What it brings, so the places that use one kind list only those: {@link #VALUES} for
         * values the automations and MQTT take, {@link #WEATHER} for the weather set
         * Ambient's information layer reads, {@link #AGENDA} for a list of events.
         */
        public final String capability;

        public Definition(String id, String name, String edition, List<Field> fields,
                List<ValueSpec> values, long intervalMs, long floorMs) {
            this(id, name, edition, "", fields, values, intervalMs, floorMs, null);
        }

        public Definition(String id, String name, String edition, String summary, List<Field> fields,
                List<ValueSpec> values, long intervalMs, long floorMs, Source source) {
            this(id, name, edition, summary, fields, values, intervalMs, floorMs, source, VALUES);
        }

        public Definition(String id, String name, String edition, String summary,
                List<Field> fields, List<ValueSpec> values, long intervalMs, long floorMs,
                Source source, String capability) {
            this.capability = capability;
            this.id = id;
            this.name = name;
            this.edition = edition;
            this.summary = summary;
            this.fields = Collections.unmodifiableList(fields);
            this.values = Collections.unmodifiableList(values);
            this.intervalMs = intervalMs;
            this.floorMs = floorMs;
            this.source = source;
        }

        public Field field(String fieldId) {
            for (Field field : fields) {
                if (field.id.equals(fieldId)) {
                    return field;
                }
            }
            return null;
        }

        /**
         * The values an instance reports. Fixed for most; a definition whose instance chooses the
         * type or unit (a value from a web address) answers per instance.
         */
        public List<ValueSpec> valuesOf(Instance instance) {
            return source == null ? values : source.values(this, instance);
        }

        /** How often an instance asks: its "interval_s" setting where it has one, never below the floor. */
        public long intervalOf(Instance instance) {
            String seconds = instance.setting(this, "interval_s");
            long ms = seconds.matches("\\d{1,9}") ? Long.parseLong(seconds) * 1000L : intervalMs;
            return Math.max(floorMs, ms);
        }

        public ValueSpec value(String valueId) {
            for (ValueSpec spec : values) {
                if (spec.id.equals(valueId)) {
                    return spec;
                }
            }
            return null;
        }
    }

    /** What an integration's value reads now: typed, perhaps unavailable, with attributes. */
    public static final class Value {
        public final Type type;
        public final boolean available;
        public final double number;
        public final Boolean flag;
        public final String text;
        /** Details for a card or a pop-up (the next meeting's subject and times); never secret. */
        public final Map<String, String> attributes;

        private Value(Type type, boolean available, double number, Boolean flag, String text,
                Map<String, String> attributes) {
            this.type = type;
            this.available = available;
            this.number = number;
            this.flag = flag;
            this.text = text;
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        public static Value of(double number) {
            return new Value(Type.NUMBER, true, number, null, null, Collections.emptyMap());
        }

        public static Value of(boolean flag) {
            return new Value(Type.FLAG, true, Double.NaN, flag, null, Collections.emptyMap());
        }

        public static Value of(String text) {
            return new Value(Type.TEXT, true, Double.NaN, null, text, Collections.emptyMap());
        }

        /** A time as milliseconds since 1970, compared as a number. */
        public static Value at(long epochMs) {
            return new Value(Type.TIME, true, epochMs, null, null, Collections.emptyMap());
        }

        public static Value unavailable(Type type) {
            return new Value(type, false, Double.NaN, null, null, Collections.emptyMap());
        }

        public Value with(Map<String, String> details) {
            return new Value(type, available, number, flag, text, details);
        }

        /**
         * What the automations see: the value in the shape its events ask about, or an empty
         * sample while unavailable, on which no rule decides anything (Automations.holds says
         * null), so the state a rule last saw stands and nothing fires.
         */
        Automations.Sample sample() {
            if (!available) {
                return new Automations.Sample(Double.NaN, null, Double.NaN);
            }
            switch (type) {
                case FLAG:
                    return Automations.Sample.of(flag);
                case TEXT:
                    return Automations.Sample.ofText(text);
                default:
                    return Automations.Sample.of(number);
            }
        }
    }

    /**
     * One integration added on this panel: which definition, a name the person gave it, whether
     * it runs, and its settings that are not secret. Secret settings live in SecretStore under
     * {@link #secretName}, never here, so this list can travel in the status and the settings
     * file.
     */
    public static final class Instance {
        public final String id;
        public final String definition;
        public final String name;
        public final boolean enabled;
        public final Map<String, String> settings;

        public Instance(String id, String definition, String name, boolean enabled,
                Map<String, String> settings) {
            this.id = id;
            this.definition = definition;
            this.name = name;
            this.enabled = enabled;
            this.settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        }

        /** The setting as stored, or the field's fallback when it is empty. */
        public String setting(Definition definition, String fieldId) {
            String value = settings.getOrDefault(fieldId, "");
            Field field = definition.field(fieldId);
            return value.isEmpty() && field != null ? field.fallback : value;
        }

        Instance withEnabled(boolean on) {
            return new Instance(id, definition, name, on, settings);
        }
    }

    public static final String VALUES = "values";
    public static final String WEATHER = "weather";
    public static final String AGENDA = "agenda";

    /**
     * A failed request in words for the status line and the log: the exception's own message
     * only when it carries no address, since a message like "FileNotFoundException:
     * https://...?appid=" would put a key from the address where everyone reads it.
     */
    public static String why(java.io.IOException failed) {
        String message = failed.getMessage();
        return message == null || message.isEmpty() || message.contains("://")
                || message.contains("?") ? "" : " (" + message + ")";
    }

    /** The SecretStore name of an instance's secret setting. */
    static String secretName(String instance, String fieldId) {
        return "integration_" + instance + "_" + fieldId;
    }

    /** What one ask of the service brought: the values, or why there are none this time. */
    public static final class Result {
        public final Map<String, Value> values;
        /** Why the ask failed, in words for the status line; null when it did not. */
        public final String problem;
        /** How long the service asked to be left alone, 0 for not said. */
        public final long retryAfterMs;
        /** The service refused the credentials: polling stops until new ones (reauth). */
        public final boolean credentialsRefused;

        private Result(Map<String, Value> values, String problem, long retryAfterMs,
                boolean credentialsRefused) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.problem = problem;
            this.retryAfterMs = retryAfterMs;
            this.credentialsRefused = credentialsRefused;
        }

        public static Result of(Map<String, Value> values) {
            return new Result(values, null, 0, false);
        }

        public static Result failed(String problem, long retryAfterMs) {
            return new Result(Collections.emptyMap(), problem, retryAfterMs, false);
        }

        public static Result refused(String problem) {
            return new Result(Collections.emptyMap(), problem, 0, true);
        }
    }

    /** An answer to one HTTP request, its body already capped. */
    public static final class Response {
        public final int status;
        public final String body;
        /** The Retry-After header in milliseconds, 0 when absent or unreadable. */
        public final long retryAfterMs;

        public Response(int status, String body, long retryAfterMs) {
            this.status = status;
            this.body = body;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /**
     * How a source reaches its service: one GET, with headers. The service's implementation has
     * the timeouts, the size cap and the redirect checks; a source never opens a connection itself,
     * so every integration is held to the same limits.
     */
    public interface Http {
        Response get(String url, Map<String, String> headers) throws java.io.IOException;
    }

    /** What an integration does when it asks its service. Built into the app, never downloaded. */
    public interface Source {
        /** The values this instance reports; most return the definition's own. */
        default List<ValueSpec> values(Definition definition, Instance instance) {
            return definition.values;
        }

        /** Why these settings cannot work, beyond each field's own check; null when they can. */
        default String check(Definition definition, Map<String, String> settings) {
            return null;
        }

        Result fetch(Definition definition, Instance instance, Map<String, String> secrets,
                Http http);
    }

    /**
     * Why an instance's settings are refused, or null: each field's own check, then the
     * source's. {@code secretsKept} names the secret fields that already hold a value, so an
     * empty secret box ("keep the current one") passes a required secret.
     */
    public static String check(Definition definition, Map<String, String> settings,
            java.util.Set<String> secretsKept) {
        for (Field field : definition.fields) {
            String value = settings.getOrDefault(field.id, "");
            if (field.secret && value.isEmpty() && secretsKept.contains(field.id)) {
                continue;
            }
            String problem = field.check(value);
            if (problem != null) {
                return problem;
            }
        }
        for (String key : settings.keySet()) {
            if (definition.field(key) == null) {
                return definition.name + " has no setting called " + key;
            }
        }
        return definition.source == null ? null : definition.source.check(definition, settings);
    }

    /** The longest name a person gives an instance. */
    public static final int MAX_NAME_LENGTH = 40;
    /** How many instances one panel holds. */
    public static final int MAX_INSTANCES = 30;

    /** Why a name is refused, or null. */
    public static String checkName(String name) {
        if (name.trim().isEmpty()) {
            return "the name is needed";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "the name is longer than " + MAX_NAME_LENGTH + " characters";
        }
        return name.matches(".*\\p{Cntrl}.*") ? "the name must be one line" : null;
    }

    /**
     * The stored instances, in the order they were added. Malformed entries are dropped one by
     * one, so one bad entry does not take the others with it; an entry for a definition this
     * app does not know (a newer Muralis wrote it) is kept, and the runner leaves it alone.
     */
    static List<Instance> parseInstances(String json) {
        List<Instance> instances = new java.util.ArrayList<>();
        if (json == null || json.isEmpty()) {
            return instances;
        }
        List<Object> array;
        try {
            array = TinyJson.array(TinyJson.parse(json));
        } catch (IllegalArgumentException unreadable) {
            return instances;
        }
        if (array == null) {
            return instances;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object entry : array) {
            Map<String, Object> one = TinyJson.object(entry);
            if (one == null) {
                continue;
            }
            String id = TinyJson.string(one, "id", "");
            String definition = TinyJson.string(one, "definition", "");
            if (!id.matches(INSTANCE_SHAPE) || definition.isEmpty() || !seen.add(id)) {
                continue;
            }
            Map<String, String> settings = new LinkedHashMap<>();
            Map<String, Object> stored = TinyJson.object(one.get("settings"));
            if (stored != null) {
                for (Map.Entry<String, Object> setting : stored.entrySet()) {
                    if (setting.getValue() instanceof String) {
                        settings.put(setting.getKey(), (String) setting.getValue());
                    }
                }
            }
            instances.add(new Instance(id, definition, TinyJson.string(one, "name", definition),
                    TinyJson.flag(one, "enabled", true), settings));
        }
        return instances;
    }

    static String storeInstances(List<Instance> instances) {
        List<Object> array = new java.util.ArrayList<>();
        for (Instance instance : instances) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", instance.id);
            one.put("definition", instance.definition);
            one.put("name", instance.name);
            one.put("enabled", instance.enabled);
            one.put("settings", new LinkedHashMap<String, Object>(instance.settings));
            array.add(one);
        }
        return TinyJson.write(array);
    }

    /**
     * A fresh instance id: the definition's first letters and a number, short enough for the
     * sensor ids ({@code int_<instance>_<value>}), never one in use.
     */
    static String newInstanceId(String definition, java.util.Set<String> taken) {
        String stem = definition.replaceAll("[^a-z0-9]", "");
        stem = stem.substring(0, Math.min(stem.length(), 8));
        if (stem.isEmpty()) {
            stem = "int";
        }
        for (int n = 1; ; n++) {
            String id = stem + n;
            if (!taken.contains(id) && id.matches(INSTANCE_SHAPE)) {
                return id;
            }
        }
    }

    /** The shape of an instance id: lowercase letters and digits, no underscore. */
    static final String INSTANCE_SHAPE = "[a-z0-9]{1,12}";
    /** The shape of a value id. */
    static final String VALUE_SHAPE = "[a-z0-9_]{1,24}";
    private static final String PREFIX = "int_";

    /** The sensor id of one instance's value: {@code int_<instance>_<value>}. */
    static String sensorId(String instance, String valueId) {
        return PREFIX + instance + "_" + valueId;
    }

    static boolean isSensorId(String id) {
        return parseSensorId(id) != null;
    }

    /**
     * The instance and the value of a sensor id, or null when it is not one: the instance has no
     * underscore, so the first one after the prefix ends it.
     */
    static String[] parseSensorId(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return null;
        }
        String rest = id.substring(PREFIX.length());
        int cut = rest.indexOf('_');
        if (cut < 0) {
            return null;
        }
        String instance = rest.substring(0, cut);
        String value = rest.substring(cut + 1);
        return instance.matches(INSTANCE_SHAPE) && value.matches(VALUE_SHAPE)
                ? new String[] {instance, value} : null;
    }

    /** Which value a sensor id names, from the instances this panel holds; null when none. */
    interface Lookup {
        ValueSpec valueOf(String sensorId);
    }

    /** Set by whoever holds the instances (the service); empty until then. */
    static volatile Lookup lookup = sensorId -> null;

    /** The definitions the app ships, by id, in the order the Integrations page lists them. */
    public static final Map<String, Definition> DEFINITIONS = definitions(IntegrationRegistry.ALL);

    /**
     * The definitions by id, from the registry the build generates from the integration folders
     * (scripts/integration-registry.py). A definition whose id is not its folder's, or an id twice,
     * stops the app at once rather than shipping an integration nobody can find.
     */
    private static Map<String, Definition> definitions(Definition... all) {
        Map<String, Definition> byId = new LinkedHashMap<>();
        for (int index = 0; index < all.length; index++) {
            Definition definition = all[index];
            if (!definition.id.equals(IntegrationRegistry.IDS[index])
                    || byId.put(definition.id, definition) != null) {
                throw new IllegalStateException("integration " + definition.id
                        + " does not match its folder " + IntegrationRegistry.IDS[index]);
            }
        }
        return Collections.unmodifiableMap(byId);
    }

    /** The events a rule can wait for on a value of this type. */
    static List<Automations.Event> events(ValueSpec spec) {
        switch (spec.type) {
            case NUMBER:
                return Arrays.asList(
                        new Automations.Event("int_above", "Rises above", spec.unit, true, false),
                        new Automations.Event("int_below", "Falls below", spec.unit, true, false));
            case FLAG:
                return Arrays.asList(
                        new Automations.Event("int_on", "Turns on", null, true, false),
                        new Automations.Event("int_off", "Turns off", null, true, false));
            case TEXT:
                return Collections.singletonList(Automations.Event.text("int_becomes", "Becomes"));
            default:
                // A time is read by cards and the agenda, not waited for by a rule (yet).
                return Collections.emptyList();
        }
    }
}
