/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * The setup wizard's last question to the new device owner during QR enrolment: is the device
 * compliant with its policies? Muralis keeps the settings a setup QR carried, for the service to
 * apply, and has nothing else to put in place here. Lock task and HOME wait
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
        // The settings a setup QR carried, handed to the service to apply.
        KioskConfig.keepProvisionedSettings(this, getIntent());
        setResult(RESULT_OK);
        finish();
        // Answered: Muralis may open now, or when the setup ends (KioskService.openFirstStartAfterSetup).
        KioskConfig.storageContext(this).getSharedPreferences("kiosk_runtime", MODE_PRIVATE)
                .edit().remove(KioskService.COMPLIANCE_DUE).commit();
        KioskService.reloadConfiguration(this);
        Log.i(TAG, "Setup asked for policy compliance: the QR's settings kept, the rest waits for"
                + " the first start");
    }
}
