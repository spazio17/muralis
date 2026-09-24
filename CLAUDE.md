# Muralis (formerly KiOSk): architecture and decisions

This file is the one AI-facing document in this repo: a reference for what this app is, what it
does, and why its trickier parts are shaped the way they are. It is not a status report, a
changelog, or a getting-started guide, it does not track what's tested, what's pending, or how to
build or run the project (the scripts and their own comments are self-explanatory for that). It
describes the app as it stands, and should need no further edits except when a feature is added or
removed that changes what the app actually does.

## What this is

An Android kiosk launcher for a wall-mounted tablet showing one web page in a WebView: a
smart-home dashboard of any brand, an office board, a front-desk screen, a page on the local
network. It locks the device onto that one page, recovers from the page going stale or unreachable
without anyone touching it, and exposes a shared command/telemetry surface over both MQTT and a
small built-in HTTP admin server. Home Assistant is one integration (MQTT discovery, two
blueprints), not the product's frame; see the positioning decision of 2026-09-09.

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
  `kiosk.open_url/home`, `display.wake/visual_off/brightness/auto_brightness/orientation/off_method`,
  `webadmin.enabled`, `screensaver.start/stop/mode/playlist/source/idle_seconds/off_seconds/
  picture_seconds/dim_percent/url/on_wake/transition/credit_corner/shuffle/one_per_cycle/credit`,
  `system.reboot`, `telemetry.publish`;
  the web admin alone adds `/api/pictures`, `/api/pictures/delete`, `/api/pictures/refresh` and the
  `/api/playlists` family for the picture playlists, which are not commands)
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
  one. The web admin's one-off box used to send `set_url`, so looking at something once replaced
  the panel's dashboard and the only way back was retyping the original from memory (reported
  2026-08-24). Since 2026-09-08 there is no separate box: the Dashboard box has Save and Open
  once on one input, mirroring the tablet's Dashboard card. The rules, all host-tested and verified on hardware: **reload** reloads
  whatever is on screen, dashboard or one-off or a page reached inside the dashboard; a **kiosk
  restart**, a process restart and the nightly clean always come back to the stored dashboard.
  Every surface carries both halves as *inputs*, including two Home Assistant text entities; the
  one-off entity reads `runtime.last_page_url` rather than a stored field, because an entity whose
  state cannot be read back snaps back on every edit. **As a button, `kiosk.home` exists only in
  Home Assistant now.** The tablet's "Main dashboard" and the web admin's quick action of the
  same name were removed on 2026-09-07, when it was decided that they had to go: on the tablet's
  settings screen it sat beside "Open once" while the only save was the foot button "Open
  dashboard", two buttons naming the dashboard and neither of them the save, and it was pressed in
  place of the save, which cost a full set of typed MQTT credentials. Anyone who misreads that button makes the same mistake, so
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
  modified, the licence), short like the other rows rather than Google's long sentence, as decided
  on 2026-09-07, and the robot is never the app icon.
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
- **The web admin serves HTTPS with a certificate the panel makes itself (2026-09-09).**
  `AdminCertificate` keeps an EC key pair in the Android Keystore, which also issues the
  self-signed X.509 (ten years, `CN=Muralis <device id>`; the Keystore cannot put an address in
  it, so a browser shows two warnings, authority and name, and one click accepts both). The
  listening socket stays plain and every accepted connection is handed to TLS: Android's
  Conscrypt wraps the socket's file descriptor, not its streams, so nothing can be peeked and
  handed back (a wrapper `Socket` was tried and died with "Socket is closed" inside
  `NativeSsl.doHandshake`). BoringSSL names a plain HTTP request it was fed instead of a
  ClientHello (`HTTP_REQUEST`, `WRONG_VERSION_NUMBER`), and that case is answered on the raw
  socket with a `301` to `https://<the address it connected to>:<port>/`, path lost, password
  never asked over plain text. The fingerprint is printed on the
  tablet's web-admin card, in the web admin's own box and in the admin-only stats
  (`config.http_tls`, `config.http_certificate_sha256`), so a person can compare it with the
  browser's. A device whose Keystore cannot make the certificate falls back to plain HTTP and
  says so in red on the page. A user-supplied certificate (own CA, fullchain plus key) is the
  planned v2 step. MQTT is unchanged: plain TCP, trusted network.
- **The remote surfaces are the paid tier, gated in exactly two places.** Muralis Pro (Play
  product `muralis_pro`, one-time, account-wide) unlocks MQTT and the web admin together; the
  kiosk, recovery, the stats overlay and the local `TelemetryCollector` feeding it stay free and
  ungated forever (DDA 3.7: what ships free must stay free, which is why the gate had to exist
  before the first public release). The gate is `ProEntitlement.isActive` consulted at the top of
  `KioskService.startControllersNow` and `restartControllers`, the only two places a controller is
  ever built; do not add a third gate site or a third construction site. The entitlement is Play's
  signed purchase document, verified on-device by `PurchaseSignature` (pure, host-tested; see its
  javadoc for why there is deliberately no verification server) and cached in `SecretStore` as a
  bridge, not as a right: Google Play's answer is the truth. **The rule since 2026-09-03, absolute:
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
- **A broker that is down when the client is built is retried, not abandoned.** Paho's automatic
  reconnect covers a connection that was established and then lost, nothing else: a first connect
  that fails left the client silent for good (Lenovo, 2026-09-09: broker reachable, three minutes,
  zero attempts). `MqttController.connect` now passes a failure listener and reschedules itself on
  the schedule in `MqttConnectRetry`, 15 s, 30 s, 60 s, 120 s, 240 s, then every five minutes;
  `stop()` cancels the pending retry, and a retry checks it is still for the live client, so a
  configuration reload while one is pending leaves exactly one client (measured: one connection on
  the broker after two reloads with it down). A connect that needed retries publishes telemetry at
  once, like a reconnect does.
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
  as a bare flag, engaged-this-boot or not at all, so a reboot always comes back lit (the rule
  since 2026-08-25) and the stranded-dark bug the old persisted 1% override caused cannot return; and the
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
- **A panel with no battery is reported as mains-powered, never as a flat battery.** PoE wall
  panels and screens on a mains adapter report `EXTRA_PRESENT` false while the platform still
  fills level and status with 0 % and "charging" (measured on the Lenovo with `dumpsys battery
  set present 0`, 2026-09-09). So `battery.present` is in the status document, every other
  battery field is null on such a panel, the overlay's BAT row becomes a MAINS row carrying
  whatever the kernel measures of the supply (`PowerSupply`, pure, reads
  `/sys/class/power_supply`: only an online non-battery node, only live `voltage_now`,
  `current_now` or `power_now`, never a rating, so most tablets show "MAINS" alone; the numbers
  are whatever the kernel measures at the device's own DC input, 5 V USB to 24 V DC or PoE,
  never the 230 V on the wall; the same numbers are `power.volts`/`power.watts`), the web admin
  chip says "mains", and
  discovery announces no battery entity, withdrawing the three on every connect because an older
  build announced them. `plugged` stays true: it is what makes a real screen-off trustworthy there.
  What every panel does report is `power.source`: `battery`, `wireless` (an induction pad) or
  `mains`, which is a charger, PoE and a DC adapter alike, one word on purpose, Android cannot
  tell them apart and whether the cell is filling is the battery object's business. A "Power
  source" enum sensor carries it in discovery. Same day: the "Wake
  display" button became "Display on" to pair with "Display off" (new key `display_on`, `wake`
  withdrawn for good), and the "Reboot tablet" button is announced only to a device owner, withdrawn
  elsewhere, because a button that can only answer `unsupported` is the thermal-status case again.
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
  the whole chain was removed on 2026-08-25, because as long as the MQTT state entity updates
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
  processed one. Pre-publication that meant the maintainer's own Home Assistant, so the standing stale-keys
  block that had accumulated (recycle controls, network/charging, both `network_state` spellings,
  `last_mqtt_outage_cause`) was retired on 2026-08-25 once that installation was confirmed clean; an entity
  removed after the app is public needs its withdrawal kept indefinitely, because the last
  stranger's panel never announces its upgrade. The mechanics live in a comment above the
  discovery publish in `MqttController`.
