/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import org.spazio17.muralis.DisplayOffPolicy.Choice;
import org.spazio17.muralis.DisplayOffPolicy.Exit;
import org.spazio17.muralis.DisplayOffPolicy.Method;
import org.spazio17.muralis.DisplayOffPolicy.Why;

/**
 * The display-off matrix and the judgement of a sleep that ended, on the host. Every row here is
 * a situation that would otherwise need a particular tablet in a particular state, on battery, off
 * the allowlist, mid-update, mid-restart, with a stopwatch.
 */
public final class DisplayOffPolicyTest {
    public static void main(String[] args) {
        testVocabulary();
        testFilmWhenChosen();
        testOrdinaryInstallAlwaysGetsTheFilm();
        testSleepWhenInsisted();
        testAutomaticOnMains();
        testAutomaticOnBattery();
        testAutomaticAfterABadSleep();
        testUnknownSettingReadsAsAutomatic();
        testRefusedUntilReboot();
        testExitJudgement();
        testFrozen();
        System.out.println("DisplayOffPolicyTest passed");
    }

    private static void testVocabulary() {
        require(DisplayOffPolicy.isMethod("auto") && DisplayOffPolicy.isMethod("sleep")
                && DisplayOffPolicy.isMethod("film"), "the three methods must be accepted");
        require(!DisplayOffPolicy.isMethod("off") && !DisplayOffPolicy.isMethod("")
                && !DisplayOffPolicy.isMethod(null), "anything else must be refused");
    }

    /** The operator's film is the film, whatever else is true. */
    private static void testFilmWhenChosen() {
        Choice choice = DisplayOffPolicy.choose(DisplayOffPolicy.FILM, true, true, true, 0, false);
        require(choice.method == Method.FILM && choice.why == Why.CHOSEN,
                "film chosen on a trusted panel must still be the film");
        choice = DisplayOffPolicy.choose(DisplayOffPolicy.FILM, false, false, false, 1, false);
        require(choice.method == Method.FILM && choice.why == Why.CHOSEN,
                "film chosen must not be explained away by other reasons");
    }

    /** No admin power button, no sleep: the phone and the Pixel. */
    private static void testOrdinaryInstallAlwaysGetsTheFilm() {
        for (String setting : new String[] {DisplayOffPolicy.AUTO, DisplayOffPolicy.SLEEP}) {
            Choice choice = DisplayOffPolicy.choose(setting, false, true, true, 0, false);
            require(choice.method == Method.FILM && choice.why == Why.NOT_DEVICE_OWNER,
                    "a non-owner must get the film with the owner reason, setting " + setting);
        }
    }

    /** Sleep insisted on overrides both the battery rule and a recorded bad sleep. */
    private static void testSleepWhenInsisted() {
        Choice choice = DisplayOffPolicy.choose(DisplayOffPolicy.SLEEP, true, false, false, 5, false);
        require(choice.method == Method.SLEEP && choice.why == Why.CHOSEN,
                "sleep insisted on must sleep, on battery and after a bad sleep alike");
    }

    /** The wall panel: device owner, on a charger. Doze never engages, so sleep is trusted. */
    private static void testAutomaticOnMains() {
        Choice choice = DisplayOffPolicy.choose(DisplayOffPolicy.AUTO, true, true, false, 0, false);
        require(choice.method == Method.SLEEP && choice.why == Why.TRUSTED,
                "on mains the allowlist does not matter and sleep is trusted");
    }

    /** On battery the allowlist decides: without it Doze would cut the network of a sleeping panel. */
    private static void testAutomaticOnBattery() {
        Choice choice = DisplayOffPolicy.choose(DisplayOffPolicy.AUTO, true, false, false, 0, false);
        require(choice.method == Method.FILM && choice.why == Why.ON_BATTERY_WITHOUT_EXEMPTION,
                "on battery without the allowlist the film must be used, and say why");
        choice = DisplayOffPolicy.choose(DisplayOffPolicy.AUTO, true, false, true, 0, false);
        require(choice.method == Method.SLEEP && choice.why == Why.TRUSTED,
                "on battery with the allowlist sleep is trusted");
    }

