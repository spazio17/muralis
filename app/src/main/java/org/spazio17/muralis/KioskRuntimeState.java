/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The few runtime facts that more than one component needs: the activity records what the WebView
 * did, the service's sampler records what the kernel counters said, and the HTTP admin threads and
 * MQTT telemetry read both.
 *
 * <p>Static state is the right shape here rather than a shortcut. Muralis is a single-process
 * persistent app, activity, service and HTTP workers all run in {@code org.spazio17.muralis}, so
 * there is no IPC boundary to cross, and the alternative (binding to the service from the activity
 * purely to read counters) would add lifecycle risk to the process that must never die.
 *
 * <p>Elapsed times use a monotonic clock deliberately: this tablet has no RTC battery worth
 * trusting and picks up NTP after boot, so a wall-clock delta can jump backwards and make an
 * uptime or "last loaded" readout nonsense. Wall-clock is used only for history row timestamps,
 * where an absolute time is the point.
 */
final class KioskRuntimeState {
    private static final AtomicInteger rendererDeaths = new AtomicInteger();
    /**
     * Web admin lockouts, so repeated password guessing is visible somewhere durable.
     *
     * <p>Here rather than only in logcat, because logcat is not an audit trail on this
     * hardware. Measured on the API 28 phone while testing the throttle: logd's chatty filter
     * pruned the lockout warning outright, reporting only
     * {@code uid=...(org.spazio17.muralis) pool-2-thread-5 expire 1 line}. A counter that rides
     * out on telemetry reaches Home Assistant, where it can raise an automation, and survives
     * for as long as the process does.
     */
    private static final AtomicInteger authLockouts = new AtomicInteger();

    private static volatile long lastRendererDeathAtMs = -1;
    private static volatile long lastAuthLockoutAtMs = -1;
    private static volatile String lastAuthLockoutHost = "";
    private static volatile long lastPageFinishedAtMs = -1;
    private static volatile long lastPageErrorAtMs = -1;
    private static volatile String lastPageError = "";
    private static volatile String lastPageUrl = "";
    private static volatile SystemStats.Sample lastSample;
    private static volatile SystemStats.RuntimeFacts lastFacts;
    private static volatile boolean httpAdminListening;
    private static volatile int httpAdminPort;
    private static volatile String overlayText = "";
    private static volatile String overlayHtml = "";
    private static volatile long lastRecycleAtMs = -1;
    private static volatile String lastRecycleReason = "";
    private static final AtomicInteger recycles = new AtomicInteger();
    private static final AtomicInteger mqttOutages = new AtomicInteger();
    private static volatile long lastMqttOutageAtMs = -1;
    private static volatile long lastMqttOutageDurationMs = -1;
    private static volatile String lastMqttOutageCause = "";

    private KioskRuntimeState() {
    }

    /**
     * Monotonic milliseconds; only ever meaningful as a difference against another reading.
     *
     * <p>{@code System.nanoTime} rather than {@code SystemClock.elapsedRealtime} so this class stays
     * free of Android imports and host-testable, which {@code scripts/test-host.sh} enforces. The
     * difference matters in one case: {@code nanoTime} does not advance while the device is
     * suspended, so every elapsed figure here, including the twelve-hour window
     * {@link RecyclePolicy} checks, would under-report across a suspend. A wall panel holds a wake
     * lock and is configured to stay on while charging, so it does not suspend in the deployment
     * this is built for. If that ever stops being true, this is the line to change, and it will
     * cost this class its host tests.
     */
    static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    static void recordRendererDeath() {
        rendererDeaths.incrementAndGet();
        lastRendererDeathAtMs = nowMs();
    }

    static void recordPageFinished(String url) {
        lastPageFinishedAtMs = nowMs();
        lastPageUrl = url == null ? "" : url;
    }

    static void recordPageError(String description) {
        lastPageErrorAtMs = nowMs();
        lastPageError = description == null ? "" : description;
    }

