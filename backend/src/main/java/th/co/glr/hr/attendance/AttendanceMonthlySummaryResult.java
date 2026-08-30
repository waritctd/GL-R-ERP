package th.co.glr.hr.attendance;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.EmployeeDay;

/**
 * Everything {@code AttendanceMonthlySummaryExporter} needs to render both sheets, assembled by
 * {@code AttendanceMonthlySummaryService#buildSummary} -- kept as one plain data record (no POI, no
 * database handle) so the assembly step stays unit-testable without a workbook and the export step
 * stays free of any aggregation logic to get wrong.
 *
 * @param appliedFilterDescription human-readable ฝ่าย/พนักงาน filter actually applied -- derived
 *     from the resolved {@code AttendanceScope}, never from the raw request params, so a manager's
 *     ignored {@code divisionId} or a self-view's forced {@code employeeId} reads the same on the
 *     workbook as it behaves
 * @param summaryRows one row per employee, for {@code สรุปรายเดือน} (Sheet 1)
 * @param dailyRows one row per employee per day, grouped by employee then chronological within each
 *     group (see {@code AttendanceMonthlySummaryService#buildSummary}'s own comment on why this is
 *     NOT {@code AttendanceDailyService#list}'s raw date-DESC-then-employee-code order), for
 *     {@code รายวัน} (Sheet 2)
 * @param leaveByDay the same per-(employee, date) leave ledger {@code buildEmployeeRow} folded into
 *     {@code summaryRows}' ลา columns, reused verbatim by the exporter to render Sheet 2's ลา cell --
 *     one ledger, read twice, so the two sheets can never disagree about which dates are on leave
 */
record AttendanceMonthlySummaryResult(
    YearMonth month,
    String appliedFilterDescription,
    OffsetDateTime generatedAt,
    List<AttendanceMonthlySummaryRow> summaryRows,
    List<AttendanceDailyDto> dailyRows,
    Map<EmployeeDay, List<LeaveContribution>> leaveByDay
) {
}
