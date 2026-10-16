/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.Map;

/**
 * A pop-up over the screen (block 6), {@code panel.notify}: a message with an optional title,
 * and optionally an address shown in the card (a picture, a camera's stream or a page), for a
 * number of seconds or until touched. Pure, so the arguments' checks are host-tested; the card
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

        Request(String title, String message, String url, int seconds, boolean wake,
                boolean chime) {
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
