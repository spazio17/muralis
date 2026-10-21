/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Tells the setup wizard which kind of management Muralis wants, during QR enrolment: a fully
 * managed device, never a work profile.
 *
 * <p>From Android 12 a QR enrolment downloads and installs the app, then fails with "Can't set up
 * device" unless the app answers {@code ACTION_GET_PROVISIONING_MODE} and
 * {@code ACTION_ADMIN_POLICY_COMPLIANCE} (vendor docs, "Android 12 work changes"; AOSP
 * ManagedProvisioning's PreProvisioningActivity). Muralis answered neither, so it failed every
 * time (the Mi 9T on Android 16, 2026-10-07). Android 10 and 11 ask the same two questions when
 * the app answers both, with no list of allowed modes, and keep their older flow otherwise;
 * Android 8 and 9 never ask. It shows nothing: it answers and closes.
 *
 * <p>Started only by the setup, the system or the device's enrolment app (Android Device Policy
 * on the Mi 9T), which the manifest's {@code BIND_DEVICE_ADMIN} permission makes sure of, as
 * Google's TestDPC, Headwind MDM and FreeKiosk declare it.
 */
public final class ProvisioningModeActivity extends Activity {
    private static final String TAG = "MuralisAdmin";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Provisioning.fullyManagedOffered(getIntent().getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES))) {
            Log.i(TAG, "Setup asked for the provisioning mode: fully managed device");
            // The setup's last question comes next: Muralis opens only once it is answered
            // (KioskService.openFirstStartAfterSetup). The QR's settings and invitation are kept
            // already here, as the same extras come with this question from Android 10 on.
            KioskConfig.storageContext(this).getSharedPreferences("kiosk_runtime", MODE_PRIVATE)
                    .edit().putBoolean(KioskService.COMPLIANCE_DUE, true).commit();
            KioskConfig.keepProvisionedSettings(this, getIntent());
            setResult(RESULT_OK, new Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE));
        } else {
            // A setup that offers only a work profile cannot make a kiosk, and the setup wizard
            // ends the enrolment on a cancel, which beats a panel that cannot lock.
            Log.w(TAG, "Setup offers no fully managed device, only a work profile: declined");
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
