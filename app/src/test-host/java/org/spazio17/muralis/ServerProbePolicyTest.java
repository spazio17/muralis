/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

public final class ServerProbePolicyTest {
    public static void main(String[] args) {
        testStatusClassification();
        testHealthyServerNeverReloads();
        testReloadFiresOnRecoveryNotDuringOutage();
        testOneReloadPerOutage();
        testResetForgetsTheOutage();
        System.out.println("ServerProbePolicyTest passed");
    }

    private static void testStatusClassification() {
        // Anything the server answered proves it is up, including auth failures and redirects.
        require(ServerProbePolicy.statusMeansServerUp(200), "200 should mean up");
        require(ServerProbePolicy.statusMeansServerUp(302), "a redirect still proves the server answered");
        require(ServerProbePolicy.statusMeansServerUp(401), "401 is the load path's business, not an outage");
        require(ServerProbePolicy.statusMeansServerUp(404), "404 still proves the server answered");
        // 5xx is what a reverse proxy says while its backend restarts, the outage this exists for.
        require(!ServerProbePolicy.statusMeansServerUp(500), "500 should mean down");
        require(!ServerProbePolicy.statusMeansServerUp(502), "502 should mean down");
        require(!ServerProbePolicy.statusMeansServerUp(503), "503 should mean down");
    }

    private static void testHealthyServerNeverReloads() {
        ServerProbePolicy policy = new ServerProbePolicy();
        for (int probe = 0; probe < 100; probe++) {
            require(!policy.recordResult(true), "a server that never went down demanded a reload");
        }
    }

    private static void testReloadFiresOnRecoveryNotDuringOutage() {
        ServerProbePolicy policy = new ServerProbePolicy();
        require(!policy.recordResult(true), "reload before any outage");
        // Reloading mid-outage would paint an error page over a page that still looks right.
        require(!policy.recordResult(false), "reload demanded while the server was still down");
        require(!policy.recordResult(false), "reload demanded while the server was still down");
        require(policy.recordResult(true), "the server came back and no reload was demanded");
    }

    private static void testOneReloadPerOutage() {
        ServerProbePolicy policy = new ServerProbePolicy();
        policy.recordResult(false);
        require(policy.recordResult(true), "recovery not noticed");
        // The outage is spent: the page was reloaded, so nothing further is owed.
        require(!policy.recordResult(true), "one outage produced a second reload");
        // A single failed probe is enough to arm the next one; a healthy server still answers.
        policy.recordResult(false);
        require(policy.recordResult(true), "a one-probe outage was not caught");
    }

    private static void testResetForgetsTheOutage() {
        ServerProbePolicy policy = new ServerProbePolicy();
        policy.recordResult(false);
        policy.reset();
        // A load was issued meanwhile; its outcome belongs to the load-failure path, and the first
        // successful probe of the new page must not demand a second reload.
        require(!policy.recordResult(true), "a reset outage still demanded a reload");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
