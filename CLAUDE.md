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
  `kiosk.open_url/home`, `display.wake/visual_off/brightness/auto_brightness/portrait`,
  `webadmin.enabled`, `system.reboot`, `telemetry.publish`)
  behind an `Executor` interface. `MqttController` and `HttpAdminServer` both call into it, so a
  command behaves identically regardless of which surface it arrived on. Preserve this: it is the
  point of the design, not incidental structure to simplify away.
- **The dispatcher's validators are the only validators.** `validateDashboardUrl`,
  `validateDeviceId` and `parseEnabledFlag` live in `KioskCommandDispatcher` (host-tested), and
  every surface calls them before storing anything: the web admin returns the refusal on the page,
  the tablet toasts it, MQTT publishes it. The 2026-08-21 audit found each surface with its own
  rules: the web form stored URLs and device ids the command path refused with a reason (an
  emptied device id even re-minted the panel's identity and orphaned every Home Assistant entity),
  and the JSON and query encodings disagreed about whether `enabled=1` meant on. A new input gets
  its validator in the dispatcher, and the surfaces share it.
- **A one-off URL is not the dashboard.** `kiosk.set_url` means "this is the dashboard now" and
  persists; `kiosk.open_url` shows a URL and stores nothing; `kiosk.home` returns to the stored
  one. The web admin's "Open a URL now" box used to send `set_url`, so looking at something once
  replaced the panel's dashboard and the only way back was retyping the original from memory
  (reported 2026-08-24). The rules, all host-tested and verified on hardware: **reload** reloads
  whatever is on screen, dashboard or one-off or a page reached inside the dashboard; a **kiosk
  restart**, a process restart and the nightly clean always come back to the stored dashboard.
  Every surface carries both halves as *inputs*, including two Home Assistant text entities; the
  one-off entity reads `runtime.last_page_url` rather than a stored field, because an entity whose
  state cannot be read back snaps back on every edit. **As a button, `kiosk.home` exists only in
  Home Assistant now.** The tablet's "Main dashboard" and the web admin's quick action of the
  same name were removed on 2026-09-07 at Juri's decision: on the tablet's settings screen it sat
  beside "Open once" while the only save was the foot button "Open dashboard", two buttons naming
  the dashboard and neither of them the save, and he pressed it in place of the save and lost a
  full set of typed MQTT credentials. Anyone who misreads that button makes the same mistake, so
  the button went rather than its name. The way back from a one-off URL on those two surfaces is
  the foot button or "Restart kiosk"; the command itself is unchanged and MQTT keeps it.
  **A panel with no dashboard URL shows a parking page, not a black WebView** (2026-09-07):
  `showDashboard("")` routes to `showParkingPage`, a native screen that is the dashboard state in
  every respect but the page (fullscreen, lock task, escape corners, blackout honoured) and says
  what to do next, with the web admin address when there is one. It is reached by the foot button
  pressed with an emptied URL box (which now saves the other cards and stores the empty URL, where
  it used to do nothing), by `kiosk.home` and a kiosk restart with nothing stored, and by the
  maintenance rebuilds; **not at boot**, where an unconfigured panel still opens its settings
  screen. The dashboard URL box's example is a *hint*, never a value, and it is
  `KioskCommandDispatcher.EXAMPLE_DASHBOARD_URL` (`https://example.com/dashboard`) on both
  surfaces: the old prefilled `homeassistant.local` text was saved and loaded at the first press on
  a fresh panel, and named a product this app does not belong to. The parking page's illustration,
  the Android robot pasting a fresh poster over a scratched Muralis billboard, is an easter egg
  for exactly this state; About's Credits card carries the CC BY 3.0 credit for it (Google,
  modified, the licence), short like the other rows rather than Google's long sentence, at Juri's
  request, and the robot is never the app icon.
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
  not this app's. The web admin also has an operator on/off flag (`KioskConfig.webAdminEnabled`,
  the `webadmin.enabled` command, a tablet toggle button and a Home Assistant switch), which is
  deliberately not the password: turning the surface off must not cost the credential, and turning
  it back on must not require retyping one. An earlier tablet button that "switched off" by
  erasing the stored password did exactly that damage during QA. The switch reflects the flag, not
  the socket; a panel that is "on" with no stored password stays unbound and says so on the
  tablet's status line. Any new remote-control surface should be off until deliberately configured.
  Command payloads are capped at 16 KB on both transports (`HttpAdminServer.MAX_BODY_BYTES`,
  `MqttController.MAX_COMMAND_BYTES`, deliberately equal), and the caller-supplied command id is
  bounded to 96 characters and stripped of control characters before it is logged or echoed, so an
  embedded newline cannot forge log lines.
