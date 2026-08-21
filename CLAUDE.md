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
  switch (`kiosk.start/stop/reload/restart/set_url`,
  `display.wake/visual_off/brightness/auto_brightness`, `system.reboot`, `telemetry.publish`)
  behind an `Executor` interface. `MqttController` and `HttpAdminServer` both call into it, so a
  command behaves identically regardless of which surface it arrived on. Preserve this: it is the
  point of the design, not incidental structure to simplify away.
- **There is no `system.shutdown`.** No public or device-owner Android API can power a device off,
  at any privilege level. The command was deleted rather than shipped as a no-op that reports
  `"status":"accepted"` and does nothing — a remote caller (e.g. a Home Assistant automation) would
  otherwise believe a panel is off when it is still fully powered and rendering.
- **Both remote surfaces fail closed, but not identically.** Neither ships a default credential and
  neither activates until configured. HTTP is the stricter of the two: it binds no socket at all
  until an admin password of at least 8 characters exists. MQTT starts as soon as a broker *address*
  is set, because broker username and password are optional — plenty of home brokers accept
  anonymous connections, and refusing to work against one would be inventing a requirement the
  protocol does not have. Authentication of individual MQTT commands is therefore the broker's job,
  not this app's. Any new remote-control surface should be off until deliberately configured.
- **Fail soft everywhere else.** A missing permission or capability logs a warning and continues
  rather than crashing. This app runs across a much wider spread of Android versions and device
  policies than a build for one fixed piece of hardware would, so this matters more here, not less.
- **A refused command is never silent.** Malformed input, an unusable command name, or a retained
  MQTT command (structurally indistinguishable from a fresh one at the protocol level; MQTT strips
  `RETAIN` on delivery to an established subscription, so the app can only refuse it on reconnect
  and clear it) all publish a result with a reason. A caller must be able to tell a refusal from a
  dead device. Note the status is `rejected` for bad arguments and `unsupported` for a command name
  this build does not implement — two different answers on purpose. The single deliberate silence is
  an **empty** payload, because clearing a retained command is itself an empty retained publish, and
  replying would make the cleanup trigger its own refusal.
- **Secrets are never stored in plaintext.** `SecretStore` keeps credentials as AES-GCM ciphertext in
  its own shared-preferences file, under a key that never leaves the Android Keystore and with the
  field name bound in as additional authenticated data. MQTT has no TLS option, by design: an earlier TLS checkbox was
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
- **`RecyclePolicy` and `MemoryBaseline` govern the dashboard recycle, and no user can touch
  either.** The recycle is a recovery mechanism, not a preference: an "Auto recycle" switch and a
  "Recycle time" clock existed on the tablet, in the web admin and over MQTT, and all of them were
  deleted along with the `kiosk.auto_recycle` and `kiosk.recycle_time` commands. A control whose
  only use is to stop a panel healing itself is surface area that can only be used to break it.
  Both commands now answer `unsupported` rather than being silently ignored, since a Home Assistant
  automation somewhere may still publish them.
- **There is no memory threshold anywhere in the app.** The old policy recycled below a fixed 12% of
  total memory free. That cannot be right for every device this runs on — the same footprint is a
  leak on a 2 GB tablet and unremarkable on a 16 GB one — and it cannot tell a heavy dashboard from
  a growing one at all. Two signals replaced it, neither of them ours: what the device itself
  reports through `ActivityManager.MemoryInfo.lowMemory`, whose threshold the vendor sets per
  device, and `MemoryBaseline`, an exponentially weighted mean and variance of the footprint
  observed at the end of each dashboard generation. The trigger is mean + 3σ — a recommendation
  learned from this device, the shape of a Kubernetes VPA raising requests after watching real
  usage, not a number written down once. Two consequences to preserve: the model must **not** learn
  from generations its own trigger cut short (or it chases itself upward and measures nothing),
  which is what `GROWTH_TRIPS_BEFORE_ACCEPTING` is for, and it must still adapt when a dashboard
  legitimately grows over months, which is the other half of the same counter. `MIN_SPREAD_FRACTION`
  exists because a variance near zero collapses the budget onto the mean and turns a leak detector
  into a periodic reloader.
