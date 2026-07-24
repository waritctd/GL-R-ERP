package th.co.glr.hr.payroll;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public record PayrollEmployeeInputRequest(
    @NotNull Long employeeId,
    @PositiveOrZero BigDecimal specialPay1,
    @PositiveOrZero BigDecimal specialPay2,
    @PositiveOrZero BigDecimal specialPay3,
    @PositiveOrZero BigDecimal specialPay4,
    @PositiveOrZero BigDecimal specialPay5,
    @PositiveOrZero BigDecimal specialPay6,
    @PositiveOrZero BigDecimal specialPay7,
    @PositiveOrZero BigDecimal specialPay8,
    @PositiveOrZero BigDecimal nonTaxableIncome,
    @PositiveOrZero BigDecimal unpaidLeaveDays,
    @PositiveOrZero BigDecimal studentLoanDeduction,
    @PositiveOrZero BigDecimal legalExecutionDeduction,
    @PositiveOrZero BigDecimal otherPostTaxDeductions,
    @PositiveOrZero BigDecimal spouseAllowance,
    @PositiveOrZero BigDecimal childAllowance,
    @PositiveOrZero BigDecimal parentCareAllowance,
    @PositiveOrZero BigDecimal disabledCareAllowance,
    @PositiveOrZero BigDecimal maternityAllowance,
    @PositiveOrZero BigDecimal lifeInsuranceAllowance,
    @PositiveOrZero BigDecimal healthInsuranceAllowance,
    @PositiveOrZero BigDecimal parentHealthInsuranceAllowance,
    @PositiveOrZero BigDecimal rmfAllowance,
    @PositiveOrZero BigDecimal ssfAllowance,
    @PositiveOrZero BigDecimal pensionInsuranceAllowance,
    @PositiveOrZero BigDecimal thaiEsgAllowance,
    @PositiveOrZero BigDecimal homeLoanInterestAllowance,
    @PositiveOrZero BigDecimal educationDonation,
    @PositiveOrZero BigDecimal generalDonation,
    @PositiveOrZero BigDecimal politicalDonation,
    // Reconciliation additions (2026-07-21, C4): the three missing pre-tax deductions (sheet columns
    // Z/AA/AB). HR types these per run, unlike director remuneration which lives on the employee.
    @PositiveOrZero BigDecimal warningLetterDeduction,
    @PositiveOrZero BigDecimal customerReturnDeduction,
    @PositiveOrZero BigDecimal otherPretaxDeduction,
    // Withholding-tax override (2026-07-24, V88): the PER-RUN value HR types for this employee this
    // run. NULLABLE and meaningful -- null = "no per-run override" (fall back to the employee standing
    // override, else compute); a non-null value (including 0) WINS over the standing value. Read RAW
    // via withholdingTaxOverride() below (NOT through safe()) so null is preserved -- coercing it to
    // zero would silently force "withhold nothing" on every run. @PositiveOrZero still allows null.
    @PositiveOrZero BigDecimal withholdingTaxOverride
) {
    public List<BigDecimal> specialPays() {
        return List.of(
            safe(specialPay1),
            safe(specialPay2),
            safe(specialPay3),
            safe(specialPay4),
            safe(specialPay5),
            safe(specialPay6),
            safe(specialPay7),
            safe(specialPay8)
        );
    }

    public PayrollTaxAllowanceInput taxAllowances() {
        return new PayrollTaxAllowanceInput(
            safe(spouseAllowance),
            safe(childAllowance),
            safe(parentCareAllowance),
            safe(disabledCareAllowance),
            safe(maternityAllowance),
            safe(lifeInsuranceAllowance),
            safe(healthInsuranceAllowance),
            safe(parentHealthInsuranceAllowance),
            safe(rmfAllowance),
            safe(ssfAllowance),
            safe(pensionInsuranceAllowance),
            safe(thaiEsgAllowance),
            safe(homeLoanInterestAllowance),
            safe(educationDonation),
            safe(generalDonation),
            safe(politicalDonation)
        );
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
