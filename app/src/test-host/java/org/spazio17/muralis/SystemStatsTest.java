/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Map;

public final class SystemStatsTest {
    public static void main(String[] args) throws Exception {
        testCpuTicks();
        testMeminfo();
        testThermalScaling();
        testFormatting();
        testColorThresholds();
        testOverlayHtml();
        testFixtureTreeSample();
        testReadFailureLatch();
        testStalePageErrorIsHidden();
        System.out.println("SystemStatsTest passed");
    }

    private static void testCpuTicks() {
        // Real /proc/stat shape: the aggregate line, then per-core lines that must be ignored.
        String procStat = "cpu  1000 100 500 8000 200 0 50 0 0 0\n"
                + "cpu0 250 25 125 2000 50 0 12 0 0 0\n"
                + "intr 12345\n";
        SystemStats.CpuTicks first = SystemStats.parseCpuTicks(procStat);
        require(first != null, "aggregate cpu line was not parsed");
        require(first.total == 9850, "total jiffies wrong: " + first.total);
        require(first.idle == 8200, "idle+iowait wrong: " + first.idle);

        require(Double.isNaN(SystemStats.busyPercent(null, first)),
                "first sample must report unknown, not 0%");

        // 100 further jiffies, 75 of them idle -> 25% busy.
        SystemStats.CpuTicks second = new SystemStats.CpuTicks(9950, 8275);
        double busy = SystemStats.busyPercent(first, second);
        require(Math.abs(busy - 25.0) < 0.001, "busy percent wrong: " + busy);

        // A counter that went backwards means we lost track; reporting 0% would be a lie.
        require(Double.isNaN(SystemStats.busyPercent(second, first)),
                "backwards counters must report unknown");
        require(Double.isNaN(SystemStats.busyPercent(first, first)),
                "zero elapsed time must report unknown");
        require(SystemStats.parseCpuTicks("cpu  x y z\n") == null,
                "malformed numbers must not yield a sample");
        require(SystemStats.parseCpuTicks(null) == null, "null input must not throw");
    }

    private static void testMeminfo() {
        String meminfo = "MemTotal:        1908756 kB\n"
                + "MemFree:          123456 kB\n"
                + "MemAvailable:    1006300 kB\n"
                + "SwapTotal:       1048572 kB\n"
                + "SwapFree:        1016716 kB\n"
                + "Hugepagesize:       2048 kB\n";
        Map<String, Long> values = SystemStats.parseMeminfoKb(meminfo);
        require(values.get("MemTotal") == 1908756L, "MemTotal wrong");
        require(values.get("MemAvailable") == 1006300L, "MemAvailable wrong");
        require(values.get("SwapFree") == 1016716L, "SwapFree wrong");
        require(SystemStats.parseMeminfoKb(null).isEmpty(), "null meminfo must not throw");

        double[] load = SystemStats.parseLoadAverage("4.34 1.34 0.47 2/612 3210");
        require(load != null && Math.abs(load[0] - 4.34) < 0.001, "load average wrong");
        require(SystemStats.parseLoadAverage("garbage") == null, "short loadavg must be null");
    }

    private static void testThermalScaling() {
        // This SoC reports millidegrees; the same sysfs interface is used elsewhere for tenths and
        // whole degrees, and confusing them would show 37000 degrees on the wall.
        require(Math.abs(SystemStats.thermalZoneToCelsius(37000) - 37.0) < 0.001,
                "millidegree scaling wrong");
        require(Math.abs(SystemStats.thermalZoneToCelsius(3700) - 37.0) < 0.001,
                "centidegree scaling wrong");
        require(Math.abs(SystemStats.thermalZoneToCelsius(370) - 37.0) < 0.001,
                "decidegree scaling wrong");
        require(Math.abs(SystemStats.thermalZoneToCelsius(37) - 37.0) < 0.001,
                "whole-degree scaling wrong");
    }

