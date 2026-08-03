package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Quota read model for the UI: how much of each type an employee has used, keyed by {@link
 * SpecialMoneyType} name so it serializes as a plain JSON object.
 *
 * <p>The three maps are counted over deliberately different status sets, mirroring {@link
 * UsageSnapshot} — read its javadoc before using any of them:
 *
 * <ul>
 *   <li>{@code approvedAmountThisYearByType} — {@code APPROVED} only. Money; an undecided request
 *       has consumed none of the annual balance.
 *   <li>{@code approvedCountLifetimeByType} — <b>despite the name, this counts in-flight rows too</b>
 *       ({@code SUBMITTED}, {@code MANAGER_APPROVED} and {@code APPROVED}). It backs the "once per
 *       lifetime" guard, which must see undecided requests or an employee could file the same claim
 *       twice before either was decided. The name is a misnomer inherited from the original DTO and
 *       is left alone here rather than renamed as a side effect — renaming it changes the wire
 *       contract and every caller. Worth a follow-up; do not "fix" the counting to match the name.
 *   <li>{@code activeCountThisYearByType} — same in-flight-inclusive status set, scoped to the
 *       request's calendar year. Backs the "once per year" guard.
 * </ul>
 *
 * <p>{@code activeCountThisYearByType} was already computed by {@link UsageSnapshot} for the
 * once-per-year uniform rule but dropped on the way out, so the UI could not tell an employee they
 * had already claimed this year's uniform until the submit came back 400. Any UI built on these
 * maps must say "ยื่นแล้ว" (filed), not "อนุมัติแล้ว" (approved), for the two count maps.
 */
public record SpecialMoneyUsageDto(
    long employeeId,
    int year,
    Map<String, BigDecimal> approvedAmountThisYearByType,
    Map<String, Integer> approvedCountLifetimeByType,
    Map<String, Integer> activeCountThisYearByType) {
}