- **The escape hatch** is a user-recorded corner-tap sequence, three to twelve taps across the four
  screen corners, tail-matched with a maximum gap between taps, and recorded separately for "open
  settings" and "exit to the system launcher". It is recordable rather than fixed because a gesture
  is worthless once someone has watched it being used, and a hardcoded one is identical on every
  panel. **There is no compiled-in default any more** (the fixed BL x9 / BR x9 pair was retired
  2026-08-25, by decision of that day): a first-start wizard in `KioskActivity` records both combinations
  before anything else, and `applyKioskPolicy` refuses to engage lock task until
  `KioskConfig.escapeSequencesConfigured()` is true, because a pinned screen with no recorded way
  out is a bricked panel. Deliberately unmigrated: installs that ran on the old defaults (they were
  never persisted) see the wizard once after updating and re-record. Do not reintroduce a default,
  and do not let any new screen bypass the wizard routing in `initializeUserInterface`. **An
  optional PIN stands behind both combinations since 2026-09-09**, free on every panel: a
  combination can be watched and repeated, a PIN has to be known. `EscapePin` (pure) keeps 4 to 8
  digits as a salted PBKDF2 hash in `SecretStore`; `KioskActivity.gateBehindPin` asks for it after
  a matched combination and counts wrong tries with an `AuthThrottle` (five, then a doubling
  lockout); it is set, changed and removed on the Escape sequences page of the tablet and in the
  web admin's Escape sequences box, which is also the reset for a forgotten one. Managing it for a
  whole fleet is the paid part, per the fleet design. It works from every screen the app shows, including its own configuration screen: it is the
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
  checked; it was then proved on hardware (2026-08-24) that a *privileged* port like 80 passes a
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
- **Both settings surfaces pre-check values against the device, on blur, in colour.** The
  design of 2026-08-24: validation can only test spelling, but "is this port bindable", "does this
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

- **Display off is a real sleep where it can be trusted, and the black film otherwise
  (decided 2026-09-08).** `display.visual_off` on a device-owner panel calls
  `DevicePolicyManager.lockNow()` (`force-lock` is declared for it), which is the only call an app
  has that switches the backlight off; the film, a black view at 1% brightness, is what an
  ordinary install gets and what a device owner falls back to. `DisplayOffPolicy` (pure,
  host-tested) decides from the stored `display_off_method` (`auto`, the default, `sleep`, `film`)
  and three live facts: device owner or not, cable attached or not, and
  `isIgnoringBatteryOptimizations`, because on battery without the allowlist Doze cuts a sleeping
  panel's network so no wake can arrive. What no API announces, a vendor power manager stopping
  or freezing the process while the screen is off, is caught after the fact: `DarkWatch` records
  the sleep before `lockNow`, heartbeats while dark on the 2-second sampler, and the next process
  judges what ended the last one, with a reboot, an app update and the nightly restart recognised
  as innocent. A bad ending is recorded once and the stored method is switched to `film`, so the
  radio visibly moves to Black film and the shared sentence (`KioskService.describeDisplayOff`)
  turns red on every surface, saying Android stopped Muralis and that it is not a Muralis error;
  changing the method from any surface clears the record, which is how an operator asks for
  another try. A tap on a darkened panel wakes it on every screen (`filmOn` in
  `dispatchTouchEvent`), not only where the black view exists. Under sleep
  nothing is drawn: the dark state is the screen being off, `display.source` reports
  `display_off` from `isInteractive()`, and the power button or a remote wake ends it. The
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission is deliberately not declared: Play restricts
  it, and the policy does not need it, it only reads the answer. **A sleep leaves a keyguard
  behind** (found 2026-09-09): `lockNow` asks the keyguard to lock while lock task has it
  externally disabled, and `KeyguardViewMediator` records "reshow when re-enabled" before it ever
  checks that the device owner disabled the lock screen; `stopLockTask()` re-enables it, so the
  launcher hand-over came up behind a lock screen. It cannot be dismissed while hidden (the request
  errors), so `openSystemLauncher` drops the pin first, clears the screensaver and any brightness
  override, and `dismissKeyguardForLauncher` retries `requestDismissKeyguard` on a 100 ms clock
  until the reshown keyguard accepts it (one retry on the Lenovo). **Only for a device owner whose
  keyguard is not secure**, where the lock screen is disabled and the dismissal is therefore
  silent: a real credential stays Android's to ask for, and an ordinary install never asks. A
  callback is not a deadline, so an independent 1.2 s timer hands the screen over even where a
  vendor never answers, and `onResume` does not re-apply the kiosk policy while the hand-over is
  pending, which would otherwise re-pin the screen mid-retry. Each hand-over carries a generation,
  so a repeated escape and a late callback cannot start the launcher twice. Measured on the Lenovo
  2026-09-10, sleeping with `lockNow` and then escaping: the first dismissal request at +0 ms, a
  retry at +113 ms, the launcher started at +1,206 ms, so the deadline is what completed the
  hand-over rather than any callback; the launcher was usable with no keyguard over it, brightness
  came back to the system value, returning to Muralis re-pinned the screen, and two escapes in a
  row started the launcher exactly once. With a real credential set on the same panel **no
  dismissal was requested at all** and Android's lock screen took the screen, which is the point.
  The cost of the deadline is that an escape from an awake panel also waits out the 1.2 s, because
  the poll waits for a keyguard that never appears; that was true of the first version of this fix
  too, and it is a second of patience against a bricked hand-over.
  Measured on the Huawei BAH2-W19 (API 26, EMUI 8) 2026-09-19, after a `lockNow` sleep and after a
  power-button sleep: EMUI leaves nothing to reshow, `isKeyguardLocked` stays false and the same
  deadline starts the launcher at +1.21 s with no lock screen over it; with a device PIN set there,
  no dismissal was requested and the launcher started 9 ms after lock task ended. Lock task was
  re-applied on the way back in every run, on both panels.
