/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which panel asks a service for an integration the fleet shares, and when the others stop
 * trusting what it passed on. One panel asks and pushes the values to the others; when it goes
 * quiet, the next one takes over.
 *
 * <p>The rule, worked out by every panel on its own with nothing voted on: walk the members in
 * the signed list's order, skip one not heard from within the freshness window, skip one that
 * cannot run the instance, and the first one left asks. This panel is always fresh to itself.
 * A panel that comes back earlier in the list asks again at once. After a network split each
 * side has its own asker for a while, which only doubles reads, never writes.
 *
 * <p>Pure, with no Android imports, so the rule is host-tested. Times are on a clock that a
 * change of the date cannot move.
 */
final class FleetAsker {
    /** A heard time for a member never heard from since this panel started. */
    static final long NEVER = -1;

    private FleetAsker() {
    }

    /** One member as the rule sees it: its key hash, when last heard, what it can run. */
    static final class Member {
        final String id;
        /** When it was last heard from, or {@link #NEVER}. Ignored for this panel itself. */
        final long heardAtMs;
        /** The ids of the shared instances it said it can run. */
        final Set<String> runnable;

        Member(String id, long heardAtMs, Set<String> runnable) {
            this.id = id;
            this.heardAtMs = heardAtMs;
            this.runnable = runnable == null ? Collections.<String>emptySet() : runnable;
        }
    }

    /**
     * The id of the member that asks for {@code instanceId}, or null when no member can: the
     * first in {@code inOrder} heard from within {@code freshMs} of {@code nowMs} (this panel,
     * {@code selfId}, always is) that can run it.
     */
    static String asker(List<Member> inOrder, String selfId, String instanceId, long nowMs,
            long freshMs) {
        for (Member member : inOrder) {
            boolean self = member.id.equals(selfId);
            boolean fresh = self || (member.heardAtMs != NEVER
                    && nowMs - member.heardAtMs <= freshMs);
            if (fresh && member.runnable.contains(instanceId)) {
                return member.id;
            }
        }
        return null;
    }

    /**
     * Whether values passed on by the asker are too old to stand: nothing arrived for two of the
     * instance's intervals plus one check-in interval, so one missed push or a takeover that
     * waits for the next check-in does not blank them, a silent asker does.
     */
    static boolean stale(long lastArrivalMs, long intervalMs, long checkInIntervalMs,
            long nowMs) {
        return nowMs - lastArrivalMs > 2 * intervalMs + checkInIntervalMs;
    }
}
