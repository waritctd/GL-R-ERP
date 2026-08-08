package th.co.glr.hr.payroll.declaration;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceCapEntry;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceCapKind;

/**
 * Read-only catalogue of every ค่าลดหย่อน cap {@code PayrollCalculator} enforces, for
 * {@code GET /api/payroll/tax-allowances/caps} (decision #1: caps must be surfaced inline, sourced
 * from the backend, never hardcoded in the UI).
 *
 * <p><b>Deliberately NOT wired into {@code PayrollCalculator}.</b> That class is untouchable for a
 * cosmetic refactor per CLAUDE.md; this catalogue is a hand-maintained mirror, and
 * {@code TaxAllowanceCapCatalogTest} proves correspondence by DRIVING the real calculator (via
 * {@code PayrollService#preview}) rather than reading its source — if a literal here drifts from
 * {@code PayrollCalculator}'s actual one, that test fails, naming the field. See that test and
 * {@code PayrollCalculator}'s {@code allowanceBreakdown}/{@code retirementAllowance}/
 * {@code childAllowanceCap}/{@code disabledCareAllowanceCap}/{@code parentCareAllowance} for the
 * literals this mirrors.
 */
@Component
public class TaxAllowanceCapCatalog {
    private static final BigDecimal SIXTY_THOUSAND = new BigDecimal("60000.00");
    private static final BigDecimal THIRTY_THOUSAND = new BigDecimal("30000.00");
    // SSF (กองทุนรวมเพื่อการออม) purchases stopped being deductible from ปีภาษี 2568 (Gregorian
    // 2025) onward — mirrors PayrollCalculator's private SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR exactly.
    private static final int SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR = 2025;
    // Thai ESG (กองทุนรวมไทยเพื่อความยั่งยืน): the enhanced ฿300,000 ceiling applies ONLY to units
    // purchased 1 Jan 2024 - 31 Dec 2026 (ปีภาษี 2567-2569) — bounded on BOTH sides, unlike SSF's
    // one-way sunset above. Before 2024 and from ปีภาษี 2570 (Gregorian 2027) onward, the ceiling
    // is its original ฿100,000 — NOT a sunset to zero like SSF, and the 30% of assessable income
    // rate is unchanged in every regime, only the absolute ceiling steps down. Mirrors
    // PayrollCalculator's private THAI_ESG_ENHANCED_CAP_FIRST_TAX_YEAR / _LAST_TAX_YEAR exactly
    // (see that class for why ฿0 before the fund's actual launch date is deliberately NOT modelled,
    // and why the comparison direction differs from SSF's above). If a later Royal Decree extends
    // the enhanced window, update both constants together (and mockApi.js's mirror).
    private static final int THAI_ESG_ENHANCED_CAP_FIRST_TAX_YEAR = 2024;
    private static final int THAI_ESG_ENHANCED_CAP_LAST_TAX_YEAR = 2026;