- **The remote surfaces are the paid tier, gated in exactly two places.** Muralis Pro (Play
  product `muralis_pro`, one-time, account-wide) unlocks MQTT and the web admin together; the
  kiosk, recovery, the stats overlay and the local `TelemetryCollector` feeding it stay free and
  ungated forever (DDA 3.7: what ships free must stay free, which is why the gate had to exist
  before the first public release). The gate is `ProEntitlement.isActive` consulted at the top of
  `KioskService.startControllersNow` and `restartControllers`, the only two places a controller is
  ever built; do not add a third gate site or a third construction site. The entitlement is Play's
  signed purchase document, verified on-device by `PurchaseSignature` (pure, host-tested; see its
  javadoc for why there is deliberately no verification server) and cached in `SecretStore` as a
  bridge, not as a right: Google Play's answer is the truth. **Juri's rule, 2026-09-03, absolute:
  Muralis works with or without a Google account, Muralis Pro only with one, and a refund ends it.**
  So `ProBilling` calls `ProEntitlement.drop` at once on a full-account `queryPurchasesAsync` that
  answers `OK` without the purchase, and on `BILLING_UNAVAILABLE` from setup or query, which is
  what a signed-out device gets; every other refusal (service unavailable, disconnected, network,
  timeout) is about the moment and leaves the gate alone, so a panel with its account signed in
  and its internet down keeps Pro. Reversible by Play's next answer alone. The gate closing tears
  the controllers down in `restartControllers`; that direction is real now, do not reintroduce the
  assumption that it cannot happen. Do not add an `AccountManager` check (deprecated broadcast, a
  "Contacts" permission on the listing, visibility rules that changed at API 26) or a grace period:
  both were built on 2026-09-03 and removed the same day because Play already answers the question
  and that is how client-only apps behave. **Play is not asked anything until the first-start
  wizard has recorded both escape combinations** (`KioskActivity.startProBilling`, which the
  wizard's exit calls): Muralis is free without a Google account, so a panel nobody has finished
  setting up has no business talking to Play, and somebody who cannot leave Muralis yet must not be
  shown a Google screen on the way in. `ProBilling` also asks for the *price* only while the
  configuration screen is attached as a `StatusListener`; a panel sitting on its dashboard queries
  purchases alone. A free panel is never nagged: the surfaces
  simply do not come up, and the configuration screen's dimmed MQTT and web-admin cards (visible,
  inert, one line and a Buy button each) are the whole story. A purchase completing at the panel
  starts the surfaces immediately via `reloadConfiguration`, no restart needed. Debug builds accept
  `MURALIS_PRO_OVERRIDE=on|off` to force either path (R8-stripped from release); a signed release
  refuses to build without `MURALIS_LICENSE_KEY`, or with one that does not parse.
  rather than crashing. This app runs across a much wider spread of Android versions and device
  policies than a build for one fixed piece of hardware would, so this matters more here, not less.
