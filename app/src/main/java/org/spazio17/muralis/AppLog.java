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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The tail of this app's own log, read back from logcat.
 *
 * <p>Since Android 4.1 an app may read the log lines of its own process and no others, and it
 * needs no permission for that: {@code logcat --pid} is filtered by the log daemon, so what comes
 * back is exactly what a developer would see with {@code adb logcat} narrowed to Muralis,
 * including the WebView's page errors that {@code KioskActivity} sends there. Fully Kiosk Browser
 * offers the same through its {@code cmd=logcat}, which is what this was asked to match (Juri,
 * 2026-09-25), only it is a plain page there and a block with a level filter, a search and colours
 * here, the way Home Assistant's own log page and every logcat reader do it.
 *
 * <p>What it is for: a panel on a wall that is doing something odd, read from the sofa. Nothing is
 * stored or sent anywhere; every call reads the daemon's ring buffer afresh, and logd prunes that
 * buffer as it likes, so a line missing here is not evidence of anything (see the project notes).
 * Best effort throughout: a device that refuses the command answers with nothing.
 */
final class AppLog {
    /** Lines the web page shows and the endpoint serves by default. */
    static final int LINES = 200;

    /**
     * Lines asked of logcat before the level filter, so that "errors only" still reaches back
     * through the chatter an OEM's frameworks write under this pid (a Huawei prints a line a
     * second): the filter keeps the last {@link #LINES} of what passes.
     */
    private static final int RAW_LINES = 2000;

    /** Reading stops here even if logcat keeps talking: a runaway process must not fill memory. */
    private static final int MAX_BYTES = 512 * 1024;

    /** One logcat line, {@code -v time} format: "MM-DD HH:MM:SS.mmm L/Tag( pid): message". */
    static final class Entry {
        /** "MM-DD HH:MM:SS.mmm", what a clear-from-here marker compares against. */
        final String time;
        /** V, D, I, W, E or F. */
        final char level;
        final String tag;
        final String message;

        Entry(String time, char level, String tag, String message) {
            this.time = time;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        /** The line the two surfaces and curl get: the date and the pid dropped. */
        String line() {
            return time.length() >= 14 ? time.substring(6, 14) + " " + level + "/" + tag + ": "
                    + message : level + "/" + tag + ": " + message;
        }
    }

    private AppLog() {
    }

    /** The order the level filter walks: everything at or above the asked level passes. */
    static int rank(char level) {
        switch (level) {
            case 'V': return 0;
            case 'D': return 1;
            case 'I': return 2;
            case 'W': return 3;
            case 'E': return 4;
            case 'F': return 5;
            default: return 2;
        }
    }

    /** V, D, I, W, E or F from whatever was typed, or V for nothing usable. */
    static char levelOf(String asked) {
        if (asked == null || asked.isEmpty()) {
            return 'V';
        }
        char first = Character.toUpperCase(asked.charAt(0));
        return "VDIWEF".indexOf(first) < 0 ? 'V' : first;
    }

    /**
     * Whether a line is Muralis's own rather than a framework's under its pid: every tag of this
     * app starts with "Muralis" or "Pro" (ProBilling, ProEntitlement), and the kernel's audit
     * lines carry the thread name, "MuralisTelemetr", which is about this app as well. An OEM's
     * frameworks log a line a second under the same pid on a Huawei (ZeroHung, HiTouch), which
     * is why "own" is the first chip and the default, the way Android Studio's Logcat opens on
     * {@code package:mine}.
     */
    static boolean own(Entry entry) {
        return entry.tag.startsWith("Muralis") || entry.tag.startsWith("Pro");
    }

    /**
     * The last {@code lines} entries this process logged at {@code minLevel} or above, only its
     * own when {@code ownOnly}, oldest first, or an empty list when logcat cannot be read.
     */
    static List<Entry> tail(int lines, char minLevel, boolean ownOnly) {
        Process logcat;
        try {
            logcat = new ProcessBuilder("logcat", "-d", "-v", "time",
                    "--pid=" + android.os.Process.myPid(), "-t", String.valueOf(RAW_LINES))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException | RuntimeException unavailable) {
            return new ArrayList<>();
        }
        int floor = rank(minLevel);
        Deque<Entry> kept = new ArrayDeque<>(lines);
        int read = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                logcat.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && read < MAX_BYTES) {
                read += line.length() + 1;
                Entry entry = parse(line);
                if (entry == null || rank(entry.level) < floor || (ownOnly && !own(entry))) {
                    continue;
                }
                if (kept.size() == lines) {
                    kept.removeFirst();
                }
                kept.addLast(entry);
            }
        } catch (IOException interrupted) {
            // Whatever was read is still the tail as far as it goes.
        } finally {
            logcat.destroy();
        }
        return new ArrayList<>(kept);
    }

    /** The entries whose line contains {@code query}, case-insensitively; all of them for none. */
    static List<Entry> matching(List<Entry> entries, String query) {
        if (query == null || query.trim().isEmpty()) {
            return entries;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.line().toLowerCase(Locale.ROOT).contains(needle)) {
                found.add(entry);
            }
        }
        return found;
    }

    /** The plain-text form, one line each, the shape {@code /api/log} answers with. */
    static String text(List<Entry> entries) {
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) {
            text.append(entry.line()).append('\n');
        }
        return text.toString();
    }

    /**
     * One logcat line into its parts, or null for a line that is not one: logcat's own
     * "--------- beginning of main" header, and a continuation a badly behaved logger emits.
     */
    static Entry parse(String line) {
        // "09-25 23:39:04.231 I/Tag( 1234): message"; the tag may be empty and the pid padded.
        if (line.length() < 22 || line.charAt(2) != '-' || line.charAt(5) != ' '
                || line.charAt(18) != ' ' || line.charAt(20) != '/') {
            return null;
        }
        int paren = line.indexOf("): ", 21);
        if (paren < 0) {
            return null;
        }
        String tag = line.substring(21, line.lastIndexOf('(', paren)).trim();
        return new Entry(line.substring(0, 18), line.charAt(19),
                tag.isEmpty() ? "-" : tag, line.substring(paren + 3));
    }
}
