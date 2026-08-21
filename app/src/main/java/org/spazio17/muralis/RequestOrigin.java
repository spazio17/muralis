/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Decides whether a state-changing HTTP request was sent by this server's own page or by another
 * site on the operator's behalf.
 *
 * <p>The attack this exists to stop needs no credentials of its own. The web admin authenticates
 * with HTTP Basic, so once the operator has signed in, their browser attaches those credentials to
 * <em>any</em> request to this origin, including one a form or an image tag on an unrelated page
 * caused. Before this, a page the operator merely visited could change the admin password without
 * knowing it, or repoint the dashboard.
 *
 * <p>Two headers, each checked only when present, which is what keeps ordinary automation working.
 * {@code Sec-Fetch-Site} is attached by the browser and cannot be set from script, so it is the
 * stronger signal and it covers form posts, {@code fetch} and subresource loads alike.
 * {@code Origin} is the older and more widely supported one, compared against the {@code Host} the
 * request was addressed to. A caller that sends neither, {@code curl} or a Home Assistant
 * automation, is not a browser, cannot be a confused deputy, and passes.
 *
 * <p>Deliberately not a CSRF token. A token has to live somewhere, and with Basic auth there is no
 * session to hang one on. A per-boot token would break every scripted caller and buy nothing these
 * two headers do not already give. Revisit only if cookie sessions ever arrive.
 *
 * <p>Free of Android imports on purpose, so {@code scripts/test-host.sh} can exercise it. This is a
 * security decision whose interesting cases are precisely the ones nobody reproduces by hand.
 */
final class RequestOrigin {

    private RequestOrigin() {
    }

    /**
     * @param secFetchSite the {@code Sec-Fetch-Site} header, or null when absent
     * @param origin the {@code Origin} header, or null when absent
     * @param host the {@code Host} the request was addressed to
     * @return a short reason to refuse, or null when the request may proceed
     */
    static String crossSiteRefusal(String secFetchSite, String origin, String host) {
        if (secFetchSite != null) {
            String site = secFetchSite.trim();
            // "none" is the operator typing the address or using a bookmark. "same-origin" is this
            // page's own form or fetch. Everything else, "cross-site" and "same-site" alike, means
            // another document initiated it, and a wall panel has no sibling sites to trust.
            if (!site.equalsIgnoreCase("same-origin") && !site.equalsIgnoreCase("none")) {
                return "Sec-Fetch-Site: " + site;
            }
        }
        if (origin == null || origin.isEmpty()) {
            // Not a browser, or a browser that omits Origin on a same-origin POST, which Safari has
            // historically done. Sec-Fetch-Site above is the check that covers the browser case.
            return null;
        }
        // Refused rather than skipped. An opaque origin is what a sandboxed iframe, a data: URL or a
        // file:// page sends, which are attacker-controlled contexts; nothing legitimate on this
        // panel is served from one.
        if (origin.equalsIgnoreCase("null")) {
            return "Origin: null";
        }
        int schemeEnd = origin.indexOf("://");
        String authority = schemeEnd < 0 ? origin : origin.substring(schemeEnd + 3);
        if (!authority.trim().equalsIgnoreCase(host == null ? "" : host.trim())) {
            return "Origin does not match Host";
        }
        return null;
    }
}
