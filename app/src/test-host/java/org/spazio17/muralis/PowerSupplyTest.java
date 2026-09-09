/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The mains reader against synthetic power_supply trees, one per vendor layout seen or expected. */
public final class PowerSupplyTest {
    public static void main(String[] args) throws IOException {
        testLenovoLayoutHasNoLiveNumbers();
        testVoltageAndCurrentMakeWatts();
        testPowerNowWins();
        testOfflineAndBatteryAreIgnored();
        testMissingDirectory();
        System.out.println("PowerSupplyTest passed");
    }

    /** The Lenovo: the online USB port reports only a rating, so the result carries no numbers. */
    private static void testLenovoLayoutHasNoLiveNumbers() throws IOException {
        File root = tree();
        supply(root, "battery", "type", "Battery", "present", "1", "voltage_now", "4115404");
        supply(root, "bms", "type", "BMS", "voltage_now", "4109760");
        supply(root, "main", "type", "Main", "voltage_max", "4150000");
        supply(root, "pc_port", "type", "USB", "online", "1", "voltage_max", "5000000");
        supply(root, "usb", "type", "USB_PD", "online", "0", "voltage_now", "4489616");
        PowerSupply.Mains mains = PowerSupply.readMains(root);
        require(mains != null, "an online USB port is a mains supply");
        require(Double.isNaN(mains.volts) && Double.isNaN(mains.watts),
                "a rating must not be shown as a measurement");
    }

    private static void testVoltageAndCurrentMakeWatts() throws IOException {
        File root = tree();
        supply(root, "battery", "type", "Battery", "online", "1", "voltage_now", "3900000");
        supply(root, "usb", "type", "USB", "online", "1",
                "voltage_now", "5100000", "current_now", "-1450000");
        PowerSupply.Mains mains = PowerSupply.readMains(root);
        require(mains != null && Math.abs(mains.volts - 5.1) < 1e-9, "5100000 uV is 5.1 V");
        require(Math.abs(mains.watts - 7.395) < 1e-9, "5.1 V times 1.45 A is 7.395 W, sign ignored");
    }

    private static void testPowerNowWins() throws IOException {
        File root = tree();
        supply(root, "ac", "type", "Mains", "online", "1",
                "voltage_now", "12000000", "current_now", "500000", "power_now", "5500000");
        PowerSupply.Mains mains = PowerSupply.readMains(root);
        require(mains != null && Math.abs(mains.watts - 5.5) < 1e-9,
                "a reported power_now beats the product of volts and amps");
    }

    private static void testOfflineAndBatteryAreIgnored() throws IOException {
        File root = tree();
        supply(root, "battery", "type", "Battery", "online", "1", "voltage_now", "4000000");
        supply(root, "usb", "type", "USB", "online", "0", "voltage_now", "5000000");
        supply(root, "wireless", "type", "Wireless", "voltage_now", "9000000");
        require(PowerSupply.readMains(root) == null,
                "a battery, an offline port and a port without an online flag are not mains");
    }

    private static void testMissingDirectory() {
        require(PowerSupply.readMains(new File("/nonexistent/power_supply")) == null,
                "no class directory means no reading, not an exception");
    }

    private static File tree() throws IOException {
        File root = Files.createTempDirectory("power_supply").toFile();
        root.deleteOnExit();
        return root;
    }

    private static void supply(File root, String name, String... pairs) throws IOException {
        File dir = new File(root, name);
        require(dir.mkdir(), "temp supply " + name);
        for (int i = 0; i < pairs.length; i += 2) {
            Files.write(new File(dir, pairs[i]).toPath(),
                    (pairs[i + 1] + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
