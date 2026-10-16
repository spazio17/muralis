/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which Home Assistant entities a panel must remove: the ones it announced before and does not
 * announce now, and only those.
 *
 * <p>Home Assistant keeps an entity it discovered until the panel says otherwise: a component left
 * out of the device payload stays, "unavailable", forever. The documented removal is the component
 * once more with only its platform ({@code "p"}), then an update without it. Until 2026-10-06 the
 * panel sent such an empty entry for every sensor that was off or missing, on every connect, the
 * fixed-list way Tasmota and FreeKiosk do it, so a panel that never had a humidity sensor told Home
 * Assistant to remove one. The owner's rule of that day: Muralis starts on MQTT as if it were used for
 * the first time, so a removal is only for something this panel announced. That is zigbee2mqtt's
 * way: it remembers what it published and, at its start, reads its own retained configs back from
 * the broker; {@code MqttController} does both and asks this class what to remove.
 *
 * <p>A component is the pair key and platform: a key announced under another platform before is
 * removed under the old one, or Home Assistant keeps the old entity beside the new.
 *
 * <p>Pure, with no Android imports, so the rule is covered by host tests.
 */
final class DiscoveryWithdrawals {

    private DiscoveryWithdrawals() {
    }

    /** What the next payload must remove, key to the platform it was announced under. */
    static final class Plan {
        /** Keys gone from the payload. */
        final Map<String, String> removed;
        /** Keys still announced, under another platform. */
        final Map<String, String> replaced;
        /** Both: every key that needs an empty config under its old platform. */
        final Map<String, String> stubs;

        Plan(Map<String, String> removed, Map<String, String> replaced) {
            this.removed = removed;
            this.replaced = replaced;
            Map<String, String> both = new LinkedHashMap<>(replaced);
            both.putAll(removed);
            this.stubs = both;
        }
    }

    /**
     * @param announced what was announced before, key to platform
     * @param now       what this payload announces
     */
    static Plan plan(Map<String, String> announced, Map<String, String> now) {
        Map<String, String> removed = new LinkedHashMap<>();
        Map<String, String> replaced = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : announced.entrySet()) {
            String platformNow = now.get(entry.getKey());
            if (platformNow == null) {
                removed.put(entry.getKey(), entry.getValue());
            } else if (!platformNow.equals(entry.getValue())) {
                replaced.put(entry.getKey(), entry.getValue());
            }
        }
        return new Plan(removed, replaced);
    }
}
