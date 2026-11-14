package th.co.glr.hr.leave;

import java.math.BigDecimal;

/** §5 company-announcement leave rules as data (V116): paidDaysCap through oncePerEmployment turn what used to
 * be hardcoded per-type branches in LeaveService into columns on hr.leave_type. Seeded TRUE for VACATION only;
 * every other type is FALSE. */
public record LeaveTypeDto(
    String code,
    String nameTh,
    String nameEn,
    BigDecimal annualQuotaDays,
    boolean requiresAttachment,
    BigDecimal paidDaysCap,
    int advanceNoticeDays,
    int minServiceMonths,
    BigDecimal maxConsecutiveDays,
    boolean oncePerEmployment,
    LeaveDayCountBasis dayCountBasis,
    boolean proratedFirstYear,
    BigDecimal firstYearMaxDays,
    Integer certificateFilingWindowDays,
    int noCertificateMonthlyTolerance,
    Integer emergencyMonthlyAllowance,
    boolean carriesForward
) {
}
