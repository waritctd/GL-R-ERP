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
 *
 * <p>{@code certificateFilingWindowDays} / {@code noCertificateMonthlyTolerance} (V124): §5.1's two
 * previously-missing SICK rules -- a working-day deadline to file a medical certificate, and a
 * per-calendar-month tolerance for certificate-less "minor illness" requests. {@code null} filing
 * window = no such rule; {@code 0} tolerance = no tolerance (a missing attachment always rejects,
 * today's behaviour for every type except SICK). See {@link LeaveService#sickCertificateNote} for
 * the combined decision table and V124's migration comment for the seeded SICK values (3 / 3) and
 * the interpretation caveats.
 *
 * <p>{@code emergencyMonthlyAllowance} (V125): §5.2's emergency-filing exception -- the number of
 * times PER CALENDAR MONTH a request of this type may bypass {@code advanceNoticeDays} under
 * "อนุโลมให้ได้ไม่เกินเดือนละ 3 ครั้ง โดยไม่หักเงิน" ("...allowed up to 3 times a month, without
 * deduction"). {@code null} = no such exception (every type except PERSONAL, seeded at 3). See
 * {@link LeaveService#autoRejectNote} for the combined notice x emergency decision table.
 *
 * <p><b>{@code noCertificateMonthlyTolerance} (V124, SICK) and {@code emergencyMonthlyAllowance}
 * (V125, PERSONAL) are BOTH "&lt;=N per calendar month" counters over {@code hr.leave_request}, but
 * they count DIFFERENT things through DIFFERENT columns</b> -- {@code no_certificate_monthly_tolerance}
 * counts certificate-less SICK requests via {@code LeaveRepository#countNoCertificateRequestsInMonth}
 * (keyed off {@code attachment_id IS NULL}); {@code emergency_monthly_allowance} counts emergency
 * ลากิจ filings via {@code LeaveRepository#countEmergencyFilings} (keyed off the dedicated
 * {@code emergency_filing} boolean column, set only by {@code LeaveRepository#markEmergencyFiling}).
 * The two queries share no column and cannot contaminate each other, even though both happen to be
 * seeded at 3 and both read as "3 times a month" in the announcement text -- see each repository
 * method's Javadoc. A shared/generic "monthly tolerance" counter was deliberately NOT used for both:
 * the two rules trigger on unrelated conditions (a missing attachment vs. a missed notice deadline),
 * and conflating them into one counter would let one rule's occasions silently consume the other's
 * allowance.
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
    BigDecimal firstYearMaxDays,
    Integer certificateFilingWindowDays,
    int noCertificateMonthlyTolerance,
    Integer emergencyMonthlyAllowance
) {
}
