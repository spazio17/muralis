/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/** Host tests for {@link RequestOrigin}: which state-changing requests are refused, and why. */
public final class RequestOriginTest {

    public static void main(String[] args) {
        automationPasses();
        ownPagePasses();
        crossSiteIsRefused();
        mismatchedOriginIsRefused();
        opaqueOriginIsRefused();
        portAndSchemeAreHandled();
        System.out.println("RequestOriginTest passed");
    }

    /** curl and Home Assistant send neither header. They must keep working. */
    private static void automationPasses() {
        expectAllowed(null, null, "10.0.14.51:8080");
        // A body-less POST from a script that sets Host only.
        expectAllowed(null, "", "panel.local:8080");
    }

    private static void ownPagePasses() {
        expectAllowed("same-origin", "http://10.0.14.51:8080", "10.0.14.51:8080");
        // Typed address or bookmark: no initiating document at all.
        expectAllowed("none", null, "10.0.14.51:8080");
        // Safari has historically omitted Origin on a same-origin POST; Sec-Fetch-Site carries it.
        expectAllowed("same-origin", null, "10.0.14.51:8080");
    }

    private static void crossSiteIsRefused() {
        expectRefused("cross-site", null, "10.0.14.51:8080");
        // same-site is refused too: a wall panel has no sibling sites worth trusting.
        expectRefused("same-site", null, "10.0.14.51:8080");
        // Case is not guaranteed by the wire format.
        expectRefused("Cross-Site", null, "10.0.14.51:8080");
    }

    private static void mismatchedOriginIsRefused() {
        // The attack: an attacker's page posting to the panel with the operator's cached credentials.
        expectRefused(null, "http://evil.example", "10.0.14.51:8080");
        // Right host, wrong port, is still a different origin.
        expectRefused(null, "http://10.0.14.51:9999", "10.0.14.51:8080");
        // A prefix must not be enough.
        expectRefused(null, "http://10.0.14.51:8080.evil.example", "10.0.14.51:8080");
    }

    private static void opaqueOriginIsRefused() {
        expectRefused(null, "null", "10.0.14.51:8080");
        expectRefused(null, "NULL", "10.0.14.51:8080");
    }

    private static void portAndSchemeAreHandled() {
        // https in front of a reverse proxy that forwards the same authority.
        expectAllowed(null, "https://panel.local", "panel.local");
        // Host casing differs from Origin casing.
        expectAllowed(null, "http://Panel.Local:8080", "panel.local:8080");
        // Surrounding whitespace is a wire artefact, not a mismatch.
        expectAllowed(null, "http://panel.local:8080", " panel.local:8080 ");
    }

    private static void expectAllowed(String site, String origin, String host) {
        String refusal = RequestOrigin.crossSiteRefusal(site, origin, host);
        if (refusal != null) {
            throw new AssertionError("expected allowed for site=" + site + " origin=" + origin
                    + " host=" + host + " but got: " + refusal);
        }
    }

    private static void expectRefused(String site, String origin, String host) {
        String refusal = RequestOrigin.crossSiteRefusal(site, origin, host);
        if (refusal == null) {
            throw new AssertionError("expected refusal for site=" + site + " origin=" + origin
                    + " host=" + host);
        }
    }
}
