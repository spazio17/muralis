/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * What the kernel says about the supply that is feeding the device, read from
 * {@code /sys/class/power_supply}, because no Android API says anything about a charger beyond
 * "a cable is attached".
 *
 * <p>Every device has its own set of nodes (the Lenovo: {@code battery}, {@code bms}, {@code main},
 * {@code pc_port}, {@code usb}), every vendor fills different files, and units are micro-units
 * throughout. The rule here is narrow on purpose: the supply that counts is one that is not a
 * battery and says {@code online} = 1, and only a live reading is a reading. {@code voltage_max}
 * is a rating, not a measurement, and is ignored: a row that said "5.0V" off a rating would be
 * claiming to measure something it did not. Where nothing live exists, as on the Lenovo, the
 * result carries no numbers and the row says "MAINS" alone. The numbers are whatever the kernel
 * measures at the device's own input, the DC side of the adapter or splitter: 5 V from USB, 12 or
 * 24 V from a DC or PoE feed, never the 230 V on the wall, which no device sees.
 *
 * <p>Pure Java, so a synthetic tree in a temporary directory tests every vendor layout on the host.
 */
final class PowerSupply {
    /** A supply that is feeding the device. Volts and watts are NaN where the kernel gives none. */
    static final class Mains {
        final double volts;
        final double watts;

        Mains(double volts, double watts) {
            this.volts = volts;
            this.watts = watts;
        }
    }

    private PowerSupply() {
    }

    /**
     * @param root the {@code power_supply} class directory
     * @return the online non-battery supply with the most to say, or null when none is online or
     *         the directory cannot be read (SELinux, or a device without the class)
     */
    static Mains readMains(File root) {
        File[] supplies = root.listFiles();
        if (supplies == null) {
            return null;
        }
        Mains best = null;
        int bestScore = -1;
        for (File supply : supplies) {
            String type = read(supply, "type");
            if (type == null || type.equalsIgnoreCase("Battery") || type.equalsIgnoreCase("BMS")) {
                continue;
            }
            if (!"1".equals(read(supply, "online"))) {
                continue;
            }
            double volts = positive(micro(read(supply, "voltage_now")));
            // Sign conventions differ between vendors for current and power; the magnitude is
            // the draw, and a zero reading is no reading.
            double watts = positive(Math.abs(micro(read(supply, "power_now"))));
            if (Double.isNaN(watts)) {
                double amps = positive(Math.abs(micro(read(supply, "current_now"))));
                if (!Double.isNaN(volts) && !Double.isNaN(amps)) {
                    watts = volts * amps;
                }
            }
            int score = (Double.isNaN(volts) ? 0 : 1) + (Double.isNaN(watts) ? 0 : 2);
            if (score > bestScore) {
                best = new Mains(volts, watts);
                bestScore = score;
            }
        }
        return best;
    }

    private static String read(File supply, String name) {
        try {
            return new String(Files.readAllBytes(new File(supply, name).toPath()),
                    StandardCharsets.US_ASCII).trim();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** A micro-unit integer as a unit value, NaN when absent or unparseable. */
    private static double micro(String text) {
        if (text == null || text.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Long.parseLong(text) / 1_000_000.0;
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    private static double positive(double value) {
        return value > 0 ? value : Double.NaN;
    }
}
