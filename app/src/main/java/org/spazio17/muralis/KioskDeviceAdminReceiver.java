/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * Device-owner hook. Android grants the strongest kiosk controls only to a device owner, and a
 * device owner must be a {@link DeviceAdminReceiver}; this class exists to be that component and
 * does no policy work of its own.
 *
 * <p>What being device owner buys, none of which is reachable otherwise: lock-task mode with
 * {@code LOCK_TASK_FEATURE_NONE}, which suppresses Home and Overview; a hard status-bar disable;
 * and a keyguard disable that survives a data wipe.
 *
 * <p>It does <b>not</b> remove the system bars on its own. Android has no lock-task feature flag
 * for Back, so an edge swipe can still reveal a navigation bar with a working Back button. Hiding
 * the bars needs {@code policy_control=immersive.full}, and making a revealed one harmless needs an
 * inert {@code onBackPressed}. See KioskActivity.
 *
 * <p>Provisioned out of band, not by the app, over adb or by QR during out-of-box setup. Muralis degrades to its
 * previous behaviour when it is not the device owner, so an un-provisioned tablet still works.
 */
public final class KioskDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "MuralisAdmin";

    static ComponentName componentName(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    /** Whether Muralis is this device's owner, which is what "kiosk" means everywhere in this app. */
    static boolean isDeviceOwner(Context context) {
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        return policy != null && policy.isDeviceOwnerApp(context.getPackageName());
    }

    @Override
    public void onEnabled(Context context, android.content.Intent intent) {
        Log.i(TAG, "Muralis device admin enabled");
        // This used to call pinAsHomeActivity, on the reasoning that QR enrolment is the earliest
        // possible moment to claim HOME. It is, and that was the problem: it made Muralis the only
        // way out of the device before anybody had recorded a way out of Muralis, so when Google's
        // injected licence check killed the activity a second into every launch on 2026-09-03, the
        // tablet had nowhere else to go and had to be wiped. HOME is claimed by
        // KioskActivity.applyKioskPolicy instead, behind the same escape-combination gate as lock
        // task, on the first resume after the first-start wizard finishes. Nothing is lost by
        // waiting: until the wizard is done there is no dashboard to return to anyway.
    }

    /**
     * Makes Muralis the persistent HOME activity, as device owner.
     *
     * <p><b>Declaring the HOME intent filter is not enough, measured on the MediaPad 2026-08-19.</b>
     * After QR enrolment the app was device owner and lock task engaged, but
     * {@code resolve-activity -c android.intent.category.HOME} still returned
     * {@code com.huawei.android.launcher}: the OEM launcher is already the user's established
     * default, and a new filter does not displace it. The ROM build never hit this because it used
     * {@code android:priority="5"} on the filter, which only works for a platform-signed app
     * shipped in the system image.
     *
     * <p>{@link DevicePolicyManager#addPersistentPreferredActivity} is the device-owner equivalent
     * and needs no permission beyond ownership. "Persistent" here means the user cannot clear it
     * from Settings, which is the point for a wall panel.
     *
     * <p>This does <b>not</b> break the nine-tap escape hatch: {@code openSystemLauncher()} resolves
     * the OEM launcher and launches it with an explicit {@code setPackage}, which bypasses
     * preferred-activity resolution entirely. Verify that after changing either method.
     *
     * <p>Called from {@code KioskActivity.applyKioskPolicy} only, and only once both escape
     * combinations are recorded; see {@link #onEnabled} for why it no longer runs at enrolment.
     */
    static void pinAsHomeActivity(Context context) {
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            policy.addPersistentPreferredActivity(componentName(context), home,
                    new ComponentName(context, KioskActivity.class));
            Log.i(TAG, "Muralis pinned as the persistent HOME activity");
        } catch (SecurityException | IllegalArgumentException refused) {
            // Fail soft, as everywhere else: the dashboard still runs, it just is not the launcher,
            // so a reboot would land on the OEM launcher instead of the panel.
            Log.w(TAG, "Could not pin Muralis as HOME; a reboot will land on the OEM launcher",
                    refused);
        }
    }

    @Override
    public CharSequence onDisableRequested(Context context, android.content.Intent intent) {
        return context.getString(R.string.device_admin_disable_warning);
    }

    @Override
    public void onDisabled(Context context, android.content.Intent intent) {
        // Worth a loud log: without device-owner powers the system bars come back on edge swipe.
        Log.w(TAG, "Muralis device admin disabled, kiosk lock-task hardening is now unavailable");
    }
}
