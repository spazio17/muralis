# Muralis

This file tells an AI assistant what Muralis is and which rules the code keeps. It is a map, not a
specification and not a logbook: one line per feature area, no history, no tests, nobody's setup.
When a feature is added, extend the line of its area only if the area itself changes; the details
belong in the code and its comments.

## What Muralis is

Muralis turns an Android tablet into a screen on the wall that shows one web page and looks after
itself: a smart-home dashboard, an office board, a menu, any page on the local network. It locks
the device to that page, recovers when the page or the network fails, and can be controlled from a
browser or from home automation. It needs no cloud, no account and no server of its own.

It is an ordinary Android app on the public SDK only (no platform signature, no privileged
permissions), installed from Google Play or by a QR code during the device's first setup. The
strongest lock-down needs the app to be the device owner; without that it still runs, visibly with
fewer protections. Package `org.spazio17.muralis`. Internal class names keep an old `Kiosk` prefix
on purpose.

## What it does

- **Kiosk.** One page full screen under lock task, or one chosen app (App lock), or cameras as the
  main view. The way out is a recorded combination of corner taps, optionally behind a PIN.
- **Self-recovery.** Failed, frozen and crashed pages and browser engines come back by themselves,
  and the app refreshes itself once at night.
- **Display.** Display off, brightness, orientation, Ambient (what shows while nobody uses the
  panel), a status light drawn as a coloured frame, pop-ups over the screen, and network cameras.
- **Remote control.** A web admin over HTTPS, an HTTP API and MQTT with Home Assistant discovery,
  a screenshot and a diagnostics file on request, a settings file and a setup QR.
- **Sensors and automations.** The device's sensors, its camera and microphone, Bluetooth beacons
  and NFC, and "when this, then that" rules that act on the panel itself.
- **Fleet.** Panels on one network trust each other by their own keys and manage each other from
  any web admin: joining by a one-time code, shared settings, groups, alerts and an invitation QR
  for a new tablet. No server.
- **Integrations.** Data from other services as sensors for the automations and MQTT,
  one folder each under `app/src/main/integrations/`, compiled in and never downloaded.
- **Free and Pro.** Everything one panel does is free, with no device limit. Muralis Pro, bought
  once through Google Play, is the fleet.

## How the code is shaped

- **One command set, several surfaces.** `KioskCommandDispatcher` holds the commands that HTTP and
  MQTT share (`HttpAdminServer`, `MqttController`), so a command and a refusal behave the same
  wherever they arrive, and the validators that more than one surface calls before storing
  anything. Feature-specific rules live beside their feature.
- **Activity and service.** `KioskActivity` draws every screen of the panel; `KioskService` runs the
  remote surfaces, telemetry, sensors and the supervision that brings the dashboard back.
  `MuralisApplication` starts the service on a device-owner install whatever started the process.
- **Decisions in pure classes.** Recovery, restart scheduling, display off, the Ambient clock,
  escape sequences, provisioning and similar decisions live in classes with no Android imports,
  covered by host tests that run without a device. Keep new decision logic in that shape.
- **Settings and secrets.** `KioskConfig` reads and writes settings through an editor that touches
  only the fields it is given, so one surface never reverts another's change from a stale copy;
  forms that save several fields carry a baseline and are refused when it changed meanwhile.
  Secrets live encrypted in `SecretStore` under a key in the Android Keystore. Settings and
  secrets are in device-protected storage, because the service starts before the first unlock.
- **Fleet.** `FleetIdentity` (the panel's key), `FleetTls` (mutual TLS that admits a peer by its
  key's hash), `FleetServer` (the member endpoint, on its own port), `FleetDocument` (the signed
  list every member holds) and `FleetProfile` (shared settings, applied through the same commands
  and checks as a person's change).

## Rules the code keeps

- **Never lock someone in.** Lock task and the home-screen role engage only after both escape
  combinations exist. The escape works from every screen, App lock's app included. Only this app,
  and in App lock the one app chosen, is allowed under lock task; anything else is reached by
  releasing lock task, never by widening that list.
- **Remote surfaces fail closed.** No default password or PIN. The web admin listens only once a
  password of sufficient length exists; MQTT starts only once a broker is set. The fleet's member
  endpoint listens on every panel but admits only keys the panel trusts, apart from the narrow
  join, invitation and check-in routes while they are open. Request sizes are bounded.
- **A refusal always has a reason,** and a command never claims a success it did not have.
- **Secrets are never in plain text,** neither stored nor drawn on screen: password boxes render
  empty, and empty means "keep the current one".
- **Ports.** The app's own ports are settings between 1024 and 65535, checked before they are
  stored, refused when taken, and never silently replaced.
- **A change shows everywhere.** A change made on the panel, in the web admin or over MQTT shows on
  the others within seconds.
- **No backlog.** A panel acts when something arrives or drops it once it is stale; it never
  replays in one go what it missed while asleep or stopped.
- **Self-healing has no user switch** and no memory threshold.
- **Home Assistant discovery.** An entity's key, id and name say the same thing, and a panel
  removes from Home Assistant only what it announced itself.
- **One design on both surfaces.** The panel and the web admin are one Material 3 design from the
  ultramarine seed, light and dark, with the same sections, words and behaviour. Plain words; a
  familiar glyph or control before an explanatory sentence.
- **Cameras are only streamed,** never written to the device.
- **Legal texts** are copies of the canonical texts in the website repository; the release
  workflow fails when they differ.
- **Play's automatic integrity protection stays off.** It makes the app refuse to run unless Play
  installed it, which breaks the QR installation.
- **QR setup on Android 12 and newer** needs the app to answer the two setup requests (management
  mode and policy compliance); it asks for a fully managed device and answers at once.

## Platform

- minSdk 26 is a real target; every call newer than that needs a working fallback and a version
  check. targetSdk follows Google Play's yearly floor.
- Device owner (by QR during setup, or over adb on a fresh device) is the highest privilege the
  app can reach. Features that need it degrade visibly without it.
- Three kinds of window: a device-owner panel (full screen), an ordinary install with system bars,
  and recent Android, which draws apps edge to edge.
- Boxes for machine values (addresses, ports, ids) switch off the keyboard's corrections.

## Building

The build and the host tests run in a container with pinned tools (`scripts/build-app.sh`,
`scripts/test-host.sh`). The version comes from git (`git describe` against the latest `v*` tag,
the commit count as version code). CI builds every push; a manual workflow tags a release and
attaches the signed bundle. A pull request's title is its line in the release notes.
