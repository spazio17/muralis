#!/usr/bin/env bash
#
# Make Muralis the device owner over adb. The route that always works, and the fallback for when QR
# enrolment during out-of-box setup is unavailable (Huawei's EMUI may not carry Google's six-tap
# gesture on its welcome screen).
#
# Two preconditions Android enforces, both checked below before anything is changed:
#   1. No accounts configured. Not "no Google account", *no accounts at all*.
#   2. No existing device or profile owner.
#
# And one it enforces that has to be worked around: it refuses to set a device owner once user setup
# is marked complete. The two provisioning flags are therefore cleared for the duration of the call
# and restored immediately afterwards, including on failure, via a trap. Leaving them at 0 sends the
# tablet back into first-run setup on its next launch, which is the trap the ROM repo's
# docs/kiosk-device-owner.md warns about; that is why the restore is not merely the last line.
#
# `adb shell settings put` works without root because the shell user already holds
# WRITE_SECURE_SETTINGS, so this needs no unlocked bootloader and no su.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
adb="${project_dir}/scripts/adb.sh"
component=org.spazio17.muralis/.KioskDeviceAdminReceiver

step() { printf '\n== %s\n' "$1"; }
fail() { printf '\nFAILED: %s\n' "$1" >&2; exit 1; }

# adb output carries CRs and the wrapper's own daemon chatter; strip both everywhere.
ashell() { "${adb}" shell "$@" 2>/dev/null | tr -d '\r' | grep -v '^\*' || true; }

step "Device"
serial=$(ashell getprop ro.serialno | tail -1)
[[ -n ${serial} ]] || fail "no device over adb; enable USB debugging and accept the RSA prompt"
printf 'serial %s, Android %s (API %s)\n' "${serial}" \
    "$(ashell getprop ro.build.version.release | tail -1)" \
    "$(ashell getprop ro.build.version.sdk | tail -1)"

step "Is Muralis installed?"
if ! ashell pm list packages | grep -qx 'package:org.spazio17.muralis'; then
    printf 'not installed; installing\n'
    "${adb}" install -r "${project_dir}/provisioning/kiosk.apk" >/dev/null \
        || fail "could not install the APK"
fi
printf 'org.spazio17.muralis present\n'

step "Precondition: no accounts configured"
accounts=$(ashell dumpsys account | grep -cE '^[[:space:]]+Account \{' || true)
if [[ ${accounts} -ne 0 ]]; then
    ashell dumpsys account | grep -E '^[[:space:]]+Account \{' || true
    fail "${accounts} account(s) configured. Android refuses a device owner while any account exists.
Remove every account (Settings > Users & accounts) and re-run, or factory reset. Accounts can be
signed back in *after* provisioning, so this is not a permanent trade."
fi
printf 'none\n'

step "Precondition: no existing device or profile owner"
if ashell dumpsys device_policy | grep -qE 'Device Owner:|Profile Owner'; then
    ashell dumpsys device_policy | grep -E 'Device Owner:|Profile Owner' || true
    fail "an owner is already set; remove it with 'dpm remove-active-admin <component>' first"
fi
printf 'none\n'

step "Provisioning flags"
was_provisioned=$(ashell settings get global device_provisioned | tail -1)
was_setup_complete=$(ashell settings get secure user_setup_complete | tail -1)
printf 'device_provisioned=%s user_setup_complete=%s\n' \
    "${was_provisioned}" "${was_setup_complete}"

# Restore on ANY exit path. A device left with these at 0 re-enters first-run setup, which looks
# exactly like a bricked kiosk and is the single most likely way this script could do harm.
restore_flags() {
    printf '\n== Restoring provisioning flags\n'
    if [[ ${was_provisioned} =~ ^[0-9]+$ ]]; then
        ashell settings put global device_provisioned "${was_provisioned}" >/dev/null
    else
        ashell settings put global device_provisioned 1 >/dev/null
    fi
    if [[ ${was_setup_complete} =~ ^[0-9]+$ ]]; then
        ashell settings put secure user_setup_complete "${was_setup_complete}" >/dev/null
    else
        ashell settings put secure user_setup_complete 1 >/dev/null
    fi
    printf 'device_provisioned=%s user_setup_complete=%s\n' \
        "$(ashell settings get global device_provisioned | tail -1)" \
        "$(ashell settings get secure user_setup_complete | tail -1)"
}
trap restore_flags EXIT

step "Clearing flags and setting the device owner"
ashell settings put global device_provisioned 0 >/dev/null
ashell settings put secure user_setup_complete 0 >/dev/null
dpm_output=$(ashell dpm set-device-owner "${component}")
printf '%s\n' "${dpm_output}"

# Restore now, explicitly, so the verification below reads the device in its final state rather
# than mid-provisioning. The trap is cleared first so the flags are not written twice.
trap - EXIT
restore_flags

step "Verification"
if ashell dumpsys device_policy | grep -qE 'Device Owner:'; then
    ashell dumpsys device_policy | grep -A4 -E 'Device Owner:' || true
    printf '\nMuralis is now device owner. Restart it so the policy is applied:\n'
    printf '  scripts/adb.sh shell am force-stop org.spazio17.muralis\n'
    printf '  scripts/adb.sh shell am start -n org.spazio17.muralis/.KioskActivity\n'
    printf '\nThen confirm the hardening actually engaged:\n'
    printf '  scripts/adb.sh shell dumpsys activity | grep -A3 LockTaskController\n'
else
    fail "dpm reported no error but no Device Owner is recorded; see the dpm output above"
fi
