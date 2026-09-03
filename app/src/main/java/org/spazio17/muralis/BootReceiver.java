/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.provider.Settings;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Kiosks only. A device-owner panel must come back lit after any reboot with nobody
        // touching it, which is this receiver's whole job. On an ordinary install the same code
        // made Muralis start itself fullscreen at every boot, including after the user had closed
        // it, which is the opposite of behaving like an ordinary app (the 2026-08-21 rule), and it
        // silently undid the close paths added 2026-08-27: the recents swipe, the Close button,
        // and plain Back would all have been reverted by the next reboot.
        android.app.admin.DevicePolicyManager policy =
                context.getSystemService(android.app.admin.DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        KioskService.start(context);
        if (deviceReadyForDashboard(context)) {
            context.startActivity(new Intent(context, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * Whether the dashboard may be put on screen at all: first-run setup finished and the user
     * unlocked. Shared with {@code KioskService}'s supervisor, which relaunches the dashboard after
     * a force-stop and must apply the same rule, or it would pop the panel up over the setup wizard
     * during QR enrolment.
     *
     * <p>Only DEVICE_PROVISIONED is checked, not the pair. Settings.Secure.USER_SETUP_COMPLETE is
     * not in the public SDK, and the second half of the test existed to avoid racing LineageOS's
     * SetupWizard, which does not exist here. DEVICE_PROVISIONED alone still answers the one
     * question that matters on boot: has this device finished first-run setup at all.
     */
    static boolean deviceReadyForDashboard(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        boolean provisioned = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 1;
        return users != null && users.isUserUnlocked() && provisioned;
    }
}
