package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Everything {@link LeaveFormRenderer} needs to print one ใบลาหยุด (F-HR-020) for a single request.
 * A pure data carrier, assembled by {@link LeaveService#buildLeaveForm} from the already-computed
 * {@link LeaveRequestDto} plus two read-only lookups the DTO does not carry: the employee's
 * nickname/position/department/division (from {@link LeaveRepository#findLeaveFormHeader}) and their
 * year-to-date approved usage per type (from the leave balances). No query and no leave-day math
 * happens in the renderer -- same division of labour as {@link LeaveReportRenderer}.
 */
public record LeaveFormData(
    String employeeCode,
    String employeeName,
    String nickName,
    String positionTh,
    String departmentTh,
    String divisionTh,
    String leaveTypeCode,
    String leaveTypeNameTh,
    LocalDate filedDate,
    LocalDate startDate,
    LocalDate endDate,
    // Sub-day leave (V90): both null for a whole-day request.
    LocalTime startTime,
    LocalTime endTime,
    BigDecimal totalDays,
    String reason,
    // "ติดต่อได้ระหว่างลา" block (V90). Any may be null/blank -- printed as a blank on the form.
    String contactHouseNo,
    String contactSubdistrict,
    String contactDistrict,
    String contactProvince,
    String contactPhone,
    // ประวัติการลาในรอบปีนี้ -- approved days used so far this quota year (excludes this pending request).
    BigDecimal usedTotalDays,
    BigDecimal usedPersonalDays,
    BigDecimal usedSickDays,
    BigDecimal usedVacationDays,
    // Approval outcome (2026-09, auto-filled ความเห็นผู้บังคับบัญชา / HR-receipt boxes). All three are
    // null on the SUBMITTED form emailed to HR (the boxes render blank for a human to sign); they are
    // populated only when the form is regenerated after a manual decision, so the copy emailed to the
    // employee shows the ticked box, the approver's signature, and the decision date/time.
    // decision: "APPROVED" | "REJECTED" | null (pending -- boxes blank).
    // approverName: the ผู้อนุมัติ signature -- the employee's reports-to manager on file.
    // approvedAt: the decision timestamp (Bangkok), null when pending; carries both the date and the
    //   time the HR-receipt box prints.
    String decision,
    String approverName,
    LocalDateTime approvedAt
) {
}
