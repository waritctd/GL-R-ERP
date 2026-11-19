package th.co.glr.hr.attendance.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.WorkSchedule;

/** Pure unit tests for the late/early/missing/overtime rules — no Spring, no Mockito, no database. */
class AttendanceDailyCalculatorTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 15);

    private final AttendanceDailyCalculator calculator = new AttendanceDailyCalculator();

    private static final WorkSchedule SCHEDULE = new WorkSchedule(
        BANGKOK,
        LocalTime.of(8, 30),
        LocalTime.of(17, 30),
        5,
        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    );

    /** V117: SALES_5D — identical hours/workdays/grace to #SCHEDULE (OFFICE_5D) but requiresCheckOut = false, per
     * §4's ฝ่ายขาย scan-in-only exemption. */
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

    /** Assert BOTH sides on ONE fixture (CLAUDE.md's testing rule): the identical lone-morning-punch day is
     * MISSING_CHECK_OUT under OFFICE_5D and NOT missing under SALES_5D. */
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

}
