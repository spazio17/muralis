/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import org.spazio17.muralis.ScreensaverPolicy.Settings;
import org.spazio17.muralis.ScreensaverPolicy.Stage;
import org.spazio17.muralis.ScreensaverPolicy.Step;

/**
 * The screensaver's clock and vocabulary on the host: every row here is two minutes and a
 * stopwatch beside a tablet otherwise.
 */
public final class ScreensaverPolicyTest {
    private static final long T0 = 1_000_000L;

    public static void main(String[] args) {
        testVocabulary();
        testNumbersAsTyped();
        testModeProblem();
        testOffModeNeverStarts();
        testIdleStartsTheScreensaver();
        testZeroIdleIsOnlyOnRequest();
        testScreensaverHandsOverToDisplayOff();
        testZeroOffKeepsTheDisplayOn();
        testBlockedAndDarkDoNothing();
        testScreensaverFirst();
        testDescriptions();
        System.out.println("ScreensaverPolicyTest passed");
    }

    private static Settings settings(String mode, int idle, int off) {
        return new Settings(mode, idle, off, "https://example.com/frame", 20,
                ScreensaverPolicy.WAKE_SCREENSAVER);
    }

    private static void testVocabulary() {
        for (String mode : new String[] {"off", "dim", "film", "url", "pictures"}) {
            require(ScreensaverPolicy.isMode(mode), mode + " must be a mode");
        }
        require(!ScreensaverPolicy.isMode("photos") && !ScreensaverPolicy.isMode("")
                && !ScreensaverPolicy.isMode(null), "anything else must be refused");
        require(ScreensaverPolicy.isTransition("none") && ScreensaverPolicy.isTransition("fade")
                && ScreensaverPolicy.isTransition("slide") && !ScreensaverPolicy.isTransition("zoom"),
                "three transitions");
        require(ScreensaverPolicy.isCorner("bottom_left") && ScreensaverPolicy.isCorner("top_right")
                && !ScreensaverPolicy.isCorner("centre"), "four corners");
        require(ScreensaverPolicy.isFit("fit") && ScreensaverPolicy.isFit("fill")
                && ScreensaverPolicy.isFit("stretch") && ScreensaverPolicy.isFit("actual")
                && !ScreensaverPolicy.isFit("zoom"), "four ways to lay a picture on the glass");
        require(PictureSources.isSource("local") && PictureSources.isSource("bing")
                && PictureSources.isSource("wikimedia") && !PictureSources.isSource("nasa"),
                "three sources in this step");
        require(ScreensaverPolicy.isOnWake("screensaver")
                && ScreensaverPolicy.isOnWake("dashboard")
                && !ScreensaverPolicy.isOnWake("page"), "two on-wake choices, no third");
    }

    /** Digits only, in range; the refusal names the range. */
    private static void testNumbersAsTyped() {
        require(ScreensaverPolicy.parseSeconds("0") == 0, "0 is a valid time (off / never)");
        require(ScreensaverPolicy.parseSeconds(" 120 ") == 120, "surrounding spaces are fine");
        require(ScreensaverPolicy.parseSeconds("86400") == 86_400, "a day is the ceiling");
        require(ScreensaverPolicy.parseSeconds("86401") == null, "past a day is refused");
        require(ScreensaverPolicy.parseSeconds("-1") == null, "negative is refused");
        require(ScreensaverPolicy.parseSeconds("2 min") == null, "units are refused, not guessed");
        require(ScreensaverPolicy.parseSeconds("") == null && ScreensaverPolicy.parseSeconds(null) == null,
                "empty is refused");
        require(ScreensaverPolicy.parseSeconds("9999999") == null, "absurdly long input is refused");
        require(ScreensaverPolicy.parseSeconds("+5") == null && ScreensaverPolicy.parseSeconds("1e3") == null,
                "a sign or an exponent is not a whole number here");
        require(ScreensaverPolicy.SECONDS_RULE.contains("86400")
                && ScreensaverPolicy.DIM_RULE.contains("100")
                && ScreensaverPolicy.PICTURE_SECONDS_RULE.contains("1 to"),
                "every surface's refusal names the rule, and the rule names its range");
        require(ScreensaverPolicy.parseDimPercent("1") == 1 && ScreensaverPolicy.parseDimPercent("100") == 100,
                "1 to 100 for the dim floor");
        require(ScreensaverPolicy.parseDimPercent("0") == null && ScreensaverPolicy.parseDimPercent("101") == null,
                "0 is the film, 101 is nothing");
        require(ScreensaverPolicy.parsePictureSeconds("0") == null
                && ScreensaverPolicy.parsePictureSeconds("1") == 1, "a picture shows for at least a second");
    }

