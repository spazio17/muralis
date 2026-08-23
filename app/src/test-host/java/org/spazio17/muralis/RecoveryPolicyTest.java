/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Each historical recovery bug that shipped and had to be found on hardware is a test here, so it
 * can never ship a second time. Time is milliseconds on an arbitrary monotonic clock.
 */
public final class RecoveryPolicyTest {
    private static final long SECOND = 1_000L;
    private static final long START = 1_000_000L;

    public static void main(String[] args) {
        testHappyLoad();
        testThe502ThatCancelledItsOwnRetry();
        testFailureThenRetryAfterBackoff();
        testBlockedRetryStaysDue();
        testHungLoadIsRetried();
        testRendererDeathBoundAppliesOnce();
        testErrorPageIsNotASuccess();
        testFrozenPageNeedsAnObservedChange();
        testFrozenReloadClearsItsOwnAuthorisation();
        testPageStartedMustNotCancelARetry();
        System.out.println("RecoveryPolicyTest passed");
    }

    private static void testHappyLoad() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        require(!policy.settled(), "a load in flight is not settled");
        policy.pageStarted(START + SECOND);
        require(policy.pageFinished(START + 12 * SECOND), "a clean load should count as success");
        require(policy.settled(), "a finished load should settle");
        require(!policy.retryDue(START + 60 * SECOND), "nothing failed, nothing to retry");
    }

    /**
     * Measured 2026-08-19: for an HTTP 502 the WebView reports onReceivedHttpError, then
     * onPageStarted, then onPageFinished, because the proxy's error body loaded perfectly well.
     * The old design let that sequence cancel the retry the error had just scheduled: one 502
     * logged, zero reloads, a panel that never came back.
     */
    private static void testThe502ThatCancelledItsOwnRetry() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.recordLoadFailure(START + SECOND);          // onReceivedHttpError
        policy.pageStarted(START + SECOND + 100);          // the error page starts arriving
        require(!policy.pageFinished(START + SECOND + 200), // and finishes, quickly
                "an error page inside the failure window was counted as success");
        require(policy.retryDue(START + SECOND + RecoveryPolicy.RELOAD_BACKOFF_MS),
                "the 502 sequence cancelled its own retry; the 2026-08-19 bug is back");
    }

    private static void testFailureThenRetryAfterBackoff() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.recordLoadFailure(START + SECOND);
        require(!policy.retryDue(START + SECOND + RecoveryPolicy.RELOAD_BACKOFF_MS - 1),
                "retried before the backoff elapsed");
        require(policy.retryDue(START + SECOND + RecoveryPolicy.RELOAD_BACKOFF_MS),
                "the retry never came due");
        policy.issueLoad(START + 15 * SECOND);
        require(!policy.retryDue(START + 15 * SECOND), "issuing the load must spend the attempt");
        // The retry fails too: the next one is re-armed. There is no attempt limit, on purpose.
        policy.recordLoadFailure(START + 16 * SECOND);
        require(policy.retryDue(START + 16 * SECOND + RecoveryPolicy.RELOAD_BACKOFF_MS),
                "a failed retry did not re-arm the next attempt");
    }

    /** The caller may be offline or unconfigured; a blocked attempt must stay due, not be spent. */
    private static void testBlockedRetryStaysDue() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.recordLoadFailure(START);
        long due = START + RecoveryPolicy.RELOAD_BACKOFF_MS;
        require(policy.retryDue(due), "not due at the backoff");
        // The caller declined to issue (no network). Minutes later it must still be due.
        require(policy.retryDue(due + 300 * SECOND), "a blocked retry evaporated");
    }

    private static void testHungLoadIsRetried() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        require(policy.checkHungLoad(START + RecoveryPolicy.LOAD_TIMEOUT_MS) == null,
                "declared hung exactly at the bound; slow is not dead");
        String hung = policy.checkHungLoad(START + RecoveryPolicy.LOAD_TIMEOUT_MS + SECOND);
        require(hung != null, "a load past its bound was not declared hung");
        require(policy.retryDue(START + RecoveryPolicy.LOAD_TIMEOUT_MS + SECOND),
                "a hung load must make a retry due immediately");
        require(policy.checkHungLoad(START + RecoveryPolicy.LOAD_TIMEOUT_MS + 2 * SECOND) == null,
                "a hung load was declared twice; the pending retry should suppress the check");
    }

    /**
     * Measured 2026-08-21: a renderer crash rebuilt the WebView in 1.4 seconds and the supervisor
     * then waited out the full minute before retrying, 66 seconds of blank panel. The tighter
     * bound applies to exactly one load, the one recovery issued, and reverts afterwards so a
     * dashboard that is slow rather than dead cannot be caught in a fast reload loop.
     */
    private static void testRendererDeathBoundAppliesOnce() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.markLoadFollowsRendererDeath();
        require(policy.checkHungLoad(
                        START + RecoveryPolicy.RENDERER_DEATH_LOAD_TIMEOUT_MS + SECOND) != null,
                "the post-renderer-death load was owed the tighter bound");
        // The retry that follows is an ordinary load again.
        long retryAt = START + RecoveryPolicy.RENDERER_DEATH_LOAD_TIMEOUT_MS + SECOND;
        policy.issueLoad(retryAt);
        require(policy.checkHungLoad(
                        retryAt + RecoveryPolicy.RENDERER_DEATH_LOAD_TIMEOUT_MS + SECOND) == null,
                "the tighter bound leaked onto a second load");
        require(policy.checkHungLoad(
                        retryAt + RecoveryPolicy.LOAD_TIMEOUT_MS + SECOND) != null,
                "the ordinary bound stopped applying after a renderer death");
    }

    private static void testErrorPageIsNotASuccess() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.recordLoadFailure(START + SECOND);
        require(!policy.pageFinished(START + SECOND + RecoveryPolicy.LOAD_FAILURE_WINDOW_MS - 1),
                "a finish just inside the failure window counted as success");
        // Outside the window it is a genuinely new, healthy finish.
        require(policy.pageFinished(START + SECOND + RecoveryPolicy.LOAD_FAILURE_WINDOW_MS + 1),
                "a finish well clear of the failure window was refused");
    }

    private static void testFrozenPageNeedsAnObservedChange() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.pageFinished(START + SECOND);
        // A legitimately static page: the identical fingerprint forever must never trigger.
        for (int probe = 0; probe < 20; probe++) {
            require(!policy.recordFingerprint("42|1000"),
                    "a static page was declared frozen; the off-switch this replaced is missed");
        }
        // A live page that then stops: change observed once, then three identical probes.
        require(!policy.recordFingerprint("43|1001"), "the change itself is not frozen");
        require(!policy.recordFingerprint("43|1001"), "one stale probe is not frozen");
        require(!policy.recordFingerprint("43|1001"), "two stale probes are not frozen");
        require(policy.recordFingerprint("43|1001"),
                "a page seen changing and then identical for three probes should be frozen");
    }

    /**
     * The 2026-08-21 bug: the retry path started a new page generation without resetting the
     * detector, so the reload never cleared the state that authorised it, and a working panel
     * reloaded every fifteen minutes forever.
     */
    private static void testFrozenReloadClearsItsOwnAuthorisation() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.pageFinished(START + SECOND);
        policy.recordFingerprint("1|1");
        policy.recordFingerprint("2|2");
        policy.recordFingerprint("2|2");
        policy.recordFingerprint("2|2");
        require(policy.recordFingerprint("2|2"), "setup: the page should have been frozen");
        policy.recordLoadFailure(START + 2 * SECOND);
        policy.issueLoad(START + 2 * SECOND + RecoveryPolicy.RELOAD_BACKOFF_MS);
        policy.pageFinished(START + 20 * SECOND);
        // The reloaded page renders identically (it is the same dashboard). Three identical
        // probes must NOT re-fire: this generation has not yet been seen to change.
        require(!policy.recordFingerprint("2|2"), "generation not reset (probe 1)");
        require(!policy.recordFingerprint("2|2"), "generation not reset (probe 2)");
        require(!policy.recordFingerprint("2|2"), "generation not reset (probe 3)");
        require(!policy.recordFingerprint("2|2"),
                "the frozen-page reload re-authorised itself; the fifteen-minute loop is back");
    }

    /** A JS redirect mid-outage must not cancel the pending recovery. */
    private static void testPageStartedMustNotCancelARetry() {
        RecoveryPolicy policy = new RecoveryPolicy();
        policy.beginLoad(START);
        policy.recordLoadFailure(START + SECOND);
        policy.pageStarted(START + 2 * SECOND);
        require(policy.retryDue(START + SECOND + RecoveryPolicy.RELOAD_BACKOFF_MS),
                "pageStarted cancelled a pending recovery");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
