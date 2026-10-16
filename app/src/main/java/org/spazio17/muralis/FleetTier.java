/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How Muralis Pro reaches the panels of a fleet that never see Google (block 14): the fleet's
 * signed list carries a tier block written by an anchor, a member whose own Google Play says the
 * account owns Pro, and every member leases it for thirty days. Reasoning and rejected
 * alternatives: the board's card "Why Pro travels on a 30-day lease".
 *
 * <p>The block is {@code tier} ("pro" or "free"), {@code confirmed_by} (a panel id, never an
 * account), {@code confirmed} and {@code valid_until} (milliseconds since 1970). Pure, with no
 * Android imports, so the rules are host-tested.
 */
final class FleetTier {
    static final long LEASE_MS = 30L * 24 * 60 * 60 * 1000;
    /** From this many days after the last confirmation, the panels warn that Pro will lapse. */
    static final int WARN_AFTER_DAYS = 20;
    /** An anchor that still owns Pro writes again once a confirmation is this old. */
    static final long RECONFIRM_MS = 12L * 60 * 60 * 1000;
    /** A Pro fleet holds at most this many panels, every Muralis install counted (2026-10-09). */
    static final int MAX_PANELS = 6;

    private FleetTier() {
    }

    /** Where this panel's Pro comes from. */
    enum Source { OWN, FLEET, NONE }

    /** What a panel knows of Pro: whether it has it, from where, and what to say about it. */
    static final class Status {
        final boolean pro;
        final Source source;
        /** Who confirmed the fleet's Pro and when, and until when it holds; empty when none. */
        final String confirmedBy;
        final long confirmedMs;
        final long validUntilMs;
        /** Days since the fleet's last confirmation, when the fleet's block is Pro. */
        final int daysSinceConfirmed;
        /** The fleet's Pro is close to lapsing, whether this panel leases it or owns it: say so. */
        final boolean warn;
        /** Not Pro although the fleet had it: the lease ran out, or the anchor's account lost it. */
        final boolean lapsed;
        final boolean ended;

        Status(boolean pro, Source source, String confirmedBy, long confirmedMs, long validUntilMs,
                int daysSinceConfirmed, boolean warn) {
            this(pro, source, confirmedBy, confirmedMs, validUntilMs, daysSinceConfirmed, warn,
                    false, false);
        }

        Status(boolean pro, Source source, String confirmedBy, long confirmedMs, long validUntilMs,
                int daysSinceConfirmed, boolean warn, boolean lapsed, boolean ended) {
            this.lapsed = lapsed;
            this.ended = ended;
            this.pro = pro;
            this.source = source;
            this.confirmedBy = confirmedBy;
            this.confirmedMs = confirmedMs;
            this.validUntilMs = validUntilMs;
            this.daysSinceConfirmed = daysSinceConfirmed;
            this.warn = warn;
        }
    }

    /** The block an anchor writes after Google Play answered for its account. */
    static Map<String, Object> confirmed(boolean owned, String panelId, long nowMs) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("tier", owned ? "pro" : "free");
        block.put("confirmed_by", panelId);
        block.put("confirmed", nowMs);
        block.put("valid_until", owned ? nowMs + LEASE_MS : nowMs);
        return block;
    }

    /**
     * Whether an anchor should write a new block: Play said {@code owned} for this panel's
     * account and the fleet's block is {@code held}. An owner writes when the fleet is not Pro or
     * its confirmation is older than {@link #RECONFIRM_MS}; a panel whose account does not own
     * Pro writes "free" only over a block it confirmed itself (a refund), never over another
     * anchor's, so two panels on two accounts do not undo each other.
     */
    static boolean shouldWrite(boolean owned, String panelId, Map<String, Object> held,
            long nowMs) {
        boolean heldPro = held != null && "pro".equals(held.get("tier"));
        if (owned) {
            return !heldPro || nowMs - number(held, "confirmed") >= RECONFIRM_MS;
        }
        return heldPro && panelId.equals(held.get("confirmed_by"));
    }

    /** This panel's Pro from its own purchase and the fleet's block, at {@code nowMs}. */
    static Status status(boolean ownPurchase, Map<String, Object> block, long nowMs) {
        if (ownPurchase) {
            // Pro here either way, but a panel whose account cannot reach Play any more (the
            // anchor whose account was removed keeps its cached purchase) still sees the fleet's
            // confirmation age, so its Fleet page and card warn as the members' do.
            boolean leasing = block != null && "pro".equals(block.get("tier"));
            long confirmed = leasing ? number(block, "confirmed") : 0;
            int days = leasing ? (int) Math.max(0, (nowMs - confirmed) / (24L * 60 * 60 * 1000))
                    : 0;
            return new Status(true, Source.OWN, "", confirmed, 0, days,
                    leasing && days >= WARN_AFTER_DAYS);
        }
        if (block == null) {
            return new Status(false, Source.NONE, "", 0, 0, 0, false);
        }
        if (!"pro".equals(block.get("tier"))) {
            return new Status(false, Source.NONE, String.valueOf(block.getOrDefault(
                    "confirmed_by", "")), number(block, "confirmed"), 0, 0, false, false, true);
        }
        long confirmed = number(block, "confirmed");
        long until = number(block, "valid_until");
        int days = (int) Math.max(0, (nowMs - confirmed) / (24L * 60 * 60 * 1000));
        boolean pro = nowMs < until;
        String by = String.valueOf(block.getOrDefault("confirmed_by", ""));
        return new Status(pro, pro ? Source.FLEET : Source.NONE, by, confirmed, until, days,
                pro && days >= WARN_AFTER_DAYS, !pro, false);
    }

    /** Of two blocks, the one confirmed later; the first on a tie. Either may be null. */
    static Map<String, Object> newer(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return number(b, "confirmed") > number(a, "confirmed") ? b : a;
    }

    private static long number(Map<String, Object> block, String key) {
        Object value = block == null ? null : block.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }
}
