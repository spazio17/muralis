/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.spazio17.muralis.PictureSources.Picture;

/**
 * Ambient's pictures: where they are kept and how each one is decoded for the screen.
 *
 * <p>Local pictures are the panel's own, read through MediaStore under the read permission (a
 * device owner grants it to itself, an ordinary install asks once), plus the private upload
 * store; they are picked one by one into
 * named playlists inside Muralis, on every device, and the system's picker is not used.
 *
 * <p>Disk and decode work runs on one worker. UI callbacks return to the main thread; the web
 * admin also accesses the library from its request workers. One instance per process. Provider
 * availability is not guaranteed by a persisted grant.
 */
final class PictureLibrary {
    private static final String TAG = "MuralisPictures";
    /** Beside {@code captions.json}; see {@link #hiddenCredits}. */
    private static final String CREDITS_HIDDEN_FILE = "credits_hidden.json";
    /** Beside {@code captions.json}; see {@link #rememberName}. */
    private static final String LAST_NAMES_FILE = "last_names.json";
    /** How long the names learnt by one listing wait to be written together. */
    private static final long LAST_NAMES_WRITE_DELAY_MS = 2_000;
    /** Which caption-key migration a panel has had; see {@link #migrateCaptionKeys}. */
    private static final String CAPTIONS_KEY_VERSION = "captions_key_version";
    /** 1 moved names onto URIs; 2 also dropped the names nothing had claimed (2026-09-10). */
    private static final int CAPTIONS_KEY_TARGET = 2;

    private static volatile PictureLibrary instance;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** A picture of the playlist that could not be read, said until one reads again. */
    private volatile String unreadable;
    /** The folder's last count and when it was taken: /api/stats polls the sentence every 5 s. */
    private volatile int localCount = -1;
    private volatile long localCountAtMs;
    private final PictureBrowser browser;
    private final PicturePlaylists playlists;
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean counting = new java.util.concurrent.atomic.AtomicBoolean();

    private PictureLibrary(Context context) {
        app = context.getApplicationContext();
        browser = new PictureBrowser(app);
        playlists = new PicturePlaylists(app);
        run(this::ensurePlaylist);
    }

    static PictureLibrary get(Context context) {
        PictureLibrary library = instance;
        if (library == null) {
            synchronized (PictureLibrary.class) {
                library = instance;
                if (library == null) {
                    library = new PictureLibrary(context);
                    instance = library;
                }
            }
        }
        return library;
    }

    /** Runs {@code work} on the worker thread; for decoding and anything that reads the disk. */
    void run(Runnable work) {
        worker.execute(() -> {
            try { work.run(); }
            catch (RuntimeException | OutOfMemoryError failed) {
                Log.w(TAG, "Picture work failed; the panel remains available", failed);
            }
        });
    }

    void onMain(Runnable work) {
        main.post(work);
    }

    // ------------------------------------------------------------------ the catalogue

    /** The pictures of the playlist in use, in order. */
    List<Picture> catalog() {
        return listLocal();
    }

    /**
     * The one sentence the settings page and the status document show: how many pictures, from
     * which playlist, and what is wrong if anything is.
     */
    String state() {
        int count = localCount();
        String issue = problem();
        PlaylistDocument.Playlist active = playlists.load().active();
        if (active == null) {
            // Not an error and not an empty playlist: no playlist has been chosen, which is
            // the state a fresh panel and a panel whose active playlist was just deleted are
            // both in, and which Ambient must be able to say rather than going black.
            return "No playlist is in use." + (issue == null ? "" : " " + issue);
        }
        return (count < 0 ? "Reading the playlist"
                : count + (count == 1 ? " picture" : " pictures"))
                + " from " + active.name + "."
                + (issue == null ? "" : " " + issue);
    }

    /**
     * How many pictures the folder holds, remembered for a few seconds: the status document is
     * polled every five seconds by any open web admin page. Invalidated by every playlist,
     * upload, caption or folder-access change.
     */
    private int localCount() {
        long now = android.os.SystemClock.elapsedRealtime();
        int cached = localCount;
        if (cached >= 0 && now - localCountAtMs < 5_000L) {
            return cached;
        }
        if (counting.compareAndSet(false, true)) run(() -> {
            try {
                localCount = listLocal().size();
                localCountAtMs = android.os.SystemClock.elapsedRealtime();
            } finally { counting.set(false); }
        });
        return cached;
    }

    void forgetLocalCount() {
        synchronized (knownNames) {
            knownNames.clear();
        }
        localCount = -1;
        revision.incrementAndGet();
    }

    long revision() { return revision.get(); }

