# Muralis

A self-recovering Android kiosk launcher that turns a tablet into a wall panel for any web page,
a dashboard, a board or a page on your own network, controllable over MQTT and HTTP.

Targets the public Android SDK only (no platform signature, no privileged permissions), so it
installs as an ordinary APK on stock Android, from API 26 up. See [`CLAUDE.md`](CLAUDE.md) for an
overview of the app and the rules its code keeps.

## Building

Everything builds and tests inside a podman container; the host needs podman and git, neither an Android SDK nor a JDK.

```bash
scripts/build-container.sh   # once per clone: builds the image, provisions the SDK
scripts/test-host.sh         # fast checks in the same container: lint the scripts/manifest, run
                              # the pure-Java unit tests
scripts/build-app.sh         # assembleDebug; prints the APK path and the adb commands to install
                              # it and take device ownership
```

After a change to `build/Containerfile.android`, run `scripts/build-container.sh` again; `test-host.sh` names the missing tool when the image is older than the script.

`scripts/gradle.sh <task>` runs any other Gradle task (`:app:lintDebug`, `:app:bundleDebug`, ...)
inside the same container.

## Status

On Google Play (`org.spazio17.muralis`) and as an APK
with a setup QR on [muralis.spazio17.org](https://muralis.spazio17.org). Everything a single panel
does is free; Muralis Pro, one purchase on Google Play, adds the fleet.

## Licence

Muralis is **source available**, not open source, under the
[PolyForm Shield License 1.0.0](LICENSE.md). In plain words: you may read the code, learn from it,
use Muralis on as many devices as you like, at home or in a business, change it, use parts of it in
your own projects, and be paid to install and set it up for someone else. You may not use Muralis,
or any part of it, for a product that competes with Muralis or with Muralis Pro and Business, even
a free one. Removing or working around the Pro and Business lock is not allowed by the app's
[terms](https://muralis.spazio17.org/terms/). The licence gives no right to use the name Muralis or
its icon for another product. This summary does not replace the licence; read
[`LICENSE.md`](LICENSE.md) before you reuse the code. Contributions come under the agreement in
[`CONTRIBUTING.md`](CONTRIBUTING.md).
Third-party code and artwork keep their own licences: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
