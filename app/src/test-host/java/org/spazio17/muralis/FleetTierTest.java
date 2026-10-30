/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pro's thirty-day lease through the fleet: who writes, who has Pro, which block wins. */
public final class FleetTierTest {
    private static final String HALL = "key-hallway";
    private static final String KITCHEN = "key-kitchen";


    /** A Play key pair of the test's own, and a purchase it signed. */
    private static String key;
    private static final String PURCHASE = "{\"orderId\":\"GPA.0000-1111-2222-33333\","
            + "\"packageName\":\"org.spazio17.muralis\",\"productId\":\"muralis_pro\","
            + "\"purchaseTime\":1760000000000,\"purchaseState\":0,\"purchaseToken\":\"t\"}";
    private static String signature;
    private static KeyPair play;

    private static Map<String, Object> confirmed(boolean owned, String id, String panelKey,
            long at) {
        return FleetTier.confirmed(owned, id, panelKey, at, PURCHASE, signature);
    }

    private static FleetTier.Status status(boolean own, Map<String, Object> block, long at) {
        return FleetTier.status(own, block, at, key, 2);
    }

    private static String sign(String data) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(play.getPrivate());
        signer.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        play = generator.generateKeyPair();
        key = Base64.getEncoder().encodeToString(play.getPublic().getEncoded());
        signature = sign(PURCHASE);
        long day = 24L * 60 * 60 * 1000;
        // The real clock: a merge reads it to set aside confirmations from the future.
        long now = System.currentTimeMillis();
        Map<String, Object> pro = confirmed(true, "hallway", HALL, now);
        require("pro".equals(pro.get("tier")) && ((Number) pro.get("valid_until")).longValue()
                == now + FleetTier.LEASE_MS, "an owner's block is pro for thirty days");

        // Who has Pro.
        require(status(true, null, now).source == FleetTier.Source.OWN,
                "a panel's own purchase");
        FleetTier.Status leased = status(false, pro, now + 5 * day);
        require(leased.pro && leased.source == FleetTier.Source.FLEET && !leased.warn
                && "hallway".equals(leased.confirmedBy), "a member leases the fleet's Pro");
        require(status(false, pro, now + 20 * day).warn,
                "a warning from day twenty");
        require(!status(false, pro, now + 30 * day).pro,
                "no confirmation for thirty days: free");
        require(!status(false, null, now).pro
                && !status(false, confirmed(false, "hallway", HALL, now),
                        now).pro,
                "no block or a free block: free");

        require(status(false, pro, now + 31 * day).lapsed
                && status(false, confirmed(false, "hallway", HALL, now),
                        now).ended
                && !status(false, null, now).lapsed
                && !status(false, null, now).ended,
                "a lapse and a refund are told apart from never having had Pro");

        require(!status(true, pro, now + 5 * day).warn
                && status(true, pro, now + 21 * day).warn
                && status(true, pro, now + 21 * day).pro,
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
        Map<String, Object> later = confirmed(true, "kitchen", KITCHEN, now + day);
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

        // A wrong clock or a writer that lies (review, 2026-10-18).
        Map<String, Object> long_ = confirmed(true, "hallway", HALL, now);
        long_.put("valid_until", now + 365 * day);
        require(!status(false, long_, now + 31 * day).pro,
                "a block never holds longer than one lease from its confirmation");
        Map<String, Object> ahead = confirmed(true, "hallway", HALL, now + 300 * day);
        require(!status(false, ahead, now).pro,
                "a confirmation far in the future gives no Pro");
        require(status(false, confirmed(true, "hallway", HALL, now + 3600_000L),
                now).pro, "a clock a little ahead still counts");
        Map<String, Object> refund = confirmed(false, "hallway", HALL, now);
        require(FleetTier.newer(ahead, refund, now) == refund
                && FleetTier.newer(refund, ahead, now) == refund,
                "a refund wins over a confirmation from the future");
        require(FleetTier.shouldWrite(true, "hallway", HALL, ahead, now),
                "an owner writes again over a confirmation from the future");
        testEveryMemberJudgesTheBlock(now, day);
        System.out.println("FleetTierTest passed");
    }

    /** A modified writer cannot invent Pro, grow the fleet past six, or turn the clock back. */
    private static void testEveryMemberJudgesTheBlock(long now, long day) throws Exception {
        Map<String, Object> pro = confirmed(true, "hallway", HALL, now);
        require(FleetTier.status(false, pro, now, key, 6).pro, "six panels lease Pro");
        FleetTier.Status seven = FleetTier.status(false, pro, now, key, 7);
        require(!seven.pro && seven.why.contains("7 panels"), "seven panels do not: " + seven.why);
        Map<String, Object> bare = FleetTier.confirmed(true, "hallway", HALL, now, null, null);
        require(!status(false, bare, now).pro, "a block without a purchase gives no Pro");
        Map<String, Object> forged = new LinkedHashMap<>(pro);
        forged.put("purchase", PURCHASE.replace("33333", "44444"));
        require(!status(false, forged, now).pro, "a purchase changed after Play signed it");
        String other = PURCHASE.replace("muralis_pro", "muralis_coffee");
        Map<String, Object> wrongProduct = FleetTier.confirmed(true, "hallway", HALL, now, other,
                sign(other));
        require(!status(false, wrongProduct, now).pro, "another product signed by Play");
        String pending = PURCHASE.replace("\"purchaseState\":0", "\"purchaseState\":4");
        require(!status(false, FleetTier.confirmed(true, "hallway", HALL, now, pending,
                sign(pending)), now).pro, "a pending purchase");
        require(!FleetTier.status(false, pro, now, "", 2).pro, "a build with no key leases nothing");
        require(FleetTier.status(true, null, now, "", 9).pro, "a panel's own purchase stands");

        // The clock: set back, the last time seen and the time since boot still count.
        long seen = now + 10 * day;
        require(FleetTier.monotonicNow(now, 5_000, seen, 1_000) == seen + 4_000,
                "a clock set back ten days does not move now back");
        require(FleetTier.monotonicNow(seen + day, 5_000, seen, 1_000) == seen + day,
                "a clock ahead of what was seen is now");
        require(FleetTier.monotonicNow(now, 500, seen, 1_000) == seen,
                "after a reboot the last time seen stands");
        require(FleetTier.restartsClock(now, now - day, now + 60_000)
                && !FleetTier.restartsClock(now, now, now)
                && !FleetTier.restartsClock(now - 5 * day, now - 6 * day, now),
                "a newer renewal the wall clock agrees with restarts a clock that ran ahead");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
