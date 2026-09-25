/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The tail of this app's own log, read back from logcat.
 *
 * <p>Since Android 4.1 an app may read the log lines of its own process and no others, and it
 * needs no permission for that: {@code logcat --pid} is filtered by the kernel-side log daemon,
 * so what comes back is exactly what a developer would see with {@code adb logcat} narrowed to
 * Muralis, including the WebView's page errors that {@code KioskActivity} sends there. Fully
 * Kiosk Browser offers the same through its {@code cmd=logcat}, which is what this was asked to
 * match (Juri, 2026-09-25), only it is a plain page there and a live block here.
 *
 * <p>What it is for: a panel on a wall that is doing something odd, read from the sofa. Nothing
 * is stored or sent anywhere; every call reads the daemon's ring buffer afresh, and logd prunes
 * that buffer as it likes, so a line missing here is not evidence of anything (see the project
 * notes). Best effort throughout: a device that refuses the command answers with nothing.
 */
final class AppLog {
    /** Lines the two surfaces show. More than fits a screen, fewer than a phone struggles with. */
    static final int LINES = 200;

    /** Reading stops here even if logcat keeps talking: a runaway process must not fill memory. */
    private static final int MAX_BYTES = 256 * 1024;

    private AppLog() {
    }

    /**
     * The last {@code lines} lines this process logged, oldest first, each ending in a newline,
     * or an empty string when logcat cannot be read.
     */
    static String tail(int lines) {
        Process logcat;
        try {
            logcat = new ProcessBuilder("logcat", "-d", "-v", "time",
                    "--pid=" + android.os.Process.myPid(), "-t", String.valueOf(lines))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException | RuntimeException unavailable) {
            return "";
        }
        Deque<String> kept = new ArrayDeque<>(lines);
        int read = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                logcat.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && read < MAX_BYTES) {
                read += line.length() + 1;
                // logcat's own header, printed before the first line of every buffer.
                if (line.startsWith("--------- beginning of ")) {
                    continue;
                }
                if (kept.size() == lines) {
                    kept.removeFirst();
                }
                kept.addLast(line);
            }
        } catch (IOException interrupted) {
            // Whatever was read is still the tail as far as it goes.
        } finally {
            logcat.destroy();
        }
        StringBuilder text = new StringBuilder();
        for (String line : kept) {
            text.append(line).append('\n');
        }
        return text.toString();
    }
}