    private static void testModeProblem() {
        require(ScreensaverPolicy.modeProblem("url", "") != null, "URL mode needs an address");
        require(ScreensaverPolicy.modeProblem("url", "   ") != null, "blank is no address");
        require(ScreensaverPolicy.modeProblem("url", "https://example.com/") == null,
                "URL mode with an address is fine");
        require(ScreensaverPolicy.modeProblem("dim", "") == null
                && ScreensaverPolicy.modeProblem("film", "") == null
                && ScreensaverPolicy.modeProblem("off", "") == null,
                "the other modes need nothing");
        Settings urlWithoutAddress = new Settings("url", 120, 900, "", 20, "screensaver");
        require(!ScreensaverPolicy.runnable(urlWithoutAddress), "not runnable without the address");
        require(ScreensaverPolicy.next(urlWithoutAddress, Stage.DASHBOARD, false, T0,
                T0 + 3_600_000L) == Step.NONE, "an unrunnable mode never starts by itself");
    }

    private static void testOffModeNeverStarts() {
        Settings off = settings("off", 1, 1);
        require(ScreensaverPolicy.next(off, Stage.DASHBOARD, false, T0, T0 + 86_400_000L)
                == Step.NONE, "off is off, however long the panel idles");
    }

    private static void testIdleStartsTheScreensaver() {
        Settings dim = settings("dim", 120, 900);
        require(ScreensaverPolicy.next(dim, Stage.DASHBOARD, false, T0, T0 + 119_999L) == Step.NONE,
                "a millisecond short of the idle time is not idle enough");
        require(ScreensaverPolicy.next(dim, Stage.DASHBOARD, false, T0, T0 + 120_000L) == Step.START,
                "exactly the idle time starts the screensaver");
        require(ScreensaverPolicy.next(dim, Stage.DASHBOARD, false, T0, T0 + 999_000L) == Step.START,
                "long past it, still START and never DISPLAY_OFF from the dashboard");
    }

    private static void testZeroIdleIsOnlyOnRequest() {
        Settings onRequest = settings("film", 0, 300);
        require(ScreensaverPolicy.next(onRequest, Stage.DASHBOARD, false, T0, T0 + 86_400_000L)
                == Step.NONE, "idle 0 never starts by itself");
        require(ScreensaverPolicy.next(onRequest, Stage.SCREENSAVER, false, T0, T0 + 300_000L)
                == Step.DISPLAY_OFF, "but once started on request, the second timer still runs");
    }

    private static void testScreensaverHandsOverToDisplayOff() {
        Settings url = settings("url", 120, 900);
        require(ScreensaverPolicy.next(url, Stage.SCREENSAVER, false, T0, T0 + 899_999L) == Step.NONE,
                "the second timer counts from the screensaver's start");
        require(ScreensaverPolicy.next(url, Stage.SCREENSAVER, false, T0, T0 + 900_000L)
                == Step.DISPLAY_OFF, "and hands over to display off when it runs out");
    }

    private static void testZeroOffKeepsTheDisplayOn() {
        Settings forever = settings("url", 120, 0);
        require(ScreensaverPolicy.next(forever, Stage.SCREENSAVER, false, T0, T0 + 86_400_000L)
                == Step.NONE, "off time 0 means the display stays on");
    }