    static void recordRecycle(String reason) {
        recycles.incrementAndGet();
        lastRecycleAtMs = nowMs();
        lastRecycleReason = reason == null ? "" : reason;
    }

    static int recycles() {
        return recycles.get();
    }

    static long lastRecycleAgoMs() {
        return lastRecycleAtMs < 0 ? -1 : nowMs() - lastRecycleAtMs;
    }

    static String lastRecycleReason() {
        return lastRecycleReason;
    }

    /**
     * Records a survived MQTT outage, written by {@code MqttController} on reconnect. The cause is
     * {@code OutageLedger}'s verdict, "network" or "broker", which is the answer to the question a
     * live entity can never carry: whether MQTT died alone while the network stayed up. Recorded
     * here so it rides out on the very telemetry publish the reconnect triggers.
     */
    static void recordMqttOutage(String cause, long durationMs) {
        mqttOutages.incrementAndGet();
        lastMqttOutageAtMs = nowMs();
        lastMqttOutageDurationMs = durationMs;
        lastMqttOutageCause = cause == null ? "" : cause;
    }

    static int mqttOutages() {
        return mqttOutages.get();
    }

    static long lastMqttOutageAgoMs() {
        return lastMqttOutageAtMs < 0 ? -1 : nowMs() - lastMqttOutageAtMs;
    }

    static long lastMqttOutageDurationMs() {
        return lastMqttOutageDurationMs;
    }

    static String lastMqttOutageCause() {
        return lastMqttOutageCause;
    }

    /** Records that one address has been locked out of the web admin for guessing. */
    static void recordAuthLockout(String host) {
        authLockouts.incrementAndGet();
        lastAuthLockoutAtMs = nowMs();
        lastAuthLockoutHost = host == null ? "" : host;
    }

    static int authLockouts() {
        return authLockouts.get();
    }

    static long lastAuthLockoutAgoMs() {
        return lastAuthLockoutAtMs < 0 ? -1 : nowMs() - lastAuthLockoutAtMs;
    }

    static String lastAuthLockoutHost() {
        return lastAuthLockoutHost;
    }

    static int rendererDeaths() {
        return rendererDeaths.get();
    }

    static long lastRendererDeathAgoMs() {
        return lastRendererDeathAtMs < 0 ? -1 : nowMs() - lastRendererDeathAtMs;
    }

    static long lastPageFinishedAgoMs() {
        return lastPageFinishedAtMs < 0 ? -1 : nowMs() - lastPageFinishedAtMs;
    }

    static long lastPageErrorAgoMs() {
        return lastPageErrorAtMs < 0 ? -1 : nowMs() - lastPageErrorAtMs;
    }

    static String lastPageError() {
        return lastPageError;
    }

    static String lastPageUrl() {
        return lastPageUrl;
    }

    static void publish(SystemStats.Sample sample, SystemStats.RuntimeFacts facts,
            String overlay, String html) {
        lastSample = sample;
        lastFacts = facts;
        overlayText = overlay == null ? "" : overlay;
        overlayHtml = html == null ? "" : html;
    }

    /**
     * Whether the web admin actually holds a socket, and on which port. The surface fails closed by
     * design, so "no password set" and "password too short" both end in silence; without this the
     * only evidence was one log line nobody reads from a wall panel.
     */
    static void publishHttpAdminState(boolean listening, int port) {
        httpAdminListening = listening;
        httpAdminPort = port;
    }

    static boolean httpAdminListening() {
        return httpAdminListening;
    }

    static int httpAdminPort() {
        return httpAdminPort;
    }

    static SystemStats.Sample lastSample() {
        return lastSample;
    }

    static SystemStats.RuntimeFacts lastFacts() {
        return lastFacts;
    }

    /** The formatted overlay block, or empty until the first sample has been taken. */
    static String overlayText() {
        return overlayText;
    }

    /** The same block as coloured HTML, which is what the on-screen overlay renders. */
    static String overlayHtml() {
        return overlayHtml;
    }
}
