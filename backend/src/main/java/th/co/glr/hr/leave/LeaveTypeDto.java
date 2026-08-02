package th.co.glr.hr.leave;

import java.math.BigDecimal;

/**
 * §5 company-announcement leave rules as data (V116): {@code paidDaysCap} through
 * {@code oncePerEmployment} turn what used to be hardcoded per-type branches in
 * {@code LeaveService} into columns on {@code hr.leave_type}. See V116's migration comment for the
 * seeded values per type and the decisions behind them.
 *
 * <p>{@code dayCountBasis} (V119, 2026-08-02): §5.4 MATERNITY calendar-day counting -- see
 * {@link LeaveDayCountBasis} for the announcement text and V119's migration comment for why only
 * MATERNITY is {@code CALENDAR_DAYS} (every other type, including MILITARY/ORDINATION, stays the
 * default {@code WORKING_DAYS}).
 *
 * <p>{@code proratedFirstYear} (V120): fixes a V116 seeding defect where VACATION used
 * {@code minServiceMonths=12} as an outright eligibility floor instead of pro-rating, contradicting
 * §5.3's "(กรณีอายุงานไม่ถึงหนึ่งปีให้ลาได้เป็นสัดส่วนตามอายุงาน)". See {@link LeaveService#employeeAnnualQuota}
 * for the pro-ration formula and V120's migration comment for the full defect writeup.
 *
 * <p>{@code firstYearMaxDays} (V120, defect 3): an ADDITIONAL total-per-year day ceiling that binds
 * only while {@code proratedFirstYear} would otherwise apply (i.e. only during the employee's first
 * 12 months of service) -- {@code null} means no such ceiling. PERSONAL is seeded at 3.00 (the 2567
 * text's "(...และไม่อนุญาตให้ลากิจเกิน 3 วัน)", now scoped to first-year employees only, replacing the
 * old blanket {@code maxConsecutiveDays=3} everyone-and-consecutive-only rule). See {@link
 * LeaveService#autoRejectNote} for how this composes with pro-ration (effective cap =
 * {@code min(proratedQuota, firstYearMaxDays)}).
 */
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
    BigDecimal firstYearMaxDays
) {
}
