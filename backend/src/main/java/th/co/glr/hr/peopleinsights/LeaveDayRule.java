package th.co.glr.hr.peopleinsights;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import th.co.glr.hr.attendance.schedule.WorkSchedule;

/**
 * Whether a day touched by APPROVED leave is still late or an early leave. The stored minutes are measured from the
 * schedule alone, so leave can only take minutes away: lateness counts only the minutes between work start and arrival
 * that no leave covers, and likewise for leaving early. Times are minutes after midnight in the schedule's zone.
 */
final class LeaveDayRule {
    private static final int BREAK_START = 12 * 60 + 30;
    private static final int BREAK_END = 13 * 60 + 30;
    private static final int DAY = 24 * 60;

    private LeaveDayRule() {}

    /** A stretch of one day covered by approved leave, [start, end] in minutes after midnight. */
    record Span(int start, int end) {
        static final Span WHOLE_DAY = new Span(0, DAY);

        static Span of(LocalTime from, LocalTime to) {
            return new Span(minute(from), minute(to));
        }
    }

    record Outcome(int lateMinutes, boolean early) {}

    /** The part of {@code day} an approved leave covers. A timed leave may span days: start_time belongs to its first day, end_time to its last. */
    static Span spanOn(LocalDate day, LocalDate leaveStart, LocalDate leaveEnd, LocalTime from, LocalTime to) {
        if (from == null || to == null) return Span.WHOLE_DAY;
        if (leaveStart.equals(leaveEnd)) return Span.of(from, to);
        if (day.equals(leaveStart)) return new Span(minute(from), DAY);
        if (day.equals(leaveEnd)) return new Span(0, minute(to));
        return Span.WHOLE_DAY;
    }

    /**
     * @param spans    the day's leave spans; never empty
     * @param checkIn  local check-in time, null when there is none (a lone punch after the midpoint is stored as the check-out)
     * @param checkOut local check-out time, or null
     */
    static Outcome apply(WorkSchedule schedule, List<Span> spans, LocalTime checkIn, LocalTime checkOut, int storedLate, int storedEarly) {
        if (spans.stream().anyMatch(s -> s.start() <= 0 && s.end() >= DAY)) return new Outcome(0, false);
        List<Span> covered = union(spans);
        int start = minute(schedule.workStart()), end = minute(schedule.workEnd());

        Integer arrival = checkIn == null ? null : minute(checkIn);
        boolean lonePunchArrival = false;
        if (checkIn == null && checkOut != null && storedEarly > 0) {
            int punch = minute(checkOut);
            Span block = covered.stream().filter(s -> s.start() <= start && s.end() > start).findFirst().orElse(null);
            if (block != null && punch <= block.end() + (end - block.end()) / 2) {
                arrival = punch;
                lonePunchArrival = true;
            } else if (block != null) {
                // A later lone punch stays a departure: not late, early by the minutes of [punch, end] no leave covers.
                return new Outcome(storedLate, Math.min(uncovered(covered, punch, end), storedEarly) > 0);
            }
        }

        int late = storedLate;
        if (arrival != null && overlap(covered, start, arrival) > 0) {
            int residual = uncovered(covered, start, arrival);
            late = residual > schedule.graceMinutes() ? residual : 0;
            if (checkIn != null) late = Math.min(late, storedLate);
        }

        boolean early = storedEarly > 0;
        if (lonePunchArrival) {
            early = false;
        } else if (checkIn != null && checkOut != null && !checkIn.equals(checkOut)) {
            int out = minute(checkOut);
            if (overlap(covered, out, end) > 0) early = Math.min(uncovered(covered, out, end), storedEarly) > 0;
        }
        return new Outcome(late, early);
    }

    /** The union of the spans, plus the company break when some span overlaps or touches it. */
    private static List<Span> union(List<Span> spans) {
        List<Span> all = new ArrayList<>(spans);
        if (spans.stream().anyMatch(s -> s.start() <= BREAK_END && s.end() >= BREAK_START)) all.add(new Span(BREAK_START, BREAK_END));
        all.sort(Comparator.comparingInt(Span::start));
        List<Span> out = new ArrayList<>();
        for (Span s : all) {
            Span last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && s.start() <= last.end()) out.set(out.size() - 1, new Span(last.start(), Math.max(last.end(), s.end())));
            else out.add(s);
        }
        return out;
    }

    private static int overlap(List<Span> covered, int from, int to) {
        return covered.stream().mapToInt(s -> Math.max(0, Math.min(s.end(), to) - Math.max(s.start(), from))).sum();
    }

    private static int uncovered(List<Span> covered, int from, int to) {
        return Math.max(0, to - from) - overlap(covered, from, to);
    }

    private static int minute(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
