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
        static final CommandArgs EMPTY = new CommandArgs(-1, null, null);

        final int brightnessPercent;
        final String url;
        /** Null when the caller did not say, which is rejected rather than guessed at. */
        final Boolean enabled;

        CommandArgs(int brightnessPercent, String url) {
            this(brightnessPercent, url, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled) {
            this.brightnessPercent = brightnessPercent;
            this.url = url;
            this.enabled = enabled;
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
         * Mounts the dashboard upright or on its side.
         *
         * <p>No return value, unlike the brightness pair: every Android device can be asked to
         * change orientation, so there is no failure to report.
         */
        void setPortrait(boolean enabled);

        void setDashboardUrl(String url);

        void publishTelemetry();

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
            case "display.portrait":
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                executor.setPortrait(args.enabled);
                return accepted();
            case "telemetry.publish":
                executor.publishTelemetry();
                return accepted();
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
