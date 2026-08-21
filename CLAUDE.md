# Muralis (formerly KiOSk): architecture and decisions

This file is the one AI-facing document in this repo: a reference for what this app is, what it
does, and why its trickier parts are shaped the way they are. It is not a status report, a
changelog, or a getting-started guide, it does not track what's tested, what's pending, or how to
build or run the project (the scripts and their own comments are self-explanatory for that). It
describes the app as it stands, and should need no further edits except when a feature is added or
removed that changes what the app actually does.

## What this is

An Android kiosk launcher for a wall-mounted tablet running a Home Assistant dashboard in a
WebView. It locks the device into that one dashboard, recovers from the dashboard going stale or
unreachable without anyone touching it, and exposes a shared command/telemetry surface over both
MQTT and a small built-in HTTP admin server.

It targets the **public Android SDK only**: no platform signature, no privileged permissions, so it
installs as an ordinary APK (minSdk 26, targetSdk 36, Play's floor, which moves every August;
re-verify before any submission) on stock Android. A separate, privileged reference build exists
(a custom-ROM app with `DEVICE_POWER`/`REBOOT`/`STATUS_BAR`/`WRITE_SECURE_SETTINGS`), kept frozen
elsewhere as a working proof of what a fully-privileged build can do; this app is the deliberately
constrained, publishable version of the same idea, not a port of that build's plumbing.

`applicationId`/package is `org.spazio17.muralis`; "Muralis" is the Play display name. Internal
Java class names (`KioskActivity`, `KioskService`, `KioskCommandDispatcher`, ...) still carry the
old `Kiosk` prefix on purpose, a deliberate scope decision, not an oversight: they're not
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
  `"status":"accepted"` and does nothing, a remote caller (e.g. a Home Assistant automation) would
  otherwise believe a panel is off when it is still fully powered and rendering.
- **Both remote surfaces fail closed, but not identically.** Neither ships a default credential and
  neither activates until configured. HTTP is the stricter of the two: it binds no socket at all
  until an admin password of at least 8 characters exists. MQTT starts as soon as a broker *address*
  is set, because broker username and password are optional, plenty of home brokers accept
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
  this build does not implement, two different answers on purpose. The single deliberate silence is
  an **empty** payload, because clearing a retained command is itself an empty retained publish, and
  replying would make the cleanup trigger its own refusal.
- **Secrets are never stored in plaintext.** `SecretStore` keeps credentials as AES-GCM ciphertext in
  its own shared-preferences file, under a key that never leaves the Android Keystore and with the
  field name bound in as additional authenticated data. MQTT has no TLS option, by design: an earlier TLS checkbox was
  removed because it was never actually backed by trust-material handling and could only ever work
  against a publicly-trusted certificate on a matching hostname, useless for the home-broker
  audience this app targets. Don't reintroduce a TLS toggle without also building the certificate/
  fingerprint machinery to back it.
- **Dashboard self-recovery is one clock, not scattered callbacks.** A single supervising loop
  (`dashboardSupervisor` in `KioskActivity`) owns every retry/timeout decision. WebView page
  callbacks (`onPageStarted`, `onReceivedError`, etc.) only record facts (`recordLoadFailure`,
  `beginLoad`), they never themselves issue a reload. This is deliberate: a single cancellable
  handle that's reused for two different meanings (a retry timer and a hung-load timeout) is the
  bug class that motivated this shape; don't reintroduce one.
- **`NetworkGate`** holds MQTT/HTTP startup and the first dashboard load until there's a network,
  using `NET_CAPABILITY_INTERNET`, never `NET_CAPABILITY_VALIDATED`, a LAN-only Home Assistant
  install has no route to the internet and would never validate. It gives up after a bounded wait
  and starts anyway (fail soft): a panel with genuinely no network must still end up with a running
  web admin, since that's the surface someone would use to diagnose exactly that.
