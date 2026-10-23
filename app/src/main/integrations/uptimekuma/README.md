# Uptime Kuma

Which monitors of an Uptime Kuma status page are down. A rule such as "when Any monitor down turns
on, the status light goes red" makes the panel a status board for an office or a home lab, with
nothing in between.

## What you need

An Uptime Kuma the panel can reach, and a status page with the monitors to watch (Uptime Kuma,
Status Pages). The page is public, so no key is needed.

## Settings

| Setting | Required | Default | What it is |
| --- | --- | --- | --- |
| Uptime Kuma address | yes | | where Uptime Kuma answers: `http://192.168.1.10:3001` |
| Status page | yes | | the end of the page's address: `/status/home` is `home` |
| Ask every (seconds) | no | 60 | 60 to 86,400; Uptime Kuma refreshes its page once a minute |

## What it gives Muralis

| Value | Type |
| --- | --- |
| Monitors down | number |
| Any monitor down | on or off |
| Monitors up | number |
| Down | text: the names of the monitors that are down, joined by commas |

A monitor in maintenance or still pending counts as neither up nor down.

## Limits

- Uptime Kuma calls the status page's API internal; if a new Uptime Kuma changes it, this
  integration says its answer "could not be read" and its values become unavailable, never "all up".
- After three failures in a row the values are unavailable and no rule fires on them.
- A monitor counts by its latest beat, as the status page shows it: one that stopped beating keeps
  its last state there.
- The status page as written in Uptime Kuma: on Uptime Kuma 1.23 capital letters must match.

## Removing it

Remove takes its values out of the automations and MQTT; rules that wait for them stay
and do not run until they are changed.
