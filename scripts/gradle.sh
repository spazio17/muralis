#!/usr/bin/env bash
#
# Run Gradle inside the builder image against this project. All arguments are
# passed straight through, so this is the entry point for every Gradle task:
#   scripts/gradle.sh assembleDebug
#   scripts/gradle.sh tasks
#   scripts/gradle.sh --no-daemon lint

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image_name=kiosk-android-builder:36

if [[ ! -d "${project_dir}/.android-sdk/platforms" ]]; then
    printf 'SDK not provisioned; run scripts/build-container.sh first.\n' >&2
    exit 1
fi

# The debug keystore is created here rather than left to AGP: see the comment on
# signingConfigs.debug in app/build.gradle for why AGP's default location does not
# survive in this container. Generated once, then reused, so rebuilds stay
# install-compatible with what is already on the tablet.
keystore="${project_dir}/.gradle/kiosk-debug.keystore"
if [[ ! -f ${keystore} ]]; then
    printf 'Creating the debug keystore (once): %s\n' "${keystore}"
    podman run --rm \
        --userns=keep-id \
        --security-opt label=disable \
        --volume "${project_dir}/.gradle:/gradle" \
        "${image_name}" \
        keytool -genkeypair -noprompt \
            -keystore /gradle/kiosk-debug.keystore \
            -storepass android -keypass android \
            -alias androiddebugkey \
            -dname 'CN=Android Debug,O=Android,C=US' \
            -keyalg RSA -keysize 2048 -validity 10950
fi

# --no-daemon: each invocation is a fresh short-lived container, so a lingering
# daemon would be killed with it anyway and only costs startup work.
podman run --rm --interactive \
    --userns=keep-id \
    --security-opt label=disable \
    --volume "${project_dir}:/src" \
    --volume "${project_dir}/.android-sdk:/sdk" \
    --volume "${project_dir}/.gradle:/gradle" \
    "${image_name}" \
    gradle --project-dir /src --no-daemon "$@"
