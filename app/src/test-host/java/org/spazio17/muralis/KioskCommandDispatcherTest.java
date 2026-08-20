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
        testRecycleCommands();
        testBrightnessRefusalIsReported();

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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * The nightly recycle, driven remotely. The time is the interesting half: it is **rejected**
     * rather than clamped, unlike the stored value, because a command is somebody asking for a
     * specific thing and quietly recycling at 04:00 when they asked for 25:00 is the sort of silent
     * substitution this project keeps deleting.
     */
    private static void testRecycleCommands() {
        RecordingExecutor executor = new RecordingExecutor();

        require(KioskCommandDispatcher.dispatch("kiosk.auto_recycle",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                "auto_recycle without a flag was accepted");
        require(!executor.calls.contains("setAutoRecycle"), "executor ran despite a missing flag");

        require(KioskCommandDispatcher.dispatch("kiosk.auto_recycle",
                new KioskCommandDispatcher.CommandArgs(-1, null, false), executor)
                .status.equals("accepted"), "auto_recycle off was not accepted");
        require(Boolean.FALSE.equals(executor.lastAutoRecycle), "auto_recycle flag not forwarded");

        // Home Assistant's MQTT time platform sends ISO HH:MM:SS; a person or a shell sends HH:MM.
        require(KioskCommandDispatcher.dispatch("kiosk.recycle_time",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "23:30:00"), executor)
                .status.equals("accepted"), "HH:MM:SS was rejected");
        require(executor.lastRecycleHour == 23 && executor.lastRecycleMinute == 30,
                "recycle time not forwarded");

        require(KioskCommandDispatcher.dispatch("kiosk.recycle_time",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "04:05"), executor)
                .status.equals("accepted"), "HH:MM was rejected");
        require(executor.lastRecycleHour == 4 && executor.lastRecycleMinute == 5,
                "short recycle time not forwarded");

        // Every one of these must be refused, not quietly turned into something valid.
        String[] rubbish = {null, "", "25:00", "12:60", "-1:00", "midnight", "12", "12:30:00:00"};
        for (String value : rubbish) {
            executor.calls.clear();
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(
                    "kiosk.recycle_time",
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, value), executor);
            require(result.status.equals("rejected"), "recycle_time accepted \"" + value + "\"");
            require(!executor.calls.contains("setRecycleTime"),
                    "executor ran for rejected time \"" + value + "\"");
        }
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
        Boolean lastAutoRecycle;
        int lastRecycleHour = -1;
        int lastRecycleMinute = -1;
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
        public void setDashboardUrl(String url) {
            calls.add("setDashboardUrl");
            lastUrl = url;
        }

        @Override
        public void setAutoRecycle(boolean enabled) {
            calls.add("setAutoRecycle");
            lastAutoRecycle = enabled;
        }

        @Override
        public void setRecycleTime(int hour, int minute) {
            calls.add("setRecycleTime");
            lastRecycleHour = hour;
            lastRecycleMinute = minute;
        }

        @Override
        public void publishTelemetry() {
            calls.add("publishTelemetry");
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
