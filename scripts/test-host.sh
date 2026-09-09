#!/usr/bin/env bash
#
# Fast validation that needs neither the container, the Android SDK, nor a
# device. Mirrors ../wall-kiosk-rom/scripts/test-host.sh: the point is that the
# pure-Java logic (KioskCommandDispatcher, SystemStats, RecyclePolicy,
# EscapeSequence) stays free of Android imports and testable in seconds.
#
# Grow the javac blocks below as more logic becomes host-testable. They currently
# compile and run these suites: dispatcher, provisioning, request origin, system
# bar overlap, display-off policy, auth throttle, purchase signature, MQTT connect
# retry, system stats, recycle policy, recovery policy, server probe policy,
# escape sequence and relaunch policy.

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
for name in KioskCommandDispatcher SystemStats RecyclePolicy RecoveryPolicy \
            ServerProbePolicy EscapeSequence KioskRuntimeState \
            Provisioning RequestOrigin AuthThrottle PurchaseSignature \
            SystemBarOverlap DisplayOffPolicy MqttConnectRetry; do
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
    "${pure_java_dir}/DisplayOffPolicy.java" \
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

# Where the corner tap targets land, per device, from whatever the platform reports. Extracted
# from the activity because three of its four cases need a device with visible system bars, and
# the wall panel is not always attached.
mkdir -p "${test_dir}/overlap"
javac -d "${test_dir}/overlap" \
    "${pure_java_dir}/SystemBarOverlap.java" \
    "${host_test_dir}/SystemBarOverlapTest.java"
java -cp "${test_dir}/overlap" org.spazio17.muralis.SystemBarOverlapTest

# Which "display off" a panel gets, and how a sleep that ended is judged. Extracted because the
# rows need a tablet on battery, off the allowlist, mid-update or mid-restart, one per row.
mkdir -p "${test_dir}/displayoff"
javac -d "${test_dir}/displayoff" \
    "${pure_java_dir}/DisplayOffPolicy.java" \
    "${host_test_dir}/DisplayOffPolicyTest.java"
java -cp "${test_dir}/displayoff" org.spazio17.muralis.DisplayOffPolicyTest

# The wait between broker connection attempts after one failed outright, which Paho's own
# reconnect does not cover. Extracted because testing it for real means stopping a broker and
# waiting up to five minutes per row.
mkdir -p "${test_dir}/mqttretry"
javac -d "${test_dir}/mqttretry" \
    "${pure_java_dir}/MqttConnectRetry.java" \
    "${host_test_dir}/MqttConnectRetryTest.java"
java -cp "${test_dir}/mqttretry" org.spazio17.muralis.MqttConnectRetryTest

mkdir -p "${test_dir}/throttle"
javac -d "${test_dir}/throttle" \
    "${pure_java_dir}/AuthThrottle.java" \
    "${host_test_dir}/AuthThrottleTest.java"
java -cp "${test_dir}/throttle" org.spazio17.muralis.AuthThrottleTest

# The paid unlock's integrity check, against RSA key pairs generated by the test itself, so it
# proves acceptance and refusal against real signatures rather than fixtures.
mkdir -p "${test_dir}/signature"
javac -d "${test_dir}/signature" \
    "${pure_java_dir}/PurchaseSignature.java" \
    "${host_test_dir}/PurchaseSignatureTest.java"
java -cp "${test_dir}/signature" org.spazio17.muralis.PurchaseSignatureTest

javac -d "${test_dir}/stats" \
    "${pure_java_dir}/SystemStats.java" \
    "${pure_java_dir}/RecyclePolicy.java" \
    "${pure_java_dir}/RecoveryPolicy.java" \
    "${pure_java_dir}/ServerProbePolicy.java" \
    "${pure_java_dir}/EscapeSequence.java" \
    "${pure_java_dir}/RelaunchPolicy.java" \
    "${host_test_dir}/SystemStatsTest.java" \
    "${host_test_dir}/RecyclePolicyTest.java" \
    "${host_test_dir}/RecoveryPolicyTest.java" \
    "${host_test_dir}/ServerProbePolicyTest.java" \
    "${host_test_dir}/EscapeSequenceTest.java" \
    "${host_test_dir}/RelaunchPolicyTest.java"
java -cp "${test_dir}/stats" org.spazio17.muralis.SystemStatsTest
java -cp "${test_dir}/stats" org.spazio17.muralis.RecyclePolicyTest
java -cp "${test_dir}/stats" org.spazio17.muralis.RecoveryPolicyTest
java -cp "${test_dir}/stats" org.spazio17.muralis.ServerProbePolicyTest
java -cp "${test_dir}/stats" org.spazio17.muralis.EscapeSequenceTest
java -cp "${test_dir}/stats" org.spazio17.muralis.RelaunchPolicyTest

# The admin page's JavaScript lives in res/raw as real files (it used to be Java string
# literals, where a syntax error or a name collision shipped and only showed up as a blank box
# in somebody's browser; both happened, see admin_stats.js for the "pct" shadowing story). Real
# files get editor support, but still nothing executes them before a browser does, so this keeps
# checking the two failure classes that shipped: bracket/quote balance, and a function shadowed
# by a var of the same name (var hoists to the top of the enclosing function, so the shadow wins
# even where the declaration sits below the call).
python3 - "${project_dir}/app/src/main/res/raw" <<'PYCHECK'
import os
import re
import sys

raw_dir = sys.argv[1]
failures = []
checked = 0

def strip_comments(text):
    out = []
    i, n, quote = 0, len(text), None
    while i < n:
        c = text[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == quote:
                quote = None
            i += 1; continue
        if c in "'\"":
            quote = c; out.append(c); i += 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)

for name in sorted(os.listdir(raw_dir)):
    if not (name.startswith("admin") and (name.endswith(".js") or name.endswith(".css"))):
        continue
    checked += 1
    js = strip_comments(open(os.path.join(raw_dir, name), encoding="utf-8").read())

    # Balance, which catches a dropped quote or bracket.
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

    if not name.endswith(".js"):
        continue
    # A function shadowed by a var of the same name.
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

if checked < 5:
    # Four scripts plus the stylesheet. A rename that stops them matching here would otherwise
    # turn this whole check into a silent no-op.
    failures.append("expected at least 5 admin assets, found %d" % checked)
if failures:
    for failure in failures:
        print("admin page asset: " + failure, file=sys.stderr)
    raise SystemExit(1)
print("admin page assets checked: balance and no shadowed helpers (%d files)" % checked)
PYCHECK

printf 'Muralis app host validation passed\n'
