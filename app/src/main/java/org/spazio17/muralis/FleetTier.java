/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How Muralis Pro reaches the panels of a fleet that never see Google: the fleet's
 * signed list carries a tier block written by an anchor, a member whose own Google Play says the
 * account owns Pro, and every member leases it for thirty days from each confirmation.
 *
 * <p>The block is {@code tier} ("pro" or "free"), {@code confirmed_by} (a panel id, never an
 * account), {@code confirmed_key} (that panel's key hash, which a rename does not change),
 * {@code confirmed} and {@code valid_until} (milliseconds since 1970). Pure, with no
 * Android imports, so the rules are host-tested.
 */
final class FleetTier {
    static final long LEASE_MS = 30L * 24 * 60 * 60 * 1000;
    /** From this many days after the last confirmation, the panels warn that Pro will lapse. */
    static final int WARN_AFTER_DAYS = 20;
    /** An anchor that still owns Pro writes again once a confirmation is this old. */
    static final long RECONFIRM_MS = 12L * 60 * 60 * 1000;
    /**
     * How far ahead of this panel's clock a confirmation may lie and still count: clocks differ a
     * little, but a block confirmed further ahead comes from a clock that is wrong (or a writer
     * that lies), and trusting it would keep Pro past a refund and win every later merge.
     */
    static final long CLOCK_SKEW_MS = 24L * 60 * 60 * 1000;
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
    static Map<String, Object> confirmed(boolean owned, String panelId, String panelKey,
            long nowMs) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("tier", owned ? "pro" : "free");
        block.put("confirmed_by", panelId);
        block.put("confirmed_key", panelKey);
        block.put("confirmed", nowMs);
        block.put("valid_until", owned ? nowMs + LEASE_MS : nowMs);
        return block;
    }

    /**
     * Whether an anchor should write a new block: Play said {@code owned} for this panel's
     * account and the fleet's block is {@code held}. An owner writes when the fleet is not Pro or
     * its confirmation is older than {@link #RECONFIRM_MS}; a panel whose account does not own
     * Pro writes "free" only over a block it confirmed itself (a refund), never over another
     * anchor's, so two panels on two accounts do not undo each other. Its own block is found by
     * the panel's key, so a renamed anchor still ends it; by its id for a block written before
     * the key was kept.
     */
    static boolean shouldWrite(boolean owned, String panelId, String panelKey,
            Map<String, Object> held, long nowMs) {
        boolean heldPro = held != null && "pro".equals(held.get("tier"));
        if (owned) {
            // Also over a block confirmed in the future, which the members no longer trust: the
            // anchor whose clock ran ahead writes again once its clock is right.
            return !heldPro || nowMs - number(held, "confirmed") >= RECONFIRM_MS
                    || number(held, "confirmed") > nowMs + CLOCK_SKEW_MS;
        }
        Object key = held == null ? null : held.get("confirmed_key");
        return heldPro && (key != null ? panelKey.equals(key)
                : panelId.equals(held.get("confirmed_by")));
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
        // Never longer than one lease from the confirmation, whatever the block says, and nothing
        // from a confirmation in the future.
        long until = Math.min(number(block, "valid_until"), confirmed + LEASE_MS);
        int days = (int) Math.max(0, (nowMs - confirmed) / (24L * 60 * 60 * 1000));
        boolean pro = nowMs < until && confirmed <= nowMs + CLOCK_SKEW_MS;
        String by = String.valueOf(block.getOrDefault("confirmed_by", ""));
        return new Status(pro, pro ? Source.FLEET : Source.NONE, by, confirmed, until, days,
                pro && days >= WARN_AFTER_DAYS, !pro, false);
    }

    /**
     * Of two blocks, the one confirmed later; the first on a tie. Either may be null. A block
     * confirmed more than {@link #CLOCK_SKEW_MS} after {@code nowMs} never wins over one that is
     * not, so a wrong clock cannot hold the fleet's tier against a later renewal or a refund.
     */
    static Map<String, Object> newer(Map<String, Object> a, Map<String, Object> b, long nowMs) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        boolean aAhead = number(a, "confirmed") > nowMs + CLOCK_SKEW_MS;
        boolean bAhead = number(b, "confirmed") > nowMs + CLOCK_SKEW_MS;
        if (aAhead != bAhead) {
            return aAhead ? b : a;
        }
        return number(b, "confirmed") > number(a, "confirmed") ? b : a;
    }

    private static long number(Map<String, Object> block, String key) {
        Object value = block == null ? null : block.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }
}
