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

    @Test void a_noLeaveSpansKeepsStored() { assertDay(run(List.of(), "09:30", null, 60, 0), 60, false); }
    @Test void b_wholeDayLeaveIsNeitherLateNorEarly() { assertDay(run(List.of(Span.WHOLE_DAY), "10:00", null, 90, 0), 0, false); }
    @Test void c_morningLeaveThenArrivalRightAfterBreakIsNotLate() { assertDay(run(List.of(span("08:30", "12:30")), "13:25", "17:30", 295, 0), 0, false); }
    @Test void d_morningLeaveThenArrivalAnHourAfterBreakIsLateByThatHour() { assertDay(run(List.of(span("08:30", "12:30")), "14:30", "17:30", 360, 0), 60, false); }
    @Test void e_leaveAfterArrivalKeepsStoredLate() { assertDay(run(List.of(span("16:00", "17:00")), "10:00", "17:30", 90, 0), 90, false); }
    @Test void f_leaveInsideTheLateWindowOnlyExcusesItsOwnMinutes() { assertDay(run(List.of(span("09:00", "11:00")), "11:05", null, 155, 0), 35, false); }
    @Test void g_residualWithinGraceIsNotLate() { assertDay(run(List.of(span("08:30", "10:00")), "10:10", null, 100, 0), 0, false); }
    @Test void h_afternoonLeaveExcusesLeavingAtLunch() { assertDay(run(List.of(span("13:30", "17:30")), "08:30", "12:30", 0, 300), 0, false); }
    @Test void i_leavingBeforeTheLeaveBeginsIsStillEarly() { assertDay(run(List.of(span("13:30", "17:30")), "08:30", "11:30", 0, 360), 0, true); }
    @Test void k_middleDayOfATimedMultiDayLeaveIsWholeDay() {
        // Mon 13:30 -> Wed 12:30: Tue is strictly between (whole day), Wed is [00:00, 12:30].
        assertDay(run(List.of(Span.WHOLE_DAY), "10:00", null, 90, 0), 0, false);
        assertDay(run(List.of(span("00:00", "12:30")), "13:25", "17:30", 295, 0), 0, false);
    }
    @Test void l_lonePunchRightAfterMorningLeaveIsTheArrival() { assertDay(run(List.of(span("08:30", "12:30")), null, "13:20", 0, 250), 0, false); }
    @Test void m_lonePunchLaterInTheAfternoonIsLateArrivalNotEarlyLeave() { assertDay(run(List.of(span("08:30", "12:30")), null, "14:30", 0, 180), 60, false); }
    @Test void m2_lonePunchPastTheMidpointStaysADepartureAndIsEarlyByItsUncoveredMinutes() { assertDay(run(List.of(span("08:30", "12:30")), null, "17:20", 0, 10), 0, true); }
    @Test void q_staleStoredValueIsOnlyEverReducedNeverRaised() { assertDay(run(List.of(span("09:00", "10:00")), "10:00", null, 60, 0), 30, false); }
    @Test void r_lateCapStoredValueBelowResidualWins() { assertDay(run(List.of(span("09:00", "10:00")), "10:00", null, 20, 0), 20, false); }
    @Test void s_earlyCapStoredZeroKeepsNotEarlyAndLateStays() { assertDay(run(List.of(span("16:00", "17:00")), "10:00", "15:00", 90, 0), 90, false); }
    @Test void t_lonePunchBeforeLeaveThatDoesNotStartAtWorkStartKeepsStoredEarly() { assertDay(run(List.of(span("15:00", "17:30")), null, "14:00", 0, 210), 0, true); }

    @Test void n_overlappingLeavesAreUnitedNotSummed() { assertDay(run(List.of(span("08:30", "10:00"), span("09:00", "10:00")), "10:40", null, 130, 0), 40, false); }
    @Test void o_secondsAreTruncatedAndLeaveAfterArrivalKeepsStored() { assertDay(run(List.of(span("16:00", "17:00")), "08:45:30", null, 15, 0), 15, false); }
    @Test void p_breakIsNotWorkTimeOnALeaveDayThatTouchesIt() { assertDay(run(List.of(span("13:30", "17:30")), "13:00", null, 270, 0), 240, false); }

    @Test
    void spanOnReadsATimedMultiDayLeaveAsStartDayTailMiddleWholeDaysAndEndDayHead() {
        java.time.LocalDate mon = java.time.LocalDate.of(2026, 3, 9), wed = mon.plusDays(2);
        assertThat(LeaveDayRule.spanOn(mon, mon, wed, t("13:30"), t("12:30"))).isEqualTo(new Span(13 * 60 + 30, 24 * 60));
        assertThat(LeaveDayRule.spanOn(mon.plusDays(1), mon, wed, t("13:30"), t("12:30"))).isEqualTo(Span.WHOLE_DAY);
        assertThat(LeaveDayRule.spanOn(wed, mon, wed, t("13:30"), t("12:30"))).isEqualTo(new Span(0, 12 * 60 + 30));
        assertThat(LeaveDayRule.spanOn(mon, mon, mon, t("08:30"), t("12:30"))).isEqualTo(span("08:30", "12:30"));
        assertThat(LeaveDayRule.spanOn(mon, mon, wed, null, null)).isEqualTo(Span.WHOLE_DAY);
    }
}
