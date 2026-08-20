/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Samples the kernel's own counters for the on-screen overlay, the HTTP stats surface and MQTT
 * telemetry, so all three report identical numbers from one place.
 *
 * <p>Deliberately free of Android imports: the parsing and formatting are the parts that can be
 * wrong, and this way they are covered by host unit tests that need neither the container nor a
 * device. The read paths are plain Linux procfs/sysfs, which exist on the build host too.
 *
 * <p>Every read degrades to "unknown" ({@code -1} for integers, {@code NaN} for temperatures)
 * rather than throwing. That is not defensive habit, and it is no longer hypothetical: the ROM build
 * only got away with these reads because it ran SELinux <em>permissive</em>. Measured on the stock
 * Huawei MediaPad (API 26) on 2026-08-19, an ordinary app is in the {@code untrusted_app} domain and
 * the kernel refuses all three outright:
 *
 * <pre>
 *   avc: denied { read } for name="stat"    ... tclass=file permissive=0
 *   avc: denied { read } for name="loadavg" ... tclass=file permissive=0
 *   avc: denied { read } for name="thermal" ... tclass=dir  permissive=0
 * </pre>
 *
 * So on stock Android the CPU, load-average and thermal figures are simply unavailable, and the
 * overlay shows {@code --%} for CPU. That is the contract's "unsupported counter is null", not a bug.
 * Memory and storage keep working because they come from public Android APIs, not procfs.
 *
 * <p>Which is why {@link #readOrNull} latches: see the comment there. A wall panel runs for months,
 * and retrying a permanently-denied read every two seconds would write kernel audit records forever.
 */
final class SystemStats {
    static final long UNKNOWN = -1L;

    private static final String PROC_STAT = "/proc/stat";
    private static final String PROC_MEMINFO = "/proc/meminfo";
    private static final String PROC_LOADAVG = "/proc/loadavg";
    private static final String THERMAL_ROOT = "/sys/class/thermal";
    private static final String CPU_ROOT = "/sys/devices/system/cpu";
    private static final int MAX_PROC_BYTES = 128 * 1024;

    /**
     * How many consecutive failures before a path is abandoned for the life of the process.
     *
     * <p>More than one, because a transient failure is real: procfs entries can vanish mid-read, and
     * a thermal zone can disappear when a driver unbinds. Small, because a permission denial is
     * permanent and there is no point discovering that slowly.
     */
    private static final int MAX_READ_FAILURES = 3;

    private final String procRoot;
    private final String sysRoot;

    /** Paths that have failed {@link #MAX_READ_FAILURES} times running and are no longer attempted. */
    private final java.util.Set<String> abandonedPaths = new java.util.HashSet<>();
    private final Map<String, Integer> consecutiveFailures = new java.util.HashMap<>();

    private CpuTicks previousTicks;
    private List<String> cpuTemperaturePaths;
    private List<String> gpuTemperaturePaths;
    private List<String> cpuFrequencyPaths;

    SystemStats() {
        this("", "");
    }

    /** Test seam: prefixes every absolute path, so a fixture tree can stand in for procfs. */
    SystemStats(String procRoot, String sysRoot) {
        this.procRoot = procRoot;
        this.sysRoot = sysRoot;
    }

    /**
     * Reads one sample. CPU busy percent is a delta against the previous call, so the first sample
     * after construction reports it as unknown.
     */
    Sample sample() {
        Sample sample = new Sample();

        CpuTicks ticks = parseCpuTicks(readOrNull(procRoot + PROC_STAT));
        sample.cpuBusyPercent = busyPercent(previousTicks, ticks);
        if (ticks != null) {
            previousTicks = ticks;
        }

        Map<String, Long> meminfo = parseMeminfoKb(readOrNull(procRoot + PROC_MEMINFO));
        sample.memTotalKb = valueOrUnknown(meminfo, "MemTotal");
        sample.memAvailableKb = valueOrUnknown(meminfo, "MemAvailable");
        sample.swapTotalKb = valueOrUnknown(meminfo, "SwapTotal");
        sample.swapFreeKb = valueOrUnknown(meminfo, "SwapFree");

        sample.loadAverage = parseLoadAverage(readOrNull(procRoot + PROC_LOADAVG));

        if (cpuTemperaturePaths == null) {
            // Zone types never change at runtime, and there are ~30 of them on this SoC. Scanning
            // every zone on every tick would be the most expensive thing the overlay does.
            classifyThermalZones();
        }
        sample.cpuTemperatureC = maxTemperature(cpuTemperaturePaths);
        sample.gpuTemperatureC = maxTemperature(gpuTemperaturePaths);

        if (cpuFrequencyPaths == null) {
            cpuFrequencyPaths = findCpuFrequencyPaths();
        }
        sample.cpuMaxFrequencyKhz = maxLong(cpuFrequencyPaths);

        return sample;
    }

    /** One reading of everything this class can see. Unknown fields keep their sentinel value. */
    static final class Sample {
        double cpuBusyPercent = Double.NaN;
        long memTotalKb = UNKNOWN;
        long memAvailableKb = UNKNOWN;
        long swapTotalKb = UNKNOWN;
        long swapFreeKb = UNKNOWN;
        double cpuTemperatureC = Double.NaN;
        double gpuTemperatureC = Double.NaN;
        long cpuMaxFrequencyKhz = UNKNOWN;
        double[] loadAverage;

        long memUsedKb() {
            if (memTotalKb == UNKNOWN || memAvailableKb == UNKNOWN) {
                return UNKNOWN;
            }
            return memTotalKb - memAvailableKb;
        }

        long swapUsedKb() {
            if (swapTotalKb == UNKNOWN || swapFreeKb == UNKNOWN) {
                return UNKNOWN;
            }
            return swapTotalKb - swapFreeKb;
        }
    }

    /** Cumulative jiffies from the aggregate {@code cpu} line of /proc/stat. */
    static final class CpuTicks {
        final long total;
        final long idle;

        CpuTicks(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    static CpuTicks parseCpuTicks(String procStat) {
        if (procStat == null) {
            return null;
        }
        for (String line : procStat.split("\n")) {
            if (!line.startsWith("cpu ")) {
                continue;
            }
            String[] fields = line.trim().split("\\s+");
            long total = 0;
            long idle = 0;
            for (int index = 1; index < fields.length; index++) {
                long value;
                try {
                    value = Long.parseLong(fields[index]);
                } catch (NumberFormatException malformed) {
                    return null;
                }
                total += value;
                // Fields 4 and 5 are idle and iowait; a core waiting on I/O is not doing work.
                if (index == 4 || index == 5) {
                    idle += value;
                }
            }
            return total > 0 ? new CpuTicks(total, idle) : null;
        }
        return null;
    }

    static double busyPercent(CpuTicks previous, CpuTicks current) {
        if (previous == null || current == null) {
            return Double.NaN;
        }
        long totalDelta = current.total - previous.total;
        long idleDelta = current.idle - previous.idle;
        if (totalDelta <= 0 || idleDelta < 0) {
            // Counters only advance; anything else means we lost track (suspend, wrap, reorder).
            return Double.NaN;
        }
        double busy = 100.0 * (totalDelta - idleDelta) / totalDelta;
        return Math.max(0.0, Math.min(100.0, busy));
    }

    static Map<String, Long> parseMeminfoKb(String meminfo) {
        Map<String, Long> values = new HashMap<>();
        if (meminfo == null) {
            return values;
        }
        for (String line : meminfo.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String[] fields = line.substring(colon + 1).trim().split("\\s+");
            if (fields.length == 0) {
                continue;
            }
            try {
                values.put(key, Long.parseLong(fields[0]));
            } catch (NumberFormatException malformed) {
                // Skip the line rather than lose the whole file.
            }
        }
        return values;
    }

    static double[] parseLoadAverage(String loadavg) {
        if (loadavg == null) {
            return null;
        }
        String[] fields = loadavg.trim().split("\\s+");
        if (fields.length < 3) {
            return null;
        }
        try {
            return new double[] {
                Double.parseDouble(fields[0]),
                Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]),
            };
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * Normalises a raw thermal-zone reading to Celsius. Qualcomm zones on this SoC report
     * millidegrees ({@code 37000}), but the same sysfs interface is used elsewhere for tenths of a
     * degree and for whole degrees, and mixing them up would silently report 37000 °C or 3.7 °C.
     * Magnitude is the only reliable discriminator available.
     */
    static double thermalZoneToCelsius(long raw) {
        double value = raw;
        if (Math.abs(value) >= 10_000) {
            return value / 1000.0;
        }
        if (Math.abs(value) >= 1_000) {
            return value / 100.0;
        }
        if (Math.abs(value) >= 200) {
            return value / 10.0;
        }
        return value;
    }

    /**
     * Formats the compact overlay block. Kept here, beside the sampling, so the host tests cover
     * the arithmetic that turns kernel units into what a human reads on the wall.
     */
    static String formatOverlay(Sample sample, RuntimeFacts runtime) {
        StringBuilder text = new StringBuilder();
        text.append("CPU ").append(percent(sample.cpuBusyPercent));
        if (sample.cpuMaxFrequencyKhz != UNKNOWN) {
            text.append(' ').append(String.format(Locale.US, "%.2fGHz",
                    sample.cpuMaxFrequencyKhz / 1_000_000.0));
        }
        text.append("  T ").append(celsius(sample.cpuTemperatureC))
                .append('/').append(celsius(sample.gpuTemperatureC));
        if (sample.loadAverage != null) {
            text.append("  load ").append(String.format(Locale.US, "%.2f", sample.loadAverage[0]));
        }

        text.append('\n').append("RAM ").append(mib(sample.memUsedKb()))
                .append('/').append(mib(sample.memTotalKb));
        text.append("  zram ").append(mib(sample.swapUsedKb()))
                .append('/').append(mib(sample.swapTotalKb));

        text.append('\n').append("up ").append(duration(runtime.uptimeMs));
        if (runtime.batteryPercent >= 0) {
            text.append("  bat ").append(Math.round(runtime.batteryPercent)).append('%')
                    .append(' ').append(chargeStateShort(runtime));
        }
        if (runtime.wifiRssiDbm != UNKNOWN) {
            text.append("  wifi ").append(runtime.wifiRssiDbm).append("dBm");
        }
        if (runtime.ipAddress != null && !runtime.ipAddress.isEmpty()) {
            text.append('\n').append("ip ").append(runtime.ipAddress);
        }

        text.append('\n').append("renderer deaths ").append(runtime.rendererDeaths);
        text.append("  page ").append(runtime.lastPageFinishedAgoMs < 0
                ? "never" : duration(runtime.lastPageFinishedAgoMs) + " ago");
        if (pageErrorIsCurrent(runtime)) {
            text.append('\n').append("last error ").append(runtime.lastPageError);
        }
        return text.toString();
    }

    /**
     * The overlay as Android-flavoured HTML: dim labels, bright values, and each value coloured by
     * how worried you should be about it, the point of a RivaTuner-style readout is that a glance
     * from across the room tells you whether anything is wrong.
     *
     * <p>Formatted here rather than in the view so the thresholds are covered by host tests. Only
     * the tags {@code TextView} actually honours are used ({@code font color}, {@code b},
     * {@code br}); anything richer is silently dropped by the platform's HTML parser.
     */
    static String formatOverlayHtml(Sample sample, RuntimeFacts runtime) {
        StringBuilder html = new StringBuilder();

        row(html, "CPU", colored(percent(sample.cpuBusyPercent), cpuColor(sample.cpuBusyPercent))
                + (sample.cpuMaxFrequencyKhz == UNKNOWN ? "" : dim("  ")
                        + colored(String.format(Locale.US, "%.2fGHz",
                                sample.cpuMaxFrequencyKhz / 1_000_000.0), VALUE))
                + (sample.loadAverage == null ? "" : dim("  load ")
                        + colored(String.format(Locale.US, "%.2f", sample.loadAverage[0]), VALUE)));

        long memPercent = usedPercent(sample.memUsedKb(), sample.memTotalKb);
        row(html, "RAM", colored(mib(sample.memUsedKb()) + "/" + mib(sample.memTotalKb),
                memoryColor(memPercent))
                + (memPercent < 0 ? "" : dim("  ") + colored(memPercent + "%",
                        memoryColor(memPercent))));

        long swapPercent = usedPercent(sample.swapUsedKb(), sample.swapTotalKb);
        row(html, "ZRAM", colored(mib(sample.swapUsedKb()) + "/" + mib(sample.swapTotalKb),
                swapColor(swapPercent)));

        row(html, "TEMP", colored(celsius(sample.cpuTemperatureC),
                temperatureColor(sample.cpuTemperatureC))
                + dim("cpu  ") + colored(celsius(sample.gpuTemperatureC),
                        temperatureColor(sample.gpuTemperatureC)) + dim("gpu"));

        StringBuilder line = new StringBuilder();
        line.append(colored(duration(runtime.uptimeMs), VALUE)).append(dim(" up"));
        if (runtime.batteryPercent >= 0) {
            line.append(dim("   ")).append(colored(Math.round(runtime.batteryPercent) + "%",
                    batteryColor(runtime.batteryPercent, runtime.charging)));
            line.append(dim(" " + chargeStateShort(runtime)));
        }
        if (runtime.wifiRssiDbm != UNKNOWN) {
            line.append(dim("   ")).append(colored(runtime.wifiRssiDbm + "dBm",
                    wifiColor(runtime.wifiRssiDbm)));
        }
        row(html, "SYS", line.toString());

        if (runtime.ipAddress != null && !runtime.ipAddress.isEmpty()) {
            row(html, "IP", colored(escapeHtml(runtime.ipAddress), VALUE));
        }

        row(html, "WEB", colored(Integer.toString(runtime.rendererDeaths),
                runtime.rendererDeaths == 0 ? OK : BAD)
                + dim(" deaths   ")
                + colored(runtime.lastPageFinishedAgoMs < 0
                        ? "never" : duration(runtime.lastPageFinishedAgoMs) + " ago",
                        runtime.lastPageFinishedAgoMs < 0 ? WARN : VALUE));

        if (pageErrorIsCurrent(runtime)) {
            row(html, "ERR", colored(escapeHtml(clip(runtime.lastPageError, 40)), BAD));
        }
        return html.toString();
    }

    // Catppuccin Mocha, accents intensified, same palette as KioskTheme and the web admin page,
    // so the overlay reads as part of the product rather than a debug readout bolted on.
    private static final String OK = "#8EE88A";
    private static final String WARN = "#FFDF8F";
    private static final String BAD = "#FF6F91";
    private static final String VALUE = "#CDD6F4";
    private static final String LABEL = "#9399B2";

    private static void row(StringBuilder html, String label, String value) {
        if (html.length() > 0) {
            html.append("<br>");
        }
        html.append("<font color=\"").append(LABEL).append("\">").append(label)
                .append(' ').append("</font>").append(value);
    }

    private static String colored(String text, String color) {
        return "<font color=\"" + color + "\"><b>" + text + "</b></font>";
    }

    private static String dim(String text) {
        return "<font color=\"" + LABEL + "\">" + text.replace(" ", "&nbsp;") + "</font>";
    }

    /** Percentage of a total that is in use, or -1 when either figure is unknown. */
    static long usedPercent(long used, long total) {
        if (used == UNKNOWN || total <= 0) {
            return -1;
        }
        return Math.round(100.0 * used / total);
    }

    static String cpuColor(double busyPercent) {
        if (Double.isNaN(busyPercent)) {
            return LABEL;
        }
        // Sustained high CPU on a wall dashboard means something is spinning, not working.
        return busyPercent < 60 ? OK : busyPercent < 85 ? WARN : BAD;
    }

    static String memoryColor(long usedPercent) {
        if (usedPercent < 0) {
            return LABEL;
        }
        // The failure this ROM exists to prevent is the renderer being killed for memory, and lmkd
        // starts reclaiming well before the last megabyte, so amber lands early on purpose.
        return usedPercent < 70 ? OK : usedPercent < 88 ? WARN : BAD;
    }

    static String swapColor(long usedPercent) {
        if (usedPercent < 0) {
            return LABEL;
        }
        // zram in use is normal and healthy; near-full zram means the next step is a kill.
        return usedPercent < 50 ? OK : usedPercent < 80 ? WARN : BAD;
    }

    static String temperatureColor(double celsius) {
        if (Double.isNaN(celsius)) {
            return LABEL;
        }
        // This SoC throttles well before it is in danger; amber is "expect the dashboard to slow".
        return celsius < 55 ? OK : celsius < 70 ? WARN : BAD;
    }

    static String batteryColor(double percent, boolean charging) {
        if (percent < 0) {
            return LABEL;
        }
        if (charging) {
            return OK;
        }
        // A wall kiosk is normally powered; running down on battery is itself the warning.
        return percent > 50 ? WARN : BAD;
    }


    static String wifiColor(long rssiDbm) {
        if (rssiDbm == UNKNOWN) {
            return LABEL;
        }
        return rssiDbm > -67 ? OK : rssiDbm > -75 ? WARN : BAD;
    }

    private static String clip(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The Android-side facts the overlay shows next to the kernel counters. */
    static final class RuntimeFacts {
        long uptimeMs;
        double batteryPercent = -1;
        /** True while the battery is gaining charge, or is full on mains. */
        boolean charging;
        /** True whenever a charger is attached, which is not the same as gaining charge. */
        boolean plugged;
        /** True when the charger is attached and the battery has nothing left to take. */
        boolean full;
        long wifiRssiDbm = UNKNOWN;
        /** Address of the active network, so the admin page can be reached without adb. */
        String ipAddress = "";
        int rendererDeaths;
        long lastPageFinishedAgoMs = -1;
        String lastPageError = "";
        /** Age of {@link #lastPageError}. Needed to tell a current failure from an old one. */
        long lastPageErrorAgoMs = -1;
    }

    /**
     * Whether the last recorded page error is still the most recent thing that happened.
     *
     * <p>Without this the overlay showed a red {@code ERR net::ERR_NAME_NOT_RESOLVED} indefinitely,
     * including on a panel whose dashboard had loaded successfully one second earlier: the error was
     * simply the last error ever seen, with nothing clearing it. On a wall display that is worse than
     * showing nothing, because a permanent red line is either believed and acted on, or learned to be
     * ignored, and then a real failure is ignored too.
     *
     * <p>Ages are "how long ago", so a <em>smaller</em> number is more recent. An error with no
     * recorded time never displays.
     */
    static boolean pageErrorIsCurrent(RuntimeFacts runtime) {
        if (runtime.lastPageError == null || runtime.lastPageError.isEmpty()) {
            return false;
        }
        if (runtime.lastPageErrorAgoMs < 0) {
            return false;
        }
        // Never loaded successfully, so the error stands.
        if (runtime.lastPageFinishedAgoMs < 0) {
            return true;
        }
        return runtime.lastPageErrorAgoMs <= runtime.lastPageFinishedAgoMs;
    }

    /**
     * What the battery is doing, in words. A bare percentage does not say whether it is going up or
     * down, which on a wall panel is the more urgent half of the reading. "Charged" rather than
     * "charging" while plugged and full, because Android reports full as a charging state and a
     * panel that has been on mains for a week should not claim to still be filling up.
     */
    static String chargeStateLabel(RuntimeFacts runtime) {
        if (runtime.batteryPercent < 0) {
            return "";
        }
        // No cable, no charging, whatever any status field claims.
        if (!runtime.plugged) {
            return "discharging";
        }
        if (runtime.full) {
            return "charged";
        }
        return runtime.charging ? "charging" : "on hold";
    }

    /** The same states abbreviated for the on-glass overlay, where the line has to stay short. */
    static String chargeStateShort(RuntimeFacts runtime) {
        if (runtime.batteryPercent < 0) {
            return "";
        }
        if (!runtime.plugged) {
            return "bat";
        }
        return runtime.full ? "full" : runtime.charging ? "chg" : "hold";
    }

    static String percent(double value) {
        return Double.isNaN(value) ? "--%" : Math.round(value) + "%";
    }

    static String celsius(double value) {
        return Double.isNaN(value) ? "--" : String.format(Locale.US, "%.0f°", value);
    }

    static String mib(long kib) {
        return kib == UNKNOWN ? "--" : (kib / 1024) + "M";
    }

    static String duration(long millis) {
        if (millis < 0) {
            return "--";
        }
        long seconds = millis / 1000;
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        if (days > 0) {
            return days + "d" + hours + "h";
        }
        if (hours > 0) {
            return hours + "h" + minutes + "m";
        }
        return minutes + "m";
    }

    private void classifyThermalZones() {
        cpuTemperaturePaths = new ArrayList<>();
        gpuTemperaturePaths = new ArrayList<>();
        File root = new File(sysRoot + THERMAL_ROOT);
        File[] zones = root.listFiles();
        if (zones == null) {
            return;
        }
        for (File zone : zones) {
            if (!zone.getName().startsWith("thermal_zone")) {
                continue;
            }
            String type = readOrNull(new File(zone, "type").getPath());
            if (type == null) {
                continue;
            }
            type = type.trim().toLowerCase(Locale.US);
            String temperature = new File(zone, "temp").getPath();
            if (type.contains("gpu")) {
                gpuTemperaturePaths.add(temperature);
            } else if (type.contains("cpu")) {
                // Includes apc1-cpuN-usr and cpussN-usr on this SoC. Several zones track the same
                // silicon at different trip points, so the maximum is the honest number.
                cpuTemperaturePaths.add(temperature);
            }
        }
    }

    private List<String> findCpuFrequencyPaths() {
        List<String> paths = new ArrayList<>();
        File root = new File(sysRoot + CPU_ROOT);
        File[] entries = root.listFiles();
        if (entries == null) {
            return paths;
        }
        for (File entry : entries) {
            if (!entry.getName().matches("cpu[0-9]+")) {
                continue;
            }
            File frequency = new File(entry, "cpufreq/scaling_cur_freq");
            if (frequency.exists()) {
                paths.add(frequency.getPath());
            }
        }
        return paths;
    }

    private double maxTemperature(List<String> paths) {
        double highest = Double.NaN;
        if (paths == null) {
            return highest;
        }
        for (String path : paths) {
            long raw = readLong(path);
            if (raw == UNKNOWN) {
                continue;
            }
            double celsius = thermalZoneToCelsius(raw);
            if (Double.isNaN(highest) || celsius > highest) {
                highest = celsius;
            }
        }
        return highest;
    }

    private long maxLong(List<String> paths) {
        long highest = UNKNOWN;
        if (paths == null) {
            return highest;
        }
        for (String path : paths) {
            long value = readLong(path);
            if (value != UNKNOWN && value > highest) {
                highest = value;
            }
        }
        return highest;
    }

    private long readLong(String path) {
        String content = readOrNull(path);
        if (content == null) {
            return UNKNOWN;
        }
        try {
            return Long.parseLong(content.trim());
        } catch (NumberFormatException malformed) {
            return UNKNOWN;
        }
    }

    private static long valueOrUnknown(Map<String, Long> values, String key) {
        Long value = values.get(key);
        return value == null ? UNKNOWN : value;
    }

    /**
     * Reads a small procfs/sysfs file, returning null on any failure. Several of these report a
     * size of 0 and must be read to be measured, so the length is bounded explicitly instead.
     *
     * <p>Stops trying after {@link #MAX_READ_FAILURES} consecutive failures for a given path. On
     * stock Android the CPU and load-average reads are denied by SELinux permanently, and this
     * sampler runs every two seconds; without the latch an unattended panel would generate a kernel
     * audit record for each denied read, of the order of a million a month, for a number that is
     * never going to arrive. A single success resets the count, so a genuinely transient failure
     * does not cost the metric permanently.
     */
    String readOrNull(String path) {
        if (abandonedPaths.contains(path)) {
            return null;
        }
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                content.append(buffer, 0, read);
                if (content.length() > MAX_PROC_BYTES) {
                    break;
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            // Missing entry, SELinux denial, or a proc file that vanished mid-read.
            noteReadFailure(path);
            return null;
        }
        consecutiveFailures.remove(path);
        return content.toString();
    }

    private void noteReadFailure(String path) {
        int failures = consecutiveFailures.getOrDefault(path, 0) + 1;
        if (failures >= MAX_READ_FAILURES) {
            consecutiveFailures.remove(path);
            abandonedPaths.add(path);
        } else {
            consecutiveFailures.put(path, failures);
        }
    }

    /** Visible for host tests: whether this path has been latched off. */
    boolean hasAbandoned(String path) {
        return abandonedPaths.contains(path);
    }
}
