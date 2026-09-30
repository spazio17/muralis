/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.Map;

/** The shapes the fleet of 0.7 builds on: which settings are shared, and named lists. */
public final class FleetShapeTest {
    private FleetShapeTest() {
    }

    public static void main(String[] args) {
        testSharedSettings();
        testNamedList();
        System.out.println("FleetShapeTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void testSharedSettings() {
        require(SensorSettings.shared("movement_sensitivity"), "a sensitivity is shared");
        require(SensorSettings.shared("camera_fps") && SensorSettings.shared("camera_motion"),
                "the frame rate and motion detection are shared");
        require(!SensorSettings.shared("camera_size") && !SensorSettings.shared("camera_lens")
                && !SensorSettings.shared("camera_orientation"),
                "size, lens and orientation belong to one panel");
        require(!SensorSettings.shared("proximity_threshold")
                && !SensorSettings.shared("microphone_quiet_db"),
                "calibrations belong to one panel");
        require(!SensorSettings.shared("light_asleep"), "a test while asleep belongs to one panel");
        require(!SensorSettings.shared("beacons_uuid"), "a panel's own beacon is its own");
        require(!SensorSettings.shared("tag_seen_04F9AB7A2B4980"), "tags read belong to one panel");
        require(!SensorSettings.shared(null), "no key is not shared");
    }

    private static void testNamedList() {
        NamedList empty = NamedList.parse(null);
        require(empty.names().isEmpty() && NamedList.parse("broken").names().isEmpty(),
                "nothing stored or a broken document is an empty list");
        NamedList list = NamedList.parse(null);
        list.set("a", " Front door ", 1_000);
        list.set("b", "Keys", 1_000);
        NamedList back = NamedList.parse(list.store());
        require(back.name("a").equals("Front door") && back.name("b").equals("Keys"),
                "names survive the round trip, trimmed");
        require(back.store().contains("\"changed_at\":1000"), "each name carries its time");
        back.set("a", "Front door", 2_000);
        require(back.store().contains("\"changed_at\":1000"), "the same name keeps its time");
        back.set("a", "", 3_000);
        String stored = back.store();
        require(back.name("a").isEmpty() && stored.contains("\"deleted_at\":3000"),
                "a name taken away leaves a marker");
        back.set("a", "Back door", 4_000);
        require(!back.store().contains("\"deleted_at\":3000"), "a name given again drops the marker");
        Map<String, String> names = back.names();
        require(names.size() == 2, "two names");
        NamedList many = NamedList.parse(null);
        for (int index = 0; index < NamedList.MAX_DELETED + 5; index++) {
            many.set("x" + index, "n", index);
            many.set("x" + index, "", index + 1);
        }
        String capped = many.store();
        require(!capped.contains("\"x0\"") && capped.contains("\"x" + (NamedList.MAX_DELETED + 4) + "\""),
                "only the newest markers are kept");
    }
}
