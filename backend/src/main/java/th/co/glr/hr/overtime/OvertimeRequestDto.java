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
    OffsetDateTime updatedAt
) {
}