    private static void testFormatting() {
        require(SystemStats.percent(Double.NaN).equals("--%"), "unknown percent must not read 0%");
        require(SystemStats.celsius(Double.NaN).equals("--"), "unknown temperature must not read 0");
        require(SystemStats.mib(SystemStats.UNKNOWN).equals("--"), "unknown memory must not read 0M");
        require(SystemStats.mib(1_048_576).equals("1024M"), "MiB conversion wrong");
        require(SystemStats.duration(-1).equals("--"), "unknown duration wrong");
        require(SystemStats.duration(90_000).equals("1m"), "minute duration wrong");
        require(SystemStats.duration(3_600_000 * 5 + 60_000 * 7).equals("5h7m"),
                "hour duration wrong");
        require(SystemStats.duration(86_400_000 * 3 + 3_600_000 * 4).equals("3d4h"),
                "day duration wrong");

        SystemStats.Sample sample = new SystemStats.Sample();
        sample.cpuBusyPercent = 23.4;
        sample.memTotalKb = 1_908_756;
        sample.memAvailableKb = 1_006_300;
        sample.swapTotalKb = 1_048_572;
        sample.swapFreeKb = 1_016_716;
        sample.cpuTemperatureC = 39.0;
        sample.gpuTemperatureC = 36.0;
        sample.cpuMaxFrequencyKhz = 1_497_600;
        sample.loadAverage = new double[] {1.25, 1.0, 0.8};

        SystemStats.RuntimeFacts facts = new SystemStats.RuntimeFacts();
        facts.appUptimeMs = 3_600_000 * 26;
        facts.batteryPercent = 87.0;
        facts.charging = true;
        // A charging panel is a plugged-in panel; the cable is what the label keys off.
        facts.plugged = true;
        facts.wifiRssiDbm = -52;
        facts.rendererDeaths = 2;
        facts.lastRendererDeathAgoMs = 300_000;
        facts.lastPageFinishedAgoMs = 120_000;

        // Charge state, which a bare percentage cannot express. "charging" alone was wrong twice
        // over: it stayed on after the cable was pulled, and it claimed a full panel on mains was
        // still filling up.
        SystemStats.RuntimeFacts charge = new SystemStats.RuntimeFacts();
        charge.batteryPercent = 50;
        charge.plugged = true;
        charge.charging = true;
        require(SystemStats.chargeStateLabel(charge).equals("charging"), "plugged and rising");
        charge.full = true;
        require(SystemStats.chargeStateLabel(charge).equals("charged"), "full on mains is charged");
        charge.full = false;
        charge.charging = false;
        require(SystemStats.chargeStateLabel(charge).equals("on hold"), "plugged, taking nothing");
        charge.plugged = false;
        require(SystemStats.chargeStateLabel(charge).equals("discharging"), "cable pulled");
        // This tablet keeps reporting BATTERY_STATUS_CHARGING after the cable is pulled, so the
        // cable has to win over the status field or the panel claims to be charging on battery.
        charge.charging = true;
        charge.full = true;
        require(SystemStats.chargeStateLabel(charge).equals("discharging"),
                "no cable beats a stale charging status");
        charge.charging = false;
        charge.full = false;
        require(SystemStats.chargeStateLabel(charge).equals("discharging"),
                "no cable, no charging claim");
        charge.batteryPercent = -1;
        require(SystemStats.chargeStateLabel(charge).isEmpty(), "no reading, no claim");

        String overlay = SystemStats.formatOverlay(sample, facts);
        require(overlay.contains("CPU 23%"), "cpu missing from overlay: " + overlay);
        require(overlay.contains("1.50GHz"), "frequency missing from overlay: " + overlay);
        require(overlay.contains("ZRAM"), "zram missing from overlay: " + overlay);
        require(overlay.contains("UP 1d2h"), "uptime missing from overlay: " + overlay);
        // Spelled out, not abbreviated: "chg" and "bat" were a guess dressed as a reading.
        require(overlay.contains("BAT 87% charging"), "battery missing from overlay: " + overlay);
        require(overlay.contains("-52dBm"), "wifi missing from overlay: " + overlay);
        require(overlay.contains("WEB 2 deaths  5m ago"), "renderer deaths missing: " + overlay);

        // The row order is a contract, not an accident: the web admin's System stats box renders
        // the same eight in the same sequence, and somebody comparing the panel with the admin
        // page has to be reading the same thing in the same place. See formatOverlayHtml.
        String[] labels = {"CPU ", "RAM ", "ZRAM ", "TEMP ", "BAT ", "IP ", "WEB ", "UP "};
        String[] rows = overlay.split("\n");
        require(rows.length == labels.length,
                "expected " + labels.length + " overlay rows, got " + rows.length + ": " + overlay);
        for (int i = 0; i < labels.length; i++) {
            require(rows[i].startsWith(labels[i]),
                    "row " + i + " should start with " + labels[i] + ": " + rows[i]);
        }

        // An all-unknown sample is what an enforcing-SELinux device would produce. It must still
        // format, and must not invent zeroes.
        String unknown = SystemStats.formatOverlay(
                new SystemStats.Sample(), new SystemStats.RuntimeFacts());
        require(unknown.contains("CPU --%"), "unknown sample formatted as a number: " + unknown);
        require(unknown.contains("WEB 0 deaths  never"),
                "a WebView that has never died misreported: " + unknown);
        require(unknown.split("\n").length == labels.length,
                "an unknown sample must still produce every row: " + unknown);
        require(unknown.contains("BAT --") && unknown.contains("IP --"),
                "missing figures must read -- rather than drop their row: " + unknown);

        // A PoE panel, or a screen on its mains adapter: no battery to read, and the platform's
        // 0 % / "charging" for it must not appear on any surface.
        SystemStats.RuntimeFacts mains = new SystemStats.RuntimeFacts();
        mains.batteryPresent = false;
        mains.batteryPercent = 0;
        mains.plugged = true;
        String poe = SystemStats.formatOverlay(new SystemStats.Sample(), mains);
        require(poe.contains("\nMAINS\n") && !poe.contains("0%") && !poe.contains("BAT"),
                "a panel without a battery reads MAINS alone when nothing is measured: " + poe);
        String poeHtml = SystemStats.formatOverlayHtml(new SystemStats.Sample(), mains);
        require(poeHtml.contains("MAINS") && !poeHtml.contains("0%") && !poeHtml.contains("BAT"),
                "the HTML overlay must say MAINS too: " + poeHtml);
        mains.mainsVolts = 5.1;
        mains.mainsWatts = 7.395;
        String measured = SystemStats.formatOverlay(new SystemStats.Sample(), mains);
        require(measured.contains("MAINS 5.1V 7.4W"),
                "measured volts and watts follow the word, one decimal each: " + measured);
        mains.mainsWatts = Double.NaN;
        require(SystemStats.formatOverlay(new SystemStats.Sample(), mains).contains("MAINS 5.1V\n"),
                "volts without watts shows volts alone");

        require(SystemStats.powerSource(false, 1).equals("mains"), "no cell is mains, whatever feeds it");
        require(SystemStats.powerSource(false, 0).equals("mains"), "no cell and no cable reported is still mains");
        require(SystemStats.powerSource(true, 1).equals("mains") && SystemStats.powerSource(true, 2).equals("mains"),
                "a cell on an AC or USB charger is mains too");
        require(SystemStats.powerSource(true, 4).equals("wireless"), "a cell on an induction pad is wireless");
        require(SystemStats.powerSource(true, 0).equals("battery"), "a cell with no cable is on battery");
    }

