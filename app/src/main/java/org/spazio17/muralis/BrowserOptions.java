/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

/**
 * The page's browser options: the sign-in a page behind HTTP basic auth asks for,
 * the user agent, the text size, the page zoom, the dark look and the way back to the dashboard
 * after a while. Pure, so every validator both surfaces and the commands share is host-tested;
 * the WebView side is {@code KioskActivity}'s.
 */
final class BrowserOptions {
    static final int MAX_USER_AGENT = 512;
    static final int MAX_USER = 128;
    static final int DEFAULT_TEXT_SIZE = 100;
    static final int MIN_TEXT_SIZE = 50;
    static final int MAX_TEXT_SIZE = 300;
    /** 0 is Automatic: the page's own scale. */
    static final int AUTO_ZOOM = 0;
    static final int MIN_ZOOM = 25;
    static final int MAX_ZOOM = 400;
    static final int MAX_BACK_AFTER = 86_400;

    static final String USER_AGENT_RULE = "the user agent must be one line of at most "
            + MAX_USER_AGENT + " characters";
    static final String TEXT_SIZE_RULE = "the text size must be a whole number from "
            + MIN_TEXT_SIZE + " to " + MAX_TEXT_SIZE;
    static final String ZOOM_RULE = "the page zoom must be 0 for automatic or a whole number from "
            + MIN_ZOOM + " to " + MAX_ZOOM;
    static final String BACK_AFTER_RULE = "the time before going back must be a whole number of"
            + " seconds, 0 to " + MAX_BACK_AFTER;
    static final String PERMISSION_RULE = "allow it on the panel first: Android has not"
            + " given Muralis that permission";
    static final String DARK_RULE = "the page's dark look needs Android 10 or newer";
    static final String USER_RULE = "the sign-in name must be one line of at most " + MAX_USER
            + " characters, without a colon";

    /** What is stored, the password apart: it lives in SecretStore and is read only to sign in. */
    static final class Settings {
        final String userAgent;
        final int textSize;
        final int pageZoom;
        final boolean dark;
        final int backAfterSeconds;
        final String signInUser;
        /** Whether the page may use the camera and the microphone when it asks. */
        final boolean pageCamera;
        final boolean pageMicrophone;

        Settings(String userAgent, int textSize, int pageZoom, boolean dark,
                int backAfterSeconds, String signInUser) {
            this(userAgent, textSize, pageZoom, dark, backAfterSeconds, signInUser, false, false);
        }

        Settings(String userAgent, int textSize, int pageZoom, boolean dark,
                int backAfterSeconds, String signInUser, boolean pageCamera,
                boolean pageMicrophone) {
            this.userAgent = userAgent == null ? "" : userAgent;
            this.textSize = textSize;
            this.pageZoom = pageZoom;
            this.dark = dark;
            this.backAfterSeconds = backAfterSeconds;
            this.signInUser = signInUser == null ? "" : signInUser;
            this.pageCamera = pageCamera;
            this.pageMicrophone = pageMicrophone;
        }
    }

    private BrowserOptions() {
    }

    /**
     * Whether the WebView can be told: it reads the dark look from its theme's isLightTheme,
     * which Android has from 10 (API 29); older ones show every page in its light look.
     */
    static boolean darkLookPossible(int sdk) {
        return sdk >= 29;
    }

    /** Null when the text can be the user agent; empty means the WebView's own. */
    static String checkUserAgent(String value) {
        if (value == null || value.length() > MAX_USER_AGENT || hasControl(value)) {
            return USER_AGENT_RULE;
        }
        return null;
    }

    /** Null when the name can sign in; empty means no sign-in. A colon cannot be in a basic-auth name. */
    static String checkSignInUser(String value) {
        if (value == null || value.length() > MAX_USER || hasControl(value)
                || value.indexOf(':') >= 0) {
            return USER_RULE;
        }
        return null;
    }

    static Integer parseTextSize(String value) {
        Integer number = wholeNumber(value);
        return number != null && number >= MIN_TEXT_SIZE && number <= MAX_TEXT_SIZE
                ? number : null;
    }

    /** "auto", empty or 0 is Automatic (0); otherwise 25 to 400. */
    static Integer parsePageZoom(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("auto")) {
            return AUTO_ZOOM;
        }
        Integer number = wholeNumber(trimmed);
        if (number == null) {
            return null;
        }
        return number == AUTO_ZOOM || (number >= MIN_ZOOM && number <= MAX_ZOOM) ? number : null;
    }

    static Integer parseBackAfter(String value) {
        Integer number = wholeNumber(value);
        return number != null && number >= 0 && number <= MAX_BACK_AFTER ? number : null;
    }

    /** The user agent the page sees: the stored one whole, or the WebView's with Muralis after it. */
    static String userAgent(String stored, String webViewDefault, String version) {
        if (stored != null && !stored.trim().isEmpty()) {
            return stored.trim();
        }
        return webViewDefault + " Muralis/" + version;
    }

    /**
     * Whether a sign-in may be given to this host: the dashboard address's own host only, so a
     * page that pulls in another site's frame never receives the panel's password.
     */
    static boolean signInHost(String dashboardUrl, String host) {
        if (dashboardUrl == null || host == null || host.isEmpty()) {
            return false;
        }
        try {
            String own = new java.net.URI(dashboardUrl.trim()).getHost();
            return own != null && own.equalsIgnoreCase(host);
        } catch (java.net.URISyntaxException unreadable) {
            return false;
        }
    }

    /**
     * Whether an address differs from the one the dashboard settled on, ignoring a fragment and
     * a trailing slash, which a single-page dashboard changes on its own as it is used.
     */
    static boolean awayFrom(String current, String dashboard) {
        if (current == null || current.isEmpty() || dashboard == null || dashboard.isEmpty()) {
            return false;
        }
        return !comparable(current).equals(comparable(dashboard));
    }

    private static String comparable(String url) {
        String bare = url.trim();
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        while (bare.endsWith("/")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        return bare;
    }

    /**
     * Whether to load the dashboard again: the page left it after a touch (or an open-once
     * address took its place), nobody has touched the glass for the time set, and the time is
     * not 0.
     */
    static boolean timeToGoBack(boolean away, long idleMs, int backAfterSeconds) {
        return away && backAfterSeconds > 0 && idleMs >= backAfterSeconds * 1000L;
    }

    private static Integer wholeNumber(String value) {
        if (value == null || !value.trim().matches("\\d{1,6}")) {
            return null;
        }
        return Integer.parseInt(value.trim());
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
