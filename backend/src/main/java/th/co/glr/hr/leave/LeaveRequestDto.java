package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
    // Sub-day leave (2026-07-25): both null for legacy/whole-day leave. See LeaveDayMath.
    LocalTime startTime,
    LocalTime endTime,
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
    // V118 cross-year quota fix (2026-08-02): for a request that spans a calendar-year boundary
    // (e.g. a long MATERNITY request), these two figures reflect ONLY quotaYear (the request's START
    // year) -- see hr.leave_request's V118 column comments. The full per-year breakdown is not
    // exposed on this DTO; it lives in hr.leave_request_quota_year (LeaveRepository#findQuotaYearSplits).
    // Unchanged meaning for the common (same-year) case, which is still the vast majority of requests.
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
    OffsetDateTime updatedAt,
    // Paper-form (ใบลาหยุด F-HR-020) "contact during leave" block -- autofilled from the employee's
    // current address/phone when the requester leaves them blank. See LeaveService#resolveContact.
    String contactHouseNo,
    String contactSubdistrict,
    String contactDistrict,
    String contactProvince,
    String contactPhone,
    // §5.2 leave purpose (V125): self-declared, optional -- see LeaveService#normalizePurposeCode.
    String purposeCode,
    // §5.2 emergency-filing exception (V125): TRUE if this request was approved via the monthly
    // emergency tolerance rather than by meeting its type's ordinary advance notice -- see
    // LeaveService#autoRejectNote and hr.leave_request.emergency_filing's migration comment.
    boolean emergencyFiling
) {
}
