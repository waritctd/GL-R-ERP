package th.co.glr.hr.attendance.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.WorkSchedule;

/**
 * Pure unit tests for the late/early/missing/overtime rules — no Spring, no Mockito, no database.
 *
 * <p>The grace-boundary cases are the ones that matter most: "grace is a threshold, not an
 * allowance" is the rule most likely to be silently re-implemented wrong, and under Thai Labour
 * Protection Act §76 an inflated late figure is exactly the number that must not exist.
 */
class AttendanceDailyCalculatorTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 15);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 18);

    private final AttendanceDailyCalculator calculator = new AttendanceDailyCalculator();

    private static final WorkSchedule SCHEDULE = new WorkSchedule(
        BANGKOK,
        LocalTime.of(8, 30),
        LocalTime.of(17, 30),
        5,
        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    );

    /**
     * V117: SALES_5D — identical hours/workdays/grace to {@link #SCHEDULE} (OFFICE_5D) but
     * {@code requiresCheckOut = false}, per §4's ฝ่ายขาย scan-in-only exemption.
     */
    private static final WorkSchedule SALES_SCHEDULE = new WorkSchedule(
        BANGKOK,
        LocalTime.of(8, 30),
        LocalTime.of(17, 30),
        5,
        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        false
    );

    private static PunchRecord punch(long id, LocalDate date, int hour, int minute) {
        return new PunchRecord(
            id,
            date.atTime(hour, minute).atZone(BANGKOK).toOffsetDateTime(),
            "SHOWROOM"
        );
    }

    private AttendanceDailyRecord calculate(List<PunchRecord> punches) {
        return calculator.calculate(7L, WEDNESDAY, punches, SCHEDULE, 0, false);
    }

    @Test
    void onTimeDayHasNoFlags() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 24),
            punch(2, WEDNESDAY, 17, 35)
        ));

        assertThat(record.lateMinutes()).isZero();
        assertThat(record.earlyLeaveMinutes()).isZero();
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.PRESENT);
        assertThat(record.flags()).isEmpty();
        assertThat(record.totalMinutes()).isEqualTo(551);
        assertThat(record.punchCount()).isEqualTo(2);
        assertThat(record.absent()).isFalse();
    }

    @Test
    void checkInWithinGraceIsNotLate() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 34),
            punch(2, WEDNESDAY, 17, 35)
        ));

        assertThat(record.lateMinutes()).isZero();
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.LATE);
    }

    @Test
    void checkInAtExactlyTheGraceBoundaryIsNotLate() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 35),
            punch(2, WEDNESDAY, 17, 35)
        ));

        assertThat(record.lateMinutes()).isZero();
    }

    /** Grace is a THRESHOLD, not an allowance: 08:40 is 10 late (from 08:30), never 4. */
    @Test
    void pastGraceCountsFromWorkStartNotFromTheGraceBoundary() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 40),
            punch(2, WEDNESDAY, 17, 35)
        ));

        assertThat(record.lateMinutes()).isEqualTo(10);
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.LATE);
        assertThat(record.flags()).contains(AttendanceDayFlag.LATE);
    }

    @Test
    void earlyCheckOutIsMeasuredFromWorkEnd() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 28),
            punch(2, WEDNESDAY, 16, 20)
        ));

        assertThat(record.earlyLeaveMinutes()).isEqualTo(70);
        assertThat(record.flags()).contains(AttendanceDayFlag.EARLY_LEAVE);
        assertThat(record.lateMinutes()).isZero();
    }

    @Test
    void loneMorningPunchIsACheckInWithNoCheckOut() {
        AttendanceDailyRecord record = calculate(List.of(punch(1, WEDNESDAY, 8, 47)));

        assertThat(record.checkIn()).isNotNull();
        assertThat(record.checkOut()).isNull();
        assertThat(record.checkOutPunchId()).isNull();
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.MISSING_CHECK_OUT);
        assertThat(record.flags()).contains(AttendanceDayFlag.MISSING_CHECK_OUT);
        assertThat(record.lateMinutes()).isEqualTo(17);
        // No check-out means no early-leave figure may be invented.
        assertThat(record.earlyLeaveMinutes()).isZero();
        assertThat(record.totalMinutes()).isNull();
    }

    /**
     * §4's ฝ่ายขาย scan-in-only exemption (V117): on SALES_5D, a lone morning punch is a complete,
     * compliant day — no MISSING_CHECK_OUT, no fabricated early-leave, no fabricated total, but
     * lateness is still evaluated normally from the check-in.
     */
    @Test
    void salesScheduleLoneMorningPunchIsNotMissingCheckOut() {
        AttendanceDailyRecord record = calculator.calculate(
            7L, WEDNESDAY, List.of(punch(1, WEDNESDAY, 8, 47)), SALES_SCHEDULE, 0, false);

        assertThat(record.checkIn()).isNotNull();
        assertThat(record.checkOut()).isNull();
        assertThat(record.status()).isNotEqualTo(AttendanceDayStatus.MISSING_CHECK_OUT);
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.MISSING_CHECK_OUT);
        assertThat(record.lateMinutes()).isEqualTo(17);
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.LATE);
        // No check-out means no early-leave figure may be invented, exempt schedule or not.
        assertThat(record.earlyLeaveMinutes()).isZero();
        assertThat(record.totalMinutes()).isNull();
    }

    /**
     * A sales employee who DOES scan out is unaffected by the exemption: the ordinary two-punch
     * path applies unchanged, including early-leave.
     */
    @Test
    void salesScheduleWithBothPunchesBehavesLikeAnyOtherSchedule() {
        AttendanceDailyRecord record = calculator.calculate(
            7L, WEDNESDAY,
            List.of(punch(1, WEDNESDAY, 8, 28), punch(2, WEDNESDAY, 16, 20)),
            SALES_SCHEDULE, 0, false);

        assertThat(record.checkIn()).isNotNull();
        assertThat(record.checkOut()).isNotNull();
        assertThat(record.earlyLeaveMinutes()).isEqualTo(70);
        assertThat(record.flags()).contains(AttendanceDayFlag.EARLY_LEAVE);
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.MISSING_CHECK_OUT);
        assertThat(record.lateMinutes()).isZero();
        assertThat(record.totalMinutes()).isNotNull();
    }

    /**
     * Assert BOTH sides on ONE fixture (CLAUDE.md's testing rule): the identical lone-morning-punch
     * day is MISSING_CHECK_OUT under OFFICE_5D and NOT missing under SALES_5D. A one-sided assertion
     * ("SALES_5D is not missing") would also pass if the calculator secretly ignored
     * {@code requiresCheckOut} entirely and simply never flagged anyone — checking the exact same
     * punch under OFFICE_5D still flags rules that out.
     */
    @Test
    void sameLoneMorningPunchIsMissingCheckOutUnderOfficeScheduleButNotUnderSalesSchedule() {
        List<PunchRecord> punches = List.of(punch(1, WEDNESDAY, 8, 47));

        AttendanceDailyRecord officeRecord =
            calculator.calculate(7L, WEDNESDAY, punches, SCHEDULE, 0, false);
        AttendanceDailyRecord salesRecord =
            calculator.calculate(7L, WEDNESDAY, punches, SALES_SCHEDULE, 0, false);

        assertThat(officeRecord.status()).isEqualTo(AttendanceDayStatus.MISSING_CHECK_OUT);
        assertThat(officeRecord.flags()).contains(AttendanceDayFlag.MISSING_CHECK_OUT);

        assertThat(salesRecord.status()).isNotEqualTo(AttendanceDayStatus.MISSING_CHECK_OUT);
        assertThat(salesRecord.flags()).doesNotContain(AttendanceDayFlag.MISSING_CHECK_OUT);
    }

    /**
     * The exemption is check-out only: a sales employee's lone AFTERNOON punch is still
     * MISSING_CHECK_IN even on SALES_5D — §4 never relaxes the arrival scan.
     */
    @Test
    void salesScheduleLoneAfternoonPunchIsStillMissingCheckIn() {
        AttendanceDailyRecord record = calculator.calculate(
            7L, WEDNESDAY, List.of(punch(1, WEDNESDAY, 17, 25)), SALES_SCHEDULE, 0, false);

        assertThat(record.checkIn()).isNull();
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.MISSING_CHECK_IN);
        assertThat(record.flags()).contains(AttendanceDayFlag.MISSING_CHECK_IN);
    }

    /**
     * The case that motivates the midpoint rule: treating this as an arrival would report someone
     * turning up at 17:25 and fabricate ~535 late minutes.
     */
    @Test
    void loneAfternoonPunchIsACheckOutWithNoCheckIn() {
        AttendanceDailyRecord record = calculate(List.of(punch(1, WEDNESDAY, 17, 25)));

        assertThat(record.checkIn()).isNull();
        assertThat(record.checkInPunchId()).isNull();
        assertThat(record.checkOut()).isNotNull();
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.MISSING_CHECK_IN);
        assertThat(record.flags()).contains(AttendanceDayFlag.MISSING_CHECK_IN);
        assertThat(record.lateMinutes()).isZero();
        assertThat(record.earlyLeaveMinutes()).isEqualTo(5);
    }

    @Test
    void lonePunchExactlyAtMidpointCountsAsACheckIn() {
        AttendanceDailyRecord record = calculate(List.of(punch(1, WEDNESDAY, 13, 0)));

        assertThat(record.checkIn()).isNotNull();
        assertThat(record.checkOut()).isNull();
        assertThat(record.flags()).contains(AttendanceDayFlag.MISSING_CHECK_OUT);
    }

    @Test
    void midDayPunchesCountButOnlyFirstAndLastBecomeCheckInAndOut() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(3, WEDNESDAY, 13, 2),
            punch(1, WEDNESDAY, 8, 20),
            punch(5, WEDNESDAY, 17, 40),
            punch(2, WEDNESDAY, 12, 3),
            punch(4, WEDNESDAY, 15, 30)
        ));

        assertThat(record.punchCount()).isEqualTo(5);
        assertThat(record.checkInPunchId()).isEqualTo(1L);
        assertThat(record.checkOutPunchId()).isEqualTo(5L);
    }

    @Test
    void unsortedInputIsOrderedInternally() {
        AttendanceDailyRecord late = calculate(List.of(
            punch(2, WEDNESDAY, 17, 35),
            punch(1, WEDNESDAY, 8, 20)
        ));

        assertThat(late.checkInPunchId()).isEqualTo(1L);
        assertThat(late.checkOutPunchId()).isEqualTo(2L);
    }

    @Test
    void earlyMorningPunchBelongsToItsOwnWorkDate() {
        AttendanceDailyRecord record = calculate(List.of(punch(1, WEDNESDAY, 1, 0)));

        // 01:00 is before the 13:00 midpoint, so it reads as an arrival — and a very late one.
        assertThat(record.checkIn()).isNotNull();
        assertThat(record.workDate()).isEqualTo(WEDNESDAY);
        assertThat(record.lateMinutes()).isZero();
    }

    @Test
    void nonWorkdayNeverAccruesLateOrEarlyMinutes() {
        AttendanceDailyRecord record = calculator.calculate(
            7L,
            SATURDAY,
            List.of(punch(1, SATURDAY, 10, 15), punch(2, SATURDAY, 14, 0)),
            SCHEDULE,
            0,
            false
        );

        assertThat(record.lateMinutes()).isZero();
        assertThat(record.earlyLeaveMinutes()).isZero();
        assertThat(record.status()).isEqualTo(AttendanceDayStatus.NON_WORKDAY);
        assertThat(record.flags()).contains(AttendanceDayFlag.NON_WORKDAY);
        assertThat(record.punchCount()).isEqualTo(2);
    }

    /** Holiday beats workday: a weekday that is also a company holiday evaluates no late/early. */
    @Test
    void holidayBeatsWorkday() {
        AttendanceDailyRecord record = calculator.calculate(
            7L,
            WEDNESDAY,
            List.of(punch(1, WEDNESDAY, 8, 40), punch(2, WEDNESDAY, 17, 35)),
            SCHEDULE,
            0,
            true
        );

        assertThat(record.status()).isEqualTo(AttendanceDayStatus.HOLIDAY);
        assertThat(record.flags()).contains(AttendanceDayFlag.HOLIDAY);
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.NON_WORKDAY, AttendanceDayFlag.LATE);
        assertThat(record.lateMinutes()).isZero();
        assertThat(record.earlyLeaveMinutes()).isZero();
    }

    /** Distinct from NON_WORKDAY: a holiday that falls on what would already be a non-workday is still HOLIDAY. */
    @Test
    void holidayOnANonWorkdayIsStillHolidayNotNonWorkday() {
        AttendanceDailyRecord record = calculator.calculate(
            7L,
            SATURDAY,
            List.of(punch(1, SATURDAY, 9, 0), punch(2, SATURDAY, 17, 0)),
            SCHEDULE,
            0,
            true
        );

        assertThat(record.status()).isEqualTo(AttendanceDayStatus.HOLIDAY);
        assertThat(record.flags()).contains(AttendanceDayFlag.HOLIDAY);
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.NON_WORKDAY);
    }

    @Test
    void approvedOvertimeIsFlaggedAndStored() {
        AttendanceDailyRecord record = calculator.calculate(
            7L,
            WEDNESDAY,
            List.of(punch(1, WEDNESDAY, 8, 20), punch(2, WEDNESDAY, 19, 0)),
            SCHEDULE,
            90,
            false
        );

        assertThat(record.overtimeMinutes()).isEqualTo(90);
        assertThat(record.flags()).contains(AttendanceDayFlag.OVERTIME_APPROVED);
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.WORKED_LATE_UNAPPROVED);
    }

    /** Staying late without approval is not overtime — payroll would not pay it. */
    @Test
    void stayingLateWithoutApprovalIsNotOvertime() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 20),
            punch(2, WEDNESDAY, 19, 0)
        ));

        assertThat(record.overtimeMinutes()).isZero();
        assertThat(record.flags()).contains(AttendanceDayFlag.WORKED_LATE_UNAPPROVED);
        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.OVERTIME_APPROVED);
    }

    @Test
    void leavingJustAfterWorkEndIsNotFlaggedAsWorkingLate() {
        AttendanceDailyRecord record = calculate(List.of(
            punch(1, WEDNESDAY, 8, 20),
            punch(2, WEDNESDAY, 17, 34)
        ));

        assertThat(record.flags()).doesNotContain(AttendanceDayFlag.WORKED_LATE_UNAPPROVED);
    }

    @Test
    void siteComesFromTheCheckInPunch() {
        AttendanceDailyRecord record = calculate(List.of(
            new PunchRecord(1, WEDNESDAY.atTime(8, 20).atZone(BANGKOK).toOffsetDateTime(), "WAREHOUSE"),
            new PunchRecord(2, WEDNESDAY.atTime(17, 35).atZone(BANGKOK).toOffsetDateTime(), "SHOWROOM")
        ));

        assertThat(record.siteCode()).isEqualTo("WAREHOUSE");
    }

    @Test
    void siteFallsBackToTheCheckOutPunchWhenThereIsNoCheckIn() {
        AttendanceDailyRecord record = calculate(List.of(
            new PunchRecord(1, WEDNESDAY.atTime(17, 25).atZone(BANGKOK).toOffsetDateTime(), "WAREHOUSE")
        ));

        assertThat(record.siteCode()).isEqualTo("WAREHOUSE");
    }

    @Test
    void punchlessDaysAreRejectedRatherThanStored() {
        assertThatThrownBy(() -> calculate(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never stored");
    }

    @Test
    void overtimeMinutesNeverGoNegative() {
        AttendanceDailyRecord record = calculator.calculate(
            7L, WEDNESDAY, List.of(punch(1, WEDNESDAY, 8, 20), punch(2, WEDNESDAY, 17, 35)),
            SCHEDULE, -30, false
        );

        assertThat(record.overtimeMinutes()).isZero();
    }
}