    private static void testColorThresholds() {
        String ok = SystemStats.cpuColor(10);
        String warn = SystemStats.cpuColor(70);
        String bad = SystemStats.cpuColor(95);
        require(!ok.equals(warn) && !warn.equals(bad), "cpu bands must be distinguishable");
        require(SystemStats.cpuColor(Double.NaN).equals(SystemStats.memoryColor(-1)),
                "unknown values should share the neutral label colour");

        require(SystemStats.memoryColor(50).equals(ok), "half-used memory should read healthy");
        require(SystemStats.memoryColor(75).equals(warn), "75% memory should warn");
        require(SystemStats.memoryColor(95).equals(bad), "95% memory should alarm");

        // zram being in use at all is normal, so its healthy band reaches further than RAM's.
        require(SystemStats.swapColor(45).equals(ok), "moderate zram use should read healthy");
        require(SystemStats.swapColor(90).equals(bad), "nearly full zram should alarm");

        require(SystemStats.temperatureColor(40).equals(ok), "40C should read healthy");
        require(SystemStats.temperatureColor(60).equals(warn), "60C should warn");
        require(SystemStats.temperatureColor(80).equals(bad), "80C should alarm");

        // A wall kiosk should be on mains: discharging is a warning even at a high percentage.
        require(SystemStats.batteryColor(90, true).equals(ok), "charging should read healthy");
        require(SystemStats.batteryColor(90, false).equals(warn), "discharging should warn");
        require(SystemStats.batteryColor(20, false).equals(bad), "low battery should alarm");

        require(SystemStats.wifiColor(-50).equals(ok), "strong wifi should read healthy");
        require(SystemStats.wifiColor(-80).equals(bad), "weak wifi should alarm");

        require(SystemStats.usedPercent(902_456, 1_908_756) == 47,
                "used percent wrong: " + SystemStats.usedPercent(902_456, 1_908_756));
        require(SystemStats.usedPercent(SystemStats.UNKNOWN, 100) == -1,
                "unknown used must not compute a percentage");
        require(SystemStats.usedPercent(10, 0) == -1, "zero total must not divide");
    }

