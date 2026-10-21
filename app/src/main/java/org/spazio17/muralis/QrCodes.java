/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * QR codes drawn with ZXing (Apache 2.0), for the Join screen and the setup QR: the modules, which the
 * panel paints into a bitmap, and the same as SVG for the web admin. Always dark on light with
 * a quiet zone, whatever the theme, since that is what a camera reads. The Join code carries the
 * app's icon in its centre; the setup QR, already dense with a settings file, does not.
 */
final class QrCodes {
    private QrCodes() {
    }

    /**
     * The app's icon in the centre of a code, on {@link #ICON_UNITS} units: the dark tile, the
     * tablet's outline with its camera, and the four panes, the numbers of
     * res/drawable/ic_launcher_foreground.xml.
     */
    static final int ICON_UNITS = 108;
    static final int ICON_TILE = 0xFF12131D;
    static final int ICON_LIGHT = 0xFFB9C3FF;
    static final int ICON_DEEP = 0xFF6F86FF;
    /** The outline: left, top, right, bottom, corner and stroke width, in icon units. */
    static final float[] ICON_OUTLINE = {26, 30, 82, 78, 6, 4};
    /** The camera, a hole in the top of the outline in the tile's colour: centre x, y, radius. */
    static final float[] ICON_DOT = {54, 30, 1.25f};
    /** The panes' corner, in icon units. */
    static final float ICON_PANE_CORNER = 2;
    /**
     * How much larger the glyph is drawn than on the launcher icon, about the tile's centre: the
     * dark edge around it half as wide, so the glyph reads at a code's small size.
     */
    static final float ICON_GLYPH_SCALE = 41f / 28f;
    /** The panes, left, top, right, bottom, each followed by whether it is the light one. */
    static final float[][] ICON_PANES = {
            {33, 40, 57, 52, 1}, {61, 40, 75, 52, 0}, {33, 56, 47, 68, 0}, {51, 56, 75, 68, 1}};

    /** One entry per module, quiet zone included; null when the content cannot be encoded. */
    static BitMatrix matrix(String content) {
        return matrix(content, false);
    }

    /**
     * The same, with room for the icon when {@code withIcon}: the strongest error correction,
     * which reads through the icon: a fifth of the code's width, a twentieth of its area.
     */
    static BitMatrix matrix(String content, boolean withIcon) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION,
                withIcon ? ErrorCorrectionLevel.H : ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException | IllegalArgumentException tooLong) {
            return null;
        }
    }

    /**
     * How many modules the icon's square covers, white edge included: an odd number near a fifth
     * of the code's width without its quiet zone, so it sits on the centre module.
     */
    static int iconModules(BitMatrix modules) {
        int code = modules.getWidth() - 8;
        int side = Math.max(7, Math.round(code * 0.22f));
        return side % 2 == 0 ? side + 1 : side;
    }

    /** The code as an SVG document, one module per unit; null when it cannot be encoded. */
    static String svg(String content) {
        return svg(content, false);
    }

    /** The same, with the app's icon in the centre when {@code withIcon}. */
    static String svg(String content, boolean withIcon) {
        BitMatrix modules = matrix(content, withIcon);
        if (modules == null) {
            return null;
        }
        int width = modules.getWidth();
        int height = modules.getHeight();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < width) {
                if (!modules.get(x, y)) {
                    x++;
                    continue;
                }
                int run = 1;
                while (x + run < width && modules.get(x + run, y)) {
                    run++;
                }
                path.append('M').append(x).append(' ').append(y)
                        .append('h').append(run).append("v1h-").append(run).append('z');
                x += run;
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + width + " " + height
                + "\" shape-rendering=\"crispEdges\"><rect width=\"" + width + "\" height=\""
                + height + "\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/>"
                + (withIcon ? iconSvg(modules) : "") + "</svg>";
    }

    /** The icon over the centre of the code: a white square, then the dark tile and its glyph. */
    private static String iconSvg(BitMatrix modules) {
        int side = iconModules(modules);
        float start = (modules.getWidth() - side) / 2f;
        float tile = side - 1f;
        float scale = tile / ICON_UNITS;
        float x0 = start + 0.5f;
        StringBuilder icon = new StringBuilder();
        icon.append("<g shape-rendering=\"geometricPrecision\"><rect x=\"").append(start)
                .append("\" y=\"").append(start).append("\" width=\"").append(side)
                .append("\" height=\"").append(side).append("\" fill=\"#fff\"/>")
                .append("<g transform=\"translate(").append(x0).append(' ').append(x0)
                .append(") scale(").append(scale).append(")\"><rect width=\"").append(ICON_UNITS)
                .append("\" height=\"").append(ICON_UNITS).append("\" rx=\"24\" fill=\"")
                .append(hex(ICON_TILE)).append("\"/><g transform=\"translate(54 54) scale(")
                .append(ICON_GLYPH_SCALE).append(") translate(-54 -54)\"><rect x=\"")
                .append(ICON_OUTLINE[0])
                .append("\" y=\"").append(ICON_OUTLINE[1]).append("\" width=\"")
                .append(ICON_OUTLINE[2] - ICON_OUTLINE[0]).append("\" height=\"")
                .append(ICON_OUTLINE[3] - ICON_OUTLINE[1]).append("\" rx=\"").append(ICON_OUTLINE[4])
                .append("\" fill=\"none\" stroke=\"").append(hex(ICON_LIGHT))
                .append("\" stroke-width=\"").append(ICON_OUTLINE[5]).append("\"/><circle cx=\"")
                .append(ICON_DOT[0]).append("\" cy=\"").append(ICON_DOT[1]).append("\" r=\"")
                .append(ICON_DOT[2]).append("\" fill=\"").append(hex(ICON_TILE)).append("\"/>");
        for (float[] pane : ICON_PANES) {
            icon.append("<rect x=\"").append(pane[0]).append("\" y=\"").append(pane[1])
                    .append("\" width=\"").append(pane[2] - pane[0]).append("\" height=\"")
                    .append(pane[3] - pane[1]).append("\" rx=\"").append(ICON_PANE_CORNER)
                    .append("\" fill=\"")
                    .append(hex(pane[4] > 0 ? ICON_LIGHT : ICON_DEEP)).append("\"/>");
        }
        return icon.append("</g></g></g>").toString();
    }

    private static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }
}
