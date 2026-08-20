# Muralis (formerly KiOSk): architecture and decisions

This file is the one AI-facing document in this repo: a reference for what this app is, what it
does, and why its trickier parts are shaped the way they are. It is not a status report, a
changelog, or a getting-started guide — it does not track what's tested, what's pending, or how to
build or run the project (the scripts and their own comments are self-explanatory for that). It
describes the app as it stands, and should need no further edits except when a feature is added or
removed that changes what the app actually does.

## What this is

An Android kiosk launcher for a wall-mounted tablet running a Home Assistant dashboard in a
WebView. It locks the device into that one dashboard, recovers from the dashboard going stale or
unreachable without anyone touching it, and exposes a shared command/telemetry surface over both
MQTT and a small built-in HTTP admin server.

It targets the **public Android SDK only**: no platform signature, no privileged permissions, so it
installs as an ordinary APK (minSdk 26, targetSdk 36 — Play's floor, which moves every August;
re-verify before any submission) on stock Android. A separate, privileged reference build exists
(a custom-ROM app with `DEVICE_POWER`/`REBOOT`/`STATUS_BAR`/`WRITE_SECURE_SETTINGS`), kept frozen
elsewhere as a working proof of what a fully-privileged build can do; this app is the deliberately
constrained, publishable version of the same idea, not a port of that build's plumbing.

`applicationId`/package is `org.spazio17.muralis`; "Muralis" is the Play display name. Internal
Java class names (`KioskActivity`, `KioskService`, `KioskCommandDispatcher`, ...) still carry the
old `Kiosk` prefix on purpose — a deliberate scope decision, not an oversight: they're not
product-facing, and renaming them touches every file for a purely cosmetic gain.

## Core architecture

- **One command dispatcher, two transports.** `KioskCommandDispatcher` holds a single command
  switch (`kiosk.start/stop/reload/restart/set_url/auto_recycle/recycle_time`,
  `display.wake/visual_off/brightness/auto_brightness`, `system.reboot`, `telemetry.publish`)
  behind an `Executor` interface. `MqttController` and `HttpAdminServer` both call into it, so a
  command behaves identically regardless of which surface it arrived on. Preserve this: it is the
  point of the design, not incidental structure to simplify away.
- **There is no `system.shutdown`.** No public or device-owner Android API can power a device off,
  at any privilege level. The command was deleted rather than shipped as a no-op that reports
  `"status":"accepted"` and does nothing — a remote caller (e.g. a Home Assistant automation) would
  otherwise believe a panel is off when it is still fully powered and rendering.
- **Both remote surfaces fail closed.** No default credential ships with either transport; each
  stays inactive until a secret is configured locally (an admin password for HTTP, broker
  credentials for MQTT). Any new remote-control surface should follow the same rule.
- **Fail soft everywhere else.** A missing permission or capability logs a warning and continues
  rather than crashing. This app runs across a much wider spread of Android versions and device
  policies than a build for one fixed piece of hardware would, so this matters more here, not less.
- **A rejected command is never silent.** Malformed input, an unknown command name, or a retained
  MQTT command (structurally indistinguishable from a fresh one at the protocol level; MQTT strips
  `RETAIN` on delivery to an established subscription, so the app can only refuse it on reconnect
  and clear it) all publish a `rejected` result with a reason. A caller must be able to tell
  "rejected" from "device is gone."
- **Secrets are never in plaintext.** `SecretStore`/`KioskConfig` hold credentials via Android
  Keystore, not shared preferences. MQTT has no TLS option, by design: an earlier TLS checkbox was
  removed because it was never actually backed by trust-material handling and could only ever work
  against a publicly-trusted certificate on a matching hostname — useless for the home-broker
  audience this app targets. Don't reintroduce a TLS toggle without also building the certificate/
  fingerprint machinery to back it.
- **Dashboard self-recovery is one clock, not scattered callbacks.** A single supervising loop
  (`dashboardSupervisor` in `KioskActivity`) owns every retry/timeout decision. WebView page
  callbacks (`onPageStarted`, `onReceivedError`, etc.) only record facts (`recordLoadFailure`,
  `beginLoad`) — they never themselves issue a reload. This is deliberate: a single cancellable
  handle that's reused for two different meanings (a retry timer and a hung-load timeout) is the
  bug class that motivated this shape; don't reintroduce one.
