/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pro's thirty-day lease through the fleet: who writes, who has Pro, which block wins. */
public final class FleetTierTest {
    private static final String HALL = "key-hallway";
    private static final String KITCHEN = "key-kitchen";


    public static void main(String[] args) {
        long day = 24L * 60 * 60 * 1000;
        // The real clock: a merge reads it to set aside confirmations from the future.
        long now = System.currentTimeMillis();
        Map<String, Object> pro = FleetTier.confirmed(true, "hallway", HALL, now);
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
                && !FleetTier.status(false, FleetTier.confirmed(false, "hallway", HALL, now),
                        now).pro,
                "no block or a free block: free");

        require(FleetTier.status(false, pro, now + 31 * day).lapsed
                && FleetTier.status(false, FleetTier.confirmed(false, "hallway", HALL, now),
                        now).ended
                && !FleetTier.status(false, null, now).lapsed
                && !FleetTier.status(false, null, now).ended,
                "a lapse and a refund are told apart from never having had Pro");

        require(!FleetTier.status(true, pro, now + 5 * day).warn
                && FleetTier.status(true, pro, now + 21 * day).warn
                && FleetTier.status(true, pro, now + 21 * day).pro,
                "an anchor that cannot confirm any more keeps its Pro and warns from day twenty");

        // Who writes.
        require(FleetTier.shouldWrite(true, "hallway", HALL, null, now), "the first owner writes");
        require(FleetTier.shouldWrite(false, "hall", HALL, pro, now + 3600_000L),
                "a renamed anchor could not end its own Pro");
        Map<String, Object> keyless = new java.util.LinkedHashMap<>(pro);
        keyless.remove("confirmed_key");
        require(FleetTier.shouldWrite(false, "hallway", "other", keyless, now + 3600_000L)
                && !FleetTier.shouldWrite(false, "hall", HALL, keyless, now + 3600_000L),
                "a block without a key is not matched by the id");
        require(!FleetTier.shouldWrite(true, "hallway", HALL, pro, now + 3600_000L),
                "a fresh confirmation is not written again within hours");
        require(FleetTier.shouldWrite(true, "kitchen", KITCHEN, pro, now + FleetTier.RECONFIRM_MS),
                "any owner renews an old confirmation");
        require(FleetTier.shouldWrite(false, "hallway", HALL, pro, now + day),
                "the anchor whose account lost Pro (a refund) writes free");
        require(!FleetTier.shouldWrite(false, "kitchen", KITCHEN, pro, now + day),
                "a panel on another account never undoes the anchor's Pro");
        require(!FleetTier.shouldWrite(false, "hallway", HALL, null, now),
                "a panel without Pro writes nothing where there was nothing");

        // Which block wins.
        Map<String, Object> later = FleetTier.confirmed(true, "kitchen", KITCHEN, now + day);
        require(FleetTier.newer(pro, later, now) == later && FleetTier.newer(later, pro, now) == later,
                "the later confirmation wins, whichever side it is on");
        require(FleetTier.newer(null, pro, now) == pro
                && FleetTier.newer(pro, new LinkedHashMap<>(), now) == pro, "a missing block loses");
        Map<String, Object> docA = new LinkedHashMap<>();
        docA.put("version", 5L);
        docA.put("tier", later);
        Map<String, Object> docB = new LinkedHashMap<>();
        docB.put("version", 6L);
        docB.put("tier", pro);
        require(FleetTier.newer(FleetDocument.tierOf(FleetDocument.merge(docA, docB)), later, now)
                == later && ((Number) FleetDocument.tierOf(FleetDocument.merge(docA, docB))
                        .get("confirmed")).longValue() == now + day,
                "a merge keeps the later confirmation even from the older document");

        // A wrong clock or a writer that lies (Emmet, 2026-10-18).
        Map<String, Object> long_ = FleetTier.confirmed(true, "hallway", HALL, now);
        long_.put("valid_until", now + 365 * day);
        require(!FleetTier.status(false, long_, now + 31 * day).pro,
                "a block never holds longer than one lease from its confirmation");
        Map<String, Object> ahead = FleetTier.confirmed(true, "hallway", HALL, now + 300 * day);
        require(!FleetTier.status(false, ahead, now).pro,
                "a confirmation far in the future gives no Pro");
        require(FleetTier.status(false, FleetTier.confirmed(true, "hallway", HALL, now + 3600_000L),
                now).pro, "a clock a little ahead still counts");
        Map<String, Object> refund = FleetTier.confirmed(false, "hallway", HALL, now);
        require(FleetTier.newer(ahead, refund, now) == refund
                && FleetTier.newer(refund, ahead, now) == refund,
                "a refund wins over a confirmation from the future");
        require(FleetTier.shouldWrite(true, "hallway", HALL, ahead, now),
                "an owner writes again over a confirmation from the future");
        System.out.println("FleetTierTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
