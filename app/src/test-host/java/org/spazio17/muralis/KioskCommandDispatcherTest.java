/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.List;

public final class KioskCommandDispatcherTest {
    public static void main(String[] args) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();

        require(KioskCommandDispatcher.dispatch(
                "dashboard.reload", KioskCommandDispatcher.CommandArgs.EMPTY, executor)
                .status.equals("accepted"), "dashboard.reload should be accepted");
        require(executor.calls.contains("kioskReload"), "dashboard.reload did not reach executor");

        testStatusLightCommands();
        testBrowserOptions();
        testCertificatePins();
        testPopups();
        testCameras();
        testPowerCommandsTellTheTruth();
        testWithdrawnRecycleCommands();
        testBrightnessRefusalIsReported();
        testDeviceIdValidation();
        testCertificateChoice();
        testAdminPortValidation();
        testWebAdminToggle();
        testOneOffUrlAndHome();
        testTelemetryPublishTellsTheTruth();
        testEnabledFlagParsing();
        testScreensaverCommands();
        testSensorCommands();
        testDashboardShows();
        testWordsAndKeys();

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

        // display.orientation: the vocabulary is closed, forwarded verbatim, and "auto" on a
        // device with no accelerometer is refused rather than silently accepted, the same
        // contract auto_brightness has with the light sensor.
        KioskCommandDispatcher.Result noValue = KioskCommandDispatcher.dispatch(
                "display.orientation", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(noValue.status.equals("rejected"), "orientation without a value was accepted");
        KioskCommandDispatcher.Result badValue = KioskCommandDispatcher.dispatch(
                "display.orientation",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "upside-down"), executor);
        require(badValue.status.equals("rejected"), "an unknown orientation was accepted");
        require(executor.lastOrientation == null, "executor ran despite a bad value");
        KioskCommandDispatcher.Result upright = KioskCommandDispatcher.dispatch(
                "display.orientation",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "portrait"), executor);
        require(upright.status.equals("accepted"), "portrait was rejected");
        require("portrait".equals(executor.lastOrientation), "orientation not forwarded");
        executor.accelerometerPresent = false;
        KioskCommandDispatcher.Result noAccelerometer = KioskCommandDispatcher.dispatch(
                "display.orientation",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "auto"), executor);
        require(noAccelerometer.status.equals("rejected"),
                "a device with no accelerometer must not report success for auto");
        require(noAccelerometer.detail.contains("accelerometer"), "rejection should say why: "
                + noAccelerometer.detail);
        executor.accelerometerPresent = true;

        // display.off_method: the same shape as orientation. A closed vocabulary validated here,
        // and "sleep" refused for an install that is not the device owner, before anything is
        // stored, because lockNow does not exist for it.
        KioskCommandDispatcher.Result noMethod = KioskCommandDispatcher.dispatch(
                "display.off_method", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(noMethod.status.equals("rejected"), "off_method without a value was accepted");
        KioskCommandDispatcher.Result badMethod = KioskCommandDispatcher.dispatch(
                "display.off_method",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "dim"), executor);
        require(badMethod.status.equals("rejected"), "an unknown display-off method was accepted");
        require(executor.lastDisplayOffMethod == null, "executor ran despite a bad method");
        KioskCommandDispatcher.Result film = KioskCommandDispatcher.dispatch(
                "display.off_method",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "film"), executor);
        require(film.status.equals("accepted"), "film was rejected");
        require("film".equals(executor.lastDisplayOffMethod), "method not forwarded");
        executor.deviceOwner = false;
        KioskCommandDispatcher.Result sleepWithoutOwner = KioskCommandDispatcher.dispatch(
                "display.off_method",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "sleep"), executor);
        require(sleepWithoutOwner.status.equals("rejected"),
                "a non-owner must not report success for sleep");
        require(sleepWithoutOwner.detail.contains("device-owner"),
                "rejection should say why: " + sleepWithoutOwner.detail);
        require("film".equals(executor.lastDisplayOffMethod),
                "a refused sleep must leave the stored method alone");
        executor.deviceOwner = true;

        KioskCommandDispatcher.Result badUrl = KioskCommandDispatcher.dispatch(
                "dashboard.url", new KioskCommandDispatcher.CommandArgs(-1, "ftp://example.com"),
                executor);
        require(badUrl.status.equals("rejected"), "non-http(s) url accepted");

        KioskCommandDispatcher.Result emptyUrl = KioskCommandDispatcher.dispatch(
                "dashboard.url", new KioskCommandDispatcher.CommandArgs(-1, "   "), executor);
        require(emptyUrl.status.equals("rejected"), "blank url accepted");

        KioskCommandDispatcher.Result okUrl = KioskCommandDispatcher.dispatch(
                "dashboard.url",
                new KioskCommandDispatcher.CommandArgs(-1, "https://home.local:8123/"),
                executor);
        require(okUrl.status.equals("accepted"), "valid https url rejected");
        require("https://home.local:8123/".equals(executor.lastUrl),
                "url value not forwarded");

        KioskCommandDispatcher.Result paddedUrl = KioskCommandDispatcher.dispatch(
                "dashboard.url",
                new KioskCommandDispatcher.CommandArgs(
                        -1, "  https://home.local:8123/dashboard  "),
                executor);
        require(paddedUrl.status.equals("accepted"), "padded valid URL rejected");
        require("https://home.local:8123/dashboard".equals(executor.lastUrl),
                "validated URL was not normalized before persistence");

        KioskCommandDispatcher.Result unknown = KioskCommandDispatcher.dispatch(
                "kiosk.self_destruct", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(unknown.status.equals("unsupported"), "unknown command should be unsupported");

        screensaverPlaylistIsChosenByName();
        System.out.println("KioskCommandDispatcherTest passed");
    }

    /**
     * A power command that cannot work must report "unsupported", never "accepted".
     *
     * <p>This guards a bug that was live on hardware on 2026-08-19: {@code system.shutdown} returned
     * {@code {"status":"accepted","detail":"shutting down"}} from a build with no way to power the
     * device off, and {@code system.reboot} did the same without device-owner status. A smart-home
     * automation would have believed it switched the panel off and stopped watching it.
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
     * The web admin port is floored at 1024, not 1. Found on hardware (2026-08-24): port 80 passed a
     * 1-65535 check, persisted, and failed at bind time, because an unprivileged app can never
     * bind below 1024 on Android, so the admin's own settings box switched the admin off. The
     * broker port deliberately has no such floor; it is a remote port.
     */
    private static void testAdminPortValidation() {
        require(KioskCommandDispatcher.validateAdminPort(8080, 8080, 8081) == null,
                "the default port must be accepted");
        require(KioskCommandDispatcher.validateAdminPort(1024, 8080, 8081) == null,
                "the first unprivileged port must be accepted");
        require(KioskCommandDispatcher.validateAdminPort(65535, 8080, 8081) == null,
                "the last port must be accepted");
        require(KioskCommandDispatcher.validateAdminPort(1023, 8080, 8081) != null,
                "the last privileged port must be refused");
        require(KioskCommandDispatcher.validateAdminPort(80, 8080, 8081) != null,
                "a privileged port passes a range check and fails at bind; it must be refused here");
        require(KioskCommandDispatcher.validateAdminPort(0, 8080, 8081) != null,
                "port 0 must be refused");
        require(KioskCommandDispatcher.validateAdminPort(65536, 8080, 8081) != null,
                "a port above 65535 must be refused");
        require(KioskCommandDispatcher.validateAdminPort(80, 8080, 8081).contains("1024"),
                "the refusal must name the allowed range");
        // The web admin and the member endpoint never share a port (2026-10-08).
        require(KioskCommandDispatcher.validateAdminPort(8081, 8080, 8081) != null,
                "the member port must be refused as a new web admin port");
        require(KioskCommandDispatcher.validateAdminPort(8082, 8080, 8081) == null,
                "a port next to the member port must be accepted");
        require(KioskCommandDispatcher.validateAdminPort(8081, 8081, 8081) == null,
                "a panel already serving the web admin on the member port can still save");
        require(KioskCommandDispatcher.validateMemberPort(9000, 8081, 8080) == null,
                "a free member port must be accepted");
        require(KioskCommandDispatcher.validateMemberPort(8080, 8081, 8080) != null,
                "the web admin port must be refused as a new member port");
        require(KioskCommandDispatcher.validateMemberPort(80, 8081, 8080) != null,
                "a privileged member port must be refused");
        require(KioskCommandDispatcher.validateMemberPort(65536, 8081, 8080) != null,
                "a member port above 65535 must be refused");
        // The network speaker's port, against both of the others.
        require(KioskCommandDispatcher.validateSpeakerPort(2325, 2325, 8080, 8081) == null,
                "the default speaker port must be accepted");
        require(KioskCommandDispatcher.validateSpeakerPort(1023, 2325, 8080, 8081) != null,
                "a privileged speaker port must be refused");
        require(KioskCommandDispatcher.validateSpeakerPort(65536, 2325, 8080, 8081) != null,
                "a speaker port above 65535 must be refused");
        require(KioskCommandDispatcher.validateSpeakerPort(8080, 2325, 8080, 8081)
                        .contains("web admin"),
                "the web admin port must be refused as a new speaker port, by name");
        require(KioskCommandDispatcher.validateSpeakerPort(8081, 2325, 8080, 8081)
                        .contains("member"),
                "the member port must be refused as a new speaker port, by name");
        require(KioskCommandDispatcher.validateMemberPort(8080, 8080, 8080) == null,
                "a member port that already collides can still be saved unchanged");
    }

    /**
     * A one-off URL is shown but never stored, and there is a way back to the stored one.
     *
     * <p>The distinction is the whole point: dashboard.url replaces the panel's dashboard, while
     * dashboard.open_once shows a URL with one-off query parameters and leaves storage alone, so a
     * restart or the nightly clean returns to the dashboard. dashboard.open_once validates its URL
     * exactly like set_url, because an unusable one-off is still worth refusing with a reason.
     */
    private static void testOneOffUrlAndHome() {
        RecordingExecutor executor = new RecordingExecutor();

        KioskCommandDispatcher.Result badUrl = KioskCommandDispatcher.dispatch(
                "dashboard.open_once", new KioskCommandDispatcher.CommandArgs(-1, "ftp://example.com"),
                executor);
        require(badUrl.status.equals("rejected"), "a non-http(s) one-off URL was accepted");
        require(!executor.calls.contains("openUrlOnce"), "executor ran despite rejection");

        KioskCommandDispatcher.Result blank = KioskCommandDispatcher.dispatch(
                "dashboard.open_once", new KioskCommandDispatcher.CommandArgs(-1, "   "), executor);
        require(blank.status.equals("rejected"), "a blank one-off URL was accepted");

        KioskCommandDispatcher.Result once = KioskCommandDispatcher.dispatch(
                "dashboard.open_once",
                new KioskCommandDispatcher.CommandArgs(
                        -1, "  http://home.local:8123/dashboard/0?kiosk  "),
                executor);
        require(once.status.equals("accepted"), "a valid one-off URL was rejected");
        require("http://home.local:8123/dashboard/0?kiosk".equals(executor.lastOnceUrl),
                "one-off URL not trimmed and forwarded: " + executor.lastOnceUrl);
        // The one-off path must never reach the setter that persists.
        require(!executor.calls.contains("setDashboardUrl"),
                "a one-off URL must not be stored as the dashboard");

        KioskCommandDispatcher.Result home = KioskCommandDispatcher.dispatch(
                "dashboard.open", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(home.status.equals("accepted"), "dashboard.open was rejected");
        require(executor.calls.contains("showMainDashboard"), "dashboard.open did not reach executor");
        require(!executor.calls.contains("setDashboardUrl"),
                "dashboard.open must not write anything");
    }

    /**
     * webadmin.enabled flips the surface without touching the password: the flag is required (no
     * guessing), forwarded exactly, and the same envelope shape as every other enabled command,
     * so the MQTT switch and a curl both drive it.
     */
    private static void testWebAdminToggle() {
        RecordingExecutor executor = new RecordingExecutor();

        KioskCommandDispatcher.Result noFlag = KioskCommandDispatcher.dispatch(
                "webadmin.enabled", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(noFlag.status.equals("rejected"), "webadmin.enabled without a flag was accepted");
        require(!executor.calls.contains("setWebAdminEnabled"),
                "executor ran despite a missing flag");

        KioskCommandDispatcher.Result off = KioskCommandDispatcher.dispatch(
                "webadmin.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, Boolean.FALSE), executor);
        require(off.status.equals("accepted"), "webadmin off was rejected");
        require(Boolean.FALSE.equals(executor.lastWebAdminEnabled), "flag not forwarded");

        KioskCommandDispatcher.Result on = KioskCommandDispatcher.dispatch(
                "webadmin.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, Boolean.TRUE), executor);
        require(on.status.equals("accepted"), "webadmin on was rejected");
        require(Boolean.TRUE.equals(executor.lastWebAdminEnabled), "flag not forwarded");
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

    /** The web admin's certificate: own always, uploaded only while one is kept, nothing else. */
    private static void testCertificateChoice() {
        require(KioskCommandDispatcher.validateCertificateChoice("own", false) == null,
                "the panel's own certificate is always there");
        require(KioskCommandDispatcher.validateCertificateChoice("uploaded", true) == null,
                "an upload kept can be chosen");
        require("no certificate is uploaded".equals(
                KioskCommandDispatcher.validateCertificateChoice("uploaded", false)),
                "nothing uploaded cannot be chosen");
        for (String bad : new String[] {null, "", " own", "OWN", "<script>", "self"}) {
            require("the certificate must be own or uploaded".equals(
                    KioskCommandDispatcher.validateCertificateChoice(bad, true)),
                    "a choice nothing offers is refused: " + bad);
        }
    }

    /**
     * The device id must be refused at the surface, with a reason, for anything MQTT cannot carry.
     *
     * <p>Guards the audited failure mode: an id with a space or a topic metacharacter was stored
     * by both settings surfaces, answered with success, and MQTT then refused to start with one
     * logcat line nobody reads. An empty id is refused separately because KioskConfig.load treats
     * it as "never provisioned" and re-mints the panel's identity, orphaning every MQTT discovery
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

    /** The sensors' commands: the arguments each needs, and a refusal that reaches the reply. */
    private static void testSensorCommands() {
        RecordingExecutor executor = new RecordingExecutor();
        KioskCommandDispatcher.Result r;
        r = KioskCommandDispatcher.dispatch("sensor.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "illuminance"), executor);
        require(r.status.equals("rejected"), "sensor.enabled without enabled accepted");
        r = KioskCommandDispatcher.dispatch("sensor.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, true, null), executor);
        require(r.status.equals("rejected"), "sensor.enabled without a sensor accepted");
        r = KioskCommandDispatcher.dispatch("sensor.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, true, "illuminance"), executor);
        require(r.status.equals("accepted") && executor.calls.contains("setSensorEnabled:illuminance:true"),
                "sensor.enabled did not reach the executor");
        r = KioskCommandDispatcher.dispatch("automation.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "wake"), executor);
        require(r.status.equals("rejected"), "automation.enabled without enabled accepted");
        r = KioskCommandDispatcher.dispatch("automation.enabled",
                new KioskCommandDispatcher.CommandArgs(-1, null, false, "wake"), executor);
        require(r.status.equals("accepted") && executor.calls.contains("setAutomationEnabled:wake:false"),
                "automation.enabled did not reach the executor");
        r = KioskCommandDispatcher.dispatch("camera.motion",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("rejected"), "camera.motion without enabled accepted");
        r = KioskCommandDispatcher.dispatch("camera.motion",
                new KioskCommandDispatcher.CommandArgs(-1, null, true), executor);
        require(r.status.equals("accepted") && executor.calls.contains("setCameraMotion:true"),
                "camera.motion did not reach the executor");
        r = KioskCommandDispatcher.dispatch("camera.snapshot",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("cameraSnapshot"),
                "camera.snapshot did not reach the executor");
        r = KioskCommandDispatcher.dispatch("panel.screenshot",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("screenshot"),
                "panel.screenshot did not reach the executor");
        executor.screenshotProblem = "The panel is not showing its screen";
        r = KioskCommandDispatcher.dispatch("panel.screenshot",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("rejected") && r.detail.equals(executor.screenshotProblem),
                "panel.screenshot did not carry the executor's refusal");
        executor.screenshotProblem = null;
        r = KioskCommandDispatcher.dispatch("panel.identify",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("identify"),
                "panel.identify did not reach the executor");
        executor.identifyProblem = "The panel is not showing its screen";
        r = KioskCommandDispatcher.dispatch("panel.identify",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("rejected") && r.detail.equals(executor.identifyProblem),
                "panel.identify did not carry the executor's refusal");
        executor.identifyProblem = null;
        // The way in and out from afar, without the corner taps: the web admin and the Fleet page.
        r = KioskCommandDispatcher.dispatch("panel.dashboard",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("dashboard"),
                "panel.dashboard did not reach the executor");
        r = KioskCommandDispatcher.dispatch("panel.settings",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("settings"),
                "panel.settings did not reach the executor");
        r = KioskCommandDispatcher.dispatch("panel.home_screen",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("home_screen"),
                "panel.home_screen did not reach the executor");
        r = KioskCommandDispatcher.dispatch("proximity.calibrate",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "covered"), executor);
        require(r.status.equals("accepted") && executor.calls.contains("calibrateProximity:covered"),
                "proximity.calibrate did not carry its step");
        r = KioskCommandDispatcher.dispatch("sound_level.calibrate",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "quiet"), executor);
        require(r.status.equals("accepted") && executor.calls.contains("calibrateMicrophone:quiet"),
                "sound_level.calibrate did not carry its step");
        r = KioskCommandDispatcher.dispatch("bluetooth_beacons.calibrate",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "far", "u:1:2"), executor);
        require(r.status.equals("accepted")
                && executor.calls.contains("calibrateBeacons:far:u:1:2"),
                "bluetooth_beacons.calibrate did not carry its step and beacon");
        r = KioskCommandDispatcher.dispatch("sensor.sleep_test",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "proximity"), executor);
        require(r.status.equals("accepted") && executor.calls.contains("sleepTest:proximity"),
                "sensor.sleep_test did not carry its sensor");
        r = KioskCommandDispatcher.dispatch("audio.volume",
                new KioskCommandDispatcher.CommandArgs(101, null), executor);
        require(r.status.equals("rejected") && !executor.calls.contains("setMediaVolume:101"),
                "audio.volume over 100 accepted");
        r = KioskCommandDispatcher.dispatch("audio.volume",
                new KioskCommandDispatcher.CommandArgs(30, null), executor);
        require(r.status.equals("accepted") && executor.calls.contains("setMediaVolume:30"),
                "audio.volume did not reach the executor");
        r = KioskCommandDispatcher.dispatch("audio.play",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("rejected"), "audio.play without a url accepted");
        r = KioskCommandDispatcher.dispatch("audio.play",
                new KioskCommandDispatcher.CommandArgs(-1, "https://example.net/a.mp3"), executor);
        require(r.status.equals("accepted") && executor.calls.contains("playAudio:https://example.net/a.mp3"),
                "audio.play did not reach the executor");
        r = KioskCommandDispatcher.dispatch("audio.stop",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(r.status.equals("accepted") && executor.calls.contains("playAudio:null"),
                "audio.stop did not reach the executor");
        r = KioskCommandDispatcher.dispatch("audio.say",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "  "), executor);
        require(r.status.equals("rejected"), "audio.say with nothing to say accepted");
        r = KioskCommandDispatcher.dispatch("audio.say",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, " Hello "), executor);
        require(r.status.equals("accepted") && executor.calls.contains("say:Hello"),
                "audio.say did not carry its sentence trimmed");
        // A refusal from the executor reaches the reply as the reason.
        RecordingExecutor refusing = new RecordingExecutor() {
            @Override
            public String sleepTest(String sensorId) {
                return "the panel did not sleep";
            }
        };
        r = KioskCommandDispatcher.dispatch("sensor.sleep_test",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "illuminance"), refusing);
        require(r.status.equals("rejected") && r.detail.equals("the panel did not sleep"),
                "the executor's refusal did not reach the reply");
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
     * <p>Worth a test rather than a deletion. Both were announced over MQTT discovery, so a smart
     * home somewhere may still hold an automation that publishes them, and an automation whose
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

    /**
     * The screensaver's three commands. Start tells the truth the way brightness does: a panel
     * whose mode is off, or whose web-page mode has no address, refuses with the reason rather
     * than accepting a screensaver that shows nothing. The mode is a closed vocabulary.
     */
    private static void testScreensaverCommands() {
        RecordingExecutor executor = new RecordingExecutor();
        require(KioskCommandDispatcher.dispatch("ambient.start",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("accepted"),
                "start with a runnable screensaver must be accepted");
        require(executor.calls.contains("screensaverStart"), "start did not reach the executor");
        executor.screensaverProblem = "the Ambient mode is off";
        KioskCommandDispatcher.Result refused = KioskCommandDispatcher.dispatch(
                "ambient.start", KioskCommandDispatcher.CommandArgs.EMPTY, executor);
        require(refused.status.equals("rejected") && refused.detail.contains("mode is off"),
                "a screensaver that cannot run must say so: " + refused.detail);
        require(KioskCommandDispatcher.dispatch("ambient.stop",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("accepted"),
                "stop is always accepted");
        require(executor.calls.contains("screensaverStop"), "stop did not reach the executor");
        require(KioskCommandDispatcher.dispatch("ambient.mode",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                "mode without a value must be rejected");
        require(KioskCommandDispatcher.dispatch("ambient.mode",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "photos"), executor)
                .status.equals("rejected"), "an unknown mode must be rejected");
        require(executor.screensaverSettings.isEmpty(), "executor ran despite a bad mode");
        require(KioskCommandDispatcher.dispatch("ambient.mode",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "url"), executor)
                .status.equals("accepted"), "url is a mode");
        require("url".equals(executor.screensaverSettings.get(
                KioskCommandDispatcher.ScreensaverSetting.MODE)), "mode not forwarded");
        require(KioskCommandDispatcher.dispatch("ambient.mode",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "off"), executor)
                .status.equals("accepted"), "off is a mode too: it is how Ambient is switched off");
        testScreensaverSettingCommands();
    }

    /**
     * Every screensaver setting is a command, because MQTT and HTTP must be able to set what the
     * two user interfaces can (2026-09-09). The table below is the whole vocabulary: a
     * good value forwarded canonically, a bad one refused with its rule.
     */
    private static void testScreensaverSettingCommands() {
        RecordingExecutor executor = new RecordingExecutor();
        String[][] good = {
                {"ambient.start_after_seconds", " 45 ", "START_AFTER_SECONDS", "45"},
                {"ambient.off_seconds", "0", "OFF_SECONDS", "0"},
                {"ambient.picture_seconds", "30", "PICTURE_SECONDS", "30"},
                {"ambient.dim_percent", "8", "DIM_PERCENT", "8"},
                {"ambient.transition", "slide", "TRANSITION", "slide"},
                {"ambient.transition", "cut", "TRANSITION", "cut"},
                {"ambient.picture_fit", "fill", "PICTURE_FIT", "fill"},
                {"ambient.credit_corner", "top_right", "CREDIT_CORNER", "top_right"},
                {"ambient.on_wake", "dashboard", "ON_WAKE", "dashboard"},
        };
        for (String[] row : good) {
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(row[0],
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, row[1]), executor);
            require(result.status.equals("accepted"), row[0] + " was rejected: " + result.detail);
            require(row[3].equals(executor.screensaverSettings.get(
                    KioskCommandDispatcher.ScreensaverSetting.valueOf(row[2]))),
                    row[0] + " stored " + executor.screensaverSettings.get(
                            KioskCommandDispatcher.ScreensaverSetting.valueOf(row[2])));
        }
        String[][] bad = {
                {"ambient.start_after_seconds", "2 min"},
                {"ambient.transition", "none"},
                {"ambient.off_seconds", "-1"},
                {"ambient.picture_seconds", "0"},
                {"ambient.dim_percent", "0"},
                {"ambient.transition", "zoom"},
                {"ambient.picture_fit", "zoom"},
                {"ambient.credit_corner", "centre"},
                {"ambient.on_wake", "later"},
        };
        for (String[] row : bad) {
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(row[0],
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, row[1]), executor);
            require(result.status.equals("rejected"), row[0] + " accepted " + row[1]);
            require(!result.detail.isEmpty(), row[0] + " refused without a reason");
        }
        // The picture source left with Bing and Wikimedia, and the screensaver became Ambient
        // with no alias: neither command is answered any more.
        require(KioskCommandDispatcher.dispatch("ambient.source",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "local"), executor)
                .status.equals("unsupported"), "ambient.source is gone");
        require(KioskCommandDispatcher.dispatch("screensaver.start",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, null), executor)
                .status.equals("unsupported"), "the old screensaver.* names are gone");
        // The three flags take the same enabled parsing as every other flag in this app.
        for (String command : new String[] {"ambient.shuffle", "ambient.one_per_start",
                "ambient.credit"}) {
            require(KioskCommandDispatcher.dispatch(command,
                    KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                    command + " without a flag was accepted");
            require(KioskCommandDispatcher.dispatch(command,
                    new KioskCommandDispatcher.CommandArgs(-1, null, Boolean.TRUE), executor)
                    .status.equals("accepted"), command + " with true was rejected");
        }
        require("true".equals(executor.screensaverSettings.get(
                KioskCommandDispatcher.ScreensaverSetting.SHUFFLE)), "shuffle stored");
        // The screensaver's page: the dashboard's own validator, and empty is how it is cleared.
        require(KioskCommandDispatcher.dispatch("ambient.url",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "example.com"), executor)
                .status.equals("rejected"), "a schemeless page address was accepted");
        require(KioskCommandDispatcher.dispatch("ambient.url",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "https://example.com/frame"),
                executor).status.equals("accepted"), "a page address was rejected");
        require("https://example.com/frame".equals(executor.screensaverSettings.get(
                KioskCommandDispatcher.ScreensaverSetting.URL)), "the page address is stored");
        require(KioskCommandDispatcher.dispatch("ambient.url",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, ""), executor)
                .status.equals("accepted"), "an empty address clears it");
        require("".equals(executor.screensaverSettings.get(
                KioskCommandDispatcher.ScreensaverSetting.URL)), "cleared");
    }

    /**
     * The playlist is chosen by name, and an empty name means none, which is a real state because a
     * playlist can be deleted while it is in use.
     */
    private static void screensaverPlaylistIsChosenByName() {
        RecordingExecutor executor = new RecordingExecutor();
        require(KioskCommandDispatcher.dispatch("ambient.album",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Holidays"), executor)
                .status.equals("accepted"), "an album name must be accepted");
        require("Holidays".equals(executor.lastPlaylist), "the name must reach the executor");
        require(KioskCommandDispatcher.dispatch("ambient.album",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, ""), executor)
                .status.equals("accepted"), "an empty name means none and must be accepted");
        require("".equals(executor.lastPlaylist), "the empty name must reach the executor");
        // No value at all is a caller mistake rather than a request for none.
        require(KioskCommandDispatcher.dispatch("ambient.album",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                "an album command with no value must be rejected");
        // The executor's own refusal is what the caller is told, so a wrong name names the right ones.
        executor.playlistProblem = "no album called Nope; this panel has Holidays";
        KioskCommandDispatcher.Result refused = KioskCommandDispatcher.dispatch(
                "ambient.album",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Nope"), executor);
        require(refused.status.equals("rejected") && refused.detail.contains("has Holidays"),
                "an unknown album must be refused with the names that exist: " + refused.detail);
    }

    /**
     * The status light: light.on with a colour and a brightness, each optional and each checked
     * before the executor runs, and light.off.
     */
    private static void testStatusLightCommands() {
        RecordingExecutor executor = new RecordingExecutor();
        require(KioskCommandDispatcher.dispatch("light.on",
                new KioskCommandDispatcher.CommandArgs(40, null, null, " 0, 200 ,0 "), executor)
                .status.equals("accepted"), "light.on with a colour and a brightness");
        require(Boolean.TRUE.equals(executor.lightOn) && executor.lightColor == 0x00C800
                && executor.lightPercent == 40, "the light's three values reached the executor");
        require(KioskCommandDispatcher.dispatch("light.on",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("accepted")
                && executor.lightColor == null && executor.lightPercent == -1,
                "light.on alone keeps the stored colour and brightness");
        for (String bad : new String[] {"red", "255,0", "256,0,0", "-1,0,0", "1,2,3,4", "#ff0000"}) {
            executor.lightOn = null;
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch("light.on",
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, bad), executor);
            require(result.status.equals("rejected") && result.detail.equals(StatusLight.COLOR_RULE)
                    && executor.lightOn == null, "colour " + bad + " refused with its rule");
        }
        for (int bad : new int[] {0, 101, -2}) {
            executor.lightOn = null;
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch("light.on",
                    new KioskCommandDispatcher.CommandArgs(bad, null, null, null), executor);
            require(result.status.equals("rejected") && executor.lightOn == null,
                    "brightness " + bad + " refused");
        }
        require(KioskCommandDispatcher.dispatch("light.off",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("accepted")
                && Boolean.FALSE.equals(executor.lightOn), "light.off");
        require(StatusLight.formatColor(StatusLight.parseColor("255,160,0")).equals("255,160,0")
                && "Orange".equals(StatusLight.nameOf(0xFFA500)) && StatusLight.nameOf(0x123456) == null,
                "colours round-trip and the named ones are found");
        require((StatusLight.argb(new StatusLight.State(true, 0xFF0000, 100)) >>> 24) == 255
                && (StatusLight.argb(new StatusLight.State(true, 0xFF0000, 50)) >>> 24) == 128,
                "the brightness is the frame's opacity");
    }

    /**
     * The browser options: each command checked against BrowserOptions and forwarded
     * canonically, refused with its rule; and the pure helpers the page relies on.
     */
    private static void testBrowserOptions() {
        RecordingExecutor executor = new RecordingExecutor();
        String[][] good = {
                {"browser.text_size", " 150 ", "TEXT_SIZE", "150"},
                {"browser.page_zoom", "auto", "PAGE_ZOOM", "0"},
                {"browser.page_zoom", "25", "PAGE_ZOOM", "25"},
                {"browser.back_after", "0", "BACK_AFTER", "0"},
                {"browser.back_after", "300", "BACK_AFTER", "300"},
                {"browser.user_agent", "", "USER_AGENT", ""},
                {"browser.user_agent", " WallPanel/2 ", "USER_AGENT", "WallPanel/2"},
        };
        for (String[] row : good) {
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(row[0],
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, row[1]), executor);
            require(result.status.equals("accepted"), row[0] + " " + row[1] + ": " + result.detail);
            require(row[3].equals(executor.browserOptions.get(
                    KioskCommandDispatcher.BrowserOption.valueOf(row[2]))),
                    row[0] + " stored " + executor.browserOptions.get(
                            KioskCommandDispatcher.BrowserOption.valueOf(row[2])));
        }
        String[][] bad = {
                {"browser.text_size", "49"}, {"browser.text_size", "301"},
                {"browser.text_size", "big"}, {"browser.page_zoom", "24"},
                {"browser.page_zoom", "401"}, {"browser.page_zoom", "-5"},
                {"browser.back_after", "86401"}, {"browser.back_after", "1.5"},
                {"browser.user_agent", "two\nlines"},
        };
        for (String[] row : bad) {
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(row[0],
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, row[1]), executor);
            require(result.status.equals("rejected") && !result.detail.isEmpty(),
                    row[0] + " accepted " + row[1]);
        }
        StringBuilder long513 = new StringBuilder();
        for (int i = 0; i < 513; i++) {
            long513.append('x');
        }
        require(KioskCommandDispatcher.dispatch("browser.user_agent",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, long513.toString()),
                executor).status.equals("rejected"), "a user agent past 512 characters");
        require(KioskCommandDispatcher.dispatch("browser.dark_look",
                new KioskCommandDispatcher.CommandArgs(-1, null, true), executor)
                .status.equals("accepted") && "true".equals(executor.browserOptions.get(
                        KioskCommandDispatcher.BrowserOption.DARK)), "the dark look on");
        require(KioskCommandDispatcher.dispatch("browser.dark_look",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                "the dark look needs enabled");
        require(BrowserOptions.userAgent("", "Mozilla/5.0 X", "0.7.0")
                .equals("Mozilla/5.0 X Muralis/0.7.0")
                && BrowserOptions.userAgent(" Own/1 ", "Mozilla/5.0 X", "0.7.0").equals("Own/1"),
                "the user agent the page sees");
        require(BrowserOptions.signInHost("https://home.example.net:8123/dashboard",
                        "home.example.net")
                && !BrowserOptions.signInHost("https://home.example.net/", "evil.example")
                && !BrowserOptions.signInHost("not a url", "home.example.net"),
                "the sign-in goes to the dashboard's own host only");
        require(BrowserOptions.checkSignInUser("anna") == null
                && BrowserOptions.checkSignInUser("a:b") != null
                && BrowserOptions.checkSignInUser("") == null, "sign-in names");
        require(!BrowserOptions.awayFrom("https://h/dashboard/0#x", "https://h/dashboard/0/")
                && BrowserOptions.awayFrom("https://h/dashboard/1", "https://h/dashboard/0"),
                "a fragment or a trailing slash is not away");
        require(BrowserOptions.timeToGoBack(true, 60_000, 60)
                && !BrowserOptions.timeToGoBack(true, 59_999, 60)
                && !BrowserOptions.timeToGoBack(false, 600_000, 60)
                && !BrowserOptions.timeToGoBack(true, 600_000, 0), "the way back");
        require(!BrowserOptions.darkLookPossible(28) && BrowserOptions.darkLookPossible(29),
                "the dark look from Android 10");
        for (String command : new String[] {"browser.page_camera", "browser.page_microphone"}) {
            require(KioskCommandDispatcher.dispatch(command,
                    new KioskCommandDispatcher.CommandArgs(-1, null, true), executor)
                    .status.equals("accepted"), command + " on");
            require(KioskCommandDispatcher.dispatch(command,
                    KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                    command + " needs enabled");
        }
        require("true".equals(executor.browserOptions.get(
                KioskCommandDispatcher.BrowserOption.PAGE_CAMERA))
                && "true".equals(executor.browserOptions.get(
                        KioskCommandDispatcher.BrowserOption.PAGE_MICROPHONE)),
                "the page's camera and microphone reach the executor");
    }

    /** The trusted certificates: the store, the fingerprint's form, never a wildcard. */
    private static void testCertificatePins() {
        String fp = CertificatePins.fingerprint("a certificate".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        require(CertificatePins.isFingerprint(fp) && fp.length() == 95,
                "SHA-256 in 32 colon-separated pairs: " + fp);
        java.util.Map<String, String> pins = CertificatePins.with(new java.util.LinkedHashMap<>(),
                "HOME.local", fp);
        require(CertificatePins.trusted(pins, "home.local", fp)
                && !CertificatePins.trusted(pins, "home.local", fp.replace('A', 'B'))
                && !CertificatePins.trusted(pins, "other.local", fp)
                && !CertificatePins.trusted(pins, null, fp), "one fingerprint for one host");
        java.util.Map<String, String> back = CertificatePins.parse(CertificatePins.format(pins)
                + "*.local " + fp + "\nbroken line\nhost notafingerprint\n");
        require(back.equals(pins), "the store reads back and drops what is not a pin: " + back);
        java.util.Map<String, String> full = new java.util.LinkedHashMap<>();
        for (int i = 0; i < CertificatePins.MAX_PINS; i++) {
            full.put("h" + i, fp);
        }
        require(CertificatePins.with(full, "new", fp) == null
                && CertificatePins.with(full, "h3", fp) != null,
                "a full list refuses a new host and still replaces a known one");
    }

    /** panel.notify: the defaults, each argument's limit, the wake. */
    private static void testPopups() {
        RecordingExecutor executor = new RecordingExecutor();
        java.util.Map<String, String> extra = new java.util.HashMap<>();
        extra.put("title", "Door");
        KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch("panel.notify",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Someone at the door",
                        null, extra), executor);
        require(result.status.equals("accepted") && executor.popup != null
                && executor.popup.seconds == Popup.DEFAULT_SECONDS && executor.popup.wake
                && !executor.popup.chime && executor.calls.contains("displayWake"),
                "a pop-up with the defaults wakes the display: " + result.detail);
        extra.clear();
        extra.put("seconds", "0");
        extra.put("wake", "false");
        executor.calls.clear();
        require(KioskCommandDispatcher.dispatch("panel.notify",
                new KioskCommandDispatcher.CommandArgs(-1, "https://cam.local/snap.jpg", null,
                        null, null, extra), executor).status.equals("accepted")
                && executor.popup.seconds == 0 && executor.popup.url.equals(
                        "https://cam.local/snap.jpg")
                && !executor.calls.contains("displayWake"), "an address alone, until touched");
        String[][] bad = {{"", "", ""}, {"x", "seconds", "301"}, {"x", "seconds", "-1"},
                {"x", "url", "file:///etc/passwd"}, {"x", "wake", "maybe"}};
        for (String[] row : bad) {
            java.util.Map<String, String> named = new java.util.HashMap<>();
            if (!row[1].isEmpty()) {
                named.put(row[1], row[2]);
            }
            require(KioskCommandDispatcher.dispatch("panel.notify",
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, row[0], null, named),
                    executor).status.equals("rejected"), "refused: " + java.util.Arrays.toString(row));
        }
        StringBuilder long501 = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            long501.append('m');
        }
        require(KioskCommandDispatcher.dispatch("panel.notify",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, long501.toString()),
                executor).status.equals("rejected"), "a message past 500 characters");
    }

    /** The cameras: the list, the checks, the sign-ins kept apart. */
    private static void testCameras() {
        require(Cameras.check("Door", "http://cam.local/snap.jpg") == null, "a camera");
        require(Cameras.check("Door", "rtsp://192.0.2.5:554/stream1") == null
                && Cameras.check("Door", "RTSP://cam/h264Preview_01_sub") == null,
                "an RTSP camera");
        require(Cameras.check("", "http://x") != null
                && Cameras.check("Door", "ftp://x") != null
                && Cameras.check("Door", "rtsp://") != null
                && Cameras.check("Door", "http://x y") != null
                && Cameras.check("Door", "http://") != null,
                "each check refuses");
        java.util.List<Cameras.Camera> list = new java.util.ArrayList<>();
        list = Cameras.with(list, "", new Cameras.Camera("Door", "http://cam/door"));
        list = Cameras.with(list, "", new Cameras.Camera("Garden", "http://cam/g"));
        java.util.List<Cameras.Camera> back = Cameras.parse(Cameras.format(list));
        require(back.size() == 2 && back.get(1).url.equals("http://cam/g"), "the list round-trips");
        require(Cameras.parse("not json").isEmpty() && Cameras.parse("").isEmpty(),
                "an unreadable list is empty");
        require(Cameras.find(back, "door") != null, "found by name, any case");
        java.util.List<Cameras.Camera> renamed = Cameras.with(back, "Door",
                new Cameras.Camera("Front", "http://cam/door"));
        require(renamed.size() == 2 && renamed.get(0).name.equals("Front"),
                "a rename keeps the camera's place");
        require(Cameras.saveProblem(back, "", "garden", "http://x", "", "") != null,
                "a taken name is refused");
        require(Cameras.saveProblem(back, "Garden", "garden", "http://x", "", "") == null,
                "a camera keeps its own name");
        java.util.List<Cameras.Camera> full = new java.util.ArrayList<>();
        for (int i = 0; i < Cameras.MAX_CAMERAS; i++) {
            full = Cameras.with(full, "", new Cameras.Camera("c" + i, "http://x"));
        }
        require(Cameras.with(full, "", new Cameras.Camera("new", "http://x")) == null,
                "an eleventh camera is refused");
        Object[] split = Cameras.splitLogin("http://admin:p%40ss:w@192.0.2.30:8080/video?x=a@b");
        Cameras.Login login = (Cameras.Login) split[1];
        require("http://192.0.2.30:8080/video?x=a@b".equals(split[0]) && login != null
                && login.user.equals("admin") && login.password.equals("p@ss:w"),
                "a sign-in in the address is taken out of it");
        require(Cameras.splitLogin("http://cam/a@b")[1] == null,
                "an @ after the host is not a sign-in");
        require(Cameras.hostOf("https://Cam.local/x").equals("cam.local:443")
                && Cameras.hostOf("http://cam:81/").equals("cam:81"), "host and port");
        java.util.Map<String, Cameras.Login> logins = Cameras.withLogin(
                new java.util.LinkedHashMap<>(), "", "Door", "admin", "secret");
        logins = Cameras.withLogin(logins, "Door", "Front", "admin", "");
        require(logins.get("front") != null && logins.get("front").password.equals("secret")
                && logins.get("door") == null, "a rename moves the sign-in; empty keeps it");
        require(Cameras.parseLogins(Cameras.formatLogins(logins)).get("front").user.equals("admin"),
                "the sign-ins round-trip");
        require(Cameras.withLogin(logins, "Front", "Front", "", "x").isEmpty(),
                "an emptied user removes the sign-in");
        require(Cameras.checkLogin("a:b", "x") != null, "a user with a colon is refused");
        require(Cameras.check("Web page", "http://x") == null,
                "Web page is a camera name like any other: the dashboard's page is its own choice");
        require(Cameras.checkFull("") == null && Cameras.checkFull("http://cam/main") == null
                && Cameras.checkFull("rtsp://cam/main") == null
                && Cameras.checkFull("ftp://cam/main") != null, "the full-screen address");
        // RTSP: the sign-in typed in the address is taken out like any other, and put back in
        // front of the host only when the stream is opened, encoded.
        Object[] rtsp = Cameras.splitLogin("rtsp://admin:p%40ss@192.0.2.5/Streaming/Channels/102");
        require("rtsp://192.0.2.5/Streaming/Channels/102".equals(rtsp[0])
                && ((Cameras.Login) rtsp[1]).password.equals("p@ss"), "an RTSP sign-in split out");
        require(Cameras.rtspAddress("rtsp://192.0.2.5/x", new Cameras.Login("ad min", "p@ss:w"))
                .equals("rtsp://ad%20min:p%40ss%3Aw@192.0.2.5/x"), "the sign-in put back, encoded");
        require(Cameras.rtspAddress("rtsp://192.0.2.5/x", null).equals("rtsp://192.0.2.5/x")
                && Cameras.rtspAddress("http://cam/x", new Cameras.Login("a", "b"))
                        .equals("http://cam/x"), "nothing added without a sign-in or to HTTP");
        require(Cameras.rtspAddress("rtsp://cam/x?u={user}&p={password}",
                new Cameras.Login("a", "b")).equals("rtsp://cam/x?u=a&p=b"),
                "a sign-in named in the address is filled there");
        require(Cameras.hostOf("rtsp://Cam/x").equals("cam:554"), "RTSP's own port");
        java.util.List<Cameras.Camera> withFull = Cameras.parse(Cameras.format(
                java.util.Collections.singletonList(new Cameras.Camera("D", "http://c/sub",
                        "http://c/main"))));
        require(withFull.get(0).full.equals("http://c/main")
                && Cameras.parse(Cameras.format(back)).get(0).full.isEmpty(),
                "the full-screen address round-trips, empty when none");
        require(Cameras.isLayout("1+5") && !Cameras.isLayout("5x5") && Cameras.hasBigTile("1+3")
                && !Cameras.hasBigTile("2x2"), "the layouts");
        java.util.List<Cameras.Camera> three = new java.util.ArrayList<>(back);
        three = Cameras.with(three, "", new Cameras.Camera("Drive", "http://cam/d"));
        java.util.List<Cameras.Group> groups = new java.util.ArrayList<>();
        require(Cameras.groupProblem(three, groups, "", "door", java.util.Arrays.asList("Door"))
                != null, "a group may not take a camera's name");
        require(Cameras.groupProblem(three, groups, "", "Outside", new java.util.ArrayList<>())
                != null, "a group needs a camera");
        groups = Cameras.withGroup(groups, "", new Cameras.Group("Outside",
                java.util.Arrays.asList("Drive", "Garden")));
        groups = Cameras.withGroup(groups, "", new Cameras.Group("Front",
                java.util.Arrays.asList("Door", "Drive")));
        require(Cameras.saveProblem(three, groups, "", "outside", "http://x", "", "", "") != null,
                "a camera may not take a group's name");
        java.util.List<Cameras.Group> again = Cameras.parseGroups(Cameras.formatGroups(groups));
        require(again.size() == 2 && again.get(1).cameras.get(1).equals("Drive"),
                "the groups round-trip, a camera in two of them");
        java.util.List<Cameras.Camera> outside = Cameras.resolve(three, again, "outside");
        require(outside.size() == 2 && outside.get(0).name.equals("Garden"),
                "a group shows its cameras in the list's order");
        require(Cameras.resolve(three, again, "Door").size() == 1
                && Cameras.resolve(three, again, "Nope").isEmpty(), "a camera, or nothing");
        java.util.List<Cameras.Group> moved = Cameras.afterCamera(again, "Drive", "Driveway");
        require(moved.get(0).cameras.contains("Driveway") && moved.get(1).cameras.contains("Driveway"),
                "a renamed camera follows in every group");
        require(!Cameras.afterCamera(again, "Drive", "").get(0).cameras.contains("Drive"),
                "a removed camera leaves its groups");
        Object[] named = Cameras.splitLogin("http://h/q.jpg?username=u&password=p%20w%26x");
        require("http://h/q.jpg?username={user}&password={password}".equals(named[0])
                && ((Cameras.Login) named[1]).user.equals("u")
                && ((Cameras.Login) named[1]).password.equals("p w&x"),
                "username= is read as the user, not skipped");
        Object[] alone = Cameras.splitLogin("http://h/q.jpg?password=pw");
        require(((Cameras.Login) alone[1]).user.isEmpty() && Cameras.saveProblem(
                new java.util.ArrayList<>(), "", "X", (String) alone[0], "", "pw") != null,
                "a password alone in the query is refused, not dropped");
        require(Cameras.saveProblem(new java.util.ArrayList<>(), "", "X", "http://h/",
                "", "pw") != null, "a password with no user is refused");
        Object[] colon = Cameras.splitLogin("http://:pw@h/x.jpg");
        require(Cameras.checkLogin(((Cameras.Login) colon[1]).user,
                ((Cameras.Login) colon[1]).password) != null, "a :pw@ address is refused");
        require(DigestAuth.parse(java.util.Collections.singletonList(
                "Digest realm=\"r\", nonce=\"n\", stale=TRUE")).stale
                && !DigestAuth.parse(java.util.Collections.singletonList(
                "Digest realm=\"r\", nonce=\"n\"")).stale, "stale is read");
        // RFC 2617's own example, then the same challenge with SHA-256 and with no qop.
        DigestAuth.Challenge rfc = DigestAuth.parse(java.util.Collections.singletonList(
                "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", "
                        + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
                        + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
        String answer = DigestAuth.header(rfc, "GET", "/dir/index.html", "Mufasa",
                "Circle Of Life", "0a4f113b", "00000001");
        require(answer.contains("response=\"6629fae49393a05397450978507c4ef1\"")
                && answer.contains("qop=auth, nc=00000001"), "the RFC 2617 digest");
        require(DigestAuth.parse(java.util.Arrays.asList("Basic realm=\"x\"",
                "Digest realm=\"r\", nonce=\"n\", algorithm=MD5",
                "Digest realm=\"r\", nonce=\"n\", algorithm=SHA-256")).algorithm.equals("SHA-256"),
                "the strongest algorithm is chosen");
        require(DigestAuth.parse(java.util.Collections.singletonList(
                "Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\"")) == null,
                "auth-int alone is not answered");
        require(DigestAuth.parse(java.util.Collections.singletonList("Basic realm=\"x\"")) == null,
                "Basic is not Digest");
        Object[] reolink = Cameras.splitLogin(
                "http://cam/cgi-bin/api.cgi?cmd=Snap&channel=0&user=admin&password=a%26b");
        require("http://cam/cgi-bin/api.cgi?cmd=Snap&channel=0&user={user}&password={password}"
                .equals(reolink[0]) && ((Cameras.Login) reolink[1]).password.equals("a&b"),
                "a sign-in in the query becomes {user} and {password}");
        require(Cameras.check("Door", (String) reolink[0]) == null, "placeholders pass the check");
        require(Cameras.fill((String) reolink[0], (Cameras.Login) reolink[1])
                .endsWith("&user=admin&password=a%26b"), "filled in, encoded, when fetched");
        require(Cameras.splitLogin("http://cam/x?user={user}&password={password}")[1] == null,
                "an address with placeholders is kept as it is");
        require(Cameras.fill("http://cam/x?p=%7Bpassword%7D", new Cameras.Login("u", "p w"))
                .equals("http://cam/x?p=p%20w"), "the browser's encoded placeholder is filled too");
        RecordingExecutor executor = new RecordingExecutor();
        require(KioskCommandDispatcher.dispatch("camera.trigger",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, " Door "), executor)
                .status.equals("accepted") && "Door".equals(executor.cameraName)
                && executor.cameraTrigger, "camera.trigger reaches the executor trimmed");
        require(KioskCommandDispatcher.dispatch("camera.show",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected"),
                "camera.show needs a name");
        java.util.Map<String, String> asFull = new java.util.HashMap<>();
        asFull.put("as", "fullscreen");
        require(KioskCommandDispatcher.dispatch("camera.show",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Door", null, asFull),
                executor).status.equals("accepted") && "fullscreen".equals(executor.cameraAs)
                && !executor.cameraTrigger, "camera.show as=fullscreen");
        require(KioskCommandDispatcher.dispatch("camera.show",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Door"), executor)
                .status.equals("accepted") && "popup".equals(executor.cameraAs),
                "camera.show is a pop-up by default");
        require(KioskCommandDispatcher.dispatch("dashboard.cameras",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, " Front "), executor)
                .status.equals("accepted") && "Front".equals(executor.dashboardCameras),
                "dashboard.cameras reaches the executor trimmed");
        executor.dashboardCameras = null;
        require(KioskCommandDispatcher.dispatch("dashboard.cameras",
                KioskCommandDispatcher.CommandArgs.EMPTY, executor).status.equals("rejected")
                && executor.dashboardCameras == null,
                "dashboard.cameras needs a name: the page is dashboard.show's, not an empty camera");
        require(KioskCommandDispatcher.dispatch("kiosk.set_cameras",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Front"), executor)
                .status.equals("unsupported"), "kiosk.set_cameras is gone, with no alias");
        java.util.Map<String, String> asBad = new java.util.HashMap<>();
        asBad.put("as", "sideways");
        require(KioskCommandDispatcher.dispatch("camera.show",
                new KioskCommandDispatcher.CommandArgs(-1, null, null, "Door", null, asBad),
                executor).status.equals("rejected"), "a bad as is refused");
    }

    /**
     * dashboard.show: the three keys reach the executor, anything else is refused; and the rule
     * every surface asks before switching, that the app and the cameras need a choice first.
     */
    private static void testDashboardShows() {
        RecordingExecutor executor = new RecordingExecutor();
        for (String value : new String[] {"page", "app", "cameras"}) {
            require(KioskCommandDispatcher.dispatch("dashboard.show",
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, value), executor)
                    .status.equals("accepted") && value.equals(executor.dashboardShows),
                    "dashboard.show " + value + " reaches the executor");
        }
        executor.dashboardShows = null;
        for (String value : new String[] {null, "", "App", "web", "Web page"}) {
            KioskCommandDispatcher.Result result = KioskCommandDispatcher.dispatch(
                    "dashboard.show",
                    new KioskCommandDispatcher.CommandArgs(-1, null, null, value), executor);
            require(result.status.equals("rejected") && !result.detail.isEmpty()
                    && executor.dashboardShows == null,
                    "dashboard.show refuses " + value + " with a reason, as a key and not a word");
        }
        require(KioskCommandDispatcher.dashboardShowProblem("page", "", false, false, false,
                false) == null,
                "the page always can");
        require(KioskCommandDispatcher.NO_APP_CHOSEN.equals(
                KioskCommandDispatcher.dashboardShowProblem("app", "", true, true, false, false)),
                "the app needs one chosen");
        require(KioskCommandDispatcher.dashboardShowProblem("app", "com.example", false, false,
                false, false)
                == null, "an app chosen can be shown");
        require(KioskCommandDispatcher.NO_CAMERA.equals(
                KioskCommandDispatcher.dashboardShowProblem("cameras", "x", false, false, false,
                        false)),
                "the cameras need a camera first");
        require(KioskCommandDispatcher.NO_CAMERA_CHOSEN.equals(
                KioskCommandDispatcher.dashboardShowProblem("cameras", "", true, false, false,
                        false)),
                "the cameras need one chosen that still exists");
        require(KioskCommandDispatcher.dashboardShowProblem("cameras", "", true, true, false,
                false) == null, "a chosen camera can be shown, with no app chosen");
        require(KioskCommandDispatcher.NO_PAGE_INTEGRATION.equals(
                KioskCommandDispatcher.dashboardShowProblem("integration", "", false, false,
                        false, false)), "an integration page needs the Bitcoin clock added");
        require(KioskCommandDispatcher.NO_INTEGRATION_CHOSEN.equals(
                KioskCommandDispatcher.dashboardShowProblem("integration", "", false, false,
                        true, false)), "an integration page needs one chosen");
        require(KioskCommandDispatcher.dashboardShowProblem("integration", "", false, false,
                true, true) == null, "a chosen integration can be shown");
        for (String gone : new String[] {"kiosk.home", "kiosk.set_url", "kiosk.open_url",
                "kiosk.reload", "kiosk.restart", "kiosk.identify", "kiosk.screenshot",
                "ambient.idle_seconds", "ambient.one_per_cycle"}) {
            require(KioskCommandDispatcher.dispatch(gone,
                    new KioskCommandDispatcher.CommandArgs(-1, "https://example.com", true, "1"),
                    executor).status.equals("unsupported"), gone + " is gone, with no alias");
        }
    }

    /**
     * The words every surface shows for a choice and the keys the commands carry: each table
     * goes both ways, every key is one the command takes, and the MQTT templates hold both.
     */
    private static void testWordsAndKeys() {
        String[][][] tables = {KioskCommandDispatcher.DASHBOARD_WORDS,
                KioskCommandDispatcher.ORIENTATION_WORDS, KioskCommandDispatcher.DISPLAY_OFF_WORDS,
                KioskCommandDispatcher.AMBIENT_MODE_WORDS,
                KioskCommandDispatcher.AMBIENT_ON_WAKE_WORDS,
                KioskCommandDispatcher.AMBIENT_TRANSITION_WORDS,
                KioskCommandDispatcher.AMBIENT_FIT_WORDS, KioskCommandDispatcher.AMBIENT_CORNER_WORDS};
        for (String[][] table : tables) {
            for (String[] pair : table) {
                require(pair[1].equals(KioskCommandDispatcher.wordOf(table, pair[0]))
                        && pair[0].equals(KioskCommandDispatcher.keyOf(table, pair[1])),
                        pair[0] + " and " + pair[1] + " go both ways");
                require(!pair[1].contains("'") && !pair[0].contains("'"),
                        pair[1] + " can stand in a template's quotes");
            }
        }
        require(KioskCommandDispatcher.keyOf(KioskCommandDispatcher.DASHBOARD_WORDS, "page") == null,
                "a key is not a word");
        require("Auto-rotate".equals(KioskCommandDispatcher.wordOf(
                KioskCommandDispatcher.ORIENTATION_WORDS, "auto"))
                && "Cut".equals(KioskCommandDispatcher.wordOf(
                        KioskCommandDispatcher.AMBIENT_TRANSITION_WORDS, "cut"))
                && "Ambient first".equals(KioskCommandDispatcher.wordOf(
                        KioskCommandDispatcher.AMBIENT_ON_WAKE_WORDS, "ambient")),
                "the panel's words");
        require("zoom".equals(KioskCommandDispatcher.wordOf(
                KioskCommandDispatcher.AMBIENT_FIT_WORDS, "zoom")), "an unknown key reads as itself");
        RecordingExecutor executor = new RecordingExecutor();
        String[][] commands = {{"dashboard.show"}, {"display.orientation"},
                {"display.off_method"}, {"ambient.mode"}, {"ambient.on_wake"},
                {"ambient.transition"}, {"ambient.picture_fit"}, {"ambient.credit_corner"}};
        for (int index = 0; index < tables.length; index++) {
            for (String[] pair : tables[index]) {
                require(!KioskCommandDispatcher.dispatch(commands[index][0],
                        new KioskCommandDispatcher.CommandArgs(-1, null, null, pair[0]), executor)
                        .detail.startsWith("value must"), commands[index][0] + " takes " + pair[0]);
            }
        }
        String command = KioskCommandDispatcher.selectCommandTemplate("dashboard.show",
                KioskCommandDispatcher.DASHBOARD_WORDS);
        require(command.contains("'Web page': 'page'") && command.contains("'Cameras': 'cameras'")
                && command.contains("\"command\":\"dashboard.show\""),
                "the command template sends the key: " + command);
        String value = KioskCommandDispatcher.selectValueTemplate("value_json.config.dashboard_shows",
                KioskCommandDispatcher.DASHBOARD_WORDS);
        require(value.contains("'page': 'Web page'")
                && value.contains("value_json.config.dashboard_shows"),
                "the value template shows the word: " + value);
        require("ambient_start_after".equals(KioskCommandDispatcher.entityKey("Ambient start after"))
                && "pop_up".equals(KioskCommandDispatcher.entityKey("Pop-up"))
                && "open_once".equals(KioskCommandDispatcher.entityKey("Open once"))
                && "nfc_tag".equals(KioskCommandDispatcher.entityKey("NFC tag"))
                && "show_door_1".equals(KioskCommandDispatcher.entityKey("Show Door-1 "))
                && "entity".equals(KioskCommandDispatcher.entityKey("--")),
                "a discovery key is its name, snake-cased");
    }

    private static class RecordingExecutor implements KioskCommandDispatcher.Executor {
        Popup.Request popup;
        String cameraName;
        boolean cameraTrigger;
        String cameraAs;
        String dashboardCameras;
        String dashboardShows;
        final java.util.Map<KioskCommandDispatcher.BrowserOption, String> browserOptions =
                new java.util.EnumMap<>(KioskCommandDispatcher.BrowserOption.class);
        Boolean lightOn;
        Integer lightColor;
        int lightPercent;
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

        boolean accelerometerPresent = true;
        String lastOrientation = null;

        @Override
        public boolean setOrientation(String value) {
            calls.add("setOrientation:" + value);
            lastOrientation = value;
            return accelerometerPresent || !"auto".equals(value);
        }

        boolean deviceOwner = true;
        String lastDisplayOffMethod = null;

        @Override
        public boolean setDisplayOffMethod(String value) {
            calls.add("setDisplayOffMethod:" + value);
            if ("sleep".equals(value) && !deviceOwner) {
                return false;
            }
            lastDisplayOffMethod = value;
            return true;
        }

        @Override
        public void setDashboardUrl(String url) {
            calls.add("setDashboardUrl");
            lastUrl = url;
        }

        String lastOnceUrl = null;

        @Override
        public void openUrlOnce(String url) {
            calls.add("openUrlOnce");
            lastOnceUrl = url;
        }

        @Override
        public void showMainDashboard() {
            calls.add("showMainDashboard");
        }

        Boolean lastWebAdminEnabled = null;

        /** Non-null stands in for a panel whose screensaver cannot run right now. */
        String screensaverProblem;
        final java.util.Map<KioskCommandDispatcher.ScreensaverSetting, String> screensaverSettings =
                new java.util.LinkedHashMap<>();

        @Override
        public String screensaverStart() {
            calls.add("screensaverStart");
            return screensaverProblem;
        }

        @Override
        public void screensaverStop() {
            calls.add("screensaverStop");
        }

        String playlistProblem;
        String lastPlaylist;

        @Override
        public String setScreensaverPlaylist(String name) {
            calls.add("setAmbientAlbum:" + name);
            if (playlistProblem != null) {
                return playlistProblem;
            }
            lastPlaylist = name;
            return null;
        }

        @Override
        public void setScreensaverSetting(KioskCommandDispatcher.ScreensaverSetting setting,
                String value) {
            calls.add("setAmbientSetting:" + setting + "=" + value);
            screensaverSettings.put(setting, value);
        }

        @Override
        public void setWebAdminEnabled(boolean enabled) {
            calls.add("setWebAdminEnabled");
            lastWebAdminEnabled = enabled;
        }

        @Override
        public String setBrowserOption(KioskCommandDispatcher.BrowserOption option, String value) {
            browserOptions.put(option, value);
            return null;
        }

        @Override
        public void showPopup(Popup.Request request) {
            popup = request;
        }

        @Override
        public String cameraShow(String name, String show) {
            cameraName = name;
            cameraTrigger = false;
            cameraAs = show;
            return null;
        }

        @Override
        public String setDashboardIntegration(String nameOrId) {
            return null;
        }

        @Override
        public String setDashboardCameras(String name) {
            dashboardCameras = name;
            return null;
        }

        @Override
        public String setDashboardShows(String value) {
            dashboardShows = value;
            return null;
        }

        @Override
        public String cameraTrigger(String name) {
            cameraName = name;
            cameraTrigger = true;
            return null;
        }

        @Override
        public void setStatusLight(boolean on, Integer color, int percent) {
            lightOn = on;
            lightColor = color;
            lightPercent = percent;
        }


        /** Null means "handed to a live session"; a message stands in for a down or absent broker. */
        String telemetryProblem = null;

        @Override
        public String setSensorEnabled(String id, boolean enabled) {
            calls.add("setSensorEnabled:" + id + ":" + enabled);
            return null;
        }

        public void setMediaVolume(int percent) {
            calls.add("setMediaVolume:" + percent);
        }

        public String playAudio(String url) {
            calls.add("playAudio:" + url);
            return null;
        }

        public String setAutomationEnabled(String id, boolean enabled) {
            calls.add("setAutomationEnabled:" + id + ":" + enabled);
            return null;
        }

        @Override
        public String setCameraMotion(boolean enabled) {
            calls.add("setCameraMotion:" + enabled);
            return null;
        }

        @Override
        public String cameraSnapshot() {
            calls.add("cameraSnapshot");
            return null;
        }

        String screenshotProblem;

        @Override
        public String screenshot() {
            calls.add("screenshot");
            return screenshotProblem;
        }

        String identifyProblem;

        @Override
        public String identify() {
            calls.add("identify");
            return identifyProblem;
        }

        @Override
        public String openDashboard() {
            calls.add("dashboard");
            return null;
        }

        @Override
        public String openSettings() {
            calls.add("settings");
            return null;
        }

        @Override
        public String leaveForHomeScreen() {
            calls.add("home_screen");
            return null;
        }

        @Override
        public String calibrateMicrophone(String step) {
            calls.add("calibrateMicrophone:" + step);
            return null;
        }

        @Override
        public String calibrateBeacons(String step, String beacon) {
            calls.add("calibrateBeacons:" + step + ":" + beacon);
            return null;
        }

        @Override
        public String calibrateProximity(String step) {
            calls.add("calibrateProximity:" + step);
            return null;
        }

        @Override
        public String sleepTest(String sensorId) {
            calls.add("sleepTest:" + sensorId);
            return null;
        }

        @Override
        public String say(String text) {
            calls.add("say:" + text);
            return null;
        }

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
