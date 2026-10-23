/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Which panel asks for an integration the fleet shares, and when its values are too old. */
public final class FleetAskerTest {
    private static final long FRESH = 6 * 60_000L;
    private static final String W = "weather_1";

    public static void main(String[] args) {
        long now = 10_000_000L;
        Set<String> runs = Collections.singleton(W);
        Set<String> none = Collections.emptySet();

        // Order: the first fresh member that can run it asks, whoever this panel is.
        List<FleetAsker.Member> all = Arrays.asList(
                new FleetAsker.Member("a", now - 60_000, runs),
                new FleetAsker.Member("b", now, runs),
                new FleetAsker.Member("c", now - 1_000, runs));
        require("a".equals(FleetAsker.asker(all, "b", W, now, FRESH)), "the list order was not kept");
        require("a".equals(FleetAsker.asker(all, "c", W, now, FRESH)), "panels disagree on the asker");
        require(FleetAsker.asker(all, "c", "other_1", now, FRESH) == null,
                "an asker for an instance nobody runs");

        // The freshness window: heard exactly at its edge counts, a millisecond later does not.
        List<FleetAsker.Member> edge = Arrays.asList(
                new FleetAsker.Member("a", now - FRESH, runs),
                new FleetAsker.Member("b", now, runs));
        require("a".equals(FleetAsker.asker(edge, "b", W, now, FRESH)), "fresh at the edge skipped");
        require("b".equals(FleetAsker.asker(edge, "b", W, now + 1, FRESH)),
                "stale past the edge still asks");
        List<FleetAsker.Member> never = Arrays.asList(
                new FleetAsker.Member("a", FleetAsker.NEVER, runs),
                new FleetAsker.Member("b", now, runs));
        require("b".equals(FleetAsker.asker(never, "b", W, now, FRESH)),
                "a member never heard from asks");

        // Cannot run: skipped however fresh and early.
        List<FleetAsker.Member> cannot = Arrays.asList(
                new FleetAsker.Member("a", now, none),
                new FleetAsker.Member("b", now, runs),
                new FleetAsker.Member("c", now, runs));
        require("b".equals(FleetAsker.asker(cannot, "c", W, now, FRESH)),
                "a member that cannot run it asks");

        // This panel is always fresh to itself, whatever heard time it carries.
        List<FleetAsker.Member> selfOld = Arrays.asList(
                new FleetAsker.Member("a", FleetAsker.NEVER, runs),
                new FleetAsker.Member("b", now, runs));
        require("a".equals(FleetAsker.asker(selfOld, "a", W, now, FRESH)),
                "this panel was stale to itself");

        // Everyone stale except this panel: this panel asks.
        List<FleetAsker.Member> alone = Arrays.asList(
                new FleetAsker.Member("a", now - FRESH - 1, runs),
                new FleetAsker.Member("b", now - 3 * FRESH, runs),
                new FleetAsker.Member("c", FleetAsker.NEVER, runs));
        require("c".equals(FleetAsker.asker(alone, "c", W, now, FRESH)),
                "a panel alone did not ask");
        // ...unless it cannot run it either: nobody asks.
        List<FleetAsker.Member> aloneCannot = Arrays.asList(
                new FleetAsker.Member("a", now - FRESH - 1, runs),
                new FleetAsker.Member("c", now, none));
        require(FleetAsker.asker(aloneCannot, "c", W, now, FRESH) == null,
                "a panel that cannot run it asks");

        // A split: two panels that each hear only themselves both ask.
        List<FleetAsker.Member> seenByA = Arrays.asList(
                new FleetAsker.Member("a", now, runs),
                new FleetAsker.Member("b", FleetAsker.NEVER, runs));
        List<FleetAsker.Member> seenByB = Arrays.asList(
                new FleetAsker.Member("a", now - FRESH - 1, runs),
                new FleetAsker.Member("b", now, runs));
        require("a".equals(FleetAsker.asker(seenByA, "a", W, now, FRESH))
                && "b".equals(FleetAsker.asker(seenByB, "b", W, now, FRESH)),
                "both sides of a split must ask");

        // The first member comes back: it asks again at once, on the panel that took over too.
        List<FleetAsker.Member> back = Arrays.asList(
                new FleetAsker.Member("a", now, new HashSet<>(runs)),
                new FleetAsker.Member("b", now, runs));
        require("a".equals(FleetAsker.asker(back, "b", W, now, FRESH)),
                "the first member did not regain asking");

        // Staleness: two intervals plus one check-in interval since the last arrival.
        long interval = 10 * 60_000L;
        long checkIn = 3 * 60_000L;
        require(!FleetAsker.stale(now, interval, checkIn, now + 2 * interval + checkIn),
                "stale at the edge");
        require(FleetAsker.stale(now, interval, checkIn, now + 2 * interval + checkIn + 1),
                "not stale past the edge");
        require(!FleetAsker.stale(now, interval, checkIn, now), "stale on arrival");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
