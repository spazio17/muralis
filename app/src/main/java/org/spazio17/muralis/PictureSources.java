/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the Pictures screensaver gets its pictures from, and how each source's answer becomes a
 * picture with a credit line. The credit line is not decoration: Bing's images are copyrighted
 * and attribution alone does not establish permission to reuse them. Commons files have their
 * own licence/rights statements: retain the supplied author, licence and notices. This parser is
 * not a licence audit or a grant of rights for either source.
 *
 * <p>Pure: the URLs are built and the answers parsed here with no network and no Android, so the
 * parsers are host-tested against recorded responses; fetching, caching and decoding live in
 * {@code PictureLibrary}.
 */
final class PictureSources {
    /** Individually selected pictures from the panel's storage and the private upload store. */
    static final String LOCAL = "local";
    /** Bing's homepage image of the day; unofficial endpoint, credited, off until chosen. */
    static final String BING = "bing";
    /** Wikimedia Commons' picture of the day; free licences, credited per file. */
    static final String WIKIMEDIA = "wikimedia";

    /** How many days back each online source is asked for, so a set exists rather than one picture. */
    static final int ONLINE_DAYS = 7;

    static final class Picture {
        /** Where to fetch it: an https URL for the online sources, a {@code content:} URI locally. */
        final String url;
        final String title;
        /** The whole attribution line, as the source demands it. Empty only for local pictures. */
        final String credit;
        /** Where the credit line could point, kept for the status document; may be empty. */
        final String link;
        /**
         * The owner switched this picture's credit off on the playlist page. Only a local picture
         * can carry it: an online source's line is the attribution its licence asks for.
         */
        final boolean creditHidden;

        Picture(String url, String title, String credit, String link) {
            this(url, title, credit, link, false);
        }

