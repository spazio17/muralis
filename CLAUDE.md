# Muralis

This file tells an AI assistant what Muralis is and which rules the code keeps. It describes the
app, not its history, its tests or anyone's setup. Keep it general: when a feature is added or
removed, add or remove a line here; leave the details in the code and its comments.

## What Muralis is

Muralis turns an Android tablet into a screen on the wall that shows one web page and looks after
itself: a smart-home dashboard, an office board, a menu, any page on the local network. It locks
the device to that page, recovers when the page or the network fails, and can be controlled from a
browser or from home automation. It needs no cloud, no account and no server of its own.

It is an ordinary Android app built on the public SDK only (no platform signature, no privileged
permissions), installed from Google Play or by a QR code during the device's first setup. The
strongest lock-down needs the app to be the device owner; without that it still runs, visibly with
fewer protections. Package `org.spazio17.muralis`. Internal class names keep an old `Kiosk` prefix
on purpose.

## What it does

- **Kiosk.** One page full screen, held by lock task with no system bars when the app is device
  owner.
  The way out is a combination of corner taps the person records, one for the settings and one for
  the system launcher, optionally behind a PIN. A panel with no page shows a parking screen that
  says what to do next.
- **Self-recovery.** Retries a page that failed, notices a page that froze or a server that
  restarted and reloads once, rebuilds a crashed browser engine, rebuilds it when memory runs low,
  and restarts itself (or rebuilds its browser engine) once at night. None of this has a setting
  to switch it off.
- **Display.** Display off (a real sleep where the device can be trusted, a black overlay
  otherwise), brightness, orientation, and Ambient, the optional screen while nobody uses the
  panel: a dimmed page, black, another web page, or the panel's own pictures from named
  playlists, then display off after a second delay.
- **Remote control.** A web admin served by the panel over HTTPS (its own certificate or an
  uploaded one, password required), an HTTP API, and MQTT with Home Assistant discovery. The
  panel and the web admin offer the same settings and show the same thing, except what needs a
  hand at the panel (calibrations) or a file (uploads, web admin only). A picture of the screen
  as the wall shows it, on request from the web admin, the HTTP API and MQTT (a Home Assistant
  image and button), the password being the gate; and a diagnostics file (device, permissions,
  status, counters, log) downloaded from the web admin, never uploaded.
- **Sensors and automations.** The device's sensors (light, proximity, movement, battery, power,
  network and others), the camera as a network camera with motion detection, a sound level from
  the microphone, Bluetooth beacons and NFC tags, and small "when this, then that" rules that act on
  them on the panel itself.
- **Fleet (being built).** Panels on one network will trust each other by their own keys, and any
  panel's web admin will manage all of them. No server, no cloud. So far each panel has its fleet
  key and a member endpoint, announces itself on the local network (mDNS), joins a fleet with a
  single-use code shown on its Join screen, or leaves it, and creates a fleet or adds another
  panel by reading that code with its camera; every member holds the fleet's signed list of
  panels, the members tell each other every few minutes that they are there and which
  version they hold, so one that fell behind fetches the list, and sections of settings (the
  dashboard, MQTT, the web admin, the display, Ambient) can be shared in a group of
  panels or with the whole fleet: groups are sets of panels with the same job, made with a name
  the person gives; a panel ticks the groups it belongs to and may stop following what the whole
  fleet shares; a shared section is applied on every panel of its scope, a group winning over
  the whole fleet, and a change to it on any of them reaches all of them. A panel at its welcome
  screen can be joined before anything locks: it takes the adding panel's escape combinations
  and PIN as its own, once, at that moment, and goes to the dashboard; a panel that has
  combinations keeps them, they are never shared afterwards. A page address may carry
  `{panel.name}`, `{panel.group}` and `{fleet.name}`, filled by each panel when it loads the
  page, so one shared address shows each panel its own page. Alerts are a shared section too
  (a panel offline, a page error, battery or temperature past a threshold, a combination used,
  a PIN lockout): each panel judges itself and one panel judges the fleet's offline, each alert
  is said once, on and off, as a notification on the panel, on a plain MQTT topic and to a web
  address; every member keeps the last versions of the fleet's list, with what each changed,
  and can restore one as a new version, membership kept as it is now. The Fleet page shows a
  picture of every panel's screen on request, fetched from each panel over the fleet's own
  link and never stored, and Identify shows a panel's name on its screen and says it.
- **Free and Pro.** Everything a single panel does is free, with no device limit. Muralis Pro, a
  one-time purchase through Google Play, is the fleet and what only makes sense across panels.

## How the code is shaped

- **One command set, several surfaces.** `KioskCommandDispatcher` holds the commands that HTTP and
  MQTT share (`HttpAdminServer`, `MqttController`), so a command and a refusal behave the same
  wherever they arrive, and the validators for inputs that more than one surface accepts, which the
  panel's screens and the web admin's forms call before they store anything. Feature-specific
  rules live beside their feature (automations, sensors, the look, certificates).
