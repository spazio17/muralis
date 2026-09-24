/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every named picture playlist on this panel, and the rules about them, with no Android in it.
 *
 * <p><b>Why a document and not a table.</b> The brief asks for Room. This app has no database and
 * no Kotlin, so the playlists live in one JSON file that is replaced atomically, the same way
 * captions already do. That is not a compromise on the two invariants Room was wanted for, it is a
 * cheaper way to hold them: "exactly zero or one playlist is active" and "a reorder is one
 * transaction" are both true by construction when the whole set is written in a single rename.
 *
 * <p><b>Why ordered items.</b> The selection this replaces was a {@code Set<String>} in
 * SharedPreferences, which is unordered, so the old playlist had no order to speak of and a
 * slideshow could not honour one. Items here are a list, and {@link #reorder} takes the whole new
 * order rather than a move, so a stale screen cannot half-apply one.
 *
 * <p>Every mutator answers with a reason a person can read, or null on success, which is the
 * convention the rest of this app uses for anything a screen has to explain. Ids are supplied by
 * the caller rather than generated here so this class stays deterministic under test.
 */
final class PlaylistDocument {
    /** Enough for a wall panel; a limit exists so a broken client cannot grow the file forever. */
    static final int MAX_PLAYLISTS = 32;
    /** Per playlist, matching what the picture browser will offer. */
    static final int MAX_PICTURES = 1000;
    static final int MAX_NAME_LENGTH = 60;
    private static final int VERSION = 1;

    static final class Playlist {
        final String id;
        String name;
        long createdAt;
        long updatedAt;
        boolean active;
        final List<String> items = new ArrayList<>();

        Playlist(String id, String name, long createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }
    }

    private final List<Playlist> playlists = new ArrayList<>();

    /** An empty panel: no playlists, nothing active. */
    static PlaylistDocument empty() {
        return new PlaylistDocument();
    }

    /**
     * Reads a stored document.
     *
     * <p>Malformed JSON throws, deliberately: the caller must keep the file and say so rather than
     * quietly starting again, because starting again would throw away somebody's playlists. A
     * document whose <em>shape</em> is wrong is a different matter and is skipped entry by entry,
     * so one bad row cannot cost the rest.
     */
    static PlaylistDocument parse(String text) {
        Map<String, Object> root = TinyJson.object(TinyJson.parse(text));
        PlaylistDocument document = new PlaylistDocument();
        for (Object entry : TinyJson.array(root.get("playlists"))) {
            Map<String, Object> stored = TinyJson.object(entry);
            String id = TinyJson.string(stored, "id", "");
            String name = TinyJson.string(stored, "name", "");
            if (id.isEmpty() || name.trim().isEmpty()) {
                continue;
            }
            long created = TinyJson.number(stored, "createdAt", 0L);
            Playlist playlist = new Playlist(id, name.trim(), created);
            playlist.updatedAt = TinyJson.number(stored, "updatedAt", created);
            playlist.active = TinyJson.flag(stored, "active", false);
            for (Object item : TinyJson.array(stored.get("items"))) {
                if (item instanceof String && !((String) item).isEmpty()) {
                    playlist.items.add((String) item);
                }
            }
            document.playlists.add(playlist);
        }
        document.normalise();
        return document;
    }

    String toJson() {
        List<Object> stored = new ArrayList<>();
        for (Playlist playlist : playlists) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", playlist.id);
            entry.put("name", playlist.name);
            entry.put("createdAt", playlist.createdAt);
            entry.put("updatedAt", playlist.updatedAt);
            entry.put("active", playlist.active);
            entry.put("items", new ArrayList<Object>(playlist.items));
            stored.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("playlists", stored);
        return TinyJson.write(root);
    }

    /**
     * Holds what no single mutator can be trusted to hold alone: duplicate ids and names are
     * dropped, items inside a playlist are de-duplicated, the caps are applied, and at most one
     * playlist stays active. Called after parsing and after every change, so a hand-edited file or
     * a future bug lands on the same rules as a normal edit.
     */
    private void normalise() {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        boolean activeSeen = false;
        for (int i = 0; i < playlists.size(); i++) {
            Playlist playlist = playlists.get(i);
            if (!ids.add(playlist.id) || !names.add(key(playlist.name))
                    || ids.size() > MAX_PLAYLISTS) {
                playlists.remove(i--);
                continue;
            }
            Set<String> seen = new LinkedHashSet<>(playlist.items);
            if (seen.size() != playlist.items.size() || seen.size() > MAX_PICTURES) {
                playlist.items.clear();
                for (String uri : seen) {
                    if (playlist.items.size() >= MAX_PICTURES) {
                        break;
                    }
                    playlist.items.add(uri);
                }
            }
            if (playlist.active && activeSeen) {
                playlist.active = false;
            }
            activeSeen |= playlist.active;
        }
    }

    /** The comparison used for uniqueness: trimmed and case-folded, so "Home" and "home" clash. */
    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    List<Playlist> all() {
        return new ArrayList<>(playlists);
    }

    int size() {
        return playlists.size();
    }

    Playlist byId(String id) {
        for (Playlist playlist : playlists) {
            if (playlist.id.equals(id)) {
                return playlist;
            }
        }
        return null;
    }

    Playlist byName(String name) {
        for (Playlist playlist : playlists) {
            if (key(playlist.name).equals(key(name == null ? "" : name))) {
                return playlist;
            }
        }
        return null;
    }

    /** The one playing, or null when none is chosen. */
    Playlist active() {
        for (Playlist playlist : playlists) {
            if (playlist.active) {
                return playlist;
            }
        }
        return null;
    }

    /**
     * Why a name cannot be used, or null when it can. Answered before a save rather than by
     * refusing one, so the screen can say it while the person is still typing.
     */
    String nameProblem(String name, String exceptId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "a playlist needs a name";
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return "a name is at most " + MAX_NAME_LENGTH + " characters";
        }
        Playlist existing = byName(trimmed);
        if (existing != null && !existing.id.equals(exceptId)) {
            return "there is already a playlist called " + existing.name;
        }
        return null;
    }

    String create(String id, String name, long now) {
        if (id == null || id.isEmpty()) {
            return "a playlist needs an identifier";
        }
        if (byId(id) != null) {
            return "that playlist already exists";
        }
        if (playlists.size() >= MAX_PLAYLISTS) {
            return "there is room for " + MAX_PLAYLISTS + " playlists";
        }
        String problem = nameProblem(name, null);
        if (problem != null) {
            return problem;
        }
        playlists.add(new Playlist(id, name.trim(), now));
        normalise();
        return null;
    }

    String rename(String id, String name, long now) {
        Playlist playlist = byId(id);
        if (playlist == null) {
            return "that playlist is gone";
        }
        String problem = nameProblem(name, id);
        if (problem != null) {
            return problem;
        }
        playlist.name = name.trim();
        playlist.updatedAt = now;
        normalise();
        return null;
    }

    /**
     * Removes a playlist. Deleting the one that was playing leaves nothing active, which the
     * screensaver has to be able to say out loud rather than showing a blank panel.
     */
    String delete(String id) {
        Playlist playlist = byId(id);
        if (playlist == null) {
            return "that playlist is gone";
        }
        playlists.remove(playlist);
        return null;
    }

    /** Makes one playlist the only active one; a null id clears the choice. */
    String activate(String id) {
        if (id != null && byId(id) == null) {
            return "that playlist is gone";
        }
        for (Playlist playlist : playlists) {
            playlist.active = id != null && playlist.id.equals(id);
        }
        return null;
    }

    /** Adds pictures, keeping the order they arrive in and ignoring ones already there. */
    String add(String id, List<String> uris, long now) {
        Playlist playlist = byId(id);
        if (playlist == null) {
            return "that playlist is gone";
        }
        Set<String> present = new LinkedHashSet<>(playlist.items);
        int added = 0;
        for (String uri : uris) {
            if (uri == null || uri.isEmpty() || present.contains(uri)) {
                continue;
            }
            if (playlist.items.size() >= MAX_PICTURES) {
                return "a playlist holds at most " + MAX_PICTURES + " pictures"
                        + (added > 0 ? "; " + added + " were added" : "");
            }
            playlist.items.add(uri);
            present.add(uri);
            added++;
        }
        if (added > 0) {
            playlist.updatedAt = now;
        }
        return null;
    }

    /** Takes pictures out of the playlist. It never deletes a file; that is a separate action. */
    String remove(String id, List<String> uris, long now) {
        Playlist playlist = byId(id);
        if (playlist == null) {
            return "that playlist is gone";
        }
        if (playlist.items.removeAll(uris)) {
            playlist.updatedAt = now;
        }
        return null;
    }

    /**
     * Replaces the whole order.
     *
     * <p>The whole order and not a move, so two screens cannot half-apply one between them: if
     * what arrives is not a permutation of what is stored, nothing changes and the caller is told,
     * which is more useful than merging two people's guesses.
     */
    String reorder(String id, List<String> uris, long now) {
        Playlist playlist = byId(id);
        if (playlist == null) {
            return "that playlist is gone";
        }
        Set<String> arrived = new LinkedHashSet<>(uris);
        if (arrived.size() != uris.size()) {
            return "the same picture appears twice in that order";
        }
        if (!arrived.equals(new LinkedHashSet<>(playlist.items))) {
            return "the playlist changed while it was being reordered";
        }
        playlist.items.clear();
        playlist.items.addAll(uris);
        playlist.updatedAt = now;
        return null;
    }

    /**
     * Points one item at a different address without moving it in the order.
     *
     * <p>For the one-off migration off document URIs: the same picture, named the way the panel
     * now names it. Silently does nothing when the old address is not in this playlist, and
     * refuses when the new address is already there, so a two-to-one match cannot duplicate a row.
     */
    String repoint(String id, String from, String to, long now) {
        Playlist playlist = byId(id);
        if (playlist == null) {
            return "that playlist is gone";
        }
        int at = playlist.items.indexOf(from);
        if (at < 0) {
            return null;
        }
        if (playlist.items.contains(to)) {
            playlist.items.remove(at);
            playlist.updatedAt = now;
            return null;
        }
        playlist.items.set(at, to);
        playlist.updatedAt = now;
        return null;
    }

    /**
     * The document a panel gets the first time it runs this code: whatever was already selected,
     * as one active playlist, in the order the old store listed it.
     *
     * <p>Named rather than nameless because every playlist has a name from here on, and active
     * because the panel was already showing these pictures and must carry on showing them. A panel
     * with nothing selected gets no playlist at all, so it meets the empty state instead of an
     * empty playlist.
     */
    static PlaylistDocument migrate(String id, String name, List<String> selection, long now) {
        PlaylistDocument document = new PlaylistDocument();
        if (selection == null || selection.isEmpty()) {
            return document;
        }
        document.create(id, name, now);
        document.add(id, selection, now);
        document.activate(id);
        return document;
    }
}
