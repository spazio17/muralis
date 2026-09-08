/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Which "display off" a panel gets, and whether the last real one ended badly.
 *
 * <p>Two ways to darken a panel exist. The <b>black film</b> is a black view at the window's
 * minimum brightness: the screen stays on, so the backlight still glows in a dark room, but nothing
 * about the app changes and a tap on the glass brings the dashboard back. A real <b>sleep</b> is
 * the device owner's {@code DevicePolicyManager.lockNow()}, the admin's power button: the LED goes
 * off, and only a remote wake, the power button or the presence blueprint brings it back. Sleep is
 * what Juri asked for by default (2026-09-08); the film is what an ordinary install gets, and what
 * a device-owner panel falls back to when sleep cannot be trusted.
 *
 * <p><b>When sleep cannot be trusted, and how that is known without asking the user to
 * experiment.</b> Two things stop a sleeping panel hearing its wake. The first is Android's own
 * Doze: on battery, an app that is not on the battery-optimisation allowlist has its network
 * suspended once the device idles, so the MQTT session and the web admin go deaf. That is
 * documented behaviour, and both halves are readable, {@code isIgnoringBatteryOptimizations}
 * and whether a cable is attached, so it is decided up front. The second is a vendor power
 * manager killing or freezing the process while the screen is off, which no API announces
 * (EMUI's PowerGenie force-stopped Muralis five minutes into a screen-off on battery,
 * 2026-09-03). That one is caught after the fact, once: the service records that it put the
 * screen to sleep and heartbeats while dark, and the next process start judges what ended the
 * last one. A reboot, an app update and the nightly restart are all innocent and recognised as
 * such; anything else while dark was the system stopping Muralis, and the panel uses the film
 * from then on, saying so on the Display card and in the web admin. Changing the method
 * clears that record, so choosing Automatic again is how the operator asks for another try.
 *
 * <p>Pure, with time and every fact passed in, so the matrix is host-tested rather than
 * reproduced with a stopwatch beside three tablets.
 */
final class DisplayOffPolicy {
    /** Sleep where it can be trusted, the film otherwise. The default. */
    static final String AUTO = "auto";
    /** Always sleep; the operator has decided this panel survives it. */
    static final String SLEEP = "sleep";
    /** Always the film; the operator wants the tap-to-wake or does not trust the ROM. */
    static final String FILM = "film";

    /**
     * How often the service writes a heartbeat while the screen is asleep, and a third of the gap
     * that counts as the process having been frozen. Thirty seconds is the MQTT liveness cadence
     * too, so a panel frozen long enough to be judged here is one Home Assistant already saw go
     * unavailable.
     */
    static final long HEARTBEAT_MS = 30_000L;

    /** What actually happens to the screen. */
    enum Method { SLEEP, FILM }

    /** Why, in a form the surfaces can turn into one sentence with the right date in it. */
    enum Why {
        /** The operator chose this method outright. */
        CHOSEN,
        /** Automatic picked sleep: device owner, and nothing known against it. */
        TRUSTED,
        /** Sleep needs the admin's power button, which only the device owner has. */
        NOT_DEVICE_OWNER,
        /** On battery without the Doze allowlist, a sleeping panel would lose its network. */
        ON_BATTERY_WITHOUT_EXEMPTION,
        /** A previous sleep ended with the system stopping Muralis; see {@link Exit#STOPPED}. */
        STOPPED_WHILE_DARK,
        /**
         * {@code lockNow} was refused in this boot. The admin's declared policies are read when
         * the admin is activated and again at every boot, not when the app is updated, so an
         * install that gained {@code force-lock} by update keeps the old set until the tablet
         * restarts. Measured on the Lenovo 2026-09-08: refused after the update, honoured after
         * the reboot.
         */
        AWAITING_REBOOT
    }

    static final class Choice {
        final Method method;
        final Why why;

        Choice(Method method, Why why) {
            this.method = method;
            this.why = why;
        }
    }

