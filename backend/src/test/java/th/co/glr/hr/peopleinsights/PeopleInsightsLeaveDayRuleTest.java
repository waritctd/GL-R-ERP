package th.co.glr.hr.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.peopleinsights.LeaveDayRule.Outcome;
import th.co.glr.hr.peopleinsights.LeaveDayRule.Span;

/** The D1 scenario table: schedule 08:30-17:30, grace 15, one test per row (letters match the design). */
class PeopleInsightsLeaveDayRuleTest {
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");
    private static final WorkSchedule S = new WorkSchedule(BKK, t("08:30"), t("17:30"), 15, Set.of(DayOfWeek.MONDAY));

    private static LocalTime t(String hhmm) { return LocalTime.parse(hhmm); }
    private static Span span(String from, String to) { return Span.of(t(from), t(to)); }
    private static Outcome run(List<Span> spans, String in, String out, int late, int early) {
        return LeaveDayRule.apply(S, spans, in == null ? null : t(in), out == null ? null : t(out), late, early);
    }
    private static void assertDay(Outcome o, int lateMinutes, boolean early) {
        assertThat(o.lateMinutes()).as("late minutes").isEqualTo(lateMinutes);
        assertThat(o.early()).as("early leave").isEqualTo(early);
    }

    @Test void d_morningLeaveThenArrivalAnHourAfterBreakIsLateByThatHour() { assertDay(run(List.of(span("08:30", "12:30")), "14:30", "17:30", 360, 0), 60, false); }

}
