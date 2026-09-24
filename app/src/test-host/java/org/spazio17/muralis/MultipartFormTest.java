/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.spazio17.muralis.MultipartForm.Part;

/** The upload body parser: a boundary byte off and every picture stored would be corrupt. */
public final class MultipartFormTest {
    public static void main(String[] args) {
        testBoundary();
        testTwoParts();
        testBinaryBytesSurvive();
        testTwoFilesInOneRequest();
        testPathsAreStripped();
        testNonAsciiNames();
        testNothingUsable();
        testTruncatedSecondPart();
        testBoundaryPrefixInData();
        System.out.println("MultipartFormTest passed");
    }

    private static void testBoundary() {
        require("----WebKitFormBoundaryabc".equals(MultipartForm.boundaryOf(
                "multipart/form-data; boundary=----WebKitFormBoundaryabc")), "a browser's boundary");
        require("x y".equals(MultipartForm.boundaryOf("multipart/form-data; boundary=\"x y\"")),
                "a quoted boundary loses its quotes");
        require(MultipartForm.boundaryOf("application/x-www-form-urlencoded") == null,
                "not multipart: no boundary");
        require(MultipartForm.boundaryOf(null) == null && MultipartForm.boundaryOf(
                "multipart/form-data") == null, "missing boundary is null, not empty");
    }

    private static byte[] body(String text) {
        return text.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void testTwoParts() {
        byte[] body = body("preamble ignored\n--B\nContent-Disposition: form-data; name=\"note\"\n\n"
                + "hello\n--B\nContent-Disposition: form-data; name=\"picture\"; filename=\"a.jpg\"\n"
                + "Content-Type: image/jpeg\n\nJPEGDATA\n--B--\nepilogue");
        List<Part> parts = MultipartForm.parse(body, "B");
        require(parts.size() == 2, "two parts, preamble and epilogue ignored: " + parts.size());
        require(parts.get(0).name.equals("note") && parts.get(0).filename.isEmpty()
                && new String(parts.get(0).data, StandardCharsets.ISO_8859_1).equals("hello"),
                "the field part");
        Part file = parts.get(1);
        require(file.name.equals("picture") && file.filename.equals("a.jpg")
                && file.contentType.equals("image/jpeg"), "the file part's headers");
        require(new String(file.data, StandardCharsets.ISO_8859_1).equals("JPEGDATA"),
                "the data stops before the CRLF that precedes the closing boundary: "
                        + new String(file.data, StandardCharsets.ISO_8859_1));
    }

    private static void testBinaryBytesSurvive() {
        byte[] head = body("--B\nContent-Disposition: form-data; name=\"picture\"; filename=\"b.png\"\n"
                + "Content-Type: image/png\n\n");
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7);
        }
        // Bytes that spell a partial boundary and a CRLF inside the data must not split it.
        data[10] = '-'; data[11] = '-'; data[12] = 'B'; data[13] = '\r'; data[14] = '\n';
        byte[] tail = body("\n--B--\n");
        byte[] whole = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, whole, 0, head.length);
        System.arraycopy(data, 0, whole, head.length, data.length);
        System.arraycopy(tail, 0, whole, head.length + data.length, tail.length);
        List<Part> parts = MultipartForm.parse(whole, "B");
        require(parts.size() == 1, "one part");
        require(java.util.Arrays.equals(parts.get(0).data, data),
                "every byte of the picture, including a partial boundary inside it, arrives");
    }

    private static void testPathsAreStripped() {
        require(MultipartForm.baseName("C:\\Users\\me\\Pictures\\wall.jpg").equals("wall.jpg"),
                "a Windows path becomes its file name");
        require(MultipartForm.baseName("/home/me/../etc/passwd").equals("passwd"),
                "a unix path becomes its file name");
        require(MultipartForm.baseName("plain.png").equals("plain.png"), "a plain name stays");
        require(MultipartForm.baseName(null).isEmpty(), "null is empty");
    }

    /** What the page's "multiple" file input actually sends. */
    private static void testTwoFilesInOneRequest() {
        byte[] body = body("--B\nContent-Disposition: form-data; name=\"picture\"; filename=\"a.jpg\"\n"
                + "Content-Type: image/jpeg\n\nAAA\n"
                + "--B\nContent-Disposition: form-data; name=\"picture\"; filename=\"b.png\"\n"
                + "Content-Type: image/png\n\nBBBB\n"
                + "--B\nContent-Disposition: form-data; name=\"picture\"; filename=\"\"\n"
                + "Content-Type: application/octet-stream\n\n\n--B--\n");
        List<Part> parts = MultipartForm.parse(body, "B");
        require(parts.size() == 3, "three parts, the empty file input included: " + parts.size());
        require(parts.get(0).filename.equals("a.jpg")
                && new String(parts.get(0).data, StandardCharsets.ISO_8859_1).equals("AAA"),
                "the first file");
        require(parts.get(1).filename.equals("b.png")
                && new String(parts.get(1).data, StandardCharsets.ISO_8859_1).equals("BBBB"),
                "the second file");
        require(parts.get(2).filename.isEmpty() && parts.get(2).data.length == 0,
                "a file input nobody filled in: no name, no bytes, and the caller skips it");
    }

    private static void testNonAsciiNames() {
        byte[] body = ("--B\r\nContent-Disposition: form-data; name=\"picture\"; "
                + "filename=\"na\u00efve.jpg\"\r\n\r\nX\r\n--B--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        List<Part> parts = MultipartForm.parse(body, "B");
        require(parts.size() == 1 && parts.get(0).filename.equals("naïve.jpg"),
                "a UTF-8 file name arrives as itself: " + parts.get(0).filename);
        byte[] extended = body("--B\nContent-Disposition: form-data; name=\"picture\"; "
                + "filename*=UTF-8''na%C3%AFve%20photo.jpg\n\nX\n--B--\n");
        parts = MultipartForm.parse(extended, "B");
        require(parts.size() == 1 && parts.get(0).filename.equals("naïve photo.jpg"),
                "the encoded form is decoded too: " + parts.get(0).filename);
    }

    private static void testNothingUsable() {
        require(MultipartForm.parse(null, "B").isEmpty(), "no body");
        require(MultipartForm.parse(body("garbage without a boundary"), "B").isEmpty(), "no boundary");
        require(MultipartForm.parse(body("--B\nContent-Disposition: form-data; name=\"x\"\n\nunterminated"),
                "B").isEmpty(), "a part with no closing boundary is dropped, not half-taken");
    }

    private static void testTruncatedSecondPart() {
        require(MultipartForm.parse(body("--B\nContent-Disposition: form-data; name=x\n\nfirst"
                + "\n--B\nContent-Disposition: form-data; name=y\n\ntruncated"), "B").isEmpty(),
                "a truncated request must not store its completed first file");
    }

    private static void testBoundaryPrefixInData() {
        List<Part> parts = MultipartForm.parse(body("--B\nContent-Disposition: form-data; name=x"
                + "\n\nfirst\n--Bnot-a-delimiter\nlast\n--B--\n"), "B");
        require(parts.size() == 1 && new String(parts.get(0).data, StandardCharsets.ISO_8859_1)
                .equals("first\r\n--Bnot-a-delimiter\r\nlast"), "boundary prefixes are picture bytes");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
