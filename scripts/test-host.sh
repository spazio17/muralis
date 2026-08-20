#!/usr/bin/env bash
#
# Fast validation that needs neither the container, the Android SDK, nor a
# device. Mirrors ../wall-kiosk-rom/scripts/test-host.sh: the point is that the
# pure-Java logic (KioskCommandDispatcher, SystemStats, RecyclePolicy,
# EscapeSequence) stays free of Android imports and testable in seconds.
#
# Grow the javac blocks below as those files are ported. Right now only the
# skeleton exists, so there is nothing to compile here yet and this script
# checks the build plumbing instead of pretending to test logic it does not have.

set -euo pipefail

project_dir=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
test_dir=$(mktemp -d /tmp/kiosk-app-host-test.XXXXXX)
trap 'rm -rf "${test_dir}"' EXIT

bash -n "${project_dir}"/scripts/*.sh

xmllint --noout \
    "${project_dir}/app/src/main/AndroidManifest.xml" \
    "${project_dir}"/app/src/main/res/values/*.xml \
    "${project_dir}"/app/src/main/res/xml/*.xml

# Guard the decisions that are settled and easy to undo by accident: no
# privileged permission may reappear in the manifest, because none of them can
# be granted to a Play-installed, non-platform-signed APK.
forbidden=(
    android.permission.DEVICE_POWER
    android.permission.REBOOT
    android.permission.STATUS_BAR
    android.permission.WRITE_SECURE_SETTINGS
    lineageos.permission.FINISH_SETUP
)
for permission in "${forbidden[@]}"; do
    # Match the attribute, not a bare mention: the manifest's own comment
    # explains why each of these is absent, and that must not trip the check.
    if grep -q "android:name=\"${permission}\"" \
        "${project_dir}/app/src/main/AndroidManifest.xml"; then
        printf 'Manifest requests %s, which no unprivileged APK can hold.\n' \
            "${permission}" >&2
        exit 1
    fi
done

# Same idea for the API-26 traps: any setLockTaskFeatures or WindowInsetsController
# call site must sit near an SDK_INT check, since the MediaPad is API 26.
# `grep -vE` drops comment lines before the scan: a javadoc paragraph explaining *why* a call is
# guarded is not itself a call site, and flagging one sends you looking for a bug that is not there.
while IFS=: read -r file line _; do
    [[ -z ${file:-} ]] && continue
    if ! sed -n "$((line > 14 ? line - 14 : 1)),$((line + 6))p" "${file}" \
        | grep -q 'SDK_INT'; then
        printf '%s:%s uses an API 28+/30+ call with no nearby SDK_INT guard.\n' \
            "${file}" "${line}" >&2
        exit 1
    fi
done < <(grep -rn 'setLockTaskFeatures\|WindowInsetsController\|setDecorFitsSystemWindows' \
    "${project_dir}/app/src/main/java" \
    | grep -vE ':[[:space:]]*(\*|//|/\*)' || true)

# Pure-Java sources must not drag in Android, or they stop being host-testable.
pure_java_dir=${project_dir}/app/src/main/java/org/kiosk/launcher
host_test_dir=${project_dir}/app/src/test-host/java/org/kiosk/launcher
for name in KioskCommandDispatcher SystemStats RecyclePolicy EscapeSequence KioskRuntimeState \
            Provisioning TelemetryInterval; do
    source_file=${pure_java_dir}/${name}.java
    [[ -f ${source_file} ]] || continue
    if grep -q '^import android\.' "${source_file}"; then
        printf '%s imports android.*; it must stay host-testable.\n' \
            "${source_file}" >&2
        exit 1
    fi
done

# The real host tests, ported from ../wall-kiosk-rom/scripts/test-host.sh. These are plain
# main() classes rather than JUnit, deliberately: they run under a bare `java` in seconds with
# no test runner, no Android, and no Gradle, which is the whole point of keeping this logic free
# of Android imports. They are NOT part of the Gradle build for the same reason.
mkdir -p "${test_dir}/dispatcher" "${test_dir}/stats"

javac -d "${test_dir}/dispatcher" \
    "${pure_java_dir}/KioskCommandDispatcher.java" \
    "${host_test_dir}/KioskCommandDispatcherTest.java"
java -cp "${test_dir}/dispatcher" org.kiosk.launcher.KioskCommandDispatcherTest

mkdir -p "${test_dir}/provisioning"
javac -d "${test_dir}/provisioning" \
    "${pure_java_dir}/Provisioning.java" \
    "${host_test_dir}/ProvisioningTest.java"
java -cp "${test_dir}/provisioning" org.kiosk.launcher.ProvisioningTest

javac -d "${test_dir}/stats" \
    "${pure_java_dir}/SystemStats.java" \
    "${pure_java_dir}/RecyclePolicy.java" \
    "${pure_java_dir}/EscapeSequence.java" \
    "${pure_java_dir}/TelemetryInterval.java" \
    "${host_test_dir}/SystemStatsTest.java" \
    "${host_test_dir}/RecyclePolicyTest.java" \
    "${host_test_dir}/EscapeSequenceTest.java" \
    "${host_test_dir}/TelemetryIntervalTest.java"
java -cp "${test_dir}/stats" org.kiosk.launcher.SystemStatsTest
java -cp "${test_dir}/stats" org.kiosk.launcher.RecyclePolicyTest
java -cp "${test_dir}/stats" org.kiosk.launcher.EscapeSequenceTest
java -cp "${test_dir}/stats" org.kiosk.launcher.TelemetryIntervalTest

printf 'KiOSk app host validation passed\n'