- **The GROWTH cause does not currently fire, and the model needs redesigning rather than retuning.**
  Do not treat the bullet above as describing working behaviour. With a zero measured variance the
  budget is flatly `mean * 1.15`, i.e. a 15% climb, where the climb measured on this hardware before
  lmkd kills the renderer is 5.6% — so the kill always wins. And because `memUsedKb` is system-wide,
  `mean * 1.15` often exceeds physical RAM, which makes a device-independent relative headroom into
  the fraction-of-total threshold this work set out to delete. The trip guard halves the rate of
  runaway drift rather than preventing it, the "same level twice" rule it documents is not actually
  implemented, and a single low outlier (opening the configuration screen destroys the WebView)
  loosens the trigger for weeks. The fix is to learn the distribution of a generation's own growth
  delta — memory now minus memory when this page settled — not of the absolute footprint; that needs
  a per-generation anchor plumbed from the activity. Until then the working recovery mechanisms are
  the nightly pass and the OS's own `lowMemory` verdict. See `MemoryBaseline`'s class comment for the
  full list.
- **The nightly pass is scheduled per device, not at a fixed hour.** `RecyclePolicy.QUIET_HOUR` is
  04, and the minute comes from `String.hashCode()` of the device id. Deterministic so a panel picks
  the same minute every night, which is what makes the twelve-hour interval check behave; spread so
  that panels across many installs do not all rebuild, and all hit whatever Home Assistant they talk
  to, inside the same sixty seconds.
- **The frozen-page detector runs unconditionally, and earns it.** Its off switch existed because a
  legitimately static page — one unchanging image, no live tiles — is indistinguishable from a
  frozen one *by a single observation*. Over time it is not: a static page never changed, a frozen
  one was changing and stopped. So a reload now requires having observed this generation of the page
  change at least once. The trade is in the safe direction — a page that freezes before the first
  observed change is left to the load-failure, hung-load and nightly-recycle paths — because
  reloading a working panel every fifteen minutes forever is the worse failure.
- **`TelemetryCollector`/`SystemStats`** read procfs/HAL sources that may be SELinux-denied on a
  stock, unprivileged install; a denied path latches off after repeated failures rather than
  retrying (and re-denying) forever. `HardwareProperties` (via `HardwarePropertiesManager`, public
  API since 24) recovers CPU/thermal readings once the app is device owner, bypassing the denied
  procfs paths entirely. Telemetry publishes on a configurable interval (10/30/60/300s) and early,
  on-change, for a short allowlist of fields (battery, charging, memory pressure, thermal status)
  that change rarely enough for an early publish not to spam the broker. A "Connected"/
  "disconnected" entity is backed directly by the broker's Last Will rather than by a periodic
  field, so it reflects the tablet being gone even when nothing is left running to publish `false`.
- **The escape hatch** is a user-recorded corner-tap sequence — three to twelve taps across the four
  screen corners, tail-matched with a maximum gap between taps, and recorded separately for "open
  settings" and "exit to the system launcher". It is recordable rather than fixed because a gesture
  is worthless once someone has watched it being used, and a hardcoded one is identical on every
  panel. It works from every screen the app shows, including its own configuration screen: it is the
  one way out of the kiosk and has no screen-specific exceptions. Exiting releases lock task and
  launches the OEM launcher resolved at runtime via an explicit `setPackage` intent, never a
  hardcoded launcher package, since the default launcher varies by OEM.
  `addPersistentPreferredActivity` re-pins this app as the HOME activity once it's device owner, so
  HOME reliably returns to it.
- **A setting changed on any surface must be visible on all of them, quickly.** Three surfaces can
  write the same settings, so the rules are: every Behaviour control, the brightness pair and the
  admin password apply the moment they are touched, on the tablet and in the web admin alike; only
  the connection fields (dashboard URL, device id, broker, ports) wait for a save button, because
  applying those per keystroke would rebind sockets and restart the MQTT client. Anything applied
  outside `KioskCommandDispatcher` must also call `KioskService.publishTelemetrySoon`, since only
  the dispatcher republishes automatically, and without it Home Assistant keeps showing the old
  value for up to a full publish interval. Both UIs poll storage on a timer so a change made
  elsewhere appears rather than leaving two surfaces disagreeing. And a writer must never save a
  whole `KioskConfig` snapshot taken when a screen was built — `save()` writes every field, so a
  stale snapshot silently reverts whatever another surface changed meanwhile; load fresh, set the
  one field, save. That bug has been introduced three separate times, which is why
  `saveEscapeSequences` exists as a partial write.
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
