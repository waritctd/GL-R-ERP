package th.co.glr.hr.leave;

import java.math.BigDecimal;

/**
 * Per-calendar-year quota attribution for a single leave request (V118 cross-year quota fix,
 * 2026-08-02). A request's [startDate, endDate] range almost always falls inside one calendar year,
 * but a long request (MATERNITY, up to 98 working days / ~136 calendar days -- §5.4) can span a 31
 * Dec/1 Jan boundary. {@code hr.leave_request} keeps total_days/paid_days/unpaid_days as the SUMS
 * across every year touched (so {@code chk_leave_paid_unpaid_sum} still holds); this record is one
 * row of the true per-year breakdown, persisted in {@code hr.leave_request_quota_year} and computed
 * in {@link LeaveService#submit}.
 *
 * <p><b>Design decision (per-year-independent quota AND paid cap):</b> days falling in year A consume
 * year A's own remaining quota and year A's own {@code paid_days_cap} allowance; days in year B
 * consume year B's, entirely independently -- a fresh allotment, not a shared budget split across the
 * boundary. This is simple and matches how {@code hr.leave_type}'s quota/cap columns are already
 * inherently per-calendar-year concepts everywhere else in this codebase (see {@link
 * LeaveRepository#sumUsedDays}/{@link LeaveRepository#sumPaidDays}, both keyed by year). The known
 * consequence: a request that happens to straddle 31 Dec can receive MORE total paid days than an
 * otherwise-identical same-year request would, because each side of the boundary gets its own fresh
 * cap (e.g. a 98-day MATERNITY request split 44/54 across the boundary can pay up to 44+45=89 days,
 * not 45) -- see LeaveService's submit() Javadoc and the branch's PR description for the concrete
 * worked example. This is a stated design choice, not an oversight; flagged here so a future reader
 * does not "fix" it without knowing it was deliberate.
 *
 * <p>{@code quotaRemainingBefore}/{@code quotaRemainingAfter} are this year's own remaining-quota
 * figures (mirroring {@code hr.leave_request}'s own columns of the same name, which now reflect only
 * the request's START year -- see that table's column comments).
 */
record LeaveQuotaYearSplit(
    int quotaYear,
    BigDecimal totalDays,
    BigDecimal paidDays,
    BigDecimal unpaidDays,
    BigDecimal quotaRemainingBefore,
    BigDecimal quotaRemainingAfter
) {
}
