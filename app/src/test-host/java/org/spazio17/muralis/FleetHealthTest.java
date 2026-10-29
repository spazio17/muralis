/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;
import java.util.List;

/** FleetHealth: the reasons and their order, versions, the list order and the redraw key. */
public final class FleetHealthTest {

    public static void main(String[] args) {
        // Versions: numbers first, then the commits since the release.
        require(FleetHealth.older("0.7.1", "0.7.1-3-gab4c6aa"), "a release is older than a build after it");
        require(FleetHealth.older("0.7.1-3-gab4c6aa", "0.7.2"), "a build is older than the next release");
        require(FleetHealth.older("0.7.1-3-gab4c6aa", "0.7.1-10-g1111111-dirty"), "commit counts compare");
        require(!FleetHealth.older("0.7.2", "0.7.1"), "a newer one is not older");
        require(!FleetHealth.older("0.7.1", "0.7.1"), "the same is not older");
        require(!FleetHealth.older("weird", "0.7.2") && !FleetHealth.older("0.7.1", "weird")
                && !FleetHealth.older("", "0.7.2"), "an unreadable version is never older");

        FleetHealth.Member self = member("me", "Hall", true, true, "0.7.2");
        FleetHealth.Member quiet = member("q", "attic", false, false, "0.7.2");
        FleetHealth.Member broken = member("b", "Kitchen", false, true, "0.7.2");
        broken.pageError = "net::ERR_NAME_NOT_RESOLVED";
        FleetHealth.Member old = member("o", "Office", false, true, "0.7.1");
        FleetHealth.Member loner = member("l", "Lobby", false, true, "0.7.2");
        loner.followsFleet = false;
        FleetHealth.Member fine = member("f", "bedroom", false, true, "0.7.2");
        List<FleetHealth.Member> all = Arrays.asList(fine, loner, old, broken, quiet, self);
        String newest = FleetHealth.newest(all);
        require("0.7.2".equals(newest), "newest " + newest);

        require(FleetHealth.reasons(self, newest, true).isEmpty(), "this panel is never offline");
        require(FleetHealth.reasons(quiet, newest, true).equals(Arrays.asList(FleetHealth.OFFLINE)),
                "offline");
        require(FleetHealth.reasons(broken, newest, true).equals(
                Arrays.asList(FleetHealth.PAGE_ERROR)), "page error");
        require(FleetHealth.reasons(old, newest, true).equals(Arrays.asList(FleetHealth.UPDATE)),
                "update");
        require(FleetHealth.reasons(loner, newest, true).equals(
                Arrays.asList(FleetHealth.NOT_FOLLOWING)), "not following");
        require(FleetHealth.reasons(loner, newest, false).isEmpty(),
                "not following only matters while the whole fleet shares something");
        // Never heard from is offline too; an offline panel with an old version has both, in order.
        FleetHealth.Member never = member("n", "New", false, false, "0.7.1");
        never.seenAgoMs = -1;
        require(FleetHealth.reasons(never, newest, true).equals(
                Arrays.asList(FleetHealth.OFFLINE, FleetHealth.UPDATE)), "reasons in their order");

        List<FleetHealth.Member> sorted = FleetHealth.sorted(all, newest, true);
        StringBuilder names = new StringBuilder();
        for (FleetHealth.Member each : sorted) {
            names.append(each.id);
        }
        require("qbolmef".equals(names.toString()), "order " + names);

        require("good".equals(FleetHealth.wifiWord(-60)) && "fair".equals(FleetHealth.wifiWord(-75))
                && "weak".equals(FleetHealth.wifiWord(-85)) && "".equals(FleetHealth.wifiWord(0)),
                "Wi-Fi words");

        // The redraw key moves on a reason, never on the time since a panel was seen.
        fine.seenAgoMs = 10_000;
        String before = FleetHealth.listKey(5, all, true);
        fine.seenAgoMs = 170_000;
        require(before.equals(FleetHealth.listKey(5, all, true)), "the time since seen moves the key");
        fine.pageError = "boom";
        require(!before.equals(FleetHealth.listKey(5, all, true)), "a reason does not move the key");
        require(!FleetHealth.listKey(5, all, true).equals(FleetHealth.listKey(6, all, true)),
                "the list's version does not move the key");
        // Who has a shared section: refused, waiting for an older list, or applied.
        FleetHealth.Member door = new FleetHealth.Member();
        door.online = true;
        door.listVersion = 12;
        require(FleetHealth.sharedState(door, "dashboard", 12).equals(FleetHealth.APPLIED),
                "a panel holding the version applied it");
        require(FleetHealth.sharedState(door, "dashboard", 13).equals(FleetHealth.WAITING),
                "a panel on an older list is waiting");
        door.listVersion = -1;
        require(FleetHealth.sharedState(door, "dashboard", 1).equals(FleetHealth.WAITING),
                "a panel that never said what it holds is waiting");
        door.listVersion = 20;
        door.refused.put("mqtt", "the port is taken");
        require(FleetHealth.sharedStateWords(door, "mqtt", 12)
                .equals("Refused: the port is taken"), "a refusal with its reason");
        FleetHealth.Member here = new FleetHealth.Member();
        here.self = true;
        require(FleetHealth.sharedState(here, "dashboard", 99).equals(FleetHealth.APPLIED),
                "this panel holds what it shows");
        System.out.println("FleetHealthTest passed");
    }

    private static FleetHealth.Member member(String id, String name, boolean self, boolean online,
            String version) {
        FleetHealth.Member member = new FleetHealth.Member();
        member.id = id;
        member.name = name;
        member.self = self;
        member.online = online;
        member.seenAgoMs = online ? 30_000 : 900_000;
        member.appVersion = version;
        return member;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
