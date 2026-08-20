/*
 * Copyright 2026 KiOSk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.kiosk.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.provider.Settings;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        KioskService.start(context);
        UserManager users = context.getSystemService(UserManager.class);
        // Only DEVICE_PROVISIONED is checked, not the pair. Settings.Secure.USER_SETUP_COMPLETE is
        // not in the public SDK, and the second half of the test existed to avoid racing LineageOS's
        // SetupWizard, which does not exist here. DEVICE_PROVISIONED alone still answers the one
        // question that matters on boot: has this device finished first-run setup at all.
        boolean provisioned = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 1;
        if (users != null && users.isUserUnlocked() && provisioned) {
            context.startActivity(new Intent(context, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
