# Muralis

A self-recovering Android kiosk launcher that turns a tablet into a wall-mounted Home Assistant
dashboard, controllable over MQTT and HTTP.

Targets the public Android SDK only (no platform signature, no privileged permissions), so it
installs as an ordinary APK on stock Android, from API 26 up. See [`CLAUDE.md`](CLAUDE.md) for the
architecture and the current state of the project.

## Building

Everything builds inside a container; the host needs neither an Android SDK nor a matching JDK.

```bash
scripts/build-container.sh   # once per clone: builds the image, provisions the SDK
scripts/test-host.sh         # fast, container-free checks: lint the scripts/manifest, run the
                              # pure-Java unit tests
scripts/build-app.sh         # assembleDebug; prints the APK path and the adb commands to install
                              # it and take device ownership
```

`scripts/gradle.sh <task>` runs any other Gradle task (`:app:lintDebug`, `:app:bundleDebug`, ...)
inside the same container.

## Status

Staging MVP in progress: porting a working, privileged reference build (a custom-ROM kiosk app,
kept elsewhere as a frozen reference) to an unprivileged, Play-Store-installable APK. See
`CLAUDE.md` for what's verified on hardware versus what's built but not yet installed.

## License

All rights reserved for now — see [`LICENSE`](LICENSE); a placeholder pending a final licensing
decision, not an open-source license. Built on [Eclipse Paho](https://www.eclipse.org/paho/) MQTT
(`org.eclipse.paho.client.mqttv3`, EPL/EDL) and the [Catppuccin](https://catppuccin.com/) palette,
both credited on the app's own About screen.
