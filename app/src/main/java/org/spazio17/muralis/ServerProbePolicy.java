/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Decides when a reachability probe of the dashboard's server means the page needs reloading.
 *
 * <p>This covers the failure the other recovery paths cannot see: the page loaded fine, and then
 * the server restarted underneath it. No main-frame request happens, so
 * {@code onReceivedError}/{@code onReceivedHttpError} stay silent; no load is in flight, so the
 * hung-load timeout is idle; and after a Home Assistant restart the frontend reconnects its own
 * websocket and most of the page recovers, so the frozen-page probe correctly sees a live page.
 * What is left behind is a card here and there stuck on an error tile, observed on the panel after
 * an ordinary Home Assistant restart, and nothing ever reloads it.
 *
 * <p>So the activity probes the dashboard URL while the page is settled, and this class turns the
 * stream of probe verdicts into at most one reload decision per outage:
 *
 * <ul>
 *   <li><b>The reload fires on recovery, never during the outage.</b> Reloading while the server is
 *   down replaces a page that still looks right, and mostly still is, with a Chromium error page,
 *   which is strictly worse than doing nothing. The wall keeps showing the last good page for the
 *   whole outage and gets a fresh one a few seconds after it ends.
 *   <li><b>One failed probe is enough to count as an outage.</b> A failure here is a refused
 *   connection, a timeout, or a 5xx; a healthy-but-busy server still answers. The cost of a false
 *   positive is a single warm reload, and demanding consecutive failures would miss the short
 *   restarts this exists to catch.
 *   <li><b>Any answered request counts as up, including 4xx.</b> A 401 or a 404 proves the server
 *   answered; whether the page then loads is the load path's business, not this one's. Only 5xx is
 *   an outage, because that is what a reverse proxy says for as long as its backend is restarting.
 * </ul>
 *
 * <p>Pure logic with no Android imports, so every branch is covered by host tests instead of by
 * restarting Home Assistant next to the tablet.
 */
final class ServerProbePolicy {
    /** Whether an outage has been observed since the last reset or recovery. */
    private boolean serverWasUnreachable;

    /**
     * Whether an HTTP status from the probe means the server is up. Anything answered is up,
     * including auth failures and redirects; only a 5xx means the backend is gone.
     */
    static boolean statusMeansServerUp(int httpStatus) {
        return httpStatus < 500;
    }

    /**
     * Records one probe verdict. Returns true exactly when the server has just come back from an
     * observed outage, which is the one moment a reload is due.
     */
    boolean recordResult(boolean serverUp) {
        if (!serverUp) {
            serverWasUnreachable = true;
            return false;
        }
        boolean recovered = serverWasUnreachable;
        serverWasUnreachable = false;
        return recovered;
    }

    /**
     * Forgets any observed outage. Called whenever a load is issued: that load's outcome belongs to
     * the ordinary load-failure path, and carrying the outage across would make the first probe
     * after a successful recovery reload demand a second reload.
     */
    void reset() {
        serverWasUnreachable = false;
    }
}
