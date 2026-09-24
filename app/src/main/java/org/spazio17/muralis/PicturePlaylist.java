/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Granted folders are places to browse; the playlist is a separate selection of individual files.
 * Browsing never releases lock task. Only adding a grant, explicitly at the settings screen,
 * opens Android's picker. Document IDs are opaque, so ancestry is enforced by the provider.
 */
final class PicturePlaylist {
    static final int PAGE_SIZE = 100;
    static final int MAX_PICTURES = 1000;
    static final int MAX_FOLDERS = 16;
    /**
     * How far below a granted folder the browser will descend: the folder itself, then three more.
     *
     * <p>Decided 2026-09-10, and it replaces worrying about enormous roots rather than adding to
     * it: "large folders should not exist, simply maxdepth = 3". Somebody's pictures live at
     * {@code <root>/Pictures/holidays/2026}, not eleven levels down, and a person who granted the
     * whole of internal storage should not be handed a file manager. The depth travels in the
     * browse token because a document id is opaque and cannot be measured against its tree. That
     * is a limit on how much of a listing an interface will build, not a security boundary: a
     * caller who forged a shallower depth would still only reach folders inside a grant it
     * already has, which {@link #contains} decides and the provider enforces.
     */
    static final int MAX_DEPTH = 3;
    static final String UPLOADS = "uploads";
    static final String SELECTED = "selected";
    private final Context app;
    private final SharedPreferences prefs;
    private volatile String browseProblem;
    private volatile String persistenceProblem;

    static final class Entry {
        final String uri;
        final String name;
        final boolean folder;
        final boolean selected;

        Entry(String uri, String name, boolean folder, boolean selected) {
            this.uri = uri;
            this.name = name;
            this.folder = folder;
            this.selected = selected;
        }
    }

    static final class Page {
        final List<Entry> entries = new ArrayList<>();
        String problem;
        boolean more;
        /** How far below the granted folder this listing sits; folders stop being offered at the floor. */
        int depth;
        /** True when a folder here was not offered because {@link #MAX_DEPTH} was reached. */
        boolean deeperFoldersHidden;
    }

    PicturePlaylist(Context context) {
        app = context.getApplicationContext();
        prefs = KioskConfig.storageContext(app).getSharedPreferences("picture_playlist", 0);
    }

    synchronized void migrate(List<PictureSources.Picture> previous) {
        if (prefs.getBoolean("migrated", false)) return;
        SharedPreferences.Editor edit = prefs.edit();
        Set<String> selected = new HashSet<>(prefs.getStringSet("selected", Collections.emptySet()));
        for (PictureSources.Picture picture : previous) {
            selected.add(picture.url);
            edit.putString("name:" + picture.url, picture.credit);
        }
        Set<String> roots = roots();
        String old = KioskConfig.screensaverFolderUri(app);
        if (!old.isEmpty()) roots.add(old);
        if (edit.putStringSet("selected", selected).putStringSet("roots", roots)
                .putBoolean("migrated", true).commit()) persistenceProblem = null;
        else persistenceProblem = "The playlist could not be persisted; check the panel's free storage.";
    }

    boolean migrated() { return prefs.getBoolean("migrated", false); }

    Set<String> roots() { return new HashSet<>(prefs.getStringSet("roots", Collections.emptySet())); }

    synchronized String addFolder(Uri uri) {
        Set<String> roots = roots();
        boolean alreadySaved = roots.contains(uri.toString());
        if (!alreadySaved && roots.size() >= MAX_FOLDERS) {
            return "At most " + MAX_FOLDERS + " folders; forget one before adding another.";
        }
        try {
            app.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException refused) {
            return "Android could not keep access to this folder. The existing folders are unchanged.";
        }
        roots.add(uri.toString());
        if (!prefs.edit().putStringSet("roots", roots).commit()) {
            if (!alreadySaved) {
                try {
                    app.getContentResolver().releasePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // The refusal remains accurate: no saved root depends on this grant.
                }
            }
            persistenceProblem = "The folder access could not be persisted; check the panel's free storage.";
            return "Cannot save the folder.";
        }
        persistenceProblem = null;
        browseProblem = null;
        return null;
    }

    synchronized String forget(String root) {
        Set<String> roots = roots();
        if (!roots.remove(root)) return "This folder is not saved.";
        // Keep selected files recorded: a removed card or grant must not silently edit a playlist.
        if (!prefs.edit().putStringSet("roots", roots).commit()) {
            persistenceProblem = "The folder change could not be persisted; check the panel's free storage.";
            return "Cannot save the folder change.";
        }
        persistenceProblem = null;
        browseProblem = null;
        try {
            app.getContentResolver().releasePersistableUriPermission(Uri.parse(root),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException gone) {
            // The local record is still removed when Android already forgot the grant.
        }
        return null;
    }

    boolean hasGrant(String root) {
        for (UriPermission grant : app.getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && grant.getUri().toString().equals(root)) return true;
        }
        return false;
    }

