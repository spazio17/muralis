/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host tests for {@link PlaylistDocument}: the invariants that a database would have enforced with
 * a UNIQUE column and a transaction, and which are this class's job instead.
 */
public final class PlaylistDocumentTest {

    public static void main(String[] args) {
        namesAreUniqueAndPresent();
        onlyOnePlaylistIsEverActive();
        deletingTheActiveOneLeavesNothingActive();
        addingIsOrderedAndIdempotent();
        removingNeverTouchesAnythingElse();
        reorderTakesAWholePermutation();
        anOrderArrivesAsOneAddressPerLine();
        repointMovesAnAddressWithoutDuplicating();
        capsHold();
        aStoredDocumentSurvivesARoundTrip();
        aBrokenShapeCostsOneRowNotTheFile();
        malformedJsonThrowsRatherThanEmptying();
        migrationCarriesTheOldSelection();
        System.out.println("PlaylistDocumentTest passed");
    }

    private static void namesAreUniqueAndPresent() {
        PlaylistDocument document = PlaylistDocument.empty();
        expectOk(document.create("a", "Holidays", 1L));
        expectRefused(document.create("b", "Holidays", 2L), "already a playlist");
        // Case and surrounding space are not a new name: "holidays" is the same wall.
        expectRefused(document.create("b", "  holidays ", 2L), "already a playlist");
        expectRefused(document.create("b", "   ", 2L), "needs a name");
        expectRefused(document.create("b", null, 2L), "needs a name");
        expectRefused(document.create("", "Family", 2L), "needs an identifier");
        expect(document.size() == 1, "only the first playlist was created");
        // Renaming onto its own name is not a clash.
        expectOk(document.rename("a", "Holidays", 3L));
        expectOk(document.rename("a", "Family", 4L));
        expect("Family".equals(document.byId("a").name), "the rename took");
        expect(document.byId("a").updatedAt == 4L, "the rename stamped the playlist");
    }

    private static void onlyOnePlaylistIsEverActive() {
        PlaylistDocument document = three();
        expect(document.active() == null, "nothing is active to start with");
        expectOk(document.activate("b"));
        expect("b".equals(document.active().id), "b is active");
        expectOk(document.activate("c"));
        expect("c".equals(document.active().id), "c is active");
        expect(activeCount(document) == 1, "activating moved the flag rather than adding one");
        expectOk(document.activate(null));
        expect(document.active() == null, "the choice can be cleared");
        expectRefused(document.activate("nope"), "gone");
    }

    private static void deletingTheActiveOneLeavesNothingActive() {
        PlaylistDocument document = three();
        expectOk(document.activate("b"));
        expectOk(document.delete("b"));
        expect(document.active() == null, "deleting the active playlist left none active");
        expect(document.size() == 2, "the other two are untouched");
        expectRefused(document.delete("b"), "gone");
    }

    private static void addingIsOrderedAndIdempotent() {
        PlaylistDocument document = three();
        expectOk(document.add("a", Arrays.asList("one", "two", "three"), 10L));
        expect(document.byId("a").items.equals(Arrays.asList("one", "two", "three")),
                "pictures keep the order they arrived in");
        long stamped = document.byId("a").updatedAt;
        expectOk(document.add("a", Arrays.asList("two", "one"), 20L));
        expect(document.byId("a").items.equals(Arrays.asList("one", "two", "three")),
                "adding what is already there changes nothing");
        expect(document.byId("a").updatedAt == stamped,
                "and does not stamp the playlist as changed");
        expectOk(document.add("a", Arrays.asList("", null, "four"), 30L));
        expect(document.byId("a").items.equals(Arrays.asList("one", "two", "three", "four")),
                "empty and missing addresses are ignored, not stored");
        expectRefused(document.add("nope", Arrays.asList("one"), 40L), "gone");
    }

    private static void removingNeverTouchesAnythingElse() {
        PlaylistDocument document = three();
        expectOk(document.add("a", Arrays.asList("one", "two", "three"), 10L));
        expectOk(document.add("b", Arrays.asList("two"), 10L));
        expectOk(document.remove("a", Arrays.asList("two", "absent"), 20L));
        expect(document.byId("a").items.equals(Arrays.asList("one", "three")),
                "only the named picture left playlist a");
        expect(document.byId("b").items.equals(Arrays.asList("two")),
                "the same picture stays in playlist b: removing is not deleting");
    }