- **A refused command is never silent.** Malformed input, an unusable command name, or a retained
  MQTT command (structurally indistinguishable from a fresh one at the protocol level; MQTT strips
  `RETAIN` on delivery to an established subscription, so the app can only refuse it on reconnect
  and clear it) all publish a result with a reason. A caller must be able to tell a refusal from a
  dead device. Note the status is `rejected` for bad arguments and `unsupported` for a command name
  this build does not implement, two different answers on purpose. The single deliberate silence is
  an **empty** payload, because clearing a retained command is itself an empty retained publish, and
  replying would make the cleanup trigger its own refusal. The same rule covers success: a command
  must not claim it either, which is why `telemetry.publish` answers `rejected` ("MQTT is not
  configured" / "MQTT is not connected") instead of accepting a publish that went nowhere, the
  accepted-no-op shape `system.shutdown` was deleted to avoid.
- **Secrets are never stored in plaintext.** `SecretStore` keeps credentials as AES-GCM ciphertext in
  its own shared-preferences file, under a key that never leaves the Android Keystore and with the
  field name bound in as additional authenticated data. They are never *rendered* in plaintext
  either: the tablet's password boxes draw blank like the web admin's always have, with blank
  meaning "keep the current one", because a stored password prefilled into a masked EditText is
  one input-type toggle away from readable. Every preference file, this one included, lives in
  device-protected storage through `KioskConfig.storageContext`: the service is directBootAware,
  and reading a credential-encrypted file before the first unlock throws. MQTT has no TLS option, by design: an earlier TLS checkbox was
  removed because it was never actually backed by trust-material handling and could only ever work
  against a publicly-trusted certificate on a matching hostname, useless for the home-broker
  audience this app targets. Don't reintroduce a TLS toggle without also building the certificate/
  fingerprint machinery to back it.
- **Dashboard self-recovery is one clock, not scattered callbacks, and its decisions are pure.**
  Every retry/timeout/frozen-page decision lives in `RecoveryPolicy` (no Android imports,
  host-tested, with each historical recovery bug pinned as a test); `KioskActivity` only wires it
  to the WebView, the clock and the handlers, and its supervising loop (`dashboardSupervisor`) is
  the single place a reload is ever issued. WebView page callbacks (`onPageStarted`,
  `onReceivedError`, etc.) only record facts, they never themselves issue a reload. This is
  deliberate: a single cancellable handle that's reused for two different meanings (a retry timer
  and a hung-load timeout) is the bug class that motivated this shape; don't reintroduce one, and
  put any new recovery decision in `RecoveryPolicy` with a test, not in the activity. The failure no page callback can
  report, the server restarting underneath an already-loaded page (a Home Assistant restart leaves
  the odd card stuck on an error tile while the frontend's own websocket reconnect recovers the
  rest, so the page is neither failed nor frozen), is covered by a reachability probe: while the
  page is settled the activity sends a HEAD to the dashboard URL every 15 seconds, and
  `ServerProbePolicy` (pure, host-tested) turns the verdicts into at most one reload per outage,
  fired on recovery rather than mid-outage so the wall never trades a live-looking page for an
  error page, and serviced through the same `recordLoadFailure`/supervisor path as everything
  else.
- **`NetworkGate`** holds MQTT/HTTP startup and the first dashboard load until there's a network,
  using `NET_CAPABILITY_INTERNET`, never `NET_CAPABILITY_VALIDATED`, a LAN-only Home Assistant
  install has no route to the internet and would never validate. It gives up after a bounded wait
  and starts anyway (fail soft): a panel with genuinely no network must still end up with a running
  web admin, since that's the surface someone would use to diagnose exactly that.
- **Two self-maintenance mechanisms, deliberately different in scale, and no user control over
  either.** `RecyclePolicy` decides between them. A **nightly restart of the whole app**, once a day
  at `QUIET_HOUR` (04) plus a per-device offset derived from `String.hashCode()` of the device id, routine cleaning, and because the process actually exits it reclaims the heap, the renderer, native
  allocations and any leaked handler in a way rebuilding in place cannot. **The full process exit
  is device-owner-only** (2026-08-29): an ordinary install gets a nightly WebView rebuild in place
  instead (`Action.NIGHTLY_REBUILD`, same calendar-day rule, same recorded day). The exit cannot
  be survived without device-owner status, for two independent reasons: the relaunch alarm calls
  `setExact`, which from Android 12 needs an exact-alarm permission this app deliberately does not
  hold (`SCHEDULE_EXACT_ALARM` needs a user grant plus a Play declaration; `USE_EXACT_ALARM` is
  reserved for alarm-clock/calendar apps), and even a scheduled activity start from a dead process
  is a background start on modern Android. A device-owner panel is HOME, so the system relaunches
  it when nothing else can be resumed, and its own service relaunches it otherwise (next bullet).
  Where `setExact` would throw, `restartApplication` now falls back to an inexact
  `set()`, belt and braces under the HOME relaunch. And a **WebView rebuild
  when the OS reports low memory**, which is a response to a live condition, floored at once per 30
  minutes so a dashboard simply too big for the device cannot reload in a loop. Renderer death is
  handled separately by `onRenderProcessGone`. Every WebView teardown (`destroyWebView`, so
  `kiosk.restart`, the pressure rebuild and the nightly clean alike) also clears the WebView's
  HTTP disk cache, which is app-global and otherwise survives even the process exit; cache only,
  never cookies or WebStorage, those hold the dashboard's login and clearing them would log the
  panel out. There is no separate "clear cache" command or button, by the same no-user-control
  rule. An "Auto recycle" switch and a "Recycle time" clock
  existed on the tablet, in the web admin and over MQTT, and all of them were deleted along with the
  `kiosk.auto_recycle` and `kiosk.recycle_time` commands: these are recovery mechanisms, and a
  control whose only use is to stop a panel healing itself is surface area that can only be used to
  break it. Both commands answer `unsupported` rather than being silently ignored. Three boundaries
  both mechanisms respect: a deliberate `kiosk.stop` is persisted (`KioskConfig.kioskStopped`,
  2026-08-24) and survives them both, so neither pass lights up a panel somebody blanked on
  purpose; `display.visual_off` survives them the same way (2026-08-25, after the nightly restart
  lit a panel blanked with the Display off button), but keyed to `BOOT_COUNT` rather than stored
  as a bare flag, engaged-this-boot or not at all, so a reboot always comes back lit (Juri's
  rule) and the stranded-dark bug the old persisted 1% override caused cannot return; and the
  pressure rebuild waits while the configuration screen or the
  sequence recorder is up (`KioskRuntimeState.operatorOnScreen`), because destroying the view tree
  mid-edit costs an operator a half-filled form. The nightly restart never waits: it runs inside
  the quiet hour, and a screen left open must not be able to starve the calendar-day rule.
