# shellcheck shell=bash
#
# What every script in this directory shares: where the project is, and the
# builder image with the podman options that run it. Sourced, never run, with
#   source "$(dirname "${BASH_SOURCE[0]}")/builder.sh"
# so the paths are worked out once, here, from this file's own location.
#
# keep-id: what the container writes on the mounts belongs to the user running it.
# label=disable: the mounts are plain directories under the home, which SELinux
# would otherwise keep the container out of.
#
# shellcheck disable=SC2034  # all four are read by the scripts that source this file

scripts_dir=$(CDPATH='' cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(CDPATH='' cd -- "${scripts_dir}/.." && pwd)
builder_image=kiosk-android-builder:36
builder_run_args=(--userns=keep-id --security-opt label=disable)
