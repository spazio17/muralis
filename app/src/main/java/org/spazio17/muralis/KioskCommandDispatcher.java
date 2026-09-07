/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Command handling shared by every transport (MQTT, HTTP). Transports parse their own
 * envelope/auth, build a {@link CommandArgs}, and get back a transport-agnostic {@link Result}.
 */
final class KioskCommandDispatcher {
    private static final int MAX_URL_LENGTH = 2_048;
    /**
     * MQTT's alphabet, not taste: the id becomes the topic prefix and every Home Assistant
     * unique_id, so anything a topic cannot carry must never be stored as an id.
     */
    private static final java.util.regex.Pattern DEVICE_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private KioskCommandDispatcher() {
    }

    static final class Result {
        final String status;
        final String detail;

        Result(String status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    static final class CommandArgs {
        static final CommandArgs EMPTY = new CommandArgs(-1, null, null, null);

        final int brightnessPercent;
        final String url;
        /** Null when the caller did not say, which is rejected rather than guessed at. */
        final Boolean enabled;
        /** A named choice, for commands whose argument is a vocabulary rather than a flag. */
        final String value;

        CommandArgs(int brightnessPercent, String url) {
            this(brightnessPercent, url, null, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled) {
            this(brightnessPercent, url, enabled, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled, String value) {
            this.brightnessPercent = brightnessPercent;
            this.url = url;
            this.enabled = enabled;
            this.value = value;
        }
    }

    interface Executor {
        void kioskStart();

        void kioskStop();

        void kioskReload();

        void kioskRestart();

        void displayWake();

        void displayVisualOff();

        /**
         * Sets the panel's brightness.
         *
         * @return null when it was actually applied, otherwise why it was not. A string rather than
         *         a boolean because there is more than one way to fail and the caller has to be able
         *         to say which: without the {@code WRITE_SETTINGS} app-op nothing can be written at
         *         all, and while automatic brightness is on the write lands in the setting but never
         *         reaches the backlight.
         */
        String setBrightness(int percent);

        /**
         * Hands the panel's brightness to the ambient-light sensor, or takes it back.
         *
         * @return false when this device has no light sensor, so the caller can say so rather than
         *         report success for something that cannot happen
         */
        boolean setAutoBrightness(boolean enabled);

        /**
         * Mounts the dashboard: "landscape", "portrait", or "auto", which follows the
         * accelerometer until a fixed one is chosen. The strings mirror KioskConfig's
         * ORIENTATION_* constants; they are spelled out here because this class is also compiled
         * for the host test suite, where the Android side does not exist.
         *
         * @return false only when "auto" is asked of a device with no accelerometer, so the
         *         caller can refuse rather than report success for something that cannot happen;
         *         the same contract as {@link #setAutoBrightness}
         */
        boolean setOrientation(String value);

        /** Stores {@code url} as the panel's dashboard and shows it. */
        void setDashboardUrl(String url);

        /**
         * Shows {@code url} now without storing it, so the next reload of the *kiosk* (a restart,
         * a reboot, the nightly clean) returns to the stored dashboard. For a URL with one-off
         * query parameters, which is what a stored dashboard URL must not become.
         */
        void openUrlOnce(String url);

        /** Goes back to the stored dashboard URL, whatever is on screen now. */
        void showMainDashboard();

        /**
         * Allows or stops the web admin surface, without touching the stored password. No return
         * value: the flag always persists; whether a socket then binds is reported where it
         * always was, the runtime state and the tablet's status line.
         */
        void setWebAdminEnabled(boolean enabled);

        /**
         * Publishes the telemetry document now.
         *
         * @return null when the publish was handed to a live MQTT session, otherwise a short
         *         reason ("MQTT is not configured", "MQTT is not connected") for the caller's
         *         rejection. Void was the audited shape: the dispatcher answered accepted while
         *         the payload was silently dropped on a missing or disconnected client.
         */
        String publishTelemetry();

        /**
         * Reboots the device, asynchronously.
         *
         * @return false when this build cannot reboot at all, which on an unprivileged app means it
         *         has not been provisioned as device owner. Reporting that is the difference between
         *         a caller knowing its automation did nothing and a caller believing it worked.
         */
        boolean reboot();

    }

    static Result dispatch(String command, CommandArgs args, Executor executor) {
        switch (command) {
            case "kiosk.start":
                executor.kioskStart();
                return accepted();
            case "kiosk.stop":
                executor.kioskStop();
                return accepted();
            case "kiosk.reload":
                executor.kioskReload();
                return accepted();
            case "kiosk.restart":
                executor.kioskRestart();
                return accepted();
            case "kiosk.set_url": {
                String error = validateDashboardUrl(args.url);
                if (error != null) {
                    return rejected(error);
                }
                executor.setDashboardUrl(args.url.trim());
                return accepted();
            }
            case "kiosk.open_url": {
                String error = validateDashboardUrl(args.url);
                if (error != null) {
                    return rejected(error);
                }
                executor.openUrlOnce(args.url.trim());
                return accepted();
            }
            case "kiosk.home":
                executor.showMainDashboard();
                return accepted();
            case "display.wake":
                executor.displayWake();
                return accepted();
            case "display.visual_off":
                executor.displayVisualOff();
                return accepted();
            case "display.brightness": {
                if (args.brightnessPercent < 0 || args.brightnessPercent > 100) {
                    return rejected("percent must be between 0 and 100");
                }
                // Measured on the panel 2026-08-20: with automatic brightness on, writing
                // Settings.System.SCREEN_BRIGHTNESS moves the stored number and the backlight does
                // not follow, because the auto-brightness algorithm owns it (mAppliedAutoBrightness
                // = true, mScreenBrightness unchanged across a 5% and a 95% write). The command was
                // accepted, nothing happened, and the stats then reported the level nobody was
                // looking at. The 2026-08-19 design assumed the nudge was momentary until the next
                // sensor recompute; it is not momentary, it is inert.
                String problem = executor.setBrightness(args.brightnessPercent);
                if (problem != null) {
                    return rejected(problem);
                }
                return accepted();
            }
            case "display.auto_brightness":
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                if (!executor.setAutoBrightness(args.enabled)) {
                    return rejected("this device has no ambient light sensor");
                }
                return accepted();
            // There is deliberately no "kiosk.auto_recycle" and no "kiosk.recycle_time". The
            // dashboard recycle is a recovery mechanism, not a preference: nobody has a reason to
            // turn a panel's self-healing off, and the schedule is derived per device rather than
            // chosen. Both fall through to "unsupported", which is the honest answer for a control
            // this build does not have. See RecyclePolicy.
            case "display.orientation":
                if (!"auto".equals(args.value) && !"landscape".equals(args.value)
                        && !"portrait".equals(args.value)) {
                    return rejected("value must be auto, landscape or portrait");
                }
                if (!executor.setOrientation(args.value)) {
                    return rejected("this device has no accelerometer to follow");
                }
                return accepted();
            case "webadmin.enabled":
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                executor.setWebAdminEnabled(args.enabled);
                return accepted();
            case "telemetry.publish": {
                // The executor answers for itself: with no broker configured, or a session that is
                // down, "accepted" would be the accepted no-op shape system.shutdown was deleted
                // to avoid, a caller told its publish happened when nothing left the device.
                String problem = executor.publishTelemetry();
                if (problem != null) {
                    return rejected(problem);
                }
                return accepted();
            }
            // There is deliberately no "system.shutdown" case. Android exposes no public and no
            // device-owner API to power a device off at any API level; the privileged ROM build did
            // it through the hidden IPowerManager interface, which hidden-API enforcement blocks for
            // anything not platform-signed. It therefore falls through to "unsupported", which is the
            // honest answer. Do not re-add it as a no-op: an accepted command that does nothing is
            // worse than an unsupported one, because callers stop watching the panel.
            case "system.reboot":
                if (!executor.reboot()) {
                    return new Result("unsupported",
                            "reboot needs device-owner status; provision Muralis as device owner");
                }
                return new Result("accepted", "rebooting");
            default:
                return new Result("unsupported", "unknown command");
        }
    }


    /** Returns null when {@code url} is an acceptable dashboard URL, an error message otherwise. */
    /**
     * The example both settings surfaces show for the dashboard URL, and they show it as a hint,
     * never as a value.
     *
     * <p>It was {@code http://homeassistant.local:8123/}, prefilled into the tablet's box as real
     * text, and on a fresh panel the first press of the save button stored it and loaded it,
     * landing on an error page for a host that does not exist (Juri, 2026-09-07). Two things
     * were wrong with that, and this fixes both. A value nobody typed must never be one press
     * from being stored: the form-design literature is unanimous that example text belongs in
     * the hint, alongside a visible label, where it shows the shape of a valid entry and cannot
     * be mistaken for data or submitted by accident. And Muralis is not a Home Assistant app: it
     * runs any dashboard, so the example must not name one product. {@code example.com} is
     * reserved for exactly this use (RFC 2606), resolves to nothing anyone runs, and reads as
     * "put yours here" to everyone.
     */
    static final String EXAMPLE_DASHBOARD_URL = "https://example.com/dashboard";

    static String validateDashboardUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "url must not be empty";
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            return "url is too long";
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "url must start with http:// or https://";
        }
        return null;
    }

    /**
     * Turns whatever a transport parsed for {@code enabled} into a Boolean, or null when the value
     * cannot honestly be read as one.
     *
     * <p>One parser for every transport, because the transports used to disagree: the query path
     * had its own spelling list (1/true/on meant true, anything else silently meant false), while
     * the JSON paths used {@code optBoolean(..., false)}, which coerces any non-boolean, the
     * number 1 included, to false. The same literal 1 therefore turned auto-brightness on over
     * {@code ?enabled=1} and off over {@code {"enabled":1}}, and both answered accepted. Booleans,
     * the numbers 0 and 1, and the spellings a shell, a browser form or an automation are likely
     * to send are accepted; everything else must be rejected by the caller, never guessed at.
     */
    static Boolean parseEnabledFlag(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number == 1d) {
                return Boolean.TRUE;
            }
            if (number == 0d) {
                return Boolean.FALSE;
            }
            return null;
        }
        if (value instanceof String) {
            String flag = ((String) value).trim();
            if (flag.equals("1") || flag.equalsIgnoreCase("true") || flag.equalsIgnoreCase("on")) {
                return Boolean.TRUE;
            }
            if (flag.equals("0") || flag.equalsIgnoreCase("false")
                    || flag.equalsIgnoreCase("off")) {
                return Boolean.FALSE;
            }
            return null;
        }
        return null;
    }

