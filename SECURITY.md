# Security policy

Muralis runs on tablets that hang on walls in homes, practices and offices, often unattended. A
flaw in it can open a locked screen, a web admin or a home network to someone who should not be
there. Please report such flaws privately, and they will be fixed as fast as one person can.

## Supported versions

Only the latest release is supported: the one on Google Play and on the
[download page](https://muralis.spazio17.org/download/). A fix comes as a new release, never as a
patch for an older one.

## How to report

Report privately, never in a public issue, discussion, pull request or post.

- **GitHub (preferred):** the repository's
  [Report a vulnerability](https://github.com/spazio17/muralis/security/advisories/new) form.
- **E-mail:** spazio17@protonmail.com, with "Muralis security" in the subject.

Please include:

- what the flaw is and which part of Muralis it is in;
- what an attacker could do with it, and from where (on the network, at the panel, from the page);
- the steps to reproduce it, the Muralis version, the Android version and the device;
- a proof of concept or screenshots, if you have them, and a fix, if you have one.

## What happens next

| Step | Target |
| --- | --- |
| A first answer | within 7 days |
| An assessment: confirmed or not, and how serious | within 21 days |
| A fixed release | within 90 days |
| Public disclosure | after the fixed release, at the latest 120 days after the report |

Muralis is made by one person, so these are targets, not promises. A flaw that is used in the wild
is handled first. You hear how it goes, and you agree the disclosure date together. With your
consent you are named in the release notes and the security advisory. There is no paid bug bounty.

## Scope

**In scope:** the Muralis app and everything it serves or speaks:

- the lock-down: getting out of the kiosk or into another app without the escape combination,
  the PIN or the device owner's consent;
- the web admin and the HTTP API: getting in without the password, getting around its lockout,
  or reading or changing settings and secrets you should not reach;
- MQTT: commands or data that cross where they should not;
- the fleet: joining, impersonating or controlling a panel without its trust;
- secrets: a password, a key or a sign-in stored or shown in plain text;
- the setup QR and the download page: anything that lets a tablet install something other than
  the published, signed app.

**Out of scope:**

- the pages a panel shows, your broker, Home Assistant and other systems Muralis talks to;
- flaws in Android, the device's firmware or the system's web engine, unless Muralis makes them
  reachable;
- limits that are documented and by design: an ordinary install (not device owner) keeps the
  system bars reachable; corner taps can be watched and repeated, which is why a PIN can follow
  them;
- attacks that need someone who already knows the web admin's password or holds the unlocked
  device;
- denial of service by flooding a panel on your own network.

## Testing

Test on your own devices and your own network. Do not touch other people's panels, data or
networks, do not keep personal data you come across, and stop once you have shown the flaw.
Research done in good faith under this policy will not be met with legal action from the
developer of Muralis. This does not cover systems that belong to others.

## For people running Muralis

- Keep Muralis up to date.
- Use a long web admin password, and set a PIN after the escape combinations on panels in public
  places.
- Set Muralis up as device owner, by the QR during setup, for the full lock-down.
- Keep the web admin and MQTT on your own network; do not expose them to the internet.
