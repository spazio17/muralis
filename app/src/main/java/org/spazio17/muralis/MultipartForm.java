/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code multipart/form-data} body a browser sends for a file upload, split into its parts.
 * The web admin's picture upload is the only caller; every other form on that server is
 * urlencoded and stays so. Pure and host-tested, because a parser that is off by one boundary
 * byte silently corrupts every picture it stores.
 */
final class MultipartForm {
    static final class Part {
        final String name;
        /** The client's file name without any path, or empty for an ordinary field. */
        final String filename;
        final String contentType;
        final byte[] data;

        Part(String name, String filename, String contentType, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }
    }

    private MultipartForm() {
    }

    /** The boundary named in a {@code Content-Type: multipart/form-data; boundary=...} header, or null. */
    static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (!lower.split(";", 2)[0].trim().equals("multipart/form-data")) {
            return null;
        }
        for (String piece : contentType.split(";")) {
            String trimmed = piece.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return validBoundary(boundary) ? boundary : null;
            }
        }
        return null;
    }

    /**
     * Splits {@code body} at every delimiter, which is CRLF followed by {@code --boundary} (the
     * first one may open the body without the CRLF); each part is its headers, a blank line, and
     * the bytes up to the next delimiter. Searching for the bare {@code --boundary} would cut a
     * picture at any place those bytes happen to occur, which the test pins. Anything before the
     * first delimiter or after the closing {@code --boundary--} is ignored, as the RFC allows.
     */
    static List<Part> parse(byte[] body, String boundary) {
        List<Part> parts = new ArrayList<>();
        if (body == null || !validBoundary(boundary)) {
            return parts;
        }
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int at;
        if (startsWith(body, marker, 0)) {
            at = 0;
        } else {
            int first = indexOf(body, delimiter, 0);
            at = first < 0 ? -1 : first + 2;
        }
        while (at >= 0) {
            int lineEnd = at + marker.length;
            if (lineEnd + 1 < body.length && body[lineEnd] == '-' && body[lineEnd + 1] == '-') {
                return parts;
            }
            if (!startsWith(body, new byte[] {'\r', '\n'}, lineEnd)) return new ArrayList<>();
            int headersStart = skipCrlf(body, lineEnd);
            int blank = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headersStart);
            if (blank < 0 || blank - headersStart > 8192 || parts.size() >= 64) {
                break;
            }
            // UTF-8, so a file name with an accent or a non-Latin script arrives as itself;
            // the bytes of a part are copied raw either way.
            String headers = new String(body, headersStart, blank - headersStart,
                    StandardCharsets.UTF_8);
            int dataStart = blank + 4;
            int next = nextDelimiter(body, delimiter, dataStart);
            if (next < 0) {
                break;
            }
            byte[] data = new byte[next - dataStart];
            System.arraycopy(body, dataStart, data, 0, data.length);
            String filename = headerParameter(headers, "content-disposition", "filename");
            if (filename.isEmpty()) {
                // RFC 5987's encoded form, which some clients send instead: filename*=UTF-8''name
                filename = decodeExtended(
                        headerParameter(headers, "content-disposition", "filename*"));
            }
            parts.add(new Part(
                    headerParameter(headers, "content-disposition", "name"),
                    baseName(filename),
                    headerValue(headers, "content-type"),
                    data));
            at = next + 2;
        }
        // A completed first part does not make a truncated second part a successful upload.
        return new ArrayList<>();
    }

    private static boolean validBoundary(String boundary) {
        if (boundary == null || boundary.isEmpty() || boundary.length() > 70
                || boundary.endsWith(" ")) return false;
        for (int i = 0; i < boundary.length(); i++) {
            char c = boundary.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && "'()+_,-./:=? ".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static int nextDelimiter(byte[] body, byte[] delimiter, int from) {
        int next = from;
        while ((next = indexOf(body, delimiter, next)) >= 0) {
            int end = next + delimiter.length;
            if (startsWith(body, new byte[] {'\r', '\n'}, end)
                    || (startsWith(body, new byte[] {'-', '-'}, end)
                    && (end + 2 == body.length
                    || startsWith(body, new byte[] {'\r', '\n'}, end + 2)))) return next;
            next++;
        }
        return -1;
    }

    private static boolean startsWith(byte[] body, byte[] prefix, int at) {
        if (at + prefix.length > body.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (body[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int skipCrlf(byte[] body, int at) {
        if (at + 1 < body.length && body[at] == '\r' && body[at + 1] == '\n') {
            return at + 2;
        }
        return at;
    }

    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    /** {@code name="..."} inside a header's parameters; browsers quote, curl quotes, both handled. */
    private static String headerParameter(String headers, String header, String parameter) {
        String value = headerValue(headers, header);
        for (String piece : value.split(";")) {
            String trimmed = piece.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT)
                    .equals(parameter)) {
                String raw = trimmed.substring(equals + 1).trim();
                if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw = raw.substring(1, raw.length() - 1);
                }
                return raw.replace("\\\"", "\"");
            }
        }
        return "";
    }

    /** {@code UTF-8''na%C3%AFve.jpg} to {@code naïve.jpg}; the charset label is ignored, UTF-8 assumed. */
    static String decodeExtended(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int quotes = value.indexOf("''");
        String encoded = quotes >= 0 ? value.substring(quotes + 2) : value;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                try {
                    out.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException notAnEscape) {
                    // Written through as it stands: a stray percent is not a reason to lose a name.
                }
            }
            out.write(String.valueOf(c).getBytes(StandardCharsets.UTF_8), 0,
                    String.valueOf(c).getBytes(StandardCharsets.UTF_8).length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The file name alone: a browser may send a path, and a path must never reach the disk. */
    static String baseName(String filename) {
        if (filename == null) {
            return "";
        }
        int cut = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = cut >= 0 ? filename.substring(cut + 1) : filename;
        return name.trim();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
