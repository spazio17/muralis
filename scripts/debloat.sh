#!/usr/bin/env bash
#
# Replay the package removals recorded in device-baseline/packages-you-removed.txt.
#
# That list was recovered from the tablet before its factory reset, by diffing `pm list packages`
# against `pm list packages -u`, so it is what was actually removed rather than a guess at what
# should be. Regenerate it with scripts/export-device-baseline.sh if the set ever changes.
#
# `pm uninstall --user 0` rather than a real uninstall, on purpose: it removes the package for the
# primary user while leaving it in the system image, so a factory reset brings back anything that
# turns out to be load-bearing. On a device whose OEM software is undocumented that reversibility is
# worth more than the few MB a full removal would additionally reclaim.
#
# Usage: scripts/debloat.sh [list-file]
#        scripts/debloat.sh --dry-run [list-file]

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
adb="${project_dir}/scripts/adb.sh"

dry_run=false
if [[ ${1:-} == --dry-run ]]; then
    dry_run=true
    shift
fi
list=${1:-${project_dir}/device-baseline/packages-you-removed.txt}

[[ -f ${list} ]] || { printf 'No package list at %s\n' "${list}" >&2; exit 1; }

# </dev/null is essential, not tidiness: scripts/adb.sh runs `podman run --interactive`, so without
# it the adb call inherits and drains the loop's stdin. The first version of this script removed
# exactly one package and exited cleanly, because adb had swallowed the remaining 31 lines of the
# list. The loop below also reads on fd 3 for the same reason, belt and braces.
ashell() { "${adb}" shell "$@" </dev/null 2>/dev/null | tr -d '\r' | grep -v '^\*' || true; }

ashell getprop ro.serialno | grep -q . \
    || { printf 'No device over adb.\n' >&2; exit 1; }

# Never remove these, whatever a list says. The launcher and Settings are the escape hatch Muralis
# releases lock task for; losing either turns a recoverable kiosk into a tablet that can only be
# fixed by another factory reset.
protected='^(com\.huawei\.android\.launcher|com\.android\.settings|com\.android\.vending|org\.spazio17\.muralis|com\.google\.android\.gms|com\.android\.systemui)$'

# Plus the keyboard actually in use, resolved rather than hardcoded. Removing the active input method
# leaves a kiosk that cannot be configured at all: every field on the settings screen needs typing, and
# there is no way to install a replacement without one. Read from the live setting because which IME is
# active differs per device (this tablet uses SwiftKey, not the Huawei one).
active_ime=$(ashell settings get secure default_input_method | tail -1 | cut -d/ -f1)
if [[ -n ${active_ime} && ${active_ime} != null ]]; then
    protected="${protected%$}|^${active_ime//./\\.}$"
    printf 'Protecting the active keyboard: %s\n\n' "${active_ime}"
fi

installed=$(ashell pm list packages | sed 's/^package://' | sort)

removed=0 skipped=0 failed=0
while read -r package <&3; do
    [[ -z ${package} || ${package} == \#* ]] && continue

    if [[ ${package} =~ ${protected} ]]; then
        printf 'PROTECTED %s (refusing, this is an escape-hatch package)\n' "${package}"
        skipped=$((skipped + 1))
        continue
    fi
    if ! grep -qx "${package}" <<<"${installed}"; then
        printf 'ABSENT    %s\n' "${package}"
        skipped=$((skipped + 1))
        continue
    fi
    if ${dry_run}; then
        printf 'WOULD     %s\n' "${package}"
        continue
    fi

    if [[ $(ashell pm uninstall --user 0 "${package}" | tail -1) == Success ]]; then
        printf 'REMOVED   %s\n' "${package}"
        removed=$((removed + 1))
    else
        printf 'FAILED    %s\n' "${package}"
        failed=$((failed + 1))
    fi
done 3< "${list}"

printf '\n%s: %d removed, %d skipped, %d failed\n' \
    "$(${dry_run} && echo 'Dry run' || echo 'Debloat')" "${removed}" "${skipped}" "${failed}"
if [[ ${failed} -gt 0 ]]; then
    printf 'A failure is usually a package the system marks non-removable; harmless to leave.\n'
fi
printf 'Restore any single package with: scripts/adb.sh shell cmd package install-existing <pkg>\n'