    private static void testOverlayHtml() {
        SystemStats.Sample sample = new SystemStats.Sample();
        sample.cpuBusyPercent = 92.0;
        sample.memTotalKb = 1_908_756;
        sample.memAvailableKb = 100_000;
        sample.swapTotalKb = 1_048_572;
        sample.swapFreeKb = 1_000_000;
        sample.cpuTemperatureC = 41.0;
        sample.gpuTemperatureC = 39.0;

        SystemStats.RuntimeFacts facts = new SystemStats.RuntimeFacts();
        facts.appUptimeMs = 7_200_000;
        facts.rendererDeaths = 3;
        facts.lastPageError = "HTTP 502";
        // The error must carry an age now: an error with no timestamp is treated as not current,
        // because that is how a page error looks before anything has recorded when it happened.
        facts.lastPageErrorAgoMs = 5_000;
        facts.lastPageFinishedAgoMs = 60_000;

        String html = SystemStats.formatOverlayHtml(sample, facts);
        require(html.contains("CPU"), "cpu row missing: " + html);
        require(html.split("<br>").length == 9,
                "eight rows plus the error row expected: " + html);
        require(html.indexOf("BAT") < html.indexOf("IP")
                        && html.indexOf("IP") < html.indexOf("WEB")
                        && html.indexOf("WEB") < html.indexOf("UP"),
                "rows are out of order: " + html);
        require(html.contains(SystemStats.cpuColor(92.0)), "cpu value not coloured by threshold");
        require(html.contains(SystemStats.memoryColor(95)), "near-full memory not coloured red");
        require(html.contains("ERR"), "error row missing when an error is recorded");
        require(html.contains("HTTP 502"), "error text missing: " + html);
        require(html.contains("<br>"), "rows should be separated by line breaks");
        require(!html.contains("<div"), "only TextView-supported tags may be emitted");

        // An all-unknown sample is the enforcing-SELinux case; it must still produce rows.
        String unknown = SystemStats.formatOverlayHtml(
                new SystemStats.Sample(), new SystemStats.RuntimeFacts());
        require(unknown.contains("--%"), "unknown cpu should render as --%: " + unknown);
        require(!unknown.contains("ERR"), "no error row should appear without an error");

        // Angle brackets from a page error must not be able to inject markup into the overlay.
        SystemStats.RuntimeFacts injected = new SystemStats.RuntimeFacts();
        injected.lastPageError = "<b>bold</b>";
        // Needs a timestamp to be considered current, and therefore to be rendered at all; see
        // testStalePageErrorIsHidden. Without one the escaping would never be exercised.
        injected.lastPageErrorAgoMs = 1_000;
        String escaped = SystemStats.formatOverlayHtml(new SystemStats.Sample(), injected);
        require(escaped.contains("&lt;b&gt;"), "page error was not escaped: " + escaped);
    }

