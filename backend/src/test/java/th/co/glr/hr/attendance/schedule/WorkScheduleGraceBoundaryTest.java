package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pins the 15-minute grace boundary V121 raised hr.work_schedule.grace_minutes /
 * app.attendance.schedule.grace-minutes to (owner ruling 2026-08-02, was 5 minutes). */
class WorkScheduleGraceBoundaryTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private static final WorkSchedule SCHEDULE = new WorkSchedule(
        BANGKOK,
        LocalTime.of(8, 30),
        LocalTime.of(17, 30),
        15,
        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    );

    @Test
    void aCheckInAtEightFortyFourIsWithinTheFifteenMinuteGraceAndNotLateAtAll() {
        LocalTime checkIn = LocalTime.of(8, 44);

        assertThat(SCHEDULE.isLate(checkIn)).as("08:44 is within the 15-minute grace").isFalse();
        assertThat(SCHEDULE.lateMinutes(checkIn)).isZero();
    }

    @Test
    void aCheckInAtEightFortySixIsLateBySixteenMinutesFromWorkStartNotOneMinutePastGrace() {
        // The threshold-not-allowance rule, pinned at the NEW boundary: once 08:30 + 15 = 08:45 is exceeded, lateness
        // is measured from workStart (08:30), not from the end of the grace window (08:45) -- so 08:46 is late by 16
        // minutes, not 1.
        LocalTime checkIn = LocalTime.of(8, 46);

        assertThat(SCHEDULE.isLate(checkIn)).as("08:46 is past the 15-minute grace").isTrue();
        assertThat(SCHEDULE.lateMinutes(checkIn))
            .as("late minutes measured from workStart (08:30), not from the grace boundary (08:45)")
            .isEqualTo(16);
    }
}
