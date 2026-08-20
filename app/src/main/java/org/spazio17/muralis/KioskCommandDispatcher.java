/*
 * Copyright 2026 Muralis contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.spazio17.muralis;

/**
 * Command handling shared by every transport (MQTT, HTTP). Transports parse their own
 * envelope/auth, build a {@link CommandArgs}, and get back a transport-agnostic {@link Result}.
 */
final class KioskCommandDispatcher {
    private static final int MAX_URL_LENGTH = 2_048;

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
        /** Clock time as text, "HH:MM" or "HH:MM:SS". Null when the caller did not say. */
        final String time;

        CommandArgs(int brightnessPercent, String url) {
            this(brightnessPercent, url, null, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled) {
            this(brightnessPercent, url, enabled, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled, String time) {
            this.brightnessPercent = brightnessPercent;
            this.url = url;
            this.enabled = enabled;
            this.time = time;
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

        void setDashboardUrl(String url);

        /** Turns the nightly and memory-pressure dashboard recycle on or off. */
        void setAutoRecycle(boolean enabled);

        /** Moves the nightly recycle. Both values are already validated. */
        void setRecycleTime(int hour, int minute);

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
            case "kiosk.auto_recycle":
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                executor.setAutoRecycle(args.enabled);
                return accepted();
            case "kiosk.recycle_time": {
                int[] parsed = parseClockTime(args.time);
                if (parsed == null) {
                    return rejected("time must be HH:MM or HH:MM:SS, 00:00 to 23:59");
                }
                executor.setRecycleTime(parsed[0], parsed[1]);
                return accepted();
            }
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

    /**
     * Parses "HH:MM" or "HH:MM:SS" into {hour, minute}, or null when it is not a real time.
     *
     * <p>Rejects rather than clamps, unlike {@link RecyclePolicy#clampHour}. Clamping is right where
     * a stored value has to yield something usable whatever is in it; a command is somebody asking
     * for a specific thing, and silently recycling at 04:00 because they asked for 25:00 is the kind
     * of quiet substitution this project keeps deleting.
     *
     * <p>Seconds are accepted and discarded: Home Assistant's MQTT time platform sends ISO
     * "HH:MM:SS", and the panel schedules to the minute.
     */
    static int[] parseClockTime(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2 && parts.length != 3) {
            return null;
        }
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
            if (parts.length == 3) {
                Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }
        return new int[] {hour, minute};
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

    private static Result accepted() {
        return new Result("accepted", "");
    }

    private static Result rejected(String detail) {
        return new Result("rejected", detail);
    }
}