- **Two self-maintenance mechanisms, deliberately different in scale, and no user control over
  either.** `RecyclePolicy` decides between them. A **nightly restart of the whole app**, once a day
  at `QUIET_HOUR` (04) plus a per-device offset derived from `String.hashCode()` of the device id, routine cleaning, and because the process actually exits it reclaims the heap, the renderer, native
  allocations and any leaked handler in a way rebuilding in place cannot. And a **WebView rebuild
  when the OS reports low memory**, which is a response to a live condition, floored at once per 30
  minutes so a dashboard simply too big for the device cannot reload in a loop. Renderer death is
  handled separately by `onRenderProcessGone`. An "Auto recycle" switch and a "Recycle time" clock
  existed on the tablet, in the web admin and over MQTT, and all of them were deleted along with the
  `kiosk.auto_recycle` and `kiosk.recycle_time` commands: these are recovery mechanisms, and a
  control whose only use is to stop a panel healing itself is surface area that can only be used to
  break it. Both commands answer `unsupported` rather than being silently ignored.
- **The nightly rule is a calendar date, not an elapsed time.** `KioskConfig.recordNightlyRestartDay`
  stores the local day number and `commit()`s it *synchronously before* `System.exit`, that write
  being asynchronous would leave the day unrecorded, so the pass would fire again on the next tick
  after the restart, forever. It is a date rather than a monotonic floor for two reasons: the process
  dies, so any "time since the last one" resets with it; and the twelve-hour floor this replaced meant
  a panel restarted for any other reason at 20:00 silently skipped that night's clean.
- **The restart relaunches through an `AlarmManager` one-shot, not by trusting `START_STICKY`.** The
  service would come back on its own and the activity usually follows because it is HOME, but
  "usually" is doing too much work for the mechanism that has to survive unattended for months. An
  alarm is held by the system rather than by this process, so it fires whether or not anything here
  returns by itself. Verified on the API 26 panel: forced to fire immediately, the process exited,
  came back, reconnected MQTT and rebound the admin server, then stayed put, which also proves the
  synchronous date write, since otherwise it would have looped.
- **There is no memory threshold in this app, and no learned model. Do not reintroduce one.** An
  earlier version recycled below a fixed 12% of total memory. The version after that tried to learn
  what this dashboard normally costs and act on growth, in the shape of a Kubernetes VPA raising a
  pod's requests from observed usage. That idea cannot work here, and the reason is worth keeping
  because it is not obvious: the WebView renderer is an isolated process belonging to the WebView
  provider, not to this app, so no public API and no readable procfs path exposes its footprint.
  Measuring system-wide memory instead measures the device rather than the dashboard, the node
  rather than the pod, and every threshold derived from it was either unreachable or above physical
  RAM. `getHistoricalProcessExitReasons` would give ground truth about a kill, but it is API 30 and
  the target hardware is API 26. The nightly restart plus the OS's own verdict plus renderer-death
  recovery cover the same failure without measuring anything.
- **`TelemetryCollector`/`SystemStats`** read procfs/HAL sources that may be SELinux-denied on a
  stock, unprivileged install; a denied path latches off after repeated failures rather than
  retrying (and re-denying) forever. `HardwareProperties` (via `HardwarePropertiesManager`, public
  API since 24) recovers CPU/thermal readings once the app is device owner, bypassing the denied
  procfs paths entirely. Telemetry publishes on a configurable interval (10/30/60/300s) and early,
  on-change, for a short allowlist of fields (battery, charging, memory pressure, thermal status)
  that change rarely enough for an early publish not to spam the broker. A "Connected"/
  "disconnected" entity is backed directly by the broker's Last Will rather than by a periodic
  field, so it reflects the tablet being gone even when nothing is left running to publish `false`.
- **The escape hatch** is a user-recorded corner-tap sequence, three to twelve taps across the four
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
  whole `KioskConfig` snapshot taken when a screen was built, `save()` writes every field, so a
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
  app must work, in a visibly degraded way, without it too, see the device-owner warning banner
  pattern in the config screen.
- **`targetSdk` tracks Play's rolling floor.** It moves every August; re-check
  https://developer.android.com/google/play/requirements/target-sdk before any submission rather
  than trusting a remembered number.

## Toolchain

Fully containerized: the image carries a fixed JDK/Gradle/cmdline-tools, and the SDK packages and
Gradle caches live on gitignored bind mounts regenerated by `scripts/build-container.sh`. Versions
are pinned deliberately (AGP, Gradle, cmdline-tools), this app is meant to run unattended for
months at a time, and an unplanned toolchain upgrade on rebuild is a liability, not a convenience.
