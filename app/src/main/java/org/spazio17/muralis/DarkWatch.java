/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * The storage half of {@link DisplayOffPolicy}: what the service writes down when it puts the
 * screen to sleep, so the next process can judge how the last one ended, and the standing record
 * of a sleep that ended badly.
 *
 * <p>Lives in {@code kiosk_runtime} beside the applied brightness, in device-protected storage,
 * because a panel that wakes from sleep before the user unlocks still has to read it. Every write
 * that has to survive the process ending uses {@code commit}: the whole point of the dark record
 * is to outlive a kill that may follow within the second.
 */
final class DarkWatch {
    private static final String PREFS = "kiosk_runtime";
    private static final String DARK_BOOT_COUNT = "dark_boot_count";
    private static final String DARK_SINCE_MS = "dark_since_ms";
    private static final String DARK_HEARTBEAT_ELAPSED_MS = "dark_heartbeat_elapsed_ms";
    private static final String DARK_INSTALL_STAMP = "dark_install_stamp";
    private static final String INTENTIONAL_EXIT = "intentional_exit";
    private static final String STOPPED_AT_MS = "sleep_stopped_at_ms";
    private static final String STOPPED_WHY = "sleep_stopped_why";
    private static final String REFUSED_BOOT_COUNT = "sleep_refused_boot_count";

    /** The system stopped the process while the screen was asleep. */
    static final String WHY_STOPPED = "stopped";
    /** The process lived but its heartbeat stopped for minutes: frozen, which is deaf all the same. */
    static final String WHY_FROZEN = "frozen";

    private DarkWatch() {
    }

    private static SharedPreferences prefs(Context context) {
        return KioskConfig.storageContext(context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Records that the screen is being put to sleep, before the call that does it, so a kill that
     * follows at once still finds the record.
     */
    static void begin(Context context, int bootCount, long installStamp, long nowElapsedMs) {
        prefs(context).edit()
                .putInt(DARK_BOOT_COUNT, bootCount)
                .putLong(DARK_SINCE_MS, System.currentTimeMillis())
                .putLong(DARK_HEARTBEAT_ELAPSED_MS, nowElapsedMs)
                .putLong(DARK_INSTALL_STAMP, installStamp)
                .putBoolean(INTENTIONAL_EXIT, false)
                .commit();
    }

    static boolean active(Context context) {
        return prefs(context).getInt(DARK_BOOT_COUNT, -1) >= 0;
    }

    static long lastBeatElapsedMs(Context context) {
        return prefs(context).getLong(DARK_HEARTBEAT_ELAPSED_MS, 0L);
    }

    /** Asynchronous on purpose: a beat lost to a kill is exactly what the judge is looking for. */
    static void beat(Context context, long nowElapsedMs) {
        prefs(context).edit().putLong(DARK_HEARTBEAT_ELAPSED_MS, nowElapsedMs).apply();
    }

    /** The screen is awake again, by whatever means; nothing is left to judge. */
    static void end(Context context) {
        prefs(context).edit()
                .remove(DARK_BOOT_COUNT)
                .remove(DARK_SINCE_MS)
                .remove(DARK_HEARTBEAT_ELAPSED_MS)
                .remove(DARK_INSTALL_STAMP)
                .remove(INTENTIONAL_EXIT)
                .commit();
    }

    /** The nightly restart says so before it exits, so its exit is not held against sleep. */
    static void markIntentionalExit(Context context) {
        prefs(context).edit().putBoolean(INTENTIONAL_EXIT, true).commit();
    }

    /**
     * At process start: what ended the previous process, and clears the dark record either way,
     * since this process has not put anything to sleep yet.
     */
    static DisplayOffPolicy.Exit judgeAndClear(Context context, int currentBoot,
            long currentInstall) {
        SharedPreferences prefs = prefs(context);
        DisplayOffPolicy.Exit exit = DisplayOffPolicy.judgeExit(
                prefs.getInt(DARK_BOOT_COUNT, -1), currentBoot,
                prefs.getLong(DARK_INSTALL_STAMP, 0L), currentInstall,
                prefs.getBoolean(INTENTIONAL_EXIT, false));
        if (exit != DisplayOffPolicy.Exit.NONE) {
            end(context);
        }
        return exit;
    }

    static void recordStopped(Context context, long atMs, String why) {
        prefs(context).edit()
                .putLong(STOPPED_AT_MS, atMs)
                .putString(STOPPED_WHY, why)
                .commit();
    }

    /** Wall time the system last stopped Muralis while asleep, or 0 for never since the method was set. */
    static long stoppedAtMs(Context context) {
        return prefs(context).getLong(STOPPED_AT_MS, 0L);
    }

    static String stoppedWhy(Context context) {
        return prefs(context).getString(STOPPED_WHY, "");
    }

    /**
     * {@code lockNow} threw in this boot. Keyed to the boot count like the visual-off flag, so it
     * expires by construction at the reboot that also grants the policy, with no cleanup to miss.
     */
    static void recordRefusal(Context context, int bootCount) {
        prefs(context).edit().putInt(REFUSED_BOOT_COUNT, bootCount).commit();
    }

    static boolean refusedThisBoot(Context context, int bootCount) {
        int recorded = prefs(context).getInt(REFUSED_BOOT_COUNT, -1);
        return recorded >= 0 && recorded == bootCount;
    }

    static void forgetStopped(Context context) {
        prefs(context).edit().remove(STOPPED_AT_MS).remove(STOPPED_WHY).commit();
    }

    /**
     * Stores the operator's method and forgets any stopped record: setting the method, to any
     * value, is how the operator says "judge this panel afresh". Every surface that changes the
     * method goes through here, so none can store the one without the other.
     */
    static void setMethod(Context context, String method) {
        KioskConfig.edit(context).displayOffMethod(method).apply();
        forgetStopped(context);
    }

    /**
     * The app's install stamp, which the installer moves on every update. Recorded with the dark
     * record so an update's kill, which lands while the screen is off on a panel that sleeps every
     * night, is never read as the system stopping Muralis.
     */
    static long installStamp(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException impossible) {
            return 0L;
        }
    }
}