    /**
     * Returns null when {@code port} can serve the web admin, an error message otherwise.
     *
     * <p>The floor is 1024 rather than 1 because Juri proved the difference on hardware
     * (2026-08-24): port 80 passed a 1-65535 range check, persisted, and then failed at bind
     * time, since an unprivileged app can never bind below 1024 on Android. The server fails
     * closed on a bind error, so the admin's own settings box was able to switch the admin off,
     * undoable only from the tablet. This floor applies exclusively to the port this app binds
     * itself; the broker port is a remote port and keeps the full 1-65535 range.
     */
    static String validateAdminPort(int port) {
        if (port < 1024 || port > 65535) {
            return "web admin port must be between 1024 and 65535";
        }
        return null;
    }

    /**
     * Returns null when {@code id} is usable as this panel's identity, an error message otherwise.
     *
     * <p>Every surface that can write the id checks here, so the refusal happens where the
     * operator is looking. It used to be checked only in {@code MqttController.start()}, whose
     * whole answer was one logcat line: a space or a {@code /} typed into either settings surface
     * was stored, answered with success, and MQTT then never started. An empty id is refused for a
     * different reason: {@code KioskConfig.load} treats an empty id as "never provisioned" and
     * mints a fresh one, which orphans every Home Assistant entity the old id had announced and
     * leaves its retained topics behind.
     */
    static String validateDeviceId(String id) {
        if (id == null || id.trim().isEmpty()) {
            return "device id must not be empty";
        }
        if (!DEVICE_ID.matcher(id.trim()).matches()) {
            return "device id may only use letters, digits, dot, underscore and hyphen,"
                    + " up to 64 characters";
        }
        return null;
    }

    private static Result accepted() {
        return new Result("accepted", "");
    }

    private static Result rejected(String detail) {
        return new Result("rejected", detail);
    }
}
