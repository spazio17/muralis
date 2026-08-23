#!/usr/bin/env bash
#
# Fast validation that needs neither the container, the Android SDK, nor a
# device. Mirrors ../wall-kiosk-rom/scripts/test-host.sh: the point is that the
# pure-Java logic (KioskCommandDispatcher, SystemStats, RecyclePolicy,
# EscapeSequence) stays free of Android imports and testable in seconds.
#
# Grow the javac blocks below as more logic becomes host-testable. They currently
# compile and run nine suites: dispatcher, provisioning, request origin,
# auth throttle, system stats, recycle policy, server probe policy, escape
# sequence and telemetry interval.

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
pure_java_dir=${project_dir}/app/src/main/java/org/spazio17/muralis
host_test_dir=${project_dir}/app/src/test-host/java/org/spazio17/muralis
for name in KioskCommandDispatcher SystemStats RecyclePolicy ServerProbePolicy \
            EscapeSequence KioskRuntimeState Provisioning RequestOrigin \
            AuthThrottle; do
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
java -cp "${test_dir}/dispatcher" org.spazio17.muralis.KioskCommandDispatcherTest

mkdir -p "${test_dir}/provisioning"
javac -d "${test_dir}/provisioning" \
    "${pure_java_dir}/Provisioning.java" \
    "${host_test_dir}/ProvisioningTest.java"
java -cp "${test_dir}/provisioning" org.spazio17.muralis.ProvisioningTest

mkdir -p "${test_dir}/origin"
javac -d "${test_dir}/origin" \
    "${pure_java_dir}/RequestOrigin.java" \
    "${host_test_dir}/RequestOriginTest.java"
java -cp "${test_dir}/origin" org.spazio17.muralis.RequestOriginTest

mkdir -p "${test_dir}/throttle"
javac -d "${test_dir}/throttle" \
    "${pure_java_dir}/AuthThrottle.java" \
    "${host_test_dir}/AuthThrottleTest.java"
java -cp "${test_dir}/throttle" org.spazio17.muralis.AuthThrottleTest

javac -d "${test_dir}/stats" \
    "${pure_java_dir}/SystemStats.java" \
    "${pure_java_dir}/RecyclePolicy.java" \
    "${pure_java_dir}/ServerProbePolicy.java" \
    "${pure_java_dir}/EscapeSequence.java" \
    "${host_test_dir}/SystemStatsTest.java" \
    "${host_test_dir}/RecyclePolicyTest.java" \
    "${host_test_dir}/ServerProbePolicyTest.java" \
    "${host_test_dir}/EscapeSequenceTest.java"
java -cp "${test_dir}/stats" org.spazio17.muralis.SystemStatsTest
java -cp "${test_dir}/stats" org.spazio17.muralis.RecyclePolicyTest
java -cp "${test_dir}/stats" org.spazio17.muralis.ServerProbePolicyTest
java -cp "${test_dir}/stats" org.spazio17.muralis.EscapeSequenceTest

# The admin page's JavaScript lives inside Java string literals, so nothing on the way to the
# device parses it: a syntax error or a name collision ships and only shows up as a blank box in
# somebody's browser. Both have happened. On 2026-08-23 a new "pct" helper was added to
# STATS_SCRIPT while render() already had "var pct = bat.percent" for the battery chip; var hoists
# to the top of the function, so the local shadowed the helper and every stats poll died with
# "pct is not a function". This reassembles each script constant and checks it.
python3 - "${project_dir}/app/src/main/java/org/spazio17/muralis/HttpAdminServer.java" <<'PYCHECK'
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read()
failures = []

for name in ("COMMAND_SCRIPT", "SETTING_SCRIPT", "STATS_SCRIPT", "THEME_SCRIPT"):
    start = source.index("private static final String %s = " % name)
    end = source.index('";\n', start)
    # Only the string literals, so the // comments between them are dropped.
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', source[start:end + 1])
    js = "".join(literals).encode().decode("unicode_escape")
    js = js.replace("<script>", "").replace("</script>", "")

    # Balance, which catches a dropped quote or bracket in the concatenation.
    depth = {"(": 0, "[": 0, "{": 0}
    closers = {")": "(", "]": "[", "}": "{"}
    quote = None
    escaped = False
    for character in js:
        if quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in "'\"":
            quote = character
        elif character in depth:
            depth[character] += 1
        elif character in closers:
            depth[closers[character]] -= 1
    if quote is not None:
        failures.append("%s: unterminated string literal" % name)
    for opener, count in depth.items():
        if count:
            failures.append("%s: unbalanced %s (%+d)" % (name, opener, count))

    # A function shadowed by a var of the same name. var is function-scoped and hoisted, so the
    # shadow wins for the whole enclosing function even where the declaration sits below the call.
    functions = set(re.findall(r"function\s+([A-Za-z_$][\w$]*)\s*\(", js))
    bound = set()
    for declaration in re.findall(r"\b(?:var|let|const)\s+([^;{}\n]+)", js):
        for piece in declaration.split(","):
            match = re.match(r"\s*([A-Za-z_$][\w$]*)\s*(=|$)", piece)
            if match:
                bound.add(match.group(1))
    for clash in sorted(functions & bound):
        failures.append(
            "%s: function %s() is shadowed by a var of the same name; rename one" % (name, clash))

if failures:
    for failure in failures:
        print("admin page script: " + failure, file=sys.stderr)
    raise SystemExit(1)
print("admin page scripts checked: balance and no shadowed helpers")
PYCHECK

printf 'Muralis app host validation passed\n'
