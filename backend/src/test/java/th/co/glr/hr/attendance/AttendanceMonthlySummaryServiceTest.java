package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDailyFilter;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.AttendanceDayFlag;
import th.co.glr.hr.attendance.daily.AttendanceDayStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.ApprovedLeaveSpanDto;
import th.co.glr.hr.leave.LeaveRepository;

/** Unit coverage for the AGGREGATION rules stated in AttendanceMonthlySummaryService's own javadoc -- ขาดงาน/ลา
 * attribution and the OT-hours flag gate. */
class AttendanceMonthlySummaryServiceTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 8);
    private static final LocalDate A_WORKDAY = LocalDate.of(2026, 8, 5); // a Wednesday
    private static final LocalDate A_WEEKEND = LocalDate.of(2026, 8, 8); // a Saturday
    private static final long EMPLOYEE_ID = 501L;
    private static final UserPrincipal HR_USER =
        new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", null, true, LocalDate.now(), false, null, false);

    private final AttendanceDailyService dailyService = mock(AttendanceDailyService.class);
    private final LeaveRepository leaveRepository = mock(LeaveRepository.class);
    private final AttendanceService attendanceService = new AttendanceService(
        mock(AttendanceRepository.class), new AttendanceDatParser(), new AppProperties(), dailyService);
    private final AttendanceMonthlySummaryService service = new AttendanceMonthlySummaryService(
        attendanceService, dailyService, leaveRepository, new AttendanceMonthlySummaryExporter());

    private static AttendanceDailyDto day(
            LocalDate date, boolean workday, AttendanceDayStatus status,
            List<AttendanceDayFlag> flags, int overtimeMinutes) {
        return new AttendanceDailyDto(
            EMPLOYEE_ID, "E501", "พนักงาน ทดสอบ", "เทส", "พนักงานทั่วไป",
            date, workday, null, null, null, 0, 0, overtimeMinutes, 0, null, status, flags, false, null);
    }

    private void stubDay(AttendanceDailyDto... days) {
        when(dailyService.list(any(AttendanceDailyFilter.class))).thenReturn(List.of(days));
    }

    private void stubLeave(ApprovedLeaveSpanDto... spans) {
        when(leaveRepository.findApprovedLeaveOverlapping(anyCollection(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(spans));
    }

    private AttendanceMonthlySummaryRow onlyRow() {
        AttendanceMonthlySummaryResult result = service.buildSummary(HR_USER, MONTH, null, null);
        assertThat(result.summaryRows()).hasSize(1);
        return result.summaryRows().get(0);
    }

    @Test
    void countsUnexcusedAbsenceOnAWorkdayWithNoRecordAndNoLeave() {
        stubDay(day(A_WORKDAY, true, AttendanceDayStatus.NO_RECORD, List.of(), 0));
        stubLeave();

        AttendanceMonthlySummaryRow row = onlyRow();

        assertThat(row.absentDays()).isEqualTo(1);
        assertThat(row.totalLeaveDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void approvedWholeDayLeaveOnAWorkdaySuppressesAbsenceAndLandsInItsOwnColumn() {
        stubDay(day(A_WORKDAY, true, AttendanceDayStatus.NO_RECORD, List.of(), 0));
        stubLeave(new ApprovedLeaveSpanDto(
            EMPLOYEE_ID, "SICK", "ลาป่วย", A_WORKDAY, A_WORKDAY, null, null, BigDecimal.ONE));

        AttendanceMonthlySummaryRow row = onlyRow();

        assertThat(row.absentDays()).isEqualTo(0);
        assertThat(row.sickDays()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(row.totalLeaveDays()).isEqualByComparingTo(BigDecimal.ONE);
    }

    /** LeaveRepository#findApprovedLeaveOverlapping's own WHERE clause is status = 'APPROVED' -- a
     * SUBMITTED/REJECTED/CANCELLED request never reaches this service at all, so the fixture representing "a
     * request exists but isn't approved" is the SAME empty leave list a no-leave-at-all day produces. */
    @Test
    void aSubmittedRejectedOrCancelledLeaveDoesNotSuppressAbsence() {
        stubDay(day(A_WORKDAY, true, AttendanceDayStatus.NO_RECORD, List.of(), 0));
        stubLeave();

        AttendanceMonthlySummaryRow row = onlyRow();

        assertThat(row.absentDays()).isEqualTo(1);
    }

    @Test
    void subDayLeaveContributesItsOwnFractionNotAWholeDay() {
        stubDay(day(A_WORKDAY, true, AttendanceDayStatus.PRESENT, List.of(), 0));
        BigDecimal half = new BigDecimal("0.50");
        stubLeave(new ApprovedLeaveSpanDto(
            EMPLOYEE_ID, "PERSONAL", "ลากิจ", A_WORKDAY, A_WORKDAY,
            LocalTime.of(13, 0), LocalTime.of(17, 30), half));

        AttendanceMonthlySummaryRow row = onlyRow();

        assertThat(row.personalDays()).isEqualByComparingTo(half);
        assertThat(row.totalLeaveDays()).isEqualByComparingTo(half);
    }

    @Test
    void wholeDayLeaveOnANonWorkdayContributesNothing() {
        stubDay(day(A_WEEKEND, false, AttendanceDayStatus.NON_WORKDAY, List.of(AttendanceDayFlag.NON_WORKDAY), 0));
        stubLeave(new ApprovedLeaveSpanDto(
            EMPLOYEE_ID, "VACATION", "ลาพักร้อน", A_WEEKEND, A_WEEKEND, null, null, BigDecimal.ONE));

        AttendanceMonthlySummaryResult result = service.buildSummary(HR_USER, MONTH, null, null);

        assertThat(result.leaveByDay()).isEmpty();
        AttendanceMonthlySummaryRow row = result.summaryRows().get(0);
        assertThat(row.totalLeaveDays()).isEqualByComparingTo(BigDecimal.ZERO);
        // Not a workday, so it must not read as ขาดงาน either -- that column is workday-gated too.
        assertThat(row.absentDays()).isEqualTo(0);
    }

    /** AttendanceDailyService#toDto never actually assigns AttendanceDayFlag#WORKED_LATE_UNAPPROVED alongside a
     * non-zero overtimeMinutes -- this fixture builds that "impossible" combination directly to prove the
     * AGGREGATION reads the flag, not the raw minutes, exactly as this class's own javadoc explains. */
    @Test
    void otHoursComeOnlyFromDaysFlaggedOvertimeApproved() {
        stubDay(day(A_WORKDAY, true, AttendanceDayStatus.PRESENT, List.of(AttendanceDayFlag.WORKED_LATE_UNAPPROVED), 90));
        stubLeave();

        AttendanceMonthlySummaryRow row = onlyRow();

        assertThat(row.approvedOtHours()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
