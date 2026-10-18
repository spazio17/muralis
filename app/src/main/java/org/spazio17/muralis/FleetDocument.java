/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The fleet document: one JSON every member holds, naming the
 * fleet, its members and the keys revoked, at a version, signed by the manager who wrote it.
 *
 * <p>Signed with ECDSA P-256 over the document's canonical form (keys sorted at every level, no
 * spaces, as {@link TinyJson} writes) without its {@code signature}, which carries the signer's
 * key hash ({@code by}), its public key ({@code key}, so a member needs nothing else to check it)
 * and the signature itself, both in base64. A member takes a document only from a manager it
 * already knows, at a higher version; two managers' edits of the same version are settled without
 * the clock: one that revokes a key wins, otherwise the lower author id.
 *
 * <p>Pure, with no Android imports, so the rules are covered by host tests. The profile holds
 * what the fleet and its groups share; the tier block is how Pro reaches the members
 * ({@link FleetTier}).
 */
final class FleetDocument {
    /** A member entry's fields, as the design names them. */
    static final String ID = "id";
    static final String NAME = "name";
    static final String SPKI = "spki";
    static final String MANAGER = "manager";

    private FleetDocument() {
    }

    /** The document as maps and lists, or null when the text is not a JSON object. */
    static Map<String, Object> parse(String text) {
        try {
            Object value = TinyJson.parse(text);
            return value instanceof Map ? TinyJson.object(value) : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The canonical form: every object's keys sorted, no spaces. */
    static String canonical(Object value) {
        return TinyJson.write(sorted(value));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sorted(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> items = new ArrayList<>();
            for (Object item : (List<?>) value) {
                items.add(sorted(item));
            }
            return items;
        }
        return value;
    }

    /** What the signature covers: the document without its signature, canonical, UTF-8. */
    static byte[] signedBytes(Map<String, Object> document) {
        Map<String, Object> unsigned = new LinkedHashMap<>(document);
        unsigned.remove("signature");
        return canonical(unsigned).getBytes(StandardCharsets.UTF_8);
    }

    /** Signs {@code document} in place as the holder of {@code key}, and returns its text. */
    static String sign(Map<String, Object> document, PrivateKey key, PublicKey publicKey)
            throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(signedBytes(document));
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("by", hashOf(publicKey.getEncoded()));
        signature.put("key", Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        signature.put("sig", Base64.getEncoder().encodeToString(signer.sign()));
        document.put("signature", signature);
        return canonical(document);
    }

    /**
     * The key hash of whoever signed {@code document}, or null when the signature is missing,
     * malformed, made with another key than the one it names, or does not match the content.
     */
    static String signer(Map<String, Object> document) {
        Object block = document.get("signature");
        if (!(block instanceof Map)) {
            return null;
        }
        Map<String, Object> signature = TinyJson.object(block);
        String by = TinyJson.string(signature, "by", "");
        try {
            byte[] encoded = Base64.getDecoder().decode(TinyJson.string(signature, "key", ""));
            if (!hashOf(encoded).equals(by)) {
                return null;
            }
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            Signature checker = Signature.getInstance("SHA256withECDSA");
            checker.initVerify(key);
            checker.update(signedBytes(document));
            return checker.verify(Base64.getDecoder().decode(
                    TinyJson.string(signature, "sig", ""))) ? by : null;
        } catch (GeneralSecurityException | IllegalArgumentException forged) {
            return null;
        }
    }

    /** SHA-256 of an encoded public key, lowercase hex: the key hash panels know each other by. */
    static String hashOf(byte[] encodedPublicKey) {
        return FleetTls.spkiOf(encodedPublicKey);
    }

    static String fleetId(Map<String, Object> document) {
        return TinyJson.string(fleet(document), ID, "");
    }

    static String fleetName(Map<String, Object> document) {
        return TinyJson.string(fleet(document), NAME, "");
    }

    private static Map<String, Object> fleet(Map<String, Object> document) {
        Object fleet = document.get("fleet");
        return fleet instanceof Map ? TinyJson.object(fleet) : new LinkedHashMap<>();
    }

    /** Any number, as read back (a Double) or as put by this app (a Long). */
    static long version(Map<String, Object> document) {
        Object version = document == null ? null : document.get("version");
        return version instanceof Number ? ((Number) version).longValue() : 0;
    }

    static String author(Map<String, Object> document) {
        return TinyJson.string(document, "author", "");
    }

    /** The member entries, each a map; none when the field is missing or malformed. */
    static List<Map<String, Object>> members(Map<String, Object> document) {
        List<Map<String, Object>> members = new ArrayList<>();
        Object list = document == null ? null : document.get("members");
        if (list instanceof List) {
            for (Object one : (List<?>) list) {
                if (one instanceof Map) {
                    members.add(TinyJson.object(one));
                }
            }
        }
        return members;
    }

    /** A member entry's version of joining, and a revocation's version: what a merge compares. */
    static final String SINCE = "since";
    static final String AT = "at";
    /**
     * The profile's sections: the groups of settings a fleet can share, each either
     * shared, with the values every member applies, or not. Never shared: a panel's name, member
     * port, escape combinations and PIN, calibrations and what belongs to its hardware.
     */
    static final String[] SECTIONS = {"dashboard", "mqtt", "web_admin", "display", "ambient",
            "alerts"};
    /** A section's words on both surfaces. */
    static String sectionLabel(String section) {
        switch (section) {
            case "dashboard": return "Dashboard";
            case "mqtt": return "MQTT";
            case "web_admin": return "Web admin";
            case "display": return "Display";
            case "alerts": return "Alerts";
            default: return "Ambient";
        }
    }

    /** Values that are secrets: carried only on the wire, blanked in every stored copy. */
    static final String[] SECRET_KEYS = {"mqtt_password", "http_admin_password"};

    /**
     * The scope of the whole fleet, which is no group a person sees; the groups are made by a
     * person, with a name, as the device groups of the fleet products (settled 2026-10-13). A
     * shared section is shared in one scope; a panel applies it from its groups, a group winning
     * over the whole fleet, and from the whole fleet while it follows it.
     */
    static final String EVERYONE = "everyone";
    static final String EVERYONE_NAME = "Whole fleet";
    /**
     * Offered when a group is made: jobs panels share, not rooms (nobody has several panels in
     * one room), generic, since a fleet may be a home, a shop or a fair.
     */
    static final String[] GROUP_SUGGESTIONS = {"Meeting rooms", "Lobby screens", "Reception",
            "Shop floor", "Upstairs", "Downstairs", "Guest house"};
    /** A member entry's flag: whether it follows what the whole fleet shares; true when absent. */
    static final String FOLLOWS_FLEET = "follows_fleet";

    /** Whether a member follows the whole fleet's shared settings (its groups it always does). */
    static boolean followsFleet(Map<String, Object> member) {
        return member == null || TinyJson.flag(member, FOLLOWS_FLEET, true);
    }

    /** The next document with the member of key hash {@code spki} following the fleet or not. */
    static Map<String, Object> withMemberFollowsFleet(Map<String, Object> held, String author,
            String spki, boolean follows, String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        List<Object> members = new ArrayList<>();
        for (Map<String, Object> member : members(held)) {
            if (spki.equals(TinyJson.string(member, SPKI, ""))) {
                member = new LinkedHashMap<>(member);
                member.put(FOLLOWS_FLEET, follows);
                member.put(AT, version(next));
            }
            members.add(member);
        }
        next.put("members", members);
        return next;
    }

    /** The groups a person made, by id: name and at; removed ones left out. */
    static Map<String, String> groups(Map<String, Object> document) {
        Map<String, String> groups = new LinkedHashMap<>();
        Object all = document == null ? null : document.get("groups");
        if (all instanceof Map) {
            for (Map.Entry<String, Object> entry : TinyJson.object(all).entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> group = TinyJson.object(entry.getValue());
                    if (!group.containsKey("removed")) {
                        groups.put(entry.getKey(), TinyJson.string(group, NAME, entry.getKey()));
                    }
                }
            }
        }
        return groups;
    }

    /** A group's name, Everyone's for its id, the id itself for one no longer known. */
    static String groupName(Map<String, Object> document, String id) {
        if (EVERYONE.equals(id)) {
            return EVERYONE_NAME;
        }
        String name = groups(document).get(id);
        return name == null ? id : name;
    }

    /** Whether a group name is one a person may give: one line, 1 to 40 characters, not Everyone. */
    static String validateGroupName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40 || trimmed.contains("\n")) {
            return "a group's name is one line of 1 to 40 characters";
        }
        if (trimmed.equalsIgnoreCase(EVERYONE_NAME) || trimmed.equalsIgnoreCase(EVERYONE)) {
            return "the whole fleet is not a group";
        }
        return null;
    }

    /** The next document with group {@code id} named {@code name}, made or renamed. */
    static Map<String, Object> withGroup(Map<String, Object> held, String author, String id,
            String name, String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        Map<String, Object> groups = groupEntries(next);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put(NAME, name.trim());
        group.put(AT, version(next));
        groups.put(id, group);
        next.put("groups", groups);
        return next;
    }

    /**
     * The next document without group {@code id}: its entry stays, marked removed at this
     * version so a merge never brings it back, and what it shared is gone.
     */
    static Map<String, Object> withoutGroup(Map<String, Object> held, String author, String id,
            String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        Map<String, Object> groups = groupEntries(next);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put(AT, version(next));
        group.put("removed", version(next));
        groups.put(id, group);
        next.put("groups", groups);
        Map<String, Object> profile = profileEntries(next);
        profile.remove(id);
        next.put("profile", profile);
        return next;
    }

    /** The groups a member is in: its own, and Everyone first. */
    static List<String> memberGroups(Map<String, Object> member) {
        List<String> groups = new ArrayList<>();
        groups.add(EVERYONE);
        Object list = member == null ? null : member.get("groups");
        if (list instanceof List) {
            for (Object one : (List<?>) list) {
                if (one instanceof String && !EVERYONE.equals(one) && !groups.contains(one)) {
                    groups.add((String) one);
                }
            }
        }
        return groups;
    }

    /** The member entry with key hash {@code spki}, or null: a panel is known by its key. */
    static Map<String, Object> memberByKey(Map<String, Object> document, String spki) {
        for (Map<String, Object> member : members(document)) {
            if (spki != null && spki.equals(TinyJson.string(member, SPKI, ""))) {
                return member;
            }
        }
        return null;
    }

    /** The groups a member is in that still exist, Everyone first; see memberGroups. */
    static List<String> memberGroups(Map<String, Object> document, Map<String, Object> member) {
        List<String> groups = new ArrayList<>();
        Map<String, String> known = groups(document);
        for (String group : memberGroups(member)) {
            if (EVERYONE.equals(group) || known.containsKey(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * The next document with the member of key hash {@code spki} in exactly {@code groups} (Everyone
     * aside); the entry is stamped, so a merge keeps the later of two edits of it.
     */
    static Map<String, Object> withMemberGroups(Map<String, Object> held, String author,
            String spki, List<String> groups, String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        List<Object> members = new ArrayList<>();
        for (Map<String, Object> member : members(held)) {
            if (spki.equals(TinyJson.string(member, SPKI, ""))) {
                member = new LinkedHashMap<>(member);
                List<Object> own = new ArrayList<>();
                for (String group : groups) {
                    if (!EVERYONE.equals(group) && !own.contains(group)) {
                        own.add(group);
                    }
                }
                member.put("groups", own);
                member.put(AT, version(next));
            }
            members.add(member);
        }
        next.put("members", members);
        return next;
    }

    /**
     * The next document without the member of key hash {@code spki}, its key revoked by this
     * version: a panel that left, or one removed from the Fleet page. A key revoked so can join
     * again later; its new entry's {@code since} is past the revocation.
     */
    static Map<String, Object> withoutMember(Map<String, Object> held, String author, String spki,
            String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        List<Object> members = new ArrayList<>();
        for (Map<String, Object> member : members(held)) {
            if (!spki.equals(TinyJson.string(member, SPKI, ""))) {
                members.add(member);
            }
        }
        next.put("members", members);
        Map<String, Long> revoked = revokedAt(held);
        revoked.put(spki, version(next));
        next.put("revoked", revokedList(revoked));
        return next;
    }

    private static Map<String, Object> bumped(Map<String, Object> held, String author,
            String updated) {
        Map<String, Object> next = new LinkedHashMap<>(held);
        next.remove("signature");
        long version = version(held) + 1;
        next.put("version", version);
        next.put("base_version", version - 1);
        next.put("author", author);
        next.put("updated", updated);
        return next;
    }

    private static Map<String, Object> groupEntries(Map<String, Object> document) {
        Map<String, Object> groups = new LinkedHashMap<>();
        if (document.get("groups") instanceof Map) {
            groups.putAll(TinyJson.object(document.get("groups")));
        }
        return groups;
    }

    private static Map<String, Object> profileEntries(Map<String, Object> document) {
        Map<String, Object> profile = new LinkedHashMap<>();
        if (document.get("profile") instanceof Map) {
            profile.putAll(TinyJson.object(document.get("profile")));
        }
        return profile;
    }

    /** The values {@code group} shares for {@code section}, or null when it does not. */
    static Map<String, Object> sectionValues(Map<String, Object> document, String group,
            String section) {
        Map<String, Object> entry = profileEntry(document, group, section);
        Object values = entry == null ? null : entry.get("values");
        return values instanceof Map ? TinyJson.object(values) : null;
    }

    private static Map<String, Object> profileEntry(Map<String, Object> document, String group,
            String section) {
        Object profile = document == null ? null : document.get("profile");
        Object ofGroup = profile instanceof Map ? TinyJson.object(profile).get(group) : null;
        Object entry = ofGroup instanceof Map ? TinyJson.object(ofGroup).get(section) : null;
        return entry instanceof Map ? TinyJson.object(entry) : null;
    }

    /** The version at which {@code group} last shared, changed or unshared {@code section}. */
    static long sectionAt(Map<String, Object> document, String group, String section) {
        Map<String, Object> entry = profileEntry(document, group, section);
        return entry == null ? 0 : numberOf(entry, AT);
    }

    /** The groups that share {@code section}, each with the version of its last change. */
    static Map<String, Long> sharedBy(Map<String, Object> document, String section) {
        Map<String, Long> by = new LinkedHashMap<>();
        Object profile = document == null ? null : document.get("profile");
        if (!(profile instanceof Map)) {
            return by;
        }
        Map<String, String> known = groups(document);
        for (String group : TinyJson.object(profile).keySet()) {
            if ((EVERYONE.equals(group) || known.containsKey(group))
                    && sectionValues(document, group, section) != null) {
                by.put(group, sectionAt(document, group, section));
            }
        }
        return by;
    }

    /**
     * The next document with {@code section} shared in {@code group} with {@code values}, or
     * unshared there when they are null, by {@code author}: one version higher, the entry
     * stamped with it, everything else carried over. The entry stays when unsharing, without
     * values, so the version of the unsharing is known to the merge.
     */
    static Map<String, Object> withSection(Map<String, Object> held, String author, String group,
            String section, Map<String, Object> values, String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        Map<String, Object> profile = profileEntries(next);
        Map<String, Object> ofGroup = new LinkedHashMap<>();
        if (profile.get(group) instanceof Map) {
            ofGroup.putAll(TinyJson.object(profile.get(group)));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(AT, version(next));
        if (values != null) {
            entry.put("values", new LinkedHashMap<>(values));
        }
        ofGroup.put(section, entry);
        profile.put(group, ofGroup);
        next.put("profile", profile);
        return next;
    }

    /**
     * The secrets in {@code document}'s profile, by group and key ({@code group/key}), blanked
     * in place; an empty one too.
     */
    static Map<String, String> stripSecrets(Map<String, Object> document) {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (String group : profileGroups(document)) {
            for (String section : SECTIONS) {
                Map<String, Object> values = sectionValues(document, group, section);
                if (values == null) {
                    continue;
                }
                for (String key : SECRET_KEYS) {
                    if (values.get(key) instanceof String) {
                        secrets.put(group + "/" + key, (String) values.get(key));
                        values.put(key, "");
                    }
                }
            }
        }
        return secrets;
    }

    /** Puts {@code secrets} back where {@link #stripSecrets} blanked them. */
    static void fillSecrets(Map<String, Object> document, Map<String, String> secrets) {
        for (String group : profileGroups(document)) {
            for (String section : SECTIONS) {
                Map<String, Object> values = sectionValues(document, group, section);
                if (values == null) {
                    continue;
                }
                for (String key : SECRET_KEYS) {
                    if (values.containsKey(key) && secrets.containsKey(group + "/" + key)) {
                        values.put(key, secrets.get(group + "/" + key));
                    }
                }
            }
        }
    }

    /** Every group with an entry in the profile, Everyone included, removed ones too. */
    static List<String> profileGroups(Map<String, Object> document) {
        Object profile = document == null ? null : document.get("profile");
        return profile instanceof Map ? new ArrayList<>(TinyJson.object(profile).keySet())
                : new ArrayList<>();
    }

    /** The fleet's Pro block (FleetTier), or null when there is none. */
    static Map<String, Object> tierOf(Map<String, Object> document) {
        Object tier = document == null ? null : document.get("tier");
        return tier instanceof Map && !((Map<?, ?>) tier).isEmpty() ? TinyJson.object(tier) : null;
    }

    /** The next document with {@code tier} as the fleet's Pro block. */
    static Map<String, Object> withTier(Map<String, Object> held, String author,
            Map<String, Object> tier, String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        next.put("tier", new LinkedHashMap<>(tier));
        return next;
    }

    /** The revoked key hashes. */
    static Set<String> revoked(Map<String, Object> document) {
        return revokedAt(document).keySet();
    }

    /**
     * Each revoked key hash with the version that revoked it, an entry {@code {"spki", "at"}}.
     * A member entry carries {@code since}, the version that admitted it, so a key joined again
     * after its revocation (since past at) is told from one that never heard of it.
     */
    static Map<String, Long> revokedAt(Map<String, Object> document) {
        Map<String, Long> revoked = new TreeMap<>();
        Object list = document == null ? null : document.get("revoked");
        if (list instanceof List) {
            for (Object one : (List<?>) list) {
                if (one instanceof Map) {
                    Map<String, Object> entry = TinyJson.object(one);
                    String spki = TinyJson.string(entry, SPKI, "");
                    if (spki.matches("[0-9a-f]{64}")) {
                        revoked.put(spki, numberOf(entry, AT));
                    }
                }
            }
        }
        return revoked;
    }

    /** Any number, as read back (a Double) or as put by this app (a Long); 0 when missing. */
    private static long numberOf(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private static List<Object> revokedList(Map<String, Long> revoked) {
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : revoked.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put(SPKI, entry.getKey());
            one.put(AT, entry.getValue());
            list.add(one);
        }
        return list;
    }

    /** Every member's key hash that is not revoked: the keys a member admits. */
    static Set<String> trustedKeys(Map<String, Object> document) {
        Set<String> trusted = new HashSet<>();
        Set<String> revoked = revoked(document);
        for (Map<String, Object> member : members(document)) {
            String spki = TinyJson.string(member, SPKI, "");
            if (spki.matches("[0-9a-f]{64}") && !revoked.contains(spki)) {
                trusted.add(spki);
            }
        }
        return trusted;
    }

    /** Whether {@code spki} is a manager in {@code document}, and not revoked. */
    static boolean isManager(Map<String, Object> document, String spki) {
        if (spki == null || revoked(document).contains(spki)) {
            return false;
        }
        for (Map<String, Object> member : members(document)) {
            if (spki.equals(TinyJson.string(member, SPKI, ""))
                    && TinyJson.flag(member, MANAGER, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The next document after {@code held} (null before the first) with {@code member} added,
     * written by {@code self}, unsigned: one version higher, the fleet named as held (or as given
     * for a new one), every other entry and field carried over. Both entries replace any entry
     * with the same id or key; an old key under the same id is revoked at this version (a reset
     * panel comes back with a new key), and the added key, admitted since this version, leaves
     * the revoked list: a fresh join is the one way back in. {@code updated} is a note for
     * people, never compared.
     */
    static Map<String, Object> withMember(Map<String, Object> held, String fleetId,
            String fleetName, Map<String, Object> self, Map<String, Object> member,
            String updated) {
        Map<String, Object> next = held == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(held);
        next.remove("signature");
        if (held == null) {
            Map<String, Object> fleet = new LinkedHashMap<>();
            fleet.put(ID, fleetId);
            fleet.put(NAME, fleetName.trim());
            fleet.put("created", updated);
            next.put("fleet", fleet);
            next.put("profile", new LinkedHashMap<String, Object>());
            next.put("tier", new LinkedHashMap<String, Object>());
        }
        long version = version(held);
        next.put("version", version + 1);
        next.put("base_version", version);
        next.put("author", TinyJson.string(self, ID, ""));
        next.put("updated", updated);
        Map<String, Long> revoked = revokedAt(held);
        List<Object> members = new ArrayList<>();
        self = new LinkedHashMap<>(self);
        self.put(SINCE, version + 1);
        member = new LinkedHashMap<>(member);
        member.put(SINCE, version + 1);
        for (Map<String, Object> one : members(held)) {
            if (TinyJson.string(one, SPKI, "").equals(TinyJson.string(self, SPKI, ""))) {
                self.put(SINCE, numberOf(one, SINCE));
                // Everything else the entry says of this panel (its groups, whether it follows
                // the fleet, the stamp that keeps them through a merge) carries over.
                for (Map.Entry<String, Object> field : one.entrySet()) {
                    if (!self.containsKey(field.getKey())) {
                        self.put(field.getKey(), field.getValue());
                    }
                }
            }
            if (replaces(self, one, revoked, version + 1)
                    || replaces(member, one, revoked, version + 1)) {
                continue;
            }
            members.add(one);
        }
        members.add(0, self);
        members.add(member);
        revoked.remove(TinyJson.string(member, SPKI, ""));
        revoked.remove(TinyJson.string(self, SPKI, ""));
        next.put("members", members);
        next.put("revoked", revokedList(revoked));
        return next;
    }

    /** Whether {@code entry} takes {@code old}'s place; an old key under its id is revoked. */
    private static boolean replaces(Map<String, Object> entry, Map<String, Object> old,
            Map<String, Long> revoked, long at) {
        String spki = TinyJson.string(entry, SPKI, "");
        String oldSpki = TinyJson.string(old, SPKI, "");
        if (spki.equals(oldSpki)) {
            return true;
        }
        if (TinyJson.string(entry, ID, "").equals(TinyJson.string(old, ID, ""))) {
            if (oldSpki.matches("[0-9a-f]{64}")) {
                revoked.put(oldSpki, at);
            }
            return true;
        }
        return false;
    }

    /**
     * Null when a member may take {@code incoming} (merged with what it holds, {@link #merge}),
     * signed by {@code signer}, the reason otherwise. {@code held} is its current document, or
     * null before the first; {@code fleetId} the fleet it joined; {@code firstManager} the keys
     * it trusts from the join, which may sign the first document; {@code revokedHeld} what it
     * already holds revoked. Versions are not compared: an older document can still carry a
     * panel added apart, and the merge takes nothing a newer one revoked.
     */
    static String refusal(Map<String, Object> held, Map<String, Object> incoming, String signer,
            String fleetId, Set<String> firstManager, Set<String> revokedHeld) {
        if (signer == null) {
            return "the signature does not match the document";
        }
        if (fleetId.isEmpty() || !fleetId.equals(fleetId(incoming))) {
            return "the document is for another fleet";
        }
        if (revokedHeld.contains(signer)) {
            return "the signer's key is revoked";
        }
        boolean manager = held == null ? firstManager.contains(signer) : isManager(held, signer);
        if (!manager) {
            return "the signer is not a manager of this fleet";
        }
        if (members(incoming).isEmpty()) {
            return "the document lists no members";
        }
        if (version(incoming) < 1) {
            return "version " + version(incoming) + " is not a fleet document version";
        }
        return null;
    }

    /**
     * Which of two documents counts as the newer: the higher version, and at the same version
     * the lower author id, so every panel settles a tie the same way.
     */
    static boolean newer(Map<String, Object> a, Map<String, Object> b) {
        if (b == null) {
            return true;
        }
        if (a == null) {
            return false;
        }
        if (version(a) != version(b)) {
            return version(a) > version(b);
        }
        return author(a).compareTo(author(b)) < 0;
    }

    /**
     * The two documents combined, as every panel combines them, so panels that changed the
     * fleet apart end with the same list once they meet, whatever the order: every member either
     * lists, under the entry of the newer document where both have it; every key either revokes,
     * at the later of the two versions, unless a member entry joined after that (a fresh join
     * after the removal); each shared section of the profile from the document that changed it
     * last; the fleet of the newer document, the tier confirmed later (whoever
     * wrote the document). Version, author and
     * signature are the newer document's and must be set anew when the result differs from both
     * inputs. {@code held} may be null.
     */
    static Map<String, Object> merge(Map<String, Object> held, Map<String, Object> incoming) {
        Map<String, Object> newer = newer(incoming, held) ? incoming : held;
        Map<String, Object> older = newer == incoming ? held : incoming;
        Map<String, Object> merged = new LinkedHashMap<>(newer);
        merged.remove("signature");
        Map<String, Long> revoked = revokedAt(newer);
        for (Map.Entry<String, Long> entry : revokedAt(older).entrySet()) {
            Long at = revoked.get(entry.getKey());
            if (at == null || at < entry.getValue()) {
                revoked.put(entry.getKey(), entry.getValue());
            }
        }
        List<Object> members = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> both = new ArrayList<>(members(newer));
        both.addAll(members(older));
        // Where both list a key, the entry edited last (its groups) wins, the newer document's on
        // a tie: both is the newer document's entries first.
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> member : both) {
            String key = TinyJson.string(member, SPKI, "");
            boolean replaced = false;
            for (int i = 0; i < picked.size(); i++) {
                Map<String, Object> other = picked.get(i);
                if (key.equals(TinyJson.string(other, SPKI, ""))) {
                    if (numberOf(member, AT) > numberOf(other, AT)) {
                        picked.set(i, member);
                    }
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                picked.add(member);
            }
        }
        for (Map<String, Object> member : picked) {
            String key = TinyJson.string(member, SPKI, "");
            if (!seen.add(key)) {
                continue;
            }
            Long at = revoked.get(key);
            if (at != null && numberOf(member, SINCE) <= at) {
                continue;
            }
            revoked.remove(key);
            members.add(member);
        }
        merged.put("members", members);
        merged.put("revoked", revokedList(revoked));
        // Pro's lease: the later confirmation, so an anchor's renewal is never lost to a document
        // that changed something else meanwhile.
        Map<String, Object> tier = FleetTier.newer(tierOf(newer), tierOf(older),
                System.currentTimeMillis());
        if (tier != null) {
            merged.put("tier", tier);
        }
        // Each group on its own: the entry changed last wins, the newer document on a tie; a
        // removed group stays removed.
        Map<String, Object> groups = new LinkedHashMap<>();
        Set<String> groupIds = new java.util.LinkedHashSet<>(groupEntries(newer).keySet());
        if (older != null) {
            groupIds.addAll(groupEntries(older).keySet());
        }
        for (String id : groupIds) {
            Object ours = groupEntries(newer).get(id);
            Object theirs = older == null ? null : groupEntries(older).get(id);
            Object winner = theirs instanceof Map && (!(ours instanceof Map)
                    || numberOf(TinyJson.object(theirs), AT) > numberOf(TinyJson.object(ours), AT))
                    ? theirs : ours;
            if (winner instanceof Map) {
                groups.put(id, winner);
            }
        }
        if (!groups.isEmpty() || newer.containsKey("groups")
                || (older != null && older.containsKey("groups"))) {
            merged.put("groups", groups);
        }
        // Each shared section of each group on its own: the later change wins, the newer
        // document on a tie; a removed group shares nothing.
        Map<String, Object> profile = new LinkedHashMap<>();
        Set<String> profileGroups = new java.util.LinkedHashSet<>(profileGroups(newer));
        profileGroups.addAll(profileGroups(older));
        for (String group : profileGroups) {
            Object entry = groups.get(group);
            if (entry instanceof Map && TinyJson.object(entry).containsKey("removed")) {
                continue;
            }
            Map<String, Object> ofGroup = new LinkedHashMap<>();
            for (String section : SECTIONS) {
                Map<String, Object> ours = profileEntry(newer, group, section);
                Map<String, Object> theirs = profileEntry(older, group, section);
                Map<String, Object> winner = theirs != null && (ours == null
                        || numberOf(theirs, AT) > numberOf(ours, AT)) ? theirs : ours;
                if (winner != null) {
                    ofGroup.put(section, winner);
                }
            }
            if (!ofGroup.isEmpty()) {
                profile.put(group, ofGroup);
            }
        }
        if (!profile.isEmpty() || newer.containsKey("profile")
                || (older != null && older.containsKey("profile"))) {
            merged.put("profile", profile);
        }
        return merged;
    }

    /**
     * Whether two documents say the same about the fleet: everything but the version, the
     * author, the base version, the time and the signature.
     */
    static boolean sameContent(Map<String, Object> a, Map<String, Object> b) {
        return canonical(content(a)).equals(canonical(content(b)));
    }

    private static Map<String, Object> content(Map<String, Object> document) {
        Map<String, Object> content = new LinkedHashMap<>(document);
        for (String key : new String[] {"signature", "version", "base_version", "author",
                "updated"}) {
            content.remove(key);
        }
        return content;
    }

    /**
     * What {@code after} changed against {@code before}, one line each, for the History card:
     * the fleet renamed, panels added or removed, groups made, renamed or removed, sections
     * shared, changed or unshared in a scope. Empty when nothing of that changed (a version
     * carrying only a member's stamp, say); {@code before} may be null for the first version.
     */
    static List<String> describeChange(Map<String, Object> before, Map<String, Object> after) {
        List<String> lines = new ArrayList<>();
        if (before != null && !fleetName(before).equals(fleetName(after))) {
            lines.add("fleet renamed to " + fleetName(after));
        }
        Map<String, String> membersBefore = memberNames(before);
        Map<String, String> membersAfter = memberNames(after);
        for (Map.Entry<String, String> member : membersAfter.entrySet()) {
            if (!membersBefore.containsKey(member.getKey())) {
                lines.add("panel " + member.getValue() + " added");
            }
        }
        for (Map.Entry<String, String> member : membersBefore.entrySet()) {
            if (!membersAfter.containsKey(member.getKey())) {
                lines.add("panel " + member.getValue() + " removed");
            }
        }
        Map<String, String> groupsBefore = groups(before);
        Map<String, String> groupsAfter = groups(after);
        for (Map.Entry<String, String> group : groupsAfter.entrySet()) {
            String was = groupsBefore.get(group.getKey());
            if (was == null) {
                lines.add("group " + group.getValue() + " made");
            } else if (!was.equals(group.getValue())) {
                lines.add("group " + was + " renamed to " + group.getValue());
            }
        }
        for (Map.Entry<String, String> group : groupsBefore.entrySet()) {
            if (!groupsAfter.containsKey(group.getKey())) {
                lines.add("group " + group.getValue() + " removed");
            }
        }
        Set<String> scopes = new java.util.LinkedHashSet<>(profileGroups(after));
        scopes.addAll(profileGroups(before));
        for (String scope : scopes) {
            if (!EVERYONE.equals(scope) && !groupsAfter.containsKey(scope)) {
                // A group removed, said above, shares nothing any more.
                continue;
            }
            String where = EVERYONE.equals(scope) ? "the whole fleet"
                    : "the group " + groupsAfter.get(scope);
            for (String section : SECTIONS) {
                Map<String, Object> was = sectionValues(before, scope, section);
                Map<String, Object> now = sectionValues(after, scope, section);
                String label = sectionLabel(section).toLowerCase(java.util.Locale.ROOT);
                if (was == null && now != null) {
                    lines.add(label + " shared with " + where);
                } else if (was != null && now == null) {
                    lines.add(label + " no longer shared with " + where);
                } else if (was != null && !canonical(was).equals(canonical(now))) {
                    lines.add(label + " changed for " + where);
                }
            }
        }
        return lines;
    }

    /** Each member's key hash with the name it is shown by. */
    private static Map<String, String> memberNames(Map<String, Object> document) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> member : members(document)) {
            String id = TinyJson.string(member, ID, "");
            names.put(TinyJson.string(member, SPKI, id), TinyJson.string(member, NAME, id));
        }
        return names;
    }

    /**
     * Restore as new: the next document after {@code held}, by {@code author}, with the fleet
     * name, the groups and the shared settings of the older version {@code old}, written as a
     * new, higher version, never a rollback of the version number. Membership is the present
     * and is never rolled back: the members and the revoked keys are the ones held now, so a
     * panel that joined after the old version stays in, and one removed since stays out. A
     * secret the old version carries blank (every stored copy blanks them) is taken from the
     * document held, where the same scope still shares it.
     */
    static Map<String, Object> restored(Map<String, Object> old, Map<String, Object> held,
            String author, String updated) {
        Map<String, Object> next = bumped(held, author, updated);
        long at = version(next);
        Map<String, Object> fleet = new LinkedHashMap<>(fleet(held));
        fleet.put(NAME, fleetName(old));
        next.put("fleet", fleet);
        // Every group either version knows, stamped with this version so the merge on every
        // other panel takes the restored state as the latest: live as the old version had it,
        // removed where the old version did not have it.
        Map<String, String> oldGroups = groups(old);
        Map<String, Object> groups = new LinkedHashMap<>();
        Set<String> ids = new java.util.LinkedHashSet<>(groupEntries(old).keySet());
        ids.addAll(groupEntries(held).keySet());
        for (String id : ids) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put(AT, at);
            if (oldGroups.containsKey(id)) {
                group.put(NAME, oldGroups.get(id));
            } else {
                group.put("removed", at);
            }
            groups.put(id, group);
        }
        next.put("groups", groups);
        // Each section of each scope the same way: shared with the old values, or unshared
        // where the old version did not share it and the held one does.
        Map<String, Object> profile = new LinkedHashMap<>();
        Set<String> scopes = new java.util.LinkedHashSet<>(profileGroups(old));
        scopes.addAll(profileGroups(held));
        for (String scope : scopes) {
            if (!EVERYONE.equals(scope) && !oldGroups.containsKey(scope)) {
                continue;
            }
            Map<String, Object> ofGroup = new LinkedHashMap<>();
            for (String section : SECTIONS) {
                Map<String, Object> values = sectionValues(old, scope, section);
                Map<String, Object> current = sectionValues(held, scope, section);
                // A section neither version knows in this scope stays absent; one either
                // knows, shared or unshared (an entry without values), is written here with
                // the new stamp, so the merge on every other panel takes this document as it
                // is instead of putting an older entry back and signing its own version.
                if (profileEntry(old, scope, section) == null
                        && profileEntry(held, scope, section) == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(AT, at);
                if (values != null) {
                    values = new LinkedHashMap<>(values);
                    for (String key : SECRET_KEYS) {
                        if (values.containsKey(key) && String.valueOf(values.get(key)).isEmpty()
                                && current != null && current.get(key) instanceof String) {
                            values.put(key, current.get(key));
                        }
                    }
                    entry.put("values", values);
                }
                ofGroup.put(section, entry);
            }
            if (!ofGroup.isEmpty()) {
                profile.put(scope, ofGroup);
            }
        }
        next.put("profile", profile);
        if (old.containsKey("tier")) {
            next.put("tier", old.get("tier"));
        }
        return next;
    }

    /**
     * {@code url} with the panel's facts in place of its placeholders, {@code {panel.name}},
     * {@code {panel.group}} and {@code {fleet.name}}, written plain or percent-encoded (a browser
     * may encode the braces of a pasted address), each value encoded for an address; an address
     * without placeholders comes back as it is. Pure, so the rules are host-tested.
     */
    static String fillPlaceholders(String url, String panelName, String group, String fleetName) {
        if (url == null || (url.indexOf('{') < 0 && !url.contains("%7B"))) {
            return url;
        }
        String filled = url;
        String[][] facts = {{"panel.name", panelName}, {"panel.group", group},
                {"fleet.name", fleetName}};
        for (String[] fact : facts) {
            String value = urlEncoded(fact[1] == null ? "" : fact[1]);
            filled = filled.replace("{" + fact[0] + "}", value)
                    .replace("%7B" + fact[0] + "%7D", value)
                    .replace("%7b" + fact[0] + "%7d", value);
        }
        return filled;
    }

    private static String urlEncoded(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
