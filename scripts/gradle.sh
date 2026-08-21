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

# The release upload key, if this machine has one. Deliberately outside the project: the
# repo is public and .gradle/ is documented as regenerable, so neither is a place for it.
# Mounted read-only, and the password is passed as an environment variable rather than
# written anywhere Gradle could log or cache it.
#
# Absent on a machine that has not been given the key, and that is fine: assembleRelease
# still shrinks and still builds, it just emits an unsigned APK. Nobody needs the secret to
# reproduce the release build.
#
# MURALIS_KEY_DIR overrides the location. The password is read from a 0600 file beside the
# keystore when MURALIS_KEYSTORE_PASSWORD is not already set, so an unattended build works;
# export the variable instead and delete that file if you would rather not have the password
# on disk at all.
key_dir=${MURALIS_KEY_DIR:-${HOME}/keys/muralis}
key_args=()
if [[ -f "${key_dir}/muralis-upload.p12" ]]; then
    if [[ -z ${MURALIS_KEYSTORE_PASSWORD:-} && -f "${key_dir}/.pw" ]]; then
        MURALIS_KEYSTORE_PASSWORD=$(<"${key_dir}/.pw")
    fi
    key_args=(
        --volume "${key_dir}/muralis-upload.p12:/keys/muralis-upload.p12:ro"
        --env "MURALIS_KEYSTORE=/keys/muralis-upload.p12"
        --env "MURALIS_KEYSTORE_PASSWORD=${MURALIS_KEYSTORE_PASSWORD:-}"
        --env "MURALIS_KEY_ALIAS=${MURALIS_KEY_ALIAS:-muralis-upload}"
    )
fi

# --no-daemon: each invocation is a fresh short-lived container, so a lingering
# daemon would be killed with it anyway and only costs startup work.
podman run --rm --interactive \
    --userns=keep-id \
    --security-opt label=disable \
    --volume "${project_dir}:/src" \
    --volume "${project_dir}/.android-sdk:/sdk" \
    --volume "${project_dir}/.gradle:/gradle" \
    "${key_args[@]+"${key_args[@]}"}" \
    "${image_name}" \
    gradle --project-dir /src --no-daemon "$@"
