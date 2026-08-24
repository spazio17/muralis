/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * MQTT transport for telemetry and commands.
 *
 * <p><b>Trust model (revised 2026-08-18):</b> the authenticated thing here is the
 * <em>connection</em>, not the individual message. The defences are the broker's own: a username
 * and password, TLS, and an ACL scoped to this device's topic prefix. This is deliberately the same
 * posture as WLED and Tasmota, the two devices this project takes its dual-transport design from.
 *
 * <p>An earlier design signed every command with an HMAC-SHA256 envelope (id/timestamp/nonce/mac)
 * keyed on a 32-byte secret. It was removed because the secret was generated on-device and never
 * surfaced in any UI, log or API, so nothing, including the tablet's owner or Home Assistant,
 * could ever produce a valid signature: MQTT commands were unusable in practice while appearing to
 * work. A stronger scheme nobody can hold the key to is weaker than a simple one that is actually
 * used. If per-message signing returns, it must ship with a way to read and set the key.
 */
final class MqttController implements MqttCallbackExtended {
    interface CommandListener {
        void onCommand(String id, String command, JSONObject arguments);
    }

    private static final String TAG = "MuralisMqtt";
    private static final Pattern BROKER_HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern SAFE_COMMAND = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    /** Mirrors HttpAdminServer.MAX_BODY_BYTES, so neither transport accepts what the other caps. */
    static final int MAX_COMMAND_BYTES = 16_384;
    /** Same ceiling as a command name; long enough for any honest correlation id. */
    private static final int MAX_COMMAND_ID_LENGTH = 96;

    private final KioskConfig config;
    private final CommandListener commandListener;
    private final String topicPrefix;
    /**
     * How often {@link #heartbeat()} reasserts availability, and how long Home Assistant waits
     * before deciding the panel has gone quiet. See {@link #publishMqttStateEntity} for why the
     * Last Will alone is not enough.
     */
    static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final int HEARTBEAT_EXPIRY_SECONDS = 90;
    /**
     * How long a deliberate shutdown waits for its "offline" to reach the broker. Short: this runs
     * on the way to disconnecting and a configuration reload must not visibly hang on a broker that
     * has already gone away.
     */
    private static final long FAREWELL_TIMEOUT_MS = 1_000L;

    /** Real hardware identity for Home Assistant discovery. See {@link #publishDiscovery()}. */
    private final String deviceManufacturer;
    private final String deviceModel;
    private final String appVersion;
    /**
     * Read once at construction rather than per discovery run: a light sensor cannot appear or
     * disappear on a running device, and the controller is rebuilt whenever configuration reloads.
     */
    private final boolean hasLightSensor;
    /**
     * Volatile because Paho's callback thread reads it ({@code connectComplete}, {@code publish})
     * while the main thread replaces it in {@link #start}/{@link #stop}. Without it a reader can
     * see a stale non-null reference to an already-closed client.
     */
    private volatile MqttAsyncClient client;
    /**
     * Network downtime history, owned by the service; null-tolerated so a missing
     * ConnectivityManager degrades to "every outage reads as a broker outage" instead of a crash.
     */
    private final OutageLedger outageLedger;
    private final Context appContext;
    /**
     * When {@link #connectionLost} last fired, on the elapsedRealtime clock, or 0 while connected.
     * Volatile: written and read on Paho's callback threads across an outage.
     */
    private volatile long connectionLostAtMs;

    MqttController(Context context, CommandListener commandListener, OutageLedger outageLedger) {
        config = KioskConfig.load(context);
        this.commandListener = commandListener;
        this.outageLedger = outageLedger;
        appContext = context.getApplicationContext();
        topicPrefix = "kiosk/" + config.deviceId + "/";
        deviceManufacturer = android.os.Build.MANUFACTURER;
        deviceModel = android.os.Build.MODEL;
        appVersion = TelemetryCollector.appVersionName(context);
        hasLightSensor = KioskService.hasLightSensor(context);
    }

