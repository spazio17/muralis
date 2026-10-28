/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Whether each panel of the fleet is healthy, for the Fleet page of both surfaces: the counts
 * at the top, the "Not healthy" panels first with their reason, and the key that tells the
 * page to draw the list again (2026-10-25, the Fleet page redesign).
 *
 * <p>A panel is not healthy when one of these holds; the first one is the reason its row says:
 * it is not this panel and has not checked in for a while (offline), its page shows an error
 * now, its Muralis is older than the newest one in the fleet, or it does not take what the
 * whole fleet shares while the whole fleet shares something. Battery and temperature belong to
 * the alerts, not here.
 *
 * <p>Pure, with no Android imports, so the rule is host-tested.
 */
final class FleetHealth {
    static final String OFFLINE = "offline";
    static final String PAGE_ERROR = "page_error";
    static final String UPDATE = "update";
    static final String NOT_FOLLOWING = "not_following";

    private FleetHealth() {
    }

    /** One panel as the Fleet page knows it. */
    static final class Member {
        String id = "";
        String name = "";
        boolean self;
        boolean online;
        /** How long ago it last checked in, or -1 when it was never heard from. */
        long seenAgoMs = -1;
        String appVersion = "";
        /** The page error it shows now, empty for none. */
        String pageError = "";
        boolean followsFleet = true;
        /** -1 when not known. */
        int batteryPercent = -1;
        boolean charging;
        /** 0 or above when not known (a reading is always negative). */
        int wifiRssiDbm;
        String android = "";
        /** The version of the fleet's list it holds, -1 when not known (part C, 2026-10-28). */
        long listVersion = -1;
        /** The shared sections it refused, by section, with why, as it reported them. */
        java.util.Map<String, String> refused = new java.util.HashMap<>();
    }

    static final String APPLIED = "applied";
    static final String WAITING = "waiting";
    static final String REFUSED = "refused";

    /**
     * Whether {@code member} has a shared section that was stamped at list version
     * {@code stamp}: refused (it said why), waiting (it holds an older list, or none it told), or
     * applied. This panel itself is never waiting: it holds what it shows.
     */
    static String sharedState(Member member, String section, long stamp) {
        if (member.refused.containsKey(section)) {
            return REFUSED;
        }
        if (!member.self && (member.listVersion < 0 || member.listVersion < stamp)) {
            return WAITING;
        }
        return APPLIED;
    }

    /** The state in a person's words, the same on both surfaces. */
    static String sharedStateWords(Member member, String section, long stamp) {
        switch (sharedState(member, section, stamp)) {
            case REFUSED:
                return "Refused: " + member.refused.get(section);
            case WAITING:
                return member.online ? "Waiting for the panel" : "Waiting: catches up when back";
            default:
                return "Applied";
        }
    }

    /**
     * Why {@code member} is not healthy, the first reason first; empty when it is healthy.
     * {@code newest} is the newest Muralis in the fleet, {@code fleetShares} whether the whole
     * fleet shares at least one section.
     */
    static List<String> reasons(Member member, String newest, boolean fleetShares) {
        List<String> reasons = new ArrayList<>();
        if (!member.self && !member.online) {
            reasons.add(OFFLINE);
        }
        if (!member.pageError.isEmpty()) {
            reasons.add(PAGE_ERROR);
        }
        if (older(member.appVersion, newest)) {
            reasons.add(UPDATE);
        }
        if (fleetShares && !member.followsFleet) {
            reasons.add(NOT_FOLLOWING);
        }
        return reasons;
    }

    /** The newest Muralis among the members, empty when none can be read. */
    static String newest(List<Member> members) {
        String newest = "";
        for (Member member : members) {
            if (parse(member.appVersion) != null
                    && (newest.isEmpty() || older(newest, member.appVersion))) {
                newest = member.appVersion;
            }
        }
        return newest;
    }

    /**
     * Whether version {@code a} is older than {@code b}: "0.7.1" before "0.7.1-3-gab4c6aa"
     * before "0.7.2". A version that cannot be read is never older, so a strange build never
     * shows as out of date.
     */
    static boolean older(String a, String b) {
        long[] left = parse(a);
        long[] right = parse(b);
        if (left == null || right == null) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    /** Major, minor, patch and the commits since that release, or null when unreadable. */
    private static long[] parse(String version) {
        if (version == null) {
            return null;
        }
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+)-g[0-9a-f]+)?(?:-dirty)?$")
                .matcher(version.trim());
        if (!match.matches()) {
            return null;
        }
        return new long[] {Long.parseLong(match.group(1)), Long.parseLong(match.group(2)),
                Long.parseLong(match.group(3)),
                match.group(4) == null ? 0 : Long.parseLong(match.group(4))};
    }

    /**
     * The members in the order the page lists them: the not healthy first, by their first
     * reason in the order of {@link #reasons}, then the healthy with this panel first; by name
     * within each, ignoring case.
     */
    static List<Member> sorted(List<Member> members, String newest, boolean fleetShares) {
        List<String> order = java.util.Arrays.asList(OFFLINE, PAGE_ERROR, UPDATE, NOT_FOLLOWING);
        List<Member> sorted = new ArrayList<>(members);
        Collections.sort(sorted, Comparator.comparingInt((Member member) -> {
            List<String> why = reasons(member, newest, fleetShares);
            return why.isEmpty() ? order.size() + (member.self ? 0 : 1) : order.indexOf(why.get(0));
        }).thenComparing(member -> member.name.toLowerCase(Locale.ROOT)));
        return sorted;
    }

    /** "Good", "Fair" or "Weak" for a Wi-Fi signal, empty when it is not known. */
    static String wifiWord(int rssiDbm) {
        if (rssiDbm >= 0) {
            return "";
        }
        return rssiDbm >= -67 ? "good" : rssiDbm >= -80 ? "fair" : "weak";
    }

    /**
     * What moves when the Fleet page should be drawn again: the list's version, then per member
     * its id, whether it is online, its reasons, its battery in tens, its Wi-Fi word and its
     * version. Not the time since it was seen: the page says "Online" and "Offline since 09:12",
     * which do not move by the minute, so the page is not fetched again with every heartbeat.
     */
    static String listKey(long listVersion, List<Member> members, boolean fleetShares) {
        String newest = newest(members);
        StringBuilder key = new StringBuilder().append(listVersion);
        for (Member member : members) {
            key.append('|').append(member.id).append(',').append(member.online).append(',')
                    .append(String.join("+", reasons(member, newest, fleetShares))).append(',')
                    .append(member.batteryPercent < 0 ? -1 : member.batteryPercent / 10)
                    .append(member.charging ? 'c' : '-').append(',')
                    .append(wifiWord(member.wifiRssiDbm)).append(',').append(member.appVersion);
        }
        return key.toString();
    }
}
