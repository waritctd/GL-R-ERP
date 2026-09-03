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
    // Sub-day leave (2026-07-25): both null means legacy/whole-day leave. See
    // LeaveService#computeTotalDays / #validateSubDayTimes and V90's chk_leave_time_* checks.
    LocalTime startTime,
    LocalTime endTime,
    // Paper-form (ใบลาหยุด F-HR-020) "contact during leave" block. Autofilled from the employee's
    // current address/phone when blank -- see LeaveService#resolveContact. All optional.
    @Size(max = 60) String contactHouseNo,
    @Size(max = 120) String contactSubdistrict,
    @Size(max = 120) String contactDistrict,
    @Size(max = 120) String contactProvince,
    @Size(max = 40) String contactPhone,
    // §5.2 leave purpose (V125): optional, open-ended -- see LeaveService#normalizePurposeCode.
    @Size(max = 30) String purposeCode,
    // §5.2 emergency-filing exception (V125): the requester's own declaration that a late request
    // should be considered under the "อนุโลมให้ได้ไม่เกินเดือนละ 3 ครั้ง" tolerance. Only consulted
    // when the type's own advance-notice gate would otherwise reject -- see
    // LeaveService#autoRejectNote. null/false are equivalent (treated as "not requested").
    Boolean requestedAsEmergency,
    // §5.3.5 pool choice (V161): which quota pool (carried-in vs this year's own) to draw from
    // first, for a leave type that carries forward -- see LeaveQuotaPoolPreference's Javadoc. null
    // means CARRIED_IN_FIRST (LeaveQuotaPoolPreference#orDefault); ignored entirely for a type where
    // LeaveTypeDto#carriesForward() is FALSE, since there is no second pool to choose between.
    LeaveQuotaPoolPreference quotaPoolPreference
) {
    /** Convenience constructor for the pre-sub-day/contact call sites (whole-day leave only). */
    public SubmitLeaveRequest(Long employeeId, String leaveTypeCode, LocalDate startDate, LocalDate endDate, String reason) {
        this(employeeId, leaveTypeCode, startDate, endDate, reason, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Convenience constructor for the pre-quota-pool-preference call sites (V161) -- the FULL
     * pre-existing shape (sub-day times, contact block, purpose, emergency filing), minus the new
     * trailing field. Exists purely so the many existing callers (mostly tests) that already
     * construct the full 14-argument form keep compiling unchanged; every one of them gets
     * {@code quotaPoolPreference = null}, i.e. the CARRIED_IN_FIRST default -- see
     * LeaveQuotaPoolPreference#orDefault.
     */
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
