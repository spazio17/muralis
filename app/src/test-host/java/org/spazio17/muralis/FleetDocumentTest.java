/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The fleet document: canonical form, signature, and which documents a member takes. */
public final class FleetDocumentTest {

    public static void main(String[] args) throws Exception {
        KeyPair manager = pair();
        KeyPair second = pair();
        KeyPair stranger = pair();
        String managerSpki = FleetDocument.hashOf(manager.getPublic().getEncoded());
        String secondSpki = FleetDocument.hashOf(second.getPublic().getEncoded());
        String memberSpki = repeat('b', 64);

        // Canonical: keys sorted at every level, no spaces, whatever order they were put in.
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1);
        a.put("a", Collections.singletonMap("y", "ü"));
        require(FleetDocument.canonical(a).equals("{\"a\":{\"y\":\"ü\"},\"z\":1}"),
                "not canonical: " + FleetDocument.canonical(a));

        // Signed, read back, checked.
        Map<String, Object> first = document("fleet-1", 1, "kiosk-a", managerSpki, memberSpki,
                secondSpki);
        String text = FleetDocument.sign(first, manager.getPrivate(), manager.getPublic());
        Map<String, Object> read = FleetDocument.parse(text);
        require(managerSpki.equals(FleetDocument.signer(read)), "a good signature was refused");
        require(FleetDocument.fleetName(read).equals("Test home"), "the name did not read back");
        require(FleetDocument.version(read) == 1, "the version did not read back");
        require(FleetDocument.trustedKeys(read).equals(new HashSet<>(java.util.Arrays.asList(
                managerSpki, memberSpki, secondSpki))), "the trusted keys are wrong");

        // Any change after signing, or another key under the same name, is refused.
        Map<String, Object> tampered = FleetDocument.parse(text.replace("Test home", "Evil"));
        require(FleetDocument.signer(tampered) == null, "a changed document kept its signature");
        Map<String, Object> borrowed = FleetDocument.parse(text);
        @SuppressWarnings("unchecked")
        Map<String, Object> signature = (Map<String, Object>) borrowed.get("signature");
        signature.put("key", java.util.Base64.getEncoder()
                .encodeToString(stranger.getPublic().getEncoded()));
        require(FleetDocument.signer(borrowed) == null, "a key not matching its hash was taken");
        Map<String, Object> unsigned = FleetDocument.parse(text);
        unsigned.remove("signature");
        require(FleetDocument.signer(unsigned) == null, "an unsigned document was taken");
        require(FleetDocument.parse("not json") == null && FleetDocument.parse("[1]") == null,
                "text that is not an object was read as a document");

        Set<String> joinedBy = Collections.singleton(managerSpki);
        Set<String> none = Collections.emptySet();
        // The first document: from the manager the panel joined, for its fleet.
        require(FleetDocument.refusal(null, read, managerSpki, "fleet-1", joinedBy, none) == null,
                "the first document from the joining manager was refused");
        require(FleetDocument.refusal(null, read, managerSpki, "fleet-2", joinedBy, none) != null,
                "a document of another fleet was taken");
        require(FleetDocument.refusal(null, read, null, "fleet-1", joinedBy, none) != null,
                "a document with a bad signature was taken");
        require(FleetDocument.refusal(null, read, secondSpki, "fleet-1", joinedBy, none) != null,
                "a first document from a manager the panel never met was taken");
        require(FleetDocument.refusal(null, read, managerSpki, "fleet-1", joinedBy,
                Collections.singleton(managerSpki)) != null, "a revoked signer was taken");

        // Later documents: from any manager the held one lists, at a higher version.
        Map<String, Object> next = document("fleet-1", 2, "kiosk-c", managerSpki, memberSpki,
                secondSpki);
        require(FleetDocument.refusal(read, next, secondSpki, "fleet-1", none, none) == null,
                "a newer document from a listed manager was refused");
        require(FleetDocument.refusal(read, next, memberSpki, "fleet-1", none, none) != null,
                "a document from a member that is no manager was taken");
        require(FleetDocument.refusal(next, read, managerSpki, "fleet-1", none, none) == null,
                "an older version was refused; it may carry a panel added apart");
        require(FleetDocument.refusal(read, read, managerSpki, "fleet-1", none, none) == null,
                "the same document again was refused");
        Map<String, Object> empty = document("fleet-1", 3, "kiosk-a", managerSpki, memberSpki,
                secondSpki);
        empty.put("members", new ArrayList<>());
        require(FleetDocument.refusal(read, empty, managerSpki, "fleet-1", none, none) != null,
                "a document with no members was taken");