    /** One recorded bad sleep is enough, and it outranks the battery rule in the explanation. */
    private static void testAutomaticAfterABadSleep() {
        Choice choice = DisplayOffPolicy.choose(DisplayOffPolicy.AUTO, true, true, true,
                1_700_000_000_000L, false);
        require(choice.method == Method.FILM && choice.why == Why.STOPPED_WHILE_DARK,
                "after a bad sleep automatic must use the film and say the system stopped it");
    }

    /**
     * The policy set an admin declared is read at activation and at boot, not at update, so the
     * first sleep after gaining force-lock by update is refused. Until the reboot the film is used
     * and the reason names the reboot, and it outranks even an insisting operator, because the
     * call would only throw again.
     */
    private static void testRefusedUntilReboot() {
        for (String setting : new String[] {DisplayOffPolicy.AUTO, DisplayOffPolicy.SLEEP}) {
            Choice choice = DisplayOffPolicy.choose(setting, true, true, true, 0, true);
            require(choice.method == Method.FILM && choice.why == Why.AWAITING_REBOOT,
                    "a refusal in this boot must give the film and name the reboot, setting "
                            + setting);
        }
        Choice film = DisplayOffPolicy.choose(DisplayOffPolicy.FILM, true, true, true, 0, true);
        require(film.why == Why.CHOSEN, "film chosen needs no reboot story");
        Choice ordinary = DisplayOffPolicy.choose(DisplayOffPolicy.AUTO, false, true, true, 0, true);
        require(ordinary.why == Why.NOT_DEVICE_OWNER,
                "a non-owner is told about the owner, not about a reboot");
    }

    /** A stored spelling this build does not know must not leave a panel with no way to go dark. */
    private static void testUnknownSettingReadsAsAutomatic() {
        Choice choice = DisplayOffPolicy.choose("whatever", true, true, true, 0, false);
        require(choice.method == Method.SLEEP && choice.why == Why.TRUSTED,
                "an unknown setting must behave as automatic");
    }

    /**
     * What the next process concludes from the record the last one left. Three innocent endings
     * and one guilty one, and the innocent ones are checked first because each of them also kills
     * the process.
     */
    private static void testExitJudgement() {
        require(DisplayOffPolicy.judgeExit(-1, 12, 0, 0, false) == Exit.NONE,
                "no dark record means nothing to judge");
        require(DisplayOffPolicy.judgeExit(11, 12, 500, 500, false) == Exit.REBOOTED,
                "a moved boot count is a reboot");
        require(DisplayOffPolicy.judgeExit(12, 12, 500, 900, false) == Exit.UPDATED,
                "a moved install stamp is an update");
        require(DisplayOffPolicy.judgeExit(12, 12, 500, 500, true) == Exit.INTENTIONAL,
                "the nightly restart marks itself");
        require(DisplayOffPolicy.judgeExit(12, 12, 500, 500, false) == Exit.STOPPED,
                "nothing innocent left means the system stopped Muralis");
        // A reboot that also updated: still a reboot, judged first, so neither is ever blamed.
        require(DisplayOffPolicy.judgeExit(11, 12, 500, 900, true) == Exit.REBOOTED,
                "a reboot outranks every other explanation");
    }

    /** Late is not frozen; minutes are. */
    private static void testFrozen() {
        long interval = DisplayOffPolicy.HEARTBEAT_MS;
        require(!DisplayOffPolicy.frozen(0, 10 * interval, interval),
                "no beat yet cannot be frozen");
        require(!DisplayOffPolicy.frozen(1_000, 1_000 + interval, interval),
                "one interval late is on time");
        require(!DisplayOffPolicy.frozen(1_000, 1_000 + 3 * interval, interval),
                "exactly three intervals is the boundary, not past it");
        require(DisplayOffPolicy.frozen(1_000, 1_001 + 3 * interval, interval),
                "past three intervals is frozen");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
