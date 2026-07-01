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
    BigDecimal politicalDonation
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
}