- **The screensaver is a quieter page after a chosen idle time, and then Display off after a
  second one (built 2026-09-09, from the design note of that day).** Three modes in this step,
  `dim` (the page at a brightness floor), `film` (the black view Display off uses) and `url` (a
  second web page over the dashboard, which stays loaded underneath so a touch brings it back at
  once); `off` is the shipped value, because a panel that started darkening by itself after an
  update would read as broken. `ScreensaverPolicy` (pure, host-tested) holds the vocabulary, the
  ranges and the clock: `next()` says START after `idle_s` without a touch and DISPLAY_OFF after
  `off_s` of screensaver, and either time at 0 switches that step off. `KioskActivity` runs a
  one-second clock and owns the state (`screensaverStage`, `screensaverShowing`, the layer under
  the black view), asks the service for the display off (`KioskService.displayOff`, so sleep or
  film is decided by `DisplayOffPolicy` exactly as for the button), and consumes the first touch
  on a showing screensaver while still counting it for the escape combinations. What a wake from
  display off shows is the user's choice (`screensaver_on_wake`, for `url` and `dim`; the film
  has nothing to glance at, so the control is greyed out there with the reason, and both pages
  show only the fields the chosen mode uses):
  `onDisplayWoke` is idempotent because a wake from sleep reaches it twice, from `onResume` and
  from the `display.wake` broadcast. The web-page mode with no address is stored anyway and
  refused at start time with the reason, so the mode and the address can be typed in either
  order; the one sentence every surface shows (`ScreensaverPolicy.describe`) turns red meanwhile.
  Settings go through the instant path (`screensaver_mode/_idle_s/_off_s/_url/_dim_percent/
  _on_wake` in the web admin's behaviour section, applied on change and refused in red on the
  field itself; the tablet's card carries the mode and the sentence, its page every field,
  applied on blur or Done with a toast for a refusal, and "Show it now", whose preview a touch
  ends by returning to that page rather than to the dashboard); the commands are
  `screensaver.start` (refused, not accepted, when it cannot show: mode off, no address, kiosk
  stopped), `screensaver.stop` and `screensaver.mode`. The status document carries a
  `screensaver` block (`active`, `mode`, the times, `summary`, `problem`, and `url`, shared rather
  than admin-only because the Home Assistant text entity reads it), and
  `display.source` reads `screensaver` while the dim floor or the film is the screensaver's, so a
  lit dimmed page is never reported as display off. Home Assistant gets a select for the mode and
  one switch that is both state and control; no separate start/stop buttons, they would be the
  switch twice over. The camera and microphone are not part of it.
- **Pictures: local playlists, Bing and Wikimedia Commons.** `PictureSources` and `TinyJson` stay
  pure and host-tested. JSON has nesting and value limits and rejects malformed numbers and raw
  control characters. **A third party chooses the addresses this panel connects to, so those
  addresses are confined rather than trusted**: HTTPS only, one of four exact approved hosts
  (`www.bing.com`, `commons.wikimedia.org`, `upload.wikimedia.org`, `thumb.wikimedia.org`), no
  userinfo, no non-standard port, no fragment, and the check is repeated on every redirect hop,
  which is why redirects are followed by hand and capped at three. Bing's `urlbase` must start
  `/th?id=`, so a hostile answer cannot rewrite the authority through path concatenation. That is
  destination confinement, not proof of safe bytes: a compromised approved service can still send
  a decoder input, a wrong credit or an objectionable picture. A separate download worker keeps
  network waits out of the disk and decode queue, so a slow fetch cannot stall the slideshow that
  is already cached. Seven entries per source are cached with a manifest, fresh for 20 hours,
  checked at every screensaver start and hourly; a failed fetch keeps the last usable cache and
  says so in the source's sentence, measured on the Lenovo 2026-09-10 with the radio off and the
  manifest artificially aged: the cache survived the failed refresh untouched.
- **Named playlists, one browser, and no folder grants (reworked 2026-09-10).** Juri's rules, from
  the specification in `../spec-screensaver-picture-mode.md` and his sketch
  `../folders-pictures-selection.jpg`: several named playlists exist, exactly one plays, a playlist
  holds individually chosen pictures from any number of folders, a deleted picture leaves the
  playlist, and a picture removed from a playlist is **not** deleted from disk. His words on the
  browser settle the shape: "Tablet and phone behaves identically when creating playlist and
  selecting photos. Even if the phone has the possibility to exit muralis and use the android's file
  browser, it is still Muralis that must handle the folder and image selections, reason why the
  build is one only and it must integrate on both devices the in-muralis picture browser."
  **`PlaylistDocument`** (pure, host-tested) holds the whole set and the rules: unique case-folded
  names, at most one active, an ordered item list, a duplicate add is a no-op, 32 playlists and
  1,000 pictures each. **`PicturePlaylists`** is only its storage: one JSON document at
  `files/playlists.json` replaced by an atomic rename, which is what makes "zero or one active" and
  "a reorder is one transaction" true by construction without a database. Items are a **list**
  because the selection this replaced was a `StringSet` and therefore had no order a slideshow
  could honour; `reorder` takes the whole new order and answers "the playlist changed while it was
  being reordered" rather than half-applying a stale one. Malformed JSON **throws** and the file is
  left on disk, because reading it as empty would discard somebody's playlists after one
  interrupted write.
