# Calendar from a link

Whether something is on now and what comes next, from a calendar's secret iCal address. Google,
iCloud, Outlook, Nextcloud and most calendar services publish one. Rules such as "when Busy turns
on, the status light goes red" or "when Minutes to next falls below 5, wake the display" make the
panel a room or family board on its own.

## What you need

The calendar's secret address in iCal format, ending in `.ics`:

- Google Calendar: Settings, the calendar, "Secret address in iCal format".
- Outlook / Microsoft 365: Settings, Calendar, Shared calendars, Publish a calendar, ICS link.
- iCloud: share the calendar as a public calendar (a `webcal://` link works).
- Nextcloud: the calendar's menu, Copy subscription link.

The link is the password to the calendar, so the panel keeps it encrypted and never shows it.

## Settings

| Setting | Required | Default | What it is |
| --- | --- | --- | --- |
| Calendar link (iCal) | yes | | `https://`, `http://` or `webcal://` |
| All-day events count as busy | no | No | Yes makes a holiday or a day-long event "busy" |
| Ask every (seconds) | no | 900 | 300 to 86,400 |

## What it gives Muralis

| Value | Type |
| --- | --- |
| Busy | on while an event is on |
| Now | the title of the event on now, empty when none |
| Next | the title of the next event within two weeks |
| Next starts | the time it starts |
| Minutes to next | number, minutes |

## Limits

- Repeating events are read within the next two weeks, in the event's own time zone: daily and
  weekly (on chosen days), monthly (on one day of the month, or one weekday such as the first
  Monday or the last Friday), yearly on the date it started, with an end date, a count, skipped
  dates and moved single occurrences. A repeat rule with anything more, such as several weekdays
  in a month or "the fourth Thursday of November", counts the event once, at its start.
- With "All-day events count as busy" on No, holidays and day-long events are left out of Next as
  well as Busy.
- Cancelled events are left out. Time zones are read from the calendar; Outlook's Windows zone
  names fall back to the panel's own zone.
- A calendar larger than 4 MB, or slower than 30 seconds, is a failure.
- A link that answers 401 or 403 was withdrawn or replaced: the asking stops until a new link is
  saved. Other failures, a 404 included, make the values unavailable after three in a row.

## Removing it

Remove takes its values out of the automations and MQTT; rules that wait for them stay
and do not run until they are changed.
