/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * The setup wizard's last question to the new device owner during QR enrolment: is the device
 * compliant with its policies? Muralis has nothing to put in place here. Lock task and HOME wait
 * until the first-start wizard has recorded a way out, on purpose (see
 * {@link KioskDeviceAdminReceiver#onEnabled}), and the service's device-owner defaults apply
 * whenever its process runs, as they always have. So the answer is yes, at once, and setup goes
 * on.
 *
 * <p>Required from Android 12 with {@link ProvisioningModeActivity}; see there. The setup wizard
 * starts it near its end, with Muralis already the device owner, and steps back on anything but
 * OK, so the answer comes before anything else. Started only by the setup, the system or the
 * device's enrolment app ({@code BIND_DEVICE_ADMIN} in the manifest), and it shows nothing.
 */
public final class PolicyComplianceActivity extends Activity {
    private static final String TAG = "MuralisAdmin";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The settings a setup QR carried (block 8), handed to the service to apply.
        KioskConfig.keepProvisionedSettings(this, getIntent());
        setResult(RESULT_OK);
        finish();
        Log.i(TAG, "Setup asked for policy compliance: nothing to set until the first start");
    }
}
