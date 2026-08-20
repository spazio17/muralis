/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * Works out whether this install can actually be a kiosk, and what to tell the operator if not.
 *
 * <p>The problem this solves is specific to shipping as an app rather than as a ROM. Device-owner
 * status is what stands in for every privileged permission the ROM build had, and it <b>cannot be
 * granted after the fact by installing from the Play Store</b>: Android only accepts a device owner
 * on a device with no configured accounts, which in practice means during out-of-box setup or over
 * adb on a freshly wiped device. So the common failure is entirely silent, someone installs Muralis
 * from Play, it runs, and none of the kiosk hardening is in force. Nothing crashes and nothing
 * explains why the status bar still works.
 *
 * <p>Deliberately free of Android imports so the classification and the advice it produces are
 * covered by host unit tests, the same rule {@link SystemStats} and {@link KioskCommandDispatcher}
 * follow. The caller passes in the two facts it looked up; this class holds the reasoning.
 */
final class Provisioning {

    /** Google Play. The only installer that implies the app was updated through Play. */
    static final String PLAY_STORE_PACKAGE = "com.android.vending";

    /** Where an install came from, to the extent Android will say. */
    enum InstallSource {
        /** Installed and updated by Google Play. */
        PLAY_STORE,
        /** No installer recorded: `adb install`, or a file manager on some builds. */
        SIDELOADED,
        /** Some other store or MDM, for example a vendor app store. */
        OTHER_INSTALLER,
    }

    /** What the operator needs to be told, if anything. */
    enum Advice {
        /** Provisioned correctly. Say nothing. */
        NONE,
        /**
         * Installed from Play but not device owner. The honest advice is that this cannot be fixed
         * in place: it needs a factory reset and enrolment during setup, or a USB provisioning run.
         */
        PLAY_INSTALL_NOT_PROVISIONED,
        /** Sideloaded and not device owner, so the one-line adb command will fix it. */
        SIDELOAD_NOT_PROVISIONED,
    }

    private Provisioning() {
    }

    /**
     * Classifies an installer package name as reported by the package manager.
     *
     * @param installerPackageName the installer, or null/empty when Android recorded none
     */
    static InstallSource classify(String installerPackageName) {
        if (installerPackageName == null || installerPackageName.trim().isEmpty()) {
            return InstallSource.SIDELOADED;
        }
        if (PLAY_STORE_PACKAGE.equals(installerPackageName.trim())) {
            return InstallSource.PLAY_STORE;
        }
        return InstallSource.OTHER_INSTALLER;
    }

    /**
     * What to tell the operator.
     *
     * <p>Device-owner status is the only thing that decides whether there is a problem: a
     * correctly-provisioned kiosk needs no advice regardless of where it was installed from. The
     * install source only changes <em>which</em> instructions are useful, which is the whole reason
     * this is not a single generic warning.
     */
    static Advice adviceFor(InstallSource source, boolean deviceOwner) {
        if (deviceOwner) {
            return Advice.NONE;
        }
        if (source == InstallSource.PLAY_STORE) {
            return Advice.PLAY_INSTALL_NOT_PROVISIONED;
        }
        // A sideload and an unknown store are the same case in practice: adb is available and is the
        // route that always works.
        return Advice.SIDELOAD_NOT_PROVISIONED;
    }

    /**
     * Convenience for callers that have both raw facts.
     *
     * <p>Never throws and never returns null, because it is called on the startup path of a device
     * that must come up unattended: a kiosk that refuses to start because it cannot work out how it
     * was installed would be a worse bug than the one this class exists to report.
     */
    static Advice adviceForInstaller(String installerPackageName, boolean deviceOwner) {
        return adviceFor(classify(installerPackageName), deviceOwner);
    }
}
