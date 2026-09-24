/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The panel's named picture playlists on disk, and the one-off migrations onto them.
 *
 * <p>The rules live in {@link PlaylistDocument}, which has no Android in it and is host-tested.
 * This class is only the storage: read the file, hand the document to a caller, write it back in
 * one atomic rename. Every mutator therefore looks the same, load, change, store, and there is no
 * partial state a crash could leave behind, which is what replaces the transaction a database
 * would have given.
 */
final class PicturePlaylists {
    private static final String TAG = "MuralisPlaylists";
    private static final String FILE = "playlists.json";
    /** Bumped when a migration is added, so a panel runs each one exactly once. */
    private static final int MIGRATION_TARGET = 2;
    private static final String MIGRATION_KEY = "playlists_migration";

    private final Context app;
    private final SharedPreferences prefs;
    private volatile String persistenceProblem;
    /** A file that could not be read was kept under another name; said for a day. */
    private volatile String damagedNote;
    private volatile long damagedAt;
    private static final long DAMAGED_NOTE_MS = 24 * 60 * 60 * 1000L;
    /** Names of pictures a migration could not carry across, said until they match. */
    private volatile List<String> unmatched = new ArrayList<>();
    /** When the unfinished matching step last ran, so it is retried by the minute, not per call. */
    private long repointedAt;
    private static final long REPOINT_RETRY_MS = 60_000L;
    /** The playlist names of the document last read or written, joined, for discovery. */
    private volatile String namesKey;

    PicturePlaylists(Context context) {
        app = context.getApplicationContext();
        prefs = KioskConfig.storageContext(app).getSharedPreferences("picture_playlist", 0);
    }

    private File file() {
        return new File(app.getFilesDir(), "pictures/" + FILE);
    }

    /**
     * The stored document, or an empty one when there is none.
     *
     * <p>An unreadable file is <em>not</em> treated as absent: it is moved aside under a dated
     * name, logged, and said on the screens for a day, and an empty document is returned. Moved
     * rather than left in place, because every mutator stores what it loaded: the first version
     * left the bytes where they were and the very next save wrote an empty document over them
     * (review of 2026-09-19). The damaged bytes stay on disk for a later look.
     */
    synchronized PlaylistDocument load() {
        File file = file();
        if (!file.isFile()) {
            return PlaylistDocument.empty();
        }
        try {
            PlaylistDocument document = PlaylistDocument.parse(readText(file));
            namesKey = namesOf(document);
            return document;
        } catch (IOException | IllegalArgumentException unreadable) {
            File kept = new File(file.getPath() + ".damaged-" + System.currentTimeMillis());
            boolean moved = file.renameTo(kept);
            Log.w(TAG, "Unreadable playlists, " + (moved ? "kept as " + kept.getName()
                    : "could not be moved aside"), unreadable);
            damagedNote = moved
                    ? "The playlists could not be read; the file was kept as " + kept.getName()
                            + " and a fresh start was made."
                    : "The playlists could not be read.";
            damagedAt = android.os.SystemClock.elapsedRealtime();
            return PlaylistDocument.empty();
        }
    }