- **One browser on every device, reading MediaStore.** `PictureBrowser` replaced
  `PicturePlaylist`, and the whole SAF path is deleted with it: `ACTION_OPEN_DOCUMENT_TREE`,
  `takePersistableUriPermission`, granted roots, `MAX_FOLDERS`, the picker's two-minute patience
  timer, "a saved folder has lost access", and a picture that could be held twice under two
  different addresses. What differs between devices is only how the read permission arrives: a
  device owner grants it to itself silently in `KioskService.grantOwnRuntimePermissions`, an
  ordinary install is asked once by the system dialog from the Screensaver page, which is the one
  thing Android will not let an app do for itself. **Why MediaStore and not directories:**
  `File.listFiles()` on `/storage/emulated/0` was written first and does not work, because at
  Play's `targetSdk` floor scoped storage grants shared media *through MediaStore* and not through
  the filesystem, so it returns null (measured on the Lenovo). The alternative that does give paths
  is `MANAGE_EXTERNAL_STORAGE`, which Play treats as very restricted. So a folder is MediaStore's
  `RELATIVE_PATH` and a picture is a `content://media/...` id. **There is no depth limit.** A
  `maxdepth` of 3 was built on the morning of 2026-09-10 and deleted the same day: Juri agreed with
  the specification against his own earlier instruction, because `Pictures/2026/Italy/Rome/Vatican`
  is depth five and ordinary, and there is nothing for a cap to save when the folders come from an
  index rather than a walk. Verified on the API 26 tablet: that exact path is reachable and its
  pictures selectable. The folder index is cached for 30 s and dropped by `refresh()`, so going
  back into a folder is instant, which is the thing the gallery apps this was modelled on get wrong.
