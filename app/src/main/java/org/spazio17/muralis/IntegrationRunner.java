/*
 * Copyright 2026 Muralis contributors
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
    private static final int ASKING_THREADS = 3;

    /** Told when values or a status changed, so the status and MQTT follow. */
    interface Listener {
        void integrationsChanged();
    }

    /** One running instance: its settings as started, its schedule, what it last reported. */
    private static final class Running {
        final Integrations.Instance instance;
        final Integrations.Definition definition;
        final IntegrationSchedule schedule;
        /** The values by value id, unavailable until the first answer. */
        final Map<String, Integrations.Value> values = new LinkedHashMap<>();
        boolean asking;
        String problem;
        long updatedAtMs;
        /** Why it does not run at all (an edition it needs), or null. */
        final String blocked;

        Running(Integrations.Instance instance, Integrations.Definition definition,
                String blocked, long nowMs) {
            this.instance = instance;
            this.definition = definition;
            this.blocked = blocked;
            this.schedule = new IntegrationSchedule(definition.intervalOf(instance),
                    definition.floorMs, nowMs);
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
        changed();
    }

    /** New credentials were saved for this instance: ask at once, within the floor. */
    synchronized void credentialsReplaced(String instanceId) {
        Running one = running.get(instanceId);
        if (one != null) {
            one.schedule.credentialsReplaced(SystemClock.elapsedRealtime());
            one.problem = null;
        }
    }

    /** Asks this instance's service as soon as its floor allows; false when it is not running. */
    synchronized boolean pollNow(String instanceId) {
        Running one = running.get(instanceId);
        if (one == null) {
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

    private void tick() {
        List<Running> due = new ArrayList<>();
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            for (Running one : running.values()) {
                if (one.blocked == null && !one.asking && one.schedule.start(now)) {
                    one.asking = true;
                    due.add(one);
                }
            }
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
        synchronized (this) {
            one.asking = false;
            if (running.get(one.instance.id) != one) {
                // Removed or changed while its service was being asked: what it said is old.
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
        }
        for (String[] line : logLines) {
            Log.i(TAG, "Integration " + line[0] + " " + line[1]);
        }
        for (Map.Entry<String, Integrations.Value> value : toFeed.entrySet()) {
            feed(one.instance.id, value.getKey(), value.getValue());
        }
        changed();
    }

    private void feed(String instanceId, String valueId, Integrations.Value value) {
        if (engine != null) {
            engine.sample(Integrations.sensorId(instanceId, valueId), value.sample(),
                    SystemClock.elapsedRealtime(), Sensors.minuteOfDay());
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
        if (one.schedule.needsCredentials()) {
            return "needs_credentials";
        }
        if (one.problem != null) {
            return one.schedule.unavailable() ? "unavailable" : "failing";
        }
        return one.schedule.unavailable() ? "waiting" : "ok";
    }

    /** The one line a row shows, the same words on both surfaces. */
    private static String statusLine(String state, Running one) {
        switch (state) {
            case "unknown":
                return "Not in this version of Muralis";
            case "off":
                return "Off";
            case "blocked":
                return "Off: " + one.blocked;
            case "needs_credentials":
                return "Stopped: " + one.problem + ". Save a new key to start again.";
            case "unavailable":
                return "Not answering: " + one.problem;
            case "failing":
                return "Last ask failed: " + one.problem;
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
                throw new IOException("redirected to a " + url.getProtocol() + " address");
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
        throw new IOException("more than " + MAX_REDIRECTS + " redirects");
    }

    private static String readCapped(InputStream in, long deadline) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                // A server that drips a byte now and then is cut off, not waited for forever.
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw new IOException("the answer took longer than "
                            + (TOTAL_DEADLINE_MS / 1000) + " s");
                }
                if (out.size() + read > MAX_BODY_BYTES) {
                    throw new IOException("the answer is larger than "
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
