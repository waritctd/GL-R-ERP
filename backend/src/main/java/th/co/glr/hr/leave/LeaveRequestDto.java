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
    // Leave -> payroll unpaid-day deduction (2026-07-23): totalDays split into what statutory quota
    // covered (paidDays) vs. what went unpaid (unpaidDays); paidDays + unpaidDays == totalDays always
    // for an APPROVED request. Both are 0 for AUTO_REJECTED/REJECTED/SUBMITTED requests, which never
    // consumed any days. See LeaveService#submit.
    BigDecimal paidDays,
    BigDecimal unpaidDays,
    int quotaYear,
    String reason,
    Long attachmentId,
    String attachmentFileName,
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
