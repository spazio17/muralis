/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.List;

public final class KioskCommandDispatcherTest {
    public static void main(String[] args) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();

        require(KioskCommandDispatcher.dispatch(
                "kiosk.reload", KioskCommandDispatcher.CommandArgs.EMPTY, executor)
                .status.equals("accepted"), "kiosk.reload should be accepted");
        require(executor.calls.contains("kioskReload"), "kiosk.reload did not reach executor");

        testPowerCommandsTellTheTruth();
        testWithdrawnRecycleCommands();
        testBrightnessRefusalIsReported();
        testDeviceIdValidation();
        testTelemetryPublishTellsTheTruth();
        testEnabledFlagParsing();

        KioskCommandDispatcher.Result badBrightness = KioskCommandDispatcher.dispatch(
                "display.brightness", new KioskCommandDispatcher.CommandArgs(150, null), executor);
        require(badBrightness.status.equals("rejected"), "out-of-range brightness accepted");
        require(!executor.calls.contains("setBrightness"), "executor ran despite rejection");

        KioskCommandDispatcher.Result okBrightness = KioskCommandDispatcher.dispatch(
                "display.brightness", new KioskCommandDispatcher.CommandArgs(40, null), executor);
        require(okBrightness.status.equals("accepted"), "in-range brightness rejected");
        require(executor.lastBrightness == 40, "brightness value not forwarded");

