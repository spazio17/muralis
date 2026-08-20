#!/usr/bin/env bash
#
# Snapshot which packages are on the attached device, into device-baseline/.
#
# Worth being a script rather than a one-off command because of one specific trick it encodes:
# `pm list packages` shows what is installed for the user, while `pm list packages -u` also includes
# packages uninstalled *for the user* but still present in the system image. The difference between
# those two sets is exactly the list of OEM packages somebody removed by hand, which is otherwise
# unrecoverable information and is destroyed by a factory reset.
#
# Run this BEFORE any factory reset. Run it again after, to a different directory, to diff what the
# ROM put back:
#   scripts/export-device-baseline.sh device-baseline-after-reset

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
adb="${project_dir}/scripts/adb.sh"
out_dir=${1:-${project_dir}/device-baseline}

ashell() { "${adb}" shell "$@" 2>/dev/null | tr -d '\r' | grep -v '^\*' || true; }

ashell getprop ro.serialno | grep -q . \
    || { printf 'No device over adb.\n' >&2; exit 1; }

mkdir -p "${out_dir}"
packages() { ashell pm list packages "$@" | sed 's/^package://' | sort; }

packages          > "${out_dir}/packages-enabled.txt"
packages -u       > "${out_dir}/packages-including-uninstalled.txt"
packages -s       > "${out_dir}/packages-system.txt"
packages -3       > "${out_dir}/packages-thirdparty.txt"
packages -f       > "${out_dir}/packages-with-paths.txt"

# The valuable one: present in the image, removed for the user.
comm -13 "${out_dir}/packages-enabled.txt" "${out_dir}/packages-including-uninstalled.txt" \
    > "${out_dir}/packages-you-removed.txt"

{
    printf 'device      %s\n' "$(ashell getprop ro.product.model | tail -1)"
    printf 'serial      %s\n' "$(ashell getprop ro.serialno | tail -1)"
    printf 'android     %s (API %s)\n' \
        "$(ashell getprop ro.build.version.release | tail -1)" \
        "$(ashell getprop ro.build.version.sdk | tail -1)"
    printf 'build       %s\n' "$(ashell getprop ro.build.display.id | tail -1)"
    printf 'webview     %s\n' \
        "$(ashell dumpsys webviewupdate | grep -i 'Current WebView package' | head -1)"
} > "${out_dir}/device.txt"

printf 'Wrote %s\n' "${out_dir}"
wc -l "${out_dir}"/*.txt
printf '\npackages-you-removed.txt is the set to replay with scripts/debloat.sh\n'
