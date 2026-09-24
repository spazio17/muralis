/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.spazio17.muralis.PictureSources.Picture;

/**
 * The Pictures screensaver's pictures: where they are kept, how the online sets are fetched and
 * how each one is decoded for the screen.
 *
 * <p>Local pictures are selected individually from persisted SAF grants and the private upload
 * store. Grants permit browsing; they do not automatically select new files. Android's picker is
 * only used to acquire access explicitly on the panel. No broad photo/storage permission.
 *
 * <p><b>The online sets</b> (Bing, Wikimedia Commons) are the last {@link PictureSources#ONLINE_DAYS}
 * days' pictures, downloaded into the app's cache with a manifest of their credits, so the
 * screensaver never depends on the network at the moment it starts: a source that cannot be
 * reached shows the last set and says so on the settings page, never a blank screen. A set counts
 * as fresh for {@link #FRESH_MS}; the activity asks for a refresh when the screensaver starts and
 * once an hour while it sits on the dashboard.
 *
 * <p>Network refresh has its own worker, separate from disk/decode work. UI callbacks return to
 * the main thread; the web admin also accesses the library from its request workers. One instance
 * per process. Provider availability is not guaranteed by a persisted grant.
 */
final class PictureLibrary {
    private static final String TAG = "MuralisPictures";
    /** Which caption-key migration a panel has had; see {@link #migrateCaptionKeys}. */
    private static final String CAPTIONS_KEY_VERSION = "captions_key_version";
    /** 1 moved names onto URIs; 2 also dropped the names nothing had claimed (2026-09-10). */
    private static final int CAPTIONS_KEY_TARGET = 2;
    /**
     * The shape of a cached credit. A manifest stores the composed credit line, so a change to
     * what that line contains has to invalidate every manifest written by an older build, or a
     * panel keeps showing yesterday's wording from its cache. 2 added the supplied notices;
     * 3 dropped the licence and source addresses from the line (2026-09-10).
     */
    private static final int ATTRIBUTION_VERSION = 3;
    private static final long FRESH_MS = 20L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 15_000;
    /** A rendition at 1920 px is a few megabytes; anything past this is not a wallpaper. */
    private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
    private static final int PICTURE_WIDTH = 1920;

    private static volatile PictureLibrary instance;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Network stalls must not queue the cached slideshow behind fourteen HTTP timeouts.
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> problems = new ConcurrentHashMap<>();
    /** Source to the callbacks waiting on the fetch in flight for it; empty list means idle. */
    private final Map<String, List<Runnable>> refreshing = new java.util.HashMap<>();
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

    /** The pictures a source has right now, in order: the folder's files or the cached set. */
    List<Picture> catalog(String source) {
        if (PictureSources.LOCAL.equals(source)) {
            return listLocal();
        }
        return readManifest(source);
    }

    /**
     * Refreshes an online set when it is missing or older than {@link #FRESH_MS}, once at a
     * time; {@code onDone} runs on the main thread after a fetch, and not at all when nothing was
     * fetched, so a caller that only wants "tell me when the pictures changed" can pass its redraw.
     */
    void refreshIfStale(String source, Runnable onDone) {
        if (PictureSources.LOCAL.equals(source)) {
            return;
        }
        long age = System.currentTimeMillis() - fetchedAt(source);
        if (age >= 0 && age < FRESH_MS && !catalog(source).isEmpty()) {
            return;
        }
        refresh(source, onDone);
    }