    void start() {
        if (config.mqttHost.isEmpty()) {
            Log.i(TAG, "MQTT is not configured");
            return;
        }
        // Both settings surfaces refuse a bad id before storing it (see
        // KioskCommandDispatcher.validateDeviceId), so this is the last line of defence for ids
        // stored before that check existed, not the place an operator ever hears about one.
        String idProblem = KioskCommandDispatcher.validateDeviceId(config.deviceId);
        if (!BROKER_HOST.matcher(config.mqttHost).matches() || idProblem != null) {
            Log.e(TAG, "MQTT broker host or device ID is invalid"
                    + (idProblem != null ? ": " + idProblem : ""));
            return;
        }

        // Plain TCP only. TLS was offered as a checkbox and removed on 2026-08-19, because it was
        // never actually implemented: the whole of it was "ssl" instead of "tcp" here, with no trust
        // store, no SSLSocketFactory and no way anywhere in the app to supply a certificate. Paho
        // therefore fell back to Android's system trust store with hostname verification on, so it
        // could only ever work against a publicly-trusted CA certificate on a matching public
        // hostname. Every ordinary home broker uses a self-signed or private-CA certificate, so for
        // the actual audience the option could not work at all, and it was ON by default, which made
        // a fresh install fail its first connection.
        //
        // That is the same mistake this project already corrected once, when the per-command HMAC was
        // removed for advertising protection nobody could hold the key to.
        //
        // The reference implementations agree with dropping it: WLED supports no MQTT TLS at all and
        // tells users to stay on a local broker, and Tasmota's TLS is a compile-time option, off in
        // default builds, which needs CA-certificate or public-key-fingerprint pinning to work with a
        // self-signed broker. Adding TLS properly here means adding that same machinery: a
        // certificate or fingerprint field, storage for it, and an SSLSocketFactory built from it.
        // Until that exists, this stays plain TCP and the documentation says so plainly.
        String uri = "tcp://" + config.mqttHost + ":" + config.mqttPort;
        try {
            client = new MqttAsyncClient(uri, config.deviceId, new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(15);
            options.setKeepAliveInterval(30);
            options.setHttpsHostnameVerificationEnabled(true);
            options.setWill(topicPrefix + "availability",
                    "offline".getBytes(StandardCharsets.UTF_8), 1, true);
            if (!config.mqttUsername.isEmpty()) {
                options.setUserName(config.mqttUsername);
            }
            if (!config.mqttPassword.isEmpty()) {
                options.setPassword(config.mqttPassword.toCharArray());
            }
            client.connect(options);
        } catch (MqttException exception) {
            Log.e(TAG, "Unable to start MQTT client", exception);
        }
    }

    void stop() {
        MqttAsyncClient stopping = client;
        if (stopping == null) {
            return;
        }
        // Null the field first: connectComplete runs on Paho's own thread and would otherwise see
        // a client we are in the middle of tearing down.
        client = null;
        try {
            if (stopping.isConnected()) {
                // Explicitly against `stopping`, and waited on.
                //
                // This used to call publish(), which reads the `client` field, and the line above
                // has just set that field to null. So it returned immediately and silently, and
                // the farewell has not actually been sent since the null-first ordering was
                // introduced. The effect was invisible on the panel and glaring in Home Assistant:
                // a deliberate shutdown sends a DISCONNECT, which by MQTT's own rules tells the
                // broker *not* to fire the Last Will, so with the "offline" missing too, nothing
                // ever replaced the retained "online". Change the broker host, or stop the panel
                // for maintenance, and the dashboard went on reporting it connected forever.
                // Found 2026-08-23 against a throwaway broker; the log showed the DISCONNECT with
                // no "offline" before it.
                //
                // Waited on because the publish is asynchronous and disconnectForcibly is about to
                // tear the socket down underneath it. Its quiesce timeout would usually cover this,
                // but "usually" is what produced the bug above.
                publishAndWait(stopping, topicPrefix + "availability", "offline");
            }
            // Unconditionally, not just when connected. A client caught mid-CONNECT is neither
            // connected nor idle, and close() refuses to tear one down in that state
            // (MqttException 32110/32100), which used to leave the old client, its socket and its
            // automatic-reconnect timer running with nobody holding a reference to stop them.
            // startControllersNow then built a second client with the *same* client id and a clean
            // session, so once the broker returned the two evicted each other in a permanent flap,
            // and every later reload added one more. Forcing the disconnect first makes close()
            // legal from any state.
            stopping.disconnectForcibly(1_000L, 1_000L);
        } catch (MqttException exception) {
            Log.w(TAG, "Error while disconnecting MQTT client", exception);
        }
        try {
            // The forcing overload: it stops the reconnect cycle as well, which the no-argument
            // close() does not, and is what actually releases the client's threads.
            stopping.close(true);
        } catch (MqttException exception) {
            Log.w(TAG, "Error while closing MQTT client", exception);
        }
    }

    void publishState(JSONObject state) {
        publish(topicPrefix + "state", state.toString(), 0, true);
    }

    void publishCommandResult(String id, String status, String detail) {
        JSONObject result = new JSONObject();
        try {
            result.put("id", id);
            result.put("status", status);
            result.put("detail", detail);
            result.put("timestamp_ms", System.currentTimeMillis());
            publish(topicPrefix + "command/result", result.toString(), 1, false);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        Log.i(TAG, reconnect ? "MQTT reconnected" : "MQTT connected");
        // A survived outage is judged here, on the way back, because that is the first moment the
        // verdict can reach anyone: "MQTT down, network up" is unreportable while it is true, the
        // channel that would carry it is the one that is broken. The Last Will cannot say it
        // either, its payload is frozen at connect time. So the panel reports outages in
        // hindsight: the ledger says whether the device's network was down during the outage, and
        // the answer separates a Wi-Fi problem from a broker one, which no live entity can.
        long lostAt = connectionLostAtMs;
        if (reconnect && lostAt != 0) {
            connectionLostAtMs = 0;
            long now = android.os.SystemClock.elapsedRealtime();
            String cause = outageLedger == null
                    ? OutageLedger.CAUSE_BROKER : outageLedger.causeOfMqttOutage(lostAt, now);
            KioskRuntimeState.recordMqttOutage(cause, now - lostAt);
            Log.i(TAG, "MQTT outage of " + (now - lostAt) + "ms attributed to: " + cause);
            // Early publish, so Home Assistant hears the verdict seconds after the entities come
            // back rather than at the next scheduled tick.
            KioskService.publishTelemetrySoon(appContext);
        }
        // Copied to a local, the way publish() already does. This runs on Paho's thread while
        // stop() can be nulling the field from the main thread, so a configuration reload landing
        // exactly as the broker connection completes used to NPE here and kill the process.
        MqttAsyncClient active = client;
        if (active == null) {
            return;
        }
        try {
            publish(topicPrefix + "availability", "online", 1, true);
            active.subscribe(topicPrefix + "command", 1);
            active.subscribe("homeassistant/status", 0);
            publishDiscovery();
        } catch (MqttException exception) {
            Log.e(TAG, "Unable to initialize MQTT session", exception);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        connectionLostAtMs = android.os.SystemClock.elapsedRealtime();
        Log.w(TAG, "MQTT connection lost; automatic reconnect is enabled", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (topic.equals("homeassistant/status")) {
            String status = new String(message.getPayload(), StandardCharsets.UTF_8);
            if (status.equals("online")) {
                publishDiscovery();
            }
            return;
        }
        if (!topic.equals(topicPrefix + "command")) {
            return;
        }
        // Retained commands stay rejected. Nothing to do with authentication: a retained message is
        // redelivered on every reconnect, so one would re-fire itself after every network blip and
        // every reboot. WLED and Tasmota do not publish retained commands either.
        if (message.isRetained()) {
            Log.w(TAG, "Rejected retained MQTT command");
            // Say so, rather than only logging: somebody who published with -r sees their command
            // ignored on every reconnect with no clue why, and the device log is not somewhere a
            // Home Assistant automation can look.
            publishCommandResult("", "rejected",
                    "retained commands are refused; publish without the retain flag");
            // Then clear it. A retained command sits on the broker forever and is redelivered on
            // every single reconnect, so leaving it there means this refusal repeats for the life of
            // the device. Clearing turns a permanent landmine into a one-off mistake. Writing to our
            // own command topic is within the ACL the contract prescribes (readwrite on
            // kiosk/<device_id>/#).
            publish(topicPrefix + "command", "", 0, true);
            return;
        }
        if (message.getPayload().length > MAX_COMMAND_BYTES) {
            // The HTTP twin has capped bodies at 16 KB since the flood fixes; a broker connection
            // had no ceiling at all, so a single publish could hand this process a payload of any
            // size the broker allows. Refused before decoding, with the reason on the result
            // topic; no id is available because the envelope goes unread.
            Log.w(TAG, "Rejected oversized MQTT command payload ("
                    + message.getPayload().length + " bytes)");
            publishCommandResult("", "rejected",
                    "payload exceeds " + MAX_COMMAND_BYTES + " bytes");
            return;
        }
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
        if (payload.isEmpty()) {
            // Deliberately silent, and it must stay silent: clearing a retained command is done by
            // publishing an empty retained message, including by this class just above. Replying to
            // an empty payload would make that clear-up trigger its own rejection.
            return;
        }
        String id = "";
        String command;
        JSONObject arguments = new JSONObject();
        if (payload.startsWith("{")) {
            try {
                JSONObject envelope = new JSONObject(payload);
                command = envelope.optString("command", "");
                // The id is echoed into results and logged, so it is bounded and stripped of
                // control characters here, once, before any use. Unfiltered, an embedded newline
                // forged extra log lines in logcat, and there was no length limit at all.
                id = sanitizeCommandId(envelope.optString("id", ""));
                JSONObject args = envelope.optJSONObject("args");
                if (args != null) {
                    arguments = args;
                }
            } catch (JSONException malformed) {
                Log.w(TAG, "Rejected malformed MQTT command", malformed);
                // No id is available, since the envelope is what failed to parse, so the reply carries
                // an empty one. Still worth publishing: silence is indistinguishable from a broker
                // problem or an offline device, which is the wrong thing to tell a caller that in fact
                // reached a healthy device and sent it nonsense.
                publishCommandResult("", "rejected", "malformed JSON payload");
                return;
            }
        } else {
            // A bare command name as the whole payload, so `mosquitto_pub -m kiosk.reload` works.
            // This is the WLED/Tasmota convenience shape and carries no arguments.
            command = payload;
        }
        if (!SAFE_COMMAND.matcher(command).matches()) {
            Log.w(TAG, "Rejected MQTT command with an unusable name");
            // The id survived parsing here even though the name did not, so the caller can correlate
            // this rejection with its request. The offending name is deliberately not echoed back: it
            // is attacker-controlled text and this result is consumed by dashboards.
            publishCommandResult(id, "rejected",
                    "command name must match [A-Za-z0-9._:-] and be 1 to 96 characters");
            return;
        }
        commandListener.onCommand(id, command, arguments);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Delivery acknowledgements do not need a second application-level log.
    }

    /**
     * Publishes on an explicitly named client and waits for it to land, for the one caller that
     * cannot use {@link #publish}: {@code stop()} has already cleared the {@code client} field by
     * the time it says goodbye. Failures are logged and swallowed, because a shutdown that cannot
     * reach the broker still has to finish shutting down.
     */
    private void publishAndWait(MqttAsyncClient target, String topic, String payload) {
        try {
            target.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true)
                    .waitForCompletion(FAREWELL_TIMEOUT_MS);
        } catch (MqttException exception) {
            Log.w(TAG, "Could not publish the offline notice before disconnecting", exception);
        }
    }

    /**
     * Bounds a caller-supplied command id and strips control characters, so it is safe to log and
     * to echo into results. Sanitized rather than rejected: the id exists only so the caller can
     * correlate a result with its request, and a caller odd enough to need trimming still gets a
     * recognisable prefix back, where a rejection would cost it the whole answer.
     */
    private static String sanitizeCommandId(String id) {
        StringBuilder clean = new StringBuilder(Math.min(id.length(), MAX_COMMAND_ID_LENGTH));
        for (int i = 0; i < id.length() && clean.length() < MAX_COMMAND_ID_LENGTH; i++) {
            char c = id.charAt(i);
            if (!Character.isISOControl(c)) {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    /**
     * Null when a publish handed over right now would reach a live session, a short reason
     * otherwise. Exists so telemetry.publish can answer honestly: {@link #publish} deliberately
     * drops payloads in silence, which is right for the periodic tick and wrong for a command
     * that must not claim success over a dead or absent connection.
     */
    String publishProblem() {
        if (config.mqttHost.isEmpty()) {
            return "MQTT is not configured";
        }
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected()) {
            return "MQTT is not connected";
        }
        return null;
    }

    private void publish(String topic, String payload, int qos, boolean retained) {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected()) {
            return;
        }
        try {
            activeClient.publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retained);
        } catch (MqttException exception) {
            Log.w(TAG, "MQTT publish failed", exception);
        }
    }

    private void publishDiscovery() {
        try {
            JSONObject discovery = new JSONObject();
            JSONObject device = new JSONObject();
            device.put("ids", new JSONArray().put(config.deviceId));
            device.put("name", "Muralis " + config.deviceId);
            // Read from the device, not hardcoded. These were "Lenovo" and "TB-X505F", the ROM
            // project's tablet, so every panel announced itself to Home Assistant as a Lenovo
            // TB-X505F regardless of what it actually was. Found on the Huawei MediaPad 2026-08-19,
            // where the discovery payload was confidently wrong about the hardware.
            device.put("mf", deviceManufacturer);
            device.put("mdl", deviceModel);
            device.put("sw", "Muralis " + appVersion);
            discovery.put("dev", device);

            JSONObject origin = new JSONObject();
            origin.put("name", "Muralis");
            origin.put("sw", appVersion);
            discovery.put("o", origin);
            discovery.put("state_topic", topicPrefix + "state");
            discovery.put("availability_topic", topicPrefix + "availability");
            discovery.put("qos", 0);

            JSONObject components = new JSONObject();
            // Whole percent. Android reports level and scale as integers and the division is only
            // a double so an unusual scale still divides cleanly, so a decimal here was never real
            // precision, just "87.0 %" on a dashboard. `round` alone still yields a float in Jinja,
            // hence the int.
            // Renamed from "Battery" 2026-08-20, at the user's request, to avoid clashing with the
            // conventional Home Assistant name for this device class. Home Assistant derives the
            // entity id from this name, so an existing installation's history follows the entity id
            // only if the rename is done in Settings > Entities once this payload lands, not by
            // deleting and re-adding.
            components.put("battery", sensor(
                    "Battery level", "battery", "%", "measurement",
                    "{{ value_json.battery.percent | round | int }}"));
            components.put("battery_temperature", sensor(
                    "Battery temperature", "temperature", "°C", "measurement",
                    "{{ value_json.battery.temperature_c | round(1) }}"));
            components.put("memory_available", sensor(
                    "Available memory", "data_size", "MiB", "measurement",
                    "{{ (value_json.memory.available_bytes / 1048576) | round(1) }}"));
            components.put("storage_available", sensor(
                    "Available storage", "data_size", "MiB", "measurement",
                    "{{ (value_json.storage.available_bytes / 1048576) | round(1) }}"));
            // Only where the device can actually answer. PowerManager.getCurrentThermalStatus is
            // API 29, so on the API 26 MediaPad this counter is JSON null for the life of the
            // panel, and announcing it produced a Home Assistant entity permanently stuck at
            // "unknown" (reported 2026-08-20). The null in the state document is contract-compliant
            // and stays; advertising an entity for it is not the same thing. Same reasoning as the
            // auto-brightness control, which the panel omits entirely on hardware with no light
            // sensor rather than offering something that can only fail.
            boolean thermalSupported =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q;
            if (thermalSupported) {
                components.put("thermal_status", sensor(
                        "Thermal status", null, null, null,
                        "{{ value_json.thermal_status }}"));
            }
            // There is deliberately NO live "Network state" entity any more, and it must not come
            // back: publishing "my network is down" requires the network, so a live entity could
            // only ever say "connected" and froze there whenever anything was actually wrong. Its
            // question, "did MQTT die alone, or did the network take it down?", is answered in
            // hindsight instead: OutageLedger tracks the device's own connectivity continuously,
            // connectComplete asks it for a verdict on every reconnect, and this sensor carries
            // that verdict. "network" means Wi-Fi/router; "broker" means the network was clean and
            // the MQTT session died alone (broker restart, Home Assistant update, credentials).
            // "none" until the first survived outage. When it happened and for how long: the MQTT
            // state entity's own history in Home Assistant, plus last_mqtt_outage_ago_ms and
            // last_mqtt_outage_duration_ms in this same telemetry document.
            JSONObject outageCause = sensor(
                    "Last MQTT outage cause", null, null, null,
                    "{{ value_json.runtime.last_mqtt_outage_cause or 'none' }}");
            outageCause.put("entity_category", "diagnostic");
            components.put("last_mqtt_outage_cause", outageCause);
            // Four values, not two, so this one stays a text sensor: charging, discharging,
            // charged, on hold. Straight from battery.charge_state, which is
            // SystemStats.chargeStateLabel, the same string the overlay and the web admin print,
            // so the three surfaces cannot drift apart. It is empty when the battery has no
            // reading at all, which would be a blank state rather than an honest one.
            components.put("battery_state", sensor(
                    "Battery state", null, null, null,
                    "{{ value_json.battery.charge_state or 'unknown' }}"));
            // The build actually running, as its own entity rather than only the device-info "sw"
            // field: device info is buried behind the device page, while a diagnostic sensor can
            // sit on a dashboard, be templated against, and answer "which panels are behind"
            // across an installation at a glance. The value names an exact commit, because
            // versions are derived from git at build time.
            JSONObject appVersionSensor = sensor(
                    "App version", null, null, null,
                    "{{ value_json.app_version }}");
            appVersionSensor.put("entity_category", "diagnostic");
            components.put("app_version", appVersionSensor);

            // Controls, not just readings. Discovery published sensors only, so a Home Assistant
            // user could see the panel but not touch it without hand-writing mqtt.publish calls in
            // every automation: reported 2026-08-20 as "the brightness slider and the auto mode are
            // not there". Nothing about MQTT prevented it; the panel simply never announced them.
            //
            // Every one of these drives the same KioskCommandDispatcher that the HTTP surface does,
            // which is the whole point of that design, so none of it is a second implementation.
            components.put("brightness", number(
                    "Brightness", 0, 100, "%",
                    "{\"command\":\"display.brightness\",\"args\":{\"percent\":{{ value }}}}",
                    "{{ value_json.display.brightness_percent }}"));

            // Whether the ambient light sensor decides the level. Omitted entirely on hardware
            // without one, matching the panel's own screens: the command reports "this device has
            // no ambient light sensor" rather than silently accepting, so an entity here would be a
            // switch that can only fail.
            if (hasLightSensor) {
                components.put("auto_brightness", toggle(
                        "Automatic brightness",
                        "{\"command\":\"display.auto_brightness\",\"args\":{\"enabled\":true}}",
                        "{\"command\":\"display.auto_brightness\",\"args\":{\"enabled\":false}}",
                        "{{ 'ON' if value_json.config.auto_brightness else 'OFF' }}"));
            }

            // No recycle controls. The panel used to announce an "Auto recycle" switch and a
            // "Recycle time" clock; both are gone, along with the commands behind them, because
            // recycling is a recovery mechanism rather than a preference and its schedule is now
            // derived per device. They are withdrawn below rather than merely omitted. The stats
            // overlay is deliberately absent for a different reason: it changes what is drawn on
            // the panel's own glass, which is a decision for whoever is standing at it or holding
            // the admin page, not something a broker subscriber needs.

            // A bare command name is a valid payload, which MqttController accepts deliberately so
            // that `mosquitto_pub -m kiosk.reload` works, so a button needs nothing more than this.
            components.put("portrait", toggle(
                    "Portrait mode",
                    "{\"command\":\"display.portrait\",\"args\":{\"enabled\":true}}",
                    "{\"command\":\"display.portrait\",\"args\":{\"enabled\":false}}",
                    "{{ 'ON' if value_json.config.portrait else 'OFF' }}"));
            components.put("reload", button("Reload dashboard", "kiosk.reload"));
            components.put("restart", button("Restart kiosk", "kiosk.restart"));
            components.put("wake", button("Wake display", "display.wake"));
            components.put("display_off", button("Display off", "display.visual_off"));
            // Answers "unsupported" with a provisioning hint when the panel is not device owner,
            // rather than pretending. No shutdown button exists for the same reason: nothing can
            // power off an Android device, which is why that command was deleted outright.
            components.put("reboot", button("Reboot tablet", "system.reboot"));
            discovery.put("cmps", components);

            publishMqttStateEntity(device, origin);

            String topic = "homeassistant/device/" + config.deviceId + "/config";

            // Simply leaving a component out does NOT remove an entity that was announced before,
            // which is how a panel updated in place ends up with a stranded "unavailable" entity
            // forever. Home Assistant documents a two-step removal: publish the component with an
            // empty config, keeping only the required platform key, then publish the whole
            // configuration again with it omitted. Both payloads are otherwise identical and
            // complete, so nothing else flickers.
            //
            // Done on every discovery run rather than once. It is idempotent, it costs one extra
            // publish on a topic that is written when the panel connects and when Home Assistant
            // restarts, and it self-heals a panel moved to a fresh Home Assistant.
            //
            // thermal_status is conditional: it is withdrawn only on hardware that cannot report
            // it. The two recycle controls are unconditional, because this build no longer has them
            // at all, anything upgraded from a build that did would otherwise keep a switch and a
            // clock that answer nothing.
            JSONObject stale = new JSONObject(components.toString());
            if (!thermalSupported) {
                stale.put("thermal_status", withdrawn("sensor"));
            }
            stale.put("auto_recycle", withdrawn("switch"));
            stale.put("recycle_time", withdrawn("time"));
            // Earlier spellings of the two entities above. "network" and "charging" were the
            // original binary_sensors; both are gone, the first renamed to network_state and the
            // second replaced by battery_state. Withdrawn under their own keys, which is safe
            // precisely because nothing in the payload that follows uses those keys again: a key
            // that appears withdrawn here and complete there would delete and recreate a live
            // entity on every discovery run, a visible flicker each time Home Assistant restarts.
            stale.put("network", withdrawn("binary_sensor"));
            stale.put("charging", withdrawn("binary_sensor"));
            // network_state carried three platforms over its life: the original text sensor, then
            // a binary_sensor, and since 2026-08-23 nothing at all (see the Last MQTT outage cause
            // sensor above for why a live network entity cannot work). A discovered component is
            // identified by platform *and* key, never by key alone, so each spelling needs its own
            // removal under its own platform, and one JSON object cannot hold two entries for the
            // same key: the sensor spelling rides in this first removal payload, the binary_sensor
            // spelling gets a second one below. Merely omitting a component never removes it; when
            // network_state changed from sensor to binary_sensor without a withdrawal, the text
            // sensor stayed subscribed and undeletable, reported from the dashboard 2026-08-23.
            stale.put("network_state", withdrawn("sensor"));
            JSONObject removal = new JSONObject(discovery.toString());
            removal.put("cmps", stale);
            publish(topic, removal.toString(), 1, true);

            // Second removal pass: the binary_sensor spelling of network_state, the one that was
            // live until 2026-08-23. QoS 1 publishes from one client keep their order, so Home
            // Assistant processes sensor-removal, then binary_sensor-removal, then the complete
            // configuration below, and nothing that is still announced ever flickers.
            JSONObject staleBinary = new JSONObject(components.toString());
            staleBinary.put("network_state", withdrawn("binary_sensor"));
            JSONObject removalBinary = new JSONObject(discovery.toString());
            removalBinary.put("cmps", staleBinary);
            publish(topic, removalBinary.toString(), 1, true);

            publish(topic, discovery.toString(), 1, true);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Whether the broker currently holds a session with this panel: the single live connectivity
     * entity, since the "Network state" one was removed as information-free (it could only ever
     * publish "connected"; see the Last MQTT outage cause sensor). Two values and only ever two,
     * so the connectivity device class fits; the trade is that the rendered words come from Home
     * Assistant and are capitalised.
     *
     * <p>Deliberately a standalone, classic discovery message rather than one more component in
     * the device-based {@code cmps} bundle. A component in that bundle inherits the device's
     * {@code availability_topic}, so it would be marked unavailable the instant the Last Will
     * fires, which is precisely the outcome this entity exists to report. An MQTT entity with no
     * {@code availability_topic} of its own is always considered available, so this one keeps
     * showing its state while every other entity greys out around it.
     *
     * <p>Its {@code state_topic} is the topic the Last Will writes to, so no second signal is
     * invented: {@code online}/{@code offline} map straight onto on/off.
     *
     * <p>The half the Last Will cannot cover, and why {@code expire_after} is here. A will is
     * published by the <em>broker</em>, so it fires only for a session the broker was holding and
     * then lost ungracefully: the panel loses power, crashes, or drops off the network, detected
     * one and a half keep-alives later, about 45s at the 30s keep-alive set in start(). It cannot
     * fire when there is no session to lose. A panel that never reaches the broker at all (wrong
     * host, broker down, refused credentials) leaves the retained "online" from its last good
     * session on this topic and every dashboard goes on claiming it is connected. Neither can it
     * fire when the broker itself is what died, since the process that owed us the will is the one
     * that went away, and a broker with persistence restores that same retained "online" on the way
     * back up. So the panel beats on this topic (see {@link #heartbeat()}) and this entity expires
     * if the beat stops, at three missed beats: long enough that one dropped publish is not an
     * alarm, short enough to be useful.
     *
     * <p>An expired entity reads unavailable rather than "disconnected", and that is the honest
     * rendering: nobody has spoken for this panel, and nothing at this end can tell a dead panel
     * from an unreachable broker, because both are silence.
     */
    private void publishMqttStateEntity(JSONObject device, JSONObject origin) throws JSONException {
        JSONObject state = new JSONObject();
        state.put("name", "MQTT state");
        state.put("unique_id", config.deviceId + "_mqtt_state");
        state.put("state_topic", topicPrefix + "availability");
        state.put("device_class", "connectivity");
        state.put("payload_on", "online");
        state.put("payload_off", "offline");
        state.put("entity_category", "diagnostic");
        state.put("expire_after", HEARTBEAT_EXPIRY_SECONDS);
        state.put("dev", device);
        state.put("o", origin);
        state.put("qos", 0);
        publish("homeassistant/binary_sensor/" + config.deviceId + "_mqtt_state/config",
                state.toString(), 1, true);

        // The same entity under its two earlier names, deleted rather than left to rot. Classic
        // discovery removes an entity by publishing an empty retained payload to its config topic,
        // which also clears the retained config so a fresh Home Assistant never sees it. Both were
        // sensors on the sensor/ topic tree, so neither is reachable from the binary_sensor topic
        // above and neither would ever go away on its own.
        publish("homeassistant/sensor/" + config.deviceId + "_connected/config", "", 1, true);
    }

    /**
     * Reasserts "online" on the availability topic, so the MQTT state entity above has something
     * to expire against.
     *
     * <p>Called on a fixed cadence rather than from {@link #publishState}: the telemetry interval
     * is an operator preset that reaches five minutes, and tying liveness detection to it would
     * mean a panel could be unreachable for twelve minutes before anything said so. It is a
     * six-byte retained publish; at {@link #HEARTBEAT_INTERVAL_MS} that is well under the traffic
     * the state topic already generates.
     *
     * <p>Silently does nothing when there is no live session, which is not a failure: it is the
     * condition the entity is there to detect, and the absence of this publish is the signal.
     */
    void heartbeat() {
        MqttAsyncClient active = client;
        if (active == null || !active.isConnected()) {
            return;
        }
        publish(topicPrefix + "availability", "online", 1, true);
    }

    private JSONObject sensor(
            String name,
            String deviceClass,
            String unit,
            String stateClass,
            String valueTemplate) throws JSONException {
        JSONObject sensor = new JSONObject();
        sensor.put("p", "sensor");
        sensor.put("name", name);
        sensor.put("unique_id", config.deviceId + "_" + name
                .toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
        sensor.put("value_template", valueTemplate);
        if (deviceClass != null) {
            sensor.put("device_class", deviceClass);
        }
        if (unit != null) {
            sensor.put("unit_of_measurement", unit);
        }
        if (stateClass != null) {
            sensor.put("state_class", stateClass);
        }
        return sensor;
    }

    /** A slider. {@code command_template} renders the dispatcher envelope around the new value. */
    private JSONObject number(
            String name,
            int min,
            int max,
            String unit,
            String commandTemplate,
            String valueTemplate) throws JSONException {
        JSONObject number = new JSONObject();
        number.put("p", "number");
        number.put("name", name);
        number.put("unique_id", uniqueId(name));
        number.put("command_topic", topicPrefix + "command");
        number.put("command_template", commandTemplate);
        number.put("value_template", valueTemplate);
        number.put("min", min);
        number.put("max", max);
        number.put("step", 1);
        number.put("unit_of_measurement", unit);
        number.put("mode", "slider");
        return number;
    }

    /**
     * A switch. {@code state_on}/{@code state_off} are stated explicitly and must be, because the
     * payloads sent are command envelopes and would never match the state the panel reports back.
     */
    private JSONObject toggle(
            String name,
            String payloadOn,
            String payloadOff,
            String valueTemplate) throws JSONException {
        JSONObject toggle = new JSONObject();
        toggle.put("p", "switch");
        toggle.put("name", name);
        toggle.put("unique_id", uniqueId(name));
        toggle.put("command_topic", topicPrefix + "command");
        toggle.put("payload_on", payloadOn);
        toggle.put("payload_off", payloadOff);
        toggle.put("value_template", valueTemplate);
        toggle.put("state_on", "ON");
        toggle.put("state_off", "OFF");
        return toggle;
    }

    /**
     * An empty component config, which is how Home Assistant is told to drop an entity a previous
     * discovery payload announced. Only the platform key is required, and only it is sent.
     */
    private static JSONObject withdrawn(String platform) throws JSONException {
        JSONObject removed = new JSONObject();
        removed.put("p", platform);
        return removed;
    }

    /** A press. Stateless, so it needs no template and reads nothing. */
    private JSONObject button(String name, String command) throws JSONException {
        JSONObject button = new JSONObject();
        button.put("p", "button");
        button.put("name", name);
        button.put("unique_id", uniqueId(name));
        button.put("command_topic", topicPrefix + "command");
        button.put("payload_press", command);
        return button;
    }

    private String uniqueId(String name) {
        return config.deviceId + "_" + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }

}
