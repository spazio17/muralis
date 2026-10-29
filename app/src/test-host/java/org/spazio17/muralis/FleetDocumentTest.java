/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
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
        // Adding a panel keeps the adder's own groups and stamp.
        Map<String, Object> selfInLobby = member("kiosk-a", "kiosk-a", managerSpki, true);
        Map<String, Object> keptGroups = FleetDocument.withMember(FleetDocument.withMemberGroups(
                FleetDocument.withGroup(founded, "kiosk-a", "group-9", "Lobby", "2026-10-12T20:01:00Z"),
                "kiosk-a", managerSpki, Collections.singletonList("group-9"), "2026-10-12T20:02:00Z"),
                "fleet-9", "Test home", selfInLobby, member("kiosk-z", "Z", repeat('a', 64), true),
                "2026-10-12T20:03:00Z");
        require(FleetDocument.memberGroups(keptGroups, FleetDocument.memberByKey(keptGroups,
                managerSpki)).contains("group-9"), "adding a panel dropped the adder's groups");
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

        // The profile, per group: a section shared in Everyone from one panel, changed apart on
        // two, the later wins; unsharing is a change too; secrets leave the stored copy and come
        // back for the wire.
        String all = FleetDocument.EVERYONE;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dashboard_url", "https://a.example/");
        Map<String, Object> shared = FleetDocument.withSection(read, "kiosk-a", all, "dashboard",
                values, "2026-10-12T21:00:00Z");
        require(FleetDocument.version(shared) == 2
                && FleetDocument.sectionAt(shared, all, "dashboard") == 2
                && "https://a.example/".equals(FleetDocument.sectionValues(shared, all,
                "dashboard").get("dashboard_url")), "sharing a section did not stamp it");
        require(FleetDocument.sectionValues(shared, all, "mqtt") == null
                && FleetDocument.sectionAt(shared, all, "mqtt") == 0,
                "an unshared section read as shared");
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("dashboard_url", "https://b.example/");
        Map<String, Object> byB2 = FleetDocument.withSection(shared, "kiosk-b", all, "dashboard",
                v2, "2026-10-12T21:01:00Z");
        Map<String, Object> byC2 = FleetDocument.withSection(FleetDocument.withSection(shared,
                "kiosk-c", all, "mqtt", Collections.singletonMap("mqtt_host", "broker"),
                "2026-10-12T21:01:00Z"), "kiosk-c", all, "dashboard", values,
                "2026-10-12T21:02:00Z");
        Map<String, Object> both = FleetDocument.merge(byB2, byC2);
        require("https://a.example/".equals(FleetDocument.sectionValues(both, all, "dashboard")
                .get("dashboard_url")), "the later change of a section lost");
        require("broker".equals(FleetDocument.sectionValues(both, all, "mqtt").get("mqtt_host")),
                "a section shared on one side only was lost");
        Map<String, Object> unshared = FleetDocument.withSection(both, "kiosk-a", all, "mqtt",
                null, "2026-10-12T21:03:00Z");
        require(FleetDocument.sectionValues(unshared, all, "mqtt") == null
                && FleetDocument.sectionValues(FleetDocument.merge(byC2, unshared), all, "mqtt")
                        == null, "unsharing did not hold in a merge");
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("mqtt_host", "broker");
        secret.put("mqtt_password", "hunter2");
        Map<String, Object> withSecret = FleetDocument.withSection(read, "kiosk-a", all, "mqtt",
                secret, "2026-10-12T21:04:00Z");
        Map<String, String> taken = FleetDocument.stripSecrets(withSecret);
        require("hunter2".equals(taken.get(all + "/mqtt_password")) && "".equals(
                FleetDocument.sectionValues(withSecret, all, "mqtt").get("mqtt_password")),
                "the secret was not stripped");
        FleetDocument.fillSecrets(withSecret, taken);
        require("hunter2".equals(FleetDocument.sectionValues(withSecret, all, "mqtt")
                .get("mqtt_password")), "the secret did not come back");

        // Groups: made with a name, a member put in one, a section shared in it, two groups
        // sharing the same section told apart, a removed group gone for good, and the later
        // edit of a member's groups winning a merge.
        require(FleetDocument.validateGroupName("Whole fleet") != null
                && FleetDocument.followsFleet(FleetDocument.memberByKey(read, memberSpki))
                && !FleetDocument.followsFleet(FleetDocument.memberByKey(
                        FleetDocument.withMemberFollowsFleet(read, "kiosk-a", memberSpki, false,
                                "2026-10-12T21:04:30Z"), memberSpki)),
                "the follow flag is wrong");
        Map<String, Object> renamed = FleetDocument.memberByKey(FleetDocument.withMemberSelf(read,
                memberSpki, "hall", "10.0.0.9", 8090, "2026-10-12T21:04:40Z"), memberSpki);
        require("hall".equals(TinyJson.string(renamed, FleetDocument.ID, ""))
                && "hall".equals(TinyJson.string(renamed, FleetDocument.NAME, ""))
                && "[10.0.0.9]".equals(String.valueOf(renamed.get("addresses")))
                && ((Number) renamed.get("port")).longValue() == 8090,
                "a panel's own entry kept its old name, address or port");
        require(FleetDocument.describeChange(read, FleetDocument.withMemberSelf(read, memberSpki,
                "hall", "", 8081, "2026-10-12T21:04:40Z")).toString().contains(" renamed to hall"),
                "the history does not say the panel was renamed");
        require(FleetDocument.validateGroupName("Everyone") != null
                && FleetDocument.validateGroupName(" ") != null
                && FleetDocument.validateGroupName("Lobby") == null, "the group name rule");
        Map<String, Object> lobby = FleetDocument.withGroup(shared, "kiosk-a", "group-1", "Lobby",
                "2026-10-12T21:05:00Z");
        require("Lobby".equals(FleetDocument.groups(lobby).get("group-1"))
                && "Lobby".equals(FleetDocument.groupName(lobby, "group-1"))
                && FleetDocument.EVERYONE_NAME.equals(FleetDocument.groupName(lobby, all)),
                "the group was not made");
        Map<String, Object> inLobby = FleetDocument.withMemberGroups(lobby, "kiosk-a", memberSpki,
                java.util.Arrays.asList("group-1", all), "2026-10-12T21:06:00Z");
        require(FleetDocument.memberGroups(FleetDocument.memberByKey(inLobby, memberSpki))
                .equals(java.util.Arrays.asList(all, "group-1"))
                && FleetDocument.memberGroups(FleetDocument.memberByKey(inLobby, managerSpki))
                        .equals(Collections.singletonList(all)), "the member's groups are wrong");
        Map<String, Object> lobbyUrl = new LinkedHashMap<>();
        lobbyUrl.put("dashboard_url", "https://lobby.example/");
        Map<String, Object> lobbyShares = FleetDocument.withSection(inLobby, "kiosk-a", "group-1",
                "dashboard", lobbyUrl, "2026-10-12T21:07:00Z");
        require(FleetDocument.sharedBy(lobbyShares, "dashboard").keySet()
                .equals(new HashSet<>(java.util.Arrays.asList(all, "group-1")))
                && FleetDocument.sharedBy(lobbyShares, "mqtt").isEmpty(),
                "which groups share a section is wrong");
        // Which group's values apply on a member: its own group over the whole fleet.
        require("group-1".equals(FleetDocument.sourceGroup(lobbyShares,
                        FleetDocument.memberByKey(lobbyShares, memberSpki), "dashboard"))
                && all.equals(FleetDocument.sourceGroup(lobbyShares,
                        FleetDocument.memberByKey(lobbyShares, managerSpki), "dashboard"))
                && FleetDocument.sourceGroup(lobbyShares,
                        FleetDocument.memberByKey(lobbyShares, managerSpki), "mqtt") == null,
                "the group a member takes a section from is wrong");
        Map<String, Object> removed = FleetDocument.withoutGroup(lobbyShares, "kiosk-a",
                "group-1", "2026-10-12T21:08:00Z");
        require(FleetDocument.memberGroups(removed, FleetDocument.memberByKey(removed, memberSpki))
                .equals(Collections.singletonList(all)), "a removed group still counted for a member");
        require(!FleetDocument.groups(removed).containsKey("group-1")
                && FleetDocument.sectionValues(removed, "group-1", "dashboard") == null
                && !FleetDocument.groups(FleetDocument.merge(lobbyShares, removed))
                        .containsKey("group-1")
                && FleetDocument.sectionValues(FleetDocument.merge(removed, lobbyShares),
                        "group-1", "dashboard") == null,
                "a removed group came back in a merge");
        Map<String, Object> editA = FleetDocument.withMemberGroups(inLobby, "kiosk-a", memberSpki,
                Collections.singletonList(all), "2026-10-12T21:09:00Z");
        Map<String, Object> editC = FleetDocument.withMemberGroups(FleetDocument.withGroup(inLobby,
                "kiosk-c", "group-2", "Offices", "2026-10-12T21:09:00Z"), "kiosk-c", memberSpki,
                java.util.Arrays.asList("group-1", "group-2"), "2026-10-12T21:10:00Z");
        Map<String, Object> edits = FleetDocument.merge(editA, editC);
        require(FleetDocument.memberGroups(FleetDocument.memberByKey(edits, memberSpki))
                .equals(java.util.Arrays.asList(all, "group-1", "group-2"))
                && "Offices".equals(FleetDocument.groups(edits).get("group-2")),
                "the later edit of a member's groups lost, or a group made apart was lost");

        // A member that left, or was removed: off the list, its key revoked, and an older list
        // merged in does not bring it back.
        Map<String, Object> gone = FleetDocument.withoutMember(inLobby, "kiosk-a", memberSpki,
                "2026-10-12T21:11:00Z");
        require(FleetDocument.memberByKey(gone, memberSpki) == null
                && FleetDocument.revoked(gone).contains(memberSpki)
                && !FleetDocument.trustedKeys(gone).contains(memberSpki)
                && FleetDocument.memberByKey(gone, managerSpki) != null
                && FleetDocument.memberByKey(FleetDocument.merge(gone, inLobby), memberSpki) == null
                && FleetDocument.memberByKey(FleetDocument.merge(inLobby, gone), memberSpki) == null,
                "a member taken off the list stayed, or came back in a merge");

        // Placeholders in a page address: plain or percent-encoded, values encoded, none left.
        require("https://x/rooms/Meeting%20room%202?g=Lobby&f=New%20Home".equals(
                FleetDocument.fillPlaceholders("https://x/rooms/{panel.name}?g={panel.group}&f={fleet.name}",
                        "Meeting room 2", "Lobby", "New Home")), "the placeholders were not filled");
        require("https://x/?room=Hall".equals(FleetDocument.fillPlaceholders(
                "https://x/?room=%7Bpanel.name%7D", "Hall", "", "")),
                "a percent-encoded placeholder was not filled");
        require("https://x/?g=".equals(FleetDocument.fillPlaceholders("https://x/?g={panel.group}",
                "Hall", "", "")) && "https://x/plain".equals(FleetDocument.fillPlaceholders(
                "https://x/plain", "Hall", "Lobby", "F")), "an empty fact or a plain address is wrong");

        // History: what a version changed against the one before, in words.
        Map<String, Object> h1 = document("fleet-1", 1, "kiosk-a", managerSpki, memberSpki,
                secondSpki);
        require(FleetDocument.describeChange(null, h1).size() == 3
                && FleetDocument.describeChange(null, h1).get(0).equals("panel Phone added"),
                "the first version's change is wrong: " + FleetDocument.describeChange(null, h1));
        require(FleetDocument.describeChange(h1, h1).isEmpty(), "no change read as one");
        Map<String, Object> h2 = FleetDocument.withGroup(h1, "kiosk-a", "group-1", "Lobby", "t");
        require(FleetDocument.describeChange(h1, h2).equals(
                java.util.Collections.singletonList("group Lobby made")),
                "a group made is wrong: " + FleetDocument.describeChange(h1, h2));
        Map<String, Object> h3 = FleetDocument.withSection(h2, "kiosk-a", "group-1", "dashboard",
                java.util.Collections.<String, Object>singletonMap("dashboard_url", "https://a/"), "t");
        require(FleetDocument.describeChange(h2, h3).equals(
                java.util.Collections.singletonList("dashboard shared with the group Lobby")),
                "a section shared is wrong: " + FleetDocument.describeChange(h2, h3));
        Map<String, Object> h4 = FleetDocument.withSection(h3, "kiosk-a", "group-1", "dashboard",
                java.util.Collections.<String, Object>singletonMap("dashboard_url", "https://b/"), "t");
        require(FleetDocument.describeChange(h3, h4).equals(
                java.util.Collections.singletonList("dashboard changed for the group Lobby")),
                "a section changed is wrong: " + FleetDocument.describeChange(h3, h4));
        Map<String, Object> h5 = FleetDocument.withSection(h4, "kiosk-a", "group-1", "dashboard",
                null, "t");
        require(FleetDocument.describeChange(h4, h5).equals(
                java.util.Collections.singletonList("dashboard no longer shared with the group Lobby")),
                "a section unshared is wrong: " + FleetDocument.describeChange(h4, h5));
        // A panel put in a group and taken out again.
        Map<String, Object> g6 = FleetDocument.withMemberGroups(h5, "kiosk-a", memberSpki,
                java.util.Collections.singletonList("group-1"), "t");
        require(FleetDocument.describeChange(h5, g6).equals(
                java.util.Collections.singletonList("panel Kitchen put in the group Lobby")),
                "a panel put in a group is wrong: " + FleetDocument.describeChange(h5, g6));
        require(FleetDocument.version(g6) == FleetDocument.version(h5) + 1,
                "putting a panel in a group is not one version");
        Map<String, Object> g7 = FleetDocument.withMemberGroups(g6, "kiosk-a", memberSpki,
                java.util.Collections.<String>emptyList(), "t");
        require(FleetDocument.describeChange(g6, g7).equals(
                java.util.Collections.singletonList("panel Kitchen taken out of the group Lobby")),
                "a panel taken out of a group is wrong: " + FleetDocument.describeChange(g6, g7));
        Map<String, Object> h6 = FleetDocument.withoutGroup(h5, "kiosk-a", "group-1", "t");
        require(FleetDocument.describeChange(h5, h6).equals(
                java.util.Collections.singletonList("group Lobby removed")),
                "a group removed is wrong: " + FleetDocument.describeChange(h5, h6));
        Map<String, Object> h7 = FleetDocument.withSection(h6, "kiosk-a", "everyone", "alerts",
                java.util.Collections.<String, Object>singletonMap("alert_page_error", true), "t");
        require(FleetDocument.describeChange(h6, h7).equals(
                java.util.Collections.singletonList("alerts shared with the whole fleet")),
                "the whole fleet's share is wrong: " + FleetDocument.describeChange(h6, h7));
        Map<String, Object> h8 = FleetDocument.withMember(h7, "fleet-1", "Test home",
                member("kiosk-a", "Phone", managerSpki, true), member("kiosk-d", "Bar",
                        repeat('d', 64), false), "t");
        require(FleetDocument.describeChange(h7, h8).equals(
                java.util.Collections.singletonList("panel Bar added")),
                "a panel added is wrong: " + FleetDocument.describeChange(h7, h8));

        // Restore as new: the old version's name, groups and shared settings at a new, higher
        // version; the members and revoked keys held now, so a panel added since stays in.
        Map<String, Object> restoredBack = FleetDocument.restored(h4, h8, "kiosk-b", "t2");
        require(FleetDocument.version(restoredBack) == 9 && "kiosk-b".equals(FleetDocument.author(restoredBack))
                && FleetDocument.version(h8) == 8, "the restored version is not the next one");
        require(FleetDocument.members(restoredBack).size() == 4
                && FleetDocument.memberByKey(restoredBack, repeat('d', 64)) != null,
                "a panel added after the old version was dropped by the restore");
        require("Lobby".equals(FleetDocument.groups(restoredBack).get("group-1"))
                && "https://b/".equals(FleetDocument.sectionValues(restoredBack, "group-1", "dashboard")
                        .get("dashboard_url")), "the old group and its share did not come restoredBack");
        require(FleetDocument.sectionValues(restoredBack, "everyone", "alerts") == null
                && FleetDocument.sectionAt(restoredBack, "everyone", "alerts") == 9,
                "a section shared after the old version was not unshared at the new version");
        require(FleetDocument.sectionAt(restoredBack, "group-1", "dashboard") == 9,
                "the restored share is not stamped with the new version");
        // Merged on another panel that holds h8: the restored state wins everywhere.
        Map<String, Object> onOther = FleetDocument.merge(h8, restoredBack);
        require("Lobby".equals(FleetDocument.groups(onOther).get("group-1"))
                && FleetDocument.sectionValues(onOther, "everyone", "alerts") == null
                && FleetDocument.sameContent(onOther, restoredBack),
                "the restore did not win the merge on another panel");
        // The other way: a group removed since is removed again by a restore of a version
        // without it, so every panel ends the same.
        Map<String, Object> toV6 = FleetDocument.restored(h6, h8, "kiosk-b", "t3");
        require(FleetDocument.groups(toV6).isEmpty()
                && FleetDocument.sameContent(FleetDocument.merge(h8, toV6), toV6),
                "restoring a version without the group did not remove it everywhere");
        // A section unshared since (an entry without values) is carried, stamped anew, so the
        // merge on a panel that held the stub takes the restored document as it is.
        Map<String, Object> h9 = FleetDocument.withSection(h8, "kiosk-a", "everyone", "alerts",
                null, "t");
        Map<String, Object> overStub = FleetDocument.restored(h4, h9, "kiosk-b", "t5");
        require(FleetDocument.sectionValues(overStub, "everyone", "alerts") == null
                && FleetDocument.sectionAt(overStub, "everyone", "alerts") == 10
                && FleetDocument.sameContent(FleetDocument.merge(h9, overStub), overStub),
                "a restore over an unshared section is rewritten by the merge on another panel");
        // A secret the old version carries blank is taken from the list held now.
        Map<String, Object> mqttValues = new LinkedHashMap<>();
        mqttValues.put("mqtt_host", "broker");
        mqttValues.put("mqtt_password", "secret");
        Map<String, Object> withMqtt = FleetDocument.withSection(h8, "kiosk-a", "everyone", "mqtt",
                mqttValues, "t");
        Map<String, Object> blanked = FleetDocument.parse(FleetDocument.canonical(withMqtt));
        FleetDocument.stripSecrets(blanked);
        Map<String, Object> fromBlank = FleetDocument.restored(blanked, withMqtt, "kiosk-a", "t4");
        require("secret".equals(FleetDocument.sectionValues(fromBlank, "everyone", "mqtt")
                .get("mqtt_password")), "the blank secret was not taken from the list held");

        // The groups' order: a panel in two groups that both share a section takes it from the
        // higher one; moved, the other wins; the order survives a merge and is in the history.
        Map<String, Object> twoGroups = FleetDocument.withGroup(FleetDocument.withGroup(h8,
                "kiosk-a", "group-a", "Entrance", "t"), "kiosk-a", "group-b", "Lobby", "t");
        twoGroups = FleetDocument.withMemberGroups(twoGroups, "kiosk-a", memberSpki,
                java.util.Arrays.asList("group-a", "group-b"), "t");
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("dashboard_url", "https://a.example/");
        twoGroups = FleetDocument.withSection(twoGroups, "kiosk-a", "group-a", "dashboard", page,
                "t");
        page.put("dashboard_url", "https://b.example/");
        twoGroups = FleetDocument.withSection(twoGroups, "kiosk-a", "group-b", "dashboard", page,
                "t");
        Map<String, Object> inBoth = FleetDocument.memberByKey(twoGroups, memberSpki);
        require("group-a".equals(FleetDocument.sourceGroup(twoGroups, inBoth, "dashboard")),
                "the higher group does not win, though shared earlier");
        Map<String, Object> reordered = FleetDocument.withGroupOrder(twoGroups, "kiosk-a",
                java.util.Arrays.asList("group-b", "group-a"), "t");
        require("group-b".equals(FleetDocument.sourceGroup(reordered,
                FleetDocument.memberByKey(reordered, memberSpki), "dashboard")),
                "the group moved up does not win");
        require(new java.util.ArrayList<>(FleetDocument.groups(FleetDocument.merge(reordered,
                twoGroups)).keySet()).equals(java.util.Arrays.asList("group-b", "group-a")),
                "the merge lost the new order");
        require(FleetDocument.describeChange(twoGroups, reordered).contains(
                "groups ordered Lobby, Entrance"), "the order change is not in the history: "
                + FleetDocument.describeChange(twoGroups, reordered));
        // Groups made before ranks existed keep their order when a new one is added: last.
        Map<String, Object> unranked = FleetDocument.parse(FleetDocument.canonical(twoGroups));
        for (Object entry : TinyJson.object(unranked.get("groups")).values()) {
            TinyJson.object(entry).remove("rank");
        }
        Map<String, Object> added = FleetDocument.withGroup(unranked, "kiosk-a", "group-c",
                "Kitchen", "t");
        java.util.List<String> order = new java.util.ArrayList<>(
                FleetDocument.groups(added).keySet());
        require(order.get(order.size() - 1).equals("group-c"),
                "a new group did not come last after unranked ones: " + order);
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
