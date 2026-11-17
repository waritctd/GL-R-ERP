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
    // ล.ย.01 completeness. Appended last so every 16-arg positional call site keeps
    // compiling via the legacy constructor below.
    //
    // Per-head counts, which is what แบบ ล.ย.01 actually asks for. The engine caps the declared baht
    // amounts against these rather than trusting a free-typed figure: 30,000 per child, 60,000 for the
    // second and later child born from พ.ศ. 2561, 60,000 per disabled person cared for.
    int childCount,
    int childCountDouble,
    int disabledCareCount,
    // บัตรประจำตัวคนพิการ. With it, the ยกเว้นเงินได้ 190,000 (กฎกระทรวง ฉบับที่ 126) applies at any age; without
    // it, only from 65. See PayrollCalculator#assessAnnualTax.
    boolean disabilityCardHolder,
    // Parent allowance per qualifying head: ฿30,000 PER qualifying parent,
    // ฿120,000 = the four-parent maximum -- NOT a flat cap regardless of count.
    // Nullable/0 means "no count declared yet"; PayrollCalculator falls back to the legacy flat-cap
    // treatment of parentCareAllowance in that case (see that class for the reconciliation rule
    // between this and the baht-typed parentCareAllowance field above, which are two sources of
    // truth for one allowance).
    Integer parentCareCount,
    // ข้อ 9 ล.ย.01, appended LAST, per this record's append-last discipline, so every earlier
    // positional call site keeps compiling via a legacy constructor below.
    //
    // GL&R operates no employer provident fund (zero employees hold a provident_fund_no). But
    // ข้อ 9 is ONE box covering FOUR funds -- กองทุนสำรองเลี้ยงชีพ, กบข., กองทุนสงเคราะห์ครูโรงเรียนเอกชน and
    // กองทุนการออมแห่งชาติ (กอช.). กอช. is an INDIVIDUAL membership an employee may hold whatever
    // their employer does, so the box has to be fillable regardless of the employer-fund finding.
    // See PayrollCalculator#retirementAllowance for the cap and the
    // ordering that follow from this.
    BigDecimal providentFundAllowance
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
            0,
            0,
            0,
            false,
            0,
            BigDecimal.ZERO
        );
    }

    /**
     * Legacy 16-arg constructor: the full signature before the ล.ย.01 completeness fields and
     * {@code parentCareCount} existed. Kept so every older call site keeps compiling --
     * including {@code PayrollExcelReconciliationTest}, which still uses this exact arity (it
     * drives {@link PayrollCalculator}'s {@code calculateClassified} entry point).
     *
     * <p>The per-head counts are DERIVED from the declared amounts rather than defaulted to zero,
     * as the migration backfill does: at this arity the amount is the only
     * evidence there is, and reading "30,000 declared, 0 children" as an overstatement would silently
     * delete a real allowance. Deriving keeps a legacy caller's result unchanged,
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
            headCount(childAllowance, "30000"),
            0,
            headCount(disabledCareAllowance, "60000"),
            false,
            0,
            BigDecimal.ZERO
        );
    }

    /**
     * Legacy 20-arg constructor: the signature with the 16 base allowances + ล.ย.01
     * completeness, before {@code parentCareCount} existed. Kept so older call sites --
     * including several {@code PayrollCalculatorPo96ReviewTest}/{@code PayrollCalculatorTest} cases
     * that construct the per-head-count fields explicitly -- still compile. {@code parentCareCount}
     * defaults to 0 ("no count declared"), same as the 16-arg legacy constructor above.
     *
     * <p>It has no {@code providentFundAllowance} parameter: GL&R has no provident fund, and every
     * caller of the earlier 21-arg form passed a literal {@code BigDecimal.ZERO} for it.
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
            politicalDonation, childCount, childCountDouble,
            disabledCareCount, disabilityCardHolder, 0, BigDecimal.ZERO
        );
    }

    /** Legacy 17-arg constructor: branch 118's own original signature (16 base allowances + parentCareCount, no
     * ล.ย.01 completeness fields) before those fields (V93, branch 117) were merged in. */
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
            headCount(childAllowance, "30000"),
            0,
            headCount(disabledCareAllowance, "60000"),
            false,
            parentCareCount,
            BigDecimal.ZERO
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
