package th.co.glr.hr.overtime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record OvertimeRequestDto(
    long id,
    long employeeId,
    String employeeCode,
    String employeeName,
    LocalDate workDate,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    int plannedMinutes,
    String dayType,
    BigDecimal payRateMultiplier,
    String reason,
    String status,
    OffsetDateTime actualStartAt,
    OffsetDateTime actualEndAt,
    int actualMinutes,
    int payableMinutes,
    String calculationNote,
    LocalDate payrollMonth,
    Long requestedById,
    String requestedByName,
    OffsetDateTime requestedAt,
    Long managerApprovedBy,
    String managerApprovedByName,
    OffsetDateTime managerApprovedAt,
    Long ceoApprovedBy,
    String ceoApprovedByName,
    OffsetDateTime ceoApprovedAt,
    Long reviewedById,
    String reviewedByName,
    OffsetDateTime reviewedAt,
    String reviewerNote,
    OffsetDateTime cancelledAt,
    Long managerEmployeeId,
    String managerName,
    /** Whether a manager stage exists for this request at all. False means the CEO reviews it directly from
     * SUBMITTED — see ManagerApproverRepository. Note this is unrelated to managerEmployeeId, which is reports-to
     * and is display-only since approval became ฝ่าย-manager-only. */
    boolean hasManagerApprover,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    // feat/pending-approver-info: "who this is waiting on" for a SUBMITTED/MANAGER_APPROVED request -- read-only,
    // informational, NOT an authorization decision (see PendingApproverSql's Javadoc). Both null for any other
    // status.
    String pendingApproverRole,
    String pendingApproverName,
    /** feat/ot-nonworkday-rate-suggestion: the system's SUGGESTION for dayType -- "WORKDAY" or "HOLIDAY" --
     * computed by OvertimeService#suggestDayType at READ time from hr.holiday OR the employee's resolved
     * WorkSchedule non-workday, whichever fires. Never a pay input by itself. */
    String suggestedDayType
) {
}
