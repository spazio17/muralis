/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * When the screensaver starts, when it hands over to Display off, and what each of its settings
 * is allowed to be.
 *
 * <p>The screensaver is the quieter thing a panel shows after a chosen time without a touch: the
 * page dimmed to a floor, the black film, or another web page (a photo frame's slideshow, a clock,
 * a picture dashboard). After a second chosen time it turns the display off exactly the way
 * "Display off" does, so {@link DisplayOffPolicy} decides sleep or film there as everywhere. A
 * touch, a remote wake or the presence blueprint bring the page back. Designed 2026-09-09.
 *
 * <pre>
 * dashboard --idle A--> screensaver --idle B--> display off
 *     ^                      |   ^                    |
 *     | touch / remote wake / presence                |
 *     +----------------------+   +--- wake ----------+
 * </pre>
 *
 * <p>What a wake from display off shows is the user's choice ({@link #WAKE_SCREENSAVER} or
 * {@link #WAKE_DASHBOARD}) for the web page and the dimmed page, a glance before the touch that
 * brings the page; the film has nothing to glance at, so a wake from it is always the page. Every
 * number is a field the user sets; the values here
 * are only what a fresh panel starts with. The mode starts {@link #OFF}: a panel that began to
 * darken by itself after an update would be read as broken.
 *
 * <p>Pure, with the clock passed in, so the timings are host-tested rather than waited for beside
 * a tablet with a stopwatch.
 */
final class ScreensaverPolicy {
    /** No screensaver. */
    static final String OFF = "off";
    /** The page itself, at a brightness floor the user sets. */
    static final String DIM = "dim";
    /** The black film at minimum brightness; the same view Display off uses. */
    static final String FILM = "film";
    /** Another web page, shown over the dashboard, which stays loaded underneath. */
    static final String URL = "url";
    /** Pictures from a {@link PictureSources} source, full screen, with the credit line. */
    static final String PICTURES = "pictures";

    /** How one picture gives way to the next. */
    static final String TRANSITION_NONE = "none";
    static final String TRANSITION_FADE = "fade";
    static final String TRANSITION_SLIDE = "slide";

    /** The corner the credit line sits in. */
    static final String CORNER_BOTTOM_LEFT = "bottom_left";
    static final String CORNER_BOTTOM_RIGHT = "bottom_right";
    static final String CORNER_TOP_LEFT = "top_left";
    static final String CORNER_TOP_RIGHT = "top_right";

    /** After a wake from display off, the screensaver first: a glance, then a touch for the page. */
    static final String WAKE_SCREENSAVER = "screensaver";
    /** After a wake from display off, straight to the page. */
    static final String WAKE_DASHBOARD = "dashboard";

    static final int DEFAULT_IDLE_SECONDS = 120;
    static final int DEFAULT_OFF_SECONDS = 900;
    static final int DEFAULT_DIM_PERCENT = 20;
    static final int DEFAULT_PICTURE_SECONDS = 20;
    /** A day. Any longer means "never", which 0 already says. */
    static final int MAX_SECONDS = 86_400;
    /** The range rules as sentences: every surface's refusal names the rule, not the value. */
    static final String SECONDS_RULE = "must be a whole number of seconds, 0 to " + MAX_SECONDS;
    static final String DIM_RULE = "must be a whole number from 1 to 100";
    static final String PICTURE_SECONDS_RULE = "must be a whole number of seconds, 1 to " + MAX_SECONDS;

    /** Where the panel is in the diagram above. {@code DARK} is display off, by either method. */
    enum Stage { DASHBOARD, SCREENSAVER, DARK }

    /** What the clock's tick has to do now. */
    enum Step { NONE, START, DISPLAY_OFF }

    /** The stored settings, read once per decision so a tick never sees half of an edit. */
    static final class Settings {
        final String mode;
        /** Seconds without a touch before the screensaver; 0 means only when asked for. */
        final int idleSeconds;
        /** Seconds of screensaver before display off; 0 means never. */
        final int offSeconds;
        final String url;
        final int dimPercent;
        final String onWake;
        /** The pictures mode: where from, how long each, how they change, in what order. */
        final String source;
        final int pictureSeconds;
        final String transition;
        final boolean shuffle;
        /** One picture per screensaver: chosen at the start, kept until the page returns. */
        final boolean onePerCycle;
        /** The credit line; only the local folder may switch it off, the sources require it. */
        final boolean credit;
        final String creditCorner;

        Settings(String mode, int idleSeconds, int offSeconds, String url, int dimPercent,
                String onWake) {
            this(mode, idleSeconds, offSeconds, url, dimPercent, onWake, PictureSources.LOCAL,
                    DEFAULT_PICTURE_SECONDS, TRANSITION_FADE, false, false, true,
                    CORNER_BOTTOM_LEFT);
        }

        Settings(String mode, int idleSeconds, int offSeconds, String url, int dimPercent,
                String onWake, String source, int pictureSeconds, String transition,
                boolean shuffle, boolean onePerCycle, boolean credit, String creditCorner) {
            this.mode = mode;
            this.idleSeconds = idleSeconds;
            this.offSeconds = offSeconds;
            this.url = url == null ? "" : url;
            this.dimPercent = dimPercent;
            this.onWake = onWake;
            this.source = source;
            this.pictureSeconds = pictureSeconds;
            this.transition = transition;
            this.shuffle = shuffle;
            this.onePerCycle = onePerCycle;
            this.credit = credit;
            this.creditCorner = creditCorner;
        }

        /** The credit line as shown: the online sources require it whatever the switch says. */
        boolean creditShown() {
            return credit || !PictureSources.LOCAL.equals(source);
        }

        boolean enabled() {
            return !OFF.equals(mode);
        }
    }

    private ScreensaverPolicy() {
    }

    static boolean isMode(String value) {
        return OFF.equals(value) || DIM.equals(value) || FILM.equals(value) || URL.equals(value)
                || PICTURES.equals(value);
    }

    static boolean isTransition(String value) {
        return TRANSITION_NONE.equals(value) || TRANSITION_FADE.equals(value)
                || TRANSITION_SLIDE.equals(value);
    }

    static boolean isCorner(String value) {
        return CORNER_BOTTOM_LEFT.equals(value) || CORNER_BOTTOM_RIGHT.equals(value)
                || CORNER_TOP_LEFT.equals(value) || CORNER_TOP_RIGHT.equals(value);
    }

    /** Seconds per picture: at least one, or a slideshow becomes a flicker. */
    static Integer parsePictureSeconds(String text) {
        return parseWhole(text, 1, MAX_SECONDS);
    }

    static boolean isOnWake(String value) {
        return WAKE_SCREENSAVER.equals(value) || WAKE_DASHBOARD.equals(value);
    }

    /**
     * A time field as typed: a whole number of seconds from 0 to {@link #MAX_SECONDS}, or null
     * when it is not one. Digits only, so "2 min" is refused rather than read as 2 seconds.
     */
    static Integer parseSeconds(String text) {
        return parseWhole(text, 0, MAX_SECONDS);
    }

    /** The dim floor as typed: 1 to 100. Not 0, which is the film wearing a dim's name. */
    static Integer parseDimPercent(String text) {
        return parseWhole(text, 1, 100);
    }

    private static Integer parseWhole(String text, int min, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 6) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < '0' || trimmed.charAt(i) > '9') {
                return null;
            }
        }
        int value = Integer.parseInt(trimmed);
        return value < min || value > max ? null : value;
    }

    /**
     * Why the chosen mode cannot run with the other settings, or null when it can. The web-page
     * mode with no address is the one case: the mode is stored anyway, so the operator can choose
     * it and then type the address in either order, and until both are there the panel says so
     * on every surface instead of starting a screensaver that shows nothing.
     */
    static String modeProblem(String mode, String url) {
        if (URL.equals(mode) && (url == null || url.trim().isEmpty())) {
            return "the web-page screensaver needs a page address";
        }
        return null;
    }

    /** Whether the screensaver can run at all, by mode and by the setting the mode needs. */
    static boolean runnable(Settings settings) {
        return settings.enabled() && modeProblem(settings.mode, settings.url) == null;
    }

    /**
     * What the clock has to do now.
     *
     * @param stage    where the panel is
     * @param blocked  a reason nothing may start: the operator is on a settings screen, the kiosk
     *                 is stopped, the activity is not in front, or the panel is already dark
     * @param sinceMs  monotonic time the current stage began: the last touch on the dashboard,
     *                 or the moment the screensaver came on
     * @param nowMs    monotonic time now
     */
    static Step next(Settings settings, Stage stage, boolean blocked, long sinceMs, long nowMs) {
        if (blocked || !runnable(settings)) {
            return Step.NONE;
        }
        long idle = nowMs - sinceMs;
        switch (stage) {
            case DASHBOARD:
                return settings.idleSeconds > 0 && idle >= settings.idleSeconds * 1000L
                        ? Step.START : Step.NONE;
            case SCREENSAVER:
                return settings.offSeconds > 0 && idle >= settings.offSeconds * 1000L
                        ? Step.DISPLAY_OFF : Step.NONE;
            case DARK:
            default:
                return Step.NONE;
        }
    }

    /**
     * Whether a wake from display off shows the screensaver rather than the page. The web page
     * and the dimmed page are something to glance at; a wake to the black film would be no wake
     * at all, so the choice does not apply there (the surfaces grey it out and say so).
     */
    static boolean screensaverFirst(Settings settings) {
        return runnable(settings) && wakeChoiceApplies(settings.mode)
                && WAKE_SCREENSAVER.equals(settings.onWake);
    }

    /** Whether the on-wake choice means anything in this mode; false for the film and for off. */
    static boolean wakeChoiceApplies(String mode) {
        return URL.equals(mode) || DIM.equals(mode) || PICTURES.equals(mode);
    }

    static String modeName(String mode) {
        switch (mode == null ? "" : mode) {
            case DIM:
                return "Dimmed page";
            case FILM:
                return "Black film";
            case URL:
                return "Web page";
            case PICTURES:
                return "Pictures";
            case OFF:
            default:
                return "Off";
        }
    }

    /** "45 s", "2 min", "1 h 30 min": what a person reads, for a value stored in seconds. */
    static String describeDuration(int seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int rest = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append(" h");
        }
        if (minutes > 0) {
            text.append(text.length() > 0 ? " " : "").append(minutes).append(" min");
        }
        if (rest > 0) {
            text.append(text.length() > 0 ? " " : "").append(rest).append(" s");
        }
        return text.toString();
    }

    /**
     * The one line every surface shows under the controls: what is set, in words, and whether it
     * is on the glass right now. The same sentence on the tablet, in the web admin and in the
     * status document, so nobody has to translate between three descriptions of one rule.
     */
    static String describe(Settings settings, boolean active) {
        if (!settings.enabled()) {
            return "Screensaver off.";
        }
        String problem = modeProblem(settings.mode, settings.url);
        if (problem != null) {
            return modeName(settings.mode) + ": " + problem + ".";
        }
        StringBuilder text = new StringBuilder(modeName(settings.mode));
        if (PICTURES.equals(settings.mode)) {
            text.append(" from ").append(PictureSources.sourceName(settings.source));
        }
        if (settings.idleSeconds > 0) {
            text.append(" after ").append(describeDuration(settings.idleSeconds))
                    .append(" without a touch");
        } else {
            text.append(", only when asked for");
        }
        if (settings.offSeconds > 0) {
            text.append(", display off ").append(describeDuration(settings.offSeconds))
                    .append(" later.");
        } else {
            text.append(", the display stays on.");
        }
        if (active) {
            text.append(" Showing now.");
        }
        return text.toString();
    }
}
