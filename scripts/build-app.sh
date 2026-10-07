#!/usr/bin/env bash
#
# Build the debug APK and report where it landed.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

"${project_dir}/scripts/gradle.sh" assembleDebug

apk=${project_dir}/app/build/outputs/apk/debug/app-debug.apk
if [[ ! -f "${apk}" ]]; then
    printf 'Gradle reported success but %s is missing.\n' "${apk}" >&2
    exit 1
fi

printf 'APK: %s (%s bytes)\n' "${apk}" "$(stat -c %s "${apk}")"
printf 'Install and provision as device owner with:\n'
printf '  adb install -r %s\n' "${apk}"
printf '  adb shell dpm set-device-owner org.spazio17.muralis/.KioskDeviceAdminReceiver\n'
