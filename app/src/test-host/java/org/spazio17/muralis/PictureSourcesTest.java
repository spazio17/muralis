/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.List;
import java.util.Map;


/**
 * The pictures' pure helpers (credits from labels, types by bytes, safe names, decode sizes) and
 * the JSON reader the fleet and the playlists stand on.
 */
public final class PictureSourcesTest {
    public static void main(String[] args) {
        testJsonReader();
        testJsonRefusesGarbage();
        testFileTitleToWords();
        testImageTypeByBytes();
        testSafeNames();
        testDepthBomb();
        testDecodeBudget();
        testCreditFromLabel();
        System.out.println("PictureSourcesTest passed");
    }

    private static void testCreditFromLabel() {
        require("pd 1".equals(PictureSources.creditFromLabel("./Pictures/MuralisTest/pd_1.jpg")),
                "a folder label gives the file's name in words, as Ambient prints it");
        require("SardineBait-UHD".equals(
                PictureSources.creditFromLabel("./uploads/SardineBait-UHD.jpg")),
                "an upload's label works the same");
        require("IMG 20260925".equals(PictureSources.creditFromLabel("IMG_20260925.png")),
                "a bare name with an extension, when the store knows no folder");
        require("".equals(PictureSources.creditFromLabel("186" + PictureSources.NOT_FOUND)),
                "a picture that is gone has no default credit");
        require("".equals(PictureSources.creditFromLabel("186")),
                "a bare store id, when the store did not answer, is not a name");
        require("".equals(PictureSources.creditFromLabel("picture")),
                "nor is the word the page shows when the panel may not read its pictures");
        require("".equals(PictureSources.creditFromLabel(null))
                && "".equals(PictureSources.creditFromLabel("")), "nothing gives nothing");
        require("läbel test".equals(
                PictureSources.creditFromLabel("./Pictures/Review 0.6.1 é/läbel test.png")),
                "accents and spaces in the folder and the name survive");
    }

    private static void testDecodeBudget() {
        for (int[] size : new int[][] {{1920, 1080}, {4000, 3000}, {65535, 16},
                {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            int sample = PictureSources.decodeSample(size[0], size[1], 1920, 1080);
            long pixels = ((size[0] + (long) sample - 1) / sample)
                    * ((size[1] + (long) sample - 1) / sample);
            require(pixels <= 2_097_152L, "decoded pixels must fit the per-picture budget");
        }
        require(PictureSources.decodeSample(1920, 1080, 1920, 1080) == 1, "HD stays intact");
    }

    private static void testJsonReader() {
        Object parsed = TinyJson.parse(
                "{\"a\": [1, 2.5, -3e2, true, false, null, \"x\\\"y\\u00e9\\n\"], \"b\": {}, \"c\": []}");
        Map<String, Object> root = TinyJson.object(parsed);
        List<Object> a = TinyJson.array(root.get("a"));
        require(a.size() == 7, "seven values in the array");
        require(a.get(0).equals(1.0) && a.get(1).equals(2.5) && a.get(2).equals(-300.0),
                "numbers read as doubles");
        require(a.get(3).equals(Boolean.TRUE) && a.get(4).equals(Boolean.FALSE) && a.get(5) == null,
                "literals");
        require(a.get(6).equals("x\"yé\n"), "escapes, unicode included: " + a.get(6));
        require(TinyJson.object(root.get("b")).isEmpty() && TinyJson.array(root.get("c")).isEmpty(),
                "empty containers");
        require(TinyJson.string(root, "missing", "d").equals("d"), "fallback for a missing string");
        require(TinyJson.object("not an object").isEmpty() && TinyJson.array(null).isEmpty(),
                "the helpers never throw on the wrong shape");
    }

    private static void testJsonRefusesGarbage() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\" 1}", "tru", "\"open", "{} x", null,
                "01", "1.", "-.1", "1e9999", "\"raw\ncontrol\"", "\"\\u+123\"", "\"\\u-123\""}) {
            try {
                TinyJson.parse(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // the caller keeps the last good set
            }
        }
    }

    private static void testFileTitleToWords() {
        require(PictureSources.fileTitleToWords("Great_Wave_off_Kanagawa2.jpg")
                .equals("Great Wave off Kanagawa2"), "file title to words");
    }

    /** The type is the bytes' business; a name can say anything. */
    private static void testImageTypeByBytes() {
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        byte[] webp = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};
        require("image/jpeg".equals(PictureSources.imageMime(jpeg)), "JPEG by signature");
        require("image/png".equals(PictureSources.imageMime(png)), "PNG by signature");
        require("image/webp".equals(PictureSources.imageMime(webp)), "WebP by RIFF and WEBP");
        require(PictureSources.imageMime("<?php echo 1; ?>".getBytes()) == null, "a script is not a picture");
        require(PictureSources.imageMime(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' '}) == null,
                "a RIFF that is not WebP is refused");
        require(PictureSources.imageMime(null) == null && PictureSources.imageMime(new byte[3]) == null,
                "nothing and too little are refused");
    }

    /** The second half of the path defence: nothing stored can name a directory or hide. */
    private static void testSafeNames() {
        require(PictureSources.safeName("holiday.png", "image/jpeg", 7).equals("holiday.jpg"),
                "the extension follows the bytes, not the name: "
                        + PictureSources.safeName("holiday.png", "image/jpeg", 7));
        require(PictureSources.safeName("wall.jpg", "image/webp", 7).equals("wall.webp"), "WebP");
        require(PictureSources.safeName("../../etc/passwd", "image/jpeg", 7).equals("passwd.jpg"),
                "a path is stripped here too, not only in MultipartForm: "
                        + PictureSources.safeName("../../etc/passwd", "image/jpeg", 7));
        require(PictureSources.safeName("C:\\pics\\..\\wall.jpg", "image/jpeg", 7).equals("wall.jpg"),
                "a Windows path as well: "
                        + PictureSources.safeName("C:\\pics\\..\\wall.jpg", "image/jpeg", 7));
        require(PictureSources.safeName(".hidden.jpg", "image/jpeg", 7).equals("hidden.jpg"),
                "a leading dot cannot make a dotfile");
        require(PictureSources.safeName("...", "image/png", 42).equals("picture-42.png"),
                "a name that filters down to nothing becomes a timestamp: "
                        + PictureSources.safeName("...", "image/png", 42));
        require(PictureSources.safeName("!!!.jpg", "image/jpeg", 42).equals("picture-42.jpg"),
                "so does a name of nothing but punctuation");
        require(PictureSources.safeName(null, "image/jpeg", 9).equals("picture-9.jpg"), "no name");
        String long_ = PictureSources.safeName(new String(new char[200]).replace('\0', 'a')
                + ".jpg", "image/jpeg", 7);
        require(long_.length() == 84, "a long name is cut to 80 characters plus the extension: "
                + long_.length());
    }

    /** A depth bomb is malformed, not a stack overflow: the caller keeps its last good set. */
    private static void testDepthBomb() {
        StringBuilder bomb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            bomb.append("[");
        }
        try {
            TinyJson.parse(bomb.toString());
            throw new AssertionError("a depth bomb was accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("nested deeper"),
                    "the refusal must name the reason: " + expected.getMessage());
        }
        StringBuilder deepButFine = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            deepButFine.append("[");
        }
        deepButFine.append("1");
        for (int i = 0; i < 20; i++) {
            deepButFine.append("]");
        }
        TinyJson.parse(deepButFine.toString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
