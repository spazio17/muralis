#!/usr/bin/env bash
#
# Copies the canonical legal texts into res/raw.
#
# The privacy policy and the terms have ONE editable source: legal/privacy.txt and
# legal/terms.txt in the public muralis-site repo (decided 2026-08-25). This app bundles
# verbatim copies so the documents stay readable on a kiosk with no browser and no
# internet route; the site's HTML pages are generated from the same files by that repo's
# scripts/generate-legal.py. Edit the texts THERE, run this, commit both repos.
#
# The release workflow byte-compares res/raw against the site repo's main branch and
# refuses to release while they differ, so forgetting this step cannot ship.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
site_dir=${1:-"${project_dir}/../muralis-site"}

if [[ ! -f "${site_dir}/legal/privacy.txt" ]]; then
    printf 'No canonical texts at %s/legal/; pass the muralis-site checkout as the first argument.\n' \
        "${site_dir}" >&2
    exit 1
fi

changed=0
# Same file names on both sides, on purpose: res/raw/privacy_policy.txt was renamed to
# privacy.txt (2026-08-25) so this loop, and the release workflow's check, need no name
# mapping.
for name in privacy.txt terms.txt; do
    source_file="${site_dir}/legal/${name}"
    target_file="${project_dir}/app/src/main/res/raw/${name}"
    if cmp -s "${source_file}" "${target_file}"; then
        printf 'res/raw/%s already matches legal/%s\n' "${name}" "${name}"
    else
        cp "${source_file}" "${target_file}"
        printf 'res/raw/%s updated from legal/%s\n' "${name}" "${name}"
        changed=1
    fi
done

if [[ ${changed} -eq 1 ]]; then
    printf 'Review with git diff, then commit.\n'
fi
