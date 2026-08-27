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

# The version identity comes from git (see the comment in app/build.gradle), but the container
# has no git, so both values are computed here on the host and passed in. Absent when this is not
# a git checkout, and that is fine: the build falls back to a value that is obviously no release.
version_args=()
if version_name=$(git -C "${project_dir}" describe --tags --always --dirty 2>/dev/null); then
    version_args+=(--env "MURALIS_VERSION_NAME=${version_name#v}")
fi
if version_code=$(git -C "${project_dir}" rev-list --count HEAD 2>/dev/null); then
    version_args+=(--env "MURALIS_VERSION_CODE=${version_code}")
fi

# The licensing public key and the debug-only Pro override, forwarded when set. The container
# inherits nothing from this shell, so a key exported on the host is invisible to Gradle without
# this. Absent is a normal development state: the build succeeds, purchases cannot be verified,
# and app/build.gradle refuses a *release* built that way. The key is read from a file beside the
# upload key when the variable is not already exported, so an unattended release build works the
# same way the keystore password does.
entitlement_args=()
license_key=${MURALIS_LICENSE_KEY:-}
if [[ -z ${license_key} && -f "${key_dir}/license-key.txt" ]]; then
    license_key=$(<"${key_dir}/license-key.txt")
fi
# Play Console prints the key wrapped across lines; base64 of a DER key has no newlines in it, so
# strip all whitespace rather than trusting however it was pasted.
license_key=${license_key//[[:space:]]/}
if [[ -n ${license_key} ]]; then
    entitlement_args+=(--env "MURALIS_LICENSE_KEY=${license_key}")
fi
if [[ -n ${MURALIS_PRO_OVERRIDE:-} ]]; then
    entitlement_args+=(--env "MURALIS_PRO_OVERRIDE=${MURALIS_PRO_OVERRIDE}")
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
    "${version_args[@]+"${version_args[@]}"}" \
    "${entitlement_args[@]+"${entitlement_args[@]}"}" \
    "${image_name}" \
    gradle --project-dir /src --no-daemon "$@"