- **The playlist page is one page for creating and editing**, built from the sketch: Folders on the
  left, Content on the right (the tapped folder, named on its first line), and In this playlist
  under Folders, with its folder path prepended so two files called `test.jpg` read apart; where
  two lanes fit, Folders and In this playlist share the left lane and Content has the right one
  (2026-09-19). Content shows ten pictures at a time, with Show 10 25 50 100 under the list
  (`PictureBrowser.PAGE_SIZES`, the same four on the web page); a new size starts the folder over.
  Each held row carries the box that names the picture for its credit, with Save beside it and
  Remove in the main colour, the web page's row copied (2026-09-19) in place of a Name button that
  opened a page; the name is the existing caption, stored at once because it belongs to the file,
  and only a refusal is said. The pencil sits against the title's last letter, a 14 dp glyph with
  no pill, the title capped in width so a long name cannot push it off the row. **The playlist's name is the page's title, with a pencil beside it that turns
  the title into a box** (2026-09-19, the way a pull request's title is edited); the Name card went
  with it, a new playlist opens with the box already showing, and the box's Save only settles the
  draft's name. The count beside a folder is the pictures directly in it, not everything below it,
  which read as a miscount. Nothing is written until Save, which is what makes Cancel mean
  something. The Screensaver
  page lists the playlists as the web page does, name, count, Use or "In use", Edit and Delete, with
  the "New playlist name" box and Create playlist under the list (2026-09-19, the web panel copied;
  the button used to sit above the list and open the draft page); Create makes an empty playlist at
  once and Edit is where its pictures are picked. The active row says "In use" in the same column so
  the action columns stay aligned.
  Deleting the playlist in use leaves none in use and the sentence says so, rather than a black
  panel. Every question this feature asks is a screen the app draws itself: an `AlertDialog` came up
  in Android's light theme over a dark panel **and took the immersive mode with it**, so a
  navigation bar appeared on a locked kiosk (measured on API 26).
- **Every tap on that page repaints one pane, not the screen.** The first version answered a folder
  tap, a tick and a Remove by calling `showPlaylistPage` again, which is correct and unusable: the
  page was rebuilt from the top, so picking twenty pictures meant scrolling back down twenty times.
  `PlaylistPage` holds the three panes and the tick box of every picture on screen, and each action
  touches only what it changed: a folder tap repaints the folder list from the tree already in
  memory and re-reads the right pane, a tick repaints the Selected pane alone, and Remove also
  unticks the box if that folder happens to be open, so the two panes cannot disagree. A background
  read that finishes for a page that has been left, or for a folder somebody has since navigated
  away from, is dropped rather than painted. `showPlaylistPage` itself is now only arrival and
  rotation. **List actions use a smaller button than the rest of the app** (`rowButton`): a wall
  panel's buttons are sized for standing distance and three of them at that size fill a playlist row
  and crowd out the name they belong to. And a pair of short labels stays side by side on a phone
  (`pairedButtonRow`, Select all/Select none and Cancel/Save), where `buttonRow` would stack them;
  above 600 dp it defers to `buttonRow`, because stretching two buttons across a 1200 px card is the
  bar-shaped button that rule exists to prevent. On a phone the Selected rows put the path on its
  own line above its two buttons, since side by side it was ellipsized to `./Pictures.../pd_1.jpg`
  for two different pictures in different folders.
- **The migration off document URIs is deliberately unable to lose a selection.** It matches each
  old address to a media id by the path its document id encodes, which for the external-storage
  provider is exactly MediaStore's relative path plus display name, with the display name alone as
  the fallback for genuinely opaque providers. That difference is not academic: the phone held two
  files called `pd_1.jpg` in different folders, which a name cannot tell apart and a path can. It
  waits for the read permission, and it only counts itself finished once every address matched, so
  a later run picks up what an earlier one could not. **What it cannot match is kept, not removed**:
  the first version deleted it, and on the phone that emptied a playlist of five files that were on
  disk the whole time and simply not indexed yet, because they arrived over adb. "The index has not
  caught up" and "the picture is gone" are not the same thing. A kept item still opens on its own
  persisted grant, and one sentence names it. Measured on the phone: all five repointed, none lost.
- **The web admin's screensaver is a page of its own (`/screensaver`), a playlist is a page of its
  own under it (`/playlist?id=`), and neither reloads while it is used.** The screensaver page was
  a `<details>` inside the settings grid, so a two-pane browser and a playlist table were unfolding
  inside a 300 px column of a multi-column page. That page now holds the mode, the options named
  after it, and the list of playlists: name, count, Use or "In use", a green Edit and a red Delete,
  and nothing else. **Edit opens the playlist's own page**, arranged as the panel's own since
  2026-09-19: the name is the title, renamed through the pencil beside it (a `<details>`, so it
  opens, closes and posts with no scripting, and the script only keeps the answer on the page);
  Folders, with the upload at its foot because that is where a picture arrives, and Content side by
  side; In this playlist under Folders in the left column, Content with the right column to itself,
  placed by hand like the screensaver page's panels (In this playlist is the panel's own pane, with
  the folder prepended, the box that names a picture for its credit with a plain Save, and Remove).
  Content shows ten pictures at a time and offers Show 10 25 50 100 under the list where there is
  more than ten to show; the choice rides in the address as `n=` and in every fragment request, and
  a new size starts the folder over. Every one of them is scoped to
  that playlist rather
  than to whichever one happens to be playing (Juri, 2026-09-12: the two surfaces should be arranged
  alike and the panel's shape is the better one). The rename box that used to own a column of the
  table went with it: nobody renames a playlist often enough to spend a column on it. Unlike the
  panel the page applies as it goes rather than collecting a draft behind a Save, because every
  other control in this admin already works that way and a draft would need the whole selection
  carried in the browser. The three panels are placed by hand rather than left to flow: the options
  panel is much the tallest, so in document order the playlist list landed in a row that began below
  it and left a hand's width of nothing under the mode chooser. **A folder is a link**, not a
  form button, with a real `/playlist?id=<id>&at=<path>` address, so a folder can be reloaded,
  bookmarked or opened in a second tab; the address bar follows by `replaceState`, without a history entry per
  folder. That is also what fixed a stale-address 404: a folder used to be a POST to
  `/api/pictures/folder/browse`, whose URL then sat in the address bar and answered nothing on a
  reload. `admin_pictures.js` intercepts those links and every POST form on the page, not only the
  ones inside the browser: the name box and the held-pictures list sit outside it and would
  otherwise navigate to `/api/...` and leave that address in the bar. It re-reads three fragments
  after every change, Folders (`/api/pictures/folders`), Content (`/api/pictures/content`) and what
  the playlist holds, because a tick changes the row and the list, an upload changes a count, and a
  page whose panels disagree is a page nobody trusts. An upload carries its playlist in the
  form's action instead, since a multipart body is not parsed for anything but the file. The
  server answers each change twice over, as a sentence and a flag for `?fragment=1` and as the whole
  page for a browser with no scripting, so nothing navigates and nothing scrolls. Form bodies are
  URL-encoded, deliberately: a `FormData` body is sent as multipart and this server parses multipart
  for the upload alone, so posting a tick that way arrived with no fields at all. The upload's answer
  is a banner directly under the Upload button, red for a refusal, with a cross and a five-second
  timer; **a success says nothing on this page** (Juri, 2026-09-19: "Playlist updated." on every
  added picture was noise), the panels re-read after it are the answer, and "Uploading..." shows
  while a file is on its way.
  The stats poll runs on this page too, for the chip and for the fields it keeps current, so its
  readout element is optional: writing to the missing `#stats` threw on every tick and took the rest
  of the poll down with it. **Use, Delete and Create playlist are silent since 2026-09-19:**
  `admin_playlists.js` posts them as fragments from the screensaver page and re-reads the table
  (`GET /api/playlists/table`) in place, so the "In use" mark moving, a row going or a row appearing
  is the whole answer; only a refusal is said, red, in the banner under the table. Before that, Use
  came back as the whole page with "Playlist in use." on top and `/api/playlists/activate` in the
  address bar, which said nothing the mark did not (Juri: tap the playlist you want and simply
  switch). Without scripting the forms still post for real and the page comes back with its
  sentence, or with none for Use; an empty sentence in a fragment answer hides the banner instead
  of showing the raw JSON, on both pages.
- **Wording both surfaces share, trimmed 2026-09-11**: "One picture per screensaver" (not "..., the
  next one next time"), "Show the title and credit line" (not "... (always on for the online
  sources)"), "Preview" rather than "Show it now", and every way back is "← Back" with the arrow.
  Upload's row reads Browse, the chosen file, then Upload against the right edge.
- **The palette is the app's own, and the accents are chosen for contrast, not for a name.** It
  began near a well-known palette and diverged accent by accent ("intensified at the user's
  request"); on 2026-09-19 Juri judged it similar but not that palette any more, and its credit
  went from the About screen, the README, the site's imprint and every comment, the flavour names
  with it (`KioskTheme.darkPalette()` and `lightPalette()`). On 2026-09-12 the light theme's
  accents were darkened, because the earlier values did not clear WCAG's 4.5:1 for text on this
  app's card colour: measured, subtext 3.73, blue 3.71, red 4.10, green 2.58, yellow 2.28. The app's stats
  readout was worse still, 1.02 to 2.13 on the light card, because the service bakes its colours
  into the markup and has no screen; it recolours itself for a light surface now, and is unchanged
  over the dashboard, where it sits on a black plate. Hue and saturation are kept exactly in every
  case; only lightness moved, by the smallest amount that clears the minimum, and the dark theme
  needed nothing. **Both surfaces are audited by measurement rather than by eye**: every pair
  either one actually draws is listed and checked, and the only remaining miss is the 1 px row
  separator, which is decorative. Juri's rule, 2026-09-12: "we must make the themes look good",
  and no palette's name is a constraint.
- **What the review of 2026-09-19 found and fixed, all on the branch's last day.** An upload from
  a playlist's page landed in whichever playlist was in use, because the upload handler never
  passed the page's id to `saveLocal`; it does now. Every playlist edit goes through
  `PictureLibrary.editPlaylists`, one lock for the web admin, Home Assistant and the panel, where
  each had its own load-modify-store and a tick in the browser could undo a switch from a card.
  A playlists file that cannot be parsed is moved aside under a dated name and said for a day,
  instead of being overwritten by the next save. A picture is dropped from a playlist only when
  MediaStore answered for every picture and at least one is still there: a provider that does not
  answer throws `PictureBrowser.Unavailable` rather than looking like a missing row, and every
  picture vanishing at once is treated as an ejected card, not a deletion. The migration stores
  only when it changed something, which ends a loop on an ordinary install waiting for its
  permission (store, publish, list, migrate, store). The dropped-pictures sentence lasts ten
  minutes instead of being swallowed by the first reader, which was the telemetry publish.
  Discovery goes out again before a state publish when the playlist names differ from what it
  announced, so a new or renamed playlist reaches the Home Assistant select without a reconnect.
  `screensaver.start` is refused while an operator is in the settings, because the activity
  answered it by showing the dashboard over a half-made draft. The web's Delete asks first, as the
  panel does. The held rows' labels are looked up on the worker and cached per page. The
  retired folder feature's leftovers went: the SAF keys in `KioskConfig`, the picker runnable, the
  `screensaver.folder` and `screensaver.pick_folder` commands with their test, and the
  `/api/pictures/folder/pick` route. `READ_MEDIA_VISUAL_USER_SELECTED` is declared and accepted
  as a partial grant on Android 14, per the vendor's page; **not yet tried on an Android 14
  device**. A second pass on 2026-09-20 closed what the first left half done: the panel's own Save,
  Use, Delete and Create go through `editPlaylists` too; the permission result is judged by what
  the browser can now read, not by the first answer; the activity ignores a `screensaver.start`
  that arrives with settings open instead of showing the dashboard; the migration's matching step
  retries by the minute, not per listing; playlist names for discovery come from the document last
  read or written, not a third file read per publish; MediaStore names are cached for half a
  minute so a listing under a stats poll is not a query per picture; a one-picture playlist whose
  file is gone is still cleaned; and a store that throws without the permission still says
  "picture". A separate security pass found nothing.
- **Both surfaces are Material 3 since 2026-09-23, one design drawn once.** Decided from the drawings
  in `../media/drafts/material3/` (generated by `build_final.py`; `INVENTORY.md` beside them lists
  every control on both surfaces with its decision, and a redesign is checked against that list,
  never against a screenshot of one surface, because the first drawings were made from web
  screenshots and missed the panel's Open dashboard button). The rules: the colour roles are
  derived from the palette (`KioskTheme.card/primaryContainer/secondaryContainer/...` and the
  `--card/--primary-container/...` mixes in `admin.css`), so a card, a container and its ink match
  across the two surfaces; cards are the base lifted a step with a 12 dp corner and no border;
  text fields are outlined with the label on the border and the helper text under the box in the
  field's own slot, keeping its wording ("(blank keeps the current one)" is the one text that
  moved out of a label); buttons are 40 dp pills, filled for a card's one main action, tonal for
  the second, outlined for a command that acts now and stores nothing, text for navigation, and
  red only inside a confirmation; **anything repeated in a row is a 40 dp icon button** with a
  48 dp target, a tooltip and a spoken name (edit, delete, remove, save the name, the pager's
  chevrons, the view); on/off settings are switches, the playlist in use is a radio, pictures in
  a playlist are check boxes on rows and check circles on tiles, and the header box over a page
  ticks or clears the page shown (the Select all and Select none buttons went). **The settings are
  one menu of sections**, each a glyph, its name and a one-line summary of stored values only:
  below 840 dp an accordion no wider than 720 dp with one section open and remembered
  (`admin_menu.js` on the web, UI preference `open_section` on the panel), from 840 dp Material's
  list-detail, 360 dp of list and at most 640 dp of open section, so no panel spans a landscape
  tablet; Quick actions (web only) and About are the last two sections, About holding the legal
  pages and the version, which links to the repository on the web. **Back is the arrow in the app
  bar, once**; the "← Back" buttons at head and foot went, and the theme control sits in the app
  bar (segmented on a wide web page, one cycling icon button on a narrow one, the sun/moon on the
  panel). **A folder is shown in one of four views**, list, details, small and big thumbnails,
  cycled by one icon button in the Content card's head and stored as one preference for both
  surfaces (`KioskConfig.pictureViewOf`); thumbnails are made once by the panel into
  `cache/thumbs/` (`PictureLibrary.thumbnail`, 256 px JPEG, keyed by address) and served to the
  web page by `GET /api/pictures/thumb`, which answers only for MediaStore rows and uploads. The
  details view reads size, date and dimensions from the same MediaStore row. A dark panel reports
  `brightness_percent` 0 rather than null (same day, at Juri's request). The bullets below that
  describe the earlier button weights, the button colour rule and the smaller row buttons are
  history from that point on; what they decided about *behaviour* still holds.
- **Three weights, none of them hollow (chosen 2026-09-12 after five treatments were compared on
  the live pages).** Filled is the action of the thing it sits in and every action inside a list
  row; outlined is a command that acts now and stores nothing; **tonal** is navigation. Tonal
  rather than a text button because the same language is mirrored on the panel's touch UI, where
  there is no hover, and a control with no body until you point at it is one nobody finds; Material
  is the precedent ("tonal is useful where a lower-priority button requires slightly more emphasis
  than an outline would give"). **A tonal label is `--ink-alt`, never the hue**: the
  hue on a tint of itself cannot reach 4.5:1 at any strength. Measured at 18% into the card: 4.74
  dark and 5.62 light, borders clearing 3:1 in both. There is one tonal hue, the blue; a purple one
  was built the same day and deleted unused rather than left in the stylesheet. The panel carries
  the same tier (`KioskActivity.tonalButton`, `KioskTheme.inkAlt`), so the two surfaces are one
  design rather than two that resemble each other. **A navigation button that carries a role class
  is that role first**: Edit is a GET form and green, and only an unclassed button in a GET form is
  tonal, which is what the `:not([class])` in that rule is for. Counted across the whole admin
  the same day, exactly **two rows** mixed a filled button with a hollow one, Save + Open once and
  Browse + Upload; a hollow button beside a filled sibling is what reads as abandoned, so both
  gained a body. The unit for counting filled buttons is the **card**, following Atlassian's
  per-section and Polaris's per-card rule rather than Material's, Carbon's and Primer's
  one-per-page, which is a deliberate relaxation.
  **The panel's buttons carry the web's measurements since 2026-09-19** (Juri: the same colours and
  style everywhere in the app, the screensaver pages in particular): padding .5rem .9rem, a 10 px
  radius, a 1 px border, a 2 px hard edge and .9rem text become 8 by 14 dp, 10 dp, 1 dp, 2 dp and
  14 sp (`KioskActivity.webShaped`), a row button .25rem .6rem at .8rem, the tonal edge the hue at
  45% over the card, and the platform Button's 48 dp minimum height and 88 dp minimum width are
  cleared, which is what had made them slabs beside the browser's; `buttonRow` no longer forces a
  160 dp minimum either. Inputs, radios and check boxes keep their touch sizes.
- **A button's colour says what it does, on both surfaces.** Juri's rule, 2026-09-11: the main
  colour is a press that applies a setting permanently (every Save, Rename, Use, and "In the
  playlist" as the mark that something is enabled); a plain outline is a visible action that saves
  nothing (Open once, Reboot, Reload, Display on and off, Preview, Back); **red deletes
  something** (Delete a picture, Delete a playlist, and the confirm screen's own button); **green
  adds something** (Create playlist, Upload, Add to playlist, and **Edit**, which opens the page
  where pictures are added: Juri's call on the glass, 2026-09-12, "it looks like it fits better").
  Taking a picture out of a playlist is the main colour and never red, on both surfaces: red here
  deletes a file or a playlist and this deletes neither. `button.danger` and `button.add` in
  `admin.css`, `dangerButton` and `addButton` in `KioskActivity`, both built on the same shape and
  lift as the main button so a row of mixed buttons still reads as one family. The rule is applied
  across the pictures and screensaver surfaces; everything else it names already followed it.
  Buttons on the web page also take the page's own font, not the browser's button font, which is
  what left the Browse label three pixels taller than the Upload button beside it.
- **The screensaver page is three panels, on both surfaces, named after what is chosen.**
  Juri's structure, 2026-09-11: **"Screensaver mode"** holds the mode chooser and nothing else;
  the second panel is named after the mode (`Dimmed page options`, `Black film option` singular,
  `Web page options`, `Pictures options`) and holds everything that mode uses, in the order idle,
  display-off, the mode's own field, the wake choice, then the sentence it all adds up to, then
  Show it now; and **"Playlist"** appears only for the Pictures mode with this panel as the source.
  **Off has no second panel at all**, his decision and his words: "it is Off so there is no
  settings for it in any case". The times keep applying the moment a mode is picked. On the web
  the legend is rewritten from the chooser's own option text (`admin_setting.js`), so a mode
  changed without a reload renames its panel and the two surfaces cannot drift over a word. The
  mode is **five radios on both surfaces** (2026-09-11): it is the only chooser on the page whose
  value changes what else is on the page, so it is worth seeing at once. The settings page's own
  Screensaver card keeps a menu, because it is one card among a dozen and every other chooser
  there is a menu; `admin_setting.js` and `admin_stats.js` read and follow both shapes. Web radios
  are drawn rather than given `accent-color`: Chrome derives an unchecked radio's ring from it and
  against this purple on a dark scheme the ring came out khaki. **A mode with no wake choice shows
  none**, rather than a disabled one with a note explaining it: the black film has nothing to look
  at on waking, and a control that can never be enabled is clutter. This
  also fixed a real bug he found: with any mode but Pictures the second web panel was still headed
  "Pictures" and was **empty**, because it held the picture fields alone while the web page's
  address and the dimmed brightness sat in the first panel.
- **The web browser has the panel's "nothing opened yet" state.** It used to open the top of the
  volume on arrival, so it had no way to say "pick a folder"; the top of the volume is a folder
  like any other and is now reached by tapping it. Absent and empty are different answers for the
  `at` parameter, which is why `browseTarget` returns null rather than "" when it is missing.
- **The screensaver page does not use the settings page's multicol.** `.saver` is an explicit grid
  of `auto-fill` columns with a 30 rem floor and a 72 rem cap, so the page is two equal columns at
  any desktop size and one on a phone, and the Playlist panel spans the pair rather than the
  window. Multicol balances by height, and with two boxes in four columns it put one at the far
  left and one at the far right with a hand's width of nothing between them, and moved the right
  one every time a scrollbar changed the width by a pixel (Juri, 2026-09-11, with a screenshot).
  The playlist table is sized to its content rather than stretched, for the same reason: at full
  width the Rename cell took every spare pixel and pushed Delete to the far edge of the card.
- **Two things the screensaver page deliberately does not have.** There is no "Back to the page"
  button beside "Show it now": it ends a showing screensaver, which is what Display on already does
  to a lit panel, and on a dark panel it ends one without lighting the panel, which nobody presses a
  button for; the tablet's own page never had it (Juri asked what distinguished them, 2026-09-11).
  The `screensaver.stop` command is unchanged for MQTT and the API. And a page below the settings
  page carries its heading alone, with no subtitle and no status chip: the panel's id, its address
  and its load belong where somebody is configuring the panel, not on a page about one feature of
  it. The way back is a "Back" button at the top and at the bottom, the same on the privacy and
  terms pages, because a page you have to scroll to read is one you would have to scroll back up to
  leave.
- **`screensaver.playlist` switches the playlist by name**, and a Home Assistant select carries the
  panel's own names. By name because that is what a person and a card know, and an unknown name is
  refused with the names that do exist. The select's "no playlist" option is the word `None`,
  because a select cannot hold an empty option, so the panel treats that word as none unless a
  playlist is actually called that; without it, clearing the playlist from a card was refused
  (found over MQTT, 2026-09-10). Uploads still need no permission at all and appear as their own
  folder in the browser.
- **Captions and uploads.** A caption is keyed by the picture's own address, at most 200
  characters, and there is no file-name fallback any more: reading the name as a fallback is what
  crossed captions between same-named pictures in different folders (a second folder's `pd_2.jpg`
  shown on the glass under the first folder's "The Great Wave off Kanagawa, Hokusai", measured
  2026-09-10). `migrateCaptionKeys` moved the old name keys onto the addresses they were written
  for and then dropped every bare name; `captions_key_version` is 2, so a panel that already ran
  the first pass gets the second sweep too. The caption is also the friendly name the playlist page
  edits, not a second field. Only uploaded picture files may be deleted; a picture chosen from the
  panel's own storage is only removed from the playlist. Multipart parsing rejects the whole
  truncated request and preserves boundary-prefix bytes inside images. Authentication and
  cross-site checks precede body allocation. Only exact POST `/api/pictures` gets 24 MiB/120 s; one
  process-wide upload permit prevents multiplying that allocation by worker count. Other requests
  retain 16 KiB/8 s. Uploads are not streamed: body and part copies still cost memory.
- **Credited display.** Decode uses a power-of-two sample with at most 2,097,152 ARGB pixels
  (8 MiB) per bitmap, independent of image shape. Generation and frame/source checks discard stale
  work, including work invalidated by sleep. Wake resumes an interrupted initial decode even for
  one-picture-per-cycle. **The credit line is the title and the names, and never a web address**
  (2026-09-10, after reading the lines on the panel): Commons carries artist, licence short name,
  "Wikimedia Commons" and the supplied Credit and Attribution notices, and Bing its `copyright`
  line verbatim, while the licence address and the source-page address stay in the picture and in
  the cache manifest without reaching the glass, because a wall panel is read from across a room
  and nobody types a URL off one. A supplied notice that is nothing but an address is dropped for
  the same reason; one that names somebody is shown as the source worded it. Online credits cannot
  be disabled. **One credit panel at a time**, decided the same day: the outgoing picture keeps the
  line until it has left the glass and the incoming one takes it in the transition's end action.
  Stacking both attributions was built that morning and rejected that afternoon, because a second
  panel appearing over a picture still on screen reads as a fault; the caption always names a
  picture that is visible either way. If a full credit cannot fit, refuse the image rather than
  ellipsize, because an ellipsis is not attribution. Error messages clear old pictures and picture
  telemetry. The composed credit is what a cache manifest stores, so
  `PictureLibrary.ATTRIBUTION_VERSION` invalidates every manifest an older build wrote; it is 3.
  Attribution is not evidence of permission to redistribute Bing photographs: its unofficial
  endpoint remains an opt-in product/legal risk, not a licence supplied by Muralis.
- **Settings stay shared.** The existing dispatcher validates every screensaver setting on all
  three surfaces. Every screensaver setting is a discovery entity (read back off the broker
  2026-09-10: 36 components on the device-owner Lenovo, sixteen of them the screensaver's, the
  newest being the playlist select), and
  the four boolean switches use a template that renders `None`, which Home Assistant reads as
  unknown, for a missing field, a null, a non-boolean and a `screensaver` block that is not a
  mapping; nine such payloads were rendered through Jinja to check it. **Verified against a real instance 2026-09-10**: a throwaway Home
  Assistant in podman consumed the discovery document and built 35 entities per panel, fifteen of
  them the screensaver's, 22 entity round trips through Home Assistant services all matched the
  panel, and the four boolean switches read `unknown` for each of the four bad payload shapes. **A mode changed under a showing
  screensaver ends it** and the next one comes after the idle time in the new mode, which is
  deliberate and documented at `tickScreensaver`; a same-mode change (a dim floor, a transition,
  a playlist revision) is re-applied in place **with the clock towards display off left where it
  was**, measured on the Lenovo: a 40 s off timer still fired at 40 s across an in-place change at
  10 s. The same-boot screensaver start timestamp survives an activity rebuild too, measured the
  same way across `kiosk.restart`. **A local decode failure used to be remembered until the
  process restarted**, so a panel whose storage came back kept saying a selected picture cannot be
  read through three clean cycles and a rebuild, cleared only by a reboot, because the record was an
  in-memory `problems` map with no counterpart on success. Fixed 2026-09-10: a successful local
  decode clears it, which is the only event that actually answers the question the sentence asks. The secondary WebView handles renderer death and leaves the
  saver, **demonstrated on the Lenovo 2026-09-10** once rooted debugging was enabled (killing
  another app's isolated renderer needs root): `Renderer process crash detected` was followed by
  `Screensaver off (screensaver renderer ended)`, `renderer_deaths=1`, HTTPS still answering 200,
  MQTT still accepting commands, a new renderer spawned and the dashboard back. Renderer memory cannot be
  inferred from the app's own heap and is reported as its own process: on the Lenovo the app sat
  at 162 to 187 MB and the renderer at 95 to 126 MB across dashboard, second WebView, one picture,
  repeated fades, four source changes and six stop/start cycles, with no growth, no OOM, no
  renderer death, and file descriptors steady at 188 to 191 (210 while the second WebView is up).
- **The HTTPS server's capacity is sized for a browser, not for curl.** 16 workers, 12 connections
  per host, a queue depth of 8, and a socket that has sent nothing by 2 s is closed, which is what
  keeps a browser's speculative connections from spending the whole per-host budget. Measured on
  the Lenovo 2026-09-10 against the failure reported that morning: six silent sockets held open
  from one address no longer cost anything, the admin page still loads in half a second; twelve or
  twenty do refuse the next connection, immediately rather than after a ten-second hang, and
  capacity is back inside 2.6 s without anyone closing them. Capacity refusals are logged, one
  line per 30 s. Failed TLS wrappers and queued sockets close on shutdown. **The plain-HTTP redirect
  was broken for a real browser and is fixed** (2026-09-10): the 301 arrived with its headers and
  `Content-Length: 50`, the body never did, and the connection ended in a reset, so Chrome rendered
  an empty document and Firefox hung. curl printed the headers it got and looked fine, which is how
  it survived a first diagnosis. Three causes, all now closed. `handleConnection` had begun
  assigning `channel = tls` *before* the handshake, so the `finally` block closed the TLS wrapper
  over the same file descriptor the raw-socket 301 had just been written to; the channel is now the
  TLS socket only after a successful handshake, with a separate `tlsToRelease` so a failed wrapper
  is still freed. The header and the body left in two writes and now leave in one. And every
  connection ends with a **lingering close**, FIN, then drain the client's unread bytes to a 500 ms
  deadline, then close, which is what Apache's `ap_lingering_close` and nginx's `lingering_close`
  do and what any early refusal needs anyway: a 401 answered before a 24 MiB upload is read leaves
  unread bytes by design. Measured afterwards: 186 bytes, the full declared body, a clean FIN.
- **Fleet is not implemented by a local playlist.** Section 10b of the fleet design still owns
  content hashes, copied fleet-store bytes and member sync. Document URIs are local references,
  never cross-panel IDs. Keep uploads alongside grants so removed or cloud storage is not required
  for a self-contained panel. No claim of fleet compatibility, and none of unattended readiness:
  the open items are in `../review-screensaver-keyguard-2026-09-10.md`. The three that were defects
  rather than gaps, the plain-HTTP redirect, the legacy caption keys and the uncleared decode
  failure, were all fixed and measured on 2026-09-10; what remains there are gaps, and a soak is
  still the largest of them.

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
  default. That combination reads as two bugs, and on 2026-09-07 it read that way to the maintainer on a
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
  `test.mosquitto.org` typed into the broker box arrived as `test. mosquito. org` (seen
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
