/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
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
 * A QR enrolment asks {@link ProvisioningModeActivity} and {@link PolicyComplianceActivity}, the
 * admin-integrated setup (needed from Android 12, used from Android 10 since the app offers it),
 * and in that setup Android sends this receiver no provisioning-complete broadcast; nothing depends
 * on it: ProvisioningModeActivity and PolicyComplianceActivity receive the setup QR's settings, and {@link #onEnabled} starts the
 * service.
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

    /**
     * The setup QR's extras, where Android sends this broadcast; in the admin-integrated setup
     * (Android 10 and newer) ProvisioningModeActivity and PolicyComplianceActivity keep them
     * instead.
     */
    @Override
    public void onProfileProvisioningComplete(Context context, android.content.Intent intent) {
        KioskConfig.keepProvisionedSettings(context, intent);
    }

    @Override
    public void onEnabled(Context context, android.content.Intent intent) {
        Log.i(TAG, "Muralis device admin enabled");
        // Once the device's own setup is over, Muralis opens, so a panel set up by QR lands in
        // Muralis rather than in the system's launcher; see KioskService.openFirstStartAfterSetup.
        // HOME is claimed at once when the QR carried the combinations, and otherwise waits for
        // the way out.
        KioskConfig.storageContext(context).getSharedPreferences("kiosk_runtime",
                Context.MODE_PRIVATE).edit().putBoolean(KioskService.OPEN_AFTER_SETUP, true).apply();
        // The process was started for the setup's questions, before Muralis was the owner, so
        // MuralisApplication did not start the service then; it starts here, and watches for the
        // end of the setup.
        try {
            KioskService.start(context);
        } catch (RuntimeException refused) {
            Log.w(TAG, "The service could not start when Muralis became the owner", refused);
        }
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
     * <p>This does <b>not</b> break the escape combination to the launcher: {@code openSystemLauncher()} resolves
     * the OEM launcher and launches it with an explicit {@code setPackage}, which bypasses
     * preferred-activity resolution entirely. Verify that after changing either method.
     *
     * <p>Called from {@code KioskActivity.applyKioskPolicy} and, at the end of the device's
     * setup, from {@code KioskService.openFirstStartAfterSetup}, and only once both escape
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

    /** Where a release build's QRs download Muralis: the website's file, as its own QR names it. */
    static final String SETUP_APK = "https://muralis.spazio17.org/download/muralis-latest.apk";

    /**
     * The QR content that sets a factory-reset tablet up with this same Muralis as device owner,
     * with {@code extras} (string values) handed to it at the end of the setup; null when this
     * build has nowhere to download from (a debug build without MURALIS_SETUP_APK_URL). The
     * checksum is this app's own signing certificate, read here rather than written down: the
     * website's file and a release build carry the same Play signing key, a debug build and the
     * server it names the debug key.
     */
    static String provisioningQr(Context context, org.json.JSONObject extras) {
        String download = BuildConfig.DEBUG ? BuildConfig.SETUP_APK_URL : SETUP_APK;
        String checksum = ownCertificateChecksum(context);
        if (download.isEmpty() || checksum == null) {
            return null;
        }
        try {
            return new org.json.JSONObject()
                    .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME",
                            componentName(context).flattenToShortString())
                    .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION",
                            download)
                    .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM",
                            checksum)
                    .put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
                    .put("android.app.extra.PROVISIONING_SKIP_ENCRYPTION", true)
                    .put("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE", extras)
                    .toString();
        } catch (org.json.JSONException impossible) {
            return null;
        }
    }

    /** SHA-256 of this app's signing certificate, base64url without padding, as the QR wants it. */
    @SuppressWarnings("deprecation")
    static String ownCertificateChecksum(Context context) {
        try {
            android.content.pm.PackageManager packages = context.getPackageManager();
            byte[] certificate;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.SigningInfo signing = packages.getPackageInfo(
                        context.getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                android.content.pm.Signature[] signers = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
                // The current key is the last of the history, the one the website's file carries.
                certificate = signers[signers.length - 1].toByteArray();
            } else {
                certificate = packages.getPackageInfo(context.getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNATURES).signatures[0].toByteArray();
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate);
            return android.util.Base64.encodeToString(digest,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
        } catch (android.content.pm.PackageManager.NameNotFoundException
                | java.security.NoSuchAlgorithmException | RuntimeException failure) {
            Log.w(TAG, "The app's signing certificate could not be read", failure);
            return null;
        }
    }
}