    private static void reorderTakesAWholePermutation() {
        PlaylistDocument document = three();
        expectOk(document.add("a", Arrays.asList("one", "two", "three"), 10L));
        expectOk(document.reorder("a", Arrays.asList("three", "one", "two"), 20L));
        expect(document.byId("a").items.equals(Arrays.asList("three", "one", "two")),
                "the new order took");
        // A stale screen sending a shorter or longer order must not half-apply.
        expectRefused(document.reorder("a", Arrays.asList("three", "one"), 30L), "changed while");
        expectRefused(document.reorder("a", Arrays.asList("three", "one", "two", "four"), 30L),
                "changed while");
        expectRefused(document.reorder("a", Arrays.asList("one", "one", "two"), 30L),
                "appears twice");
        expect(document.byId("a").items.equals(Arrays.asList("three", "one", "two")),
                "a refused reorder left the order alone");
    }

    private static void repointMovesAnAddressWithoutDuplicating() {
        PlaylistDocument document = three();
        expectOk(document.add("a", Arrays.asList("saf:1", "saf:2", "saf:3"), 10L));
        expectOk(document.repoint("a", "saf:2", "media:2", 20L));
        expect(document.byId("a").items.equals(Arrays.asList("saf:1", "media:2", "saf:3")),
                "the picture kept its place in the order under its new address");
        // Two old addresses resolving to one new one must leave one row, not two.
        expectOk(document.repoint("a", "saf:3", "media:2", 30L));
        expect(document.byId("a").items.equals(Arrays.asList("saf:1", "media:2")),
                "a two-to-one match dropped the duplicate instead of storing it twice");
        expectOk(document.repoint("a", "absent", "media:9", 40L));
        expect(document.byId("a").items.size() == 2, "repointing what is not there does nothing");
    }

