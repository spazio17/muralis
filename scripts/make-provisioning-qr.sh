#!/usr/bin/env bash
#
# Build the QR payload for device-owner provisioning during out-of-box setup, and render it.
#
# Why this exists rather than a wiki note: the payload embeds the SHA-256 of the APK's *signing
# certificate*, base64url-encoded with the padding stripped. Get that wrong and provisioning fails
# late, on a freshly wiped tablet, with a message that does not say which field was wrong. Deriving
# it from the APK that is actually being served removes the chance of the two drifting apart.
#
# Usage: scripts/make-provisioning-qr.sh [host-ip] [port]
#   host-ip defaults to this machine's LAN address, which is where the APK is served from by
#   scripts/serve-provisioning.sh. The tablet must be able to reach it during setup.

set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image_name=kiosk-android-builder:36
out_dir=${project_dir}/provisioning

host_ip=${1:-$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP '(?<=src\s)\d+(\.\d+){3}' | head -1)}
port=${2:-8099}
if [[ -z ${host_ip} ]]; then
    printf 'Could not determine this host LAN IP; pass it explicitly.\n' >&2
    exit 1
fi

"${project_dir}/scripts/build-app.sh" >/dev/null
mkdir -p "${out_dir}"
cp "${project_dir}/app/build/outputs/apk/debug/app-debug.apk" "${out_dir}/kiosk.apk"

# The digest apksigner prints is already the SHA-256 of the DER certificate, which is exactly what
# Android compares against; it only needs re-encoding from hex to base64url.
cert_sha256_hex=$(podman run --rm \
    --userns=keep-id --security-opt label=disable \
    --volume "${out_dir}:/out" --volume "${project_dir}/.android-sdk:/sdk" \
    "${image_name}" \
    /sdk/build-tools/36.0.0/apksigner verify --print-certs /out/kiosk.apk \
    | grep -oP '(?<=certificate SHA-256 digest: )[0-9a-f]+' | head -1)

checksum=$(python3 -c "
import base64, binascii, sys
print(base64.urlsafe_b64encode(binascii.unhexlify(sys.argv[1])).decode().rstrip('='))
" "${cert_sha256_hex}")

download_url="http://${host_ip}:${port}/kiosk.apk"

# PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED is true on purpose. Left at its default, device-owner
# provisioning disables every non-required system app, which on an EMUI device is a large and
# undocumented set including things the launcher depends on. Debloating is done deliberately
# afterwards with `pm uninstall --user 0`, against a list diffed from device-baseline/, so that a
# removal that breaks something can be traced to the package that caused it.
python3 - "${out_dir}/qr-payload.json" "${download_url}" "${checksum}" <<'PY'
import json, sys
out, url, checksum = sys.argv[1], sys.argv[2], sys.argv[3]
payload = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
        "org.kiosk.launcher/.KioskDeviceAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": url,
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
}
# Compact separators: every byte saved is one less QR module, and a denser code is harder for an
# older front-facing camera to read off a screen.
with open(out, "w") as handle:
    json.dump(payload, handle, separators=(",", ":"), sort_keys=True)
PY

# qrencode comes from a throwaway container: it is needed once per payload change, so baking it
# into the builder image would be carrying a dependency for nothing.
# No --userns=keep-id here, unlike every other podman call in this repo: apt-get needs to be root
# inside the container. Under rootless podman the container's root maps to the invoking host user, so
# the files it writes into the mount still come out owned by us.
podman run --rm \
    --security-opt label=disable \
    --volume "${out_dir}:/out" \
    docker.io/library/debian:stable-slim \
    bash -lc 'apt-get update -qq && apt-get install -y -qq --no-install-recommends qrencode \
        >/dev/null 2>&1 && qrencode -t PNG -o /out/qr.png -s 10 -m 4 -l M < /out/qr-payload.json \
        && qrencode -t UTF8 -m 1 -l M < /out/qr-payload.json > /out/qr.txt'

printf '\nProvisioning payload written:\n'
printf '  APK        %s (%s bytes)\n' "${out_dir}/kiosk.apk" "$(stat -c %s "${out_dir}/kiosk.apk")"
printf '  Cert hash  %s\n' "${checksum}"
printf '  Download   %s\n' "${download_url}"
printf '  Payload    %s\n' "${out_dir}/qr-payload.json"
printf '  QR image   %s\n' "${out_dir}/qr.png"
printf '\nServe the APK with scripts/serve-provisioning.sh before scanning.\n'
