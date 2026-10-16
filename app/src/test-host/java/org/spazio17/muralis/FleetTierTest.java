/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pro's thirty-day lease through the fleet: who writes, who has Pro, which block wins. */
public final class FleetTierTest {

    public static void main(String[] args) {
        long day = 24L * 60 * 60 * 1000;
        long now = 1_800_000_000_000L;
        Map<String, Object> pro = FleetTier.confirmed(true, "hallway", now);
        require("pro".equals(pro.get("tier")) && ((Number) pro.get("valid_until")).longValue()
                == now + FleetTier.LEASE_MS, "an owner's block is pro for thirty days");

        // Who has Pro.
        require(FleetTier.status(true, null, now).source == FleetTier.Source.OWN,
                "a panel's own purchase");
        FleetTier.Status leased = FleetTier.status(false, pro, now + 5 * day);
        require(leased.pro && leased.source == FleetTier.Source.FLEET && !leased.warn
                && "hallway".equals(leased.confirmedBy), "a member leases the fleet's Pro");
        require(FleetTier.status(false, pro, now + 20 * day).warn,
                "a warning from day twenty");
        require(!FleetTier.status(false, pro, now + 30 * day).pro,
                "no confirmation for thirty days: free");
        require(!FleetTier.status(false, null, now).pro
                && !FleetTier.status(false, FleetTier.confirmed(false, "hallway", now), now).pro,
                "no block or a free block: free");

        require(FleetTier.status(false, pro, now + 31 * day).lapsed
                && FleetTier.status(false, FleetTier.confirmed(false, "hallway", now), now).ended
                && !FleetTier.status(false, null, now).lapsed
                && !FleetTier.status(false, null, now).ended,
                "a lapse and a refund are told apart from never having had Pro");

        require(!FleetTier.status(true, pro, now + 5 * day).warn
                && FleetTier.status(true, pro, now + 21 * day).warn
                && FleetTier.status(true, pro, now + 21 * day).pro,
                "an anchor that cannot confirm any more keeps its Pro and warns from day twenty");

        // Who writes.
        require(FleetTier.shouldWrite(true, "hallway", null, now), "the first owner writes");
        require(!FleetTier.shouldWrite(true, "hallway", pro, now + 3600_000L),
                "a fresh confirmation is not written again within hours");
        require(FleetTier.shouldWrite(true, "kitchen", pro, now + FleetTier.RECONFIRM_MS),
                "any owner renews an old confirmation");
        require(FleetTier.shouldWrite(false, "hallway", pro, now + day),
                "the anchor whose account lost Pro (a refund) writes free");
        require(!FleetTier.shouldWrite(false, "kitchen", pro, now + day),
                "a panel on another account never undoes the anchor's Pro");
        require(!FleetTier.shouldWrite(false, "hallway", null, now),
                "a panel without Pro writes nothing where there was nothing");

        // Which block wins.
        Map<String, Object> later = FleetTier.confirmed(true, "kitchen", now + day);
        require(FleetTier.newer(pro, later) == later && FleetTier.newer(later, pro) == later,
                "the later confirmation wins, whichever side it is on");
        require(FleetTier.newer(null, pro) == pro && FleetTier.newer(pro, new LinkedHashMap<>())
                == pro, "a missing block loses");
        Map<String, Object> docA = new LinkedHashMap<>();
        docA.put("version", 5L);
        docA.put("tier", later);
        Map<String, Object> docB = new LinkedHashMap<>();
        docB.put("version", 6L);
        docB.put("tier", pro);
        require(FleetTier.newer(FleetDocument.tierOf(FleetDocument.merge(docA, docB)), later)
                == later && ((Number) FleetDocument.tierOf(FleetDocument.merge(docA, docB))
                        .get("confirmed")).longValue() == now + day,
                "a merge keeps the later confirmation even from the older document");
        System.out.println("FleetTierTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
