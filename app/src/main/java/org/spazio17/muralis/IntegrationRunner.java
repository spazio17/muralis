/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the integrations added on this panel: asks each enabled instance's service on its
 * {@link IntegrationSchedule}, keeps the values it reported, hands them to the automations as the
 * sensors hand theirs, and says per instance how it is doing (the status line a row shows).
 *
 * <p>One scheduler thread checks the schedules every second; the asking itself runs on a small
 * pool, so a slow service holds up only itself. A value is what the service said last, until the
 * schedule says unavailable (three failures in a row); after that an old value never stands in
 * for a service that does not answer. A result that arrives for an instance removed or changed
 * meanwhile is dropped.
 *
 * <p>An instance the fleet shares is asked by one panel for the whole group (FleetAsker): that
 * panel reads it as above and passes the values on (FleetServer.pushValues); the others do not
 * ask, take the newest values that arrive as if they had read them, and drop them once no
 * reading came for too long. Each panel works out its role again every few seconds.
 */
final class IntegrationRunner {
    private static final String TAG = "MuralisIntegrations";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    /** The largest answer read: room for a calendar with years of events, not a download. */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    /** The longest one ask may take, redirects and a slow answer included. */
    private static final long TOTAL_DEADLINE_MS = 30_000;
    /**
     * The longest a Try may take: three asks, the most any integration here makes at once
     * (Bitcoin reads the height, the fees and the prices). The web admin keeps a Try's connection
     * open this long.
     */
    static final long TRY_DEADLINE_MS = 3 * TOTAL_DEADLINE_MS;
    private static final int ASKING_THREADS = 3;
    /** How often the roles for the fleet's shared instances are worked out again. */
    private static final long ROLE_EVERY_MS = 10_000;
    /** A member counts as there when heard from within two check-ins. */
    private static final long FRESH_MS = 2 * FleetServer.HEARTBEAT_INTERVAL_MS;
    /** The states a passed-on reading may carry, the ones {@link #state} gives. */
    private static final java.util.Set<String> FLEET_STATES = new java.util.HashSet<>(
            java.util.Arrays.asList("ok", "failing", "unavailable", "needs_credentials",
                    "waiting"));

    /** Told when values or a status changed, so the status and MQTT follow. */
    interface Listener {
        void integrationsChanged();
    }

    /** One running instance: its settings as started, its schedule, what it last reported. */
    private static final class Running {
        final Integrations.Instance instance;
        final Integrations.Definition definition;
        final IntegrationSchedule schedule;
        final long intervalMs;
        /** The values by value id, unavailable until the first answer. */
        final Map<String, Integrations.Value> values = new LinkedHashMap<>();
        boolean asking;
        String problem;
        long updatedAtMs;
        /** Why it does not run at all (an edition it needs), or null. */
        final String blocked;
        /** The fleet shares it and this panel asks for the whole group: its reads are passed on. */
        boolean forFleet;
        /** The name of the panel that asks for the fleet, or null while this panel asks itself. */
        String askedBy;
        /** While another asks: when values last arrived, or when it took over (elapsed clock). */
        long fleetHeardMs;
        /** The read time of the values held, on this panel's wall clock; 0 for none. */
        long fleetReadAtMs;
        /** The asker's state and problem with the values held; null before the first. */
        String fleetState;
        String fleetProblem;
        /** No reading arrived for too long and the values were withdrawn. */
        boolean fleetStale;
        /** Every secret it needs is stored and readable; checked again until it is. */
        boolean secretsReady;

        Running(Integrations.Instance instance, Integrations.Definition definition,
                String blocked, long nowMs) {
            this.instance = instance;
            this.definition = definition;
            this.blocked = blocked;
            this.intervalMs = definition.intervalOf(instance);
            this.schedule = new IntegrationSchedule(intervalMs, definition.floorMs, nowMs);
            for (Integrations.ValueSpec spec : definition.valuesOf(instance)) {
                values.put(spec.id, Integrations.Value.unavailable(spec.type));
            }
        }
    }

