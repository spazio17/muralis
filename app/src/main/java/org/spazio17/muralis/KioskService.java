/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;

public final class KioskService extends Service implements KioskCommandDispatcher.Executor {
    private static final String TAG = "MuralisService";
    private static final String ACTION_RELOAD_CONFIGURATION =
            "org.spazio17.muralis.action.RELOAD_CONFIGURATION";
    private static final String ACTION_PUBLISH_TELEMETRY_SOON =
            "org.spazio17.muralis.action.PUBLISH_TELEMETRY_SOON";
    private static final String CHANNEL_ID = "kiosk_runtime";
    private static final int NOTIFICATION_ID = 505;
    private static final long REMOTE_POWER_DELAY_MS = 2_000L;
    /** How long after System.exit the alarm brings the activity back. */
    private static final long RESTART_RELAUNCH_DELAY_MS = 3_000L;
    /** Long enough for the log line and a pending MQTT publish to leave before the process dies. */
    private static final long RESTART_EXIT_DELAY_MS = 750L;
    private static final int RESTART_REQUEST_CODE = 7_301;
    /** Fast enough that the on-screen overlay reads as live rather than as a stale placard. */
    private static final long STATS_SAMPLE_INTERVAL_MS = 2_000L;
    /**
     * How long after an accepted command the panel republishes its state. Long enough for the
     * command's effect to be readable, short enough that a Home Assistant control does not visibly
     * spring back before the truth arrives.
     */
    private static final long STATE_ECHO_DELAY_MS = 400L;
    /**
     * Assumed full-scale value of {@code Settings.System.SCREEN_BRIGHTNESS}.
     *
     * <p>There is no public API for this. {@code PowerManager.BRIGHTNESS_ON} is 255 and would be the
     * natural constant to use, but it is not in the public SDK, so the number is stated here instead
     * of reached for through a hidden field. A device may legitimately use 1023 or 4095, which would
     * make the derived percentage wrong though still monotonic; that is why the raw value and this
     * assumption are both published in the stats, so a wrong reading can be diagnosed.
     */
    private static final int SYSTEM_BRIGHTNESS_SCALE = 255;
    /**
     * Whether a window brightness override is in force, and at what percentage, or -1 for none.
     * Written by {@code KioskActivity.setWindowBrightness}; see the note there on why this is separate
     * from the stored "last chosen level".
     */
    static final String APPLIED_BRIGHTNESS_KEY = "applied_brightness_percent";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private HandlerThread telemetryThread;
    private Handler telemetryHandler;
    private TelemetryCollector telemetryCollector;
    private MqttController mqttController;
    private HttpAdminServer httpAdminServer;
    private SystemStats systemStats;
    private NetworkGate startupGate;
    /**
     * The configuration each controller was last built from; see {@link #restartControllers}, which
     * compares against these so a save that changed neither leaves both running untouched.
     */
    private String mqttInputs;
    private String httpInputs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HardwareProperties hardwareProperties;
    /** When the last pressure-driven rebuild happened, on the monotonic clock. */
    private long lastRebuildAtMs;
    /**
     * The change-watch fields as of the last time state actually went out over MQTT, or null before
     * the first one. Compared against every two seconds; see {@link #statsTask}.
     */
    private TelemetryCollector.Watch lastPublishedWatch;

    /**
     * One sampler feeds three surfaces: the dashboard overlay, the HTTP stats page and MQTT
     * telemetry. Sampling per-surface would triple the procfs reads and let the numbers disagree.
     * Every sample is live and transient, nothing is accumulated or stored.
     */
    private final Runnable statsTask = new Runnable() {
        @Override
        public void run() {
            SystemStats.Sample sample = systemStats.sample();
            fillFromHardwareHal(sample);
            SystemStats.RuntimeFacts facts = collectRuntimeFacts();
            KioskRuntimeState.publish(sample, facts,
                    SystemStats.formatOverlay(sample, facts),
                    SystemStats.formatOverlayHtml(sample, facts));

            long now = KioskRuntimeState.nowMs();
            maybeMaintainDashboard(now);

            // Battery, charging, low memory and thermal status are worth publishing the moment
            // they change rather than waiting for the periodic interval, which can now be set as
            // high as five minutes. Deliberately narrow: cpu/memory-available/Wi-Fi signal jitter on
            // nearly every sample and were excluded on purpose, since wiring them in here would
            // mean publishing every two seconds regardless of whatever interval was configured,
            // which defeats having one at all.
            if (telemetryCollector != null) {
                TelemetryCollector.Watch watch = telemetryCollector.readWatch();
                if (watch.differsFrom(lastPublishedWatch)) {
                    // Updated immediately, not when the publish actually lands: a value that keeps
                    // wobbling around its new level must not re-trigger this on every remaining
                    // 2-second tick before publishStateSoon's own debounce fires.
                    lastPublishedWatch = watch;
                    publishStateSoon();
                }
            }
            telemetryHandler.postDelayed(this, STATS_SAMPLE_INTERVAL_MS);
        }
    };

    private final Runnable stateEchoTask = new Runnable() {
        @Override
        public void run() {
            MqttController mqtt = mqttController;
            if (mqtt != null) {
                mqtt.publishState(telemetryJson());
                lastPublishedWatch = telemetryCollector == null
                        ? lastPublishedWatch : telemetryCollector.readWatch();
            }
        }
    };