        Picture(String url, String title, String credit, String link, boolean creditHidden) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.credit = credit == null ? "" : credit;
            this.link = link == null ? "" : link;
            this.creditHidden = creditHidden;
        }
    }

    /**
     * What the playlist page says after a picture whose file is gone, so the label can be told
     * apart from a real name by the code that reads it back (see {@link #creditFromLabel}).
     */
    static final String NOT_FOUND = " (not found)";

    /**
     * The credit a local picture shows when its owner has typed none, worked out from the label
     * the playlist page already has ({@code ./folder/name.jpg}), so the page can show it in the
     * empty box without asking the picture store a second time. Empty when the label names no
     * file: a picture that is gone, and the bare store id or the word "picture" the page falls
     * back to when the store did not answer, neither of which has a folder or an extension.
     */
    static String creditFromLabel(String label) {
        if (label == null || label.isEmpty() || label.endsWith(NOT_FOUND)) {
            return "";
        }
        String name = label.substring(label.lastIndexOf('/') + 1);
        if (!label.contains("/") && name.indexOf('.') < 0) {
            return "";
        }
        return fileTitleToWords(name);
    }

    private PictureSources() {
    }

    /** Only these services may choose download destinations, including every redirect hop. */
    static boolean allowedUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawFragment() != null) {
                return false;
            }
            String host = uri.getHost();
            return "www.bing.com".equalsIgnoreCase(host)
                    || "commons.wikimedia.org".equalsIgnoreCase(host)
                    || "upload.wikimedia.org".equalsIgnoreCase(host)
                    || "thumb.wikimedia.org".equalsIgnoreCase(host);
        } catch (java.net.URISyntaxException | NullPointerException invalid) {
            return false;
        }
    }

    /** At most eight MiB of ARGB pixels per decoded picture, including unusually shaped files. */
    static int decodeSample(int width, int height, int screenWidth, int screenHeight) {
        int sample = 1;
        int targetWidth = Math.max(1, Math.min(1920, screenWidth));
        int targetHeight = Math.max(1, Math.min(1920, screenHeight));
        while (sample < (1 << 30)) {
            long pixels = ((width + (long) sample - 1) / sample)
                    * ((height + (long) sample - 1) / sample);
            if (pixels <= 2_097_152L && width / (sample * 2L) < targetWidth
                    && height / (sample * 2L) < targetHeight) break;
            sample *= 2;
        }
        return sample;
    }

    static boolean isSource(String value) {
        return LOCAL.equals(value) || BING.equals(value) || WIKIMEDIA.equals(value);
    }

    static String sourceName(String source) {
        switch (source == null ? "" : source) {
            case BING:
                return "Bing image of the day";
            case WIKIMEDIA:
                return "Wikimedia Commons picture of the day";
            case LOCAL:
            default:
                return "this panel";
        }
    }

    // ---------------------------------------------------------------- Bing

    /**
     * Bing's market code for a device locale, {@code de-AT} style; the archive answers a market
     * it does not know with the international set, so nothing is checked here.
     */
    static String bingMarket(String language, String country) {
        if (language == null || language.isEmpty()) {
            return "en-US";
        }
        if (country == null || country.isEmpty()) {
            return language.toLowerCase(Locale.ROOT);
        }
        return language.toLowerCase(Locale.ROOT) + "-" + country.toUpperCase(Locale.ROOT);
    }

    static String bingArchiveUrl(String market) {
        return "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=" + ONLINE_DAYS
                + "&mkt=" + market;
    }

    /**
     * The archive's {@code images} list: {@code urlbase} is a path such as
     * {@code /th?id=OHR.Name_EN-US123}, complete with a size suffix; {@code copyright} is the
     * whole attribution Microsoft prints under the picture and is used verbatim as the credit.
     */
    static List<Picture> parseBing(String json) {
        List<Picture> pictures = new ArrayList<>();
        Map<String, Object> root = TinyJson.object(TinyJson.parse(json));
        for (Object entry : TinyJson.array(root.get("images"))) {
            Map<String, Object> image = TinyJson.object(entry);
            String base = TinyJson.string(image, "urlbase", "");
            if (!base.startsWith("/th?id=")) {
                continue;
            }
            String copyright = TinyJson.string(image, "copyright", "");
            if (copyright.trim().isEmpty()) {
                // No attribution, no picture. This display requirement does not itself grant
                // rights to use the photograph.
                continue;
            }
            String link = TinyJson.string(image, "copyrightlink", "");
            pictures.add(new Picture(
                    "https://www.bing.com" + base + "_1920x1080.jpg",
                    TinyJson.string(image, "title", ""),
                    copyright,
                    link.startsWith("http") ? link : ""));
            if (pictures.size() == ONLINE_DAYS) break;
        }
        return pictures;
    }

    // ---------------------------------------------------------------- Wikimedia Commons

    /**
     * One query for the last {@link #ONLINE_DAYS} days' {@code Template:Potd/YYYY-MM-DD} pages,
     * whose single image each is the day's picture. {@code dates} are ISO days, newest first.
     */
    static String wikimediaPotdUrl(List<String> dates) {
        StringBuilder titles = new StringBuilder();
        for (String date : dates) {
            if (titles.length() > 0) {
                titles.append("%7C");
            }
            titles.append("Template:Potd/").append(date);
        }
        return "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&prop=images&imlimit=50&titles=" + titles;
    }

    /** The {@code File:} titles the POTD pages name, in the order the API returned the pages. */
    static List<String> parseWikimediaFileTitles(String json) {
        List<String> titles = new ArrayList<>();
        Map<String, Object> query = TinyJson.object(TinyJson.object(TinyJson.parse(json)).get("query"));
        for (Object entry : TinyJson.array(query.get("pages"))) {
            for (Object image : TinyJson.array(TinyJson.object(entry).get("images"))) {
                String title = TinyJson.string(TinyJson.object(image), "title", "");
                if (title.startsWith("File:") && !titles.contains(title)) {
                    titles.add(title);
                    if (titles.size() == ONLINE_DAYS) return titles;
                }
            }
        }
        return titles;
    }

    /**
     * One query for every file's scaled URL, MIME type and the metadata the credit needs.
     * {@code iiurlwidth} asks Commons for a rendition, which turns a 200 MB TIFF or an SVG into
     * a JPEG or PNG a tablet can decode; a video's rendition is a still, which is why the MIME
     * type is checked too.
     */
    static String wikimediaImageInfoUrl(List<String> fileTitles, int width) {
        StringBuilder titles = new StringBuilder();
        for (String title : fileTitles) {
            if (titles.length() > 0) {
                titles.append("%7C");
            }
            titles.append(encode(title));
        }
        return "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&prop=imageinfo&iiprop=url%7Cmime%7Cextmetadata"
                + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName%7CCredit%7CAttribution"
                + "&iiurlwidth=" + width + "&titles=" + titles;
    }

    /**
     * Pictures with their credit lines, skipping anything that is not an image. The credit is
     * "Artist, Licence, Wikimedia Commons": {@code Artist} arrives as HTML (a link to the user
     * page, sometimes a table) and is flattened to its text; {@code LicenseShortName} is the
     * licence the file demands attribution under.
     */
    static List<Picture> parseWikimediaImageInfo(String json) {
        List<Picture> pictures = new ArrayList<>();
        Map<String, Object> query = TinyJson.object(TinyJson.object(TinyJson.parse(json)).get("query"));
        for (Object entry : TinyJson.array(query.get("pages"))) {
            Map<String, Object> page = TinyJson.object(entry);
            List<Object> infos = TinyJson.array(page.get("imageinfo"));
            if (infos.isEmpty()) {
                continue;
            }
            Map<String, Object> info = TinyJson.object(infos.get(0));
            String mime = TinyJson.string(info, "mime", "");
            String rendition = TinyJson.string(info, "thumburl", "");
            String url = rendition.isEmpty() ? TinyJson.string(info, "url", "") : rendition;
            // An image, and either a format this platform decodes or a rendition Commons made
            // for us: an SVG or a TIFF arrives as a PNG or JPEG that way. A video's still and a
            // PDF's first page also have renditions, and are not pictures of the day, so the
            // MIME type decides first (2026-09-09 review).
            boolean decodable = "image/jpeg".equals(mime) || "image/png".equals(mime)
                    || "image/webp".equals(mime);
            if (!allowedUrl(url) || !mime.startsWith("image/")
                    || !(decodable || !rendition.isEmpty())) {
                continue;
            }
            Map<String, Object> meta = TinyJson.object(info.get("extmetadata"));
            String artist = stripHtml(metaValue(meta, "Artist"));
            String licence = stripHtml(metaValue(meta, "LicenseShortName"));
            if (artist.isEmpty() || licence.isEmpty()) {
                continue;
            }
            String title = stripHtml(metaValue(meta, "ObjectName"));
            String pageTitle = TinyJson.string(page, "title", "");
            if (title.isEmpty()) {
                title = fileTitleToWords(pageTitle);
            }
            StringBuilder credit = new StringBuilder();
            if (!artist.isEmpty()) {
                credit.append(artist);
            }
            if (!licence.isEmpty()) {
                credit.append(credit.length() > 0 ? ", " : "").append(licence);
            }
            credit.append(credit.length() > 0 ? ", " : "").append("Wikimedia Commons");
            String link = TinyJson.string(info, "descriptionurl", "");
            if (!link.startsWith("https://commons.wikimedia.org/wiki/")) {
                link = "https://commons.wikimedia.org/wiki/" + encode(pageTitle);
            }
            // The supplied notices, which name people and institutions, but not the licence
            // address: it is a row of link text on a screen nobody types from, and the licence
            // itself is already named above (2026-09-10). A notice that is only an address is
            // dropped for the same reason; one that names somebody is kept as it was supplied,
            // because that wording is the attribution the licence asks for.
            for (String field : new String[] {"Credit", "Attribution"}) {
                String notice = stripHtml(metaValue(meta, field));
                if (notice.isEmpty() || isBareUrl(notice) || credit.indexOf(notice) >= 0) {
                    continue;
                }
                credit.append("\n").append(notice);
            }
            pictures.add(new Picture(url, title, credit.toString(), link));
            if (pictures.size() == ONLINE_DAYS) break;
        }
        return pictures;
    }

    /** A notice that is nothing but a web address: a link row, not an attribution. */
    static boolean isBareUrl(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.indexOf(' ') >= 0) {
            return false;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("www.");
    }

    private static String metaValue(Map<String, Object> meta, String key) {
        return TinyJson.string(TinyJson.object(meta.get(key)), "value", "");
    }

    /** "File:Great_Wave_off_Kanagawa2.jpg" to "Great Wave off Kanagawa2". */
    static String fileTitleToWords(String fileTitle) {
        String name = fileTitle.startsWith("File:") ? fileTitle.substring(5) : fileTitle;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('_', ' ').trim();
    }

    /**
     * Tags out, entities in, whitespace folded: what a credit needs from the HTML Commons stores
     * for an author ("&lt;a href=...&gt;Name&lt;/a&gt;" or a wikitable of names and dates).
     */
    static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') {
                inTag = true;
                out.append(' ');
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                out.append(c);
            }
        }
        String text = out.toString()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * A picture's type by its bytes, never by its name: a browser's declared type is whatever the
     * file was called. JPEG, PNG and WebP are what the screensaver promises to decode.
     *
     * @return the MIME type, or null when the bytes are none of the three
     */
    static String imageMime(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if ((data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if ((data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * A file name safe to store: letters, digits, dots, dashes, underscores and spaces only,
     * with the extension the bytes earned. The second half of the path defence, after
     * {@link MultipartForm#baseName}: nothing here can name a directory, walk up one, or hide as
     * a dotfile, and a name that filters down to nothing becomes a timestamp.
     */
    static String safeName(String filename, String mime, long nowMs) {
        String extension = "image/png".equals(mime) ? ".png"
                : "image/webp".equals(mime) ? ".webp" : ".jpg";
        String base = filename == null ? "" : filename;
        // The path goes first, before the extension is looked for: in "../../etc/passwd" the
        // last dot belongs to the path, not to a name, and cutting there left nothing behind.
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        StringBuilder clean = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ' || c == '.') {
                clean.append(c);
            }
        }
        String name = clean.toString().trim();
        while (name.startsWith(".")) {
            name = name.substring(1).trim();
        }
        if (name.length() > 80) {
            name = name.substring(0, 80).trim();
        }
        if (name.isEmpty()) {
            name = "picture-" + nowMs;
        }
        return name + extension;
    }

    private static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':';
            if (plain) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }
}
