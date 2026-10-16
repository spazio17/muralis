/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
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
 * The fleet document (fleet design, section 6; block 12a): one JSON every member holds, naming the
 * fleet, its members and the keys revoked, at a version, signed by the manager who wrote it.
 *
 * <p>Signed with ECDSA P-256 over the document's canonical form (keys sorted at every level, no
 * spaces, as {@link TinyJson} writes) without its {@code signature}, which carries the signer's
 * key hash ({@code by}), its public key ({@code key}, so a member needs nothing else to check it)
 * and the signature itself, both in base64. A member takes a document only from a manager it
 * already knows, at a higher version; two managers' edits of the same version are settled without
 * the clock: one that revokes a key wins, otherwise the lower author id.
 *
 * <p>Pure, with no Android imports, so the rules are covered by host tests. The profile, the
 * overrides and the tier block are carried but not yet applied (blocks 12d and 14).
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
            next.put("overrides", new LinkedHashMap<String, Object>());
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
     * after the removal); the fleet, the profile, the overrides and the tier of the newer
     * document. Version, author and signature are the newer document's and must be set anew
     * when the result differs from both inputs. {@code held} may be null.
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
        for (Map<String, Object> member : both) {
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
}