- **Activity and service.** `KioskActivity` draws every screen of the panel; `KioskService` runs the
  remote surfaces, telemetry, sensors and the supervision that brings the dashboard back if it
  disappears. `MuralisApplication` starts the service on a device-owner install whatever started
  the process.
- **Decisions in pure classes.** Recovery, restart scheduling, display off, the Ambient clock,
  escape sequences, the provisioning check and similar decisions live in classes with no Android
  imports (`RecoveryPolicy`, `RecyclePolicy`, `RelaunchPolicy`, `DisplayOffPolicy`,
  `ScreensaverPolicy`, `EscapeSequence`, `Provisioning` and others), covered by host tests that run
  without a device. Keep new decision logic in that shape.
- **Settings and secrets.** `KioskConfig` reads and writes settings; writes go only through its
  editor, which touches just the fields it is given, so one surface can never revert another's
  change from a stale copy. Forms that save several fields carry a baseline and are refused when
  the stored values changed meanwhile. Secrets live encrypted in `SecretStore` under a key that
  stays in the Android Keystore. Settings and secrets are kept in device-protected storage, because
  the service starts before the first unlock; pictures and playlists are files of the app's normal
  storage, read only once the device is unlocked.
- **Fleet.** `FleetIdentity` (each panel's own key and certificate), `FleetTls` (mutual TLS that
  admits a peer by the hash of its public key), `FleetServer` (the member endpoint on its own
  port, separate from the web admin) and `FleetDocument` (the signed list every member holds,
  and how two lists changed apart merge into one) and `FleetProfile` (a shared group's values on
  this panel, applied through the same commands and checks a person's change takes).

## Rules the code keeps

- **Never lock someone in.** Lock task and the home-screen role engage only after both escape
  combinations are recorded. The escape works from every screen. Only this app is
  allowed under lock task; another app is reached by releasing lock task first, never by widening
  that list. Accessibility services and foreign keyboards are restricted where the app is device
  owner, and the page cannot hand control to another app through non-web links.
- **Remote surfaces fail closed.** No default password or PIN. The web admin listens only once a
  password of sufficient length exists; MQTT starts only once a broker is set. The fleet's member
  endpoint is the one exception: it listens on every panel and admits only keys the panel trusts,
  plus, while a person keeps a join code open, an unknown key on the join route alone, and a
  removed key on the check-in route alone, to be told it was removed.
  Request sizes are bounded. A refusal is always answered with a reason (the one silence is an
  empty MQTT payload, which is how a retained command is cleared), and a command never claims a
  success it did not have (there is no "shutdown" command, because Android offers no way to do it).
- **Secrets are never in plain text,** neither stored nor drawn on screen: password boxes render
  empty, and empty means "keep the current one".
- **Ports.** The app's own ports (web admin, fleet member port) are settings between 1024 and 65535,
  checked before they are stored, refused when taken, never newly set to each other's port (a pair
  that already collides still saves unchanged), and never silently replaced by another value.
- **A change shows everywhere.** A change made on the panel, in the web admin or over MQTT shows
  up on the others within seconds; a change applied outside the dispatcher asks for a telemetry
  publish itself. Values whose validity depends on the device (a port, an address)
  are checked against the device when the field is left, with a coloured border.
- **Self-healing has no user switch** and no memory threshold: the app cannot measure the browser
  engine's memory, so it reacts to the system's own signals and restarts nightly.
- **Home Assistant discovery.** An entity's key, id and name say the same thing. A panel removes from
  Home Assistant only what it announced itself, so a fresh install starts with nothing to remove.
- **One design on both surfaces.** The panel and the web admin are one Material 3 design in the
  app's own palette, light and dark, with the same sections, words and behaviour. Plain words for
  the person in front of the panel; a familiar glyph or control before an explanatory sentence.
- **Legal texts** are copies of the canonical texts kept in the website repository; the release
  workflow fails when they differ.
- **Play's automatic integrity protection must stay off.** It makes the app refuse to run unless
  Play installed it, which breaks the QR installation.
- **QR setup on Android 12 and newer** needs the app to answer two setup requests (which kind of
  management, and policy compliance); Muralis asks for a fully managed device and answers at once,
  and leaves the lock-down to its own first-start screens.

## Platform

- minSdk 26 is a real target; every call newer than that needs a working fallback and a version
  check. targetSdk follows Google Play's yearly floor.
- Device owner (by QR during setup, or over adb on a fresh device) is the highest privilege the
  app can reach. Features that need it degrade visibly without it.
- Three kinds of window to keep in mind: a device-owner panel (full screen), an ordinary install
  with system bars, and recent Android, which draws apps edge to edge under transparent bars.
- Boxes for machine values (addresses, ports, ids) switch off the keyboard's corrections; boxes
  for names and captions stay ordinary text.

## Building

The build runs in a container with pinned tools (`scripts/build-app.sh`), and the host tests too
(`scripts/test-host.sh`). The version comes from git (`git describe` against the latest `v*` tag,
the commit count as version code); nothing in the project is edited to set it. CI builds every push;
a manual workflow tags a release and attaches the debug APK and the signed bundle. A pull request's
title is its line in the release notes.