    /**
     * Fetches an online set now, whatever its age. One fetch per source at a time; a caller that
     * arrives while one is running waits for it rather than losing its callback, which used to
     * leave the first screensaver of a fresh panel on "fetching" for its whole run
     * (2026-09-09 review).
     */
    void refresh(String source, Runnable onDone) {
        if (PictureSources.LOCAL.equals(source)) {
            return;
        }
        synchronized (refreshing) {
            List<Runnable> waiting = refreshing.get(source);
            if (waiting != null) {
                if (onDone != null) {
                    waiting.add(onDone);
                }
                return;
            }
            waiting = new ArrayList<>();
            if (onDone != null) {
                waiting.add(onDone);
            }
            refreshing.put(source, waiting);
        }
        downloads.execute(() -> {
            try {
                List<Picture> pictures = PictureSources.BING.equals(source)
                        ? fetchBing() : fetchWikimedia();
                if (pictures.isEmpty()) {
                    throw new IOException("the service listed no pictures");
                }
                File dir = cacheDir(source);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("cannot create the picture cache");
                }
                List<Picture> kept = new ArrayList<>();
                Set<String> wanted = new HashSet<>();
                for (Picture picture : pictures) {
                    File file = cacheFile(source, picture.url);
                    if (!file.isFile() || file.length() == 0) {
                        try {
                            download(picture.url, file);
                        } catch (IOException one) {
                            Log.w(TAG, source + ": could not fetch " + picture.url, one);
                            continue;
                        }
                    }
                    kept.add(picture);
                    wanted.add(file.getName());
                }
                if (kept.isEmpty()) {
                    throw new IOException("no picture could be downloaded");
                }
                writeManifest(source, kept);
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!wanted.contains(file.getName()) && !"manifest.json".equals(file.getName())
                                && !file.delete()) {
                            Log.w(TAG, "Could not remove a stale picture: " + file.getName());
                        }
                    }
                }
                problems.remove(source);
                revision.incrementAndGet();
                Log.i(TAG, source + ": " + kept.size() + " picture(s) fetched");
            } catch (Throwable problem) {
                // Throwable, not Exception: a hostile or broken answer must never be worse than
                // a failed fetch, and this runs on the library's only worker thread, where an
                // Error would reach Android's uncaught handler and end the process
                // (2026-09-09 review; TinyJson also caps its nesting now).
                String why = problem.getMessage() == null
                        ? problem.getClass().getSimpleName() : problem.getMessage();
                problems.put(source, why);
                Log.w(TAG, source + ": refresh failed: " + why);
            } finally {
                List<Runnable> waiting;
                synchronized (refreshing) {
                    waiting = refreshing.remove(source);
                }
                KioskService.publishTelemetrySoon(app);
                if (waiting != null) {
                    for (Runnable callback : waiting) {
                        main.post(callback);
                    }
                }
            }
        });
    }

    /**
     * The one sentence the settings page and the status document show for a source: how many
     * pictures, from where, and what is wrong if anything is.
     */
    String state(String source) {
        if (PictureSources.LOCAL.equals(source)) {
            int count = localCount();
            String issue = problem(source);
            PlaylistDocument.Playlist active = playlists.load().active();
            if (active == null) {
                // Not an error and not an empty playlist: no playlist has been chosen, which is
                // the state a fresh panel and a panel whose active playlist was just deleted are
                // both in, and which the screensaver must be able to say rather than going black.
                return "No playlist is in use." + (issue == null ? "" : " " + issue);
            }
            return (count < 0 ? "Reading the playlist"
                    : count + (count == 1 ? " picture" : " pictures"))
                    + " from " + active.name + "."
                    + (issue == null ? "" : " " + issue);
        }
        return onlineState(source);
    }

    private String onlineState(String source) {
        /* Online state uses only the small cache manifest, never a document-provider query. */
        int count = catalog(source).size();
        String name = PictureSources.sourceName(source);
        String problem = problems.get(source);
        long fetched = fetchedAt(source);
        StringBuilder sentence = new StringBuilder();
        if (count == 0) {
            sentence.append("No pictures from ").append(name).append(" yet");
            sentence.append(problem == null ? ", fetching." : ": " + problem + ".");
            return sentence.toString();
        }
        sentence.append(count).append(count == 1 ? " picture" : " pictures").append(" from ")
                .append(name).append(", fetched ")
                .append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(fetched))).append(".");
        if (problem != null) {
            sentence.append(" The last refresh failed (").append(problem)
                    .append("); showing the last set.");
        }
        return sentence.toString();
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
        localCount = -1;
        revision.incrementAndGet();
    }

    long revision() { return revision.get(); }

    /** A source's last problem, or null; the status document carries it beside the sentence. */
    String problem(String source) {
        if (PictureSources.LOCAL.equals(source)) {
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
        }
        return problems.get(source);
    }

    // ------------------------------------------------------------------ online sources

    private List<Picture> fetchBing() throws IOException {
        Locale locale = Locale.getDefault();
        String market = PictureSources.bingMarket(locale.getLanguage(), locale.getCountry());
        return PictureSources.parseBing(fetchText(PictureSources.bingArchiveUrl(market)));
    }

    private List<Picture> fetchWikimedia() throws IOException {
        List<String> dates = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int back = 0; back < PictureSources.ONLINE_DAYS; back++) {
            dates.add(today.minusDays(back).toString());
        }
        List<String> titles = PictureSources.parseWikimediaFileTitles(
                fetchText(PictureSources.wikimediaPotdUrl(dates)));
        if (titles.isEmpty()) {
            return new ArrayList<>();
        }
        return PictureSources.parseWikimediaImageInfo(
                fetchText(PictureSources.wikimediaImageInfoUrl(titles, PICTURE_WIDTH)));
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        java.util.concurrent.ScheduledFuture<?> deadline = deadline(connection);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " from " + connection.getURL().getHost());
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
            }
        } finally {
            deadline.cancel(false);
            connection.disconnect();
        }
    }

    private void download(String url, File target) throws IOException {
        HttpURLConnection connection = open(url);
        java.util.concurrent.ScheduledFuture<?> deadline = deadline(connection);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            File partial = new File(target.getPath() + ".part");
            try (InputStream in = connection.getInputStream();
                    OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("picture larger than " + MAX_DOWNLOAD_BYTES + " bytes");
                    }
                    out.write(buffer, 0, read);
                }
            } catch (IOException failed) {
                if (!partial.delete()) {
                    Log.w(TAG, "Could not remove a partial download: " + partial.getName());
                }
                throw failed;
            }
            if (!partial.renameTo(target)) {
                throw new IOException("cannot store the picture");
            }
        } finally {
            deadline.cancel(false);
            connection.disconnect();
        }
    }

    /**
     * Both services ask that clients identify themselves (Wikimedia's User-Agent policy refuses
     * the default Java agent outright), so every request names Muralis and where to find it.
     */
    private HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop <= 3; hop++) {
            if (!PictureSources.allowedUrl(url)) throw new IOException("untrusted picture URL");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Muralis/" + appVersion()
                    + " (https://muralis.spazio17.org; screensaver)");
            connection.setRequestProperty("Accept", "application/json, image/*;q=0.9, */*;q=0.5");
            java.util.concurrent.ScheduledFuture<?> timeout = deadline(connection);
            try {
                int status = connection.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307
                        && status != 308) return connection;
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("redirect without a location");
                url = new URL(new URL(url), location).toString();
            } catch (IOException failed) {
                connection.disconnect();
                throw failed;
            } finally {
                timeout.cancel(false);
            }
            connection.disconnect();
        }
        throw new IOException("too many picture redirects");
    }

    private java.util.concurrent.ScheduledFuture<?> deadline(HttpURLConnection connection) {
        return deadlines.schedule(connection::disconnect, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private String appVersion() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            return "0";
        }
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

    // ------------------------------------------------------------------ the cache

    private File cacheDir(String source) {
        return new File(new File(app.getCacheDir(), "screensaver"), source);
    }

    private File cacheFile(String source, String url) {
        return new File(cacheDir(source), sha1(url) + ".img");
    }

    private long fetchedAt(String source) {
        File manifest = new File(cacheDir(source), "manifest.json");
        if (!manifest.isFile()) {
            return -1;
        }
        try {
            return new JSONObject(readText(manifest)).optLong("fetched_ms", -1);
        } catch (IOException | JSONException unreadable) {
            return -1;
        }
    }

    private List<Picture> readManifest(String source) {
        List<Picture> pictures = new ArrayList<>();
        File manifest = new File(cacheDir(source), "manifest.json");
        if (!manifest.isFile()) {
            return pictures;
        }
        try {
            JSONObject document = new JSONObject(readText(manifest));
            if (PictureSources.WIKIMEDIA.equals(source)
                    && document.optInt("attribution_version") < ATTRIBUTION_VERSION) {
                return pictures;
            }
            JSONArray entries = document.optJSONArray("pictures");
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                Picture picture = new Picture(entry.optString("url"), entry.optString("title"),
                        entry.optString("credit"), entry.optString("link"));
                if (PictureSources.allowedUrl(picture.url) && !picture.credit.trim().isEmpty()
                        && cacheFile(source, picture.url).isFile()) {
                    pictures.add(picture);
                }
            }
        } catch (IOException | JSONException unreadable) {
            Log.w(TAG, source + ": unreadable manifest, treated as empty", unreadable);
        }
        return pictures;
    }

    private void writeManifest(String source, List<Picture> pictures) throws IOException {
        JSONObject manifest = new JSONObject();
        try {
            manifest.put("fetched_ms", System.currentTimeMillis());
            manifest.put("attribution_version", ATTRIBUTION_VERSION);
            JSONArray entries = new JSONArray();
            for (Picture picture : pictures) {
                entries.put(new JSONObject()
                        .put("url", picture.url)
                        .put("title", picture.title)
                        .put("credit", picture.credit)
                        .put("link", picture.link));
            }
            manifest.put("pictures", entries);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        File target = new File(cacheDir(source), "manifest.json");
        File partial = new File(target.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!partial.renameTo(target)) {
            throw new IOException("cannot write the manifest");
        }
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
    Bitmap decode(String source, Picture picture, int maxWidth, int maxHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = openPicture(source, picture)) {
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
            try (InputStream in = openPicture(source, picture)) {
                android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
                if (bitmap != null && PictureSources.LOCAL.equals(source)) {
                    // A picture that reads again clears the sentence that said one could not.
                    // Without this the record outlived the fault: a folder renamed away and back
                    // left the panel saying "a selected picture cannot be read" through three
                    // clean cycles and a rebuild, and only the reboot cleared it, because the
                    // record is in memory (found on the panel 2026-09-10).
                    problems.remove(source);
                }
                return bitmap;
            }
        } catch (IOException | OutOfMemoryError | RuntimeException unreadable) {
            problems.put(source, "A selected picture cannot be read. Check its storage or remove it from the playlist.");
            Log.w(TAG, "Cannot decode " + picture.url + ": " + unreadable.getMessage());
            return null;
        }
    }

    private InputStream openPicture(String source, Picture picture) throws IOException {
        if (!PictureSources.LOCAL.equals(source)) {
            return new FileInputStream(cacheFile(source, picture.url));
        }
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

    // ------------------------------------------------------------------ the pictures at home

    /**
     * Muralis's own picture store: the web admin's uploads land here, inside the app's private
     * files, so storing and showing them needs no permission on any Android and nothing for Play
     * to ask about.
     */
    private File storeDir() {
        return new File(app.getFilesDir(), "pictures");
    }

    /** Whether pictures can be reached at all: whether this panel may read its own. */
    boolean folderReachable() {
        return browser.canReadStorage();
    }

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
     * Every selected local picture, from uploads and all saved folder grants. A supplied caption
     * is its title; otherwise the file name in words is used.
     */
    List<Picture> listLocal() {
        ensurePlaylist();
        List<Picture> pictures = new ArrayList<>();
        PlaylistDocument.Playlist active = playlists.load().active();
        if (active == null) {
            return pictures;
        }
        Map<String, String> captions = captions();
        List<String> gone = new ArrayList<>();
        // The playlist's own order, which is the point of storing a list rather than a set.
        for (String uri : active.items) {
            String name = localName(uri);
            if (name == null) {
                gone.add(uri);
                continue;
            }
            pictures.add(localPicture(uri, name, captions));
        }
        if (!gone.isEmpty()) {
            dropDeleted(active.id, gone);
        }
        return pictures;
    }

    /**
     * Takes pictures that no longer exist out of the playlist that holds them.
     *
     * <p>Juri's rule of 2026-09-10, both halves of it: "Deleted image are removed from the
     * playlist. Images removed from the playlist are not physically deleted." This is the first
     * half, and the caution it needs is in {@link #localName}: a picture is only called gone when
     * this panel can read its own pictures and the store has no row for it, so an unmounted card or
     * a revoked permission cannot be mistaken for a deletion and quietly edit somebody's playlist.
     *
     * <p>The count is remembered for one sentence on the screens, because a playlist that shrinks
     * silently is indistinguishable from a bug.
     */
    private void dropDeleted(String playlistId, List<String> gone) {
        PlaylistDocument document = playlists.load();
        if (document.byId(playlistId) == null) {
            return;
        }
        document.remove(playlistId, gone, System.currentTimeMillis());
        if (playlists.store(document) == null) {
            deleted = gone.size();
            Log.i(TAG, "Removed " + gone.size() + " deleted picture(s) from the playlist");
            forgetLocalCount();
        }
    }

    /** How many pictures were dropped because their files are gone, for one sentence. */
    private volatile int deleted;

    /** The sentence about pictures that were dropped, or null. Said once, then forgotten. */
    String deletedNote() {
        int count = deleted;
        if (count == 0) {
            return null;
        }
        deleted = 0;
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
        String name = browser.mediaName(uri);
        return name == null && !browser.canReadStorage() ? "picture" : name;
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
            // Retried at the next call; a caption is not worth failing a screensaver over.
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
        File[] stored = storeDir().listFiles();
        if (stored != null) {
            for (File file : stored) {
                if (file.isFile() && !file.getName().endsWith(".part")
                        && PictureSources.imageMime(header(file)) != null) {
                    pictures.add(localPicture(Uri.fromFile(file).toString(), file.getName(),
                            captions));
                }
            }
        }
        java.util.Collections.sort(pictures, (left, right) -> left.credit.compareToIgnoreCase(
                right.credit));
        return pictures;
    }

    /** The credit field carries the file name here: it is what both surfaces list a picture by. */
    private Picture localPicture(String url, String name, Map<String, String> captions) {
        // By URI alone. The file-name fallback this replaced crossed captions between same-named
        // pictures in different folders; migrateCaptionKeys moved the old keys across instead.
        String caption = captions.get(url);
        String title = caption != null && !caption.isEmpty()
                ? caption : PictureSources.fileTitleToWords(name);
        return new Picture(url, title, name, "");
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

    /** Whether this picture is one of Muralis's own, which is what may be removed from here. */
    /** A {@code file:} address, which is either one of our uploads or a file on shared storage. */
    static boolean isStored(String url) {
        return url != null && url.startsWith("file:");
    }

    /**
     * Whether a {@code file:} address is one of Muralis's own uploads, and so ours to delete.
     *
     * <p>The distinction arrived with the folder browser on 2026-09-10. Until then every
     * {@code file:} address was an upload, because the only other kind of local picture came
     * through a document provider; now a device owner browses shared storage directly, and those
     * files are the person's, exactly like a picture reached through a grant: they can leave a
     * playlist but they are never deleted from here.
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
    synchronized String saveLocal(String filename, byte[] data) {
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
            String selection = selectPicture(Uri.fromFile(target).toString(), true);
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
     * Removes one of Muralis's uploaded pictures. A picture reached through any saved grant is
     * the person's own file and is never deleted here.
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
        forgetLocalCount();
        return null;
    }

    // ------------------------------------------------------------------ captions

    /**
     * The line under a local picture, stored by URI in a small JSON file beside the upload store.
     * Legacy filename keys are read as fallback; online sources never consult local captions.
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
     * The panel's own upload store as a page, for the browser's Uploads folder.
     *
     * <p>Uploads are not in MediaStore: they live inside the app's private files, which is what
     * makes them need no permission, and which is also why they cannot be listed by the same
     * query as everything else.
     */
    PictureBrowser.Page uploads(int offset) {
        PictureBrowser.Page page = new PictureBrowser.Page();
        File[] files = storeDir().listFiles();
        if (files == null) {
            return page;
        }
        java.util.Arrays.sort(files);
        List<PictureBrowser.Entry> found = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || file.getName().endsWith(".part")
                    || PictureSources.imageMime(header(file)) == null) {
                continue;
            }
            found.add(new PictureBrowser.Entry(Uri.fromFile(file).toString(), file.getName()));
        }
        page.available = found.size();
        int start = Math.max(0, Math.min(offset, found.size()));
        int end = Math.min(found.size(), start + PictureBrowser.PAGE_SIZE);
        page.entries.addAll(found.subList(start, end));
        page.more = end < found.size();
        return page;
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
     * A selected picture as {@code ./folder/name.jpg}, so two files with one name read apart.
     *
     * <p>The folder is a label, never an identity: a document id is opaque by contract, so the
     * folder is read out of one only when the id looks like a path, which is what the storage
     * providers on every test device produce, and the bare name is shown when it does not. An
     * upload says {@code ./uploads/} instead, since its real path is inside the app and means
     * nothing to a person.
     */
    String displayPath(String url, String name) {
        if (isUpload(url)) {
            return "./uploads/" + name;
        }
        String folder = browser.mediaFolder(url);
        return folder.isEmpty() ? name : "./" + folder + name;
    }

    /**
     * The same label, for a picture named by a playlist rather than by a listing.
     *
     * <p>A picture whose file is gone says so rather than showing an empty row: the playlist page
     * lists what is in the playlist, and something that cannot be found is exactly what somebody
     * looking at that page needs to see in order to take it out.
     */
    String displayPath(String url) {
        String name = localName(url);
        return name == null ? lastSegmentOf(url) + " (not found)" : displayPath(url, name);
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
                if (browser.mediaName(url) == null) {
                    return "Not a supported picture.";
                }
            } else {
                return "That is not a picture this panel can read.";
            }
        }
        PlaylistDocument document = playlists.load();
        PlaylistDocument.Playlist active = document.active();
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
