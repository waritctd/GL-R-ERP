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
    @PositiveOrZero BigDecimal politicalDonation
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