    private final Runnable telemetryTask = new Runnable() {
        @Override
        public void run() {
            // The MQTT client is gated on the panel having a network; this sampler is not, and
            // deliberately so, because the overlay and /api/stats must work on a panel with no
            // network at all. So there may be nothing to publish to yet. Skipping a tick costs
            // nothing: the next tick carries a fresh snapshot anyway.
            MqttController mqtt = mqttController;
            if (mqtt != null) {
                mqtt.publishState(telemetryJson());
                lastPublishedWatch = telemetryCollector == null
                        ? lastPublishedWatch : telemetryCollector.readWatch();
            }
            // Read live rather than cached, the same way statsOverlayEnabled already is, so a
            // changed preset takes effect on this task's own next tick with no
            // restart of the telemetry thread. Via the narrow reader, not load(), which would
            // decrypt every SecretStore entry on each tick just to reach one int.
            int seconds = KioskConfig.telemetryIntervalSecondsOf(KioskService.this);
            telemetryHandler.postDelayed(this, seconds * 1000L);
        }
    };

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, KioskService.class));
    }

    public static void reloadConfiguration(Context context) {
        context.startForegroundService(new Intent(context, KioskService.class)
                .setAction(ACTION_RELOAD_CONFIGURATION));
    }

    /**
     * Asks the running service to republish state shortly, from outside the service.
     *
     * <p>{@link KioskActivity} records a renderer death or a page load failure directly on
     * {@link KioskRuntimeState}, but only {@code KioskService} holds the MQTT client, so telling
     * Home Assistant about either needs this bridge. Same mechanism as {@link #reloadConfiguration},
     * an intent the already-running service acts on in {@link #onStartCommand}, rather than a new
     * one: a service this app depends on staying alive should have as few ways to be woken as
     * possible.
     */
    static void publishTelemetrySoon(Context context) {
        context.startForegroundService(new Intent(context, KioskService.class)
                .setAction(ACTION_PUBLISH_TELEMETRY_SOON));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        applyResourceGuarantees();
        acquireRuntimeLocks();
        startControllers();
        startTelemetry();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RELOAD_CONFIGURATION.equals(intent.getAction())) {
            restartControllers();
        } else if (intent != null
                && ACTION_PUBLISH_TELEMETRY_SOON.equals(intent.getAction())) {
            publishStateSoon();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopControllers();
        stopTelemetry();
        releaseRuntimeLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Dispatches a command already authenticated by whichever transport received it. */
    KioskCommandDispatcher.Result dispatch(String command, KioskCommandDispatcher.CommandArgs args) {
        KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(command, args, this);
        if ("accepted".equals(result.status)) {
            publishStateSoon();
        }
        return result;
    }

    /**
     * Publishes state shortly after a command changed something, instead of waiting for the next
     * 30-second tick.
     *
     * <p>Home Assistant's MQTT switch and number are not optimistic when a state topic is given:
     * after a toggle they keep showing the last state the device reported, so a control moved in
     * Home Assistant **visibly sprang back** and stayed wrong for up to thirty seconds. Reported
     * 2026-08-20. Nothing was actually wrong with the panel, it simply had not said so yet.
     *
     * <p>The short delay lets a command's effect land first; the write itself is synchronous but the
     * value is read back out of the system settings, not remembered. Coalesced, so a slider dragged
     * across a dozen values publishes once rather than a dozen times.
     */
    private void publishStateSoon() {
        Handler handler = telemetryHandler;
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(stateEchoTask);
        handler.postDelayed(stateEchoTask, STATE_ECHO_DELAY_MS);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent openKiosk = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, KioskActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openKiosk)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    /**
     * The nearest available replacement for {@code android:persistent="true"}.
     *
     * <p>This is the one lost capability with no real equivalent, and it matters more here than
     * anywhere else: the privileged ROM build pinned the kiosk at {@code oom_score_adj -800} with
     * its WebView renderer at {@code -700}, and that is the main reason it survived memory pressure
     * that kills the interim Huawei tablet's generic browser kiosk daily. An ordinary app runs at
     * ordinary priority. A foreground service plus device owner plus lock task is as close as an
     * unprivileged build gets, so take every part of it that is available.
     *
     * <p>Every step is individually optional and individually fail-soft. A device that is not
     * provisioned as device owner simply keeps its defaults and the kiosk still runs.
     */
    private void applyResourceGuarantees() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isIgnoringBatteryOptimizations(getPackageName())) {
            // Worth logging rather than prompting: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS opens a
            // dialog, and Play restricts declaring it. As device owner the exemption can be set
            // below without any prompt, which is why this is only a diagnostic.
            Log.i(TAG, "Not exempt from battery optimisation yet");
        }

        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Not device owner: running at ordinary app priority with no resource "
                    + "guarantees. Provision with `adb shell dpm set-device-owner "
                    + "org.spazio17.muralis/.KioskDeviceAdminReceiver`.");
            return;
        }
        android.content.ComponentName admin = KioskDeviceAdminReceiver.componentName(this);

        // Screen stays on whenever a cable is attached, which for a wall panel is always. Preferred
        // over writing Settings.System.SCREEN_OFF_TIMEOUT, which needs WRITE_SETTINGS; as device
        // owner this global write needs no user grant. The bitmask covers every charger type.
        try {
            policy.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    Integer.toString(BatteryManager.BATTERY_PLUGGED_AC
                            | BatteryManager.BATTERY_PLUGGED_USB
                            | BatteryManager.BATTERY_PLUGGED_WIRELESS));
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.w(TAG, "Could not pin stay-on-while-plugged-in", refused);
        }

        // Exempt this package from battery optimisation and app standby, so Doze cannot suspend the
        // dashboard's WebSocket on an idle wall panel. API 28+ only; on the API 26/27 MediaPad the
        // partial wake lock plus the foreground service are what carry this, which is weaker but is
        // the same posture that device had before.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                policy.setPackagesSuspended(admin, new String[] {getPackageName()}, false);
            } catch (SecurityException | IllegalArgumentException refused) {
                Log.w(TAG, "Could not apply device-owner package restrictions", refused);
            }
        }

        // Deliberately NOT inside the API 28 branch above, where it used to sit. DISALLOW_SAFE_BOOT
        // is API 25, so gating it on 28 meant it never applied on the API 26/27 hardware this app is
        // actually built for — and safe mode starts the device with third-party apps disabled: no
        // Muralis, no lock task, no foreground service. That is an escape from the kiosk needing
        // neither the corner-tap sequence nor a cable, on the primary target device.
        try {
            policy.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT);
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.w(TAG, "Could not block safe-mode boot", refused);
        }
        grantOwnRuntimePermissions(policy, admin);
        Log.i(TAG, "Device-owner resource guarantees applied");
    }

    /**
     * Grants this app its own runtime permissions, with no dialog.
     *
     * <p>A capability the privileged ROM build never needed and never had: it was platform-signed, so
     * its permissions were granted at install. This build is an ordinary app, but a device owner can
     * set the grant state of a runtime permission for any package including itself, so it can arrive
     * at the same place by a different route.
     *
     * <p>Why this matters for the product rather than being a convenience: a wall-mounted panel has
     * nobody standing in front of it. A runtime permission dialog on such a device is not a prompt,
     * it is a hang, and after an unattended reboot it would sit there over the dashboard until
     * somebody noticed. Auto-granting removes that failure mode entirely.
     *
     * <p>Scope is deliberately narrow: only permissions this app actually declares and needs, and
     * nothing in a location or privacy-sensitive class. In particular <b>no location permission is
     * requested</b>, so the Wi-Fi SSID stays unavailable, which remains the right call for a wall
     * panel and is unchanged from the decision recorded in the ROM repo's
     * docs/play-store-viability.md. Widening this set is a product decision, not a refactor.
     */
    private void grantOwnRuntimePermissions(
            DevicePolicyManager policy, android.content.ComponentName admin) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS is the only runtime permission this app declares, and it does not
            // exist before API 33; on the API 26 MediaPad the notification simply posts.
            return;
        }
        try {
            boolean granted = policy.setPermissionGrantState(admin, getPackageName(),
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            Log.i(TAG, "POST_NOTIFICATIONS auto-grant as device owner: "
                    + (granted ? "applied" : "refused"));
        } catch (SecurityException | IllegalArgumentException refused) {
            // Fail soft: KioskActivity still asks the user the ordinary way, and the foreground
            // service runs either way.
            Log.w(TAG, "Could not auto-grant POST_NOTIFICATIONS", refused);
        }
    }

    private void acquireRuntimeLocks() {
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muralis:runtime");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wifi = getSystemService(WifiManager.class);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Muralis:wifi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        Log.i(TAG, "Runtime wake and Wi-Fi locks acquired");
    }

    private void releaseRuntimeLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void startTelemetry() {
        telemetryThread = new HandlerThread("MuralisTelemetry");
        telemetryThread.start();
        telemetryHandler = new Handler(telemetryThread.getLooper());
        telemetryCollector = new TelemetryCollector(this);
        systemStats = new SystemStats();
        hardwareProperties = new HardwareProperties(this);
        telemetryHandler.post(statsTask);
        telemetryHandler.post(telemetryTask);
    }

    /**
     * Fills in CPU load and chip temperatures that procfs could not provide.
     *
     * <p>Only ever fills gaps, never overwrites: where the kernel does allow the procfs reads, those
     * stay authoritative, because they are the numbers the host unit tests cover and the ones the
     * privileged ROM build reported. On stock Android with SELinux enforcing they are all unknown, and
     * this is what puts real values back on the overlay. See {@link HardwareProperties}.
     */
    private void fillFromHardwareHal(SystemStats.Sample sample) {
        if (hardwareProperties == null) {
            return;
        }
        if (Double.isNaN(sample.cpuBusyPercent)) {
            sample.cpuBusyPercent = hardwareProperties.cpuBusyPercent();
        }
        if (Double.isNaN(sample.cpuTemperatureC)) {
            sample.cpuTemperatureC = hardwareProperties.cpuTemperatureC();
        }
        if (Double.isNaN(sample.gpuTemperatureC)) {
            sample.gpuTemperatureC = hardwareProperties.gpuTemperatureC();
        }
    }

    /**
     * Brings the remote surfaces up, but only once the panel actually has a network.
     *
     * <p>Muralis is running within a couple of seconds of boot, well before Wi-Fi has associated, so
     * both controllers used to start against no network at all: MQTT logged a connection failure it
     * then had to back off from, and the admin server bound a socket on a device with no address to
     * reach it at. Neither was fatal, and neither had to happen. {@link NetworkGate} gives up after
     * a minute and starts them anyway, so a panel with genuinely no network still ends up with a
     * running web admin, which is the surface somebody would use to diagnose exactly that.
     */
    private void startControllers() {
        cancelStartupGate();
        startupGate = NetworkGate.whenOnline(
                this, mainHandler, "MQTT and the web admin", this::startControllersNow);
    }

    private void startControllersNow() {
        KioskConfig config = KioskConfig.load(this);
        mqttInputs = mqttInputsOf(config);
        httpInputs = httpInputsOf(config);
        mqttController = new MqttController(this, this::handleMqttCommand);
        mqttController.start();
        httpAdminServer = new HttpAdminServer(this, this);
        httpAdminServer.start();
    }

    /**
     * Separator for the fingerprints below. NUL cannot appear in a host, a port, a username or a
     * password, so joining on it cannot produce the same string from two different configurations
     * the way joining on ":" could.
     *
     * <p>Written as an escape on purpose. It used to be a raw NUL byte in the string literal, which
     * compiles fine and behaves identically, but it made this file "data" rather than "text" to
     * every tool that samples for binary content — so {@code grep -r} over the source tree skipped
     * KioskService entirely and silently, and a search for a method defined here came back empty.
     * Keep it escaped.
     */
    private static final String FIELD_SEPARATOR = "\0";

    /**
     * The configuration a controller is actually built from, so a reload can tell whether that
     * controller needs rebuilding at all.
     *
     * <p>Not a hash: a collision would mean silently failing to restart on a real change, and these
     * are short enough that exact comparison costs nothing. Never logged.
     */
    private static String mqttInputsOf(KioskConfig config) {
        return config.mqttHost + FIELD_SEPARATOR + config.mqttPort + FIELD_SEPARATOR
                + config.mqttUsername + FIELD_SEPARATOR + config.mqttPassword + FIELD_SEPARATOR
                + config.deviceId;
    }

    private static String httpInputsOf(KioskConfig config) {
        return config.httpPort + FIELD_SEPARATOR + config.httpAdminPassword;
    }

    private void cancelStartupGate() {
        if (startupGate != null) {
            startupGate.cancel();
            startupGate = null;
        }
    }

    /**
     * Rebuilds only the controllers whose own configuration actually changed.
     *
     * <p>This used to stop and start both unconditionally. Stopping the MQTT client publishes a
     * retained {@code availability: offline}, so *every* settings save — including ones with
     * nothing to do with MQTT, like the escape sequences or the dashboard URL — made every Home
     * Assistant entity for the panel drop to unavailable and the "Connected" sensor read
     * disconnected, then recover a second or two later. On a panel whose whole job is to look
     * dependable, a save should not make it briefly look dead.
     *
     * <p>A gate still waiting counts as "not started yet", so anything pending is left alone to
     * come up with the current configuration on its own.
     */
    private void restartControllers() {
        // Still waiting for a network: the gate will start both with the current configuration when
        // it fires, so there is nothing to restart. Tested with isPending() rather than for a null
        // gate, because whenOnline never returns null and fires inline when already online — a
        // non-null gate is the normal running state, not evidence that anything is pending.
        if (startupGate != null && startupGate.isPending()) {
            return;
        }
        KioskConfig config = KioskConfig.load(this);
        String mqttNow = mqttInputsOf(config);
        String httpNow = httpInputsOf(config);

        if (!mqttNow.equals(mqttInputs) || mqttController == null) {
            if (mqttController != null) {
                mqttController.stop();
            }
            mqttInputs = mqttNow;
            mqttController = new MqttController(this, this::handleMqttCommand);
            mqttController.start();
            Log.i(TAG, "MQTT configuration changed, client restarted");
        }

        if (!httpNow.equals(httpInputs) || httpAdminServer == null) {
            if (httpAdminServer != null) {
                httpAdminServer.stop();
            }
            httpInputs = httpNow;
            httpAdminServer = new HttpAdminServer(this, this);
            httpAdminServer.start();
            Log.i(TAG, "Web admin configuration changed, server restarted");
        }
    }

    private void stopControllers() {
        // Before the controllers themselves: a gate still waiting would otherwise start the very
        // things this is tearing down, a second or two later and with no one left to stop them.
        cancelStartupGate();
        if (mqttController != null) {
            mqttController.stop();
        }
        if (httpAdminServer != null) {
            httpAdminServer.stop();
        }
    }

    private void stopTelemetry() {
        if (telemetryHandler != null) {
            telemetryHandler.removeCallbacksAndMessages(null);
        }
        if (telemetryThread != null) {
            telemetryThread.quitSafely();
        }
    }

    /**
     * The panel's two self-maintenance mechanisms, both unconditional.
     *
     * <p>A nightly <b>restart of the whole app</b>, and a <b>WebView rebuild</b> when the operating
     * system reports low memory. Deliberately different in scale: the nightly pass is routine
     * cleaning and can afford to take the process down, which reclaims the heap, the renderer,
     * native allocations and any leaked handler in a way rebuilding in place cannot. The pressure
     * path is a response to a live condition and has to be cheap enough to run at any hour.
     *
     * <p>The switch that used to gate this and the setting that chose its hour are both gone: these
     * are recovery mechanisms, not preferences. So is the learned memory model that briefly lived
     * here — see {@link RecyclePolicy} for why it could not work.
     */
    private void maybeMaintainDashboard(long nowMs) {
        java.util.Calendar clock = java.util.Calendar.getInstance();
        RecyclePolicy.Decision decision = RecyclePolicy.decide(nowMs, lastRebuildAtMs,
                clock.get(java.util.Calendar.HOUR_OF_DAY), clock.get(java.util.Calendar.MINUTE),
                RecyclePolicy.QUIET_HOUR, scheduledRecycleMinute(),
                localEpochDay(clock), KioskConfig.lastNightlyRestartDay(this),
                isSystemLowOnMemory());
        if (!decision.act()) {
            return;
        }
        Log.i(TAG, "Dashboard maintenance: " + decision.action + " (" + decision.reason + ")");
        KioskRuntimeState.recordRecycle(decision.reason);
        if (decision.action == RecyclePolicy.Action.NIGHTLY_RESTART) {
            restartApplication(localEpochDay(clock));
            return;
        }
        lastRebuildAtMs = nowMs;
        // A rebuild is rare and worth knowing about promptly rather than at the next scheduled
        // publish, which could be up to five minutes away with the slowest preset.
        publishStateSoon();
        kioskRestart();
    }

    /** Local calendar date as a day number, which is what the once-a-night rule compares. */
    private static long localEpochDay(java.util.Calendar clock) {
        // Not Instant/LocalDate: those need java.time desugaring on API 26, and this is one
        // subtraction. Days since the epoch in the device's own timezone, which is the timezone the
        // quiet hour is expressed in.
        long offsetMs = clock.get(java.util.Calendar.ZONE_OFFSET)
                + clock.get(java.util.Calendar.DST_OFFSET);
        return Math.floorDiv(clock.getTimeInMillis() + offsetMs, 86_400_000L);
    }

    /**
     * Ends this process and comes back.
     *
     * <p>The date is recorded first, synchronously, because everything after it may not happen: an
     * unrecorded restart fires again on the next tick after the process returns, which is a restart
     * loop rather than a nightly clean.
     *
     * <p>An alarm relaunches the activity rather than relying on the service being recreated.
     * {@code START_STICKY} would bring KioskService back on its own schedule, and the activity would
     * usually follow because it is HOME — but "usually" is doing too much work for the mechanism
     * that has to survive unattended for months. An {@link android.app.AlarmManager} one-shot is
     * held by the system, not by this process, so it fires whether or not anything here comes back
     * by itself.
     *
     * <p>{@code System.exit} rather than a graceful teardown: reclaiming everything is the entire
     * point, and a tidy shutdown that leaves the process alive would reclaim nothing.
     */
    private void restartApplication(long epochDay) {
        KioskConfig.recordNightlyRestartDay(this, epochDay);
        try {
            android.app.AlarmManager alarms = getSystemService(android.app.AlarmManager.class);
            android.app.PendingIntent relaunch = android.app.PendingIntent.getActivity(
                    this, RESTART_REQUEST_CODE,
                    new Intent(this, KioskActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    android.app.PendingIntent.FLAG_ONE_SHOT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (alarms != null) {
                alarms.setExact(android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + RESTART_RELAUNCH_DELAY_MS, relaunch);
            }
        } catch (RuntimeException refused) {
            // Fail soft, and say so. START_STICKY plus being the HOME activity is the fallback, and
            // it is a weaker guarantee rather than none.
            Log.w(TAG, "Could not schedule the post-restart relaunch; relying on START_STICKY",
                    refused);
        }
        Log.i(TAG, "Restarting Muralis for the nightly clean");
        // Give the log line and the pending MQTT publish a moment to leave.
        new Handler(Looper.getMainLooper()).postDelayed(() -> System.exit(0),
                RESTART_EXIT_DELAY_MS);
    }

    /** This panel's minute within {@link RecyclePolicy#QUIET_HOUR}, fixed for the device. */
    private int scheduledRecycleMinute() {
        return RecyclePolicy.scheduledMinuteOf(KioskConfig.deviceIdOf(this));
    }

    /**
     * The device's own low-memory verdict rather than a fraction this app invented. The threshold
     * behind it is set per device by the vendor, which is the whole point: it is the only figure
     * available that already knows how much memory this hardware has.
     */
    private boolean isSystemLowOnMemory() {
        android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
        if (manager == null) {
            return false;
        }
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.lowMemory;
    }

    /**
     * What MQTT publishes: the same document the HTTP surface serves, minus the fields that only
     * make sense to somebody already holding the admin password.
     *
     * <p>MQTT used to publish {@code telemetryCollector.snapshot()} raw, which meant the broker was
     * told strictly less than the web page: no {@code display} block and no {@code config} block at
     * all. So a Home Assistant brightness control or an auto-brightness switch had no state to read,
     * and could not be offered however willing MQTT was to carry it. Reported 2026-08-20.
     *
     * <p>The escape sequences, the admin port and the broker's own address are deliberately left
     * out. They are diagnostics for whoever administers the panel, and the escape sequences in
     * particular are the way out of the kiosk; the HTTP surface is authenticated one-to-one, while a
     * broker topic is readable by everything else subscribed to it.
     */
    org.json.JSONObject telemetryJson() {
        return buildStats(false);
    }

    /** Latest stats for the HTTP surface: a live snapshot, plus what is actually configured. */
    org.json.JSONObject statsJson() {
        return buildStats(true);
    }

    private org.json.JSONObject buildStats(boolean includeAdminDetail) {
        // The collector is created by startTelemetry(), which runs *after* startControllers() in
        // onCreate, and NetworkGate runs its work inline when a network is already up — the normal
        // case. So the HTTP accept thread is live and serving before this field is assigned, and a
        // browser polling /api/stats every five seconds will eventually land in that window. It
        // used to NPE on a worker thread and take the process down; the config block below needs no
        // collector, so report what is knowable and omit the rest for the fraction of a second it
        // takes the sampler to come up.
        TelemetryCollector collector = telemetryCollector;
        org.json.JSONObject stats =
                collector == null ? new org.json.JSONObject() : collector.snapshot();
        try {
            // What is actually applied on the device, so a page open in a browser can show the
            // truth rather than whatever was current when it was loaded. Secrets are excluded:
            // this is the same object MQTT publishes.
            KioskConfig config = KioskConfig.load(this);
            org.json.JSONObject applied = new org.json.JSONObject();
            applied.put("dashboard_url", config.dashboardUrl);
            applied.put("device_id", config.deviceId);
            if (includeAdminDetail) {
                applied.put("settings_sequence", config.settingsSequence);
                applied.put("launcher_sequence", config.launcherSequence);
            }
            applied.put("stats_overlay", config.statsOverlay);
            // Live system state rather than a stored preference, so the web admin's checkbox tracks
            // the tablet's own auto-brightness toggle however it was changed.
            applied.put("has_light_sensor", hasLightSensor(this));
            applied.put("auto_brightness", isAutoBrightnessOn(this));
            stats.put("display", displaySnapshot());
            applied.put("telemetry_interval_seconds",
                    TelemetryInterval.clampOrDefault(config.telemetryIntervalSeconds));
            if (includeAdminDetail) {
                applied.put("http_port", config.httpPort);
                applied.put("mqtt_host", config.mqttHost);
                applied.put("mqtt_port", config.mqttPort);
            }
            stats.put("config", applied);
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return stats;
    }

    /**
     * What the backlight is actually doing, so a remote surface can show it instead of guessing.
     *
     * <p>The web admin's brightness slider used to be hardcoded to 70 and never moved, which was
     * actively misleading: with the ambient sensor covered the panel was almost black while the slider
     * still read 70%. Reported from the panel 2026-08-19.
     *
     * <p>Three cases, and which one applies matters, so {@code source} says which:
     *
     * <ul>
     *   <li><b>auto</b>: automatic mode is on. Android writes the sensor-driven result back into
     *       {@code Settings.System.SCREEN_BRIGHTNESS} on this hardware, verified by covering the
     *       sensor and watching it fall to 4, so that value is the truth and Muralis holds no override.
     *   <li><b>kiosk</b>: {@code display.brightness} has set a per-window override, which outranks the
     *       system value for this app's window. That percentage is exact, since Muralis chose it.
     *   <li><b>system</b>: manual mode with no override, so the system value is the truth.
     * </ul>
     *
     * <p><b>The scale is an assumption, and it is exposed rather than hidden.</b>
     * {@code SCREEN_BRIGHTNESS} has no public maximum: {@code PowerManager.BRIGHTNESS_ON} is 255 and
     * is the conventional full-scale value, but a device is free to use 1023 or 4095 and there is no
     * public API that reports which. The raw value and the assumed scale are both published so a wrong
     * percentage can be diagnosed rather than puzzled over, and the percentage stays monotonic either
     * way.
     */
    private org.json.JSONObject displaySnapshot() {
        org.json.JSONObject display = new org.json.JSONObject();
        try {
            boolean auto = isAutoBrightnessOn(this);
            int scale = SYSTEM_BRIGHTNESS_SCALE;
            int raw = -1;
            try {
                raw = Settings.System.getInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, -1);
            } catch (RuntimeException unavailable) {
                // Reading Settings.System needs no permission, but never let a stats read throw.
                Log.w(TAG, "Could not read the system brightness", unavailable);
            }
            // The APPLIED override, not the last level chosen. Reading the wrong preference reported
            // a stale manual level after automatic mode had cleared the override.
            int override = getSharedPreferences("kiosk_runtime", MODE_PRIVATE)
                    .getInt(APPLIED_BRIGHTNESS_KEY, -1);

            // Brightness now always comes from the system setting, so mode is simply the checkbox.
            // The only remaining window override is display.visual_off, which dims the panel to 1% as a
            // presentation state rather than as a brightness choice, and it is reported as its own
            // source so a 1% reading is not mistaken for a real level.
            String source;
            double percent;
            if (override >= 0) {
                source = "display_off";
                percent = override;
            } else {
                source = auto ? "auto" : "manual";
                percent = raw < 0 ? -1 : Math.min(100.0, 100.0 * raw / scale);
            }

            display.put("auto", auto);
            display.put("has_light_sensor", hasLightSensor(this));
            display.put("source", source);
            display.put("brightness_percent",
                    percent < 0 ? org.json.JSONObject.NULL : Math.round(percent));
            display.put("system_raw", raw < 0 ? org.json.JSONObject.NULL : raw);
            display.put("system_scale_assumed", scale);
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return display;
    }

    private SystemStats.RuntimeFacts collectRuntimeFacts() {
        SystemStats.RuntimeFacts facts = new SystemStats.RuntimeFacts();
        facts.uptimeMs = SystemClock.elapsedRealtime();
        facts.rendererDeaths = KioskRuntimeState.rendererDeaths();
        facts.lastPageFinishedAgoMs = KioskRuntimeState.lastPageFinishedAgoMs();
        facts.lastPageError = KioskRuntimeState.lastPageError();
        facts.lastPageErrorAgoMs = KioskRuntimeState.lastPageErrorAgoMs();
        try {
            BatteryManager battery = getSystemService(BatteryManager.class);
            if (battery != null) {
                int capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                facts.batteryPercent = capacity >= 0 ? capacity : -1;
            }
            // Charge state comes from the sticky battery broadcast, the same source the telemetry
            // snapshot uses, rather than from BatteryManager.isCharging(): that collapses "full on
            // mains", "charging" and "plugged in but not taking charge" into one boolean, and it
            // reported charging for a while after the cable was pulled.
            Intent batteryState = registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryState != null) {
                int status = batteryState.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                // Whether a cable is attached is the one unambiguous fact here. EXTRA_STATUS is not:
                // this tablet reports BATTERY_STATUS_CHARGING with every power source false after the
                // charger is pulled, which is why the readout kept claiming "charging". Derive
                // everything from "plugged" and let the status only distinguish full from filling.
                facts.plugged = batteryState.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                facts.full = facts.plugged && status == BatteryManager.BATTERY_STATUS_FULL;
                facts.charging = facts.plugged
                        && (status == BatteryManager.BATTERY_STATUS_CHARGING || facts.full);
                if (facts.batteryPercent < 0) {
                    int level = batteryState.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryState.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    facts.batteryPercent = level >= 0 && scale > 0 ? level * 100.0 / scale : -1;
                }
            }
            WifiManager wifi = getSystemService(WifiManager.class);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                // Signal strength only. The SSID is deliberately not read: Android 10 redacts it
                // unless the caller holds a location permission or one of the network-settings
                // signature permissions, and a wall panel should need neither. The IP address is
                // the identifier that actually matters here, and it needs no permission.
                facts.wifiRssiDbm = wifi.getConnectionInfo().getRssi();
            }
            facts.ipAddress = activeIpAddress();
        } catch (RuntimeException unavailable) {
            // Same rule as the procfs reads: an unavailable figure stays unknown.
        }
        return facts;
    }

    /**
     * Address of whatever network is actually carrying traffic, read from {@link LinkProperties}
     * rather than from {@link WifiManager} so an Ethernet or USB-tethered panel reports its address
     * too. IPv4 wins when both families are present: it is the one somebody types into a browser.
     */
    private String activeIpAddress() {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return "";
        }
        LinkProperties link = connectivity.getLinkProperties(connectivity.getActiveNetwork());
        if (link == null) {
            return "";
        }
        String fallback = "";
        for (LinkAddress address : link.getLinkAddresses()) {
            java.net.InetAddress inet = address.getAddress();
            if (inet == null || inet.isLoopbackAddress() || inet.isLinkLocalAddress()) {
                continue;
            }
            if (inet instanceof java.net.Inet4Address) {
                return inet.getHostAddress();
            }
            if (fallback.isEmpty()) {
                fallback = inet.getHostAddress();
            }
        }
        return fallback;
    }

    private void handleMqttCommand(
            String id, String command, org.json.JSONObject arguments) {
        Log.i(TAG, "MQTT command " + command + " id=" + id);
        KioskCommandDispatcher.CommandArgs args = new KioskCommandDispatcher.CommandArgs(
                arguments.optInt("percent", -1),
                arguments.optString("url", null),
                arguments.has("enabled") ? arguments.optBoolean("enabled", false) : null);
        KioskCommandDispatcher.Result result = dispatch(command, args);
        mqttController.publishCommandResult(id, result.status, result.detail);
    }

    @Override
    public void kioskStart() {
        sendUiCommand("kiosk.start", -1, null);
    }

    @Override
    public void kioskStop() {
        sendUiCommand("kiosk.stop", -1, null);
    }

    @Override
    public void kioskReload() {
        sendUiCommand("kiosk.reload", -1, null);
    }

    @Override
    public void kioskRestart() {
        sendUiCommand("kiosk.restart", -1, null);
    }

    @Override
    public void displayWake() {
        wakeDisplay();
        sendUiCommand("display.wake", -1, null);
    }

    @Override
    public void displayVisualOff() {
        sendUiCommand("display.visual_off", -1, null);
    }

    /**
     * Sets the panel brightness by writing the system setting, not a per-window override.
     *
     * <p>Deliberate design, decided with the user 2026-08-19, and it is what makes the two controls
     * coherent: <b>the automatic-brightness checkbox alone decides whether the sensor or the operator
     * is in charge.</b> In manual mode this write is the level, and it sticks. In automatic mode
     * Android's own auto-brightness overwrites {@code SCREEN_BRIGHTNESS} at its next sensor update, so
     * a slider nudge is momentary and the sensor takes control back by itself.
     *
     * <p>The previous implementation set a per-window override, which outranked automatic mode
     * indefinitely. That created a third state where the checkbox said automatic while the panel was
     * pinned to a fixed level, and no label could make two controls that contradict each other clear.
     * Writing the system setting removes the state rather than describing it.
     *
     * <p>Needs the {@code WRITE_SETTINGS} app-op, the same one automatic brightness needs, and fails
     * soft without it.
     */
    @Override
    public String setBrightness(int percent) {
        return applyBrightness(this, percent);
    }

    /**
     * Writes the system brightness, or says why it could not. Static so the configuration screen
     * uses the identical path rather than a second implementation that can drift, the same way
     * {@link #applyAutoBrightness} is shared.
     *
     * @return null when applied, otherwise a reason fit to show or publish
     */
    static String applyBrightness(Context context, int percent) {
        if (!canWriteSystemSettings(context)) {
            Log.w(TAG, "Cannot set brightness: WRITE_SETTINGS has not been granted");
            return "brightness needs the WRITE_SETTINGS permission, which has not been granted";
        }
        if (isAutoBrightnessOn(context)) {
            // Refusing keeps the model the user chose on 2026-08-19 intact: the automatic-brightness
            // switch is the mode and nothing else is. Writing anyway would report a level the panel
            // is not showing, which is worse than saying no.
            Log.i(TAG, "Refusing a brightness of " + percent
                    + "%: automatic brightness owns the backlight");
            return "automatic brightness is on; turn it off to set a level";
        }
        int value = Math.max(1, Math.min(SYSTEM_BRIGHTNESS_SCALE,
                Math.round(percent / 100.0f * SYSTEM_BRIGHTNESS_SCALE)));
        try {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, value);
        } catch (SecurityException | IllegalArgumentException denied) {
            Log.w(TAG, "Could not set the system brightness", denied);
            return "the system refused the brightness write";
        }
        return null;
    }

    @Override
    public void setDashboardUrl(String url) {
        KioskConfig config = KioskConfig.load(this);
        config.dashboardUrl = url;
        config.save(this);
        sendUiCommand("kiosk.set_url", -1, url);
    }

    /**
     * Hands brightness to the ambient-light sensor, or takes it back.
     *
     * <p>Two halves, and both are needed: the system-wide
     * {@code SCREEN_BRIGHTNESS_MODE} decides whether Android tracks the sensor at all, and Muralis's
     * own per-window brightness override outranks it, so leaving a manual override in place would
     * silently defeat automatic mode. Enabling therefore clears the override; disabling restores the
     * last level the user asked for.
     *
     * @return false when there is no light sensor, so no state is touched and the caller is told
     */
    @Override
    public boolean setAutoBrightness(boolean enabled) {
        if (!applyAutoBrightness(this, enabled)) {
            return false;
        }
        // The activity owns the window override, so it has to be told to drop or restore it.
        sendUiCommand(enabled ? "display.auto_brightness_on" : "display.auto_brightness_off",
                -1, null);
        return true;
    }

    /**
     * Writes the system brightness mode. Shared by the remote command above and the on-device
     * configuration screen, which applies its own window override directly rather than going
     * through a broadcast to itself.
     *
     * @return false when there is no light sensor or the write was refused, so no caller reports a
     *         mode change that did not happen
     */
    static boolean applyAutoBrightness(Context context, boolean enabled) {
        if (!hasLightSensor(context)) {
            return false;
        }
        // Checked before the write, not just caught after it. SCREEN_BRIGHTNESS_MODE lives in
        // Settings.System, which needs WRITE_SETTINGS; unlike the Global/Secure namespaces there is
        // no device-owner setter for it, so device-owner status does not help here. The permission
        // is user-grantable through ACTION_MANAGE_WRITE_SETTINGS, and separating "cannot" from
        // "refused" is what lets the caller offer that screen instead of failing mutely.
        if (!canWriteSystemSettings(context)) {
            Log.w(TAG, "Automatic brightness needs the WRITE_SETTINGS grant");
            return false;
        }
        try {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    enabled ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        } catch (SecurityException | IllegalArgumentException denied) {
            // WRITE_SETTINGS is user-grantable, so an app build that has not been granted it lands
            // here. Report the failure rather than claiming a mode change that did not happen.
            Log.w(TAG, "Could not change the brightness mode", denied);
            return false;
        }
        Log.i(TAG, "Automatic brightness " + (enabled ? "enabled" : "disabled"));
        return true;
    }

    /**
     * Whether the {@code WRITE_SETTINGS} app-op has been granted to this package.
     *
     * <p>Declared in the manifest but not granted by installing: it is an "special" app op the user
     * allows from Settings, or that {@code adb shell appops set <pkg> WRITE_SETTINGS allow} sets
     * directly, which is the practical route on a wall-mounted panel being provisioned over a cable.
     */
    /** The system brightness as a percentage, or -1 when it cannot be read. */
    static int currentBrightnessPercent(Context context) {
        try {
            int raw = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            if (raw < 0) {
                return -1;
            }
            return Math.max(1, Math.min(100,
                    Math.round(raw * 100.0f / SYSTEM_BRIGHTNESS_SCALE)));
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    static boolean canWriteSystemSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    /** True when this hardware can measure ambient light at all. */
    static boolean hasLightSensor(Context context) {
        SensorManager sensors = context.getSystemService(SensorManager.class);
        return sensors != null && sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    /** Whether Android is currently tracking the light sensor. */
    static boolean isAutoBrightnessOn(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public void publishTelemetry() {
        MqttController mqtt = mqttController;
        if (mqtt == null) {
            // Only reachable through HTTP, which starts alongside MQTT, so this is belt and braces
            // rather than a state anybody has seen.
            Log.w(TAG, "telemetry.publish before MQTT started; nothing published");
            return;
        }
        mqtt.publishState(telemetryJson());
    }

    @Override
    public boolean reboot() {
        // Capability is checked here, before the delay, so the caller gets a truthful answer in its
        // HTTP response or MQTT command result rather than silence two seconds later. The delay
        // exists so that response can actually be delivered before the device goes down.
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Refusing system.reboot: not device owner");
            return false;
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::performReboot,
                REMOTE_POWER_DELAY_MS);
        return true;
    }

    /**
     * Device-owner reboot, and nothing else.
     *
     * <p>The privileged build tried {@link DevicePolicyManager#reboot} first and fell back to
     * {@code PowerManager.reboot}. The fallback is gone: it needs the signature {@code REBOOT}
     * permission, which this build does not declare and could not be granted, so it could only ever
     * throw. {@code DevicePolicyManager.reboot} needs only device-owner status, which an ordinary
     * Play-Store-installed app can be granted out of band, and is API 24 so it works on the API 26
     * MediaPad.
     *
     * <p>{@link #reboot()} has already confirmed device-owner status before this runs, so reaching
     * here without it means the status was lost in the two-second window, which is worth a warning
     * rather than a crash.
     */
    private void performReboot() {
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Device-owner status lost before the reboot could be issued");
            return;
        }
        try {
            policy.reboot(KioskDeviceAdminReceiver.componentName(this));
        } catch (SecurityException | IllegalStateException refused) {
            // IllegalStateException is documented for an ongoing call. Either way the panel stays up,
            // which is the right failure for a remote command.
            Log.w(TAG, "Device-owner reboot refused", refused);
        }
    }

    private void sendUiCommand(String command, int brightnessPercent, String url) {
        Intent intent = new Intent(KioskActions.UI_CONTROL)
                .setPackage(getPackageName())
                .putExtra(KioskActions.EXTRA_COMMAND, command);
        if (brightnessPercent >= 0) {
            intent.putExtra(KioskActions.EXTRA_BRIGHTNESS, brightnessPercent);
        }
        if (url != null) {
            intent.putExtra(KioskActions.EXTRA_URL, url);
        }
        // With the permission, so the receiver may demand it; see KioskActions.PERMISSION_UI_CONTROL
        // for why package-scoping alone was not enough on API 26.
        sendBroadcast(intent, KioskActions.PERMISSION_UI_CONTROL);
    }

    /**
     * Wakes the panel without {@code DEVICE_POWER}.
     *
     * <p>The privileged build called {@code PowerManager.wakeUp}, which needs the signature
     * {@code DEVICE_POWER} permission and whose {@code WAKE_REASON_APPLICATION} constant is not in
     * the public SDK at all. The replacement is entirely on the activity side: {@code
     * setTurnScreenOn(true)} plus {@code FLAG_TURN_SCREEN_ON} on a window that is then brought
     * forward turns the screen on with no permission whatsoever.
     *
     * <p>All this method still does is launch the activity, because a window can only turn the
     * screen on while it is coming to the front. The {@code display.wake} broadcast that follows in
     * {@link #displayWake()} is what clears any visual-off overlay.
     */
    private void wakeDisplay() {
        try {
            startActivity(new Intent(this, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException refused) {
            // Background-activity-launch restrictions (API 29+) can refuse this. The broadcast that
            // follows still lifts the overlay, so a refusal costs the screen-on, not the command.
            Log.w(TAG, "Could not bring the dashboard forward to wake the screen", refused);
        }
    }
}