    public List<TaxAllowanceCapEntry> capsFor(int taxYear) {
        boolean ssfDeductible = taxYear < SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR;
        boolean thaiEsgEnhanced = taxYear >= THAI_ESG_ENHANCED_CAP_FIRST_TAX_YEAR
            && taxYear <= THAI_ESG_ENHANCED_CAP_LAST_TAX_YEAR;
        BigDecimal thaiEsgCeiling = thaiEsgEnhanced
            ? new BigDecimal("300000.00")
            : new BigDecimal("100000.00");
        return List.of(
            // Granted automatically — display only, never declared (decision #1).
            new TaxAllowanceCapEntry("personal", TaxAllowanceCapKind.FLAT, null,
                SIXTY_THOUSAND, null, null, null, null, false),

            // ---- ครอบครัว: each item stands alone, no shared family ceiling in the calculator ----
            new TaxAllowanceCapEntry("spouse", TaxAllowanceCapKind.FLAT, null,
                SIXTY_THOUSAND, null, null, null, null, true),
            new TaxAllowanceCapEntry("child", TaxAllowanceCapKind.PER_HEAD, null,
                THIRTY_THOUSAND, null, null, null, null, true),
            // 2nd-or-later child born from พ.ศ. 2561 onward: an ADDITIONAL 30,000 per such head, on
            // top of the plain child rate above — see PayrollCalculator#childAllowanceCap.
            new TaxAllowanceCapEntry("child_double", TaxAllowanceCapKind.PER_HEAD, null,
                THIRTY_THOUSAND, null, null, null, null, true),
            new TaxAllowanceCapEntry("disabled_care", TaxAllowanceCapKind.PER_HEAD, null,
                new BigDecimal("60000.00"), null, null, null, null, true),
            // 30,000 per qualifying parent, 120,000 = the 4-parent statutory maximum.
            new TaxAllowanceCapEntry("parent_care", TaxAllowanceCapKind.PER_HEAD, null,
                THIRTY_THOUSAND, null, new BigDecimal("120000.00"), null, null, true),
            new TaxAllowanceCapEntry("maternity", TaxAllowanceCapKind.FLAT, null,
                SIXTY_THOUSAND, null, null, null, null, true),

            // ---- ประกัน: life + health share one 100,000 ceiling ----
            new TaxAllowanceCapEntry("life_insurance", TaxAllowanceCapKind.SHARED_GROUP, "life_health",
                new BigDecimal("100000.00"), new BigDecimal("100000.00"), null, null, null, true),
            new TaxAllowanceCapEntry("health_insurance", TaxAllowanceCapKind.SHARED_GROUP, "life_health",
                new BigDecimal("25000.00"), new BigDecimal("100000.00"), null, null, null, true),
            new TaxAllowanceCapEntry("parent_health_insurance", TaxAllowanceCapKind.FLAT, null,
                new BigDecimal("15000.00"), null, null, null, null, true),

            // ---- การออม/ลงทุน: RMF -> SSF -> pension share one 500,000 cluster, consumed in that order ----
            new TaxAllowanceCapEntry("rmf", TaxAllowanceCapKind.PERCENT_OF_INCOME, "retirement",
                new BigDecimal("500000.00"), new BigDecimal("500000.00"), null, new BigDecimal("0.30"), null, true),
            new TaxAllowanceCapEntry("ssf", TaxAllowanceCapKind.PERCENT_OF_INCOME, "retirement",
                ssfDeductible ? new BigDecimal("200000.00") : BigDecimal.ZERO,
                new BigDecimal("500000.00"), null,
                ssfDeductible ? new BigDecimal("0.30") : BigDecimal.ZERO, null, true),
            new TaxAllowanceCapEntry("pension", TaxAllowanceCapKind.PERCENT_OF_INCOME, "retirement",
                new BigDecimal("200000.00"), new BigDecimal("500000.00"), null, new BigDecimal("0.15"), null, true),
            new TaxAllowanceCapEntry("thai_esg", TaxAllowanceCapKind.PERCENT_OF_INCOME, null,
                thaiEsgCeiling, null, null, new BigDecimal("0.30"), null, true),

            // ---- อื่น ๆ ----
            new TaxAllowanceCapEntry("home_loan_interest", TaxAllowanceCapKind.FLAT, null,
                new BigDecimal("100000.00"), null, null, null, null, true),

            // ---- บริจาค: education (x2) + general share 10% of income-after-other-deductions;
            // political sits OUTSIDE that cap entirely, its own flat 10,000 ----
            new TaxAllowanceCapEntry("education_donation", TaxAllowanceCapKind.PERCENT_OF_INCOME, "donation",
                null, null, null, new BigDecimal("0.10"), new BigDecimal("2"), true),
            new TaxAllowanceCapEntry("general_donation", TaxAllowanceCapKind.PERCENT_OF_INCOME, "donation",
                null, null, null, new BigDecimal("0.10"), null, true),
            new TaxAllowanceCapEntry("political_donation", TaxAllowanceCapKind.FLAT, null,
                new BigDecimal("10000.00"), null, null, null, null, true)
        );
    }
}
