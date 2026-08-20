#!/usr/bin/env bash
#
# Build the Android/Gradle builder image and provision the SDK packages into the
# bind-mounted SDK root. Run this once per clone, and again after editing
# build/Containerfile.android or the package list below.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image_name=kiosk-android-builder:36

# compileSdk/targetSdk 36 (Android 16), not the 34 this project's notes
# originally assumed: from 2026-08-31 Google Play requires new apps and updates
# to target API 36. build-tools tracks the same level. Re-verify against
# https://developer.android.com/google/play/requirements/target-sdk before a
# submission attempt; the floor moves every August.
sdk_packages=(
    "platform-tools"
    "platforms;android-36"
    "build-tools;36.0.0"
)

podman build \
    --tag "${image_name}" \
    --file "${project_dir}/build/Containerfile.android" \
    "${project_dir}/build"

mkdir -p "${project_dir}/.android-sdk" "${project_dir}/.gradle"

# `yes` feeds the licence prompts, which sdkmanager offers no non-interactive
# flag for. Licences are written into the SDK root, so they persist on the mount.
podman run --rm \
    --userns=keep-id \
    --security-opt label=disable \
    --volume "${project_dir}/.android-sdk:/sdk" \
    --volume "${project_dir}/.gradle:/gradle" \
    "${image_name}" \
    bash -lc "yes | sdkmanager --sdk_root=/sdk --licenses >/dev/null \
        && sdkmanager --sdk_root=/sdk ${sdk_packages[*]@Q}"

printf 'KiOSk Android builder ready: %s\n' "${image_name}"
