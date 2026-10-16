/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

/**
 * The person's own pictures as Ambient shows them: one picture with its title and credit line,
 * and the pure helpers around them (a file's type by its bytes, a safe stored name, a credit
 * worked out from a label, how far to scale a decode). No network and no Android, so they are
 * host-tested; reading, storing and decoding live in {@code PictureLibrary}.
 */
final class PictureSources {
    static final class Picture {
        /** Where it is: a {@code content:} URI or a file of the private upload store. */
        final String url;
        final String title;
        /** The credit line its owner typed; empty when none was typed. */
        final String credit;
        /** The owner switched this picture's credit off on the playlist page. */
        final boolean creditHidden;

        Picture(String url, String title, String credit) {
            this(url, title, credit, false);
        }

        Picture(String url, String title, String credit, boolean creditHidden) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.credit = credit == null ? "" : credit;
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

    /** A file's name as words: "Great_Wave_off_Kanagawa2.jpg" to "Great Wave off Kanagawa2". */
    static String fileTitleToWords(String fileTitle) {
        String name = fileTitle;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('_', ' ').trim();
    }

    /**
     * A picture's type by its bytes, never by its name: a browser's declared type is whatever the
     * file was called. JPEG, PNG and WebP are what Ambient promises to decode.
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
}
