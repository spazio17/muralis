#!/usr/bin/env bash
#
# Serve provisioning/ over plain HTTP so a tablet in out-of-box setup can download the APK.
#
# Plain HTTP on purpose: Android's provisioning downloader will accept it, the payload pins the
# APK's signing certificate so the download is verified regardless of transport, and requiring TLS
# here would mean getting a certificate the tablet trusts onto a device that has not finished setup.
# Only run this on a trusted LAN, and stop it when provisioning is done.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
port=${1:-8099}

if [[ ! -f ${project_dir}/provisioning/kiosk.apk ]]; then
    printf 'No provisioning/kiosk.apk; run scripts/make-provisioning-qr.sh first.\n' >&2
    exit 1
fi

host_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP '(?<=src\s)\d+(\.\d+){3}' | head -1)
printf 'Serving %s on http://%s:%s/kiosk.apk\n' \
    "${project_dir}/provisioning" "${host_ip:-<this-host>}" "${port}"
printf 'Leave this running while the tablet provisions. Ctrl-C to stop.\n\n'

cd "${project_dir}/provisioning"
exec python3 -m http.server "${port}" --bind 0.0.0.0
