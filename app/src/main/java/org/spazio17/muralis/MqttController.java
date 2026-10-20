/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
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
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Missing or non-boolean telemetry resets an MQTT switch to unknown, never confidently off. */
    private static String screensaverBooleanTemplate(String field) {
        return "{% set s = value_json.get('ambient', {}) %}{% if s is mapping %}"
                + "{{ 'ON' if s.get('" + field + "') is sameas true else 'OFF' if s.get('"
                + field + "') is sameas false else 'None' }}{% else %}None{% endif %}";
    }

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

    /** Real hardware identity for Home Assistant discovery. See {@link #runDiscovery}. */
    private final String deviceManufacturer;
    private final String deviceModel;
    private final String appVersion;
    /**
     * Read once at construction rather than per discovery run: a light sensor cannot appear or
     * disappear on a running device, and the controller is rebuilt whenever configuration reloads.
     */
    private final boolean hasLightSensor;
    /** Same rule as the light sensor: a panel with no battery is never offered battery entities. */
    private final boolean hasBattery;
    /**
     * Volatile because Paho's callback thread reads it ({@code connectComplete}, {@code publish})
     * while the main thread replaces it in {@link #start}/{@link #stop}. Without it a reader can
     * see a stale non-null reference to an already-closed client.
     */
    private volatile MqttAsyncClient client;
    private final Context appContext;
    /**
     * The retry after a connect that failed outright, which Paho's automatic reconnect does not
     * cover; see {@link MqttConnectRetry}. Scheduled on the main thread, cancelled by
     * {@link #stop}, and guarded besides by the client it was scheduled for still being the live
     * one, so a configuration reload can never leave a retry firing for a client already closed.
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * Discovery and the state publish run here, one at a time and in order: Paho's thread (a
     * connect, Home Assistant's birth message), the telemetry tick and a command may all ask at
     * once, and none of them should wait while the payload is built from storage. Everything
     * below it is touched only on this thread. Shut down by {@link #stop}.
     */
    private final java.util.concurrent.ScheduledExecutorService discoveryWorker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    work -> new Thread(work, "MuralisDiscovery"));
    /** False from a connect until the broker's copy of the payload was read, or the wait ran out. */
    private boolean discoveryReady;
    /** The last run did not go out whole, so the next state publish runs it again. */
    private boolean discoveryStale;
    private java.util.concurrent.ScheduledFuture<?> retainedWait;
    /** Every component this panel's payload announced last, key to platform. */
    private final java.util.Map<String, String> announced = new java.util.LinkedHashMap<>();
    /**
     * How long a connect waits, from the broker's acknowledgement of the subscription, for the
     * panel's own retained payload; zigbee2mqtt waits five seconds for its retained configs. The
     * broker sends a retained message straight after the acknowledgement, so this runs out only
     * where there is none: a new panel, or a broker that keeps nothing.
     */
    private static final long RETAINED_WAIT_MS = 5_000L;
    private MqttConnectOptions connectOptions;
    private final AtomicInteger connectFailures = new AtomicInteger();

    MqttController(Context context, CommandListener commandListener) {
        config = KioskConfig.load(context);
        this.commandListener = commandListener;
        appContext = context.getApplicationContext();
        topicPrefix = "kiosk/" + config.deviceId + "/";
        deviceManufacturer = android.os.Build.MANUFACTURER;
        deviceModel = android.os.Build.MODEL;
        appVersion = TelemetryCollector.appVersionName(context);
        hasLightSensor = KioskService.hasLightSensor(context);
        hasBattery = KioskService.hasBattery(context);
    }

    /** One client certificate change at a time, as the web admin's own certificate. */
    private static final Object CLIENT_STORE = new Object();

    /** The uploaded client certificate, or null when none is kept or it cannot be read. */
    static CertificateUpload clientCertificate(Context context) {
        SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
        return CertificateUpload.restore(secrets.getOrNull(KioskConfig.MQTT_CLIENT_CHAIN),
                secrets.getOrNull(KioskConfig.MQTT_CLIENT_KEY));
    }

    /**
     * Keeps a client certificate and its key for the broker, once {@link CertificateUpload} has
     * read them; null when kept, else why not, a fragment to follow "Not uploaded: ".
     */
    static String keepClientCertificate(Context context, String certificateText, String keyText) {
        CertificateUpload.Result read = CertificateUpload.parseClient(certificateText, keyText);
        if (read.problem != null) {
            return read.problem;
        }
        String chain = read.upload.chainText();
        String key = read.upload.keyText();
        java.security.cert.X509Certificate first = read.upload.chain[0];
        synchronized (CLIENT_STORE) {
            SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
            secrets.put(KioskConfig.MQTT_CLIENT_CHAIN, chain);
            secrets.put(KioskConfig.MQTT_CLIENT_KEY, key);
            // Read back: SecretStore leaves the old value when it cannot encrypt.
            if (!chain.equals(secrets.getOrNull(KioskConfig.MQTT_CLIENT_CHAIN))
                    || !key.equals(secrets.getOrNull(KioskConfig.MQTT_CLIENT_KEY))) {
                forgetClientCertificate(context);
                return "this panel could not keep it; upload it again";
            }
            KioskConfig.edit(context).mqttClientShown(CertificateUpload.names(first),
                    first.getNotAfter().getTime()).apply();
        }
        return null;
    }

    /** Forgets the client certificate; the broker is then reached without one. */
    static void forgetClientCertificate(Context context) {
        synchronized (CLIENT_STORE) {
            SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
            secrets.clear(KioskConfig.MQTT_CLIENT_CHAIN);
            secrets.clear(KioskConfig.MQTT_CLIENT_KEY);
            KioskConfig.edit(context).mqttClientShown("", 0).apply();
        }
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

        // TLS when the person switched it on (optional: a broker on the home network is often
        // plain). The broker is trusted when Android trusts its certificate for its name, or when
        // its certificate is the one a person trusted here (MqttTls); a client certificate is
        // presented when one was uploaded, as zigbee2mqtt's ca, cert and key.
        MqttTls.Connection tls = null;
        if (config.mqttTls) {
            try {
                tls = MqttTls.connection(config.mqttHost, config.mqttTlsPin,
                        clientCertificate(appContext));
            } catch (java.security.GeneralSecurityException failed) {
                Log.e(TAG, "MQTT over TLS could not be set up", failed);
                return;
            }
        }
        String uri = (tls == null ? "tcp://" : "ssl://") + config.mqttHost + ":" + config.mqttPort;
        try {
            client = new MqttAsyncClient(uri, config.deviceId, new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(15);
            options.setKeepAliveInterval(30);
            if (tls != null) {
                // The name is checked by MqttTls's verifier, which also accepts the trusted
                // certificate; Paho calls it only with its own check off.
                options.setSocketFactory(tls.socketFactory);
                options.setHttpsHostnameVerificationEnabled(false);
                options.setSSLHostnameVerifier(tls.hostnameVerifier);
            }
            options.setWill(topicPrefix + "availability",
                    "offline".getBytes(StandardCharsets.UTF_8), 1, true);
            if (!config.mqttUsername.isEmpty()) {
                options.setUserName(config.mqttUsername);
            }
            if (!config.mqttPassword.isEmpty()) {
                options.setPassword(config.mqttPassword.toCharArray());
            }
            connectOptions = options;
            connect(client);
        } catch (MqttException exception) {
            Log.e(TAG, "Unable to start MQTT client", exception);
        }
    }

    /**
     * One connection attempt against {@code target}, and the next one scheduled if it fails.
     * {@code target} rather than the field, because by the time the failure arrives on Paho's
     * thread, {@link #stop} may have replaced or nulled the field, and a retry for a client that is
     * no longer ours must simply not happen.
     */
    private void connect(MqttAsyncClient target) {
        if (target != client) {
            return;
        }
        try {
            target.connect(connectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // The session itself is set up in connectComplete, which Paho calls for this
                    // as for every reconnect. Nothing to do here.
                }

                @Override
                public void onFailure(IMqttToken token, Throwable cause) {
                    if (target != client) {
                        return;
                    }
                    long delay = MqttConnectRetry.delayMs(connectFailures.incrementAndGet());
                    Log.w(TAG, "MQTT connect failed (" + (cause == null ? "no cause" : cause)
                            + "); retrying in " + delay / 1000 + " s");
                    mainHandler.postDelayed(() -> connect(target), delay);
                }
            });
        } catch (MqttException exception) {
            // Paho refuses a connect() on a client that is already connecting or connected;
            // neither needs a retry from here, the pending one reports through the listener.
            Log.w(TAG, "MQTT connect not started: " + exception.getMessage());
        }
    }

    void stop() {
        discoveryWorker.shutdownNow();
        MqttAsyncClient stopping = client;
        if (stopping == null) {
            return;
        }
        // Null the field first: connectComplete runs on Paho's own thread and would otherwise see
        // a client we are in the middle of tearing down. The pending retry, if any, goes with it.
        client = null;
        mainHandler.removeCallbacksAndMessages(null);
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

    /**
     * Runs {@code task} on the discovery worker and logs what it throws: a task the executor runs
     * keeps its exception in a future nobody reads, so without this a failure would be silent.
     * Nothing happens once {@link #stop} has shut the worker down.
     */
    private void onWorker(Runnable task) {
        try {
            discoveryWorker.execute(() -> guarded(task));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // stop() has run; nothing is connected to publish to.
        }
    }

    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            Log.e(TAG, "MQTT discovery work failed", failure);
        }
    }

    void publishState(JSONObject state) {
        if (client == null) {
            // Not configured, or stopped: nothing to publish to, and no reason to start the worker.
            return;
        }
        onWorker(() -> {
            // The playlist select's options are part of discovery, and a playlist made, renamed
            // or deleted since the last discovery is one Home Assistant cannot choose or shows as
            // unknown (review of 2026-09-19). Discovery goes again before the state when they
            // differ. Same rule for the sensors, the camera and the automations: a sensor switched
            // on has its entity at once, one switched off loses it, and a rule added, renamed or
            // deleted is announced or withdrawn before the state that mentions it. Not before the
            // broker's copy was read on a connect, and again after a run that did not go out whole.
            // The state goes out whatever discovery did.
            guarded(() -> {
                if (!discoveryReady) {
                    return;
                }
                String names = PictureLibrary.get(appContext).playlists().namesKey();
                if (discoveryStale
                        || (names != null && !names.equals(announcedPlaylists))
                        || !sensorsKey(KioskRuntimeState.sensors(),
                                KioskRuntimeState.automations()).equals(announcedSensors)
                        || !camerasKey().equals(announcedCameras)) {
                    runDiscovery();
                }
            });
            publish(topicPrefix + "state", state.toString(), 0, true);
        });
    }

    /** The cameras' names discovery last announced, one button each. */
    private volatile String announcedCameras = null;

    private String camerasKey() {
        StringBuilder key = new StringBuilder();
        for (Cameras.Camera camera : KioskConfig.camerasOf(appContext)) {
            key.append(camera.name).append(',');
        }
        key.append('|');
        for (Cameras.Group group : KioskConfig.cameraGroupsOf(appContext)) {
            key.append(group.name).append(',');
        }
        return key.toString();
    }

    /** The playlist names discovery last announced, joined as PicturePlaylists.namesKey is. */
    private volatile String announcedPlaylists = null;

    /** The sensors, camera and automations discovery last announced; see {@link #sensorsKey}. */
    private volatile String announcedSensors = null;

    /**
     * What discovery announces for these sensors and rules, as one string. Given the very block
     * and rules a payload was built from, so the key stored after a publish describes that
     * payload and not a newer reading that arrived while it was being built.
     */
    private static String sensorsKey(org.json.JSONObject sensorBlock, org.json.JSONArray rules) {
        StringBuilder key = new StringBuilder();
        for (Sensors.Def def : Sensors.ALL) {
            org.json.JSONObject one = sensorBlock.optJSONObject(def.id);
            if (one != null && one.optBoolean("active")) {
                key.append(def.id).append(',');
            }
        }
        // The snapshot button comes and goes with the camera's pictures-to-MQTT switch.
        org.json.JSONObject cameraState = sensorBlock.optJSONObject("camera");
        org.json.JSONObject cameraAttributes = cameraState == null ? null
                : cameraState.optJSONObject("attributes");
        if (cameraAttributes != null && cameraAttributes.optBoolean("pictures")) {
            key.append("pictures,");
        }
        for (int index = 0; index < rules.length(); index++) {
            JSONObject rule = rules.optJSONObject(index);
            if (rule != null) {
                key.append(rule.optString("id", "")).append('=')
                        .append(rule.optString("name", "")).append(',');
            }
        }
        return key.toString();
    }

    /** A tag held to the panel, as Home Assistant's tag scanned event expects it. */
    void publishTagRead(String tagId) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("tag_id", tagId);
            publish(topicPrefix + "tag", payload.toString(), 1, false);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A picture from the camera, raw JPEG bytes, what an MQTT camera entity shows. */
    void publishPicture(byte[] jpeg) {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected() || jpeg == null) {
            return;
        }
        try {
            activeClient.publish(topicPrefix + "camera", jpeg, 0, true);
        } catch (MqttException exception) {
            Log.w(TAG, "MQTT picture publish failed", exception);
        }
    }

    /**
     * Takes the retained picture off the broker: the camera went off, or its pictures are no
     * longer wanted there, and a stale frame must not be what a fresh Home Assistant shows.
     */
    void clearPicture() {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected()) {
            return;
        }
        try {
            activeClient.publish(topicPrefix + "camera", new byte[0], 0, true);
        } catch (MqttException exception) {
            Log.w(TAG, "MQTT picture clear failed", exception);
        }
    }

    /**
     * A picture of the screen, JPEG bytes sized for a broker (KioskService.forBroker), what the
     * Home Assistant image entity shows.
     */
    void publishScreenshot(byte[] jpeg) {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected() || jpeg == null) {
            return;
        }
        try {
            activeClient.publish(topicPrefix + "screenshot", jpeg, 0, true);
        } catch (MqttException exception) {
            Log.w(TAG, "MQTT screenshot publish failed", exception);
        }
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
        int failedAttempts = connectFailures.getAndSet(0);
        Log.i(TAG, reconnect ? "MQTT reconnected"
                : failedAttempts > 0 ? "MQTT connected after " + failedAttempts + " failed attempt(s)"
                : "MQTT connected");
        if (reconnect || failedAttempts > 0) {
            // Fresh telemetry straight away: the retained snapshot on the broker is as stale as
            // the outage was long, and the next scheduled tick can be a minute away. Also after
            // a first connect that needed retries, for the same reason: by then the panel has
            // been up for a while and the broker has nothing from it yet. (Until 2026-08-25 this
            // publish also carried an outage-cause verdict from OutageLedger; that whole
            // attribution feature was removed as not worth knowing, the MQTT state entity's own
            // history already says when and for how long.)
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
            readRetainedDiscovery(active);
        } catch (MqttException exception) {
            Log.e(TAG, "Unable to initialize MQTT session", exception);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.w(TAG, "MQTT connection lost; automatic reconnect is enabled", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (topic.equals("homeassistant/status")) {
            String status = new String(message.getPayload(), StandardCharsets.UTF_8);
            if (status.equals("online")) {
                requestDiscovery();
            }
            return;
        }
        if (topic.equals(discoveryTopic())) {
            // The broker's copy of this panel's payload, asked for on connect. Only the retained
            // replay counts: our own publishes echo back live (not retained) until the
            // unsubscribe lands.
            if (message.isRetained()) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                stopReadingRetained();
                onWorker(() -> retainedArrived(payload));
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

    /**
     * A fleet alert on the plain topic the person set, on this panel's broker: not
     * retained, so a subscriber that comes later is not told of a state long gone. False when
     * not connected.
     */
    boolean publishAlert(String topic, String payload) {
        return publish(topic, payload, 1, false);
    }

    /** @return whether the message was handed to the client; false when not connected or refused */
    private boolean publish(String topic, String payload, int qos, boolean retained) {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected()) {
            return false;
        }
        try {
            activeClient.publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retained);
            return true;
        } catch (MqttException exception) {
            Log.w(TAG, "MQTT publish failed", exception);
            return false;
        }
    }

    private String discoveryTopic() {
        return "homeassistant/device/" + config.deviceId + "/config";
    }

    /**
     * On every connect: ask the broker for this panel's own retained payload before announcing
     * anything, because that payload is what Home Assistant was told, kept where it reads it.
     * zigbee2mqtt does the same at its start (it reads its own retained configs for five seconds
     * and clears what it would not publish now), and it covers what a copy on the panel cannot:
     * a panel updated from a build that kept no record, and a publish that never arrived.
     */
    private void readRetainedDiscovery(MqttAsyncClient active) throws MqttException {
        onWorker(() -> {
            discoveryReady = false;
            if (retainedWait != null) {
                retainedWait.cancel(false);
                retainedWait = null;
            }
        });
        active.subscribe(discoveryTopic(), 1, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken token) {
                onWorker(() -> {
                    if (discoveryReady) {
                        return;
                    }
                    retainedWait = discoveryWorker.schedule(() -> guarded(() -> {
                        if (!discoveryReady) {
                            // A new panel, or a broker that keeps nothing: nothing was announced.
                            stopReadingRetained();
                            finishReadingRetained();
                        }
                    }), RETAINED_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                });
            }

            @Override
            public void onFailure(IMqttToken token, Throwable failure) {
                // A broker whose access list refuses this panel reading its own topic: announce
                // with what this process remembers, as a new panel would.
                Log.w(TAG, "Could not read back this panel's discovery from the broker", failure);
                onWorker(() -> {
                    if (!discoveryReady) {
                        finishReadingRetained();
                    }
                });
            }
        });
    }

    private void stopReadingRetained() {
        MqttAsyncClient active = client;
        if (active == null) {
            return;
        }
        try {
            active.unsubscribe(discoveryTopic());
        } catch (MqttException exception) {
            Log.w(TAG, "Could not stop reading the panel's own discovery topic", exception);
        }
    }

    /**
     * Worker thread: what the broker held, folded into what this process remembers. The broker's
     * platform wins over the one in memory: it is what Home Assistant reads, and a publish handed
     * to the client can still have been lost with the connection.
     */
    private void retainedArrived(String payload) {
        if (discoveryReady) {
            Log.w(TAG, "This panel's discovery came back from the broker after the wait; "
                    + "anything only it recorded will not be removed");
            return;
        }
        try {
            JSONObject components = payload.isEmpty() ? null
                    : new JSONObject(payload).optJSONObject("cmps");
            if (components != null) {
                // An entry holding only its platform is an old build's removal, done long since.
                announced.putAll(announcedIn(components));
            }
        } catch (JSONException unreadable) {
            Log.w(TAG, "The broker's copy of this panel's discovery could not be read", unreadable);
        }
        finishReadingRetained();
    }

    /** Worker thread. */
    private void finishReadingRetained() {
        if (retainedWait != null) {
            retainedWait.cancel(false);
            retainedWait = null;
        }
        discoveryReady = true;
        runDiscovery();
    }

    /**
     * From any thread: announce again, on the worker. Home Assistant's birth message asks for it,
     * as its documentation recommends; before the broker's copy was read on a connect it is
     * already on its way.
     */
    private void requestDiscovery() {
        onWorker(() -> {
            if (discoveryReady) {
                runDiscovery();
            }
        });
    }

    /** Worker thread only. */
    private void runDiscovery() {
        try {
            org.json.JSONObject sensorBlock = KioskRuntimeState.sensors();
            org.json.JSONArray rules = KioskRuntimeState.automations();
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
            if (hasBattery) {
                components.put("battery", sensor(
                        "Battery level", "battery", "%", "measurement",
                        "{{ value_json.battery.percent | round | int }}"));
                components.put("battery_temperature", sensor(
                        "Battery temperature", "temperature", "°C", "measurement",
                        "{{ value_json.battery.temperature_c | round(1) }}"));
            }
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
            // There is deliberately NO live "Network state" entity, and it must not come back:
            // publishing "my network is down" requires the network, so a live entity could only
            // ever say "connected" and froze there whenever anything was actually wrong. A "Last
            // MQTT outage cause" diagnostic sensor answered its question in hindsight instead
            // ("network" or "broker", judged by OutageLedger on every reconnect) and was removed
            // too, with the whole attribution machinery, on 2026-08-25: knowing THAT the session
            // dropped and for how long is the MQTT state entity's own history, and knowing WHY
            // turned out not to be worth an entity. Its withdrawal was published, confirmed
            // processed on the only installation that ever saw it, and then retired with the
            // rest of the stale-keys block; the history is in git.
            // Four values, not two, so this one stays a text sensor: charging, discharging,
            // charged, on hold. Straight from battery.charge_state, which is
            // SystemStats.chargeStateLabel, the same string the overlay and the web admin print,
            // so the three surfaces cannot drift apart. It is empty when the battery has no
            // reading at all, which would be a blank state rather than an honest one.
            if (hasBattery) {
                components.put("battery_state", sensor(
                        "Battery state", null, null, null,
                        "{{ value_json.battery.charge_state or 'unknown' }}"));
            }
            // On every panel, battery or not: how it is fed. An enum sensor, so Home Assistant
            // knows the three words and can translate and colour them; see SystemStats.powerSource
            // for why a charger, PoE and a DC adapter are all "mains".
            JSONObject powerSource = sensor(
                    "Power source", "enum", null, null,
                    "{{ value_json.power.source }}");
            powerSource.put("options",
                    new JSONArray().put("battery").put("wireless").put("mains"));
            components.put("power_source", powerSource);
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
            // App lock: the app shown instead of the page, "Off" when there is none.
            components.put("app_lock", sensor("App lock", null, null, null,
                    "{{ value_json.app_lock.app if value_json.app_lock is defined"
                            + " and value_json.app_lock.app else 'Off' }}"));

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
            // derived per device. Their withdrawals ran until 2026-08-25 and were then retired as
            // confirmed processed; the history is in git. The stats
            // overlay is deliberately absent for a different reason: it changes what is drawn on
            // the panel's own glass, which is a decision for whoever is standing at it or holding
            // the admin page, not something a broker subscriber needs.

            // A bare command name is a valid payload, which MqttController accepts deliberately so
            // that `mosquitto_pub -m kiosk.reload` works, so a button needs nothing more than this.
            // "auto" is offered only where an accelerometer exists to drive it, the same gate
            // the auto-brightness switch sits behind above. The spellings mirror KioskConfig's
            // ORIENTATION_* constants.
            org.json.JSONArray orientationOptions = new org.json.JSONArray();
            if (KioskService.hasAccelerometer(appContext)) {
                orientationOptions.put("auto");
            }
            orientationOptions.put("landscape");
            orientationOptions.put("portrait");
            components.put("orientation", select(
                    "Orientation",
                    "display.orientation",
                    orientationOptions,
                    "{{ value_json.config.orientation }}"));
            // How the Display off button darkens the panel; see DisplayOffPolicy. "auto" and
            // "sleep" only where the device owner's lockNow exists to do it: an ordinary install
            // has the film alone, as its own screens show (2026-09-24), and the state reports
            // "film" there too (KioskService), so the select never reads a value it cannot offer.
            org.json.JSONArray displayOffOptions = new org.json.JSONArray();
            if (KioskService.isDeviceOwner(appContext)) {
                displayOffOptions.put(DisplayOffPolicy.AUTO);
                displayOffOptions.put(DisplayOffPolicy.SLEEP);
            }
            displayOffOptions.put(DisplayOffPolicy.FILM);
            components.put("display_off_method", select(
                    "Display off method",
                    "display.off_method",
                    displayOffOptions,
                    "{{ value_json.config.display_off_method }}"));
            // The status light as a Home Assistant light: on or off, a colour and a brightness,
            // through the template schema so its commands are the panel's own light.on and
            // light.off. Home Assistant's brightness is 0 to 255, the panel's 1 to 100.
            components.put("status_light", statusLight());
            // A pop-up on the panel from Home Assistant's notify.send_message.
            JSONObject popup = new JSONObject();
            popup.put("p", "notify");
            popup.put("name", "Pop-up");
            popup.put("unique_id", uniqueId("Pop-up"));
            popup.put("command_topic", topicPrefix + "command");
            popup.put("command_template",
                    "{\"command\":\"panel.notify\",\"args\":{\"value\":{{ value | tojson }}}}");
            components.put("pop_up", popup);
            org.json.JSONArray screensaverModes = new org.json.JSONArray();
            screensaverModes.put(ScreensaverPolicy.OFF);
            screensaverModes.put(ScreensaverPolicy.DIM);
            screensaverModes.put(ScreensaverPolicy.FILM);
            screensaverModes.put(ScreensaverPolicy.URL);
            screensaverModes.put(ScreensaverPolicy.PICTURES);
            // Ambient: the mode as a select, and one switch that is both its state and its
            // control, start on ON and stop on OFF. No separate start/stop buttons: they would be
            // the switch twice over, and every entity is a row in somebody's device page. The
            // times are numbers of their own.
            components.put("ambient_mode", select(
                    "Ambient mode",
                    "ambient.mode",
                    screensaverModes,
                    "{{ value_json.ambient.mode }}"));
            // The playlists by name, so a card can switch a panel's pictures without knowing an
            // id. "None" is a real option because a playlist can be deleted while it is in use,
            // and an entity that cannot express the state it is in reads as broken.
            org.json.JSONArray playlistNames = new org.json.JSONArray();
            playlistNames.put(PLAYLIST_NONE);
            PlaylistDocument playlists = PictureLibrary.get(appContext).playlists().load();
            for (PlaylistDocument.Playlist playlist : playlists.all()) {
                playlistNames.put(playlist.name);
            }
            announcedPlaylists = PicturePlaylists.namesOf(playlists);
            components.put("ambient_playlist", select(
                    "Ambient playlist",
                    "ambient.playlist",
                    playlistNames,
                    "{{ value_json.ambient.playlist if value_json.ambient is mapping "
                            + "and value_json.ambient.playlist else '" + PLAYLIST_NONE + "' }}"));
            components.put("ambient", toggle(
                    "Ambient",
                    "{\"command\":\"ambient.start\"}",
                    "{\"command\":\"ambient.stop\"}",
                    screensaverBooleanTemplate("active")));
            // Every Ambient setting, not a chosen few: "everything must be controllable
            // remotely" (2026-09-09). They are all commands as well, so a caller that
            // would rather publish a command than use an entity can.
            components.put("ambient_idle", number(
                    "Ambient idle time",
                    "ambient.idle_seconds",
                    0, ScreensaverPolicy.MAX_SECONDS, "s",
                    "{{ value_json.ambient.idle_s }}"));
            components.put("ambient_display_off", number(
                    "Ambient to display off",
                    "ambient.off_seconds",
                    0, ScreensaverPolicy.MAX_SECONDS, "s",
                    "{{ value_json.ambient.off_s }}"));
            components.put("ambient_picture_time", number(
                    "Ambient picture time",
                    "ambient.picture_seconds",
                    1, ScreensaverPolicy.MAX_SECONDS, "s",
                    "{{ value_json.ambient.picture_s }}"));
            components.put("ambient_dim", number(
                    "Ambient dim brightness",
                    "ambient.dim_percent",
                    1, 100, "%",
                    "{{ value_json.ambient.dim_percent }}"));
            org.json.JSONArray transitions = new org.json.JSONArray();
            transitions.put(ScreensaverPolicy.TRANSITION_NONE);
            transitions.put(ScreensaverPolicy.TRANSITION_FADE);
            transitions.put(ScreensaverPolicy.TRANSITION_SLIDE);
            components.put("ambient_transition", select(
                    "Ambient transition",
                    "ambient.transition",
                    transitions,
                    "{{ value_json.ambient.transition }}"));
            org.json.JSONArray fits = new org.json.JSONArray();
            fits.put(ScreensaverPolicy.FIT_WHOLE);
            fits.put(ScreensaverPolicy.FIT_FILL);
            fits.put(ScreensaverPolicy.FIT_STRETCH);
            fits.put(ScreensaverPolicy.FIT_ACTUAL);
            components.put("ambient_picture_fit", select(
                    "Ambient picture fit",
                    "ambient.picture_fit",
                    fits,
                    "{{ value_json.ambient.picture_fit }}"));
            org.json.JSONArray corners = new org.json.JSONArray();
            corners.put(ScreensaverPolicy.CORNER_BOTTOM_LEFT);
            corners.put(ScreensaverPolicy.CORNER_BOTTOM_RIGHT);
            corners.put(ScreensaverPolicy.CORNER_TOP_LEFT);
            corners.put(ScreensaverPolicy.CORNER_TOP_RIGHT);
            components.put("ambient_credit_corner", select(
                    "Ambient credit corner",
                    "ambient.credit_corner",
                    corners,
                    "{{ value_json.ambient.credit_corner }}"));
            org.json.JSONArray wakeChoices = new org.json.JSONArray();
            wakeChoices.put(ScreensaverPolicy.WAKE_SCREENSAVER);
            wakeChoices.put(ScreensaverPolicy.WAKE_DASHBOARD);
            components.put("ambient_on_wake", select(
                    "Ambient after a wake",
                    "ambient.on_wake",
                    wakeChoices,
                    "{{ value_json.ambient.on_wake }}"));
            components.put("ambient_shuffle", toggle(
                    "Ambient shuffle",
                    "{\"command\":\"ambient.shuffle\",\"args\":{\"enabled\":true}}",
                    "{\"command\":\"ambient.shuffle\",\"args\":{\"enabled\":false}}",
                    screensaverBooleanTemplate("shuffle")));
            components.put("ambient_one_per_cycle", toggle(
                    "Ambient one picture per cycle",
                    "{\"command\":\"ambient.one_per_cycle\",\"args\":{\"enabled\":true}}",
                    "{\"command\":\"ambient.one_per_cycle\",\"args\":{\"enabled\":false}}",
                    screensaverBooleanTemplate("one_per_cycle")));
            components.put("ambient_credit", toggle(
                    "Ambient credit line",
                    "{\"command\":\"ambient.credit\",\"args\":{\"enabled\":true}}",
                    "{\"command\":\"ambient.credit\",\"args\":{\"enabled\":false}}",
                    screensaverBooleanTemplate("credit")));
            components.put("ambient_url", valueText(
                    "Ambient page",
                    "ambient.url",
                    "{{ value_json.ambient.url }}"));
            components.put("ambient_picture", sensor(
                    "Ambient picture", null, null, null,
                    "{{ value_json.ambient.picture.title | default('', true) }}"));
            // The panel's sensors (see Sensors): one entity each, under the panel's device, named
            // as the companion app names its own. A sensor that is off, or that this device does
            // not have, is left out; one this panel announced before is withdrawn at the publish
            // below, so switching it off takes its entity away, and a panel without the hardware
            // never mentions it.
            for (Sensors.Def def : Sensors.ALL) {
                if (def.kind == Sensors.Kind.PANEL) {
                    // Battery, power, network and the rest have had their entities since
                    // before the sensors list existed; announcing them again would be the same
                    // reading twice.
                    continue;
                }
                org.json.JSONObject one = sensorBlock.optJSONObject(def.id);
                String key = "sensor_" + def.id;
                if (one == null || !one.optBoolean("active")) {
                    continue;
                }
                JSONObject entity = new JSONObject();
                entity.put("p", def.platform);
                entity.put("name", def.name);
                entity.put("unique_id", uniqueId(def.name));
                if (def.deviceClass != null && !"enum".equals(def.deviceClass)) {
                    entity.put("device_class", def.deviceClass);
                }
                if (def.unit != null) {
                    entity.put("unit_of_measurement", def.unit);
                }
                if (def.stateClass != null) {
                    entity.put("state_class", def.stateClass);
                }
                if (Sensors.BINARY.equals(def.platform)) {
                    entity.put("payload_on", "ON");
                    entity.put("payload_off", "OFF");
                    entity.put("value_template", "{{ 'ON' if value_json.sensors." + def.id
                            + ".value else 'OFF' }}");
                } else {
                    entity.put("value_template", "{{ value_json.sensors." + def.id + ".value }}");
                }
                if (def == Sensors.AUDIO || def == Sensors.BLUETOOTH || def == Sensors.NFC
                        || def == Sensors.CAMERA) {
                    entity.put("json_attributes_topic", topicPrefix + "state");
                    entity.put("json_attributes_template",
                            "{{ value_json.sensors." + def.id + ".attributes | tojson }}");
                }
                components.put(key, entity);
            }
            // The camera's controls and its picture, the set Frigate and Blue Iris publish:
            // motion detection as a switch, a snapshot button, and the last picture on motion
            // as a camera entity, all only while the camera is on.
            org.json.JSONObject cameraState = sensorBlock.optJSONObject("camera");
            boolean cameraOn = cameraState != null && cameraState.optBoolean("active");
            org.json.JSONObject cameraAttributes = cameraState == null ? null
                    : cameraState.optJSONObject("attributes");
            // The snapshot button and the picture entity only while pictures may go to the
            // broker ("Picture to MQTT", camera_mqtt): a photo of the room is retained there,
            // and the privacy text says it goes only when that is switched on (review,
            // 2026-10-01).
            boolean pictures = cameraOn && cameraAttributes != null
                    && cameraAttributes.optBoolean("pictures");
            if (cameraOn) {
                components.put("camera_motion", toggle(
                        "Camera motion detection",
                        "{\"command\":\"camera.motion\",\"args\":{\"enabled\":true}}",
                        "{\"command\":\"camera.motion\",\"args\":{\"enabled\":false}}",
                        "{{ 'ON' if value_json.sensors.camera.attributes.detecting else 'OFF' }}"));
            }
            if (pictures) {
                components.put("camera_snapshot", button("Camera snapshot", "camera.snapshot"));
                JSONObject picture = new JSONObject();
                picture.put("p", "camera");
                picture.put("name", "Camera picture");
                picture.put("unique_id", uniqueId("Camera picture"));
                picture.put("topic", topicPrefix + "camera");
                components.put("camera_picture", picture);
            } else if (!cameraOn) {
                clearPicture();
            }
            // The screen as the wall shows it: a button that asks for a picture and an image
            // entity that shows the last one, retained on the screenshot topic; on MQTT the
            // broker's credentials are the gate, as for every command, so both are announced
            // whenever MQTT runs. Key, id and name say
            // the same thing, so the button has a name of its own, as the camera's snapshot
            // button does beside the camera's picture.
            components.put("take_a_screenshot", button("Take a screenshot", "kiosk.screenshot"));
            // One button per camera and per group, "Show <name>", which pops it up; a doorbell's automation
            // in Home Assistant sends camera.trigger itself, for the panel's automations.
            java.util.List<String> shown = new java.util.ArrayList<>();
            for (Cameras.Camera camera : KioskConfig.camerasOf(appContext)) {
                shown.add(camera.name);
            }
            for (Cameras.Group group : KioskConfig.cameraGroupsOf(appContext)) {
                shown.add(group.name);
            }
            for (String cameraName : shown) {
                String name = "Show " + cameraName;
                JSONObject show = button(name, new JSONObject().put("command", "camera.show")
                        .put("args", new JSONObject().put("value", cameraName)).toString());
                String key = uniqueId(name).substring(config.deviceId.length() + 1)
                        .replaceAll("[^a-z0-9_]", "_");
                // "Door 1" and "Door-1" squeeze to one key: the second keeps its own button.
                for (int n = 2; components.has(key); n++) {
                    key = key.replaceAll("_\\d+$", "") + "_" + n;
                }
                components.put(key, show);
            }
            JSONObject screen = new JSONObject();
            screen.put("p", "image");
            screen.put("name", "Screenshot");
            screen.put("unique_id", uniqueId("Screenshot"));
            screen.put("image_topic", topicPrefix + "screenshot");
            screen.put("content_type", "image/jpeg");
            components.put("screenshot", screen);
            // One switch per automation, so a rule can be paused from Home Assistant; a rule
            // that was deleted is withdrawn at the publish below, like a sensor switched off.
            for (int index = 0; index < rules.length(); index++) {
                JSONObject rule = rules.optJSONObject(index);
                if (rule == null) {
                    continue;
                }
                String id = rule.optString("id", "");
                JSONObject ruleSwitch = toggle(
                        rule.optString("name", id),
                        "{\"command\":\"automation.enabled\",\"args\":{\"enabled\":true,\"value\":\""
                                + id + "\"}}",
                        "{\"command\":\"automation.enabled\",\"args\":{\"enabled\":false,\"value\":\""
                                + id + "\"}}",
                        "{{ 'ON' if (value_json.automations | selectattr('id', 'eq', '" + id
                                + "') | map(attribute='enabled') | first) else 'OFF' }}");
                // By the id, not the name: two rules may share a name, and a rename must keep
                // the entity rather than strand one under the old unique id.
                ruleSwitch.put("unique_id", uniqueId("automation " + id));
                components.put("automation_" + id, ruleSwitch);
            }
            announcedSensors = sensorsKey(sensorBlock, rules);
            announcedCameras = camerasKey();
            // The Web admin switch reflects the operator's flag, not whether a socket is bound:
            // with no admin password stored, "on" is an honest description of intent while the
            // bind stays refused, and the runtime state carries the difference.
            components.put("web_admin", toggle(
                    "Web admin",
                    "{\"command\":\"webadmin.enabled\",\"args\":{\"enabled\":true}}",
                    "{\"command\":\"webadmin.enabled\",\"args\":{\"enabled\":false}}",
                    "{{ 'ON' if value_json.config.web_admin_enabled else 'OFF' }}"));
            // The two URLs, as text entities so both can be set from Home Assistant, as
            // decided. They read different fields on purpose: the stored dashboard
            // reads config.dashboard_url, while the one-off reads runtime.last_page_url, the page
            // actually on the glass. A one-off has nothing stored to read back, and an entity
            // whose state cannot be read is the entity that snaps back on every edit, which is
            // exactly how the Web admin switch broke earlier today.
            components.put("dashboard_url", text(
                    "Dashboard URL",
                    "kiosk.set_url",
                    "{{ value_json.config.dashboard_url }}"));
            components.put("open_url", text(
                    "Open URL once",
                    "kiosk.open_url",
                    "{{ value_json.runtime.last_page_url }}"));
            components.put("home", button("Main dashboard", "kiosk.home"));
            components.put("reload", button("Reload dashboard", "kiosk.reload"));
            components.put("restart", button("Restart kiosk", "kiosk.restart"));
            // "Display on" since 2026-09-09, to pair with "Display off"; the key changed with the
            // name, per the naming rule.
            components.put("display_on", button("Display on", "display.wake"));
            components.put("display_off", button("Display off", "display.visual_off"));
            // Only where it can work: the command answers "unsupported" with a provisioning hint on
            // an ordinary install, and a button that can only fail is not announced there, the
            // same rule as the light sensor and the battery. No shutdown button
            // exists at all: nothing can power off an Android device, which is why that command
            // was deleted outright.
            boolean deviceOwner = KioskService.isDeviceOwner(appContext);
            if (deviceOwner) {
                components.put("reboot", button("Reboot tablet", "system.reboot"));
            }
            discovery.put("cmps", components);

            publishMqttStateEntity(device, origin);
            publishTagDiscovery(device);

            String topic = discoveryTopic();

            // Simply leaving a component out does NOT remove an entity Home Assistant already
            // discovered, it strands it as "unavailable" forever. The documented removal is the
            // component once more as an empty config holding only its platform ("p"), then an
            // update without it. This panel removes exactly what it announced before and does not
            // announce now (a sensor switched off, the camera's controls, a deleted rule, an
            // entity a new build renamed or dropped), the way zigbee2mqtt does: what it announced
            // is what this process published and, after a restart or an update, the payload the
            // broker kept (readRetainedDiscovery). A new panel finds nothing and sends no removal
            // at all, so Muralis starts on MQTT as if it were used for the first time
            // (2026-10-06). A removal that Home Assistant misses because it is offline at that
            // moment leaves the entity unavailable until a person deletes it, which Tasmota,
            // ESPHome (its documentation says so), zigbee2mqtt and FreeKiosk all accept: Home
            // Assistant acts on an empty config only for a component it has discovered since it
            // started (measured on the test instance, 2026-10-06). A key whose platform changed
            // gets the old platform's empty config, since both cannot stand under one key. A run
            // that did not go out whole is not remembered, and the next state publish runs it
            // again.
            java.util.Map<String, String> now = announcedIn(components);
            DiscoveryWithdrawals.Plan plan = DiscoveryWithdrawals.plan(announced, now);
            boolean handedOver = plan.stubs.isEmpty()
                    || publish(topic, withStubs(discovery, plan.stubs), 1, true);
            // Not without the removals: the plain payload would replace the broker's record of
            // what was announced, and a controller rebuilt before the retry could not remove it.
            handedOver = handedOver && publish(topic, discovery.toString(), 1, true);
            discoveryStale = !handedOver;
            if (handedOver) {
                for (String key : plan.removed.keySet()) {
                    Log.i(TAG, "Removed from Home Assistant: " + key);
                }
                announced.clear();
                announced.putAll(now);
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The payload with an empty config, its platform alone, for each of these keys. */
    private static String withStubs(JSONObject discovery, java.util.Map<String, String> stubs)
            throws JSONException {
        JSONObject copy = new JSONObject();
        for (java.util.Iterator<String> keys = discovery.keys(); keys.hasNext(); ) {
            String key = keys.next();
            copy.put(key, discovery.get(key));
        }
        JSONObject components = new JSONObject();
        JSONObject announcedComponents = discovery.getJSONObject("cmps");
        for (java.util.Iterator<String> keys = announcedComponents.keys(); keys.hasNext(); ) {
            String key = keys.next();
            components.put(key, announcedComponents.get(key));
        }
        for (java.util.Map.Entry<String, String> entry : stubs.entrySet()) {
            components.put(entry.getKey(), new JSONObject().put("p", entry.getValue()));
        }
        copy.put("cmps", components);
        return copy.toString();
    }

    /**
     * Each announced component's key and platform, the pair Home Assistant identifies a component
     * by. An entry holding only its platform is a removal, not an announcement, and is left out.
     */
    private static java.util.Map<String, String> announcedIn(JSONObject components) {
        java.util.Map<String, String> platforms = new java.util.LinkedHashMap<>();
        for (java.util.Iterator<String> keys = components.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject component = components.optJSONObject(key);
            String platform = component == null ? "" : component.optString("p", "");
            if (!platform.isEmpty() && component.length() > 1) {
                platforms.put(key, platform);
            }
        }
        return platforms;
    }

    /**
     * Tags are not a component of the device document: Home Assistant discovers them on their
     * own topic, one per panel, and a read on the state topic named here fires its tag scanned
     * event, so its tag automations work as they do with the companion app.
     */
    private void publishTagDiscovery(JSONObject device) throws JSONException {
        JSONObject tag = new JSONObject();
        tag.put("topic", topicPrefix + "tag");
        tag.put("value_template", "{{ value_json.tag_id }}");
        tag.put("device", device);
        publish("homeassistant/tag/" + config.deviceId + "/config", tag.toString(), 1, true);
    }

    /**
     * Whether the broker currently holds a session with this panel: the single live connectivity
     * entity, since the "Network state" one was removed as information-free (it could only ever
     * publish "connected"; see the no-live-network-entity comment in the discovery components
     * above). Two values and only ever two,
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

    /** The status light entity; see the components list for why the template schema. */
    private JSONObject statusLight() throws JSONException {
        JSONObject light = new JSONObject();
        light.put("p", "light");
        light.put("name", "Status light");
        light.put("unique_id", uniqueId("Status light"));
        light.put("schema", "template");
        light.put("command_topic", topicPrefix + "command");
        light.put("command_on_template", "{\"command\":\"light.on\",\"args\":{\"value\":\""
                + "{% if red is defined %}{{ red }},{{ green }},{{ blue }}{% endif %}\","
                + "\"percent\":{% if brightness is defined %}"
                + "{{ [(brightness * 100 / 255) | round(0, 'ceil') | int, 1] | max }}"
                + "{% else %}-1{% endif %}}}");
        light.put("command_off_template", "{\"command\":\"light.off\"}");
        light.put("state_template",
                "{{ 'on' if value_json.light is defined and value_json.light.on else 'off' }}");
        light.put("brightness_template",
                "{{ (value_json.light.brightness * 2.55) | round | int }}");
        light.put("red_template", "{{ value_json.light.color.split(',')[0] | int }}");
        light.put("green_template", "{{ value_json.light.color.split(',')[1] | int }}");
        light.put("blue_template", "{{ value_json.light.color.split(',')[2] | int }}");
        return light;
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
     * A number a person types or nudges in Home Assistant. Box mode rather than a slider: these
     * are durations in seconds, where a slider's resolution is a guess and a typed value is
     * exact.
     */
    private JSONObject number(String name, String command, int min, int max, String unit,
            String valueTemplate) throws JSONException {
        JSONObject box = new JSONObject();
        box.put("p", "number");
        box.put("name", name);
        box.put("unique_id", uniqueId(name));
        box.put("command_topic", topicPrefix + "command");
        box.put("command_template",
                "{\"command\":\"" + command + "\",\"args\":{\"value\":\"{{ value | int }}\"}}");
        box.put("min", min);
        box.put("max", max);
        box.put("step", 1);
        box.put("mode", "box");
        if (unit != null) {
            box.put("unit_of_measurement", unit);
        }
        box.put("value_template", valueTemplate);
        return box;
    }

    /** A text box whose command takes {@code value}, as Ambient's settings do. */
    private JSONObject valueText(String name, String command, String valueTemplate)
            throws JSONException {
        JSONObject box = text(name, command, valueTemplate);
        box.put("command_template",
                "{\"command\":\"" + command + "\",\"args\":{\"value\":{{ value | tojson }}}}");
        return box;
    }

    /**
     * A text box. The command carries a URL, so the payload is built with {@code tojson} rather
     * than by quoting the value into the template: a URL with a quote or a backslash in a query
     * parameter would otherwise produce a broken envelope, and this box exists precisely for
     * URLs with parameters.
     *
     * <p>{@code max} is Home Assistant's own ceiling for a text entity (255). A longer URL has to
     * go through a command payload; the box would refuse it in the frontend before it ever
     * reached the panel.
     */
    private JSONObject text(String name, String command, String valueTemplate)
            throws JSONException {
        JSONObject box = new JSONObject();
        box.put("p", "text");
        box.put("name", name);
        box.put("unique_id", uniqueId(name));
        box.put("command_topic", topicPrefix + "command");
        box.put("command_template",
                "{\"command\":\"" + command + "\",\"args\":{\"url\":{{ value | tojson }}}}");
        box.put("value_template", valueTemplate);
        box.put("max", 255);
        return box;
    }

    /**
     * A named choice among fixed options. The command template wraps the picked option into the
     * same JSON envelope every other entity sends, and {@code tojson} quotes it, so the panel
     * parses one shape.
     */
    private JSONObject select(
            String name,
            String command,
            org.json.JSONArray options,
            String valueTemplate) throws JSONException {
        JSONObject select = new JSONObject();
        select.put("p", "select");
        select.put("name", name);
        select.put("unique_id", uniqueId(name));
        select.put("command_topic", topicPrefix + "command");
        select.put("command_template",
                "{\"command\":\"" + command + "\",\"args\":{\"value\":{{ value | tojson }}}}");
        select.put("options", options);
        select.put("value_template", valueTemplate);
        return select;
    }

    /**
     * What the playlist select shows and sends when no playlist is in use.
     *
     * <p>A select cannot hold an empty option, and Home Assistant renders a state that is not one
     * of the options as unknown, so the absence of a playlist needs a word of its own. The command
     * side treats it as "none" because {@code setScreensaverPlaylist} trims it to nothing.
     */
    static final String PLAYLIST_NONE = "None";

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
