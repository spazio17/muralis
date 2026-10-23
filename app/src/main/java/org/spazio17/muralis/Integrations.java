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
 * <p>Pure, with no Android imports, so the rules are host-tested. The poller, the settings pages
 * and the secrets come in later steps; this holds the model they share.
 */
final class Integrations {
    private Integrations() {
    }

    /** What a value is, which decides what a rule can ask about it. */
    enum Type { NUMBER, FLAG, TEXT, TIME }

    /** Whether a setting is the same on every panel of a fleet or belongs to one panel. */
    enum Scope { SHARED, PANEL }

    /** One setting of an integration, drawn as a box on both surfaces. */
    static final class Field {
        final String id;
        final String label;
        final Scope scope;
        /** Kept in SecretStore, never shown, logged or put in diagnostics or the status. */
        final boolean secret;
        final boolean required;

        Field(String id, String label, Scope scope, boolean secret, boolean required) {
            this.id = id;
            this.label = label;
            this.scope = scope;
            this.secret = secret;
            this.required = required;
        }
    }

    /** One value an integration reports, declared by its definition. */
    static final class ValueSpec {
        final String id;
        final String label;
        final Type type;
        /** The unit of a number, "" for none. */
        final String unit;

        ValueSpec(String id, String label, Type type, String unit) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.unit = unit;
        }
    }

    /** One integration as the app ships it. */
    static final class Definition {
        final String id;
        final String name;
        /** The edition it needs: "free", "pro" or "business". */
        final String edition;
        final List<Field> fields;
        final List<ValueSpec> values;
        /** How often it asks its service, and never more often than the floor. */
        final long intervalMs;
        final long floorMs;

        Definition(String id, String name, String edition, List<Field> fields,
                List<ValueSpec> values, long intervalMs, long floorMs) {
            this.id = id;
            this.name = name;
            this.edition = edition;
            this.fields = Collections.unmodifiableList(fields);
            this.values = Collections.unmodifiableList(values);
            this.intervalMs = intervalMs;
            this.floorMs = floorMs;
        }

        ValueSpec value(String valueId) {
            for (ValueSpec spec : values) {
                if (spec.id.equals(valueId)) {
                    return spec;
                }
            }
            return null;
        }
    }

    /** What an integration's value reads now: typed, perhaps unavailable, with attributes. */
    static final class Value {
        final Type type;
        final boolean available;
        final double number;
        final Boolean flag;
        final String text;
        /** Details for a card or a pop-up (the next meeting's subject and times); never secret. */
        final Map<String, String> attributes;

        private Value(Type type, boolean available, double number, Boolean flag, String text,
                Map<String, String> attributes) {
            this.type = type;
            this.available = available;
            this.number = number;
            this.flag = flag;
            this.text = text;
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        static Value of(double number) {
            return new Value(Type.NUMBER, true, number, null, null, Collections.emptyMap());
        }

        static Value of(boolean flag) {
            return new Value(Type.FLAG, true, Double.NaN, flag, null, Collections.emptyMap());
        }

        static Value of(String text) {
            return new Value(Type.TEXT, true, Double.NaN, null, text, Collections.emptyMap());
        }

        /** A time as milliseconds since 1970, compared as a number. */
        static Value at(long epochMs) {
            return new Value(Type.TIME, true, epochMs, null, null, Collections.emptyMap());
        }

        static Value unavailable(Type type) {
            return new Value(type, false, Double.NaN, null, null, Collections.emptyMap());
        }

        Value with(Map<String, String> details) {
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

    /** The definitions the app ships, by id; empty until the first integration lands. */
    static final Map<String, Definition> DEFINITIONS = Collections.emptyMap();

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