        // display.auto_brightness: the flag is required, forwarded, and a device without a light
        // sensor must be reported as such rather than silently accepted.
        KioskCommandDispatcher.Result noFlag = KioskCommandDispatcher.dispatch(
                "display.auto_brightness", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(noFlag.status.equals("rejected"), "auto_brightness without a flag was accepted");
        require(!executor.calls.contains("setAutoBrightness"),
                "executor ran despite a missing flag");

        KioskCommandDispatcher.Result autoOn = KioskCommandDispatcher.dispatch(
                "display.auto_brightness",
                new KioskCommandDispatcher.CommandArgs(-1, null, Boolean.TRUE), executor);
        require(autoOn.status.equals("accepted"), "auto_brightness on was rejected");
        require(Boolean.TRUE.equals(executor.lastAutoBrightness), "flag not forwarded");

        executor.lightSensorPresent = false;
        KioskCommandDispatcher.Result noSensor = KioskCommandDispatcher.dispatch(
                "display.auto_brightness",
                new KioskCommandDispatcher.CommandArgs(-1, null, Boolean.FALSE), executor);
        require(noSensor.status.equals("rejected"),
                "a device with no light sensor must not report success");
        require(noSensor.detail.contains("light sensor"), "rejection should say why: "
                + noSensor.detail);
        executor.lightSensorPresent = true;

        KioskCommandDispatcher.Result badUrl = KioskCommandDispatcher.dispatch(
                "kiosk.set_url", new KioskCommandDispatcher.CommandArgs(-1, "ftp://example.com"),
                executor);
        require(badUrl.status.equals("rejected"), "non-http(s) url accepted");

        KioskCommandDispatcher.Result emptyUrl = KioskCommandDispatcher.dispatch(
                "kiosk.set_url", new KioskCommandDispatcher.CommandArgs(-1, "   "), executor);
        require(emptyUrl.status.equals("rejected"), "blank url accepted");

        KioskCommandDispatcher.Result okUrl = KioskCommandDispatcher.dispatch(
                "kiosk.set_url",
                new KioskCommandDispatcher.CommandArgs(-1, "https://homeassistant.local:8123/"),
                executor);
        require(okUrl.status.equals("accepted"), "valid https url rejected");
        require("https://homeassistant.local:8123/".equals(executor.lastUrl),
                "url value not forwarded");

        KioskCommandDispatcher.Result paddedUrl = KioskCommandDispatcher.dispatch(
                "kiosk.set_url",
                new KioskCommandDispatcher.CommandArgs(
                        -1, "  https://homeassistant.local:8123/dashboard  "),
                executor);
        require(paddedUrl.status.equals("accepted"), "padded valid URL rejected");
        require("https://homeassistant.local:8123/dashboard".equals(executor.lastUrl),
                "validated URL was not normalized before persistence");

        KioskCommandDispatcher.Result unknown = KioskCommandDispatcher.dispatch(
                "kiosk.self_destruct", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(unknown.status.equals("unsupported"), "unknown command should be unsupported");

        System.out.println("KioskCommandDispatcherTest passed");
    }

    /**
     * A power command that cannot work must report "unsupported", never "accepted".
     *
     * <p>This guards a bug that was live on hardware on 2026-08-19: {@code system.shutdown} returned
     * {@code {"status":"accepted","detail":"shutting down"}} from a build with no way to power the
     * device off, and {@code system.reboot} did the same without device-owner status. A Home
     * Assistant automation would have believed it switched the panel off and stopped watching it.
     * Silent success is the worst possible answer here, so it is pinned by a test.
     */
    private static void testPowerCommandsTellTheTruth() {
        RecordingExecutor executor = new RecordingExecutor();

        // system.shutdown is removed from the command set entirely, so it must now look like any
        // other unknown command. Pinned as a test so nobody reintroduces it as an accepted no-op.
        KioskCommandDispatcher.Result shutdown = KioskCommandDispatcher.dispatch(
                "system.shutdown", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(shutdown.status.equals("unsupported"),
                "system.shutdown must be unsupported, got: " + shutdown.status);
        require(shutdown.detail.equals("unknown command"),
                "system.shutdown should fall through to the unknown-command path");

        // Reboot works only as device owner.
        executor.rebootSupported = false;
        KioskCommandDispatcher.Result noOwner = KioskCommandDispatcher.dispatch(
                "system.reboot", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(noOwner.status.equals("unsupported"),
                "reboot without device owner must be unsupported, got: " + noOwner.status);

        executor.rebootSupported = true;
        KioskCommandDispatcher.Result owner = KioskCommandDispatcher.dispatch(
                "system.reboot", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(owner.status.equals("accepted"),
                "reboot as device owner must be accepted, got: " + owner.status);
        require(owner.detail.equals("rebooting"), "reboot detail should say rebooting");
    }

    /**
     * telemetry.publish must answer for what actually happened. It used to return accepted
     * unconditionally while the payload was silently dropped on a missing or disconnected broker,
     * the accepted no-op shape system.shutdown was deleted to avoid.
     */
    private static void testTelemetryPublishTellsTheTruth() {
        RecordingExecutor executor = new RecordingExecutor();

        KioskCommandDispatcher.Result published = KioskCommandDispatcher.dispatch(
                "telemetry.publish", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(published.status.equals("accepted"), "a handed-over publish was not accepted");

        executor.telemetryProblem = "MQTT is not connected";
        KioskCommandDispatcher.Result dropped = KioskCommandDispatcher.dispatch(
                "telemetry.publish", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(dropped.status.equals("rejected"),
                "a publish that went nowhere must not be accepted");
        require(dropped.detail.equals("MQTT is not connected"),
                "the rejection should carry the executor's reason: " + dropped.detail);
    }

    /**
     * One parser for the enabled flag on every transport. The JSON paths used optBoolean, which
     * coerces the number 1 to false, so {"enabled":1} and ?enabled=1 flipped auto-brightness in
     * opposite directions, both "accepted". Unparseable values are null, which callers reject.
     */
    private static void testEnabledFlagParsing() {
        require(Boolean.TRUE.equals(KioskCommandDispatcher.parseEnabledFlag(Boolean.TRUE)),
                "a JSON true must parse as true");
        require(Boolean.FALSE.equals(KioskCommandDispatcher.parseEnabledFlag(Boolean.FALSE)),
                "a JSON false must parse as false");
        require(Boolean.TRUE.equals(KioskCommandDispatcher.parseEnabledFlag(Integer.valueOf(1))),
                "the number 1 must mean true on every transport");
        require(Boolean.FALSE.equals(KioskCommandDispatcher.parseEnabledFlag(Integer.valueOf(0))),
                "the number 0 must mean false on every transport");
        require(Boolean.TRUE.equals(KioskCommandDispatcher.parseEnabledFlag("1")),
                "the query spelling ?enabled=1 must keep meaning true");
        require(Boolean.TRUE.equals(KioskCommandDispatcher.parseEnabledFlag("on")),
                "browser form checkboxes send on");
        require(Boolean.FALSE.equals(KioskCommandDispatcher.parseEnabledFlag("off")),
                "off must mean false, not fall to the reject path");
        require(Boolean.FALSE.equals(KioskCommandDispatcher.parseEnabledFlag("FALSE")),
                "spellings are case-insensitive");

        require(KioskCommandDispatcher.parseEnabledFlag(Integer.valueOf(2)) == null,
                "a number that is neither 0 nor 1 must be unparseable, never guessed");
        require(KioskCommandDispatcher.parseEnabledFlag("yes") == null,
                "an unknown spelling must be unparseable, not silently false");
        require(KioskCommandDispatcher.parseEnabledFlag(null) == null,
                "null is unparseable");
        require(KioskCommandDispatcher.parseEnabledFlag(new Object()) == null,
                "an arbitrary object is unparseable");
    }

    /**
     * The device id must be refused at the surface, with a reason, for anything MQTT cannot carry.
     *
     * <p>Guards the audited failure mode: an id with a space or a topic metacharacter was stored
     * by both settings surfaces, answered with success, and MQTT then refused to start with one
     * logcat line nobody reads. An empty id is refused separately because KioskConfig.load treats
     * it as "never provisioned" and re-mints the panel's identity, orphaning every Home Assistant
     * entity.
     */
    private static void testDeviceIdValidation() {
        require(KioskCommandDispatcher.validateDeviceId("kiosk-a1b2c3d4") == null,
                "a provisioning-shaped id must be accepted");
        require(KioskCommandDispatcher.validateDeviceId("wall_panel.2") == null,
                "dot and underscore are MQTT-legal and must be accepted");
        require(KioskCommandDispatcher.validateDeviceId("  padded-id  ") == null,
                "surrounding whitespace is trimmed by every caller and must not refuse the id");

        require(KioskCommandDispatcher.validateDeviceId(null) != null,
                "a null id must be refused");
        require(KioskCommandDispatcher.validateDeviceId("   ") != null,
                "a blank id must be refused, or the panel re-mints its identity");
        require(KioskCommandDispatcher.validateDeviceId("wall panel") != null,
                "a space must be refused");
        require(KioskCommandDispatcher.validateDeviceId("wall/panel") != null,
                "a topic separator must be refused");
        require(KioskCommandDispatcher.validateDeviceId("wall+panel") != null,
                "an MQTT single-level wildcard must be refused");
        require(KioskCommandDispatcher.validateDeviceId("wall#") != null,
                "an MQTT multi-level wildcard must be refused");
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            tooLong.append('a');
        }
        require(KioskCommandDispatcher.validateDeviceId(tooLong.toString()) != null,
                "a 65-character id must be refused");
        require(KioskCommandDispatcher.validateDeviceId("wall panel").contains("letters"),
                "the refusal must name the allowed characters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * The recycle commands are gone, and this pins that they answer "unsupported" rather than
     * quietly succeeding.
     *
     * <p>Worth a test rather than a deletion. Both were announced over MQTT discovery, so a Home
     * Assistant somewhere may still hold an automation that publishes them, and an automation whose
     * command is silently accepted while nothing happens is the exact failure this project deleted
     * system.shutdown to avoid. "unsupported" is the honest answer and it is what a caller needs to
     * see. See RecyclePolicy for why the controls went.
     */
    private static void testWithdrawnRecycleCommands() {
        RecordingExecutor executor = new RecordingExecutor();

        for (String command : new String[] {"kiosk.auto_recycle", "kiosk.recycle_time"}) {
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(
                    command, KioskCommandDispatcher.CommandArgs.EMPTY, executor);
            require(result.status.equals("unsupported"),
                    command + " should be unsupported, got " + result.status);
        }

        // Also with plausible arguments, so nothing routes on the strength of a populated field.
        require(KioskCommandDispatcher.dispatch("kiosk.auto_recycle",
                new KioskCommandDispatcher.CommandArgs(-1, null, Boolean.FALSE), executor)
                .status.equals("unsupported"), "auto_recycle with a flag was handled");
        require(executor.calls.isEmpty(), "a withdrawn command reached the executor: "
                + executor.calls);
    }

    /**
     * A brightness the panel will not apply must be reported, not accepted.
     *
     * <p>Measured on hardware 2026-08-20: with automatic brightness on, writing the system setting
     * moves the stored number and never reaches the backlight, so the command used to be accepted,
     * nothing happened, and the stats then reported a level the panel was not showing. That is the
     * same shape as the deleted {@code system.shutdown}.
     */
    private static void testBrightnessRefusalIsReported() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.brightnessProblem = "automatic brightness is on; turn it off to set a level";

        KioskCommandDispatcher.Result refused = KioskCommandDispatcher.dispatch(
                "display.brightness", new KioskCommandDispatcher.CommandArgs(60, null), executor);
        require(refused.status.equals("rejected"), "an unapplied brightness was accepted");
        require(refused.detail.equals(executor.brightnessProblem),
                "the reason the panel gave was not passed on");

        executor.brightnessProblem = null;
        KioskCommandDispatcher.Result applied = KioskCommandDispatcher.dispatch(
                "display.brightness", new KioskCommandDispatcher.CommandArgs(60, null), executor);
        require(applied.status.equals("accepted"), "an applied brightness was not accepted");
    }

    private static final class RecordingExecutor implements KioskCommandDispatcher.Executor {
        final List<String> calls = new ArrayList<>();
        int lastBrightness = -1;
        String lastUrl;
        Boolean lastAutoBrightness;
        boolean lightSensorPresent = true;

        @Override
        public void kioskStart() {
            calls.add("kioskStart");
        }

        @Override
        public void kioskStop() {
            calls.add("kioskStop");
        }

        @Override
        public void kioskReload() {
            calls.add("kioskReload");
        }

        @Override
        public void kioskRestart() {
            calls.add("kioskRestart");
        }

        @Override
        public void displayWake() {
            calls.add("displayWake");
        }

        @Override
        public void displayVisualOff() {
            calls.add("displayVisualOff");
        }

        /** Non-null stands in for a device that will not apply a level right now. */
        String brightnessProblem;

        @Override
        public String setBrightness(int percent) {
            calls.add("setBrightness");
            lastBrightness = percent;
            return brightnessProblem;
        }

        @Override
        public boolean setAutoBrightness(boolean enabled) {
            calls.add("setAutoBrightness");
            lastAutoBrightness = enabled;
            return lightSensorPresent;
        }

        @Override
        public void setPortrait(boolean enabled) {
            calls.add("setPortrait:" + enabled);
        }

        @Override
        public void setDashboardUrl(String url) {
            calls.add("setDashboardUrl");
            lastUrl = url;
        }


        /** Null means "handed to a live session"; a message stands in for a down or absent broker. */
        String telemetryProblem = null;

        @Override
        public String publishTelemetry() {
            calls.add("publishTelemetry");
            return telemetryProblem;
        }

        /** Whether this fake device can reboot, i.e. whether it stands in for a device owner. */
        boolean rebootSupported = true;

        @Override
        public boolean reboot() {
            calls.add("reboot");
            return rebootSupported;
        }

    }
}