    boolean hasLostGrant() {
        for (String root : roots()) {
            if (!hasGrant(root)) return true;
        }
        return false;
    }

    boolean contains(Uri uri) {
        try {
            String tree = DocumentsContract.getTreeDocumentId(uri);
            for (String root : roots()) {
                Uri granted = Uri.parse(root);
                if (granted.getAuthority().equals(uri.getAuthority())
                        && DocumentsContract.getTreeDocumentId(granted).equals(tree)
                        && hasGrant(root)) return true;
            }
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return false;
    }

    List<PictureSources.Picture> pictures() {
        List<PictureSources.Picture> pictures = new ArrayList<>();
        for (String uri : prefs.getStringSet("selected", Collections.emptySet())) {
            String name = prefs.getString("name:" + uri, "picture");
            pictures.add(new PictureSources.Picture(uri, PictureSources.fileTitleToWords(name), name, ""));
        }
        Collections.sort(pictures, (a, b) -> {
            int byName = a.credit.compareToIgnoreCase(b.credit);
            return byName == 0 ? a.url.compareTo(b.url) : byName;
        });
        return pictures;
    }

    boolean selected(String uri) {
        return prefs.getStringSet("selected", Collections.emptySet()).contains(uri);
    }

    synchronized String select(String uri, String name, boolean selected) {
        Set<String> selection = new HashSet<>(prefs.getStringSet("selected", Collections.emptySet()));
        if (selected) {
            if (!selection.contains(uri) && selection.size() >= MAX_PICTURES) {
                return "The playlist holds at most " + MAX_PICTURES + " pictures.";
            }
            selection.add(uri);
        } else {
            selection.remove(uri);
        }
        SharedPreferences.Editor edit = prefs.edit().putStringSet("selected", selection);
        if (selected) edit.putString("name:" + uri, name); else edit.remove("name:" + uri);
        if (edit.commit()) {
            persistenceProblem = null;
            return null;
        }
        persistenceProblem = "The playlist could not be persisted; check the panel's free storage.";
        return "Cannot save the playlist.";
    }

    /** One directory, at most one page in memory. No recursive walk of a picked storage root. */
    Page browse(String location, int offset) {
        return browse(location, offset, 0);
    }

    /** One directory, at most one page in memory. No recursive walk of a picked storage root. */
    Page browse(String location, int offset, int depth) {
        Page page = new Page();
        page.depth = depth;
        if (location == null || location.isEmpty()) {
            page.entries.add(new Entry(SELECTED, "Selected pictures", true, false));
            page.entries.add(new Entry(UPLOADS, "Uploaded pictures", true, false));
            List<String> roots = new ArrayList<>(roots());
            Collections.sort(roots);
            for (String root : roots) {
                Uri tree = Uri.parse(root);
                String id = DocumentsContract.getTreeDocumentId(tree);
                Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String label = "primary:".equals(id) ? "Internal storage"
                        : id.startsWith("primary:") ? id.substring(8) : id;
                page.entries.add(new Entry(document.toString(), label, true, false));
            }
            return page;
        }
        Uri parent = Uri.parse(location);
        if (!contains(parent)) {
            page.problem = "This folder has no saved access. Grant it on the panel's settings screen.";
            return page;
        }
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
                    DocumentsContract.getDocumentId(parent));
            try (Cursor cursor = app.getContentResolver().query(children, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IllegalStateException("Storage did not answer");
                int seen = 0;
                while (cursor.moveToNext()) {
                    String mime = cursor.getString(2);
                    boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if (folder && depth >= MAX_DEPTH) {
                        page.deeperFoldersHidden = true;
                        continue;
                    }
                    if (!folder && !("image/jpeg".equals(mime) || "image/png".equals(mime)
                            || "image/webp".equals(mime))) continue;
                    if (seen++ < Math.max(0, offset)) continue;
                    if (page.entries.size() == PAGE_SIZE) { page.more = true; break; }
                    String uri = DocumentsContract.buildDocumentUriUsingTree(parent,
                            cursor.getString(0)).toString();
                    String name = cursor.getString(1);
                    page.entries.add(new Entry(uri, name == null || name.isEmpty()
                            ? "Unnamed document" : name, folder, selected(uri)));
                }
            }
            browseProblem = null;
        } catch (RuntimeException unreadable) {
            page.problem = "This folder is unavailable. Reconnect its storage or grant access again.";
            browseProblem = page.problem;
        }
        return page;
    }

    String problem() {
        if (hasLostGrant()) return "A saved folder has lost access; grant it again on the panel.";
        return persistenceProblem != null ? persistenceProblem : browseProblem;
    }
}
