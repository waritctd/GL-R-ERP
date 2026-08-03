package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;

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
    boolean emergencyFiling,
    // Phase A0a (structured rejection outcome, V131): the machine-readable LeaveRuleCode name (e.g.
    // "SICK_CERTIFICATE_WINDOW") behind an AUTO_REJECTED request's systemNote, and the params that
    // rendered it -- see LeaveRuleOutcome/LeaveRuleMessages. Both null-safe: systemNoteCode is
    // {@code null} for an APPROVED request or any historical pre-V131 row (V131 backfills nothing --
    // see that migration's comment); systemNoteParams is never {@code null}, only an empty map, in
    // either of those same cases. systemNote itself remains the source of truth for DISPLAY (a
    // human-readable Thai sentence); these two fields are the stable contract a caller should branch
    // logic on instead of parsing that sentence.
    String systemNoteCode,
    Map<String, String> systemNoteParams,
    // Phase A0b (GET /api/leave/review-summary phase): computed SERVER-SIDE, per requesting actor,
    // by LeaveService#withCanReviewFlag -- true when this actor could act on this employee's
    // requests (HR/CEO, or this employee's own direct manager), the SAME decision
    // LeaveService#approve/#reject already gate on. NOT a role check (a department manager reviews
    // their own reports too, despite REVIEW_ALL_ROLES being {hr} only) and NOT "is this SPECIFIC
    // request actionable right now" -- callers should still check status == SUBMITTED themselves.
    // LeaveRepository#mapRequest always writes false here (it has no actor to check against);
    // LeaveService overwrites it for every DTO it returns -- see #withCanReviewFlag.
    boolean canReview
) {
}
