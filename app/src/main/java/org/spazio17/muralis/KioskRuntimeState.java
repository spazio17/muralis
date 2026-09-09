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
    /**
     * Why the web admin is not listening, for the tablet's status line. A boolean could not say,
     * so the line blamed the password for every silence, including a bind that failed because
     * another service holds the port, which sent an operator hunting for the wrong fault.
     */
    private static volatile String httpAdminDownReason = "";
    private static volatile String overlayText = "";
    private static volatile String overlayHtml = "";
    private static volatile boolean dashboardAlive;
    private static volatile long lastRecycleAtMs = -1;
    private static volatile String lastRecycleReason = "";
    private static final AtomicInteger recycles = new AtomicInteger();
    /**
     * Whether an operator screen (configuration or the sequence recorder) is on the glass. The
     * activity writes it on every screen change; the service's maintenance tick reads it so the
     * pressure rebuild does not destroy a half-edited form (see RecyclePolicy.decide).
     */
    private static volatile boolean operatorOnScreen;

    private KioskRuntimeState() {
    }

    /**
     * Monotonic milliseconds; only ever meaningful as a difference against another reading.
     *
     * <p>The clock is injected rather than called directly, so this class stays free of Android
     * imports and host-testable (which {@code scripts/test-host.sh} enforces) while the app still
     * gets the right clock. The default is {@code System.nanoTime}, which does NOT advance while
     * the device is suspended, so every elapsed figure here would under-report across a suspend;
     * {@code MuralisApplication} therefore installs {@code SystemClock.elapsedRealtime}, which
     * does advance, before any component can record anything. The default only ever runs on a
     * host-test JVM, where there is no suspend and no Android.
     */
    static long nowMs() {
        return clock.getAsLong();
    }

    private static volatile java.util.function.LongSupplier clock =
            () -> System.nanoTime() / 1_000_000L;

    /**
     * Installs the real clock. Called once, from {@code MuralisApplication.onCreate}, which runs
     * before any activity, service or receiver: every timestamp field in this class is relative,
     * so mixing readings from two different clocks would produce garbage elapsed times, and the
     * only safe installation point is before the first reading.
     */
    static void useClock(java.util.function.LongSupplier realClock) {
        clock = realClock;
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
     *
     * @param downReason short phrase for the tablet's status line when {@code listening} is false,
     *                   ignored otherwise. Empty means "no reason offered".
     */
    static void publishHttpAdminState(boolean listening, int port, String downReason) {
        httpAdminListening = listening;
        httpAdminPort = port;
        httpAdminDownReason = listening || downReason == null ? "" : downReason;
        if (!listening) {
            httpAdminSecure = false;
            httpAdminFingerprint = "";
        }
    }

    private static volatile boolean httpAdminSecure;
    private static volatile String httpAdminFingerprint = "";

    /** Whether the running server speaks HTTPS, and with which certificate; see AdminCertificate. */
    static void publishHttpAdminTls(boolean secure, String fingerprint) {
        httpAdminSecure = secure;
        httpAdminFingerprint = fingerprint == null ? "" : fingerprint;
    }

    static boolean httpAdminSecure() {
        return httpAdminSecure;
    }

    /** Colon-separated SHA-256 of the served certificate, empty on plain HTTP. */
    static String httpAdminFingerprint() {
        return httpAdminFingerprint;
    }

    /** The scheme the running web admin answers on, for every place the tablet prints its address. */
    static String httpAdminScheme() {
        return httpAdminSecure ? "https://" : "http://";
    }

    /** Empty while listening, or when whoever stopped the server offered no reason. */
    static String httpAdminDownReason() {
        return httpAdminDownReason;
    }

    static boolean httpAdminListening() {
        return httpAdminListening;
    }

    static void publishOperatorOnScreen(boolean onScreen) {
        operatorOnScreen = onScreen;
    }

    /**
     * Whether a {@code KioskActivity} instance exists in this process, resumed or paused. Written
     * by its onCreate and onDestroy; read by {@code KioskService}, which relaunches the dashboard
     * on a device-owner panel when this is false (see {@link RelaunchPolicy}). Paused behind
     * Settings or the launcher counts as alive: that is the operator's doing, not a loss.
     */
    static void publishDashboardAlive(boolean alive) {
        dashboardAlive = alive;
    }

    static boolean dashboardAlive() {
        return dashboardAlive;
    }

    static boolean operatorOnScreen() {
        return operatorOnScreen;
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
