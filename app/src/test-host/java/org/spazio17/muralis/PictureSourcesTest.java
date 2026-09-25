/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.spazio17.muralis.PictureSources.Picture;

/**
 * The picture sources' parsers and the JSON reader under them, against answers recorded from the
 * live services on 2026-09-09. The credit line is a licence obligation, so its assembly is pinned
 * here field by field.
 */
public final class PictureSourcesTest {
    public static void main(String[] args) {
        testJsonReader();
        testJsonRefusesGarbage();
        testBingMarket();
        testBingArchive();
        testWikimediaPotdTitles();
        testWikimediaImageInfo();
        testWikimediaNotices();
        testWikimediaSkipsWhatIsNotAPicture();
        testHtmlStripping();
        testImageTypeByBytes();
        testSafeNames();
        testDepthBomb();
        testUrls();
        testUntrustedUrls();
        testDecodeBudget();
        testCreditFromLabel();
        System.out.println("PictureSourcesTest passed");
    }

    private static void testCreditFromLabel() {
        require("pd 1".equals(PictureSources.creditFromLabel("./Pictures/MuralisTest/pd_1.jpg")),
                "a folder label gives the file's name in words, as the screensaver prints it");
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

    private static void testUntrustedUrls() {
        for (String url : new String[] {"http://upload.wikimedia.org/x", "https://127.0.0.1/x",
                "https://www.bing.com@192.168.1.1/x", "https://www.bing.com.evil.test/x",
                "https://www.bing.com:8443/x", "file:///etc/passwd", "https://[::1]/x",
                "https://www.bing.com/x#fragment"}) {
            require(!PictureSources.allowedUrl(url), "refuse destination: " + url);
        }
        require(PictureSources.allowedUrl("https://thumb.wikimedia.org/x"), "Commons thumbnails");
        require(PictureSources.allowedUrl("https://www.bing.com/th?id=x"), "Bing pictures");
        require(PictureSources.parseWikimediaImageInfo(IMAGE_INFO.replace("Public domain", "")
                .replace("CC BY-SA 4.0", "")).isEmpty(), "no licence means no picture");
        require(PictureSources.parseBing(BING.replace("/th?id=OHR.GabitKeni_EN-US",
                "@127.0.0.1/th?id=OHR.GabitKeni_EN-US")).isEmpty(), "Bing cannot replace the authority");
    }

    private static void testBingMarket() {
        require(PictureSources.bingMarket("de", "AT").equals("de-AT"), "language-COUNTRY");
        require(PictureSources.bingMarket("it", "").equals("it"), "language alone");
        require(PictureSources.bingMarket("", null).equals("en-US"), "nothing known: en-US");
    }

    /** Recorded 2026-09-09 (one entry of seven, en-US). */
    private static final String BING = "{\"images\":[{\"startdate\":\"20260909\",\"fullstartdate\":"
            + "\"202609090700\",\"enddate\":\"20260910\",\"url\":\"/th?id=OHR.GabitKeni_EN-US4620523183_"
            + "1920x1080.jpg&rf=LaDigue_1920x1080.jpg&pid=hp\",\"urlbase\":\"/th?id=OHR.GabitKeni_EN-US"
            + "4620523183\",\"copyright\":\"Gabit Keni Beach near Ankola, Karnataka, India (\\u00a9 Amith "
            + "Nag Photography/Getty Images)\",\"copyrightlink\":\"https://www.bing.com/search?q=Ankola\","
            + "\"title\":\"Life on India's west coast\",\"quiz\":\"/search?q=Bing+homepage+quiz\","
            + "\"wp\":true,\"hsh\":\"abc\",\"drk\":1,\"top\":1,\"bot\":1,\"hs\":[]},"
            + "{\"urlbase\":\"\",\"copyright\":\"no base, skipped\"},"
            + "{\"urlbase\":\"/th?id=OHR.NoCredit\",\"copyright\":\"  \",\"title\":\"uncredited\"}],"
            + "\"tooltips\":{\"loading\":\"Loading...\"}}";

    private static void testBingArchive() {
        List<Picture> pictures = PictureSources.parseBing(BING);
        require(pictures.size() == 1,
                "one usable picture: no urlbase and no copyright are both skipped, and an "
                        + "uncredited picture must never reach the glass");
        Picture p = pictures.get(0);
        require(p.url.equals("https://www.bing.com/th?id=OHR.GabitKeni_EN-US4620523183_1920x1080.jpg"),
                "the picture URL is the base plus the 1920x1080 suffix: " + p.url);
        require(p.title.equals("Life on India's west coast"), "title");
        require(p.credit.equals("Gabit Keni Beach near Ankola, Karnataka, India (© Amith Nag "
                + "Photography/Getty Images)"), "the copyright line is the credit, verbatim: " + p.credit);
        require(p.link.startsWith("https://www.bing.com/search"), "the copyright link is kept");
        require(PictureSources.parseBing("{}").isEmpty(), "no images key, no pictures");
    }

    /** Recorded 2026-09-09: the POTD pages of two days. */
    private static final String POTD = "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"pageid\":1,"
            + "\"ns\":10,\"title\":\"Template:Potd/2026-09-07\",\"images\":[{\"ns\":6,\"title\":"
            + "\"File:Gary Plant Tubular Steel Corporation.jpg\"}]},{\"pageid\":2,\"ns\":10,\"title\":"
            + "\"Template:Potd/2026-09-06\",\"images\":[{\"ns\":6,\"title\":\"File:Indian rhinoceros "
            + "(Rhinoceros unicornis) 1.jpg\"}]},{\"ns\":10,\"title\":\"Template:Potd/2026-09-05\","
            + "\"missing\":true}]}}";

    private static void testWikimediaPotdTitles() {
        List<String> titles = PictureSources.parseWikimediaFileTitles(POTD);
        require(titles.size() == 2, "two days with a picture, one day missing");
        require(titles.get(0).equals("File:Gary Plant Tubular Steel Corporation.jpg"), "first title");
        require(titles.get(1).equals("File:Indian rhinoceros (Rhinoceros unicornis) 1.jpg"),
                "second title, spaces and brackets intact");
    }

    /** Recorded 2026-09-09, metadata trimmed to the filtered fields. */
    private static final String IMAGE_INFO = "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"pageid\":"
            + "3,\"ns\":6,\"title\":\"File:Gary Plant Tubular Steel Corporation.jpg\",\"imagerepository\":"
            + "\"local\",\"imageinfo\":[{\"thumburl\":\"https://thumb.wikimedia.org/wikipedia/commons/"
            + "thumb/7/7c/Gary_Plant_Tubular_Steel_Corporation.jpg/1920px-Gary_Plant_Tubular_Steel_"
            + "Corporation.jpg\",\"thumbwidth\":1920,\"thumbheight\":1515,\"url\":\"https://upload."
            + "wikimedia.org/wikipedia/commons/7/7c/Gary_Plant_Tubular_Steel_Corporation.jpg\","
            + "\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:Gary_Plant_Tubular_Steel_"
            + "Corporation.jpg\",\"mime\":\"image/jpeg\",\"extmetadata\":{\"ObjectName\":{\"value\":"
            + "\"<div class=\\\"fn\\\">\\n<i>Gary Plant Tubular Alloy Steel Corporation</i></div>\","
            + "\"source\":\"commons-desc-page\"},\"Artist\":{\"value\":\"<div class=\\\"fn value\\\">\\nM. "
            + "Marshall</div>\",\"source\":\"commons-desc-page\"},\"LicenseShortName\":{\"value\":"
            + "\"Public domain\",\"source\":\"commons-desc-page\",\"hidden\":\"\"}}}]},"
            + "{\"pageid\":4,\"ns\":6,\"title\":\"File:Some_map.svg\",\"imageinfo\":[{\"thumburl\":"
            + "\"https://thumb.wikimedia.org/x/1920px-Some_map.svg.png\",\"url\":\"https://upload."
            + "wikimedia.org/x/Some_map.svg\",\"mime\":\"image/svg+xml\",\"extmetadata\":{\"Artist\":"
            + "{\"value\":\"<a href=\\\"//commons.wikimedia.org/wiki/User:Cartographer\\\" title=\\\"User:"
            + "Cartographer\\\">Cartographer</a>\"},\"LicenseShortName\":{\"value\":\"CC BY-SA 4.0\"}}}]}]}}";

    private static void testWikimediaImageInfo() {
        List<Picture> pictures = PictureSources.parseWikimediaImageInfo(IMAGE_INFO);
        require(pictures.size() == 2, "two pictures");
        Picture first = pictures.get(0);
        require(first.url.startsWith("https://thumb.wikimedia.org/") && first.url.contains("1920px"),
                "the scaled rendition, not the original: " + first.url);
        require(first.title.equals("Gary Plant Tubular Alloy Steel Corporation"),
                "the object name, HTML stripped: " + first.title);
        require(first.credit.equals("M. Marshall, Public domain, Wikimedia Commons"),
                "artist, licence, source: " + first.credit);
        require(first.link.equals("https://commons.wikimedia.org/wiki/File:Gary_Plant_Tubular_Steel_Corporation.jpg"),
                "the file page is the link");
        Picture second = pictures.get(1);
        require(second.title.equals("Some map"), "no object name: the file title in words: " + second.title);
        require(second.credit.equals("Cartographer, CC BY-SA 4.0, Wikimedia Commons"),
                "a linked author becomes a name: " + second.credit);
        require(second.url.endsWith(".svg.png"), "an SVG's rendition is a PNG the panel can show");
    }

    private static void testWikimediaSkipsWhatIsNotAPicture() {
        String video = "{\"query\":{\"pages\":[{\"title\":\"File:Clip.webm\",\"imageinfo\":[{\"thumburl\":"
                + "\"https://thumb.wikimedia.org/x/1920px--Clip.webm.jpg\",\"url\":\"https://upload."
                + "wikimedia.org/x/Clip.webm\",\"mime\":\"video/webm\",\"extmetadata\":{}}]},"
                + "{\"title\":\"File:Missing.jpg\",\"missing\":true},"
                + "{\"title\":\"File:NoUrl.jpg\",\"imageinfo\":[{\"mime\":\"image/jpeg\"}]}]}}";
        require(PictureSources.parseWikimediaImageInfo(video).isEmpty(),
                "a video's still, a missing file and a file without a URL are all skipped");
    }

    private static void testWikimediaNotices() {
        String notices = IMAGE_INFO.replace("\"extmetadata\":{", "\"extmetadata\":{"
                + "\"Credit\":{\"value\":\"Museum collection\"},"
                + "\"Attribution\":{\"value\":\"Courtesy of the photographer\"},"
                + "\"LicenseUrl\":{\"value\":\"https://creativecommons.org/licenses/by/4.0/\"},");
        java.util.List<Picture> pictures = PictureSources.parseWikimediaImageInfo(notices);
        require(!pictures.isEmpty(), "the notices fixture still parses");
        for (Picture picture : pictures) {
            require(picture.credit.contains("Museum collection")
                    && picture.credit.contains("Courtesy of the photographer"),
                    "supplied notices travel with every picture");
            // A wall panel is read from across a room, so the credit names people and licences
            // and never carries an address (2026-09-10). The source page stays in Picture.link.
            require(!picture.credit.contains("http"),
                    "no web address reaches the credit line");
            require(picture.link.startsWith("https://commons.wikimedia.org/wiki/"),
                    "the source page is still recorded, just not shown");
        }
        // A supplied notice that is nothing but an address is a link row, not an attribution.
        String urlOnly = IMAGE_INFO.replace("\"extmetadata\":{", "\"extmetadata\":{"
                + "\"Credit\":{\"value\":\"https://example.com/photo\"},");
        for (Picture picture : PictureSources.parseWikimediaImageInfo(urlOnly)) {
            require(!picture.credit.contains("example.com"),
                    "a notice that is only an address is dropped");
        }
        require(PictureSources.isBareUrl("https://example.com/x")
                && PictureSources.isBareUrl("www.example.com")
                && !PictureSources.isBareUrl("Sharp Photography, sharpphotography.co.uk")
                && !PictureSources.isBareUrl("Museum collection"),
                "an address alone is recognised, prose that mentions one is not");
    }

    private static void testHtmlStripping() {
        require(PictureSources.stripHtml("<div class=\"fn value\">\nM. Marshall</div>")
                .equals("M. Marshall"), "tags and surrounding whitespace go");
        require(PictureSources.stripHtml("<a href=\"x\">Ann</a> &amp; <b>Bob</b>").equals("Ann & Bob"),
                "entities are decoded, tags become spaces: "
                        + PictureSources.stripHtml("<a href=\"x\">Ann</a> &amp; <b>Bob</b>"));
        require(PictureSources.stripHtml(null).isEmpty(), "null is empty");
        require(PictureSources.fileTitleToWords("File:Great_Wave_off_Kanagawa2.jpg")
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

    private static void testUrls() {
        require(PictureSources.bingArchiveUrl("de-AT").equals(
                "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=7&mkt=de-AT"), "bing URL");
        String potd = PictureSources.wikimediaPotdUrl(Arrays.asList("2026-09-09", "2026-09-08"));
        require(potd.endsWith("&titles=Template:Potd/2026-09-09%7CTemplate:Potd/2026-09-08"),
                "the days joined by an encoded pipe: " + potd);
        String info = PictureSources.wikimediaImageInfoUrl(
                Arrays.asList("File:Indian rhinoceros (Rhinoceros unicornis) 1.jpg"), 1920);
        require(info.contains("iiurlwidth=1920")
                && info.endsWith("titles=File:Indian%20rhinoceros%20%28Rhinoceros%20unicornis%29%201.jpg"),
                "titles are percent-encoded, the colon kept: " + info);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
