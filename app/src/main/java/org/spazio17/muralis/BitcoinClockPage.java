/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The Bitcoin clock's page as both surfaces serve it: the HTML of res/raw/bitcoin_clock.html with
 * its fonts inlined and the address it reads its state from; the state, which is the clock's
 * options and its integration's values; and the background picture. On the panel the dashboard's
 * WebView asks for these at {@link #BASE}, answered in place (KioskActivity); in the web admin
 * they are routes of their own (HttpAdminServer), for the preview of Clock options.
 */
final class BitcoinClockPage {
    private static final String TAG = "MuralisClock";
    /** The panel's own address for the page, never on the network: its requests are answered in place. */
    static final String BASE = "http://muralis.clock/";
    /** The largest side a background picture is sent at. */
    private static final int PICTURE_MAX_PX = 1920;

    private static volatile String template;

    private BitcoinClockPage() {
    }

    /**
     * The page for one integration: {@code stateUrl} and {@code pictureUrl} are where it asks,
     * {@code preview} for the editors' preview, which neither moves against burn-in nor asks as
     * often.
     */
    static String html(Context context, String stateUrl, String pictureUrl, boolean preview) {
        String page = template;
        if (page == null) {
            page = readRaw(context, R.raw.bitcoin_clock)
                    .replace("/*FONTS*/", fontFace(context, "clock-antonio", R.raw.clock_antonio)
                            + fontFace(context, "clock-oswald", R.raw.clock_oswald)
                            + fontFace(context, "clock-label", R.raw.clock_oswald)
                            + fontFace(context, "clock-mono", R.raw.clock_azeret_mono)
                            // The styles' own: Flip's cards, Nixie's letters, Digital's segments.
                            + fontFace(context, "clock-flip", R.raw.clock_roboto_condensed)
                            + fontFace(context, "clock-thin", R.raw.clock_antonio_thin)
                            + fontFace(context, "clock-seg7", R.raw.clock_dseg7)
                            + fontFace(context, "clock-seg14", R.raw.clock_dseg14));
            template = page;
        }
        return page.replace("/*STATE_URL*/", jsText(stateUrl))
                .replace("/*PICTURE_URL*/", jsText(pictureUrl))
                .replace("/*PREVIEW*/false", preview ? "true" : "false");
    }

    /**
     * What the page reads every few seconds: the stored options; the credit line of each
     * picture of the album behind the clock, in order ("" for none), which also says how many
     * there are; and the integration as the status document has it without its settings, with
     * how long ago it last answered.
     */
    static String state(Context context, String instanceId) {
        JSONObject state = new JSONObject();
        try {
            BitcoinClock.Options options = KioskConfig.clockOptionsOf(context, instanceId);
            java.util.List<PictureSources.Picture> pictures = album(context, options.album);
            // An album deleted or emptied since it was chosen is no picture: the colour shows.
            if (pictures.isEmpty()) {
                options.album = "";
            }
            state.put("options", new JSONObject(options.toJson()));
            JSONArray credits = new JSONArray();
            for (PictureSources.Picture picture : pictures) {
                String line = picture.creditLine();
                credits.put(line == null ? "" : line);
            }
            state.put("pictures", credits);
            JSONArray block = IntegrationRunner.withoutSettings(KioskRuntimeState.integrations());
            for (int index = 0; index < block.length(); index++) {
                JSONObject entry = block.optJSONObject(index);
                if (entry != null && instanceId.equals(entry.optString("id"))) {
                    state.put("entry", entry);
                    long updated = entry.optLong("updated_at", 0);
                    if (updated > 0 && !"ok".equals(entry.optString("state"))) {
                        state.put("age_s", Math.max(0,
                                (System.currentTimeMillis() - updated) / 1000));
                    }
                }
            }
        } catch (JSONException impossible) {
            // The options were written as JSON, and the entry is JSON already.
        }
        return state.toString();
    }

    /** The album last listed, and when: the page asks every two seconds, an album is a query. */
    private static String listedAlbum = "";
    private static long listedAtMs;
    private static java.util.List<PictureSources.Picture> listed = new java.util.ArrayList<>();

    /** The pictures of album {@code id}, listed again at most every half minute; empty for none. */
    private static synchronized java.util.List<PictureSources.Picture> album(Context context,
            String id) {
        if (id.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!id.equals(listedAlbum) || now - listedAtMs > 30_000L) {
            listed = PictureLibrary.get(context).album(id);
            listedAlbum = id;
            listedAtMs = now;
        }
        return listed;
    }

    /**
     * Picture {@code index} of album {@code id} as a JPEG for the background, or null when there
     * is no such picture or it cannot be read. Only a picture of an album, and one the panel may
     * show (PictureLibrary.preview): the page names it, so it must never reach another file.
     * Worker thread only.
     */
    static byte[] picture(Context context, String id, String index) {
        if (id == null || index == null || !index.matches("\\d{1,4}")) {
            return null;
        }
        java.util.List<PictureSources.Picture> pictures = album(context, id);
        int at = Integer.parseInt(index);
        if (at >= pictures.size()) {
            return null;
        }
        Bitmap bitmap = PictureLibrary.get(context).preview(pictures.get(at).url, PICTURE_MAX_PX);
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
        bitmap.recycle();
        return jpeg.toByteArray();
    }

    /** The added integrations with a page, the choices under the dashboard's fourth answer. */
    static java.util.List<Integrations.Instance> pageInstances(Context context) {
        java.util.List<Integrations.Instance> found = new java.util.ArrayList<>();
        for (Integrations.Instance instance : KioskConfig.integrationsOf(context)) {
            if (Integrations.hasPage(instance.definition)) {
                found.add(instance);
            }
        }
        return found;
    }

    /** An added integration's name by its instance id; the id itself when it is gone. */
    static String nameOf(Context context, String instanceId) {
        for (Integrations.Instance instance : KioskConfig.integrationsOf(context)) {
            if (instance.id.equals(instanceId)) {
                return instance.name;
            }
        }
        return instanceId;
    }

    private static String fontFace(Context context, String family, int rawRes) {
        return "@font-face{font-family:'" + family + "';src:url(data:font/woff2;base64,"
                + Base64.encodeToString(readBytes(context, rawRes), Base64.NO_WRAP)
                // One weight each, shipped as cut: declared for every weight, so the engine never
                // thickens it with a synthetic bold.
                + ") format('woff2');font-weight:100 900;font-display:block}";
    }

    /** A value for a JavaScript text between single quotes. */
    private static String jsText(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c");
    }

    private static String readRaw(Context context, int rawRes) {
        return new String(readBytes(context, rawRes), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Context context, int rawRes) {
        try (InputStream in = context.getResources().openRawResource(rawRes);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException unreadable) {
            // Shipped inside the app; unreadable only if the package itself is damaged.
            Log.w(TAG, "Cannot read a shipped resource", unreadable);
            return new byte[0];
        }
    }
}