        // Two edits of version 2 made apart: the merge keeps the panel each one added.
        String addedByC = repeat('c', 64);
        String addedByB = repeat('f', 64);
        Map<String, Object> byC = document("fleet-1", 2, "kiosk-c", managerSpki, memberSpki,
                secondSpki);
        TinyJson.array(byC.get("members")).add(member("kiosk-x", "Cellar", addedByC, true));
        Map<String, Object> byB = document("fleet-1", 2, "kiosk-b", managerSpki, memberSpki,
                secondSpki);
        TinyJson.array(byB.get("members")).add(member("kiosk-y", "Garage", addedByB, true));
        Map<String, Object> merged = FleetDocument.merge(byC, byB);
        require(FleetDocument.trustedKeys(merged).containsAll(java.util.Arrays.asList(
                managerSpki, memberSpki, secondSpki, addedByC, addedByB))
                && FleetDocument.members(merged).size() == 5,
                "the merge lost a panel added apart");
        require(FleetDocument.sameContent(merged, FleetDocument.merge(byB, byC)),
                "the merge depends on the order");
        require(!FleetDocument.sameContent(merged, byB) && !FleetDocument.sameContent(merged, byC),
                "a merge that adds to both reads as one of them");
        require(FleetDocument.sameContent(FleetDocument.merge(merged, byB), merged),
                "merging an input again changed the result");
        require(FleetDocument.sameContent(FleetDocument.merge(read, byC), byC),
                "a document that only adds to the held one was not kept as it is");

        // A removal on either side holds, whatever the versions, and a merge never undoes it.
        Map<String, Object> removing = document("fleet-1", 2, "kiosk-z", managerSpki,
                memberSpki, secondSpki);
        removing.put("revoked", revoked(secondSpki, 2));
        TinyJson.array(removing.get("members")).remove(2);
        Map<String, Object> afterRemoval = FleetDocument.merge(byB, removing);
        require(FleetDocument.revoked(afterRemoval).contains(secondSpki)
                && !FleetDocument.trustedKeys(afterRemoval).contains(secondSpki),
                "a removal did not hold against an edit of the same version");
        Map<String, Object> later = document("fleet-1", 9, "kiosk-a", managerSpki, memberSpki,
                secondSpki);
        TinyJson.array(later.get("members")).remove(2);
        require(FleetDocument.revoked(FleetDocument.merge(later, removing)).contains(secondSpki),
                "a newer document without the key undid an older removal");
        // Unless the key joined again after the removal: a fresh join, since a later version.
        Map<String, Object> rejoined = document("fleet-1", 9, "kiosk-a", managerSpki, memberSpki,
                secondSpki);
        TinyJson.array(rejoined.get("members")).set(2,
                member("kiosk-c", "Hallway", secondSpki, true, 9));
        require(!FleetDocument.revoked(FleetDocument.merge(rejoined, removing)).contains(secondSpki)
                && FleetDocument.trustedKeys(FleetDocument.merge(removing, rejoined))
                        .contains(secondSpki),
                "a panel joined again after a removal stayed revoked");
        require(FleetDocument.newer(byB, byC) && !FleetDocument.newer(byC, byB)
                && FleetDocument.newer(later, byB) && FleetDocument.newer(byB, null),
                "the tie rule is not the lower author id, or versions do not order");

        // A revoked member is no manager and no longer trusted.
        Map<String, Object> revoking = document("fleet-1", 4, "kiosk-a", managerSpki, memberSpki,
                secondSpki);
        revoking.put("revoked", revoked(secondSpki, 4));
        require(!FleetDocument.isManager(revoking, secondSpki), "a revoked key stayed a manager");
        require(!FleetDocument.trustedKeys(revoking).contains(secondSpki),
                "a revoked key stayed trusted");

