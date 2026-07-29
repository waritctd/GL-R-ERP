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
    boolean disabilityCardHolder
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
            BigDecimal.ZERO
        );
    }

    /**
     * Legacy 16-arg constructor: the full signature before the ล.ย.01 completeness fields existed.
     *
     * <p>The per-head counts are DERIVED from the declared amounts rather than defaulted to zero, for
     * the same reason V93's migration backfills them that way: at this arity the amount is the only
     * evidence there is, and reading "30,000 declared, 0 children" as an overstatement would silently
     * delete a real allowance. Deriving keeps a legacy caller byte-identical to its pre-V93 result,
     * while every caller that supplies real counts — which is the repository, and therefore all of
     * production — gets the genuine per-head cap.
     *
     * <p>The doubled 60,000 rate for a second-or-later child born from พ.ศ. 2561 is NOT assumed here:
     * without the count there is nothing to say which children qualify, so the derivation stays on the
     * plain 30,000 rate and under-claims rather than over-claims.
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
            false
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