    private static void testFixtureTreeSample() throws Exception {
        // Exercises the real read path against a fixture tree, so path handling and zone
        // classification are covered without depending on the host's own hardware.
        File root = Files.createTempDirectory("kiosk-stats").toFile();
        File proc = new File(root, "proc");
        require(proc.mkdirs(), "fixture proc dir");
        write(new File(proc, "stat"), "cpu  1000 100 500 8000 200 0 50 0 0 0\n");
        write(new File(proc, "meminfo"), "MemTotal: 1908756 kB\nMemAvailable: 1006300 kB\n"
                + "SwapTotal: 1048572 kB\nSwapFree: 1016716 kB\n");
        write(new File(proc, "loadavg"), "0.50 0.40 0.30 1/500 900\n");

        File thermal = new File(root, "sys/class/thermal");
        require(thermal.mkdirs(), "fixture thermal dir");
        makeZone(thermal, "thermal_zone0", "gpu-usr", "36000");
        makeZone(thermal, "thermal_zone1", "apc1-cpu0-usr", "38000");
        makeZone(thermal, "thermal_zone2", "apc1-cpu1-usr", "41000");
        makeZone(thermal, "thermal_zone3", "battery", "30000");

        File cpu0 = new File(root, "sys/devices/system/cpu/cpu0/cpufreq");
        require(cpu0.mkdirs(), "fixture cpufreq dir");
        write(new File(cpu0, "scaling_cur_freq"), "1497600\n");

        SystemStats stats = new SystemStats(root.getPath(), root.getPath());
        SystemStats.Sample first = stats.sample();
        require(Double.isNaN(first.cpuBusyPercent), "first fixture sample should lack a delta");
        require(first.memTotalKb == 1_908_756, "fixture MemTotal wrong");
        require(first.swapUsedKb() == 31_856, "fixture zram used wrong: " + first.swapUsedKb());
        require(Math.abs(first.cpuTemperatureC - 41.0) < 0.001,
                "hottest CPU zone should win: " + first.cpuTemperatureC);
        require(Math.abs(first.gpuTemperatureC - 36.0) < 0.001, "gpu zone wrong");
        require(first.cpuMaxFrequencyKhz == 1_497_600, "cpu frequency wrong");
        require(first.loadAverage != null, "fixture load average missing");

        write(new File(proc, "stat"), "cpu  1050 100 550 8075 200 0 50 0 0 0\n");
        SystemStats.Sample second = stats.sample();
        require(Math.abs(second.cpuBusyPercent - 57.14) < 0.1,
                "delta busy percent wrong: " + second.cpuBusyPercent);

        // A tree with nothing readable is the enforcing-SELinux case: unknown, not a crash.
        SystemStats missing = new SystemStats(root.getPath() + "/absent",
                root.getPath() + "/absent");
        SystemStats.Sample empty = missing.sample();
        require(empty.memTotalKb == SystemStats.UNKNOWN, "missing meminfo must be unknown");
        require(Double.isNaN(empty.cpuTemperatureC), "missing thermal must be unknown");
    }

