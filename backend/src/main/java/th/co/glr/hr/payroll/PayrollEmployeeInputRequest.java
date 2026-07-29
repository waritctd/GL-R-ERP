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
    // พิเศษ 9 (2026-07-29, V95) -- originally appended as ค่าเช่าบ้าน. F7 correction (Opus review,
    // 2026-07-30): the accountant's-workbook renumbering (handoff section 9d, later the same day)
    // moved ค่าเช่าบ้าน to specialPay2 instead and specialPay9 is now เงินรางวัล/เงินช่วยเหลืออื่นๆ --
    // see PayrollComponent's javadoc for the CURRENT, authoritative slot -> label mapping.
    @PositiveOrZero BigDecimal specialPay9,
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
    @PositiveOrZero BigDecimal withholdingTaxOverride,
    // ---- Task 2 additions (2026-07-29). See docs/agent-handoffs/118_feat-payroll-classification-
    // and-hr-declarations.md.
    // เงินโบนัส / อื่นๆ (V96 payroll_line columns). commissionPay keeps its automatic CommissionService
    // feed (handoff section 10, "do NOT add an HR-typed commission field") -- these two are the
    // one-off fields HR types explicitly, per employee, per run.
    // ค่าอาหาร and เบี้ยเลี้ยง (ตจว/ตปท) -- V97. Real columns in the accountant's ledger (2026.xlsx
    // cols K and P) that this system had no field for until 2026-07-29. HR-typed per run.
    @PositiveOrZero BigDecimal mealAllowance,
    // เบี้ยเลี้ยง split per มาตรา 42 -- exemption is PARTIAL, so a single amount plus a flag cannot
    // express it. HR makes the split against the rate that applied; the system cannot derive it
    // without knowing destination and employee grade.
    @PositiveOrZero BigDecimal perDiemExempt,
    @PositiveOrZero BigDecimal perDiemTaxable,
    // Which limb applied. Required whenever any per-diem is paid -- the two carry different evidence
    // obligations, and an unattributable tax position is one nobody can defend later.
    PerDiemBasis perDiemBasis,
    // Dedicated one-off pay (2026-07-29, V94/V96 -- both branches added this pair; declared once).
    // Both are ข้อ 2.5 เงินพิเศษ -- see PayrollCalculator.
    @PositiveOrZero BigDecimal bonusPay,
    @PositiveOrZero BigDecimal otherOneOffPay,
    // ลูกค้าคืนสินค้า earned/unearned flag (handoff section 6). false (default) = not yet earned, so
    // customerReturnDeduction reduces the commission earning pre-tax; true = already earned and paid,
    // so customerReturnDeduction is applied as a post-tax clawback instead.
    Boolean customerReturnAlreadyEarned,
    // Garnishment payment type (handoff section 7). Nullable: null defaults to SALARY, reproducing
    // the pre-existing single 30%-plus-฿20,000-floor rule exactly.
    PayrollGarnishmentType garnishmentType,
    // Parent allowance per qualifying head, this run's in-run correction (handoff section 4).
    // Nullable: null falls back to the stored standing declaration, same per-field-override pattern
    // as every other allowance field (see PayrollService#mergeAllowances).
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(4) Integer parentCareCount
) {
    /**
     * Legacy constructor: the signature before EITHER พิเศษ 9 (V95) or the dedicated one-off pay
     * fields (V94/task 2) existed -- branch 117's oldest call sites. specialPay9 defaults to zero
     * (an empty slot contributes nothing); every task-2 field defaults the same way the newer legacy
     * constructor below already does.
     */
    public PayrollEmployeeInputRequest(
        Long employeeId,
        BigDecimal specialPay1, BigDecimal specialPay2, BigDecimal specialPay3, BigDecimal specialPay4,
        BigDecimal specialPay5, BigDecimal specialPay6, BigDecimal specialPay7, BigDecimal specialPay8,
        BigDecimal nonTaxableIncome, BigDecimal unpaidLeaveDays, BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction, BigDecimal otherPostTaxDeductions,
        BigDecimal spouseAllowance, BigDecimal childAllowance, BigDecimal parentCareAllowance,
        BigDecimal disabledCareAllowance, BigDecimal maternityAllowance,
        BigDecimal lifeInsuranceAllowance, BigDecimal healthInsuranceAllowance,
        BigDecimal parentHealthInsuranceAllowance, BigDecimal rmfAllowance, BigDecimal ssfAllowance,
        BigDecimal pensionInsuranceAllowance, BigDecimal thaiEsgAllowance,
        BigDecimal homeLoanInterestAllowance, BigDecimal educationDonation,
        BigDecimal generalDonation, BigDecimal politicalDonation,
        BigDecimal warningLetterDeduction, BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction, BigDecimal withholdingTaxOverride
    ) {
        this(employeeId, specialPay1, specialPay2, specialPay3, specialPay4, specialPay5, specialPay6,
            specialPay7, specialPay8, BigDecimal.ZERO, nonTaxableIncome, unpaidLeaveDays, studentLoanDeduction,
            legalExecutionDeduction, otherPostTaxDeductions, spouseAllowance, childAllowance,
            parentCareAllowance, disabledCareAllowance, maternityAllowance, lifeInsuranceAllowance,
            healthInsuranceAllowance, parentHealthInsuranceAllowance, rmfAllowance, ssfAllowance,
            pensionInsuranceAllowance, thaiEsgAllowance, homeLoanInterestAllowance, educationDonation,
            generalDonation, politicalDonation, warningLetterDeduction, customerReturnDeduction,
            otherPretaxDeduction, withholdingTaxOverride,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
            BigDecimal.ZERO, BigDecimal.ZERO, false, null, null
        );
    }

    /**
     * Legacy constructor, kept so every call site written before the task-2 fields existed still
     * compiles (mealAllowance/perDiemAllowance/bonusPay/otherOneOffPay default to zero,
     * customerReturnAlreadyEarned defaults false,
     * garnishmentType/parentCareCount default null -- "not typed this run", falling back to SALARY /
     * the stored standing declaration respectively).
     */
    public PayrollEmployeeInputRequest(
        Long employeeId,
        BigDecimal specialPay1,
        BigDecimal specialPay2,
        BigDecimal specialPay3,
        BigDecimal specialPay4,
        BigDecimal specialPay5,
        BigDecimal specialPay6,
        BigDecimal specialPay7,
        BigDecimal specialPay8,
        BigDecimal specialPay9,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
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
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal withholdingTaxOverride
    ) {
        this(
            employeeId, specialPay1, specialPay2, specialPay3, specialPay4, specialPay5, specialPay6,
            specialPay7, specialPay8, specialPay9, nonTaxableIncome, unpaidLeaveDays, studentLoanDeduction,
            legalExecutionDeduction, otherPostTaxDeductions, spouseAllowance, childAllowance,
            parentCareAllowance, disabledCareAllowance, maternityAllowance, lifeInsuranceAllowance,
            healthInsuranceAllowance, parentHealthInsuranceAllowance, rmfAllowance, ssfAllowance,
            pensionInsuranceAllowance, thaiEsgAllowance, homeLoanInterestAllowance, educationDonation,
            generalDonation, politicalDonation, warningLetterDeduction, customerReturnDeduction,
            otherPretaxDeduction, withholdingTaxOverride,
            // mealAllowance, perDiemAllowance (V97), then bonusPay, otherOneOffPay -- all default to
            // zero at this legacy arity, which is a no-op on every downstream figure.
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
            BigDecimal.ZERO, BigDecimal.ZERO, false, null, null
        );
    }

    public List<BigDecimal> specialPays() {
        return List.of(
            safe(specialPay1),
            safe(specialPay2),
            safe(specialPay3),
            safe(specialPay4),
            safe(specialPay5),
            safe(specialPay6),
            safe(specialPay7),
            safe(specialPay8),
            safe(specialPay9)
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
