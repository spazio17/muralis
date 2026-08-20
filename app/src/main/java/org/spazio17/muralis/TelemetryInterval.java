/*
 * Copyright 2026 Muralis contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.spazio17.muralis;

/**
 * How often MQTT state reaches Home Assistant.
 *
 * <p>A fixed list of presets rather than a free-form number, decided 2026-08-20 after two other
 * shapes were considered and dropped. A minutes/hours unit picker only relocated the "what is a
 * sane value" question behind an extra control. A free-form number with a validated range, the
 * shape Tasmota's own {@code TelePeriod} uses, was superseded by presets for the same reason
 * presets already won for the brightness and recycle-time controls elsewhere in this app: they
 * remove the validation surface entirely rather than narrowing it, and there is nothing here an
 * operator legitimately needs that a plain number would serve and these four would not.
 *
 * <p>30 seconds, today's fixed interval, is deliberately one of the four, so upgrading this app
 * changes nobody's configured behaviour.
 */
final class TelemetryInterval {
    static final int[] OPTIONS = {10, 30, 60, 300};
    static final int DEFAULT_SECONDS = 30;

    private TelemetryInterval() {
    }

    static boolean isValid(int seconds) {
        for (int option : OPTIONS) {
            if (option == seconds) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stored or requested value if it is one of {@link #OPTIONS}, otherwise the default.
     *
     * <p>Used only for a value already at rest, such as one read back out of preferences that
     * something else could have hand-edited. A value arriving as a request, over the web admin or a
     * future remote command, must be rejected rather than silently substituted; see the callers.
     */
    static int clampOrDefault(int seconds) {
        return isValid(seconds) ? seconds : DEFAULT_SECONDS;
    }
}