    /**
     * A permanently unreadable path must be abandoned, and a readable one must never be.
     *
     * <p>Guards the fix for a real condition on the stock MediaPad: SELinux denies /proc/stat and
     * /proc/loadavg to an ordinary app, the sampler runs every two seconds, and before the latch that
     * meant a kernel audit record every two seconds for the life of the device. A regression here is
     * invisible on a desk and expensive on a wall, which is exactly what a unit test is for.
     */
    /**
     * A page error older than the last successful load must not be displayed.
     *
     * <p>Guards a bug seen on the wall panel 2026-08-19: the overlay showed a red
     * {@code ERR -2 net::ERR_NAME_NOT_RESOLVED} while the dashboard was loading fine, because the
     * error was simply the last error ever recorded and nothing cleared it. A permanently red panel is
     * either believed and acted on, or learned and ignored, and then a real failure is ignored too.
     */
    private static void testStalePageErrorIsHidden() {
        SystemStats.Sample sample = new SystemStats.Sample();
        SystemStats.RuntimeFacts stale = new SystemStats.RuntimeFacts();
        stale.lastPageError = "-2 net::ERR_NAME_NOT_RESOLVED";
        stale.lastPageErrorAgoMs = 600_000;   // ten minutes ago
        stale.lastPageFinishedAgoMs = 1_000;  // succeeded one second ago
        require(!SystemStats.pageErrorIsCurrent(stale), "an error older than the last load is stale");
        require(!SystemStats.formatOverlayHtml(sample, stale).contains("ERR"),
                "a stale error must not be drawn");
        require(!SystemStats.formatOverlay(sample, stale).contains("ERR "),
                "a stale error must not appear in the text overlay either");

        // The reverse: an error newer than the last successful load is real and must show.
        SystemStats.RuntimeFacts current = new SystemStats.RuntimeFacts();
        current.lastPageError = "HTTP 502";
        current.lastPageErrorAgoMs = 2_000;
        current.lastPageFinishedAgoMs = 90_000;
        require(SystemStats.pageErrorIsCurrent(current), "an error newer than the last load is current");
        require(SystemStats.formatOverlayHtml(sample, current).contains("HTTP 502"),
                "a current error must be drawn");

        // Never loaded at all: the error is all the information there is, so it shows.
        SystemStats.RuntimeFacts neverLoaded = new SystemStats.RuntimeFacts();
        neverLoaded.lastPageError = "-2 net::ERR_NAME_NOT_RESOLVED";
        neverLoaded.lastPageErrorAgoMs = 4_000;
        neverLoaded.lastPageFinishedAgoMs = -1;
        require(SystemStats.pageErrorIsCurrent(neverLoaded),
                "with no successful load, the error must show");

        // No recorded time: not current, so nothing is drawn from a bare string.
        SystemStats.RuntimeFacts untimed = new SystemStats.RuntimeFacts();
        untimed.lastPageError = "something";
        require(!SystemStats.pageErrorIsCurrent(untimed), "an untimed error is not current");
    }

    private static void testReadFailureLatch() throws Exception {
        SystemStats stats = new SystemStats();
        String missing = "/proc/definitely-not-a-real-entry-" + SystemStatsTest.class.getName();

        require(!stats.hasAbandoned(missing), "nothing should be abandoned before any read");
        for (int attempt = 1; attempt <= 2; attempt++) {
            require(stats.readOrNull(missing) == null, "missing path must read as null");
            require(!stats.hasAbandoned(missing),
                    "must not abandon after only " + attempt + " failures; transient reads happen");
        }
        require(stats.readOrNull(missing) == null, "third read still null");
        require(stats.hasAbandoned(missing), "three consecutive failures must latch the path off");

        // A file that exists must be read every time, and a success must clear a partial count.
        File real = File.createTempFile("kiosk-stats-latch", ".txt");
        try {
            write(real, "42\n");
            for (int attempt = 0; attempt < MAX_ATTEMPTS_BEYOND_LATCH; attempt++) {
                require("42\n".equals(stats.readOrNull(real.getPath())),
                        "a readable path must keep being read");
                require(!stats.hasAbandoned(real.getPath()),
                        "a readable path must never be abandoned");
            }
        } finally {
            require(real.delete(), "temp file cleanup");
        }
    }

    /** Comfortably more than the latch threshold, so a wrong threshold cannot pass by luck. */
    private static final int MAX_ATTEMPTS_BEYOND_LATCH = 6;

    private static void makeZone(File thermal, String name, String type, String temperature)
            throws Exception {
        File zone = new File(thermal, name);
        require(zone.mkdirs(), "fixture zone dir " + name);
        write(new File(zone, "type"), type + "\n");
        write(new File(zone, "temp"), temperature + "\n");
    }

    private static void write(File file, String content) throws Exception {
        try (Writer writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
