package th.co.glr.hr.payroll;

import java.math.BigDecimal;

public record PayrollTaxAllowanceInput(
    BigDecimal spouseAllowance,
    BigDecimal childAllowance,
    BigDecimal parentCareAllowance,
    BigDecimal disabledCareAllowance,
    BigDecimal maternityAllowance,
    BigDecimal lifeInsuranceAllowance,
    BigDecimal healthInsuranceAllowance,
    BigDecimal parentHealthInsuranceAllowance,
    BigDecimal rmfAllowance,
    BigDecimal ssfAllowance,
    BigDecimal pensionInsuranceAllowance,
    BigDecimal thaiEsgAllowance,
    BigDecimal homeLoanInterestAllowance,
    BigDecimal educationDonation,
    BigDecimal generalDonation,
    BigDecimal politicalDonation,
    // ล.ย.01 completeness (2026-07-28, V93). Appended last so every 16-arg positional call site keeps
    // compiling via the legacy constructor below.
    //
    // กองทุนสำรองเลี้ยงชีพ: the employee's OWN contribution for the tax year. Was missing entirely,
    // which over-withheld every provident-fund member. Deductible up to 15% of ค่าจ้าง and 500,000,
    // inside the same 500,000 retirement cluster as RMF and บำนาญ.
    BigDecimal providentFundAllowance,
    // Per-head counts, which is what แบบ ล.ย.01 actually asks for. The engine caps the declared baht
    // amounts against these rather than trusting a free-typed figure: 30,000 per child, 60,000 for the
    // second and later child born from พ.ศ. 2561, 60,000 per disabled person cared for.
    int childCount,
    int childCountDouble,
    int disabledCareCount,
    // บัตรประจำตัวคนพิการ. With it, the ยกเว้นเงินได้ 190,000 (กฎกระทรวง ฉบับที่ 126) applies at any
    // age; without it, only from 65. See PayrollCalculator#assessAnnualTax.
    boolean disabilityCardHolder,
    // Parent allowance per qualifying head (2026-07-29, V95/task 2): ฿30,000 PER qualifying parent,
    // ฿120,000 = the four-parent maximum -- NOT a flat cap regardless of count (handoff section 4).
    // Nullable/0 means "no count declared yet"; PayrollCalculator falls back to the legacy flat-cap
    // treatment of parentCareAllowance in that case (see that class for the reconciliation rule
    // between this and the pre-existing baht-typed parentCareAllowance field above -- V95 known risk
    // 2, "two sources of truth for one allowance", resolved there).
    Integer parentCareCount
) {
    public static PayrollTaxAllowanceInput empty() {
        return new PayrollTaxAllowanceInput(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            0,
            0,
            false,
            0
        );
    }

    /**
     * Legacy 16-arg constructor: the full signature before the ล.ย.01 completeness fields (V93) and
     * {@code parentCareCount} (V95/task 2) existed. Kept so every pre-V93 call site keeps compiling --
     * including {@code PayrollExcelReconciliationTest}, which still uses this exact arity after its
     * 2026-07-29 repoint onto {@code calculateClassified} (Opus review; that repoint changed which
     * {@link PayrollCalculator} entry point the file drives, not this constructor).
     *
     * <p>The per-head counts are DERIVED from the declared amounts rather than defaulted to zero, for
     * the same reason V93's migration backfills them that way: at this arity the amount is the only
     * evidence there is, and reading "30,000 declared, 0 children" as an overstatement would silently
     * delete a real allowance. Deriving keeps a legacy caller byte-identical to its pre-V93 result,
     * while every caller that supplies real counts -- which is the repository, and therefore all of
     * production -- gets the genuine per-head cap.
     *
     * <p>The doubled 60,000 rate for a second-or-later child born from พ.ศ. 2561 is NOT assumed here:
     * without the count there is nothing to say which children qualify, so the derivation stays on the
     * plain 30,000 rate and under-claims rather than over-claims. {@code parentCareCount} defaults to
     * 0 ("no count declared"), which reproduces the pre-existing flat-cap {@code parentCareAllowance}
     * behaviour exactly -- see {@link PayrollCalculator}.
     */
    public PayrollTaxAllowanceInput(
        BigDecimal spouseAllowance,
        BigDecimal childAllowance,
        BigDecimal parentCareAllowance,
        BigDecimal disabledCareAllowance,
        BigDecimal maternityAllowance,
        BigDecimal lifeInsuranceAllowance,
        BigDecimal healthInsuranceAllowance,
        BigDecimal parentHealthInsuranceAllowance,
        BigDecimal rmfAllowance,
        BigDecimal ssfAllowance,
        BigDecimal pensionInsuranceAllowance,
        BigDecimal thaiEsgAllowance,
        BigDecimal homeLoanInterestAllowance,
        BigDecimal educationDonation,
        BigDecimal generalDonation,
        BigDecimal politicalDonation
    ) {
        this(
            spouseAllowance, childAllowance, parentCareAllowance, disabledCareAllowance,
            maternityAllowance, lifeInsuranceAllowance, healthInsuranceAllowance,
            parentHealthInsuranceAllowance, rmfAllowance, ssfAllowance, pensionInsuranceAllowance,
            thaiEsgAllowance, homeLoanInterestAllowance, educationDonation, generalDonation,
            politicalDonation,
            BigDecimal.ZERO,
            headCount(childAllowance, "30000"),
            0,
            headCount(disabledCareAllowance, "60000"),
            false,
            0
        );
    }

    /**
     * Legacy 21-arg constructor: branch 117's own full signature (16 base allowances + ล.ย.01
     * completeness) before {@code parentCareCount} (task 2) existed. Kept so pre-task-2 call sites --
     * including several {@code PayrollCalculatorPo96ReviewTest}/{@code PayrollCalculatorTest} cases
     * that construct the per-head-count fields explicitly -- still compile. {@code parentCareCount}
     * defaults to 0 ("no count declared"), same as the 16-arg legacy constructor above.
     */
    public PayrollTaxAllowanceInput(
        BigDecimal spouseAllowance,
        BigDecimal childAllowance,
        BigDecimal parentCareAllowance,
        BigDecimal disabledCareAllowance,
        BigDecimal maternityAllowance,
        BigDecimal lifeInsuranceAllowance,
        BigDecimal healthInsuranceAllowance,
        BigDecimal parentHealthInsuranceAllowance,
        BigDecimal rmfAllowance,
        BigDecimal ssfAllowance,
        BigDecimal pensionInsuranceAllowance,
        BigDecimal thaiEsgAllowance,
        BigDecimal homeLoanInterestAllowance,
        BigDecimal educationDonation,
        BigDecimal generalDonation,
        BigDecimal politicalDonation,
        BigDecimal providentFundAllowance,
        int childCount,
        int childCountDouble,
        int disabledCareCount,
        boolean disabilityCardHolder
    ) {
        this(
            spouseAllowance, childAllowance, parentCareAllowance, disabledCareAllowance,
            maternityAllowance, lifeInsuranceAllowance, healthInsuranceAllowance,
            parentHealthInsuranceAllowance, rmfAllowance, ssfAllowance, pensionInsuranceAllowance,
            thaiEsgAllowance, homeLoanInterestAllowance, educationDonation, generalDonation,
            politicalDonation, providentFundAllowance, childCount, childCountDouble,
            disabledCareCount, disabilityCardHolder, 0
        );
    }

    /**
     * Legacy 17-arg constructor: branch 118's own original signature (16 base allowances +
     * {@code parentCareCount}, no ล.ย.01 completeness fields) before those fields (V93, branch 117)
     * were merged in. Kept so pre-merge task-2 call sites that supply {@code parentCareCount}
     * explicitly -- including {@code PayrollClassifiedLimbClampReviewTest} -- still compile. The
     * ล.ย.01 fields default the same way the 16-arg legacy constructor above does; {@code
     * parentCareCount} is taken from the caller, not defaulted.
     */
    public PayrollTaxAllowanceInput(
        BigDecimal spouseAllowance,
        BigDecimal childAllowance,
        BigDecimal parentCareAllowance,
        BigDecimal disabledCareAllowance,
        BigDecimal maternityAllowance,
        BigDecimal lifeInsuranceAllowance,
        BigDecimal healthInsuranceAllowance,
        BigDecimal parentHealthInsuranceAllowance,
        BigDecimal rmfAllowance,
        BigDecimal ssfAllowance,
        BigDecimal pensionInsuranceAllowance,
        BigDecimal thaiEsgAllowance,
        BigDecimal homeLoanInterestAllowance,
        BigDecimal educationDonation,
        BigDecimal generalDonation,
        BigDecimal politicalDonation,
        int parentCareCount
    ) {
        this(
            spouseAllowance, childAllowance, parentCareAllowance, disabledCareAllowance,
            maternityAllowance, lifeInsuranceAllowance, healthInsuranceAllowance,
            parentHealthInsuranceAllowance, rmfAllowance, ssfAllowance, pensionInsuranceAllowance,
            thaiEsgAllowance, homeLoanInterestAllowance, educationDonation, generalDonation,
            politicalDonation,
            BigDecimal.ZERO,
            headCount(childAllowance, "30000"),
            0,
            headCount(disabledCareAllowance, "60000"),
            false,
            parentCareCount
        );
    }

    /** Smallest head count that would permit the declared amount — see the legacy constructor. */
    private static int headCount(BigDecimal declaredAmount, String perHead) {
        if (declaredAmount == null || declaredAmount.signum() <= 0) {
            return 0;
        }
        return declaredAmount
            .divide(new BigDecimal(perHead), 0, java.math.RoundingMode.CEILING)
            .intValueExact();
    }
}
