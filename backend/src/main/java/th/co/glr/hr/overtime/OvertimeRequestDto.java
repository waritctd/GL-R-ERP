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
    /**
     * Whether a manager stage exists for this request at all. False means the CEO reviews it
     * directly from SUBMITTED — see {@code ManagerApproverRepository}. Note this is unrelated to
     * {@code managerEmployeeId}, which is reports-to and is display-only since approval became
     * ฝ่าย-manager-only.
     */
    boolean hasManagerApprover,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    // feat/pending-approver-info: "who this is waiting on" for a SUBMITTED/MANAGER_APPROVED
    // request -- read-only, informational, NOT an authorization decision (see PendingApproverSql's
    // Javadoc). Mirrors OvertimeService#approve's own SUBMITTED/MANAGER_APPROVED branching:
    // "manager" when SUBMITTED and hasManagerApprover is true (division manager stage); "ceo"
    // when SUBMITTED with no manager stage, or when MANAGER_APPROVED. Both null for any other
    // status. pendingApproverName is null whenever the resolved role has more than one active
    // candidate (multiple division-manager peers, or multiple active ceo-role employees) -- see
    // OvertimeRepository#resolvePendingApprover's Javadoc.
    String pendingApproverRole,
    String pendingApproverName,
    /**
     * feat/ot-nonworkday-rate-suggestion: the system's SUGGESTION for {@code dayType} -- {@code
     * "WORKDAY"} or {@code "HOLIDAY"} -- computed by {@code OvertimeService#suggestDayType} at READ
     * time from {@code hr.holiday} OR the employee's resolved {@code WorkSchedule} non-workday,
     * whichever fires. Deliberately NOT persisted: {@code OvertimeService} attaches it fresh to
     * every DTO this class's methods return (see {@code requireRequest}/{@code list}), so a
     * schedule correction or a holiday added after the fact changes the suggestion on the very next
     * read, with no migration or backfill.
     *
     * <p><b>Never a pay input by itself.</b> It is what the submit form pre-fills and what the
     * approve dialog defaults its selector to -- see {@code ApproveOvertimeRequest.dayType} for the
     * one field that actually may become {@code pay_rate_multiplier}. This field, {@link #dayType},
     * and the employee's original claim (never persisted as its own column -- see {@code
     * OvertimeService#resolveDayTypeSubmitNote}) are three distinct values on purpose; do not
     * conflate them.
     */
    String suggestedDayType
) {
}
