/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Calendar from a link: parsing, repeats, exceptions, the values at a moment, each failure. */
public final class CalendarLinkIntegrationTest {

    // Wednesday 2026-10-21 10:15 UTC.
    static final long NOW = ZonedDateTime.parse("2026-10-21T10:15:00Z").toInstant().toEpochMilli();

    static final String ICS = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT", "UID:a", "SUMMARY:Stand-up", "DTSTART:20261005T100000Z",
            "DTEND:20261005T103000Z", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=20",
            "EXDATE:20261026T100000Z", "END:VEVENT",
            // The stand-up of Monday the 2nd of November moved to 11:00 that day.
            "BEGIN:VEVENT", "UID:a", "RECURRENCE-ID:20261102T100000Z", "SUMMARY:Stand-up (late)",
            "DTSTART:20261102T110000Z", "DTEND:20261102T113000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:b", "SUMMARY:Lunch with", "  the team", "DTSTART:20261021T120000Z",
            "DURATION:PT1H", "END:VEVENT",
            "BEGIN:VEVENT", "UID:c", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20261021",
            "DTEND;VALUE=DATE:20261022", "END:VEVENT",
            "BEGIN:VEVENT", "UID:d", "SUMMARY:Cancelled", "STATUS:CANCELLED",
            "DTSTART:20261021T110000Z", "DTEND:20261021T113000Z", "END:VEVENT",
            "BEGIN:VEVENT", "UID:e", "SUMMARY:Board", "DTSTART;TZID=Europe/Vienna:20261021T140000",
            "DTEND;TZID=Europe/Vienna:20261021T150000", "END:VEVENT",
            "END:VCALENDAR", "");

    public static void main(String[] args) throws Exception {
        Integrations.Definition calendar = CalendarLinkIntegration.DEFINITION;
        require(Integrations.DEFINITIONS.get("calendar") == calendar
                && Integrations.AGENDA.equals(calendar.capability), "registered as agenda");

        List<CalendarLinkIntegration.Event> events = CalendarLinkIntegration.parse(ICS);
        require(events.size() == 6, "six events, the cancelled one included: " + events.size());
        require(events.get(2).title.equals("Lunch with the team"), "a folded line");

        Map<String, Integrations.Value> at = CalendarLinkIntegration.values(events, NOW, false);
        require(at.get("busy").flag && at.get("now_title").text.equals("Stand-up"),
                "busy with the stand-up at 10:15: " + at.get("now_title").text);
        require(at.get("next_title").text.equals("Lunch with the team")
                && at.get("minutes_to_next").number == 105, "next is lunch at 12:00");

        long mondayLate = ZonedDateTime.parse("2026-10-26T10:10:00Z").toInstant().toEpochMilli();
        Map<String, Integrations.Value> excluded =
                CalendarLinkIntegration.values(events, mondayLate, false);
        require(!excluded.get("busy").flag, "an excluded Monday was busy");
        long moved = ZonedDateTime.parse("2026-11-02T10:10:00Z").toInstant().toEpochMilli();
        Map<String, Integrations.Value> later =
                CalendarLinkIntegration.values(events, moved, false);
        require(!later.get("busy").flag && later.get("next_title").text.equals("Stand-up (late)")
                && later.get("minutes_to_next").number == 50, "the moved occurrence");

        long evening = ZonedDateTime.parse("2026-10-21T19:00:00Z").toInstant().toEpochMilli();
        require(!CalendarLinkIntegration.values(events, evening, false).get("busy").flag
                && CalendarLinkIntegration.values(events, evening, true).get("busy").flag,
                "all-day counts only when asked");
        long board = ZonedDateTime.parse("2026-10-21T12:00:00Z").toInstant().toEpochMilli();
        boolean found = false;
        for (CalendarLinkIntegration.Occurrence one : CalendarLinkIntegration.occurrences(events,
                NOW, NOW + 86_400_000L)) {
            found |= one.title.equals("Board") && one.start == board;
        }
        require(found, "Vienna's 14:00 in summer time is 12:00 UTC");
        long cancelled = ZonedDateTime.parse("2026-10-21T11:10:00Z").toInstant().toEpochMilli();
        require(!CalendarLinkIntegration.values(events, cancelled, false).get("busy").flag,
                "a cancelled event made it busy");

        require(startsIn("DTSTART:20000103T090000Z", "FREQ=DAILY", NOW, 1).size() == 1
                && startsIn("DTSTART:20000103T090000Z", "FREQ=DAILY", NOW, 1).get(0)
                .equals("2026-10-22T09:00Z"), "a daily event since 2000 reaches today");
        require(startsIn("DTSTART:20261005T090000Z", "FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", NOW, 7)
                .size() == 5, "daily on weekdays only");
        require(startsIn("DTSTART:20261005T090000Z", "FREQ=MONTHLY;BYDAY=-1FR", NOW, 14)
                .equals(Collections.singletonList("2026-10-30T09:00Z")), "the last Friday");
        require(startsIn("DTSTART:20260105T090000Z", "FREQ=MONTHLY;BYDAY=5MO", NOW, 60)
                .equals(Collections.singletonList("2026-11-30T09:00Z")),
                "a fifth Monday skips October and lands on 30 November: "
                + startsIn("DTSTART:20260105T090000Z", "FREQ=MONTHLY;BYDAY=5MO", NOW, 60));
        // A date is midnight in the panel's zone, so only the count is the same everywhere.
        require(startsIn("DTSTART;VALUE=DATE:19900315", "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=15",
                NOW, 160).size() == 1, "a birthday as Outlook writes it repeats every year");
        for (String unread : new String[] {"FREQ=MONTHLY;BYDAY=1MO,3MO",
                "FREQ=MONTHLY;BYDAY=-2FR", "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH",
                "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1", "FREQ=MONTHLY;BYMONTHDAY=-1",
                "FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=5"}) {
            require(startsIn("DTSTART:20261005T090000Z", unread, NOW, 60).isEmpty(),
                    unread + " was expanded instead of counted once at its start");
        }
        // New York keeps summer time a week longer than Vienna: 09:00 there is 13:00 UTC until
        // 28 October and 14:00 UTC on 4 November, whatever zone the panel is in.
        require(startsIn("DTSTART;TZID=America/New_York:20261007T090000",
                "FREQ=WEEKLY;BYDAY=WE", NOW, 15).equals(java.util.Arrays.asList(
                "2026-10-21T13:00Z", "2026-10-28T13:00Z", "2026-11-04T14:00Z")), "a repeat keeps the event's clock");

        Integrations.Instance instance = new Integrations.Instance("calendar1", "calendar",
                "Office", true, Collections.emptyMap());
        Integrations.clock = () -> NOW;
        Integrations.Result ok = calendar.source.fetch(calendar, instance,
                Collections.singletonMap("url", "webcal://example.org/basic.ics"),
                (url, headers) -> {
                    require(url.startsWith("https://"), "webcal was not turned into https");
                    return new Integrations.Response(200, ICS, 0);
                });
        require(ok.problem == null && ok.values.get("busy").flag, "a good ask");
        require(calendar.source.fetch(calendar, instance,
                Collections.singletonMap("url", "https://x/y.ics"),
                (url, headers) -> new Integrations.Response(403, "", 0)).credentialsRefused,
                "a withdrawn link stops the asking");
        Integrations.Result missing = calendar.source.fetch(calendar, instance,
                Collections.singletonMap("url", "https://x/y.ics"),
                (url, headers) -> new Integrations.Response(404, "", 0));
        require(!missing.credentialsRefused && missing.problem.contains("404"),
                "a 404 is asked again");
        require(calendar.source.fetch(calendar, instance,
                Collections.singletonMap("url", "https://x/y.ics"),
                (url, headers) -> new Integrations.Response(200, "<html>", 0))
                .problem.contains("not a calendar"), "html is not a calendar");
        require(calendar.source.fetch(calendar, instance,
                Collections.singletonMap("url", "https://x/y.ics?secret=1"),
                (url, headers) -> { throw new java.io.FileNotFoundException(url); })
                .problem.indexOf("secret") < 0, "the link leaked into the status line");
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        settings.put("url", "ftp://x");
        require(Integrations.check(calendar, settings, Collections.emptySet()) != null,
                "an ftp link was taken");
        System.out.println("CalendarLinkIntegrationTest passed");
    }

    /** The starts in UTC of one repeating event within {@code days} after {@code from}. */
    private static List<String> startsIn(String dtstart, String rrule, long from, int days) {
        List<CalendarLinkIntegration.Event> one = CalendarLinkIntegration.parse(String.join("\r\n",
                "BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:r", "SUMMARY:R", dtstart, "DURATION:PT1H",
                "RRULE:" + rrule, "END:VEVENT", "END:VCALENDAR", ""));
        List<String> starts = new java.util.ArrayList<>();
        for (CalendarLinkIntegration.Occurrence each : CalendarLinkIntegration.occurrences(one,
                from, from + days * 86_400_000L)) {
            starts.add(java.time.Instant.ofEpochMilli(each.start).toString().replace(":00Z", "Z"));
        }
        return starts;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
