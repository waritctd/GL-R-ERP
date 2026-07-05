package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceDailyCalculatorTest {
    // Bangkok is UTC+7; punches below are expressed in UTC to prove the calculator converts to zone.
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");
    private final AttendanceDailyCalculator calculator =
        new AttendanceDailyCalculator(BKK, LocalTime.of(8, 30), LocalTime.of(17, 30), 0);

    private static OffsetDateTime bkk(int hour, int minute) {
        // 2026-06-15 local Bangkok time -> stored as an offset instant
        return OffsetDateTime.of(2026, 6, 15, hour, minute, 0, 0, ZoneOffset.ofHours(7));
    }

    @Test
    void onTimeFullDayHasNoLateOrEarlyLeave() {
        AttendanceDailyCalculator.Result result = calculator.compute(List.of(bkk(8, 30), bkk(17, 30)));

        assertThat(result.absent()).isFalse();
        assertThat(result.lateMinutes()).isZero();
        assertThat(result.earlyLeaveMinutes()).isZero();
        assertThat(result.totalMinutes()).isEqualTo(9 * 60);
        assertThat(result.punchCount()).isEqualTo(2);
    }

    @Test
    void lateArrivalAccruesLateMinutes() {
        AttendanceDailyCalculator.Result result = calculator.compute(List.of(bkk(9, 15), bkk(17, 30)));

        assertThat(result.lateMinutes()).isEqualTo(45);
        assertThat(result.earlyLeaveMinutes()).isZero();
    }

    @Test
    void earlyDepartureAccruesEarlyLeaveMinutes() {
        AttendanceDailyCalculator.Result result = calculator.compute(List.of(bkk(8, 20), bkk(16, 30)));

        assertThat(result.lateMinutes()).isZero();
        assertThat(result.earlyLeaveMinutes()).isEqualTo(60);
    }

    @Test
    void graceWindowSuppressesShortLateness() {
        AttendanceDailyCalculator graced =
            new AttendanceDailyCalculator(BKK, LocalTime.of(8, 30), LocalTime.of(17, 30), 15);

        assertThat(graced.compute(List.of(bkk(8, 44), bkk(17, 30))).lateMinutes()).isZero();
        assertThat(graced.compute(List.of(bkk(8, 50), bkk(17, 30))).lateMinutes()).isEqualTo(5);
    }

    @Test
    void middlePunchesDoNotAffectFirstInLastOut() {
        AttendanceDailyCalculator.Result result =
            calculator.compute(List.of(bkk(12, 0), bkk(8, 25), bkk(13, 0), bkk(17, 40)));

        assertThat(result.lateMinutes()).isZero();
        assertThat(result.earlyLeaveMinutes()).isZero();
        assertThat(result.punchCount()).isEqualTo(4);
    }

    @Test
    void noPunchesIsAbsent() {
        AttendanceDailyCalculator.Result result = calculator.compute(List.of());

        assertThat(result.absent()).isTrue();
        assertThat(result.punchCount()).isZero();
        assertThat(result.checkIn()).isNull();
    }
}
