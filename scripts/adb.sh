#!/usr/bin/env bash
#
# Run adb from the builder image against a USB-attached device.
#
# The host has no adb and is not meant to get one; the SDK's platform-tools on
# the .android-sdk mount is the only copy. USB needs the raw device nodes, hence
# the /dev/bus/usb mount: adb claims the interface directly. The whole directory
# rather than a single --device node, because the bus and device numbers change
# on every replug.
#
# Deliberately NOT --privileged, which is what this used at first. --privileged
# mounts every host device node, so an unrelated stale one failed the entire
# container: "crun: mount `/dev/sr0`: No such file or directory" from an empty
# optical drive, mid-session, with the tablet attached and working. Mounting only
# the USB bus is narrower and does not depend on the rest of /dev being sane.
#
# Note the adb server runs *inside* the container and dies with it, so each call
# restarts it. That costs ~1s and avoids a stray daemon holding the interface.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image_name=kiosk-android-builder:36

podman run --rm --interactive \
    --userns=keep-id \
    --security-opt label=disable \
    --volume /dev/bus/usb:/dev/bus/usb \
    --volume "${project_dir}:/src" \
    --volume "${project_dir}/.android-sdk:/sdk" \
    --volume "${project_dir}/.gradle:/gradle" \
    "${image_name}" \
    /sdk/platform-tools/adb "$@"
