/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The panel's own pictures, browsed inside Muralis on every device.
 *
 * <p><b>One browser, both devices, decided 2026-09-10.</b> Juri: "Tablet and phone behaves
 * identically when creating playlist and selecting photos. Even if the phone has the possibility to
 * exit muralis and use the android's file browser, it is still Muralis that must handle the folder
 * and image selections." So there is one implementation here and no branch on device-owner status.
 * What differs is only how the read permission arrives: a device owner grants it to itself
 * silently, an ordinary install is asked once by the system dialog, which is the one thing Android
 * will not let an app do for itself. After that the two panels behave the same.
 *
 * <p><b>Why MediaStore and not directories.</b> {@code File.listFiles()} on
 * {@code /storage/emulated/0} was written first and does not work: at Play's {@code targetSdk}
 * floor scoped storage is enforced, so a read permission grants shared media <em>through
 * MediaStore</em> and not through the filesystem, and {@code listFiles} returns null (measured on
 * the Lenovo 2026-09-10). The alternative that does give real paths is
 * {@code MANAGE_EXTERNAL_STORAGE}, which Play treats as very restricted and which asks for far more
 * than looking at pictures. So a folder is MediaStore's own {@code RELATIVE_PATH}, and a picture is
 * a {@code content://media/...} id, which survives a reboot and opens with the permission.
 *
 * <p><b>Why there is no depth limit.</b> A {@code maxdepth} of 3 was built on the morning of
 * 2026-09-10 and removed the same day, by the reasoning in the specification and Juri's agreement
 * with it: {@code Pictures/2026/Italy/Rome/Vatican} is depth five and entirely ordinary, and a cap
 * would hide those photographs without saying so. There is also nothing for a cap to save here,
 * because this asks MediaStore for the folders it already knows rather than walking a tree.
 *
 * <p><b>What the SAF path took with it.</b> Granted document trees, {@code MAX_FOLDERS}, the
 * two-minute picker timer, "a saved folder has lost access", and a picture that could be held twice
 * under two different addresses, all deleted with it. One way to name a picture is what makes the
 * last of those impossible rather than merely fixed.
 */
final class PictureBrowser {
    /**
     * The page sizes Content offers, smallest first: a folder of thousands opens on the first ten
     * and the reader asks for more (Juri, 2026-09-19: with many pictures in one folder the panel
     * got very long). Both surfaces offer the same four.
     */
    static final int[] PAGE_SIZES = {10, 25, 50, 100};
    static final int DEFAULT_PAGE_SIZE = PAGE_SIZES[0];

    /** One of the offered sizes, from a typed or stored value; anything else is the default. */
    static int pageSize(String value) {
        try {
            return pageSize(Integer.parseInt(value == null ? "" : value.trim()));
        } catch (NumberFormatException notANumber) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    static int pageSize(int value) {
        for (int size : PAGE_SIZES) {
            if (size == value) {
                return size;
            }
        }
        return DEFAULT_PAGE_SIZE;
    }
    /** The prefix a browse location carries when it names a folder of this panel's own pictures. */
    static final String MEDIA = "media:";
    /** The panel's own upload store, which needs no permission at all. */
    static final String UPLOADS = "uploads";
    /** How long a folder index is trusted before another scan, when nothing invalidated it. */
    private static final long INDEX_FRESH_MS = 30_000L;
    /** The three types the browser lists and the decoder promises; anything else is not offered. */
    private static final Set<String> IMAGE_MIMES = new HashSet<>(
            java.util.Arrays.asList("image/jpeg", "image/png", "image/webp"));

    private static final String[] COLUMNS_MODERN = {
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.MIME_TYPE,
            android.provider.MediaStore.Images.Media.RELATIVE_PATH};
    private static final String[] COLUMNS_LEGACY = {
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.MIME_TYPE,
            android.provider.MediaStore.Images.Media.DATA};

    private final Context app;
    private volatile String browseProblem;
    /** The folder index: relative path to counts. Built by one scan, see {@link #folders()}. */
    private volatile List<Folder> index;
    private volatile long indexedAt;

    /** One folder that holds pictures, or that has one below it. */
    static final class Folder {
        /** MediaStore's relative path, always ending in "/". The top of the tree is "". */
        final String path;
        /** The last segment, which is what a row shows. */
        final String name;
        /** How many segments deep, so a tree can indent without parsing the path again. */
        final int depth;
        /** Pictures directly in this folder. */
        final int pictures;
        /** Pictures in this folder and every folder below it. */
        final int total;

        Folder(String path, int pictures, int total) {
            this.path = path;
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int slash = trimmed.lastIndexOf('/');
            this.name = trimmed.isEmpty() ? "Internal storage"
                    : slash < 0 ? trimmed : trimmed.substring(slash + 1);
            int segments = 0;
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    segments++;
                }
            }
            this.depth = segments;
            this.pictures = pictures;
            this.total = total;
        }
    }

    static final class Entry {
        final String uri;
        final String name;

        Entry(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    static final class Page {
        final List<Entry> entries = new ArrayList<>();
        String problem;
        boolean more;
        /** How many pictures the folder holds in total, so a page can say "100 of 2,601". */
        int available;
    }

    PictureBrowser(Context context) {
        app = context.getApplicationContext();
    }

    /** The read permission this Android version wants for shared pictures. */
    static String permission() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /**
     * Whether this panel may read its own pictures yet.
     *
     * <p>Read at call time rather than cached: a device owner's silent grant lands at service
     * start, and an ordinary install's arrives when somebody answers the dialog, both of which can
     * happen after this object was built.
     */
    boolean canReadStorage() {
        return app.checkSelfPermission(permission())
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasRelativePath() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q;
    }

    /** The folder a row belongs to, always ending in "/", so prefix tests are unambiguous. */
    private static String relativePathOf(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (hasRelativePath()) {
            return value.endsWith("/") ? value : value + "/";
        }
        // DATA is an absolute path; everything after the volume root is the relative one.
        int slash = value.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        String parent = value.substring(0, slash + 1);
        int marker = parent.indexOf("/0/");
        if (marker >= 0) {
            return parent.substring(marker + 3);
        }
        int storage = parent.indexOf("/storage/");
        if (storage < 0) {
            return parent.startsWith("/") ? parent.substring(1) : parent;
        }
        int after = parent.indexOf('/', storage + 9);
        return after < 0 ? "" : parent.substring(after + 1);
    }

    /** Throws away the folder index, so the next browse scans again. */
    void refresh() {
        index = null;
        indexedAt = 0L;
    }

    /**
     * Every folder that holds pictures, plus the folders above them, deepest paths included.
     *
     * <p>One scan of the picture index builds the whole tree, and the result is cached, because the
     * cost that matters is not depth but re-scanning on every navigation: that is the thing the
     * gallery apps this was modelled on get wrong, and it is what makes going back into a folder
     * feel instant. The cache is dropped by {@link #refresh()} and expires by itself, so a picture
     * added while somebody is choosing does appear.
     *
     * <p>Folders on the way to a picture are synthesised even when they hold none themselves, so
     * {@code Pictures/2026/Italy/} exists as a row even if every photograph is one level below it.
     * Without that the tree would have holes exactly where a deep library needs it.
     */
    List<Folder> folders() {
        List<Folder> known = index;
        if (known != null && android.os.SystemClock.elapsedRealtime() - indexedAt < INDEX_FRESH_MS) {
            return known;
        }
        if (!canReadStorage()) {
            browseProblem = "This panel may not read its own pictures yet.";
            return new ArrayList<>();
        }
        Map<String, int[]> direct = new TreeMap<>();
        try (Cursor cursor = app.getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                hasRelativePath() ? COLUMNS_MODERN : COLUMNS_LEGACY, null, null, null)) {
            if (cursor == null) {
                browseProblem = "This panel's pictures could not be read.";
                return new ArrayList<>();
            }
            while (cursor.moveToNext()) {
                String mime = cursor.getString(2);
                if (mime == null || !IMAGE_MIMES.contains(mime.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String path = relativePathOf(cursor.getString(3));
                int[] counts = direct.get(path);
                if (counts == null) {
                    direct.put(path, new int[] {1});
                } else {
                    counts[0]++;
                }
            }
        } catch (RuntimeException unreadable) {
            browseProblem = "This panel's pictures could not be read.";
            return new ArrayList<>();
        }
        // Totals climb the tree, and every ancestor becomes a row whether or not it holds pictures.
        Map<String, int[]> totals = new TreeMap<>();
        for (Map.Entry<String, int[]> entry : direct.entrySet()) {
            String path = entry.getKey();
            int count = entry.getValue()[0];
            String walk = path;
            while (true) {
                int[] carried = totals.get(walk);
                if (carried == null) {
                    totals.put(walk, new int[] {count});
                } else {
                    carried[0] += count;
                }
                if (walk.isEmpty()) {
                    break;
                }
                walk = parentOf(walk);
            }
        }
        List<Folder> built = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : totals.entrySet()) {
            int[] own = direct.get(entry.getKey());
            built.add(new Folder(entry.getKey(), own == null ? 0 : own[0], entry.getValue()[0]));
        }
        browseProblem = null;
        index = built;
        indexedAt = android.os.SystemClock.elapsedRealtime();
        return built;
    }

    /** "Pictures/holidays/" becomes "Pictures/"; "Pictures/" becomes "". */
    private static String parentOf(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? "" : trimmed.substring(0, slash + 1);
    }

    /**
     * One folder's pictures, a page at a time, and only the ones directly in it.
     *
     * <p>From API 29 the query carries the folder as a selection, so the provider does the
     * filtering. Below that {@code RELATIVE_PATH} does not exist, so the rows are filtered here
     * against {@code DATA}'s parent; that is a scan, and it is what the API 26 panel does. Either
     * way one page is built and the cursor is closed, so a folder of tens of thousands costs a
     * cursor walk and never a copy of the library.
     */
    Page pictures(String relativePath, int offset, int size) {
        Page page = new Page();
        if (!canReadStorage()) {
            page.problem = "This panel may not read its own pictures yet.";
            return page;
        }
        String folder = relativePath == null ? "" : relativePath;
        List<Entry> found = new ArrayList<>();
        String selection = hasRelativePath()
                ? android.provider.MediaStore.Images.Media.RELATIVE_PATH + " = ?" : null;
        String[] arguments = hasRelativePath() ? new String[] {folder} : null;
        try (Cursor cursor = app.getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                hasRelativePath() ? COLUMNS_MODERN : COLUMNS_LEGACY,
                selection, arguments,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME + " ASC")) {
            if (cursor == null) {
                page.problem = "This panel's pictures could not be read.";
                browseProblem = page.problem;
                return page;
            }
            while (cursor.moveToNext()) {
                String mime = cursor.getString(2);
                if (mime == null || !IMAGE_MIMES.contains(mime.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!hasRelativePath() && !relativePathOf(cursor.getString(3)).equals(folder)) {
                    continue;
                }
                String name = cursor.getString(1);
                found.add(new Entry(android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)).toString(),
                        name == null || name.isEmpty() ? "Unnamed picture" : name));
            }
        } catch (RuntimeException unreadable) {
            page.problem = "This panel's pictures could not be read.";
            browseProblem = page.problem;
            return page;
        }
        // The provider's own sort is applied above; below API 29 the rows arrive sorted too, but
        // the filtering happens here, so the order is settled once for both paths.
        Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
        page.available = found.size();
        int start = Math.max(0, Math.min(offset, found.size()));
        int end = Math.min(found.size(), start + size);
        page.entries.addAll(found.subList(start, end));
        page.more = end < found.size();
        browseProblem = null;
        return page;
    }

    /** Every picture in one folder, in the same order the pages show, for "select all". */
    List<Entry> everyPicture(String relativePath) {
        List<Entry> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            Page page = pictures(relativePath, offset, PAGE_SIZES[PAGE_SIZES.length - 1]);
            all.addAll(page.entries);
            if (!page.more || page.entries.isEmpty()) {
                return all;
            }
            offset += page.entries.size();
        }
    }

    /** Whether a picture address is one of this panel's own MediaStore images. */
    static boolean isMedia(String uri) {
        return uri != null && uri.startsWith("content://media/");
    }

    /**
     * The display name of one of the panel's own pictures, or null when it is not a readable one.
     *
     * <p>Asked of MediaStore rather than taken from the caller, because the browser is reachable
     * from the web admin and a request only proves somebody typed an address.
     */
    String mediaName(String uri) {
        try (Cursor cursor = app.getContentResolver().query(Uri.parse(uri), new String[] {
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String mime = cursor.getString(1);
            if (mime == null || !IMAGE_MIMES.contains(mime.toLowerCase(Locale.ROOT))) {
                return null;
            }
            String name = cursor.getString(0);
            return name == null || name.isEmpty() ? "picture" : name;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The folder path one of the panel's own pictures sits in, ending in "/", or "" when unknown.
     *
     * <p>The whole path and not just the last segment, because the Selected list prepends it so
     * two files called {@code test.jpg} in different folders read apart, which is the form agreed
     * on 2026-09-10.
     */
    String mediaFolder(String uri) {
        try (Cursor cursor = app.getContentResolver().query(Uri.parse(uri),
                hasRelativePath()
                        ? new String[] {android.provider.MediaStore.Images.Media.RELATIVE_PATH}
                        : new String[] {android.provider.MediaStore.Images.Media.DATA},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return "";
            }
            return relativePathOf(cursor.getString(0));
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * Every picture id this panel can see, keyed by its own folder path and file name.
     *
     * <p>For the migration off document URIs, and the exact half of it: the external-storage
     * provider's document ids are {@code primary:Pictures/PhoneTest/pd_1.jpg}, which is precisely
     * MediaStore's relative path plus display name, so the same file can be found with no guessing
     * at all. That matters because two folders may each hold a {@code pd_1.jpg}, which a name alone
     * cannot tell apart (measured on the phone 2026-09-10). Providers with genuinely opaque ids
     * have no path to offer, and {@link #idsByName()} is the fallback for those.
     */
    Map<String, String> idsByPath() {
        Map<String, String> byPath = new LinkedHashMap<>();
        if (!canReadStorage()) {
            return byPath;
        }
        try (Cursor cursor = app.getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                hasRelativePath() ? COLUMNS_MODERN : COLUMNS_LEGACY, null, null, null)) {
            if (cursor == null) {
                return byPath;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String key = (relativePathOf(cursor.getString(3)) + name).toLowerCase(Locale.ROOT);
                byPath.put(key, android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)).toString());
            }
        } catch (RuntimeException unreadable) {
            return byPath;
        }
        return byPath;
    }

    /**
     * Every picture id this panel can see, by display name alone, for the migration's fallback.
     * A name that matches several pictures maps to none of them, because guessing which one
     * somebody meant is worse than telling them to tick it again.
     */
    Map<String, String> idsByName() {
        Map<String, String> unique = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        if (!canReadStorage()) {
            return unique;
        }
        try (Cursor cursor = app.getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                hasRelativePath() ? COLUMNS_MODERN : COLUMNS_LEGACY, null, null, null)) {
            if (cursor == null) {
                return unique;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                if (ambiguous.contains(key)) {
                    continue;
                }
                if (unique.containsKey(key)) {
                    unique.remove(key);
                    ambiguous.add(key);
                    continue;
                }
                unique.put(key, android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)).toString());
            }
        } catch (RuntimeException unreadable) {
            return unique;
        }
        return unique;
    }

    String problem() {
        return browseProblem;
    }
}