    /** What ended the previous process, if it ended while the screen was asleep. */
    enum Exit {
        /** The screen was not asleep when the previous process ended, or nothing is recorded. */
        NONE,
        /** The boot count moved: a reboot, which ends every process and proves nothing. */
        REBOOTED,
        /** The app was updated: the installer kills the process, and that proves nothing either. */
        UPDATED,
        /** The nightly restart, which marks its own exit before it happens. */
        INTENTIONAL,
        /** Nothing innocent explains it: the system stopped Muralis while the screen was off. */
        STOPPED
    }

    private DisplayOffPolicy() {
    }

    static boolean isMethod(String value) {
        return AUTO.equals(value) || SLEEP.equals(value) || FILM.equals(value);
    }

    /**
     * @param setting     the stored method, one of {@link #AUTO}, {@link #SLEEP}, {@link #FILM}
     * @param deviceOwner whether this app is the device owner, so {@code lockNow} exists for it
     * @param plugged     whether a cable is attached; Doze never engages on a charger
     * @param dozeExempt  {@code PowerManager.isIgnoringBatteryOptimizations} for this package
     * @param stoppedAtMs wall time the system last stopped Muralis while the screen was asleep,
     *                    or 0 when that has not happened since the method was last set
     * @param refusedThisBoot whether {@code lockNow} threw in this boot; see {@link Why#AWAITING_REBOOT}
     */
    static Choice choose(String setting, boolean deviceOwner, boolean plugged, boolean dozeExempt,
            long stoppedAtMs, boolean refusedThisBoot) {
        if (FILM.equals(setting)) {
            return new Choice(Method.FILM, Why.CHOSEN);
        }
        if (!deviceOwner) {
            return new Choice(Method.FILM, Why.NOT_DEVICE_OWNER);
        }
        if (refusedThisBoot) {
            // Outranks even an insisting operator: the call will throw again until the reboot,
            // and a panel that answers "accepted" while doing nothing is the shape this app
            // deletes wherever it finds it. The film goes on instead, and the sentence says why.
            return new Choice(Method.FILM, Why.AWAITING_REBOOT);
        }
        if (SLEEP.equals(setting)) {
            return new Choice(Method.SLEEP, Why.CHOSEN);
        }
        // AUTO, and anything unrecognised is read as AUTO rather than refused: a stored value this
        // build does not know must not leave a panel with no way to go dark.
        if (stoppedAtMs > 0) {
            return new Choice(Method.FILM, Why.STOPPED_WHILE_DARK);
        }
        if (!plugged && !dozeExempt) {
            return new Choice(Method.FILM, Why.ON_BATTERY_WITHOUT_EXEMPTION);
        }
        return new Choice(Method.SLEEP, Why.TRUSTED);
    }

    /**
     * Judges the previous process from what it left behind, at the start of the next one.
     *
     * @param darkBoot       the boot count recorded when the screen was put to sleep, or -1 when
     *                       the previous process did not end while asleep
     * @param currentBoot    the device's boot count now
     * @param darkInstall    the app's install stamp ({@code lastUpdateTime}) recorded at that time
     * @param currentInstall the app's install stamp now
     * @param intentional    whether the previous process marked its own exit before leaving
     */
    static Exit judgeExit(int darkBoot, int currentBoot, long darkInstall, long currentInstall,
            boolean intentional) {
        if (darkBoot < 0) {
            return Exit.NONE;
        }
        if (darkBoot != currentBoot) {
            return Exit.REBOOTED;
        }
        if (darkInstall != currentInstall) {
            return Exit.UPDATED;
        }
        if (intentional) {
            return Exit.INTENTIONAL;
        }
        return Exit.STOPPED;
    }

    /**
     * Whether the heartbeat has fallen far enough behind to mean the process was frozen rather
     * than merely late. Three intervals: a busy tick can slip by a few seconds, a frozen process
     * misses minutes.
     *
     * @param lastBeatElapsedMs monotonic time of the last heartbeat, or 0 for none yet
     * @param nowElapsedMs      monotonic time now
     */
    static boolean frozen(long lastBeatElapsedMs, long nowElapsedMs, long intervalMs) {
        return lastBeatElapsedMs > 0 && nowElapsedMs - lastBeatElapsedMs > 3 * intervalMs;
    }
}