- **The nightly rule is a calendar date, not an elapsed time.** `KioskConfig.recordNightlyRestartDay`
  stores the local day number and `commit()`s it *synchronously before* `System.exit`, that write
  being asynchronous would leave the day unrecorded, so the pass would fire again on the next tick
  after the restart, forever. It is a date rather than a monotonic floor for two reasons: the process
  dies, so any "time since the last one" resets with it; and the twelve-hour floor this replaced meant
  a panel restarted for any other reason at 20:00 silently skipped that night's clean.
- **The service supervises the dashboard's presence, because "the system relaunches HOME" is only
  half true.** Measured on the MediaPad 2026-09-03: on battery with the screen turned off at the
  power button, EMUI's PowerGenie (`com.huawei.powergenie`, uid system, persistent) force-stopped
  Muralis 5 min 36 s after screen-off, 80 s after light Doze went idle. Android then started Muralis
  as HOME, the force-stop's own finishing pass killed that activity 7 ms later, and the system
  resumed the OEM launcher's task, still underneath from the last manual launch. Wake: launcher.
  With no launcher task the system retries and Muralis comes back, so the failure needs a launcher
  task in the home stack, which any escape to the launcher or manual launch leaves behind until a
  reboot. A force-stop cancels every alarm, job and sticky service the app owns, so the only foothold
  is the process the system starts anyway (for the HOME attempt, or for the lock-task-exiting
  broadcast to the admin receiver): `MuralisApplication` starts `KioskService` on a device-owner
  install, and the service, at 1.5 s after its creation and on every 60 s telemetry tick, asks
  `RelaunchPolicy` (pure, host-tested) whether to start `KioskActivity`. Verdicts: not a kiosk,
  settling (younger than 1.5 s: at boot and after an update the activity's creation is queued
  right behind the service's, and judging early relaunched a dashboard milliseconds from existing
  and stamped the floor that then blocked the real relaunch), on screen (`KioskRuntimeState.
  dashboardAlive`, set in the activity's onCreate/onDestroy; paused behind Settings counts as
  alive), not ready (same DEVICE_PROVISIONED + unlocked rule as `BootReceiver`, so it never pops up
  over the setup wizard during QR enrolment), too soon (a persisted 60 s floor against a killer
  that strikes back instantly), relaunch. Verified over adb with `am force-stop` and a launcher task
  underneath: back and locked within 2 s; with no launcher task the system's own relaunch wins and
  the check finds the activity alive. Starting an activity from a service is exempt for the device
  owner from Android 10 on: the platform's background-start check returns early for the device
  owner's uid (`ActivityStarter`/`BackgroundActivityStartController`, "don't abort if the
  callingUid is the device owner"); the developer page on background starts does not list it.
  Whether PowerGenie honours the Doze allowlist is being measured; the app is not on it and has no
  device-owner API to put itself there, contrary to what an older comment in
  `applyResourceGuarantees` claimed.
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
  procfs paths entirely. Telemetry publishes on a **fixed 60-second interval** and early, on-change,
  for a short allowlist of fields (battery, charging, memory pressure, thermal status) that change
  rarely enough for an early publish not to spam the broker. The interval was four presets
  (10/30/60/300s) with a button row on the tablet and a menu in the web admin; both are gone, along
  with the stored field, for the reason the "Auto recycle" switch went: nobody can set it from an
  informed position, and it had to be kept in step across three surfaces to do nothing an operator
  wanted. Sixty rather than the thirty it replaced, because every field that behaves like an event
  already publishes on change, leaving the periodic tick only the readings that drift (battery
  temperature, available memory, available storage, network state). Liveness does not ride on it:
  that is the 30-second heartbeat plus `expire_after` on the one entity that needs it, so
  lengthening this cannot make anything read unavailable. An "MQTT state"
  connectivity binary_sensor is backed directly by the broker's Last Will rather than by a periodic
  field, so it reflects the tablet being gone even when nothing is left running to publish `false`.
  The Last Will only covers a session the broker *held and lost*, so it says nothing when the panel
  never reached the broker or when the broker itself died; the panel therefore also beats "online"
  onto the availability topic every 30s and that entity carries `expire_after`, which turns "nobody
  has spoken for this panel" into `unavailable` instead of a stale `on`. **There is no live
  "Network state" entity, and one must not come back**: publishing "my network is down" requires
  the network, so a live entity can only ever say "connected", and "MQTT down but network up" is
  unreportable while it is true because the reporting channel is the thing that broke (the Last
  Will cannot carry it either; its payload is frozen at connect time). For one year of its life
  that question was answered in hindsight by an outage-attribution feature (`OutageLedger`, a
  ConnectivityManager callback in the service, a verdict on every reconnect, a "Last MQTT outage
  cause" diagnostic sensor and `runtime.mqtt_outages`/`last_mqtt_outage_*` telemetry fields);
  Juri removed the whole chain on 2026-08-25, because as long as the MQTT state entity updates
  correctly, *why* a session dropped is not worth knowing, and *when and for how long* is that
  entity's own history in Home Assistant. Do not rebuild the attribution in any form without
  that conversation again, and note the reconnect still publishes telemetry early, now simply
  because the retained snapshot is as stale as the outage was long. Entity naming rule:
  discovery key, `unique_id` and name all say the same thing, so a rename changes the entity id
  too. Renames and removals are done by withdrawing the old key (an empty component config
  holding only its platform, one payload per platform the key ever had, QoS 1 for ordering) and
  then publishing the configuration without it, never by aliasing a new name onto an old
  `unique_id` and never by mere omission, which strands the entity. **Withdrawals are bridging
  code with a retention rule**: they stay until every installation that ever saw the old key has
  processed one. Pre-publication that meant Juri's own Home Assistant, so the standing stale-keys
  block that had accumulated (recycle controls, network/charging, both `network_state` spellings,
  `last_mqtt_outage_cause`) was retired on 2026-08-25 after he confirmed HA clean; an entity
  removed after the app is public needs its withdrawal kept indefinitely, because the last
  stranger's panel never announces its upgrade. The mechanics live in a comment above the
  discovery publish in `MqttController`.
- **The escape hatch** is a user-recorded corner-tap sequence, three to twelve taps across the four
  screen corners, tail-matched with a maximum gap between taps, and recorded separately for "open
  settings" and "exit to the system launcher". It is recordable rather than fixed because a gesture
  is worthless once someone has watched it being used, and a hardcoded one is identical on every
  panel. **There is no compiled-in default any more** (the fixed BL x9 / BR x9 pair was retired
  2026-08-25, decided with Juri): a first-start wizard in `KioskActivity` records both combinations
  before anything else, and `applyKioskPolicy` refuses to engage lock task until
  `KioskConfig.escapeSequencesConfigured()` is true, because a pinned screen with no recorded way
  out is a bricked panel. Deliberately unmigrated: installs that ran on the old defaults (they were
  never persisted) see the wizard once after updating and re-record. Do not reintroduce a default,
  and do not let any new screen bypass the wizard routing in `initializeUserInterface`. It works from every screen the app shows, including its own configuration screen: it is the
  one way out of the kiosk and has no screen-specific exceptions. Exiting releases lock task and
  launches the OEM launcher resolved at runtime via an explicit `setPackage` intent, never a
  hardcoded launcher package, since the default launcher varies by OEM.
  `addPersistentPreferredActivity` re-pins this app as the HOME activity once it's device owner, so
  HOME reliably returns to it, **but not before the wizard has recorded both combinations**, the
  same gate lock task sits behind and for the same reason one step removed: becoming the device's
  only HOME means every later failure relaunches into itself. That is not hypothetical. On
  2026-09-03 a QR-provisioned tablet with no Google account was left with no way back to the OEM
  launcher and had to be factory reset, because Muralis had taken HOME during enrolment, from the
  admin receiver's `onEnabled`, and something else then killed its activity a second into every
  start. `onEnabled` no longer pins; `applyKioskPolicy` is the only caller. The safe-mode block
  (`DISALLOW_SAFE_BOOT`) sits behind the same gate since the same day: safe mode is itself a way
  out, and the service used to remove it at every boot, before anyone had another. Nothing may make Muralis the
  only way out of the device before the operator has a way out of Muralis.
- **The lock-task allowlist is this package and nothing else, and must stay that way.** An
  allowlisted package is exactly one Android will let run *over* the kiosk while lock task is held,
  so every name added there is a door. `lockTaskPackages()` used to volunteer the launcher and
  Settings as "second lines of recovery"; measured 2026-09-03 on the panel,
  `am start -a android.settings.SETTINGS` then put the whole Settings app on screen over a locked
  panel and kept it, while Chrome, the Play Store and the dialer were refused by the platform with
  code 101. The supervisor did not intervene, correctly, because `KioskActivity` was alive behind
  it. Neither entry bought anything: every sanctioned hand-over (`openSystemLauncher`, the
  WRITE_SETTINGS grant screen, the HOME-settings screen) calls `releaseForOtherApp()` first, and
  that *ends* lock task, after which no allowlist entry is needed. Verified after the change:
  Settings refused with 101, and releasing lock task then starting Settings still works. If a future
  screen needs another app, release lock task for it; do not widen this list.
- **The escape hatch's side doors are shut.** Where the app is device owner, accessibility
  services are restricted to the system image (the configuration screen renders both corner-tap
  combinations in plaintext, so a service that can read the screen and synthesise taps is a
  keyless escape) and keyboards to system ones plus whichever keyboard is active when the policy
  applies, resolved at runtime rather than hardcoded: the MediaPad's SwiftKey lives in `/data`,
  so a bare "system only" would have left the settings screen unable to type. The dashboard
  WebView refuses top-level navigation to non-web schemes (`intent://`, `tel:`, `market:` hand
  control to another app, which under lock task is an exit). Every http(s) navigation stays
  allowed and mixed-content mode stays permissive, decided with the user 2026-08-24: Muralis is
  dashboard-agnostic, dashboards legitimately navigate across hosts and mix plain-http LAN camera
  streams into https pages, so origin confinement was considered and refused. Don't tighten
  either without that conversation again.
- **A setting changed on any surface must be visible on all of them, quickly.** Three surfaces can
  write the same settings, so the rules are: the stats-overlay switch, portrait, the brightness pair
  and the admin password apply the moment they are touched, on the
  tablet and in the web admin alike (the admin password on blur when non-empty, because the box
  now renders blank and its only job on either surface is setting a new one); only
  the connection fields (dashboard URL, device id, broker, ports) wait for a save button, because
  applying those per keystroke would rebind sockets and restart the MQTT client. Anything applied
  outside `KioskCommandDispatcher` must also call `KioskService.publishTelemetrySoon`, since only
  the dispatcher republishes automatically, and without it Home Assistant keeps showing the old
  value for up to a full publish interval. Both UIs poll storage on a timer so a change made
  elsewhere appears rather than leaving two surfaces disagreeing. Writes go through
  `KioskConfig.edit(...)`, which persists exactly the fields set on it; the whole-object `save()`
  it replaced reverted another surface's change from a stale snapshot three separate times before
  the API stopped being able to express the bug. A loaded `KioskConfig` is a read snapshot and a
  display model only. The Save-button forms (both surfaces) additionally carry a baseline of the
  values they were rendered from and are refused when it no longer matches the device, the same
  rule the escape recorder pioneered, so a page or screen left open cannot silently revert a
  newer change even to its own fields.
- **The web admin port is floored at 1024, and refused while another service holds it.** The
  history in one line each: an out-of-range port once crash-looped a panel, so the range became
  checked; Juri then proved on hardware (2026-08-24) that a *privileged* port like 80 passes a
  1-65535 check and fails at bind time, since an unprivileged app can never bind below 1024, and
  the server failing closed meant the admin's own settings box could switch the admin off. So
  `KioskCommandDispatcher.validateAdminPort` floors the range at 1024 (this floor is for the port
  the app binds itself; the broker port is remote and keeps 1-65535), and the web save
  additionally test-binds a changed port and refuses one already in use. A brief hardcode of 8080
  sat between these two designs and was rejected in review: two immovable services wanting the
  same port would deadlock with no recourse, which is worse than the problem. Both surfaces also
  **refuse a port rather than substituting one**: the tablet used to clamp an out-of-range admin
  port to 8080 and a bad broker port to 1883, so an operator was told nothing while a different
  value was stored, and the tablet now test-binds a changed admin port exactly as the web save
  does. `KioskActivity.parsePort`'s clamp survives for reading storage back, where something
  usable has to come out whatever is in there; it is never right for a form. Keep syntax and range
  in separate checks, too: one parser judging both answered "must be a number" for 99999.
- **Both settings surfaces pre-check values against the device, on blur, in colour.** Juri's
  design (2026-08-24): validation can only test spelling, but "is this port bindable", "does this
  URL answer HTTP", "is a broker listening there" are runtime facts only the device can know.
  `SettingProbe` is the one implementation; the web admin reaches it through `POST /api/check`
  with `admin_check.js` colouring the field, the tablet calls it directly and colours the same
  way. **POST, not GET, and it must stay a POST**: the endpoint changes nothing here but makes the
  panel connect out to a caller-chosen address, so as a GET it was an SSRF and LAN-scan primitive
  an `<img>` tag could drive with the operator's own credentials. The verb is what puts it behind
  `crossSiteRefusal`. The verdict is the border alone, no fill or glow, an
  annotation rather than an alarm. Advisory by design, a port free now can be taken at the next
  boot, so the save paths keep their own hard refusals; a check that cannot run clears the colour
  rather than guessing, because a wrong verdict is worse than none, and every verdict carries a
  per-field generation so a slow probe cannot repaint a value the operator has since corrected.
  A probe only ever follows a deliberate visit: the tablet's config page takes the initial focus
  itself, because when the first field silently owned it, the operator's first tap anywhere else
  blurred it and painted a verdict on a box they never touched. The one probe that also runs
  unasked: the tablet's MQTT card carries a broker verdict, probed when the settings screen opens
  and whenever the host or port box is left, because a broker that never answers is the one
  misconfiguration the dashboard itself never shows. It reports **on the settings page**, never as
  a toast over the dashboard: that toast existed for one round and was unreadable in the second
  before the dashboard took the screen, which is exactly where a misconfiguration must not be
  reported. Any new input whose validity is a runtime fact should get a probe here rather than a
  bespoke checker.
- **Legal documents and app/device facts are shown in-app, not linked externally.** The About
  screen (tablet) and a matching page (web admin) render the bundled privacy policy and terms
  directly, since a kiosk running under lock task has no browser to hand a URL to; Play separately
  requires the same text at a public URL, which is a listing concern rather than an app one. Each
  surface also states where the public copy lives (plain text on the tablet, a link on the web
  admin; sentence and URLs shared through `res/values/legal.xml`). Since 2026-08-25 the bundled
  texts are **verbatim copies, not sources**: the canonical documents are `legal/privacy.txt` and
  `legal/terms.txt` in the public muralis-site repo, which also generates its HTML pages from
  them. Edit them there, run `scripts/sync-legal.sh` here; the release workflow byte-compares
  `res/raw` against that repo's main branch and refuses to release while they differ, so the in-app
  copy cannot silently drift from the published one. This replaced hand-kept copies in five places,
  which had already drifted once (a hand sentence-casing produced "If you buy muralis pro" on the
  site). The layered setup, offline in-app text plus the public URL, is also what GDPR
  accessibility and the Austrian guidance for commercial apps expect; researched 2026-08-25, link-
  only was rejected on that basis.
- **Play Console's automatic integrity protection must stay OFF for this app.** Turning it on makes
  Play inject `com.pairip.*` into the signed artifact: it replaces the manifest's application class
  with `com.pairip.application.Application` and adds `com.pairip.licensecheck.LicenseActivity`,
  which refuses to let the app run unless Play itself installed it for a signed-in account. The
  wall-panel path installs the APK by QR during setup, outside Play, on purpose, so that check can
  never pass. Measured on the tablet 2026-09-03 with the Play-signed 0.4.5 and no Google account:
  `KioskActivity` resumed, `LicenseActivity` finished it 1.3 s later with `clear-task-index`, Play's
  `UnauthenticatedMainActivity` took the screen, and because Muralis was HOME the whole thing
  repeated forever; the app's own code never logged a line. It is per-release in Play Console under
  Test and release, App integrity, Automatic protection. None of this is in this repo and none of it
  is visible in a local build, which is exactly why it is written down here.

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
- **`WRITE_SETTINGS` is a user grant, and device-owner status cannot substitute for it.** The
  brightness pair writes `Settings.System.SCREEN_BRIGHTNESS` and `SCREEN_BRIGHTNESS_MODE`, and
  that namespace has no device-owner setter, unlike Global and Secure. So the one permission this
  app needs a human for is the one that looks most like it should come free with enrolment. It is
  declared in the manifest, granted from `ACTION_MANAGE_WRITE_SETTINGS` or by
  `adb shell appops set org.spazio17.muralis WRITE_SETTINGS allow`, and until it is granted both
  brightness controls are inert: the checkbox cannot write the mode, and the slider is separately
  disabled while the light sensor owns the backlight, which on an unconfigured device it does by
  default. That combination reads as two bugs, and on 2026-09-07 it read that way to Juri on a
  freshly provisioned panel, so **all three surfaces now name the missing grant rather than
  failing quietly**: the tablet's Display card carries the sentence and a button, the web admin
  carries the same sentence and the adb command, and the slider's refusal is a toast rather than
  only a log line. A device with no light sensor is the case that needs the toast, because nothing
  disables its slider.
  **The card's claim is kept true while it is on screen**: `watchForWriteSettingsGrant` watches the
  app op through `AppOpsManager` while the red line is drawn, and the moment the grant is made it
  redraws the configuration screen in place (typed boxes and scroll kept) and starts the activity
  again, which brings a `singleTask` activity forward, so a panel whose Settings screen has no
  navigation bar comes back by itself; the setup page promises exactly that. Found 2026-09-07 on
  the phone: granted, returned by hand, and the card still said the grant was missing. A device
  owner may start an activity from the background on every Android; an ordinary install on
  Android 10+ is refused silently and `onResume` does the redraw when the operator comes back.
- **Every text box on both settings surfaces is a machine value, so no keyboard prose habits.**
  The panel's own keyboard (SwiftKey on both Huawei test devices) reads a full stop as the end of
  a sentence: it adds a space, capitalises what follows and corrects the word, so
  `test.mosquitto.org` typed into the broker box arrived as `test. mosquito. org` (Juri,
  2026-09-07; the capture script had recorded the same on 2026-08-31 and worked around it by not
  typing the host). Measured on the phone that day: `TYPE_TEXT_FLAG_NO_SUGGESTIONS` alone stops
  the correcting but not the space after the full stop, so `themedInput` gives every plain box the
  visible-password variation, which keyboards treat as "type exactly this", the dashboard URL and
  the broker host the URI variation (the URL keyboard, measured intact), and ports the number
  class. The web admin's text inputs carry `autocapitalize="off" autocorrect="off"
  spellcheck="false"`, which Chrome on Android maps onto the same flags, and the two address boxes
  add `inputmode="url"`. Not `type="url"` there: the browser would refuse a host without a scheme
  before the server's normalisation could add one.
- **`targetSdk` tracks Play's rolling floor.** It moves every August; re-check
  https://developer.android.com/google/play/requirements/target-sdk before any submission rather
  than trusting a remembered number.

## Toolchain

Fully containerized: the image carries a fixed JDK/Gradle/cmdline-tools, and the SDK packages and
Gradle caches live on gitignored bind mounts regenerated by `scripts/build-container.sh`. Versions
are pinned deliberately (AGP, Gradle, cmdline-tools), this app is meant to run unattended for
months at a time, and an unplanned toolchain upgrade on rebuild is a liability, not a convenience.
A Gradle wrapper pinned to the same version exists for GitHub Actions (`.github/workflows/`): CI
builds a debug APK on every push, the manual release workflow tags main and attaches the APK and
the upload-key-signed AAB to a GitHub Release. The release's description is the list of pull request titles merged since the previous tag,
read from the merge commits; there is no changelog file to keep in step. **The app's version is
derived from git, never edited in `app/build.gradle`**: versionName from `git describe` against the latest `v*` tag,
versionCode from the commit count, passed into the container by `scripts/gradle.sh` since the
image has no git. Semantic versions are humans pushing tags; CI never bumps anything. The CI debug
keystore secret must remain byte-identical to `.gradle/kiosk-debug.keystore`, because the tablet
runs Muralis as device owner and can never be uninstalled to accept a differently-signed build.
