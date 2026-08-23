/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Every retry, timeout and frozen-page decision the dashboard's recovery clock makes, as pure
 * state with time passed in.
 *
 * <p>Extracted from {@code KioskActivity} because this was the one safety-critical subsystem with
 * no host tests, and every one of its historical bugs shipped and had to be found on hardware: the
 * shared cancellable handle that let {@code onPageStarted} cancel the very retry an error had just
 * scheduled (one 502 logged, zero reloads, measured 2026-08-19); {@code onPageFinished} treating a
 * proxy's 502 error body as a successful load; and the frozen-page detector whose retry path
 * started a new page generation without resetting the detector, so it reloaded a working panel
 * every fifteen minutes forever. Each of those is now a {@code require()} line in
 * {@code RecoveryPolicyTest}.
 *
 * <p>The design rules this class enforces, learned the hard way:
 *
 * <ul>
 *   <li><b>Callbacks record facts; only the supervisor acts.</b> The WebView's callbacks arrive in
 *   orders their names do not suggest (for an HTTP 502: {@code onReceivedHttpError}, then
 *   {@code onPageStarted}, then {@code onPageFinished}), so nothing a callback reports may cancel
 *   or issue a load. They call the record methods here; {@code KioskActivity}'s two-second tick
 *   calls {@link #checkHungLoad}/{@link #retryDue}/{@link #issueLoad} and is the only place a
 *   reload is ever born.
 *   <li><b>Failure is a timestamp, not a boolean.</b> A boolean cleared in {@code onPageStarted}
 *   was wiped by the very load that had just failed. A timestamp inside
 *   {@link #LOAD_FAILURE_WINDOW_MS} cannot be undone by a late-arriving callback.
 *   <li><b>There is no attempt limit.</b> A wall panel has nobody to press a button, and an outage
 *   that outlasts a limit would leave it dark until somebody noticed. Each failure re-arms the
 *   next attempt one backoff later, for as long as the outage lasts.
 * </ul>
 */
final class RecoveryPolicy {
    /**
     * How long to wait before retrying an unreachable dashboard. Ten seconds, chosen for an
     * ordinary home: the dashboard is unreachable mainly while Home Assistant restarts or updates,
     * which takes tens of seconds to minutes. Fast enough that the panel is back within seconds of
     * the server returning, slow enough not to hammer a machine already busy coming up.
     */
    static final long RELOAD_BACKOFF_MS = 10_000L;
    /**
     * How long a load may take before it is treated as hung and retried. Generous on purpose: a
     * Home Assistant dashboard measured 10-15 seconds to {@code onPageFinished} on the interim
     * MediaPad, so a tight timeout would reload a page that was merely slow. This only needs to
     * catch a load that is never going to complete, a server that accepts the connection and then
     * never answers, for which no callback ever fires.
     */
    static final long LOAD_TIMEOUT_MS = 60_000L;
    /**
     * The tighter bound for the single load renderer-death recovery issues. A renderer death is
     * known rather than suspected: the panel is showing nothing at all until this load lands, and
     * spending the full minute discovering it is not coming turned a 1.4-second rebuild into 66
     * seconds of blank panel, measured on the API 26 panel 2026-08-21. Twenty-five seconds rather
     * than less because that load is cold and 10-15 seconds is normal even warm. Applies to one
     * load only; every retry after it reverts to {@link #LOAD_TIMEOUT_MS}, so a dashboard that is
     * slow rather than dead can never be caught in a fast reload loop.
     */
    static final long RENDERER_DEATH_LOAD_TIMEOUT_MS = 25_000L;
    /**
     * How many consecutive unchanged fingerprints before the page is treated as frozen. Three
     * five-minute probes: fifteen minutes with not one visible change. A reload additionally
     * requires having observed this generation of the page change at least once
     * ({@link #sawPageChange}), because a legitimately static page is indistinguishable from a
     * frozen one by a single observation, and reloading a working panel forever is the worse
     * failure.
     */
    static final int FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER = 3;
    /**
     * How recently a failure must have been reported for the load that is finishing to count as
     * failed. Generous enough to absorb out-of-order callbacks, far shorter than any retry
     * interval.
     */
    static final long LOAD_FAILURE_WINDOW_MS = 5_000L;

    /** When the load in flight began, or 0 when nothing is loading. */
    private long loadStartedAtMs;
    /** When the next recovery attempt is due, or 0 when none is pending. */
    private long nextRetryAtMs;
    /** When the load in flight last reported a failure, or 0 for never. */
    private long loadFailedAtMs;
    /** Whether the load in flight is the one renderer-death recovery issued. */
    private boolean loadFollowsRendererDeath;
    /** The last content fingerprint the frozen-page probe read, or null before the first. */
    private String lastPageFingerprint;
    /** How many consecutive probes have read the identical fingerprint. */
    private int unchangedPageChecks;
    /** Whether this generation of the page has ever been observed to change. */
    private boolean sawPageChange;

    /** A load was just asked of the WebView. Clears any pending retry; this attempt is it. */
    void beginLoad(long nowMs) {
        loadStartedAtMs = nowMs;
        nextRetryAtMs = 0;
        loadFailedAtMs = 0;
        resetFingerprintTracking();
    }

    /** Forgets everything: no load in flight, no retry pending. The kiosk.stop / teardown state. */
    void reset() {
        loadStartedAtMs = 0;
        nextRetryAtMs = 0;
        loadFailedAtMs = 0;
        loadFollowsRendererDeath = false;
        resetFingerprintTracking();
    }

    /**
     * Records that the load in flight failed, and makes a retry due one backoff from now.
     * Deliberately does not decide anything itself: a failure can be reported more than once for a
     * single load, from callbacks in an order nobody controls, and recording a time is idempotent
     * where issuing a load is not.
     */
    void recordLoadFailure(long nowMs) {
        loadFailedAtMs = nowMs;
        nextRetryAtMs = nowMs + RELOAD_BACKOFF_MS;
    }

    /**
     * A document started arriving, often one nobody asked for: a JS location change, a login
     * redirect, a server 302. Re-arms the hung-load clock and forgets the previous document's
     * fingerprint history, because carrying {@code sawPageChange} across meant a static page
     * inherited permission to be declared frozen. Deliberately NOT {@link #beginLoad}: the retry
     * bookkeeping belongs to whoever issued the load, and clearing {@link #nextRetryAtMs} here
     * would cancel a pending recovery, which is the original shared-handle bug wearing a new face.
     */
    void pageStarted(long nowMs) {
        loadStartedAtMs = nowMs;
        resetFingerprintTracking();
    }

    /**
     * A document finished loading. Returns true when that counts as success; false when a failure
     * was reported within {@link #LOAD_FAILURE_WINDOW_MS}, because an error page is still a page:
     * for an HTTP 502 the proxy's error body loads perfectly well, and treating that as success
     * would cancel the pending retry and leave the panel on the error page indefinitely.
     */
    boolean pageFinished(long nowMs) {
        if (nowMs - loadFailedAtMs < LOAD_FAILURE_WINDOW_MS) {
            return false;
        }
        loadStartedAtMs = 0;
        nextRetryAtMs = 0;
        loadFollowsRendererDeath = false;
        return true;
    }

    /**
     * The load now in flight is the one renderer-death recovery issued, so it is held to
     * {@link #RENDERER_DEATH_LOAD_TIMEOUT_MS}. Called after the rebuild, never before: the rebuild
     * path runs {@link #reset()}, which would wipe this along with the rest.
     */
    void markLoadFollowsRendererDeath() {
        loadFollowsRendererDeath = true;
    }

    /** Nothing loading, nothing pending: the state in which the content probes may run. */
    boolean settled() {
        return loadStartedAtMs == 0 && nextRetryAtMs == 0;
    }

    /**
     * The hung-load check, run every supervisor tick: a load in flight past its bound, with no
     * retry already pending, becomes a retry due immediately. Returns the failure description for
     * the caller to record, or null when nothing is hung.
     */
    String checkHungLoad(long nowMs) {
        long loadTimeoutMs = loadFollowsRendererDeath
                ? RENDERER_DEATH_LOAD_TIMEOUT_MS : LOAD_TIMEOUT_MS;
        if (nextRetryAtMs == 0 && loadStartedAtMs != 0
                && nowMs - loadStartedAtMs > loadTimeoutMs) {
            loadFailedAtMs = nowMs;
            nextRetryAtMs = nowMs;
            return "load timed out after " + loadTimeoutMs + "ms";
        }
        return null;
    }

    /**
     * Whether a recovery attempt is due. Deliberately does not spend the attempt: the caller still
     * has cheap-to-skip preconditions (a network, a configured URL), and an attempt blocked by
     * them stays due so it goes out the moment they clear, which is what a router reboot used to
     * not look like from the panel.
     */
    boolean retryDue(long nowMs) {
        return nextRetryAtMs != 0 && nowMs >= nextRetryAtMs;
    }

    /**
     * Spends the due attempt: the caller is about to ask the WebView to load. This is the one
     * place a *pending* recovery becomes an issued load, so it is also where the tighter
     * post-renderer-death bound is spent, and where the fingerprint history is cleared, because a
     * frozen-page reload that leaves the fingerprints that authorised it in place fires again
     * every fifteen minutes forever, on a panel that is working.
     */
    void issueLoad(long nowMs) {
        loadFollowsRendererDeath = false;
        beginLoad(nowMs);
    }

    /**
     * One frozen-page probe reading. Returns true when the page should be declared frozen: this
     * generation has been seen to change at least once, and has now been identical for
     * {@link #FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER} consecutive probes. Until a first change is
     * observed, "unchanged" carries no information and nothing fires; a page that freezes before
     * its first observed change belongs to the load-failure and hung-load paths, and to the
     * nightly restart.
     */
    boolean recordFingerprint(String fingerprint) {
        if (fingerprint.equals(lastPageFingerprint)) {
            unchangedPageChecks++;
        } else {
            // Not on the first probe of a generation: there is nothing to have changed from, and
            // counting it would let a page observed only once look "live".
            if (lastPageFingerprint != null) {
                sawPageChange = true;
            }
            lastPageFingerprint = fingerprint;
            unchangedPageChecks = 0;
        }
        if (sawPageChange && unchangedPageChecks >= FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER) {
            unchangedPageChecks = 0;
            return true;
        }
        return false;
    }

    private void resetFingerprintTracking() {
        lastPageFingerprint = null;
        unchangedPageChecks = 0;
        sawPageChange = false;
    }
}