    private final Context context;
    private final Automations.Engine engine;
    private final Listener listener;
    private final Map<String, Running> running = new LinkedHashMap<>();
    /** The instances as stored, the disabled and unknown ones included, for the status. */
    private List<Integrations.Instance> stored = new ArrayList<>();
    private ScheduledExecutorService ticker;
    private ExecutorService asking;
    /** When the roles were last worked out (elapsed clock), so they are not every second. */
    private long rolesAtMs = Long.MIN_VALUE / 2;

    IntegrationRunner(Context context, Automations.Engine engine, Listener listener) {
        this.context = context.getApplicationContext();
        this.engine = engine;
        this.listener = listener;
    }

    synchronized void start() {
        if (ticker != null) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "MuralisIntegrations");
            thread.setDaemon(true);
            return thread;
        });
        asking = Executors.newFixedThreadPool(ASKING_THREADS, task -> {
            Thread thread = new Thread(task, "MuralisIntegrationAsk");
            thread.setDaemon(true);
            return thread;
        });
        Integrations.lookup = this::valueSpec;
        reload();
        ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
            asking.shutdownNow();
            ticker = null;
            asking = null;
        }
        running.clear();
    }

    /**
     * Reads the stored instances again after a change on a settings surface. An instance whose
     * settings did not change keeps its schedule and values; a changed one starts afresh, so its
     * values are unavailable until its service answers with the new settings. Secrets are read at
     * each ask, so new credentials need {@link #credentialsReplaced}.
     */
    synchronized void reload() {
        stored = KioskConfig.integrationsOf(context);
        long now = SystemClock.elapsedRealtime();
        Map<String, Running> next = new LinkedHashMap<>();
        for (Integrations.Instance instance : stored) {
            Integrations.Definition definition = Integrations.DEFINITIONS.get(instance.definition);
            if (definition == null || !instance.enabled || definition.source == null) {
                continue;
            }
            Running old = running.get(instance.id);
            if (old != null && sameSettings(old.instance, instance)) {
                next.put(instance.id, old);
                continue;
            }
            next.put(instance.id, new Running(instance, definition, blockedBy(definition), now));
        }
        // Values of instances gone or switched off are withdrawn from the automations: an
        // unavailable sample, on which no rule fires and the state a rule saw stands.
        for (Running gone : running.values()) {
            if (!next.containsKey(gone.instance.id) || next.get(gone.instance.id) != gone) {
                for (Map.Entry<String, Integrations.Value> value : gone.values.entrySet()) {
                    feed(gone.instance.id, value.getKey(),
                            Integrations.Value.unavailable(value.getValue().type));
                }
            }
        }
        running.clear();
        running.putAll(next);
        decideRoles(now);
        changed();
    }

    /** New credentials were saved for this instance: ask at once, within the floor. */
    synchronized void credentialsReplaced(String instanceId) {
        Running one = running.get(instanceId);
        if (one != null) {
            one.schedule.credentialsReplaced(SystemClock.elapsedRealtime());
            one.problem = null;
            one.secretsReady = false;
            // Runnable again at once, so the asker can be this panel from the next tick.
            rolesAtMs = Long.MIN_VALUE / 2;
        }
    }

    /**
     * Asks this instance's service as soon as its floor allows; false when it is not running,
     * or when another panel asks for the fleet (its status says which).
     */
    synchronized boolean pollNow(String instanceId) {
        Running one = running.get(instanceId);
        if (one == null || one.askedBy != null) {
            return false;
        }
        one.schedule.pollNow(SystemClock.elapsedRealtime());
        return true;
    }

    private static boolean sameSettings(Integrations.Instance a, Integrations.Instance b) {
        return a.definition.equals(b.definition) && a.settings.equals(b.settings)
                && a.name.equals(b.name);
    }

    /** Why a definition does not run on this panel's edition, or null when it does. */
    private String blockedBy(Integrations.Definition definition) {
        if ("pro".equals(definition.edition) && !ProEntitlement.isActive(context)) {
            return "needs Muralis Pro";
        }
        if ("business".equals(definition.edition)) {
            return "needs Muralis Business";
        }
        return null;
    }

    private Integrations.ValueSpec valueSpec(String sensorId) {
        String[] parts = Integrations.parseSensorId(sensorId);
        if (parts == null) {
            return null;
        }
        synchronized (this) {
            for (Integrations.Instance instance : stored) {
                if (!instance.id.equals(parts[0])) {
                    continue;
                }
                Integrations.Definition definition =
                        Integrations.DEFINITIONS.get(instance.definition);
                if (definition == null) {
                    return null;
                }
                for (Integrations.ValueSpec spec : definition.valuesOf(instance)) {
                    if (spec.id.equals(parts[1])) {
                        return spec;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Works out, for each instance the fleet shares, whether this panel asks for the group or
     * another does (FleetAsker), and tells the fleet which ones this panel can run. True when a
     * role changed, so the status follows. Called holding the lock.
     */
    private boolean decideRoles(long now) {
        rolesAtMs = now;
        FleetServer.IntegrationsFleet fleet = FleetServer.integrationsFleet(context);
        java.util.Set<String> mine = new java.util.HashSet<>();
        for (Running one : running.values()) {
            if (!askedForFleet(fleet, one)) {
                continue;
            }
            if (!one.secretsReady) {
                one.secretsReady = secretsReady(one);
            }
            if (one.blocked == null && one.secretsReady && !one.schedule.needsCredentials()) {
                mine.add(one.instance.id);
            }
        }
        FleetServer.setOwnRunnable(mine);
        List<FleetAsker.Member> candidates = fleet == null ? null : fleet.candidates(mine);
        boolean changed = false;
        for (Running one : running.values()) {
            boolean shared = one.blocked == null && askedForFleet(fleet, one);
            String asker = shared ? FleetAsker.asker(candidates, fleet.self, one.instance.id,
                    now, FRESH_MS) : null;
            // Nobody able to ask (this panel neither): it asks for itself as before, and its
            // status says why it cannot.
            String askedBy = asker == null || asker.equals(fleet.self) ? null
                    : nameOrDefault(fleet.nameOf(asker));
            boolean forFleet = shared && askedBy == null;
            if (askedBy != null && one.askedBy == null) {
                // Stops asking; what it holds stands until it is too old.
                one.fleetState = state(one.instance, one.definition, one);
                one.fleetProblem = one.problem;
                one.fleetHeardMs = now;
                one.fleetReadAtMs = one.updatedAtMs;
                one.fleetStale = false;
                Log.i(TAG, "Integration " + one.instance.id + ": asked by " + askedBy);
            } else if (askedBy == null && one.askedBy != null) {
                // Asks from now on, at once as far as its floor and Retry-After let it: the
                // values it was passed may already be nearly an interval old.
                one.fleetState = null;
                one.fleetProblem = null;
                one.fleetStale = false;
                one.schedule.pollNow(now);
                Log.i(TAG, "Integration " + one.instance.id + ": asking "
                        + (forFleet ? "for the fleet" : "for this panel"));
            }
            changed |= forFleet != one.forFleet || !java.util.Objects.equals(askedBy, one.askedBy);
            one.forFleet = forFleet;
            one.askedBy = askedBy;
        }
        return changed;
    }

    /**
     * Whether one panel may ask for the whole group: the fleet shares the instance and every
     * setting of it is shared. A setting kept per panel (a room) makes each panel's answer its
     * own, so each asks for itself.
     */
    private static boolean askedForFleet(FleetServer.IntegrationsFleet fleet, Running one) {
        if (fleet == null || !fleet.instances.contains(one.instance.id)) {
            return false;
        }
        for (Integrations.Field field : one.definition.fields) {
            if (field.scope == Integrations.Scope.PANEL) {
                return false;
            }
        }
        return true;
    }

    private static String nameOrDefault(String name) {
        return name.isEmpty() ? "another panel" : name;
    }

    /** Whether every secret the instance needs is stored and can be read now. */
    private boolean secretsReady(Running one) {
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        for (Integrations.Field field : one.definition.fields) {
            if (!field.secret) {
                continue;
            }
            String value = store.getOrNull(Integrations.secretName(one.instance.id, field.id));
            if (value == null || (field.required && value.isEmpty())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Values another panel read for the fleet and passed on: taken as if this panel had read
     * them, when this panel holds the instance, another asks for it, and they are newer than
     * what it holds (or what it holds was withdrawn). Null when taken, else why not.
     */
    String receive(String instanceId, long readAtMs, String state, String problem,
            String asker, JSONObject values) {
        Map<String, Integrations.Value> toFeed = new LinkedHashMap<>();
        synchronized (this) {
            Running one = running.get(instanceId);
            if (one == null) {
                return "this panel does not run " + instanceId;
            }
            if (one.askedBy == null) {
                return "this panel asks for " + instanceId + " directly";
            }
            if (!FLEET_STATES.contains(state)) {
                return "unknown state " + state;
            }
            if (values == null) {
                return "the values are missing";
            }
            if (!"ok".equals(state) && !"waiting".equals(state)
                    && (problem == null || problem.isEmpty())) {
                return "the state " + state + " carries no reason";
            }
            if (!one.fleetStale && readAtMs <= one.fleetReadAtMs) {
                return "older than the values this panel holds";
            }
            for (Map.Entry<String, Integrations.Value> spec : one.values.entrySet()) {
                Integrations.Value next =
                        valueOf(values.optJSONObject(spec.getKey()), spec.getValue().type);
                spec.setValue(next);
                toFeed.put(spec.getKey(), next);
            }
            String before = one.fleetState;
            one.fleetReadAtMs = readAtMs;
            one.fleetHeardMs = SystemClock.elapsedRealtime();
            one.fleetStale = false;
            one.fleetState = state;
            one.fleetProblem = problem == null || problem.isEmpty() ? null : problem;
            if ("ok".equals(state)) {
                one.updatedAtMs = readAtMs;
            }
            if (!state.equals(before)) {
                Log.i(TAG, "Integration " + instanceId + " from " + asker + ": " + state
                        + (one.fleetProblem == null ? "" : ", " + one.fleetProblem));
            }
        }
        for (Map.Entry<String, Integrations.Value> value : toFeed.entrySet()) {
            feed(instanceId, value.getKey(), value.getValue());
        }
        changed();
        return null;
    }

    /** A value as it travels to the other panels: its type, whether it holds one, and what. */
    private static JSONObject valueJson(Integrations.Value value) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", value.type.name());
        // JSON has no NaN or infinity: such a number travels as no value.
        boolean available = value.available && (value.type == Integrations.Type.FLAG
                || value.type == Integrations.Type.TEXT || Double.isFinite(value.number));
        json.put("available", available);
        if (available) {
            json.put("value", value.type == Integrations.Type.FLAG ? (Object) value.flag
                    : value.type == Integrations.Type.TEXT ? (Object) value.text
                    : (Object) value.number);
            if (!value.attributes.isEmpty()) {
                json.put("attributes", new JSONObject(value.attributes));
            }
        }
        return json;
    }

    /** A value that travelled, or unavailable when missing, of another type or unreadable. */
    private static Integrations.Value valueOf(JSONObject json, Integrations.Type type) {
        if (json == null || !json.optBoolean("available") || !type.name().equals(
                json.optString("type")) || !json.has("value")) {
            return Integrations.Value.unavailable(type);
        }
        Integrations.Value value;
        switch (type) {
            case FLAG:
                value = Integrations.Value.of(json.optBoolean("value"));
                break;
            case TEXT:
                value = Integrations.Value.of(json.optString("value"));
                break;
            case TIME:
                value = Integrations.Value.at(json.optLong("value"));
                break;
            default:
                double number = json.optDouble("value");
                if (Double.isNaN(number)) {
                    return Integrations.Value.unavailable(type);
                }
                value = Integrations.Value.of(number);
        }
        JSONObject attributes = json.optJSONObject("attributes");
        if (attributes != null) {
            Map<String, String> details = new LinkedHashMap<>();
            java.util.Iterator<String> keys = attributes.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                details.put(key, attributes.optString(key));
            }
            value = value.with(details);
        }
        return value;
    }

    private void tick() {
        List<Running> due = new ArrayList<>();
        // Instance id, value id and the unavailable value, fed once the lock is let go.
        List<Object[]> withdrawn = new ArrayList<>();
        boolean changed = false;
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (now - rolesAtMs >= ROLE_EVERY_MS) {
                changed = decideRoles(now);
            }
            for (Running one : running.values()) {
                if (one.askedBy != null) {
                    // Another panel asks: nothing to do here but drop its values once they are
                    // too old (no backlog: they never stand in for a silent asker).
                    if (!one.fleetStale && FleetAsker.stale(one.fleetHeardMs, one.intervalMs,
                            FleetServer.HEARTBEAT_INTERVAL_MS, now)) {
                        one.fleetStale = true;
                        Log.i(TAG, "Integration " + one.instance.id + ": no reading from "
                                + one.askedBy + ", values withdrawn");
                        for (Map.Entry<String, Integrations.Value> spec : one.values.entrySet()) {
                            Integrations.Value gone =
                                    Integrations.Value.unavailable(spec.getValue().type);
                            spec.setValue(gone);
                            withdrawn.add(new Object[] {one.instance.id, spec.getKey(), gone});
                        }
                        changed = true;
                    }
                    continue;
                }
                if (one.blocked == null && !one.asking && one.schedule.start(now)) {
                    one.asking = true;
                    due.add(one);
                }
            }
        }
        for (Object[] gone : withdrawn) {
            feed((String) gone[0], (String) gone[1], (Integrations.Value) gone[2]);
        }
        if (changed) {
            changed();
        }
        for (Running one : due) {
            try {
                asking.execute(() -> ask(one));
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                return;
            }
        }
    }

    private void ask(Running one) {
        Map<String, String> secrets = new HashMap<>();
        String unreadable = null;
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        for (Integrations.Field field : one.definition.fields) {
            if (!field.secret) {
                continue;
            }
            String value = store.getOrNull(Integrations.secretName(one.instance.id, field.id));
            if (value == null) {
                unreadable = field.label;
                break;
            }
            secrets.put(field.id, value);
        }
        Integrations.Result result;
        if (unreadable != null) {
            // Before the first unlock the Keystore may not answer yet: a failure, tried again.
            result = Integrations.Result.failed(unreadable + " cannot be read yet", 0);
        } else {
            try {
                result = one.definition.source.fetch(one.definition, one.instance, secrets,
                        IntegrationRunner::get);
            } catch (RuntimeException broken) {
                Log.w(TAG, one.instance.definition + " failed in the app", broken);
                result = Integrations.Result.failed("the integration failed in the app", 0);
            }
        }
        record(one, result);
    }

    private void record(Running one, Integrations.Result result) {
        List<String[]> logLines = new ArrayList<>();
        Map<String, Integrations.Value> toFeed = new LinkedHashMap<>();
        JSONObject forFleet = null;
        synchronized (this) {
            one.asking = false;
            if (running.get(one.instance.id) != one || one.askedBy != null) {
                // Removed or changed while its service was being asked, or another panel asks
                // for the fleet now: what it said is not this panel's to keep.
                return;
            }
            long now = SystemClock.elapsedRealtime();
            String before = one.problem;
            if (result.problem == null) {
                one.schedule.succeeded();
                one.problem = null;
                one.updatedAtMs = System.currentTimeMillis();
                for (Map.Entry<String, Integrations.Value> spec : one.values.entrySet()) {
                    Integrations.Value value = result.values.get(spec.getKey());
                    Integrations.Value next = value != null && value.type == spec.getValue().type
                            ? value : Integrations.Value.unavailable(spec.getValue().type);
                    spec.setValue(next);
                    toFeed.put(spec.getKey(), next);
                }
            } else {
                if (result.credentialsRefused) {
                    one.schedule.credentialsRefused();
                } else {
                    one.schedule.failed(now, result.retryAfterMs);
                }
                one.problem = result.problem;
                if (one.schedule.unavailable()) {
                    for (Map.Entry<String, Integrations.Value> spec : one.values.entrySet()) {
                        Integrations.Value gone =
                                Integrations.Value.unavailable(spec.getValue().type);
                        spec.setValue(gone);
                        toFeed.put(spec.getKey(), gone);
                    }
                }
            }
            // Logged once when it starts failing and once when it recovers, not every poll.
            if (one.problem != null && !one.problem.equals(before)) {
                logLines.add(new String[] {one.instance.id, "failing: " + one.problem});
            } else if (one.problem == null && before != null) {
                logLines.add(new String[] {one.instance.id, "answering again"});
            }
            if (one.forFleet) {
                forFleet = fleetBody(one);
            }
        }
        for (String[] line : logLines) {
            Log.i(TAG, "Integration " + line[0] + " " + line[1]);
        }
        for (Map.Entry<String, Integrations.Value> value : toFeed.entrySet()) {
            feed(one.instance.id, value.getKey(), value.getValue());
        }
        changed();
        if (forFleet != null) {
            FleetServer.pushValues(context, one.instance.id, forFleet);
        }
    }

    /** What the other panels are told after a read for the fleet; see FleetServer.takeValues. */
    private JSONObject fleetBody(Running one) {
        try {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, Integrations.Value> value : one.values.entrySet()) {
                values.put(value.getKey(), valueJson(value.getValue()));
            }
            return new JSONObject()
                    .put("instance", one.instance.id)
                    .put("read_at", System.currentTimeMillis())
                    .put("state", state(one.instance, one.definition, one))
                    .put("problem", one.problem == null ? "" : one.problem)
                    .put("asker", KioskConfig.deviceIdOf(context))
                    .put("values", values);
        } catch (JSONException impossible) {
            // Keys are constants, values strings, numbers and booleans; a NaN is never available.
            throw new IllegalStateException(impossible);
        }
    }

    private void feed(String instanceId, String valueId, Integrations.Value value) {
        if (engine != null) {
            engine.sample(Integrations.sensorId(instanceId, valueId), value.sample());
        }
    }

    private void changed() {
        KioskRuntimeState.publishIntegrations(snapshot());
        if (listener != null) {
            listener.integrationsChanged();
        }
    }

    /**
     * The block the status document carries: every stored instance with its definition, name,
     * whether it runs, its state and status line, its settings that are not secret, which secret
     * settings hold a value, and its values with their sensor ids. Never a secret.
     */
    synchronized JSONArray snapshot() {
        JSONArray block = new JSONArray();
        SecretStore store = new SecretStore(KioskConfig.storageContext(context));
        try {
            for (Integrations.Instance instance : stored) {
                Integrations.Definition definition =
                        Integrations.DEFINITIONS.get(instance.definition);
                Running one = running.get(instance.id);
                JSONObject entry = new JSONObject();
                entry.put("id", instance.id);
                entry.put("definition", instance.definition);
                entry.put("definition_name", definition == null ? instance.definition
                        : definition.name);
                entry.put("name", instance.name);
                entry.put("enabled", instance.enabled);
                JSONObject settings = new JSONObject();
                JSONArray secretsSet = new JSONArray();
                if (definition != null) {
                    for (Integrations.Field field : definition.fields) {
                        if (field.secret) {
                            if (store.has(Integrations.secretName(instance.id, field.id))) {
                                secretsSet.put(field.id);
                            }
                        } else {
                            settings.put(field.id, instance.settings.getOrDefault(field.id, ""));
                        }
                    }
                }
                entry.put("settings", settings);
                entry.put("secrets_set", secretsSet);
                String state = state(instance, definition, one);
                entry.put("state", state);
                entry.put("status", statusLine(state, one));
                if (one != null && one.updatedAtMs > 0) {
                    entry.put("updated_at", one.updatedAtMs);
                }
                JSONObject values = new JSONObject();
                if (definition != null) {
                    for (Integrations.ValueSpec spec : definition.valuesOf(instance)) {
                        Integrations.Value value = one == null ? null : one.values.get(spec.id);
                        JSONObject v = new JSONObject();
                        v.put("sensor", Integrations.sensorId(instance.id, spec.id));
                        v.put("name", spec.label);
                        v.put("type", spec.type.name().toLowerCase(java.util.Locale.ROOT));
                        v.put("unit", spec.unit);
                        boolean available = value != null && value.available;
                        v.put("available", available);
                        v.put("value", !available ? JSONObject.NULL
                                : value.type == Integrations.Type.FLAG ? (Object) value.flag
                                : value.type == Integrations.Type.TEXT ? (Object) value.text
                                : (Object) value.number);
                        if (available && !value.attributes.isEmpty()) {
                            v.put("attributes", new JSONObject(value.attributes));
                        }
                        values.put(spec.id, v);
                    }
                }
                entry.put("values", values);
                block.put(entry);
            }
        } catch (JSONException impossible) {
            // Keys are constants and values are strings, numbers and booleans.
        }
        return block;
    }

    /** The block without each instance's settings, for MQTT's retained state. */
    static JSONArray withoutSettings(JSONArray block) {
        JSONArray bare = new JSONArray();
        for (int index = 0; index < block.length(); index++) {
            JSONObject entry = block.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            try {
                JSONObject copy = new JSONObject(entry.toString());
                copy.remove("settings");
                bare.put(copy);
            } catch (JSONException impossible) {
                // It was built as JSON a moment ago.
            }
        }
        return bare;
    }

    /**
     * The key a panel page watches: who is added, named, on and how each is doing, without the
     * values, so a value arriving every few seconds does not redraw a box being typed in.
     */
    static String panelListKey(JSONArray block) {
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < block.length(); index++) {
            JSONObject entry = block.optJSONObject(index);
            if (entry != null) {
                key.append(entry.optString("id")).append('|').append(entry.optString("name"))
                        .append('|').append(entry.optBoolean("enabled")).append('|')
                        .append(entry.optString("state")).append('\n');
            }
        }
        return Integer.toHexString(key.toString().hashCode());
    }

    /**
     * The names the automations' editors and sentences give the integrations' values, by sensor
     * id: the instance's name, and the value's where an instance reports several.
     */
    static Map<String, String> sensorNames(JSONArray block) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int index = 0; index < block.length(); index++) {
            JSONObject entry = block.optJSONObject(index);
            JSONObject values = entry == null ? null : entry.optJSONObject("values");
            if (values == null) {
                continue;
            }
            java.util.Iterator<String> ids = values.keys();
            while (ids.hasNext()) {
                JSONObject value = values.optJSONObject(ids.next());
                if (value != null) {
                    names.put(value.optString("sensor"), entry.optString("name")
                            + (values.length() > 1 ? " " + value.optString("name") : ""));
                }
            }
        }
        return names;
    }

    /**
     * The key a page's list of integrations was drawn from: when the status carries another, the
     * list is drawn again (admin_stats.js, KioskActivity.watchLists), so an integration added,
     * renamed or failing on one surface shows on the other within seconds.
     */
    static String listKey(JSONArray block) {
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < block.length(); index++) {
            JSONObject entry = block.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            key.append(entry.optString("id")).append('|').append(entry.optString("name"))
                    .append('|').append(entry.optBoolean("enabled")).append('|')
                    .append(entry.optString("status")).append('|');
            JSONObject values = entry.optJSONObject("values");
            if (values != null) {
                key.append(values.toString());
            }
            key.append('\n');
        }
        return Integer.toHexString(key.toString().hashCode());
    }

    private static String state(Integrations.Instance instance,
            Integrations.Definition definition, Running one) {
        if (definition == null) {
            return "unknown";
        }
        if (!instance.enabled) {
            return "off";
        }
        if (one == null) {
            return "off";
        }
        if (one.blocked != null) {
            return "blocked";
        }
        if (one.askedBy != null) {
            return one.fleetStale ? "unavailable"
                    : one.fleetState == null ? "waiting" : one.fleetState;
        }
        if (one.schedule.needsCredentials()) {
            return "needs_credentials";
        }
        if (one.problem != null) {
            return one.schedule.unavailable() ? "unavailable" : "failing";
        }
        return one.schedule.unavailable() ? "waiting" : "ok";
    }

    /**
     * The one line a row shows, the same words on both surfaces: for an instance the fleet
     * shares, also who asks for it.
     */
    private static String statusLine(String state, Running one) {
        if (one != null && one.askedBy != null && !"blocked".equals(state)) {
            if (one.fleetStale) {
                long minutes = (SystemClock.elapsedRealtime() - one.fleetHeardMs) / 60_000;
                return "No reading from the fleet for " + minutes
                        + (minutes == 1 ? " minute" : " minutes");
            }
            String by = "Asked by " + one.askedBy;
            return "ok".equals(state) || "waiting".equals(state) ? by
                    : by + ". " + line(state, null, one.fleetProblem);
        }
        if (one != null && one.forFleet && "ok".equals(state)) {
            return "Fetches for the whole fleet";
        }
        return line(state, one == null ? null : one.blocked, one == null ? null : one.problem);
    }

    private static String line(String state, String blocked, String problem) {
        switch (state) {
            case "unknown":
                return "Not in this version of Muralis";
            case "off":
                return "Off";
            case "blocked":
                return "Off: " + blocked;
            case "needs_credentials":
                return "Stopped: " + problem + ". Save a new key to start again.";
            case "unavailable":
                return "Not answering: " + problem;
            case "failing":
                return "Last ask failed: " + problem;
            case "waiting":
                return "Asking for the first time";
            default:
                return "Answering";
        }
    }

    /**
     * One GET with the limits every integration is held to: timeouts, an answer of at most
     * {@link #MAX_BODY_BYTES}, redirects followed by hand only to http and https (never to a
     * file or another scheme) and at most {@link #MAX_REDIRECTS}. The headers a source sets
     * go only to the address it named, never on to a redirect's other host.
     */
    static Integrations.Response get(String address, Map<String, String> headers)
            throws IOException {
        String current = address;
        String firstHost = new URL(address).getHost();
        String firstScheme = new URL(address).getProtocol();
        long deadline = SystemClock.elapsedRealtime() + TOTAL_DEADLINE_MS;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                throw new Integrations.PlainFailure(hop == 0
                        ? "the address must start with http:// or https://"
                        : "redirected to a " + url.getProtocol() + " address");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "Muralis");
                // The source's headers (a key) only to the very host and scheme it named: never
                // on to another host, nor down from https to plain http on the same one.
                boolean sameHost = url.getHost().equalsIgnoreCase(firstHost)
                        && url.getProtocol().equals(firstScheme);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (sameHost || "Accept".equals(header.getKey())) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400 && connection.getHeaderField("Location") != null) {
                    current = new URL(url, connection.getHeaderField("Location")).toString();
                    continue;
                }
                InputStream in = status >= 400 ? connection.getErrorStream()
                        : connection.getInputStream();
                String body = in == null ? "" : readCapped(in, deadline);
                return new Integrations.Response(status, body,
                        retryAfterMs(connection.getHeaderField("Retry-After")));
            } finally {
                connection.disconnect();
            }
        }
        throw new Integrations.PlainFailure("more than " + MAX_REDIRECTS + " redirects");
    }

    private static String readCapped(InputStream in, long deadline) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                // A server that drips a byte now and then is cut off, not waited for forever.
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw new Integrations.PlainFailure("the answer took longer than "
                            + (TOTAL_DEADLINE_MS / 1000) + " s");
                }
                if (out.size() + read > MAX_BODY_BYTES) {
                    throw new Integrations.PlainFailure("the answer is larger than "
                            + (MAX_BODY_BYTES / 1024 / 1024) + " MB");
                }
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Retry-After as seconds or as an HTTP date, in milliseconds; 0 when absent or unreadable. */
    static long retryAfterMs(String header) {
        if (header == null) {
            return 0;
        }
        String value = header.trim();
        if (value.matches("\\d{1,9}")) {
            return Long.parseLong(value) * 1000L;
        }
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            long at = format.parse(value).getTime();
            return Math.max(0, at - System.currentTimeMillis());
        } catch (java.text.ParseException | NullPointerException unreadable) {
            return 0;
        }
    }
}
