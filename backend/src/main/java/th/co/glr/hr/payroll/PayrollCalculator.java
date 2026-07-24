package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PayrollCalculator {
    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 4;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal THIRTY = new BigDecimal("30");
    private static final BigDecimal EIGHT = new BigDecimal("8");
    private static final BigDecimal SSO_RATE = new BigDecimal("0.05");
    private static final BigDecimal SSO_MIN_BASE = new BigDecimal("1650.00");
    // SSO wage ceiling raised by Royal Gazette effective 1 Jan 2026 (2569):
    // max wage base 17,500 -> max 875/month -> 10,500/year (17,500 x 5% x 12).
    // Schedule: 2026-2028 = 17,500; 2029-2031 = 20,000; 2032+ = 23,000.
    private static final BigDecimal SSO_MAX_BASE = new BigDecimal("17500.00");
    private static final BigDecimal SSO_YEAR_CAP = new BigDecimal("10500.00");
    private static final BigDecimal PERSONAL_ALLOWANCE = new BigDecimal("60000.00");
    private static final BigDecimal EXPENSE_DEDUCTION_CAP = new BigDecimal("100000.00");
    private static final BigDecimal MIN_NET_AFTER_LEGAL_EXECUTION = new BigDecimal("20000.00");
    private static final int SPECIAL_PAY_SLOTS = 8;

    public PayrollCalculation calculate(PayrollCalculationInput input) {
        PayrollYearToDate yearToDate = input.yearToDate() == null ? PayrollYearToDate.empty() : input.yearToDate();
        PayrollTaxAllowanceInput allowances = input.taxAllowances() == null
            ? PayrollTaxAllowanceInput.empty()
            : input.taxAllowances();

        BigDecimal baseSalary = money(input.baseSalary());
        List<BigDecimal> specialPays = normalizeSpecialPays(input.specialPays());
        BigDecimal specialPayTotal = specialPays.stream().reduce(ZERO, BigDecimal::add);
        BigDecimal overtimePay = money(input.overtimePay());
        BigDecimal commissionPay = money(input.commissionPay());
        // ค่าตอบแทนกรรมการ (director remuneration, sheet column G). It IS taxable income (the sheet's
        // W = SUM(G:V) for directors), so it joins grossEarnings here. It is deliberately NOT folded
        // into ssoWageBase below: director remuneration is not wages under the Social Security Act, and
        // the sheet carries no SSO row at all for directors.
        BigDecimal directorRemuneration = money(input.directorRemuneration());
        // Taxable earnings only (sheet column A). Non-taxable income (sheet column D)
        // is excluded from tax and SSO and added back to net pay at the end.
        BigDecimal grossEarnings = money(baseSalary.add(specialPayTotal).add(overtimePay).add(commissionPay)
            .add(directorRemuneration));
        BigDecimal nonTaxableIncome = money(input.nonTaxableIncome());

        BigDecimal dailyRate = baseSalary.divide(THIRTY, RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal hourlyRate = dailyRate.divide(EIGHT, RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal unpaidLeaveDays = quantity(input.unpaidLeaveDays());
        BigDecimal unpaidLeaveDeduction = money(dailyRate.multiply(unpaidLeaveDays));
        // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): a positive pre-tax CREDIT reversing
        // a PRIOR month's over-deduction (the leave that caused it was cancelled after that month's
        // payroll had already processed -- see hr.leave_payroll_correction / LeaveService#cancel).
        // Deliberately kept as its own field rather than netted into unpaidLeaveDays beforehand --
        // that field is HR-typed and @PositiveOrZero, and this month may legitimately have zero of
        // its own unpaid leave while still owing a refund from an earlier month. It flows through
        // the exact same PRE-TAX path unpaidLeaveDeduction does (grossTaxableIncome, ssoWageBase,
        // totalDeductions below), with the opposite sign, so tax + SSO recompute on the restored
        // income exactly as they would have if the original deduction had never happened.
        BigDecimal leaveRefundDays = quantity(input.leaveRefundDays());
        BigDecimal leaveDeductionRefund = money(dailyRate.multiply(leaveRefundDays));
        // The three missing PRE-TAX deductions (sheet columns Z/AA/AB: หักตามใบเตือน, หัก 6
        // ลูกค้าคืนสินค้า, อื่นๆ). The sheet's AC (รวมรายหักที่ต้องคิดภาษี) is these three plus unpaid
        // leave, and AD = W - AC is what gets taxed -- so they must reduce grossTaxableIncome exactly
        // like unpaidLeaveDeduction already does, not land in the post-tax otherPostTaxDeductions.
        BigDecimal warningLetterDeduction = money(input.warningLetterDeduction());
        BigDecimal customerReturnDeduction = money(input.customerReturnDeduction());
        BigDecimal otherPretaxDeduction = money(input.otherPretaxDeduction());
        BigDecimal grossTaxableIncome = money(grossEarnings
            .subtract(unpaidLeaveDeduction)
            .add(leaveDeductionRefund)
            .subtract(warningLetterDeduction)
            .subtract(customerReturnDeduction)
            .subtract(otherPretaxDeduction)
            .max(ZERO));

        // ssoWageBase stays derived from baseSalary only -- director remuneration and the pre-tax
        // deductions above never touch it, matching the sheet's blank SSO column for directors and the
        // existing base-salary-only SSO treatment for everyone else. The refund credit runs through
        // this same wage base (added back, mirroring unpaidLeaveDeduction's subtraction) so SSO
        // recomputes on the restored income too; ssoWageBase(...)'s existing [MIN,MAX] clamp already
        // protects against a refund pushing the base past the 17,500 ceiling.
        BigDecimal ssoWageBase = ssoWageBase(baseSalary.subtract(unpaidLeaveDeduction).add(leaveDeductionRefund));
        BigDecimal monthlySso = money(ssoWageBase.multiply(SSO_RATE));
        BigDecimal remainingSsoCap = SSO_YEAR_CAP.subtract(money(yearToDate.socialSecurity())).max(ZERO);
        BigDecimal socialSecurity = min(monthlySso, remainingSsoCap);

        int monthsRemaining = Math.max(1, 13 - input.payrollMonthValue());
        BigDecimal projectedAnnualIncome = money(money(yearToDate.taxableIncome())
            .add(grossTaxableIncome.multiply(BigDecimal.valueOf(monthsRemaining))));
        BigDecimal taxExpenseDeduction = min(projectedAnnualIncome.multiply(new BigDecimal("0.50")), EXPENSE_DEDUCTION_CAP);
        AllowanceBreakdown allowanceBreakdown = allowanceBreakdown(
            allowances,
            projectedAnnualIncome,
            taxExpenseDeduction,
            yearToDate,
            socialSecurity,
            monthsRemaining
        );
        BigDecimal taxableAnnualIncome = money(projectedAnnualIncome
            .subtract(taxExpenseDeduction)
            .subtract(allowanceBreakdown.total())
            .max(ZERO));
        BigDecimal annualTax = progressiveTax(taxableAnnualIncome);
        BigDecimal remainingAnnualTax = annualTax.subtract(money(yearToDate.withholdingTax())).max(ZERO);
        BigDecimal withholdingTax = money(remainingAnnualTax.divide(BigDecimal.valueOf(monthsRemaining), MONEY_SCALE, RoundingMode.HALF_UP));

        // Withholding-tax override (2026-07-24, V88). GUARDRAIL: everything above -- progressiveTax,
        // annualTax, taxableAnnualIncome, projectedAnnualIncome, the projection and allowances -- is
        // computed and reported UNCHANGED. When an override is present we ONLY substitute the final
        // withheld amount here; every downstream figure (legal-execution floor, totalDeductions, net)
        // then flows from the substituted value automatically. A null override (the common case) is a
        // no-op and reproduces today's behaviour byte-for-byte. Zero is a legitimate override (withhold
        // nothing) and is honoured -- hence the explicit null check rather than a truthiness/sign test.
        BigDecimal withholdingTaxOverride = input.withholdingTaxOverride() == null
            ? null
            : money(input.withholdingTaxOverride());
        String calculationNote = "Annual projection tax, SSO 5% cap, and legal execution floor applied.";
        if (withholdingTaxOverride != null) {
            withholdingTax = withholdingTaxOverride;
            calculationNote = calculationNote
                + " Withholding tax overridden by HR to " + withholdingTaxOverride.toPlainString()
                + " (computed projection retained for transparency).";
        }

        BigDecimal studentLoanDeduction = money(input.studentLoanDeduction());
        BigDecimal otherPostTaxDeductions = money(input.otherPostTaxDeductions());
        BigDecimal legalExecutionDeduction = legalExecutionDeduction(
            money(input.legalExecutionRequested()),
            grossTaxableIncome,
            socialSecurity,
            withholdingTax,
            studentLoanDeduction,
            otherPostTaxDeductions
        );
        BigDecimal totalDeductions = money(unpaidLeaveDeduction
            .subtract(leaveDeductionRefund)
            .add(warningLetterDeduction)
            .add(customerReturnDeduction)
            .add(otherPretaxDeduction)
            .add(socialSecurity)
            .add(withholdingTax)
            .add(studentLoanDeduction)
            .add(legalExecutionDeduction)
            .add(otherPostTaxDeductions));
        BigDecimal netPay = money(grossEarnings.subtract(totalDeductions).add(nonTaxableIncome).max(ZERO));

        return new PayrollCalculation(
            baseSalary,
            dailyRate,
            hourlyRate,
            specialPays,
            specialPayTotal,
            overtimePay,
            commissionPay,
            grossEarnings,
            nonTaxableIncome,
            unpaidLeaveDays,
            unpaidLeaveDeduction,
            grossTaxableIncome,
            ssoWageBase,
            socialSecurity,
            projectedAnnualIncome,
            taxExpenseDeduction,
            allowanceBreakdown.total(),
            taxableAnnualIncome,
            annualTax,
            withholdingTax,
            studentLoanDeduction,
            legalExecutionDeduction,
            otherPostTaxDeductions,
            totalDeductions,
            netPay,
            calculationNote,
            directorRemuneration,
            warningLetterDeduction,
            customerReturnDeduction,
            otherPretaxDeduction,
            leaveRefundDays,
            leaveDeductionRefund,
            withholdingTaxOverride
        );
    }

    private List<BigDecimal> normalizeSpecialPays(List<BigDecimal> values) {
        List<BigDecimal> result = new ArrayList<>(SPECIAL_PAY_SLOTS);
        for (int index = 0; index < SPECIAL_PAY_SLOTS; index += 1) {
            BigDecimal value = values == null || values.size() <= index ? ZERO : values.get(index);
            result.add(money(value));
        }
        return List.copyOf(result);
    }

    private BigDecimal ssoWageBase(BigDecimal wageBase) {
        BigDecimal safeBase = money(wageBase).max(ZERO);
        if (safeBase.signum() == 0) {
            return ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return min(safeBase.max(SSO_MIN_BASE), SSO_MAX_BASE);
    }

    private AllowanceBreakdown allowanceBreakdown(
        PayrollTaxAllowanceInput input,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        PayrollYearToDate yearToDate,
        BigDecimal currentSocialSecurity,
        int monthsRemaining
    ) {
        BigDecimal projectedSsoAllowance = min(
            SSO_YEAR_CAP,
            money(yearToDate.socialSecurity()).add(currentSocialSecurity.multiply(BigDecimal.valueOf(monthsRemaining)))
        );
        BigDecimal family = min(money(input.spouseAllowance()), new BigDecimal("60000.00"))
            .add(money(input.childAllowance()))
            .add(min(money(input.parentCareAllowance()), new BigDecimal("120000.00")))
            .add(money(input.disabledCareAllowance()))
            .add(min(money(input.maternityAllowance()), new BigDecimal("60000.00")));

        BigDecimal lifeInsurance = min(money(input.lifeInsuranceAllowance()), new BigDecimal("100000.00"));
        BigDecimal healthInsurance = min(money(input.healthInsuranceAllowance()), new BigDecimal("25000.00"));
        BigDecimal lifeAndHealth = min(lifeInsurance.add(healthInsurance), new BigDecimal("100000.00"));
        BigDecimal parentHealth = min(money(input.parentHealthInsuranceAllowance()), new BigDecimal("15000.00"));

        BigDecimal retirement = retirementAllowance(input, projectedAnnualIncome);
        BigDecimal thaiEsg = min(
            money(input.thaiEsgAllowance()),
            min(percentOf(projectedAnnualIncome, "0.30"), new BigDecimal("300000.00"))
        );
        BigDecimal homeLoan = min(money(input.homeLoanInterestAllowance()), new BigDecimal("100000.00"));
        BigDecimal politicalDonation = min(money(input.politicalDonation()), new BigDecimal("10000.00"));

        BigDecimal nonDonationAllowances = PERSONAL_ALLOWANCE
            .add(projectedSsoAllowance)
            .add(family)
            .add(lifeAndHealth)
            .add(parentHealth)
            .add(retirement)
            .add(thaiEsg)
            .add(homeLoan)
            .add(politicalDonation);

        BigDecimal incomeBeforeDonation = projectedAnnualIncome
            .subtract(taxExpenseDeduction)
            .subtract(nonDonationAllowances)
            .max(ZERO);
        BigDecimal donationCap = percentOf(incomeBeforeDonation, "0.10");
        BigDecimal donationAllowance = min(
            money(input.educationDonation()).multiply(new BigDecimal("2")).add(money(input.generalDonation())),
            donationCap
        );

        return new AllowanceBreakdown(money(nonDonationAllowances.add(donationAllowance)), money(donationAllowance));
    }

    private BigDecimal retirementAllowance(PayrollTaxAllowanceInput input, BigDecimal projectedAnnualIncome) {
        BigDecimal remainingCluster = new BigDecimal("500000.00");
        BigDecimal rmf = min(money(input.rmfAllowance()), min(percentOf(projectedAnnualIncome, "0.30"), new BigDecimal("500000.00")));
        rmf = min(rmf, remainingCluster);
        remainingCluster = remainingCluster.subtract(rmf);

        BigDecimal ssf = min(money(input.ssfAllowance()), min(percentOf(projectedAnnualIncome, "0.30"), new BigDecimal("200000.00")));
        ssf = min(ssf, remainingCluster);
        remainingCluster = remainingCluster.subtract(ssf);

        BigDecimal pension = min(money(input.pensionInsuranceAllowance()), min(percentOf(projectedAnnualIncome, "0.15"), new BigDecimal("200000.00")));
        pension = min(pension, remainingCluster);

        return money(rmf.add(ssf).add(pension));
    }

    private BigDecimal legalExecutionDeduction(
        BigDecimal requested,
        BigDecimal grossTaxableIncome,
        BigDecimal socialSecurity,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal otherPostTaxDeductions
    ) {
        if (requested.signum() <= 0) {
            return ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal capByThirtyPercent = percentOf(grossTaxableIncome, "0.30");
        BigDecimal netBeforeLegal = grossTaxableIncome
            .subtract(socialSecurity)
            .subtract(withholdingTax)
            .subtract(studentLoanDeduction)
            .subtract(otherPostTaxDeductions);
        BigDecimal capByLivingFloor = netBeforeLegal.subtract(MIN_NET_AFTER_LEGAL_EXECUTION).max(ZERO);
        return money(min(requested, min(capByThirtyPercent, capByLivingFloor)));
    }

    BigDecimal progressiveTax(BigDecimal taxableAnnualIncome) {
        BigDecimal income = money(taxableAnnualIncome);
        BigDecimal total = ZERO;
        total = total.add(taxForBracket(income, "150000.00", "300000.00", "0.05"));
        total = total.add(taxForBracket(income, "300000.00", "500000.00", "0.10"));
        total = total.add(taxForBracket(income, "500000.00", "750000.00", "0.15"));
        total = total.add(taxForBracket(income, "750000.00", "1000000.00", "0.20"));
        total = total.add(taxForBracket(income, "1000000.00", "2000000.00", "0.25"));
        total = total.add(taxForBracket(income, "2000000.00", "5000000.00", "0.30"));
        if (income.compareTo(new BigDecimal("5000000.00")) > 0) {
            total = total.add(income.subtract(new BigDecimal("5000000.00")).multiply(new BigDecimal("0.35")));
        }
        return money(total);
    }

    private BigDecimal taxForBracket(BigDecimal income, String lowerText, String upperText, String rateText) {
        BigDecimal lower = new BigDecimal(lowerText);
        BigDecimal upper = new BigDecimal(upperText);
        if (income.compareTo(lower) <= 0) {
            return ZERO;
        }
        return income.min(upper).subtract(lower).multiply(new BigDecimal(rateText));
    }

    private BigDecimal percentOf(BigDecimal value, String rate) {
        return money(value.multiply(new BigDecimal(rate)));
    }

    private BigDecimal min(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? money(left) : money(right);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private record AllowanceBreakdown(BigDecimal total, BigDecimal donation) {}
}
