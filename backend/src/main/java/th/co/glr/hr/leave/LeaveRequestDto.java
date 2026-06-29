package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LeaveRequestDto(
    long id,
    long employeeId,
    String employeeCode,
    String employeeName,
    String leaveTypeCode,
    String leaveTypeNameTh,
    String leaveTypeNameEn,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal totalDays,
    int quotaYear,
    String reason,
    String attachmentName,
    String attachmentUrl,
    String status,
    BigDecimal quotaRemainingBefore,
    BigDecimal quotaRemainingAfter,
    String systemNote,
    Long requestedById,
    String requestedByName,
    OffsetDateTime requestedAt,
    Long reviewedById,
    String reviewedByName,
    OffsetDateTime reviewedAt,
    String reviewerNote,
    OffsetDateTime cancelledAt,
    Long managerEmployeeId,
    String managerName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