    /** Replaces the whole set atomically. Returns a reason a person can read, or null. */
    synchronized String store(PlaylistDocument document) {
        File file = file();
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            persistenceProblem = "The playlists could not be saved; check the panel's storage.";
            return persistenceProblem;
        }
        File partial = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(document.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            persistenceProblem = "The playlists could not be saved: " + failed.getMessage();
            return persistenceProblem;
        }
        if (!partial.renameTo(file)) {
            persistenceProblem = "The playlists could not be saved; check the panel's storage.";
            return persistenceProblem;
        }
        persistenceProblem = null;
        namesKey = namesOf(document);
        KioskService.publishTelemetrySoon(app);
        return null;
    }

    /**
     * The playlist names as one string, from the document last read or written, so a caller can
     * tell that the set changed without reading the file again: the MQTT state publish asks on
     * every publish (review of 2026-09-19). Null until the document has been read once.
     */
    String namesKey() {
        return namesKey;
    }

    static String namesOf(PlaylistDocument document) {
        StringBuilder names = new StringBuilder();
        for (PlaylistDocument.Playlist playlist : document.all()) {
            names.append(playlist.name).append('\u0000');
        }
        return names.toString();
    }

    /**
     * Runs whichever migrations this panel has not seen.
     *
     * <p>Called from {@link PictureLibrary} before the playlists are read, and cheap after the
     * first time: a stored version number, not a scan.
     */
    synchronized void migrate(PictureBrowser browser, List<PictureSources.Picture> uploads) {
        int done = prefs.getInt(MIGRATION_KEY, 0);
        if (done >= MIGRATION_TARGET) {
            return;
        }
        PlaylistDocument document = load();
        boolean changed = false;
        int reached = done;
        if (reached < 1) {
            PlaylistDocument carried = carryTheOldSelection(document, uploads);
            changed |= carried != document;
            document = carried;
            reached = 1;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (reached < 2 && browser.canReadStorage()
                && (repointedAt == 0 || now - repointedAt > REPOINT_RETRY_MS)) {
            // The addresses can only be matched against pictures this panel may read, so on an
            // ordinary install this step waits for the permission. Running it early would have
            // matched nothing (caught before it ran on the phone, 2026-09-10).
            //
            // And it only counts as done once everything matched. A picture MediaStore has not
            // indexed yet is not a picture that is gone, so leaving the step unfinished lets a
            // later run pick it up rather than deciding against the operator once and for all.
            // Retried by the minute, not on every listing: each run walks the whole picture
            // index twice, and a listing comes every five seconds under an open web page.
            repointedAt = now;
            changed |= repointDocumentUris(document, browser) > 0;
            if (unmatched.isEmpty()) {
                reached = 2;
            }
        }
        // Stored only when something changed. Storing regardless published telemetry, and the
        // telemetry lists the pictures, which runs this again: on an ordinary install waiting
        // for its permission that was a rewrite of the file and an MQTT publish every few
        // seconds, for ever (review of 2026-09-19).
        if (changed && store(document) != null) {
            return;
        }
        prefs.edit().putInt(MIGRATION_KEY, reached).commit();
    }

    /**
     * The flat selection this replaced becomes one playlist that is already playing.
     *
     * <p>Named "Pictures" because every playlist has a name from here on, and active because the
     * panel was already showing these pictures and must carry on showing them. A panel with
     * nothing selected gets no playlist at all, so it meets the empty state rather than an empty
     * playlist.
     */
    private PlaylistDocument carryTheOldSelection(PlaylistDocument document,
            List<PictureSources.Picture> uploads) {
        if (document.size() > 0) {
            return document;
        }
        List<String> selection = new ArrayList<>(
                prefs.getStringSet("selected", Collections.<String>emptySet()));
        // The old store was a Set, so it had no order of its own; sort by the name it recorded so
        // the first playlist reads the way the old Selected list did rather than arbitrarily.
        final SharedPreferences names = prefs;
        Collections.sort(selection, (a, b) -> {
            String left = names.getString("name:" + a, a);
            String right = names.getString("name:" + b, b);
            int byName = left.compareToIgnoreCase(right);
            return byName == 0 ? a.compareTo(b) : byName;
        });
        for (PictureSources.Picture upload : uploads) {
            if (!selection.contains(upload.url)) {
                selection.add(upload.url);
            }
        }
        if (selection.isEmpty()) {
            return document;
        }
        return PlaylistDocument.migrate(newId(), "Pictures", selection, System.currentTimeMillis());
    }

    /**
     * Points every document URI at the media id for the same file.
     *
     * <p>The SAF path is gone, so a selection made through a granted folder tree names a picture
     * the new browser has no way to offer again. Matching is by display name, and only where
     * exactly one picture on the panel has that name: a name matching several files maps to none of
     * them, because guessing which one somebody meant is worse than saying so.
     *
     * <p><b>What cannot be matched is kept, not removed.</b> The first version of this deleted it,
     * and on the phone that emptied a playlist of five: those files exist on disk but MediaStore
     * had never indexed them, because they arrived over adb. "The index has not caught up" and
     * "the picture is gone" are not the same thing, and only one of them is a reason to edit
     * somebody's playlist. A document URI also still opens on its own persisted grant, so a kept
     * item keeps working meanwhile.
     *
     * @return how many addresses were repointed; {@link #unmatched} says whether any were not
     */
    private int repointDocumentUris(PlaylistDocument document, PictureBrowser browser) {
        List<String> lost = new ArrayList<>();
        int repointed = 0;
        Map<String, String> byPath = null;
        Map<String, String> byName = null;
        long now = System.currentTimeMillis();
        for (PlaylistDocument.Playlist playlist : document.all()) {
            for (String uri : new ArrayList<>(playlist.items)) {
                if (PictureBrowser.isMedia(uri) || uri.startsWith("file:")
                        || !uri.startsWith("content://")) {
                    continue;
                }
                if (byPath == null) {
                    byPath = browser.idsByPath();
                    byName = browser.idsByName();
                }
                String name = prefs.getString("name:" + uri, "");
                // The path first, because it is exact, and the name only where the provider's id
                // carried no path at all.
                String path = pathOfDocument(uri);
                String match = path == null ? null
                        : byPath.get(path.toLowerCase(java.util.Locale.ROOT));
                if (match == null && !name.isEmpty()) {
                    match = byName.get(name.toLowerCase(java.util.Locale.ROOT));
                }
                if (match == null) {
                    lost.add(name.isEmpty() ? uri : name);
                } else {
                    document.repoint(playlist.id, uri, match, now);
                    repointed++;
                }
            }
        }
        unmatched = lost;
        if (!lost.isEmpty()) {
            Log.i(TAG, "Migration could not match " + lost.size()
                    + " picture(s) to this panel yet; they are kept and will be retried");
        }
        return repointed;
    }

    /**
     * The folder path and file name a document URI encodes, or null when its id is opaque.
     *
     * <p>{@code primary:Pictures/PhoneTest/pd_1.jpg} becomes {@code Pictures/PhoneTest/pd_1.jpg},
     * which is exactly how MediaStore names the same file. A document id is opaque by contract, so
     * this is a best effort and its failure is expected rather than exceptional: a cloud provider's
     * ids look nothing like a path, and the caller falls back to the display name for those.
     */
    private static String pathOfDocument(String uri) {
        try {
            String id = android.provider.DocumentsContract.getDocumentId(
                    android.net.Uri.parse(uri));
            int colon = id.indexOf(':');
            String path = colon < 0 ? id : id.substring(colon + 1);
            return path.isEmpty() || path.startsWith("/") || !path.contains("/") ? null : path;
        } catch (RuntimeException opaque) {
            return null;
        }
    }

    /**
     * The one sentence the screens show about a migration that is not finished, or null.
     *
     * <p>Said out loud because the alternative is a playlist that quietly holds pictures the
     * browser cannot show again, and somebody wondering why. Names two of them, because a list of
     * forty would not be a sentence.
     */
    String migrationNote() {
        List<String> lost = unmatched;
        if (lost.isEmpty()) {
            return null;
        }
        StringBuilder note = new StringBuilder(lost.size() == 1
                ? "One picture in a playlist" : lost.size() + " pictures in the playlists");
        note.append(" could not be matched to this panel's own pictures (");
        note.append(lost.get(0));
        if (lost.size() > 1) {
            note.append(lost.size() == 2 ? " and " + lost.get(1)
                    : ", " + lost.get(1) + " and " + (lost.size() - 2) + " more");
        }
        note.append("). They are kept and still shown; tick them again if they stay unmatched.");
        return note.toString();
    }

    /**
     * A fresh playlist id: the wall clock in base 36 plus a counter, which is short, sortable and
     * cannot collide with one made in the same millisecond.
     */
    String newId() {
        long now = System.currentTimeMillis();
        int sequence = prefs.getInt("playlist_sequence", 0) + 1;
        prefs.edit().putInt("playlist_sequence", sequence).commit();
        return Long.toString(now, 36) + "-" + Integer.toString(sequence, 36);
    }

    String problem() {
        if (persistenceProblem != null) {
            return persistenceProblem;
        }
        String damaged = damagedNote;
        return damaged != null
                && android.os.SystemClock.elapsedRealtime() - damagedAt <= DAMAGED_NOTE_MS
                ? damaged : null;
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > 4 * 1024 * 1024) {
                    throw new IOException("the playlist file is implausibly large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
