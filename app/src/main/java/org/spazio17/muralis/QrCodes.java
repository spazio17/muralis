/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
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
 * QR codes drawn with ZXing (Apache 2.0), for the Join screen: the modules themselves, which the
 * panel paints into a bitmap, and the same as SVG for the web admin. Always dark on light with
 * a quiet zone, whatever the theme, since that is what a camera reads.
 */
final class QrCodes {
    private QrCodes() {
    }

    /** One entry per module, quiet zone included; null when the content cannot be encoded. */
    static BitMatrix matrix(String content) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException | IllegalArgumentException tooLong) {
            return null;
        }
    }

    /** The code as an SVG document, one module per unit; null when it cannot be encoded. */
    static String svg(String content) {
        BitMatrix modules = matrix(content);
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
                + height + "\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }
}
