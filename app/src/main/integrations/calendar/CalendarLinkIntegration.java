/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calendar from a link: the secret iCalendar (.ics) address Google, iCloud, Outlook, Nextcloud
 * and most calendars publish, read on the panel. It says whether something is on now, what comes
 * next and when, for rules such as "light red while busy" or "wake five minutes before". The
 * link is the password to the calendar, so it is kept as a secret.
 *
 * <p>Repeats are expanded within the next two weeks (DAILY and WEEKLY, both with BYDAY, MONTHLY
 * by one day of the month or one weekday such as 1MO or -1FR, YEARLY on its start date; INTERVAL,
 * COUNT, UNTIL, EXDATE), in the event's own time zone, and a moved or cancelled single occurrence
 * (RECURRENCE-ID) replaces the one it moved. A rule with anything else counts the event once, at
 * its start, never a guess.
 */
public final class CalendarLinkIntegration implements Integrations.Source {
    static final String ID = "calendar";
    static final String YES = "Yes";
    static final String NO = "No";
    /** How far ahead a repeat is expanded, and the next event looked for. */
    static final Duration AHEAD = Duration.ofDays(14);
    /** The most occurrences one repeating event may produce, a guard against a broken rule. */
    static final int MAX_OCCURRENCES = 5000;

    public static final Integrations.Definition DEFINITION = new Integrations.Definition(ID,
            "Calendar from a link", "free",
            "Busy now and what comes next.",
            Arrays.asList(
                    new Integrations.Field("url", "Calendar link (iCal)", Integrations.Scope.SHARED,
                            true, true, Integrations.Kind.TEXT, Collections.emptyList(), "", 0, 0,
                            "The secret address in iCal format, ending in .ics; kept encrypted."),
                    new Integrations.Field("all_day", "All-day events count as busy",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.CHOICE,
                            Arrays.asList(NO, YES), NO, 0, 0,
                            ""),
                    new Integrations.Field("interval_seconds", "Ask every (seconds)",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.NUMBER,
                            Collections.emptyList(), "900", 300, 86_400, "")),
            Arrays.asList(
                    new Integrations.ValueSpec("busy", "Busy", Integrations.Type.FLAG, ""),
                    new Integrations.ValueSpec("now_title", "Now", Integrations.Type.TEXT, ""),
                    new Integrations.ValueSpec("next_title", "Next", Integrations.Type.TEXT, ""),
                    new Integrations.ValueSpec("next_start", "Next starts", Integrations.Type.TIME,
                            ""),
                    new Integrations.ValueSpec("minutes_to_next", "Minutes to next",
                            Integrations.Type.NUMBER, "min")),
            900_000L, 300_000L, new CalendarLinkIntegration(), Integrations.AGENDA);

    @Override
    public String check(Integrations.Definition definition, Map<String, String> settings) {
        String url = settings.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            return null;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://")
                || lower.startsWith("webcal://") ? null
                : "the calendar link must start with https://, http:// or webcal://";
    }

