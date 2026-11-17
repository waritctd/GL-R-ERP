package th.co.glr.hr.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record SubmitLeaveRequest(
    Long employeeId,
    @NotBlank @Size(max = 30) String leaveTypeCode,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @NotBlank @Size(max = 2000) String reason,
    // Partial-day span leave (2026-07-25, widened to multi-day V166 2026-09-10): both null means whole-day leave
    // (any number of days). Non-null no longer implies startDate == endDate -- a timed request may now span
    // multiple calendar days (e.g. an afternoon-only first day through a full second day).
    LocalTime startTime,
    LocalTime endTime,
    // Paper-form (ใบลาหยุด F-HR-020) "contact during leave" block. Autofilled from the employee's current
    // address/phone when blank -- see LeaveService#resolveContact. All optional.
    @Size(max = 60) String contactHouseNo,
    @Size(max = 120) String contactSubdistrict,
    @Size(max = 120) String contactDistrict,
    @Size(max = 120) String contactProvince,
    @Size(max = 40) String contactPhone,
    // §5.2 leave purpose (V125): optional, open-ended -- see LeaveService#normalizePurposeCode.
    @Size(max = 30) String purposeCode,
    // §5.2 emergency-filing exception (V125): the requester's own declaration that a late request should be
    // considered under the "อนุโลมให้ได้ไม่เกินเดือนละ 3 ครั้ง" tolerance. Only consulted when the type's own
    // advance-notice gate would otherwise reject -- see LeaveService#autoRejectNote.
    Boolean requestedAsEmergency,
    // §5.3.5 pool choice (V161): which quota pool (carried-in vs this year's own) to draw from first, for a leave
    // type that carries forward -- see LeaveQuotaPoolPreference's Javadoc.
    LeaveQuotaPoolPreference quotaPoolPreference
) {
    /** Convenience constructor for the pre-sub-day/contact call sites (whole-day leave only). */
    public SubmitLeaveRequest(Long employeeId, String leaveTypeCode, LocalDate startDate, LocalDate endDate, String reason) {
        this(employeeId, leaveTypeCode, startDate, endDate, reason, null, null, null, null, null, null, null, null, null, null);
    }

    /** Convenience constructor for the pre-quota-pool-preference call sites (V161) -- the FULL pre-existing shape
     * (sub-day times, contact block, purpose, emergency filing), minus the new trailing field. */
    public SubmitLeaveRequest(
            Long employeeId, String leaveTypeCode, LocalDate startDate, LocalDate endDate, String reason,
            LocalTime startTime, LocalTime endTime,
            String contactHouseNo, String contactSubdistrict, String contactDistrict, String contactProvince,
            String contactPhone, String purposeCode, Boolean requestedAsEmergency) {
        this(employeeId, leaveTypeCode, startDate, endDate, reason, startTime, endTime,
            contactHouseNo, contactSubdistrict, contactDistrict, contactProvince, contactPhone,
            purposeCode, requestedAsEmergency, null);
    }
}
