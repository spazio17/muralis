/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.Arrays;
import java.util.Collections;

/**
 * Host tests for the install-source classification and the advice derived from it.
 *
 * <p>Worth testing rather than eyeballing because the interesting cases are the ones that are hard
 * to reproduce on a desk: a Play Store install that is not device owner cannot be created on demand,
 * and getting its advice wrong would send an operator to run an adb command that cannot possibly fix
 * their problem.
 */
public final class ProvisioningTest {
    public static void main(String[] args) {
        testClassification();
        testAdviceIgnoresSourceWhenProvisioned();
        testAdviceDependsOnSourceWhenNotProvisioned();
        testFullyManagedOffered();
        System.out.println("ProvisioningTest passed");
    }

    private static void testFullyManagedOffered() {
        // Android 10 and 11 send no list: the mode is Muralis's to pick.
        require(Provisioning.fullyManagedOffered(null), "no list offers every mode");
        require(Provisioning.fullyManagedOffered(Collections.emptyList()),
                "an empty list offers every mode");
        // Android 12 on: a QR enrolment offers the work profile (2) and fully managed (1).
        require(Provisioning.fullyManagedOffered(Arrays.asList(2, 1)),
                "a QR enrolment's list offers fully managed");
        require(Provisioning.fullyManagedOffered(Arrays.asList(1)),
                "fully managed alone is offered");
        // A setup that allows only a work profile is one Muralis cannot serve.
        require(!Provisioning.fullyManagedOffered(Arrays.asList(2)),
                "a work profile alone is not a kiosk");
        require(!Provisioning.fullyManagedOffered(Arrays.asList(2, 3)),
                "the two work profile modes are not a kiosk");
    }

    private static void testClassification() {
        require(Provisioning.classify("com.android.vending") == Provisioning.InstallSource.PLAY_STORE,
                "Play Store installer must classify as PLAY_STORE");

        // A sideloaded APK has no recorded installer. Both spellings Android uses in practice.
        require(Provisioning.classify(null) == Provisioning.InstallSource.SIDELOADED,
                "a null installer is a sideload");
        require(Provisioning.classify("") == Provisioning.InstallSource.SIDELOADED,
                "an empty installer is a sideload");
        require(Provisioning.classify("   ") == Provisioning.InstallSource.SIDELOADED,
                "a blank installer is a sideload");

        // Whitespace around a real name must not defeat the Play match, or a Play install would be
        // told to run an adb command that cannot work.
        require(Provisioning.classify(" com.android.vending ")
                        == Provisioning.InstallSource.PLAY_STORE,
                "a padded Play installer name must still match");

        require(Provisioning.classify("com.huawei.appmarket")
                        == Provisioning.InstallSource.OTHER_INSTALLER,
                "a vendor store is OTHER_INSTALLER");
    }

    private static void testAdviceIgnoresSourceWhenProvisioned() {
        // A correctly provisioned kiosk must never nag, whatever installed it.
        for (Provisioning.InstallSource source : Provisioning.InstallSource.values()) {
            require(Provisioning.adviceFor(source, true) == Provisioning.Advice.NONE,
                    "device owner must produce no advice, source=" + source);
        }
        require(Provisioning.adviceForInstaller("com.android.vending", true) == Provisioning.Advice.NONE,
                "device owner via Play must produce no advice");
    }

    private static void testAdviceDependsOnSourceWhenNotProvisioned() {
        require(Provisioning.adviceForInstaller("com.android.vending", false)
                        == Provisioning.Advice.PLAY_INSTALL_NOT_PROVISIONED,
                "an unprovisioned Play install needs the reset-and-enrol advice");

        // Sideload and unknown store both have adb available, so they get the command.
        require(Provisioning.adviceForInstaller(null, false)
                        == Provisioning.Advice.SIDELOAD_NOT_PROVISIONED,
                "an unprovisioned sideload needs the adb advice");
        require(Provisioning.adviceForInstaller("com.huawei.appmarket", false)
                        == Provisioning.Advice.SIDELOAD_NOT_PROVISIONED,
                "an unprovisioned vendor-store install needs the adb advice");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
