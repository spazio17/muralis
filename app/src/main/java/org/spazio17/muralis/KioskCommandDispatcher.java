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

    /**
     * The screensaver's settings as commands, so every one of them can be set over MQTT and HTTP
     * and not only in the two user interfaces (Juri, 2026-09-09). One executor method rather
     * than a dozen: the validation is here, where the repo keeps it, and the executor's job is
     * one {@code KioskConfig.edit} call.
     */
    enum ScreensaverSetting {
        MODE, IDLE_SECONDS, OFF_SECONDS, PICTURE_SECONDS, DIM_PERCENT, URL, TRANSITION,
        PICTURE_FIT, CREDIT_CORNER, ON_WAKE, SHUFFLE, ONE_PER_CYCLE, CREDIT
    }

    /** The page's browser options a command sets, checked here against BrowserOptions. */
    enum BrowserOption {
        USER_AGENT, TEXT_SIZE, PAGE_ZOOM, DARK, BACK_AFTER, PAGE_CAMERA, PAGE_MICROPHONE
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
        /** A beacon's uuid:major:minor, for beacons.calibrate; null when not given. */
        final String beacon;

        CommandArgs(int brightnessPercent, String url) {
            this(brightnessPercent, url, null, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled) {
            this(brightnessPercent, url, enabled, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled, String value) {
            this(brightnessPercent, url, enabled, value, null);
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled, String value,
                String beacon) {
            this(brightnessPercent, url, enabled, value, beacon,
                    java.util.Collections.<String, String>emptyMap());
        }

        CommandArgs(int brightnessPercent, String url, Boolean enabled, String value,
                String beacon, java.util.Map<String, String> extra) {
            this.brightnessPercent = brightnessPercent;
            this.url = url;
            this.enabled = enabled;
            this.value = value;
            this.beacon = beacon;
            this.extra = extra;
        }

        /**
         * Every argument by name as text, for the commands with more than the fields above
         * (panel.notify's title, seconds, wake, chime); empty when the caller sent none.
         */
        final java.util.Map<String, String> extra;
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

        /**
         * Stores how {@code display.visual_off} darkens the panel; see {@code DisplayOffPolicy}.
         *
         * @return false when {@code sleep} is asked of an install that is not the device owner,
         *         before anything is stored, the same shape as {@link #setOrientation}
         */
        boolean setDisplayOffMethod(String value);

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
         * Stores the status light, already checked against {@link StatusLight}: on or off, its
         * colour (null keeps the stored one) and its brightness (-1 keeps the stored one), and
         * has the frame drawn or taken away.
         */
        void setStatusLight(boolean on, Integer color, int percent);

        /** Draws a pop-up over the screen (block 6); the display was woken first if asked. */
        void showPopup(Popup.Request request);

        /**
         * A camera's pop-up (block 6): shown on demand, or for a doorbell's trigger by the
         * camera's mode and cooldown. Returns why it does not show, or null.
         */
        String cameraPopup(String name, boolean trigger);

        /**
         * Stores one browser option, already checked and canonical (a number as digits, the
         * dark look as true or false), and has the page take it. Returns why it cannot, or
         * null: the dark look needs Android 10, where the WebView reads it from its theme, and
         * the page's camera and microphone need Android's permission first.
         */
        String setBrowserOption(BrowserOption option, String value);

        /**
         * Shows the screensaver now, whatever the idle time. Returns why it cannot, or null: the
         * mode is off or has no address, the kiosk is stopped, Muralis is not on screen, the
         * display is off, or the wizard or the escape recorder is up; the shape of
         * {@link #setBrightness}. With the settings open it shows as a preview.
         */
        String screensaverStart();

        /** Back to the page, and the idle time starts again. */
        void screensaverStop();

        /** Makes the named playlist the one in use, or none when the name is empty. */
        String setScreensaverPlaylist(String name);

        /**
         * Stores one screensaver setting. The value arrives checked and canonical: this switch
         * validates every one of them against {@link ScreensaverPolicy} and
         * {@link PictureSources} before calling, so the executor only writes.
         */
        void setScreensaverSetting(ScreensaverSetting setting, String value);

        /**
         * Publishes the telemetry document now.
         *
         * @return null when the publish was handed to a live MQTT session, otherwise a short
         *         reason ("MQTT is not configured", "MQTT is not connected") for the caller's
         *         rejection. Void was the audited shape: the dispatcher answered accepted while
         *         the payload was silently dropped on a missing or disconnected client.
         */
        String publishTelemetry();

        /** Switches one sensor on or off; null when done, else the refusal. See Sensors. */
        String setSensorEnabled(String id, boolean enabled);

        /** Sets the media volume, 0 to 100. */
        void setMediaVolume(int percent);

        /** Plays a sound from a URL, or stops what is playing for null. Null when started. */
        String playAudio(String url);

        /** Speaks a sentence through the device's text-to-speech. Null when accepted. */
        String say(String text);

        /** Switches one automation on or off by its id; null when done, else the refusal. */
        String setAutomationEnabled(String id, boolean enabled);

        /** Switches the camera's motion detection; null when done, else the refusal. */
        String setCameraMotion(boolean enabled);

        /** Publishes the camera's last picture over MQTT now; null when done, else why not. */
        String cameraSnapshot();

        /**
         * Captures the panel's screen as the wall shows it and, when MQTT runs, publishes the
         * picture retained; null when done, else why not: the panel is not showing its screen.
         */
        String screenshot();

        /**
         * Shows this panel's name over whatever is on its screen for a few seconds and says it,
         * so a person can tell which wall is which; null when done, else why not: the panel is
         * not showing its screen, or the name could not be said.
         */
        String identify();

        /** One step of the proximity calibration, covered, clear or reset; see Sensors. */
        String calibrateProximity(String step);

        /** One step of the microphone calibration, quiet, loud or reset; see Microphone. */
        String calibrateMicrophone(String step);

        /**
         * One step of a beacon's calibration, near (one metre), far (three) or reset, for the
         * beacon named or the strongest in reach; see Beacons.
         */
        String calibrateBeacons(String step, String beacon);

        /** Sleeps the panel for a moment and notes whether the sensor reported; see Sensors. */
        String sleepTest(String sensorId);

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
            case "display.off_method":
                if (!DisplayOffPolicy.isMethod(args.value)) {
                    return rejected("value must be auto, sleep or film");
                }
                if (!executor.setDisplayOffMethod(args.value)) {
                    return rejected("a real screen-off needs the device-owner install");
                }
                return accepted();
            case "light.on": {
                // The Home Assistant light's three values in one call: the colour in value as
                // r,g,b and the brightness in percent, each optional and each kept when absent.
                Integer color = null;
                if (args.value != null && !args.value.trim().isEmpty()) {
                    color = StatusLight.parseColor(args.value);
                    if (color == null) {
                        return rejected(StatusLight.COLOR_RULE);
                    }
                }
                if (args.brightnessPercent != -1 && !StatusLight.isPercent(args.brightnessPercent)) {
                    return rejected(StatusLight.PERCENT_RULE);
                }
                executor.setStatusLight(true, color, args.brightnessPercent);
                return accepted();
            }
            case "light.off":
                executor.setStatusLight(false, null, -1);
                return accepted();
            case "camera.show":
            case "camera.trigger": {
                if (args.value == null || args.value.trim().isEmpty()) {
                    return rejected("value must name the camera");
                }
                String problem = executor.cameraPopup(args.value.trim(),
                        "camera.trigger".equals(command));
                return problem == null ? accepted() : rejected(problem);
            }
            case "panel.notify": {
                java.util.Map<String, String> named = new java.util.HashMap<>(args.extra);
                if (args.url != null) {
                    named.put("url", args.url);
                }
                String[] problem = new String[1];
                Popup.Request request = Popup.parse(args.value, named, problem);
                if (request == null) {
                    return rejected(problem[0]);
                }
                if (request.wake) {
                    executor.displayWake();
                }
                executor.showPopup(request);
                return accepted();
            }
            case "browser.user_agent": {
                String agent = args.value == null ? "" : args.value;
                String problem = BrowserOptions.checkUserAgent(agent);
                if (problem != null) {
                    return rejected(problem);
                }
                executor.setBrowserOption(BrowserOption.USER_AGENT, agent.trim());
                return accepted();
            }
            case "browser.text_size": {
                Integer percent = BrowserOptions.parseTextSize(args.value);
                if (percent == null) {
                    return rejected(BrowserOptions.TEXT_SIZE_RULE);
                }
                executor.setBrowserOption(BrowserOption.TEXT_SIZE, String.valueOf(percent));
                return accepted();
            }
            case "browser.page_zoom": {
                Integer percent = BrowserOptions.parsePageZoom(args.value);
                if (percent == null) {
                    return rejected(BrowserOptions.ZOOM_RULE);
                }
                executor.setBrowserOption(BrowserOption.PAGE_ZOOM, String.valueOf(percent));
                return accepted();
            }
            case "browser.back_after": {
                Integer seconds = BrowserOptions.parseBackAfter(args.value);
                if (seconds == null) {
                    return rejected(BrowserOptions.BACK_AFTER_RULE);
                }
                executor.setBrowserOption(BrowserOption.BACK_AFTER, String.valueOf(seconds));
                return accepted();
            }
            case "browser.page_camera":
            case "browser.page_microphone": {
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                String problem = executor.setBrowserOption("browser.page_camera".equals(command)
                        ? BrowserOption.PAGE_CAMERA : BrowserOption.PAGE_MICROPHONE,
                        String.valueOf(args.enabled));
                return problem == null ? accepted() : rejected(problem);
            }
            case "browser.dark_look":
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                String darkProblem = executor.setBrowserOption(BrowserOption.DARK,
                        String.valueOf(args.enabled));
                return darkProblem == null ? accepted() : rejected(darkProblem);
            case "webadmin.enabled":
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                executor.setWebAdminEnabled(args.enabled);
                return accepted();
            case "ambient.start": {
                String problem = executor.screensaverStart();
                if (problem != null) {
                    return rejected(problem);
                }
                return accepted();
            }
            case "ambient.stop":
                executor.screensaverStop();
                return accepted();
            case "ambient.mode":
                if (!ScreensaverPolicy.isMode(args.value)) {
                    return rejected("value must be off, dim, film, url or pictures");
                }
                executor.setScreensaverSetting(ScreensaverSetting.MODE, args.value);
                return accepted();
            case "ambient.playlist":
                // By name, not by id: a name is what a person and a Home Assistant card know, and
                // an id is an implementation detail nobody types. An empty value means "none",
                // which is a real state since a playlist can be deleted while it is in use.
                if (args.value == null) {
                    return rejected("value must be a playlist name, or empty for none");
                }
                String refusal = executor.setScreensaverPlaylist(args.value);
                if (refusal != null) {
                    return rejected(refusal);
                }
                return accepted();
            case "ambient.idle_seconds":
            case "ambient.off_seconds": {
                Integer seconds = ScreensaverPolicy.parseSeconds(args.value);
                if (seconds == null) {
                    return rejected("value " + ScreensaverPolicy.SECONDS_RULE);
                }
                executor.setScreensaverSetting(command.endsWith("idle_seconds")
                        ? ScreensaverSetting.IDLE_SECONDS : ScreensaverSetting.OFF_SECONDS,
                        String.valueOf(seconds));
                return accepted();
            }
            case "ambient.picture_seconds": {
                Integer seconds = ScreensaverPolicy.parsePictureSeconds(args.value);
                if (seconds == null) {
                    return rejected("value " + ScreensaverPolicy.PICTURE_SECONDS_RULE);
                }
                executor.setScreensaverSetting(ScreensaverSetting.PICTURE_SECONDS,
                        String.valueOf(seconds));
                return accepted();
            }
            case "ambient.dim_percent": {
                Integer percent = ScreensaverPolicy.parseDimPercent(args.value);
                if (percent == null) {
                    return rejected("value " + ScreensaverPolicy.DIM_RULE);
                }
                executor.setScreensaverSetting(ScreensaverSetting.DIM_PERCENT,
                        String.valueOf(percent));
                return accepted();
            }
            case "ambient.url": {
                // The one-off page's own rules, and an empty value is how the address is cleared,
                // which leaves the mode stored and refused at start time with the reason.
                String url = args.url != null ? args.url : args.value;
                if (url != null && url.trim().isEmpty()) {
                    executor.setScreensaverSetting(ScreensaverSetting.URL, "");
                    return accepted();
                }
                String error = validateDashboardUrl(url);
                if (error != null) {
                    return rejected(error);
                }
                executor.setScreensaverSetting(ScreensaverSetting.URL, url.trim());
                return accepted();
            }
            case "ambient.on_wake":
                if (!ScreensaverPolicy.isOnWake(args.value)) {
                    return rejected("value must be ambient or dashboard");
                }
                executor.setScreensaverSetting(ScreensaverSetting.ON_WAKE, args.value);
                return accepted();
            case "ambient.transition":
                if (!ScreensaverPolicy.isTransition(args.value)) {
                    return rejected("value must be none, fade or slide");
                }
                executor.setScreensaverSetting(ScreensaverSetting.TRANSITION, args.value);
                return accepted();
            case "ambient.picture_fit":
                if (!ScreensaverPolicy.isFit(args.value)) {
                    return rejected("value must be fit, fill, stretch or actual");
                }
                executor.setScreensaverSetting(ScreensaverSetting.PICTURE_FIT, args.value);
                return accepted();
            case "ambient.credit_corner":
                if (!ScreensaverPolicy.isCorner(args.value)) {
                    return rejected(
                            "value must be bottom_left, bottom_right, top_left or top_right");
                }
                executor.setScreensaverSetting(ScreensaverSetting.CREDIT_CORNER, args.value);
                return accepted();
            case "ambient.shuffle":
            case "ambient.one_per_cycle":
            case "ambient.credit": {
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                ScreensaverSetting which = command.endsWith("shuffle") ? ScreensaverSetting.SHUFFLE
                        : command.endsWith("credit") ? ScreensaverSetting.CREDIT
                                : ScreensaverSetting.ONE_PER_CYCLE;
                executor.setScreensaverSetting(which, args.enabled ? "true" : "false");
                return accepted();
            }
            case "sensor.enabled": {
                // value names the sensor, enabled says which way: the shape every other
                // on/off command has, with the sensor id where the others need nothing.
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                if (args.value == null || args.value.isEmpty()) {
                    return rejected("value must name a sensor");
                }
                String problem = executor.setSensorEnabled(args.value, args.enabled);
                return problem == null ? accepted() : rejected(problem);
            }
            case "automation.enabled": {
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                if (args.value == null || args.value.isEmpty()) {
                    return rejected("value must name an automation");
                }
                String problem = executor.setAutomationEnabled(args.value, args.enabled);
                return problem == null ? accepted() : rejected(problem);
            }
            case "camera.motion": {
                if (args.enabled == null) {
                    return rejected("enabled must be true or false");
                }
                String problem = executor.setCameraMotion(args.enabled);
                return problem == null ? accepted() : rejected(problem);
            }
            case "camera.snapshot": {
                String problem = executor.cameraSnapshot();
                return problem == null ? accepted() : rejected(problem);
            }
            case "kiosk.screenshot": {
                String problem = executor.screenshot();
                return problem == null ? accepted() : rejected(problem);
            }
            case "kiosk.identify": {
                String problem = executor.identify();
                return problem == null ? accepted() : rejected(problem);
            }
            case "proximity.calibrate": {
                String problem = executor.calibrateProximity(args.value);
                return problem == null ? accepted() : rejected(problem);
            }
            case "microphone.calibrate": {
                String problem = executor.calibrateMicrophone(args.value);
                return problem == null ? accepted() : rejected(problem);
            }
            case "beacons.calibrate": {
                String problem = executor.calibrateBeacons(args.value, args.beacon);
                return problem == null ? accepted() : rejected(problem);
            }
            case "sensor.sleep_test": {
                String problem = executor.sleepTest(args.value);
                return problem == null ? accepted() : rejected(problem);
            }
            case "audio.volume": {
                // The media stream, 0 to 100, what Fully's setAudioVolume does for stream 3.
                if (args.brightnessPercent < 0 || args.brightnessPercent > 100) {
                    return rejected("percent must be between 0 and 100");
                }
                executor.setMediaVolume(args.brightnessPercent);
                return accepted();
            }
            case "audio.play": {
                if (args.url == null || args.url.isEmpty()) {
                    return rejected("url is required");
                }
                String problem = executor.playAudio(args.url);
                return problem == null ? accepted() : rejected(problem);
            }
            case "audio.stop": {
                executor.playAudio(null);
                return accepted();
            }
            case "audio.say": {
                if (args.value == null || args.value.trim().isEmpty()) {
                    return rejected("value must hold the sentence to say");
                }
                String problem = executor.say(args.value.trim());
                return problem == null ? accepted() : rejected(problem);
            }
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


    /**
     * The example both settings surfaces show for the dashboard URL, and they show it as a hint,
     * never as a value.
     *
     * <p>It was {@code http://homeassistant.local:8123/}, prefilled into the tablet's box as real
     * text, and on a fresh panel the first press of the save button stored it and loaded it,
     * landing on an error page for a host that does not exist (seen 2026-09-07). Two things
     * were wrong with that, and this fixes both. A value nobody typed must never be one press
     * from being stored: the form-design literature is unanimous that example text belongs in
     * the hint, alongside a visible label, where it shows the shape of a valid entry and cannot
     * be mistaken for data or submitted by accident. And Muralis is not a Home Assistant app: it
     * runs any dashboard, so the example must not name one product. {@code example.com} is
     * reserved for exactly this use (RFC 2606), resolves to nothing anyone runs, and reads as
     * "put yours here" to everyone.
     */
    static final String EXAMPLE_DASHBOARD_URL = "https://example.com/dashboard";

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
     * <p>The floor is 1024 rather than 1 because the difference was proved on hardware
     * (2026-08-24): port 80 passed a 1-65535 range check, persisted, and then failed at bind
     * time, since an unprivileged app can never bind below 1024 on Android. The server fails
     * closed on a bind error, so the admin's own settings box was able to switch the admin off,
     * undoable only from the tablet. This floor applies exclusively to the port this app binds
     * itself, the web admin's and the member endpoint's alike; the broker port is a remote port
     * and keeps the full 1-65535 range. The two own ports are never the same (Juri, 2026-10-08):
     * a new choice equal to the other is refused, while a pair that already collides (a web
     * admin on 8081 before the member port existed) can still be saved unchanged, its member
     * endpoint staying off until one of them moves.
     *
     * @param currentPort the web admin's port stored now, or the one its server holds
     * @param memberPort  the member port, stored or being saved with it
     */
    static String validateAdminPort(int port, int currentPort, int memberPort) {
        return ownPortProblem(port, currentPort, memberPort, "web admin port", "member port");
    }

    /** The member endpoint's port, by the rule of {@link #validateAdminPort}. */
    static String validateMemberPort(int port, int currentPort, int adminPort) {
        return ownPortProblem(port, currentPort, adminPort, "member port", "web admin port");
    }

    private static String ownPortProblem(int port, int current, int other, String name,
            String otherName) {
        if (port < 1024 || port > 65535) {
            return name + " must be between 1024 and 65535";
        }
        if (port == other && port != current) {
            return "port " + port + " is the " + otherName + "; choose another " + name;
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

    /** The two certificates the web admin can serve; see AdminCertificate. */
    static final String CERTIFICATE_OWN = "own";
    static final String CERTIFICATE_UPLOADED = "uploaded";

    /**
     * Returns null when {@code value} names a certificate the web admin can serve now, an error
     * message otherwise: the panel's own always, the uploaded one only while one is kept
     * ({@code uploadKept}, the one fact this needs from storage).
     */
    static String validateCertificateChoice(String value, boolean uploadKept) {
        if (!CERTIFICATE_OWN.equals(value) && !CERTIFICATE_UPLOADED.equals(value)) {
            return "the certificate must be own or uploaded";
        }
        if (CERTIFICATE_UPLOADED.equals(value) && !uploadKept) {
            return "no certificate is uploaded";
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
