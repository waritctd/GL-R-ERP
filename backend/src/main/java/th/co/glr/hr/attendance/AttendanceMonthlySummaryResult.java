package th.co.glr.hr.attendance;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.EmployeeDay;

/** Everything AttendanceMonthlySummaryExporter needs to render both sheets, assembled by
 * AttendanceMonthlySummaryService#buildSummary -- kept as one plain data record so the assembly step stays
 * unit-testable without a workbook and the export step stays free of any aggregation logic to get wrong. */
record AttendanceMonthlySummaryResult(
    YearMonth month,
    String appliedFilterDescription,
    OffsetDateTime generatedAt,
    List<AttendanceMonthlySummaryRow> summaryRows,
    List<AttendanceDailyDto> dailyRows,
    Map<EmployeeDay, List<LeaveContribution>> leaveByDay
) {
}