    private static void capsHold() {
        PlaylistDocument document = PlaylistDocument.empty();
        for (int i = 0; i < PlaylistDocument.MAX_PLAYLISTS; i++) {
            expectOk(document.create("id" + i, "name" + i, i));
        }
        expectRefused(document.create("one-too-many", "one too many", 99L), "room for");
        // On a document with room, so the name limit is what answers rather than the count one:
        // create checks for room first, because validating a name it cannot store says nothing.
        expectRefused(PlaylistDocument.empty().create("x", longName(), 99L), "at most");

        PlaylistDocument pictures = PlaylistDocument.empty();
        expectOk(pictures.create("a", "Full", 1L));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < PlaylistDocument.MAX_PICTURES + 5; i++) {
            many.add("picture" + i);
        }
        expectRefused(pictures.add("a", many, 2L), "at most");
        expect(pictures.byId("a").items.size() == PlaylistDocument.MAX_PICTURES,
                "the playlist filled to the cap and stopped");
    }

    private static void aStoredDocumentSurvivesARoundTrip() {
        PlaylistDocument document = three();
        expectOk(document.add("b", Arrays.asList("one", "two"), 10L));
        expectOk(document.activate("b"));
        // A name that would break a naive writer: quotes, a backslash, a newline, a tab.
        expectOk(document.rename("c", "He said \"go\"\n\tC:\\pictures", 20L));
        PlaylistDocument again = PlaylistDocument.parse(document.toJson());
        expect(again.size() == 3, "every playlist came back");
        expect("b".equals(again.active().id), "the active one came back active");
        expect(again.byId("b").items.equals(Arrays.asList("one", "two")), "items kept their order");
        expect("He said \"go\"\n\tC:\\pictures".equals(again.byId("c").name),
                "an awkward name came back exactly, so the escaping round-trips");
        expect(again.byId("b").updatedAt == 10L, "the timestamp is a whole number, not 1.0E1");
        expect(!document.toJson().contains("E1"), "no timestamp was written in exponent form");
    }

    private static void aBrokenShapeCostsOneRowNotTheFile() {
        String json = "{\"version\":1,\"playlists\":["
                + "{\"id\":\"a\",\"name\":\"Good\",\"items\":[\"one\",\"one\",\"two\"]},"
                + "{\"id\":\"\",\"name\":\"No id\"},"
                + "{\"id\":\"c\",\"name\":\"   \"},"
                + "{\"id\":\"d\",\"name\":\"Good\",\"active\":true},"
                + "{\"id\":\"e\",\"name\":\"Also good\",\"active\":true,\"items\":\"not a list\"},"
                + "\"not an object\""
                + "]}";
        PlaylistDocument document = PlaylistDocument.parse(json);
        expect(document.size() == 2, "the two usable playlists survived: " + document.size());
        expect(document.byName("Good") != null, "the first Good stayed");
        expect(document.byId("d") == null, "the duplicate name was dropped, not renamed");
        expect(document.byId("a").items.equals(Arrays.asList("one", "two")),
                "a picture stored twice came back once");
        expect(activeCount(document) == 1, "two active flags were reduced to one");
        expect(document.byId("e").items.isEmpty(), "an items field of the wrong type reads empty");
    }

    private static void malformedJsonThrowsRatherThanEmptying() {
        // The caller has to keep the file and say so. Returning an empty document here would
        // silently throw away every playlist on the panel the first time a write was interrupted.
        expectThrows("{\"playlists\":[");
        expectThrows("");
        expectThrows("not json at all");
    }

    private static void migrationCarriesTheOldSelection() {
        PlaylistDocument document = PlaylistDocument.migrate(
                "first", "Pictures", Arrays.asList("one", "two", "three"), 7L);
        expect(document.size() == 1, "one playlist was made");
        expect("first".equals(document.active().id), "and it is playing, as the panel already was");
        expect(document.byId("first").items.equals(Arrays.asList("one", "two", "three")),
                "in the order the old store listed");
        // Nothing selected means no playlist at all, so the panel meets the empty state.
        expect(PlaylistDocument.migrate("first", "Pictures", new ArrayList<String>(), 7L)
                .size() == 0, "an empty selection made no playlist");
        expect(PlaylistDocument.migrate("first", "Pictures", null, 7L).size() == 0,
                "a missing selection made no playlist");
    }

    private static PlaylistDocument three() {
        PlaylistDocument document = PlaylistDocument.empty();
        expectOk(document.create("a", "First", 1L));
        expectOk(document.create("b", "Second", 2L));
        expectOk(document.create("c", "Third", 3L));
        return document;
    }

    private static int activeCount(PlaylistDocument document) {
        int count = 0;
        for (PlaylistDocument.Playlist playlist : document.all()) {
            if (playlist.active) {
                count++;
            }
        }
        return count;
    }

    private static String longName() {
        StringBuilder name = new StringBuilder();
        while (name.length() <= PlaylistDocument.MAX_NAME_LENGTH) {
            name.append('x');
        }
        return name.toString();
    }

    private static void anOrderArrivesAsOneAddressPerLine() {
        expect(PlaylistDocument.parseOrder(null).isEmpty(), "no field is no order");
        expect(PlaylistDocument.parseOrder("").isEmpty(), "an empty field is no order");
        expect(PlaylistDocument.parseOrder("content://media/external/images/media/3\n"
                + "file:///data/user/0/x/files/pictures/a%20b.jpg\r\n\n  content://m/1  \n")
                .equals(Arrays.asList("content://media/external/images/media/3",
                        "file:///data/user/0/x/files/pictures/a%20b.jpg", "content://m/1")),
                "lines in order, a CRLF and a blank line and surrounding spaces dropped");
        // What arrives is only split here; whether it is the playlist's pictures is reorder's call.
        PlaylistDocument document = three();
        expectOk(document.add("a", Arrays.asList("one", "two", "three"), 10L));
        expectOk(document.reorder("a", PlaylistDocument.parseOrder("two\nthree\none"), 20L));
        expect(document.byId("a").items.equals(Arrays.asList("two", "three", "one")),
                "a parsed order is taken whole");
        expectRefused(document.reorder("a", PlaylistDocument.parseOrder("two\none"), 30L),
                "changed while");
    }

    private static void expectOk(String refusal) {
        expect(refusal == null, "expected no refusal, got: " + refusal);
    }

    private static void expectRefused(String refusal, String contains) {
        expect(refusal != null && refusal.contains(contains),
                "expected a refusal containing \"" + contains + "\", got: " + refusal);
    }

    private static void expectThrows(String json) {
        try {
            PlaylistDocument.parse(json);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected malformed JSON to throw: " + json);
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
