# shellcheck shell=bash
#
# The builder image and the podman options shared by every script that runs it:
# gradle.sh, adb.sh, build-container.sh and test-host.sh source this file, so the
# tag changes in one place. Not a script to run.
#
# keep-id: what the container writes on the mounts belongs to the user running it.
# label=disable: the mounts are plain directories under the home, which SELinux
# would otherwise keep the container out of.

builder_image=kiosk-android-builder:36
builder_run_args=(--userns=keep-id --security-opt label=disable)
