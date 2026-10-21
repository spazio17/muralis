/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DiscoveryWithdrawalsTest {

    public static void main(String[] args) {
        testNewPanelRemovesNothing();
        testSwitchedOffIsRemovedOnce();
        testUnchangedAndAddedRemoveNothing();
        testOlderBuildsEntitiesAreRemoved();
        testPlatformChange();
        System.out.println("DiscoveryWithdrawalsTest passed");
    }

    private static void testNewPanelRemovesNothing() {
        // The rule of 2026-10-06: a panel starts on MQTT as new, so with nothing announced before
        // it must not send a single removal, whatever is off or missing on it.
        DiscoveryWithdrawals.Plan plan = DiscoveryWithdrawals.plan(map(),
                map("battery", "sensor", "reload", "button"));
        require(plan.removed.isEmpty() && plan.replaced.isEmpty(), "a new panel removed something");
    }

    private static void testSwitchedOffIsRemovedOnce() {
        Map<String, String> now = map("reload", "button");
        DiscoveryWithdrawals.Plan plan = DiscoveryWithdrawals.plan(
                map("sensor_light", "sensor", "camera_motion", "switch", "automation_ab12cd", "switch",
                        "reload", "button"), now);
        require(plan.removed.equals(map("sensor_light", "sensor", "camera_motion", "switch",
                "automation_ab12cd", "switch")), "wrong removals: " + plan.removed);
        // Once the payload without them was announced, the next one removes nothing again.
        require(DiscoveryWithdrawals.plan(now, now).removed.isEmpty(), "a removal was repeated");
    }

    private static void testUnchangedAndAddedRemoveNothing() {
        Map<String, String> both = map("battery", "sensor", "web_admin", "switch");
        require(DiscoveryWithdrawals.plan(both, new LinkedHashMap<>(both)).removed.isEmpty(),
                "an unchanged payload removed something");
        require(DiscoveryWithdrawals.plan(both,
                map("battery", "sensor", "web_admin", "switch", "sensor_movement", "binary_sensor"))
                .removed.isEmpty(), "an addition removed something");
    }

    private static void testOlderBuildsEntitiesAreRemoved() {
        // A panel updated from a build that announced "wake", read back from the broker's
        // retained payload: it goes out.
        DiscoveryWithdrawals.Plan plan = DiscoveryWithdrawals.plan(
                map("wake", "button", "display_on", "button"), map("display_on", "button"));
        require(plan.removed.equals(map("wake", "button")), "an older build's entity: " + plan.removed);
    }

    private static void testPlatformChange() {
        // Home Assistant identifies a component by key and platform, so the old platform's entity
        // must go under its old platform.
        DiscoveryWithdrawals.Plan plan = DiscoveryWithdrawals.plan(map("portrait", "switch"),
                map("portrait", "select"));
        require(plan.replaced.equals(map("portrait", "switch")) && plan.removed.isEmpty(),
                "platform change: " + plan.replaced + " " + plan.removed);
        // One map of every empty config to send, removals and platform changes alike.
        DiscoveryWithdrawals.Plan both = DiscoveryWithdrawals.plan(
                map("portrait", "switch", "wake", "button"), map("portrait", "select"));
        require(both.stubs.equals(map("portrait", "switch", "wake", "button")), "stubs: " + both.stubs);
    }

    private static Map<String, String> map(String... keysAndPlatforms) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndPlatforms.length; i += 2) {
            out.put(keysAndPlatforms[i], keysAndPlatforms[i + 1]);
        }
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