    private static void testBlockedAndDarkDoNothing() {
        Settings dim = settings("dim", 1, 1);
        require(ScreensaverPolicy.next(dim, Stage.DASHBOARD, true, T0, T0 + 60_000L) == Step.NONE,
                "an operator on a settings screen blocks the start");
        require(ScreensaverPolicy.next(dim, Stage.SCREENSAVER, true, T0, T0 + 60_000L) == Step.NONE,
                "and blocks the hand-over");
        require(ScreensaverPolicy.next(dim, Stage.DARK, false, T0, T0 + 60_000L) == Step.NONE,
                "a dark panel has nothing left to do");
    }

    private static void testScreensaverFirst() {
        require(ScreensaverPolicy.screensaverFirst(settings("url", 120, 900)),
                "URL mode with the screensaver-first choice shows the screensaver on wake");
        require(!ScreensaverPolicy.screensaverFirst(new Settings("url", 120, 900,
                "https://example.com/", 20, ScreensaverPolicy.WAKE_DASHBOARD)),
                "the dashboard-first choice is honoured");
        require(ScreensaverPolicy.screensaverFirst(settings("dim", 120, 900)),
                "the dimmed page is something to glance at too");
        require(!ScreensaverPolicy.screensaverFirst(settings("film", 120, 900)),
                "a wake to the black film is no wake: the choice does not apply");
        require(ScreensaverPolicy.wakeChoiceApplies("url") && ScreensaverPolicy.wakeChoiceApplies("dim")
                && ScreensaverPolicy.wakeChoiceApplies("pictures")
                && !ScreensaverPolicy.wakeChoiceApplies("film") && !ScreensaverPolicy.wakeChoiceApplies("off"),
                "the surfaces grey the choice out exactly where it does not apply");
        require(!ScreensaverPolicy.screensaverFirst(new Settings("url", 120, 900, "", 20,
                ScreensaverPolicy.WAKE_SCREENSAVER)),
                "no address, nothing to show first");
    }

    private static void testDescriptions() {
        require(ScreensaverPolicy.describeDuration(45).equals("45 s"), "seconds");
        require(ScreensaverPolicy.describeDuration(120).equals("2 min"), "whole minutes");
        require(ScreensaverPolicy.describeDuration(150).equals("2 min 30 s"), "minutes and seconds");
        require(ScreensaverPolicy.describeDuration(5400).equals("1 h 30 min"), "hours and minutes");
        require(ScreensaverPolicy.describeDuration(3600).equals("1 h"), "a whole hour");
        require(ScreensaverPolicy.describe(settings("off", 120, 900), false)
                .equals("Screensaver off."), "off reads as off");
        String dim = ScreensaverPolicy.describe(settings("dim", 120, 900), false);
        require(dim.equals("Dimmed page after 2 min without a touch, display off 15 min later."),
                "the whole rule in one sentence: " + dim);
        String showing = ScreensaverPolicy.describe(settings("url", 0, 0), true);
        require(showing.equals("Web page, only when asked for, the display stays on. Showing now."),
                "idle 0, off 0 and active: " + showing);
        Settings pictures = new Settings("pictures", 120, 0, "", 20, "screensaver", "bing", 20,
                "fade", false, false, false, "bottom_left", "fit");
        require(ScreensaverPolicy.describe(pictures, false).equals(
                "Pictures from Bing image of the day after 2 min without a touch, the display stays on."),
                "the source is named: " + ScreensaverPolicy.describe(pictures, false));
        require(pictures.creditShown(), "an online source shows the credit whatever the switch says");
        require(!new Settings("pictures", 120, 0, "", 20, "screensaver", "local", 20, "fade", false,
                false, false, "bottom_left", "fit").creditShown(), "the local folder may switch it off");
        String broken = ScreensaverPolicy.describe(new Settings("url", 120, 900, "", 20,
                "screensaver"), false);
        require(broken.equals("Web page: the web-page screensaver needs a page address."),
                "the problem is the sentence: " + broken);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
