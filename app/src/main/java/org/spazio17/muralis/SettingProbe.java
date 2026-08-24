/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Answers "would this value work", from the one vantage point that can know: this device, on its
 * own network. Juri's design (2026-08-24), one function for every input whose validity is a
 * runtime fact rather than a spelling: the admin port (bindable here?), the dashboard URL
 * (answers HTTP from here?), the broker host (accepts TCP from here?). Both settings surfaces use
 * it, the web admin through {@code GET /api/check} and the tablet directly, so the two can never
 * disagree about what "reachable" means.
 *
 * <p>Advisory by nature, a port free now can be taken at the next boot, so the save paths keep
 * their own hard refusals; this exists so the operator finds out before saving instead of after.
 * Every probe blocks on the network and must be called off the main thread.
 */
final class SettingProbe {
    /** Short enough that a blur-triggered pre-check feels immediate even when the target is dead. */
    private static final int TIMEOUT_MS = 3_000;

    static final class Verdict {
        final boolean ok;
        final String detail;

        Verdict(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    private SettingProbe() {
    }

    /**
     * @param boundPort the port the running admin server actually holds, or -1 when it is down;
     *                  asking about that one is not a conflict, the holder is us.
     */
    static Verdict adminPort(String value, int boundPort) {
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return new Verdict(false, "not a port number");
        }
        String problem = KioskCommandDispatcher.validateAdminPort(port);
        if (problem != null) {
            return new Verdict(false, problem);
        }
        if (port == boundPort) {
            return new Verdict(true, "the web admin is serving on it right now");
        }
        if (portFree(port)) {
            return new Verdict(true, "port " + port + " is free");
        }
        return new Verdict(false, "port " + port + " is already in use on this device");
    }

    /**
     * Any HTTP status counts as reachable, a 404 included: the question this answers is "is there
     * a web server at this address", not "is the path right", and a Home Assistant login redirect
     * or an error page both prove the server is there.
     */
    static Verdict dashboardUrl(String value) {
        String problem = KioskCommandDispatcher.validateDashboardUrl(value);
        if (problem != null) {
            return new Verdict(false, problem);
        }
        try {
            java.net.HttpURLConnection probe = (java.net.HttpURLConnection)
                    new java.net.URL(value.trim()).openConnection();
            probe.setRequestMethod("HEAD");
            probe.setConnectTimeout(TIMEOUT_MS);
            probe.setReadTimeout(TIMEOUT_MS);
            probe.setInstanceFollowRedirects(false);
            int code = probe.getResponseCode();
            probe.disconnect();
            return new Verdict(true, "answered HTTP " + code);
        } catch (IOException | RuntimeException unreachable) {
            return new Verdict(false, "no HTTP answer: " + briefReason(unreachable));
        }
    }

    /**
     * A plain TCP connect, not an MQTT handshake: credentials may be "blank keeps the current
     * one" in a form, and the question is whether a broker is listening there at all, which the
     * accept alone answers.
     */
    static Verdict mqttHost(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            return new Verdict(false, "host is empty");
        }
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new InetSocketAddress(host.trim(), port), TIMEOUT_MS);
            return new Verdict(true, "accepts TCP on port " + port);
        } catch (IOException | RuntimeException unreachable) {
            return new Verdict(false, "no TCP answer on port " + port + ": "
                    + briefReason(unreachable));
        }
    }

    /** Whether this process could bind {@code port} right now; the holder may change by tonight. */
    static boolean portFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException | RuntimeException unusable) {
            return false;
        }
    }

    /** One line for a tooltip: the exception's message, or its name when it has none. */
    private static String briefReason(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
