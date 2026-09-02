/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

public final class RelaunchPolicyTest {
    /** A service old enough that "no activity" is a fact rather than a race. */
    private static final long SETTLED = RelaunchPolicy.SETTLE_MS;

    public static void main(String[] args) {
        testOrdinaryInstallIsLeftAlone();
        testYoungServiceWaits();
        testLiveActivityIsLeftAlone();
        testWaitsForSetupAndUnlock();
        testRelaunchesAfterForceStop();
        testFloorStopsAThrash();
        testBackwardsClockDoesNotStrandThePanel();
        System.out.println("RelaunchPolicyTest passed");
    }

    private static void testOrdinaryInstallIsLeftAlone() {
        // The 2026-08-21 rule: an ordinary install behaves like any app, so a closed Muralis stays
        // closed, whatever else is true.
        require(RelaunchPolicy.decide(false, SETTLED, false, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.NOT_A_KIOSK, "an ordinary install was relaunched");
    }

    private static void testYoungServiceWaits() {
        // The activity's creation is queued right behind the service's at boot and after an
        // update; judging before it lands relaunched a dashboard that was milliseconds away and,
        // worse, stamped the floor that then blocked the real relaunch (measured 2026-09-03).
        require(RelaunchPolicy.decide(true, 0L, false, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.SETTLING, "judged at service start");
        require(RelaunchPolicy.decide(true, RelaunchPolicy.SETTLE_MS - 1, false, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.SETTLING, "judged one millisecond early");
        require(RelaunchPolicy.decide(true, RelaunchPolicy.SETTLE_MS, false, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.RELAUNCH, "a settled service with no screen did nothing");
        // Settling is about the activity's existence, so an ordinary install is still left alone
        // first: no verdict for a kiosk may ever be reached for an ordinary install.
        require(RelaunchPolicy.decide(false, 0L, false, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.NOT_A_KIOSK, "an ordinary install was told to settle");
    }

    private static void testLiveActivityIsLeftAlone() {
        // Paused behind Settings or the launcher after the escape hatch is still alive: relaunching
        // there would snatch the screen from the operator.
        require(RelaunchPolicy.decide(true, SETTLED, true, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.ON_SCREEN, "a live activity was relaunched over");
    }

    private static void testWaitsForSetupAndUnlock() {
        // During QR enrolment the admin receiver starts this process before the setup wizard is
        // done; a dashboard popping up over the wizard is the failure this branch prevents.
        require(RelaunchPolicy.decide(true, SETTLED, false, false, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.NOT_READY, "relaunched over the setup wizard");
    }

    private static void testRelaunchesAfterForceStop() {
        require(RelaunchPolicy.decide(true, SETTLED, false, true, 1_000_000L, 0L)
                == RelaunchPolicy.Verdict.RELAUNCH, "a never-relaunched kiosk with no screen stayed off");
        long longAgo = 1_000_000L - RelaunchPolicy.RELAUNCH_FLOOR_MS;
        require(RelaunchPolicy.decide(true, SETTLED, false, true, 1_000_000L, longAgo)
                == RelaunchPolicy.Verdict.RELAUNCH, "exactly one floor later should relaunch");
    }

    private static void testFloorStopsAThrash() {
        long justNow = 1_000_000L - 1_500L;
        require(RelaunchPolicy.decide(true, SETTLED, false, true, 1_000_000L, justNow)
                == RelaunchPolicy.Verdict.TOO_SOON, "relaunched 1.5 s after the previous relaunch");
        long almostAFloor = 1_000_000L - RelaunchPolicy.RELAUNCH_FLOOR_MS + 1;
        require(RelaunchPolicy.decide(true, SETTLED, false, true, 1_000_000L, almostAFloor)
                == RelaunchPolicy.Verdict.TOO_SOON, "relaunched one millisecond inside the floor");
    }

    private static void testBackwardsClockDoesNotStrandThePanel() {
        // The stored stamp is in the future after a time sync. Refusing here would keep the kiosk
        // off its screen for as long as the clock was wrong.
        require(RelaunchPolicy.decide(true, SETTLED, false, true, 1_000_000L, 5_000_000L)
                == RelaunchPolicy.Verdict.RELAUNCH, "a backwards clock stranded the panel");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
