/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a sensor's settings belong to every panel and which to one panel alone (2026-09-30,
 * the base for the fleet of 0.7). Not a word on any screen: a mark in the data, so the fleet
 * knows what it may push to every panel. One panel alone: what is measured on a device's
 * hardware (the calibrations, the test while asleep, the tags it read) and what follows the
 * device and how it is mounted (the camera's name, lens, size, orientation, mirror and upside
 * down; Juri: the size can differ between devices). Everything else is shared: the switches, sensitivities and still times, the
 * frame rate, the watermark, motion detection and its picture. Pure, for the host tests.
 */
final class SensorSettings {
    private SensorSettings() {
    }

    /** Settings of one panel alone, by their whole key. */
    static final Set<String> THIS_PANEL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "camera_name", "camera_lens", "camera_size", "camera_orientation", "camera_mirror",
            "camera_flip")));

    /** Settings of one panel alone, by the start of their key: calibrations and tags read. */
    static final List<String> THIS_PANEL_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "proximity_", "microphone_", "tag_seen_"));

    /** The test while asleep's verdict of a sensor, {@code <id>_asleep}: one panel alone. */
    static final String ASLEEP_SUFFIX = "_asleep";

    /** Whether a sensor setting, by its key without {@code sensor_option_}, is shared. */
    static boolean shared(String key) {
        if (key == null || THIS_PANEL.contains(key) || key.endsWith(ASLEEP_SUFFIX)) {
            return false;
        }
        for (String prefix : THIS_PANEL_PREFIXES) {
            if (key.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