    /** The pictures' last problem, or null; the status document carries it beside the sentence. */
    String problem() {
        String stored = playlists.problem();
        if (stored != null) {
            return stored;
        }
        String migration = playlists.migrationNote();
        if (migration != null) {
            return migration;
        }
        String dropped = deletedNote();
        if (dropped != null) {
            return dropped;
        }
        String browsing = browser.problem();
        if (browsing != null) {
            return browsing;
        }
        return unreadable;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > max) {
                throw new IOException("answer larger than " + max + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in, 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static String sha1(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ------------------------------------------------------------------ decoding

    /**
     * The picture scaled to fit the screen, or null when it cannot be read. Decoded in two passes,
     * bounds first, so a 4000 px photo costs the memory of the screen it is shown on and not its
     * own. Worker thread only.
     */
    Bitmap decode(Picture picture, int maxWidth, int maxHeight) {
        return decode(picture, maxWidth, maxHeight, true);
    }

    /**
     * The same, with {@code forSlideshow} false for a thumbnail: a folder being browsed is not
     * the playlist, so a picture there that cannot be read must not put "a selected picture
     * cannot be read" under Ambient.
     */
    private Bitmap decode(Picture picture, int maxWidth, int maxHeight, boolean forSlideshow) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = openPicture(picture)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            // Halve while either side would still cover the screen: with "and" a 4000x3000
            // photo on a 1920 px panel was decoded at full size and died of memory on the API 26
            // tablet, and the picture was skipped as unreadable (2026-09-09 review).
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = PictureSources.decodeSample(bounds.outWidth, bounds.outHeight,
                    maxWidth, maxHeight);
            try (InputStream in = openPicture(picture)) {
                android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
                if (bitmap != null && forSlideshow) {
                    // A picture that reads again clears the sentence that said one could not.
                    // Without this the record outlived the fault: a folder renamed away and back
                    // left the panel saying "a selected picture cannot be read" through three
                    // clean cycles and a rebuild, and only the reboot cleared it, because the
                    // record is in memory (found on the panel 2026-09-10).
                    unreadable = null;
                }
                return bitmap;
            }
        } catch (IOException | OutOfMemoryError | RuntimeException failed) {
            if (forSlideshow) {
                unreadable = "A selected picture cannot be read. Check its storage or remove it from the playlist.";
            }
            Log.w(TAG, "Cannot decode " + picture.url + ": " + failed.getMessage());
            return null;
        }
    }

    private InputStream openPicture(Picture picture) throws IOException {
        if (isUpload(picture.url)) {
            return new FileInputStream(Uri.parse(picture.url).getPath());
        }
        // The permission is checked again here rather than trusted from the playlist: a stored
        // selection outlives the grant that made it, so a panel that lost it must fail honestly
        // instead of reading on. Only for the panel's own pictures, though: a document URI from
        // the retired folder-picker path still opens on its own persisted grant, and a panel
        // between the two migration steps has to keep showing what it was already showing.
        if (PictureBrowser.isMedia(picture.url) && !browser.canReadStorage()) {
            throw new IOException("this panel may not read its own pictures");
        }
        InputStream own = app.getContentResolver().openInputStream(Uri.parse(picture.url));
        if (own == null) {
            throw new IOException("no stream for " + picture.url);
        }
        return own;
    }

    // ------------------------------------------------------------------ thumbnails

    /** The longest side of a thumbnail: one size for both surfaces, scaled down by the view. */
    static final int THUMBNAIL_PX = 256;

    /**
     * A small JPEG of one of this panel's own pictures, made once and kept in the cache folder.
     *
     * <p>One cache for both surfaces: the panel's tiles read the file and the web page fetches it
     * through {@code /api/pictures/thumb}, so a folder opened on the web costs the panel one decode
     * per picture, ever. Keyed by the address alone, since an upload never changes under its name
     * and a MediaStore row that does is rare enough that a stale thumbnail is the smaller fault.
     * Null when the picture cannot be read, which the caller shows as a blank tile rather than an
     * error: the row's name still says what the picture is. Worker thread only.
     */
    File thumbnail(String uri) {
        if (uri == null || !(isUpload(uri) || PictureBrowser.isMedia(uri))) {
            return null;
        }
        // A thumbnail made while the permission was there is not served once it is gone: the
        // held list now shows without it, and a cached copy was still the picture (review,
        // 2026-10-05).
        if (PictureBrowser.isMedia(uri) && !browser.readsEveryPicture()) {
            // Under the partial grant only a photo somebody picked: a cached copy of one they did
            // not pick was still served (review, 2026-10-05).
            if (!browser.canReadStorage()) {
                return null;
            }
            try {
                if (browser.mediaLabel(uri) == null) {
                    return null;
                }
            } catch (PictureBrowser.Unavailable notNow) {
                return null;
            }
        }
        File dir = new File(app.getCacheDir(), "thumbs");
        File target = new File(dir, sha1(uri) + ".jpg");
        if (target.isFile() && target.length() > 0) {
            return target;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        Picture picture = new Picture(uri, "", "");
        Bitmap full = decode(picture, THUMBNAIL_PX, THUMBNAIL_PX, false);
        if (full == null) {
            return null;
        }
        try {
            float scale = Math.min(1f, THUMBNAIL_PX / (float) Math.max(full.getWidth(),
                    full.getHeight()));
            Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * scale)),
                    Math.max(1, Math.round(full.getHeight() * scale)), true) : full;
            File partial = new File(target.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(partial)) {
                if (!small.compress(Bitmap.CompressFormat.JPEG, 82, out)) {
                    throw new IOException("cannot encode the thumbnail");
                }
            }
            if (small != full) {
                small.recycle();
            }
            if (!partial.renameTo(target)) {
                throw new IOException("cannot store the thumbnail");
            }
            return target;
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "Cannot make a thumbnail for " + uri + ": " + failed.getMessage());
            return null;
        } finally {
            full.recycle();
        }
    }

    /** The thumbnail of a picture that is gone, so a deleted upload's name cannot be reused for it. */
    private void forgetThumbnail(String uri) {
        File thumb = new File(new File(app.getCacheDir(), "thumbs"), sha1(uri) + ".jpg");
        if (thumb.isFile() && !thumb.delete()) {
            Log.w(TAG, "Could not remove a thumbnail: " + thumb.getName());
        }
    }

    // ------------------------------------------------------------------ the pictures at home

    /**
     * Muralis's own picture store: the web admin's uploads land here, inside the app's private
     * files, so storing and showing them needs no permission on any Android and nothing for Play
     * to ask about.
     */
    private File storeDir() {
        return new File(app.getFilesDir(), "pictures");
    }

    /** What both surfaces say where the panel's own pictures may not be read; uploads still work. */
    static final String OWN_PICTURES_NEED_PERMISSION = "This panel may not read its own pictures "
            + "yet, so only uploaded pictures can go in a playlist.";

    /**
     * Whether this panel browses its own storage.
     *
     * <p>Always true once the read permission is granted, which is now the only path on every
     * device (2026-09-10). Kept as a question rather than removed because the screens still have
     * to say something useful while the permission is missing, which on an ordinary install is
     * every moment before somebody answers the dialog.
     */
    boolean browsesOwnStorage() {
        return browser.canReadStorage();
    }

    /** The browser the screens and the web admin share. */
    PictureBrowser browser() {
        return browser;
    }

    /** The playlists the screens and the web admin share. */
    PicturePlaylists playlists() {
        return playlists;
    }

    /**
     * One edit of the stored playlists, under this library's own lock.
     *
     * <p>Every surface came through a load-modify-store path of its own before 2026-09-19: the
     * web admin on an HTTP worker, the smart home on the MQTT thread, the panel on the library
     * worker. Two of them at once each stored a copy that lacked the other's change, so a tick
     * in the browser could quietly undo a playlist switch from a card. They all come through
     * here now, one at a time.
     */
    synchronized String editPlaylists(
            java.util.function.Function<PlaylistDocument, String> change) {
        PlaylistDocument document = playlists.load();
        String refusal = change.apply(document);
        if (refusal == null) {
            refusal = playlists.store(document);
        }
        forgetLocalCount();
        return refusal;
    }

    /**
     * Every picture in the playlist in use, from the panel's storage and the upload store. A
     * supplied caption is its title; otherwise the file name in words is used.
     */
    List<Picture> listLocal() {
        ensurePlaylist();
        List<Picture> pictures = new ArrayList<>();
        PlaylistDocument.Playlist active = playlists.load().active();
        if (active == null) {
            return pictures;
        }
        Map<String, String> captions = captions();
        Set<String> hidden = hiddenCredits();
        List<String> gone = new ArrayList<>();
        boolean unsure = false;
        // The playlist's own order, which is the point of storing a list rather than a set.
        for (String uri : active.items) {
            String name;
            try {
                name = cachedLocalName(uri);
            } catch (PictureBrowser.Unavailable notNow) {
                // The store did not answer, which is not a missing file: the picture is kept
                // under its address, and nothing is dropped from this listing.
                unsure = true;
                name = lastSegmentOf(uri);
            }
            if (name == null) {
                gone.add(uri);
                continue;
            }
            pictures.add(localPicture(uri, name, captions, hidden));
        }
        // Only a definite answer edits a playlist: the store answered for every picture, and at
        // least one of them is still there. Every picture vanishing at once is what an ejected
        // card looks like, not what deleting looks like (review of 2026-09-19); a playlist of one
        // picture has no such second witness and follows the rule that a deleted picture leaves.
        // And only with the permission to read every picture: under Android 14's partial grant
        // the store answers for the pictures somebody picked and has no row for the rest, which
        // looks exactly like a deletion and is not one.
        if (!gone.isEmpty() && !unsure && browser.readsEveryPicture()
                && (gone.size() < active.items.size() || active.items.size() == 1)) {
            dropDeleted(active.id, gone);
        } else if (!gone.isEmpty() && browser.readsEveryPicture()) {
            // Under the partial grant pictures nobody picked look gone on every listing, every
            // few seconds while a page is open: no line for that (review, 2026-10-05).
            Log.i(TAG, gone.size() + " picture(s) not found and nothing removed: the store may "
                    + "not be answering for them");
        }
        return pictures;
    }

    /**
     * Takes pictures that no longer exist out of the playlist that holds them.
     *
     * <p>The owner's rule of 2026-09-10, both halves of it: "Deleted image are removed from the
     * playlist. Images removed from the playlist are not physically deleted." This is the first
     * half, and the caution it needs is in {@link #localName}: a picture is only called gone when
     * this panel can read its own pictures and the store has no row for it, so an unmounted card or
     * a revoked permission cannot be mistaken for a deletion and quietly edit somebody's playlist.
     *
     * <p>The count is remembered for one sentence on the screens, because a playlist that shrinks
     * silently is indistinguishable from a bug.
     */
    private void dropDeleted(String playlistId, List<String> gone) {
        String refusal = editPlaylists(document -> {
            if (document.byId(playlistId) == null) {
                return PlaylistDocument.GONE;
            }
            document.remove(playlistId, gone, System.currentTimeMillis());
            return null;
        });
        if (refusal == null) {
            deleted = gone.size();
            deletedAt = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "Removed " + gone.size() + " deleted picture(s) from the playlist");
        }
    }

    /** How many pictures were dropped because their files are gone, for one sentence. */
    private volatile int deleted;
    private volatile long deletedAt;
    /** How long the sentence is said for: long enough to be seen, not a permanent state. */
    private static final long DELETED_NOTE_MS = 10 * 60 * 1000L;

    /**
     * The sentence about pictures that were dropped, or null.
     *
     * <p>Said for ten minutes to every surface that asks, not swallowed by the first reader: it
     * used to clear itself on the first read, and the first reader was the telemetry publish,
     * so the web page and the tablet never saw it (review of 2026-09-19).
     */
    String deletedNote() {
        int count = deleted;
        if (count == 0 || android.os.SystemClock.elapsedRealtime() - deletedAt > DELETED_NOTE_MS) {
            return null;
        }
        return count == 1
                ? "One picture was removed from the playlist because its file is gone."
                : count + " pictures were removed from the playlist because their files are gone.";
    }

    /**
     * The file name of a picture in a playlist, asked of the store that owns it.
     *
     * <p>A playlist holds addresses and not names, deliberately: a name copied in at selection
     * time is a second source of truth that goes stale the moment a file is renamed. The cost is
     * this lookup, which the caption map and the browser both need anyway.
     */
    String localName(String uri) {
        if (isUpload(uri)) {
            String path = Uri.parse(uri).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            if (path == null) {
                return "picture";
            }
            String name = slash < 0 ? path : path.substring(slash + 1);
            return new File(path).isFile() ? name : null;
        }
        if (!PictureBrowser.isMedia(uri)) {
            // A document URI from the retired picker path, still opening on its own grant. Nothing
            // here can tell whether it is gone, so it is never called gone.
            String stored = prefs().getString("name:" + uri, "");
            return stored.isEmpty() ? "picture" : stored;
        }
        // Only a panel that can read its own pictures may conclude that one is missing: without
        // the permission every answer is null, which would read as "every picture was deleted".
        // The same without the permission when the store throws instead, which API 26 and 28 do.
        String name;
        try {
            name = browser.mediaName(uri);
        } catch (PictureBrowser.Unavailable notNow) {
            if (!browser.canReadStorage()) {
                return "picture";
            }
            throw notNow;
        }
        return name == null && !browser.canReadStorage() ? "picture" : name;
    }

    /**
     * Names the store answered for the playlist's pictures, kept for half a minute.
     *
     * <p>{@link #listLocal} runs every five seconds under an open web admin page, and without
     * this it asked MediaStore once per picture each time, a thousand queries every five seconds
     * on the worker that also decodes the slideshow's pictures (review of 2026-09-19). A file
     * deleted meanwhile is noticed within the half minute. Cleared by {@link #forgetLocalCount}.
     */
    private final java.util.Map<String, String> knownNames = new java.util.HashMap<>();
    private long knownNamesAt;
    private static final long KNOWN_NAMES_MS = 30_000L;

    private String cachedLocalName(String uri) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (knownNames) {
            if (now - knownNamesAt > KNOWN_NAMES_MS) {
                knownNames.clear();
                knownNamesAt = now;
            }
            if (knownNames.containsKey(uri)) {
                return knownNames.get(uri);
            }
        }
        String name = localName(uri);
        synchronized (knownNames) {
            knownNames.put(uri, name);
        }
        return name;
    }

    private synchronized void ensurePlaylist() {
        android.os.UserManager user = app.getSystemService(android.os.UserManager.class);
        if (user != null && !user.isUserUnlocked()) return;
        try {
            playlists.migrate(browser, storedUploads());
        } catch (RuntimeException unavailable) {
            // Retried at the next call. A migration that cannot read the panel's pictures yet is
            // the ordinary state of an install whose permission dialog nobody has answered.
            Log.w(TAG, "Playlists not migrated yet", unavailable);
            return;
        }
        migrateCaptionKeys();
    }

    /**
     * Moves captions from file names onto the document URIs they were written for, once.
     *
     * <p>Captions used to be keyed by file name, because a name was what both surfaces listed a
     * picture by and there was one folder. With a playlist there are several, and a name is no
     * longer unique: a second folder's {@code pd_2.jpg} was shown on the glass under the first
     * folder's caption, "The Great Wave off Kanagawa, Hokusai" (measured 2026-09-10). Reading the
     * name as a fallback is what crossed them, so the fallback is gone and the old keys are
     * rewritten here instead: every selected picture whose name carried a caption and which has
     * none of its own gets it, and only then is the name key dropped. Nothing is lost and nothing
     * is guessed: where two selected pictures share a name, both inherit the one caption that
     * existed for that name, which is the only honest reading of it, and either can be corrected
     * afterwards.
     */
    private void migrateCaptionKeys() {
        // Versioned rather than a bare flag: the first pass moved names onto URIs and left the
        // names nothing had claimed behind, which is dead data on a panel that already migrated.
        // A version lets that second sweep reach those panels too.
        if (prefs().getInt(CAPTIONS_KEY_VERSION,
                prefs().getBoolean("captions_migrated", false) ? 1 : 0) >= CAPTIONS_KEY_TARGET) {
            return;
        }
        try {
            Map<String, String> captions = captions();
            if (captions.isEmpty()) {
                prefs().edit().putInt(CAPTIONS_KEY_VERSION, CAPTIONS_KEY_TARGET).commit();
                return;
            }
            Map<String, String> moved = new java.util.LinkedHashMap<>(captions);
            java.util.Set<String> consumed = new java.util.HashSet<>();
            for (PlaylistDocument.Playlist list : playlists.load().all()) {
                for (String uri : list.items) {
                    String name = localName(uri);
                    String caption = captions.get(name);
                    if (caption == null || caption.isEmpty() || moved.containsKey(uri)) {
                        continue;
                    }
                    moved.put(uri, caption);
                    consumed.add(name);
                }
            }
            // Every remaining bare name goes too, not only the ones just consumed: with the
            // fallback gone nothing can read a name key again, so leaving one behind is data that
            // lies about being in use. The only thing lost is a caption for a picture that was in
            // the old folder and has since been deselected, which would not have been applied on
            // re-selection either.
            consumed.addAll(captions.keySet());
            for (String name : consumed) {
                if (!name.startsWith("content:") && !name.startsWith("file:")) {
                    moved.remove(name);
                }
            }
            if (moved.equals(captions) || writeCaptions(moved) == null) {
                prefs().edit().putInt(CAPTIONS_KEY_VERSION, CAPTIONS_KEY_TARGET).commit();
                forgetLocalCount();
            }
        } catch (RuntimeException unavailable) {
            // Retried at the next call; a caption is not worth failing Ambient over.
            Log.w(TAG, "Caption keys not migrated yet", unavailable);
        }
    }

    private SharedPreferences prefs() {
        return KioskConfig.storageContext(app).getSharedPreferences("picture_playlist", 0);
    }

    /**
     * Every picture in the panel's own upload store.
     *
     * <p>Uploads join a playlist without any permission, so the first migration adds them: a panel
     * whose only pictures arrived through the web admin would otherwise migrate to no playlist and
     * look as though its uploads had been lost.
     */
    private List<Picture> storedUploads() {
        List<Picture> pictures = new ArrayList<>();
        Map<String, String> captions = captions();
        Set<String> hidden = hiddenCredits();
        File[] stored = storeDir().listFiles();
        if (stored != null) {
            for (File file : stored) {
                if (file.isFile() && !file.getName().endsWith(".part")
                        && PictureSources.imageMime(header(file)) != null) {
                    pictures.add(localPicture(Uri.fromFile(file).toString(), file.getName(),
                            captions, hidden));
                }
            }
        }
        java.util.Collections.sort(pictures, (left, right) -> left.credit.compareToIgnoreCase(
                right.credit));
        return pictures;
    }

    /** The credit field carries the file name here: it is what both surfaces list a picture by. */
    private Picture localPicture(String url, String name, Map<String, String> captions,
            Set<String> hidden) {
        // By URI alone. The file-name fallback this replaced crossed captions between same-named
        // pictures in different folders; migrateCaptionKeys moved the old keys across instead.
        String caption = captions.get(url);
        String title = caption != null && !caption.isEmpty()
                ? caption : PictureSources.fileTitleToWords(name);
        return new Picture(url, title, name, hidden.contains(url));
    }

    /** The first bytes of a file, enough for {@link PictureSources#imageMime}. */
    private static byte[] header(File file) {
        byte[] head = new byte[16];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(head);
            return read <= 0 ? new byte[0] : head;
        } catch (IOException unreadable) {
            return new byte[0];
        }
    }

    /** A {@code file:} address, which is either one of our uploads or a file on shared storage. */
    static boolean isStored(String url) {
        return url != null && url.startsWith("file:");
    }

    /**
     * Whether a {@code file:} address is one of Muralis's own uploads, and so ours to delete.
     *
     * <p>The distinction arrived with the folder browser on 2026-09-10, which read shared storage
     * by file path for a while. The browser has named pictures by their MediaStore ids since, so
     * today only uploads carry {@code file:} addresses, but a playlist from those days may still
     * hold one of the person's own files by path: it can leave a playlist, and it is never deleted
     * from here.
     */
    boolean isUpload(String url) {
        if (!isStored(url)) {
            return false;
        }
        String path = Uri.parse(url).getPath();
        if (path == null) {
            return false;
        }
        return storeDir().equals(new File(path).getParentFile());
    }

    /**
     * Stores an uploaded picture in Muralis's own store, or says why not. The bytes are checked
     * to be a JPEG, PNG or WebP by their signature and by decoding their header, because a
     * browser's declared type is whatever the file name says.
     */
    synchronized String saveLocal(String playlistId, String filename, byte[] data) {
        String mime = PictureSources.imageMime(data);
        if (mime == null) {
            return "not a JPEG, PNG or WebP picture";
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return "the picture cannot be decoded";
        }
        File dir = storeDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "cannot create the picture store";
        }
        String name = PictureSources.safeName(filename, mime, System.currentTimeMillis());
        File target = new File(dir, name);
        int copy = 1;
        while (target.exists()) {
            int dot = name.lastIndexOf('.');
            target = new File(dir, name.substring(0, dot) + " (" + copy++ + ")"
                    + name.substring(dot));
        }
        File partial = new File(target.getPath() + ".part");
        try {
            try (OutputStream out = new FileOutputStream(partial)) {
                out.write(data);
            }
            if (!partial.renameTo(target)) {
                throw new IOException("cannot store the picture");
            }
            ensurePlaylist();
            // Into the playlist whose page the upload came from; only a caller that names none
            // gets the one in use. The page's uploads used to land in whichever was playing.
            String selection = selectPicture(playlistId == null || playlistId.isEmpty()
                    ? null : playlistId, Uri.fromFile(target).toString(), true);
            forgetLocalCount();
            return selection == null ? null : "stored, but not selected: " + selection;
        } catch (IOException | RuntimeException failed) {
            if (partial.exists() && !partial.delete()) {
                Log.w(TAG, "Could not remove a partial upload: " + partial.getName());
            }
            Log.w(TAG, "Cannot store " + name, failed);
            return "cannot store the picture: " + failed.getMessage();
        }
    }

    /**
     * Removes one of Muralis's uploaded pictures. A picture from the panel's own storage is the
     * person's file and is never deleted here; it can only leave a playlist.
     */
    synchronized String deleteLocal(String url) {
        if (!isUpload(url)) {
            return "this picture is in your own folder; remove it there";
        }
        String path = Uri.parse(url).getPath();
        if (path == null) return "not a picture of the store";
        File file = new File(path);
        if (!file.getParentFile().equals(storeDir())) {
            return "not a picture of the store";
        }
        if (!file.isFile()) {
            return "the picture was not found";
        }
        if (file.getName().endsWith(".part") || PictureSources.imageMime(header(file)) == null) {
            return "not a stored picture";
        }
        if (!file.delete()) {
            return "the picture could not be removed";
        }
        removeEverywhere(url);
        forgetThumbnail(url);
        forgetLocalCount();
        return null;
    }

    // ------------------------------------------------------------------ captions

    /**
     * The line under a local picture, stored by URI in a small JSON file beside the upload store.
     * Legacy filename keys are read as fallback.
     */
    Map<String, String> captions() {
        Map<String, String> captions = new java.util.LinkedHashMap<>();
        File file = new File(storeDir(), "captions.json");
        if (!file.isFile()) {
            return captions;
        }
        try {
            JSONObject stored = new JSONObject(readText(file));
            for (java.util.Iterator<String> keys = stored.keys(); keys.hasNext();) {
                String key = keys.next();
                captions.put(key, stored.optString(key, ""));
            }
        } catch (IOException | JSONException unreadable) {
            Log.w(TAG, "Unreadable captions, treated as none", unreadable);
        }
        return captions;
    }

    String caption(String name) {
        String caption = captions().get(name);
        return caption == null ? "" : caption;
    }

    /** Sets or, with an empty text, clears one picture's caption. */
    synchronized String setCaption(String name, String caption) {
        if (name == null || name.trim().isEmpty()) {
            return "no picture was named";
        }
        if (caption != null && caption.length() > 200) {
            return "a caption is at most 200 characters";
        }
        Map<String, String> captions = captions();
        if (name.length() > 4096 || (!captions.containsKey(name) && captions.size() >= 1000)) {
            return "the caption store is full or the picture identifier is too long";
        }
        if (caption == null || caption.trim().isEmpty()) {
            captions.remove(name);
        } else {
            captions.put(name, caption.trim());
        }
        String refusal = writeCaptions(captions);
        if (refusal != null) {
            return refusal;
        }
        KioskService.publishTelemetrySoon(app);
        forgetLocalCount();
        return null;
    }

    /** The whole caption map to disk, replaced atomically. Returns a reason, or null. */
    private String writeCaptions(Map<String, String> captions) {
        File dir = storeDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "cannot create the picture store";
        }
        JSONObject document = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : captions.entrySet()) {
                document.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        File target = new File(dir, "captions.json");
        File partial = new File(target.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(document.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            return "cannot store the caption: " + failed.getMessage();
        }
        if (!partial.renameTo(target)) {
            return "cannot store the caption";
        }
        return null;
    }

    /**
     * The pictures whose credit their owner has switched off, by address.
     *
     * <p>A file of its own beside {@code captions.json} rather than a second value inside it: a
     * name and "show no line at all" are two different choices, and switching the line off must
     * keep the name somebody typed, so switching it back on brings the same words back. Keyed by
     * address for the reason captions are (see {@link #localPicture}), and scoped like a caption:
     * the picture's choice, whichever playlist shows it.
     */
    Set<String> hiddenCredits() {
        Set<String> hidden = new java.util.LinkedHashSet<>();
        File file = new File(storeDir(), CREDITS_HIDDEN_FILE);
        if (!file.isFile()) {
            return hidden;
        }
        try {
            JSONArray stored = new JSONArray(readText(file));
            for (int i = 0; i < stored.length(); i++) {
                String uri = stored.optString(i, "");
                if (!uri.isEmpty()) {
                    hidden.add(uri);
                }
            }
        } catch (IOException | JSONException unreadable) {
            // Read as none, which shows every credit: the safe side for a line that is on unless
            // somebody switched it off.
            Log.w(TAG, "Unreadable hidden credits, treated as none", unreadable);
        }
        return hidden;
    }

    /** Switches one picture's credit off, or back on. Returns a reason, or null. */
    synchronized String setCreditHidden(String uri, boolean hide) {
        if (uri == null || uri.trim().isEmpty()) {
            return "no picture was named";
        }
        Set<String> hidden = hiddenCredits();
        if (uri.length() > 4096 || (hide && !hidden.contains(uri) && hidden.size() >= 1000)) {
            return "the credit store is full or the picture identifier is too long";
        }
        boolean changed = hide ? hidden.add(uri) : hidden.remove(uri);
        if (!changed) {
            return null;
        }
        File dir = storeDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "cannot create the picture store";
        }
        File target = new File(dir, CREDITS_HIDDEN_FILE);
        File partial = new File(target.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(new JSONArray(hidden).toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            return "cannot store the choice: " + failed.getMessage();
        }
        if (!partial.renameTo(target)) {
            return "cannot store the choice";
        }
        KioskService.publishTelemetrySoon(app);
        forgetLocalCount();
        return null;
    }

    /**
     * The panel's own upload store as a page, for the browser's Uploads folder.
     *
     * <p>Uploads are not in MediaStore: they live inside the app's private files, which is what
     * makes them need no permission, and which is also why they cannot be listed by the same
     * query as everything else.
     */
    PictureBrowser.Page uploads(int offset, int size) {
        PictureBrowser.Page page = new PictureBrowser.Page();
        List<PictureBrowser.Entry> found = uploadEntries();
        page.available = found.size();
        int start = Math.max(0, Math.min(offset, found.size()));
        int end = Math.min(found.size(), start + size);
        page.entries.addAll(found.subList(start, end));
        page.more = end < found.size();
        return page;
    }

    /** How many pictures the upload store holds, the number beside its row in Folders. */
    int uploadCount() {
        return uploadEntries().size();
    }

    private List<PictureBrowser.Entry> uploadEntries() {
        List<PictureBrowser.Entry> found = new ArrayList<>();
        File[] files = storeDir().listFiles();
        if (files == null) {
            return found;
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            if (!file.isFile() || file.getName().endsWith(".part")
                    || PictureSources.imageMime(header(file)) == null) {
                continue;
            }
            // Dimensions from the header alone (inJustDecodeBounds reads no pixels): uploads are
            // few, and the details view shows them beside MediaStore's own measurements.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            found.add(new PictureBrowser.Entry(Uri.fromFile(file).toString(), file.getName(),
                    file.length(), file.lastModified(),
                    Math.max(0, bounds.outWidth), Math.max(0, bounds.outHeight)));
        }
        return found;
    }

    /** Takes one picture out of every playlist it appears in. */
    synchronized String removeEverywhere(String url) {
        PlaylistDocument document = playlists.load();
        for (PlaylistDocument.Playlist list : document.all()) {
            document.remove(list.id, java.util.Collections.singletonList(url),
                    System.currentTimeMillis());
        }
        String refusal = playlists.store(document);
        forgetLocalCount();
        return refusal;
    }

    /**
     * A picture in a playlist as {@code ./folder/name.jpg}, so two files with one name read apart.
     *
     * <p>The folder is a label, never an identity, and it is MediaStore's own relative path for
     * the panel's pictures. An upload says {@code ./uploads/} instead, since its real path is
     * inside the app and means nothing to a person; a document address from the retired picker
     * path shows its stored name alone.
     *
     * <p>A picture whose file is gone says so rather than showing an empty row, under the name it
     * had when it was last found ({@link #rememberName}): the playlist page lists what is in the
     * playlist, and something that cannot be found is exactly what somebody looking at that page
     * needs to see in order to take it out. Only a panel that can read its
     * own pictures may call one missing, though: without the permission every answer is empty,
     * which would read as "every picture was deleted".
     */
    String displayPath(String url) {
        if (PictureBrowser.isMedia(url)) {
            String label;
            try {
                label = browser.mediaLabel(url);
            } catch (PictureBrowser.Unavailable notNow) {
                return browser.canReadStorage() ? lastSegmentOf(url) : remembered(url);
            }
            if (label == null) {
                if (!browser.readsEveryPicture()) {
                    // Without the permission every answer is empty, and under the partial grant
                    // every picture not picked looks gone: the name it had when last found, which
                    // tells two held pictures apart where "picture" did not (seen on the Pixel,
                    // 2026-10-05), and never "not found", which nobody can know now.
                    return remembered(url);
                }
                // The name it had when it was last found, not the store's bare number: "186 (not
                // found)" told nobody which picture had gone (2026-10-05).
                String known = lastName(url);
                return (known != null ? known : lastSegmentOf(url)) + PictureSources.NOT_FOUND;
            }
            rememberName(url, label);
            return label;
        }
        String name;
        try {
            name = localName(url);
        } catch (PictureBrowser.Unavailable notNow) {
            return lastSegmentOf(url);
        }
        if (name == null) {
            return lastSegmentOf(url) + PictureSources.NOT_FOUND;
        }
        return isUpload(url) ? "./uploads/" + name : name;
    }

    // ------------------------------------------------------------------ names of held pictures

    /** The names learnt so far, by address; loaded once, guarded by this library. */
    private Map<String, String> lastNames;
    private boolean lastNamesWriteQueued;

    /**
     * The label a picture of the panel's own had when it was last found, so a picture whose file
     * is gone still says which one it was. Kept in a file of its own beside the captions, for
     * the pictures some playlist holds and no others.
     */
    private synchronized String lastName(String url) {
        return lastNames().get(url);
    }

    /**
     * Notes a picture's label as it is listed. One listing names many pictures, so the file is
     * written once, a moment after the last new name, not once per row.
     */
    private synchronized void rememberName(String url, String label) {
        if (lastNames == null && !userUnlocked()) {
            return;
        }
        Map<String, String> names = lastNames();
        if (label.equals(names.get(url))) {
            return;
        }
        names.put(url, label);
        if (!lastNamesWriteQueued) {
            lastNamesWriteQueued = true;
            deadlines.schedule(this::writeLastNames, LAST_NAMES_WRITE_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private Map<String, String> lastNames() {
        if (lastNames == null && !userUnlocked()) {
            // The file lives in storage that opens at the first unlock: read now it would look
            // empty, and that empty map, kept, would be written over the names (review,
            // 2026-10-05). Nothing is learnt or written until then.
            return new java.util.LinkedHashMap<>();
        }
        if (lastNames == null) {
            lastNames = new java.util.LinkedHashMap<>();
            File file = new File(storeDir(), LAST_NAMES_FILE);
            if (file.isFile()) {
                try {
                    JSONObject stored = new JSONObject(readText(file));
                    for (java.util.Iterator<String> keys = stored.keys(); keys.hasNext();) {
                        String key = keys.next();
                        lastNames.put(key, stored.optString(key, ""));
                    }
                } catch (IOException | JSONException unreadable) {
                    // Read as none: a gone picture then shows its address's tail, as it used to.
                    Log.w(TAG, "Unreadable picture names, treated as none", unreadable);
                }
            }
        }
        return lastNames;
    }

    private boolean userUnlocked() {
        android.os.UserManager users = app.getSystemService(android.os.UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    /** The names to disk, those of pictures no playlist holds left out, replaced atomically. */
    private void writeLastNames() {
        Set<String> held = new java.util.HashSet<>();
        for (PlaylistDocument.Playlist playlist : playlists.load().all()) {
            held.addAll(playlist.items);
        }
        // An empty document prunes nothing: a damaged playlists file is moved aside and reads as
        // none, and pruning against that would forget every name (review, 2026-10-05). Names of
        // pictures no playlist holds then wait for the next write that has playlists to go by.
        boolean pruned = !held.isEmpty();
        JSONObject document = new JSONObject();
        synchronized (this) {
            lastNamesWriteQueued = false;
            Map<String, String> names = lastNames();
            if (pruned) {
                names.keySet().retainAll(held);
            }
            try {
                for (Map.Entry<String, String> entry : names.entrySet()) {
                    document.put(entry.getKey(), entry.getValue());
                }
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        File dir = storeDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File target = new File(dir, LAST_NAMES_FILE);
        File partial = new File(target.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(document.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            Log.w(TAG, "Picture names not kept: " + failed.getMessage());
            return;
        }
        if (!partial.renameTo(target)) {
            Log.w(TAG, "Picture names not kept");
        }
    }

    /** The name a picture had when last found, or the word "picture" when none was ever learnt. */
    private String remembered(String url) {
        String known = lastName(url);
        return known != null ? known : "picture";
    }

    /** The tail of an address, for labelling something that can no longer be asked its name. */
    private static String lastSegmentOf(String url) {
        String text = url == null ? "" : url;
        int slash = text.lastIndexOf('/');
        return slash < 0 || slash == text.length() - 1 ? text : text.substring(slash + 1);
    }

    /**
     * Ticks or unticks one picture in the playlist that is playing.
     *
     * <p>The active playlist, because this is the remote and web-admin path and there is no
     * screen open to say which playlist is meant. A panel with no playlist yet gets one, named
     * "Pictures", rather than refusing: somebody uploading a picture through the web admin on a
     * fresh panel means it to appear, and an upload that vanished into no playlist was the older
     * behaviour and read as a bug. The panel's own screens edit a named playlist by name and do
     * not come through here.
     */
    synchronized String selectPicture(String url, boolean selected) {
        return selectPicture(null, url, selected);
    }

    /**
     * The same, for one named playlist rather than the one that is playing.
     *
     * <p>The web admin's playlist page edits the playlist you opened, which may not be the one on
     * the glass; a null id keeps the old meaning, "whichever is in use", which is what the
     * Ambient page and the commands want.
     */
    synchronized String selectPicture(String playlistId, String url, boolean selected) {
        ensurePlaylist();
        if (url == null || url.isEmpty()) {
            return "No picture selected.";
        }
        if (selected) {
            if (isUpload(url)) {
                File file = new File(Uri.parse(url).getPath() == null
                        ? "" : Uri.parse(url).getPath());
                if (!file.isFile() || file.getName().endsWith(".part")
                        || PictureSources.imageMime(header(file)) == null) {
                    return "Not a stored picture.";
                }
            } else if (PictureBrowser.isMedia(url)) {
                // Asked of MediaStore rather than taken from the request: the browser is reachable
                // from the web admin, so an address only proves somebody typed one.
                if (!browser.canReadStorage()) {
                    return "This panel may not read its own pictures.";
                }
                try {
                    if (browser.mediaName(url) == null) {
                        return "Not a supported picture.";
                    }
                } catch (PictureBrowser.Unavailable notNow) {
                    return "This panel's pictures could not be read just now.";
                }
            } else {
                return "That is not a picture this panel can read.";
            }
        }
        PlaylistDocument document = playlists.load();
        PlaylistDocument.Playlist active = playlistId == null
                ? document.active() : document.byId(playlistId);
        if (active == null && playlistId != null) {
            return "That playlist is gone.";
        }
        if (active == null) {
            if (!selected) {
                return null;
            }
            String id = playlists.newId();
            String refusal = document.create(id, uniqueName(document, "Pictures"),
                    System.currentTimeMillis());
            if (refusal != null) {
                return capitalise(refusal);
            }
            document.activate(id);
            active = document.byId(id);
        }
        String refusal = selected
                ? document.add(active.id, java.util.Collections.singletonList(url),
                        System.currentTimeMillis())
                : document.remove(active.id, java.util.Collections.singletonList(url),
                        System.currentTimeMillis());
        if (refusal != null) {
            return capitalise(refusal);
        }
        String stored = playlists.store(document);
        forgetLocalCount();
        KioskService.publishTelemetrySoon(app);
        return stored;
    }

    /** "Pictures", or "Pictures 2" when that name is taken, so a create can never fail on it. */
    static String uniqueName(PlaylistDocument document, String wanted) {
        if (document.byName(wanted) == null) {
            return wanted;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = wanted + " " + suffix;
            if (document.byName(candidate) == null) {
                return candidate;
            }
        }
        return wanted + " " + System.currentTimeMillis();
    }

    /** The store answers in lower case because a screen puts its reason mid-sentence. */
    static String capitalise(String reason) {
        return reason == null || reason.isEmpty() ? reason
                : Character.toUpperCase(reason.charAt(0)) + reason.substring(1) + ".";
    }
}