- **`NetworkGate`** holds MQTT/HTTP startup and the first dashboard load until there's a network,
  using `NET_CAPABILITY_INTERNET`, never `NET_CAPABILITY_VALIDATED` — a LAN-only Home Assistant
  install has no route to the internet and would never validate. It gives up after a bounded wait
  and starts anyway (fail soft): a panel with genuinely no network must still end up with a running
  web admin, since that's the surface someone would use to diagnose exactly that.
- **`RecyclePolicy`** governs the nightly/memory-pressure dashboard recycle. A recycle time given as
  a command is *rejected* if invalid, not clamped — clamping a bad value to something "safe" is a
  silent substitution of what was actually asked for.
- **`TelemetryCollector`/`SystemStats`** read procfs/HAL sources that may be SELinux-denied on a
  stock, unprivileged install; a denied path latches off after repeated failures rather than
  retrying (and re-denying) forever. `HardwareProperties` (via `HardwarePropertiesManager`, public
  API since 24) recovers CPU/thermal readings once the app is device owner, bypassing the denied
  procfs paths entirely. Telemetry publishes on a configurable interval (10/30/60/300s) and early,
  on-change, for a short allowlist of fields (battery, charging, memory pressure, thermal status)
  that change rarely enough for an early publish not to spam the broker. A "Connected"/
  "disconnected" entity is backed directly by the broker's Last Will rather than by a periodic
  field, so it reflects the tablet being gone even when nothing is left running to publish `false`.
- **The escape hatch** (a fixed-count tap gesture, independently configurable for "open settings"
  vs. "exit to the system launcher") works from every screen the app shows, including its own
  configuration screen — it is the one way out of the kiosk and has no screen-specific exceptions.
  It releases lock task and launches the OEM launcher resolved at runtime via an explicit
  `setPackage` intent, never a hardcoded launcher package, since the default launcher varies by
  OEM. `addPersistentPreferredActivity` re-pins this app as the HOME activity once it's device
  owner, so HOME reliably returns to it.
- **Most configuration fields apply on the aggregate "Open dashboard" save; a few apply
  immediately instead** (brightness, the auto-brightness checkbox, the HTTP admin password on
  focus loss) — whichever a field's own effect is cheap and safe to apply the moment it's touched,
  rather than batched with everything else.
- **Legal documents and app/device facts are shown in-app, not linked externally.** The About
  screen (tablet) and a matching page (web admin) render the bundled privacy policy and terms
  directly, since a kiosk running under lock task has no browser to hand a URL to; Play separately
  requires the same text at a public URL, which is a listing concern rather than an app one.

## Platform constraints that shape the code

- **minSdk 26 is a real target, not a floor kept for form.** Any `SDK_INT`-gated code path for 26/27
  is expected to actually run on hardware, not just exist defensively.
- **Every version-gated call needs an explicit fallback, not a silent no-op.** `setLockTaskFeatures`/
  `WindowInsetsController`-family APIs arrived at 28/30; `scripts/test-host.sh` fails the build if
  such a call appears with no `SDK_INT` check nearby, specifically so this can't be forgotten
  silently.
- **No privileged permissions are available at all.** `DEVICE_POWER`, `REBOOT`, `STATUS_BAR`,
  `WRITE_SECURE_SETTINGS` are all unobtainable outside a signature/platform build. Device-owner
  status (via `dpm set-device-owner` over adb, or QR provisioning during setup) is the ceiling of
  privilege this app can ever reach, and several capabilities (reboot, silent permission grants,
  full lock-task feature masking, `HardwarePropertiesManager`) exist only conditional on it. The
  app must work, in a visibly degraded way, without it too — see the device-owner warning banner
  pattern in the config screen.
- **`targetSdk` tracks Play's rolling floor.** It moves every August; re-check
  https://developer.android.com/google/play/requirements/target-sdk before any submission rather
  than trusting a remembered number.

## Toolchain

Fully containerized: the image carries a fixed JDK/Gradle/cmdline-tools, and the SDK packages and
Gradle caches live on gitignored bind mounts regenerated by `scripts/build-container.sh`. Versions
are pinned deliberately (AGP, Gradle, cmdline-tools) — this app is meant to run unattended for
months at a time, and an unplanned toolchain upgrade on rebuild is a liability, not a convenience.