    @Override
    public Integrations.Result fetch(Integrations.Definition definition,
            Integrations.Instance instance, Map<String, String> secrets, Integrations.Http http) {
        String url = secrets.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            return Integrations.Result.refused("no calendar link is saved");
        }
        if (url.toLowerCase(java.util.Locale.ROOT).startsWith("webcal://")) {
            url = "https://" + url.substring("webcal://".length());
        }
        Integrations.Response response;
        try {
            response = http.get(url, Collections.singletonMap("Accept", "text/calendar"));
        } catch (java.io.IOException unreachable) {
            return Integrations.Result.failed("the calendar did not answer"
                    + Integrations.why(unreachable), 0);
        }
        if (response.status == 401 || response.status == 403) {
            // A link withdrawn or replaced: only a new link brings it back. A 404 is asked again,
            // since a calendar server in maintenance may answer it for a while.
            return Integrations.Result.refused("the calendar refused the link (HTTP "
                    + response.status + ")");
        }
        if (response.status < 200 || response.status > 299) {
            return Integrations.Result.failed("the calendar answered HTTP " + response.status,
                    response.retryAfterMs);
        }
        if (!response.body.contains("BEGIN:VCALENDAR")) {
            return Integrations.Result.failed("the answer is not a calendar", 0);
        }
        boolean allDayBusy = YES.equals(instance.setting(definition, "all_day"));
        return Integrations.Result.of(values(parse(response.body), Integrations.now(),
                allDayBusy));
    }

    /** One occurrence: start and end in milliseconds, its title, and whether it is all day. */
    static final class Occurrence {
        final long start;
        final long end;
        final String title;
        final boolean allDay;

        Occurrence(long start, long end, String title, boolean allDay) {
            this.start = start;
            this.end = end;
            this.title = title;
            this.allDay = allDay;
        }
    }

    /** The values at {@code nowMs} from the calendar's occurrences. */
    static Map<String, Integrations.Value> values(List<Event> events, long nowMs,
            boolean allDayBusy) {
        List<Occurrence> all = occurrences(events, nowMs - Duration.ofDays(1).toMillis(),
                nowMs + AHEAD.toMillis());
        Occurrence current = null;
        Occurrence next = null;
        for (Occurrence one : all) {
            if (one.allDay && !allDayBusy) {
                continue;
            }
            if (one.start <= nowMs && nowMs < one.end
                    && (current == null || one.start > current.start)) {
                current = one;
            }
            if (one.start > nowMs && (next == null || one.start < next.start)) {
                next = one;
            }
        }
        Map<String, Integrations.Value> values = new LinkedHashMap<>();
        values.put("busy", Integrations.Value.of(current != null));
        values.put("now_title", Integrations.Value.of(current == null ? "" : current.title));
        values.put("next_title", Integrations.Value.of(next == null ? "" : next.title));
        values.put("next_start", next == null
                ? Integrations.Value.unavailable(Integrations.Type.TIME)
                : Integrations.Value.at(next.start));
        values.put("minutes_to_next", next == null
                ? Integrations.Value.unavailable(Integrations.Type.NUMBER)
                : Integrations.Value.of((next.start - nowMs) / 60_000L));
        return values;
    }

    /** One VEVENT as read. */
    static final class Event {
        String uid = "";
        String title = "";
        ZonedDateTime start;
        ZonedDateTime end;
        Duration duration;
        boolean allDay;
        String rrule = "";
        final Set<Long> exdates = new HashSet<>();
        /** The start of the occurrence this event replaces, or null for a master event. */
        Long recurrenceId;
        boolean cancelled;
    }

    /** The events of an iCalendar text; unreadable ones are left out, never guessed. */
    static List<Event> parse(String text) {
        List<Event> events = new ArrayList<>();
        Event event = null;
        for (String line : unfold(text)) {
            if (line.equals("BEGIN:VEVENT")) {
                event = new Event();
                continue;
            }
            if (line.equals("END:VEVENT")) {
                if (event != null && event.start != null) {
                    events.add(event);
                }
                event = null;
                continue;
            }
            if (event == null) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            String name = head.contains(";") ? head.substring(0, head.indexOf(';')) : head;
            String tzid = parameter(head, "TZID");
            boolean date = "DATE".equalsIgnoreCase(parameter(head, "VALUE"))
                    || value.matches("\\d{8}");
            try {
                switch (name.toUpperCase(java.util.Locale.ROOT)) {
                    case "UID":
                        event.uid = value;
                        break;
                    case "SUMMARY":
                        event.title = unescape(value);
                        break;
                    case "DTSTART":
                        event.start = time(value, tzid, date);
                        event.allDay = date;
                        break;
                    case "DTEND":
                        event.end = time(value, tzid, date);
                        break;
                    case "DURATION":
                        // RFC 5545 allows weeks ("P2W"), which java.time reads as days.
                        java.util.regex.Matcher weeks =
                                java.util.regex.Pattern.compile("P(\\d+)W").matcher(value);
                        event.duration = weeks.matches()
                                ? Duration.ofDays(7L * Integer.parseInt(weeks.group(1)))
                                : Duration.parse(value);
                        break;
                    case "RRULE":
                        event.rrule = value;
                        break;
                    case "EXDATE":
                        for (String one : value.split(",")) {
                            event.exdates.add(time(one, tzid, date).toInstant().toEpochMilli());
                        }
                        break;
                    case "RECURRENCE-ID":
                        event.recurrenceId = time(value, tzid, date).toInstant().toEpochMilli();
                        break;
                    case "STATUS":
                        event.cancelled = "CANCELLED".equalsIgnoreCase(value);
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException unreadable) {
                // One property that does not read leaves the rest of the event as it is.
            }
        }
        return events;
    }

    /** Every occurrence starting within [from, to), repeats expanded, moved ones in place. */
    static List<Occurrence> occurrences(List<Event> events, long from, long to) {
        Map<String, Set<Long>> moved = new LinkedHashMap<>();
        for (Event event : events) {
            if (event.recurrenceId != null) {
                moved.computeIfAbsent(event.uid, uid -> new HashSet<>()).add(event.recurrenceId);
            }
        }
        List<Occurrence> found = new ArrayList<>();
        for (Event event : events) {
            if (event.cancelled) {
                continue;
            }
            Duration length = event.end != null ? Duration.between(event.start, event.end)
                    : event.duration != null ? event.duration
                    : event.allDay ? Duration.ofDays(1) : Duration.ZERO;
            Set<Long> skip = new HashSet<>(event.exdates);
            if (event.recurrenceId == null && moved.containsKey(event.uid)) {
                skip.addAll(moved.get(event.uid));
            }
            for (ZonedDateTime start : starts(event, from, to)) {
                long at = start.toInstant().toEpochMilli();
                long end = start.plus(length).toInstant().toEpochMilli();
                if (skip.contains(at) || end <= from || at >= to) {
                    continue;
                }
                found.add(new Occurrence(at, Math.max(end, at), event.title, event.allDay));
            }
        }
        return found;
    }

    /** The starts of one event: itself, or its repeats up to {@code to}. */
    static List<ZonedDateTime> starts(Event event, long from, long to) {
        if (event.rrule.isEmpty() || event.recurrenceId != null) {
            return Collections.singletonList(event.start);
        }
        Map<String, String> rule = new LinkedHashMap<>();
        for (String part : event.rrule.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                rule.put(part.substring(0, equals).toUpperCase(java.util.Locale.ROOT),
                        part.substring(equals + 1));
            }
        }
        String freq = rule.getOrDefault("FREQ", "");
        int interval = Math.max(1, parseInt(rule.get("INTERVAL"), 1));
        int count = parseInt(rule.get("COUNT"), -1);
        long until = Long.MAX_VALUE;
        if (rule.containsKey("UNTIL")) {
            String value = rule.get("UNTIL");
            until = time(value, null, value.matches("\\d{8}")).toInstant().toEpochMilli();
        }
        List<DayOfWeek> byDay = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        for (String day : rule.getOrDefault("BYDAY", "").split(",")) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("([+-]?\\d{0,2})(MO|TU|WE|TH|FR|SA|SU)")
                            .matcher(day);
            if (matcher.matches()) {
                byDay.add(weekday(matcher.group(2)));
                ordinals.add(matcher.group(1).isEmpty() ? 0 : Integer.parseInt(
                        matcher.group(1).replace("+", "")));
            }
        }
        if (!readable(freq, rule, byDay, ordinals, event.start)) {
            // A rule this reader does not know: the event once, at its start.
            return Collections.singletonList(event.start);
        }
        int monthDay = parseInt(rule.get("BYMONTHDAY"), 0);
        List<ZonedDateTime> starts = new ArrayList<>();
        ZonedDateTime period = event.start;
        if (count < 0 && ("DAILY".equals(freq) || "WEEKLY".equals(freq))) {
            // Straight to a year before the window, whole intervals at a time, so an event
            // repeating since long ago still reaches today within MAX_OCCURRENCES. With a COUNT
            // every occurrence from the start has to be counted.
            ChronoUnit unit = "DAILY".equals(freq) ? ChronoUnit.DAYS : ChronoUnit.WEEKS;
            ZonedDateTime target = Instant.ofEpochMilli(from).atZone(event.start.getZone())
                    .minusDays(400);
            long whole = unit.between(event.start, target) / interval;
            if (whole > 0) {
                period = event.start.plus(whole * interval, unit);
            }
        }
        int produced = 0;
        for (int step = 0; step < MAX_OCCURRENCES; step++) {
            List<ZonedDateTime> inPeriod = new ArrayList<>();
            switch (freq) {
                case "DAILY":
                    if (byDay.isEmpty() || byDay.contains(period.getDayOfWeek())) {
                        inPeriod.add(period);
                    }
                    break;
                case "WEEKLY": {
                    if (byDay.isEmpty()) {
                        inPeriod.add(period);
                    } else {
                        ZonedDateTime monday = period.with(TemporalAdjusters.previousOrSame(
                                DayOfWeek.MONDAY));
                        for (DayOfWeek day : byDay) {
                            inPeriod.add(monday.with(TemporalAdjusters.nextOrSame(day)));
                        }
                        Collections.sort(inPeriod);
                    }
                    break;
                }
                case "MONTHLY": {
                    if (!byDay.isEmpty()) {
                        int ordinal = ordinals.get(0);
                        ZonedDateTime day = ordinal > 0
                                ? period.with(TemporalAdjusters.dayOfWeekInMonth(ordinal,
                                        byDay.get(0)))
                                : period.with(TemporalAdjusters.lastInMonth(byDay.get(0)));
                        // A fifth Monday that this month does not have is skipped, not moved.
                        if (day.getMonth() == period.getMonth()) {
                            inPeriod.add(day);
                        }
                    } else {
                        int day = monthDay > 0 ? monthDay : event.start.getDayOfMonth();
                        if (day <= period.toLocalDate().lengthOfMonth()) {
                            inPeriod.add(period.withDayOfMonth(day));
                        }
                    }
                    break;
                }
                default:
                    inPeriod.add(period);
                    break;
            }
            for (ZonedDateTime start : inPeriod) {
                long at = start.toInstant().toEpochMilli();
                if (start.isBefore(event.start)) {
                    continue;
                }
                if (at > until || at >= to || (count >= 0 && produced >= count)) {
                    return starts;
                }
                produced++;
                starts.add(start);
            }
            period = "DAILY".equals(freq) ? period.plusDays(interval)
                    : "WEEKLY".equals(freq) ? period.plusWeeks(interval)
                    : "MONTHLY".equals(freq) ? period.withDayOfMonth(1).plusMonths(interval)
                    : period.plusYears(interval);
        }
        return starts;
    }

    /** Parts this reader does not change a repeat by; anything else makes it a single event. */
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("FREQ", "INTERVAL",
            "COUNT", "UNTIL", "WKST", "BYDAY", "BYMONTHDAY"));

    /**
     * Whether {@link #starts} expands this rule on the right days: DAILY and WEEKLY with plain
     * weekdays, MONTHLY with one day of the month (1 to 31) or one weekday with an ordinal (1 to
     * 5, or -1 for the last), YEARLY on its start date (Outlook spells that date out as BYMONTH
     * and BYMONTHDAY). BYSETPOS and the like are not read.
     */
    static boolean readable(String freq, Map<String, String> rule, List<DayOfWeek> byDay,
            List<Integer> ordinals, ZonedDateTime start) {
        for (String part : rule.keySet()) {
            if (!IGNORED.contains(part) && !("YEARLY".equals(freq) && "BYMONTH".equals(part))) {
                return false;
            }
        }
        int days = rule.containsKey("BYDAY") ? rule.get("BYDAY").split(",").length : 0;
        if (byDay.size() != days) {
            return false;
        }
        String monthDay = rule.get("BYMONTHDAY");
        boolean oneMonthDay = monthDay != null && monthDay.matches("[1-9]|[12]\\d|3[01]");
        switch (freq) {
            case "DAILY":
            case "WEEKLY":
                return monthDay == null && Collections.frequency(ordinals, 0) == ordinals.size();
            case "MONTHLY":
                if (monthDay != null) {
                    return oneMonthDay && days == 0;
                }
                return days == 0 || (days == 1 && ordinals.get(0) != 0 && ordinals.get(0) >= -1
                        && ordinals.get(0) <= 5);
            case "YEARLY":
                return days == 0
                        && (monthDay == null || parseInt(monthDay, 0) == start.getDayOfMonth())
                        && (!rule.containsKey("BYMONTH")
                        || parseInt(rule.get("BYMONTH"), 0) == start.getMonthValue());
            default:
                return false;
        }
    }

    private static DayOfWeek weekday(String code) {
        switch (code) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            default: return DayOfWeek.SUNDAY;
        }
    }

    /** A DATE or DATE-TIME: UTC with a Z, in its TZID, or floating in the panel's own zone. */
    static ZonedDateTime time(String value, String tzid, boolean date) {
        String text = value.trim();
        if (date || text.matches("\\d{8}")) {
            return LocalDate.parse(text.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(ZoneId.systemDefault());
        }
        boolean utc = text.endsWith("Z");
        LocalDateTime local = LocalDateTime.parse(utc ? text.substring(0, text.length() - 1)
                : text, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        ZoneId zone = utc ? ZoneOffset.UTC : zoneOf(tzid);
        // Kept in its own zone, so a repeat keeps the event's clock across summer time.
        return local.atZone(zone);
    }

    private static ZoneId zoneOf(String tzid) {
        if (tzid == null || tzid.isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tzid.replace("\"", ""));
        } catch (RuntimeException unknown) {
            // Outlook writes Windows names ("W. Europe Standard Time"); the panel's own zone is
            // the nearest honest answer for a panel on the same wall as the calendar.
            return ZoneId.systemDefault();
        }
    }

    private static String parameter(String head, String name) {
        for (String part : head.split(";")) {
            if (part.toUpperCase(java.util.Locale.ROOT).startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        return null;
    }

    /** Lines with RFC 5545's folding undone (a line starting with a space continues the last). */
    static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
            } else if (!raw.isEmpty()) {
                lines.add(raw);
            }
        }
        return lines;
    }

    private static String unescape(String text) {
        String clean = text.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",")
                .replace("\\;", ";").replace("\\\\", "\\");
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }

    private static int parseInt(String text, int fallback) {
        return text != null && text.matches("-?\\d{1,6}") ? Integer.parseInt(text) : fallback;
    }
}