        // The manager adds panels: the first document, then the next, each one version up.
        Map<String, Object> self = member("kiosk-a", "kiosk-a", managerSpki, true);
        Map<String, Object> kitchen = member("kiosk-b", "Kitchen", memberSpki, true);
        Map<String, Object> founded = FleetDocument.withMember(null, "fleet-9", " Test home ",
                self, kitchen, "2026-10-12T20:00:00Z");
        require(FleetDocument.members(founded).get(1).get("since").equals(1L),
                "the added panel is not marked as joined since version 1");
        require(FleetDocument.version(founded) == 1 && FleetDocument.fleetId(founded)
                .equals("fleet-9") && FleetDocument.fleetName(founded).equals("Test home"),
                "the first document is not version 1 of the new fleet");
        require(FleetDocument.members(founded).size() == 2
                && FleetDocument.isManager(founded, managerSpki)
                && FleetDocument.author(founded).equals("kiosk-a"),
                "the first document does not list both panels with this one as author");
        String foundedText = FleetDocument.sign(founded, manager.getPrivate(),
                manager.getPublic());
        Map<String, Object> foundedRead = FleetDocument.parse(foundedText);
        require(FleetDocument.refusal(null, foundedRead, FleetDocument.signer(foundedRead),
                "fleet-9", joinedBy, none) == null, "the member refused the founding document");
        // The same panel added again with a new key (a reset): the old key is revoked.
        Map<String, Object> reset = member("kiosk-b", "Kitchen", repeat('e', 64), true);
        Map<String, Object> again = FleetDocument.withMember(foundedRead, "ignored", "ignored",
                self, reset, "2026-10-12T20:05:00Z");
        require(FleetDocument.version(again) == 2 && FleetDocument.members(again).size() == 2,
                "adding a panel again did not replace its entry");
        require(FleetDocument.revoked(again).contains(memberSpki)
                && FleetDocument.revokedAt(again).get(memberSpki) == 2
                && !FleetDocument.trustedKeys(again).contains(memberSpki),
                "the old key of a panel added again stayed trusted, or is not revoked at 2");
        require(FleetDocument.fleetName(again).equals("Test home")
                && again.get("signature") == null, "the next document lost the name or kept"
                + " the old signature");
        // A revoked key that joins again leaves the revoked list.
        Map<String, Object> back = FleetDocument.withMember(again, "", "", self, kitchen,
                "2026-10-12T20:10:00Z");
        require(!FleetDocument.revoked(back).contains(memberSpki)
                && FleetDocument.revoked(back).contains(repeat('e', 64)),
                "a key joining again stayed revoked, or the reset key was not");

        System.out.println("FleetDocumentTest passed");
    }

    private static Map<String, Object> document(String fleetId, long version, String author,
            String managerSpki, String memberSpki, String secondSpki) {
        Map<String, Object> fleet = new LinkedHashMap<>();
        fleet.put("id", fleetId);
        fleet.put("name", "Test home");
        List<Object> members = new ArrayList<>();
        members.add(member("kiosk-a", "Phone", managerSpki, true));
        members.add(member("kiosk-b", "Kitchen", memberSpki, false));
        members.add(member("kiosk-c", "Hallway", secondSpki, true));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fleet", fleet);
        document.put("version", version);
        document.put("author", author);
        document.put("members", members);
        document.put("revoked", new ArrayList<>());
        return document;
    }

    private static Map<String, Object> member(String id, String name, String spki,
            boolean manager) {
        return member(id, name, spki, manager, 1);
    }

    private static Map<String, Object> member(String id, String name, String spki,
            boolean manager, long since) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("id", id);
        member.put("name", name);
        member.put("spki", spki);
        member.put("manager", manager);
        member.put("since", since);
        return member;
    }

    private static List<Object> revoked(String spki, long at) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("spki", spki);
        one.put("at", at);
        return new ArrayList<>(Collections.singletonList(one));
    }

    private static KeyPair pair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(c);
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
