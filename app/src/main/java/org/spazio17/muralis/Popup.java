/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.Map;

/**
 * A pop-up over the screen, {@code panel.notify} and {@code camera.show}: a message with an
 * optional title, and optionally an address shown in the card (a picture, a camera's stream or a
 * page, drawn by {@code CameraView}, so nothing it sends is stored), for a number of seconds or
 * until touched. Pure, so the arguments' checks are host-tested; the card
 * is drawn by {@code KioskActivity}.
 */
final class Popup {
    static final int MAX_MESSAGE = 500;
    static final int MAX_TITLE = 80;
    static final int DEFAULT_SECONDS = 30;
    static final int MAX_SECONDS = 300;

    /** What a pop-up shows and how. */
    static final class Request {
        final String title;
        final String message;
        final String url;
        final int seconds;
        final boolean wake;
        final boolean chime;
        /** What the card draws below the message: the address, or a camera or a group's cameras. */
        final java.util.List<Cameras.Tile> tiles;
        /** When it was asked for, on a clock a change of the date cannot move. */
        final long madeAtMs = clock.getAsLong();

        Request(String title, String message, String url, int seconds, boolean wake,
                boolean chime) {
            this(title, message, url, url == null || url.isEmpty()
                    ? java.util.Collections.<Cameras.Tile>emptyList()
                    : java.util.Collections.singletonList(new Cameras.Tile(title, url, null)),
                    seconds, wake, chime);
        }

        /** A camera's or a group's pop-up: the cameras drawn as one grid, with their sign-ins. */
        Request(String title, java.util.List<Cameras.Tile> tiles, int seconds, boolean wake,
                boolean chime) {
            this(title, "", tiles.isEmpty() ? "" : tiles.get(0).url, tiles, seconds, wake, chime);
        }

        private Request(String title, String message, String url,
                java.util.List<Cameras.Tile> tiles, int seconds, boolean wake, boolean chime) {
            this.tiles = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tiles));
            this.title = title;
            this.message = message;
            this.url = url;
            this.seconds = seconds;
            this.wake = wake;
            this.chime = chime;
        }
    }

    /** The pop-up waiting to be drawn, handed from the service to the activity. */
    static volatile Request pending;

    /**
     * How long a pop-up waits for the panel's screen: the settings open, or another app in front
     * on an ordinary install. Past it, it is dropped rather than shown out of date whenever
     * someone next opens the app.
     */
    static final long WAIT_MS = 60_000L;

    /** Whether {@code request} waited too long to be shown now. */
    static boolean stale(Request request) {
        return clock.getAsLong() - request.madeAtMs > WAIT_MS;
    }

    /**
     * The clock the wait is measured on. The app installs {@code SystemClock.elapsedRealtime},
     * which keeps counting while the device sleeps; {@code System.nanoTime} (the host tests')
     * stops in deep sleep, so a pop-up left waiting overnight on a dozing phone would still
     * count as a few seconds old in the morning.
     */
    private static volatile java.util.function.LongSupplier clock =
            () -> System.nanoTime() / 1_000_000L;

    static void useClock(java.util.function.LongSupplier realClock) {
        clock = realClock;
    }

    private Popup() {
    }

    /**
     * The request from a command's arguments, or a refusal in {@code problem[0]}: the message
     * in {@code value}, the rest by name.
     */
    static Request parse(String value, Map<String, String> extra, String[] problem) {
        // Line breaks kept, every other control character a space.
        String message = value == null ? "" : value.replace("\r", "")
                .replaceAll("[\\p{Cntrl}&&[^\n]]", " ").trim();
        String title = text(extra.get("title"));
        String url = extra.containsKey("url") ? extra.get("url").trim() : "";
        if (message.isEmpty() && url.isEmpty()) {
            problem[0] = "value must hold the message, or url an address to show";
            return null;
        }
        if (message.codePointCount(0, message.length()) > MAX_MESSAGE) {
            problem[0] = "the message is longer than " + MAX_MESSAGE + " characters";
            return null;
        }
        if (title.codePointCount(0, title.length()) > MAX_TITLE) {
            problem[0] = "the title is longer than " + MAX_TITLE + " characters";
            return null;
        }
        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            problem[0] = "url must start with http:// or https://";
            return null;
        }
        int seconds = DEFAULT_SECONDS;
        String typed = extra.get("seconds");
        if (typed != null && !typed.trim().isEmpty()) {
            if (!typed.trim().matches("\\d{1,3}") || Integer.parseInt(typed.trim()) > MAX_SECONDS) {
                problem[0] = "seconds must be a whole number from 0 to " + MAX_SECONDS
                        + ", 0 for until touched";
                return null;
            }
            seconds = Integer.parseInt(typed.trim());
        }
        Boolean wake = flag(extra.get("wake"), true);
        Boolean chime = flag(extra.get("chime"), false);
        if (wake == null || chime == null) {
            problem[0] = "wake and chime must be true or false";
            return null;
        }
        return new Request(title, message, url, seconds, wake, chime);
    }

    private static String text(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").trim();
    }

    private static Boolean flag(String value, boolean fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return KioskCommandDispatcher.parseEnabledFlag(value);
    }
}
