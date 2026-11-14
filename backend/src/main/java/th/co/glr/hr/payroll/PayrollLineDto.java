package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;

public record PayrollLineDto(
    Long id,
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    String bankName,
    String bankAccount,
    BigDecimal baseSalary,
    BigDecimal dailyRate,
    BigDecimal hourlyRate,
    List<PayrollSpecialPayDto> specialPays,
    BigDecimal specialPayTotal,
    BigDecimal overtimePay,
    BigDecimal commissionPay,
    BigDecimal grossEarnings,
    BigDecimal nonTaxableIncome,
    BigDecimal unpaidLeaveDays,
    BigDecimal unpaidLeaveDeduction,
    BigDecimal grossTaxableIncome,
    BigDecimal ssoWageBase,
    BigDecimal socialSecurity,
    BigDecimal projectedAnnualIncome,
    BigDecimal taxExpenseDeduction,
    BigDecimal taxAllowanceTotal,
    BigDecimal taxableAnnualIncome,
    BigDecimal annualTax,
    BigDecimal withholdingTax,
    BigDecimal studentLoanDeduction,
    BigDecimal legalExecutionDeduction,
    BigDecimal otherPostTaxDeductions,
    BigDecimal totalDeductions,
    BigDecimal netPay,
    String calculationNote,
    // Reconciliation additions.
    BigDecimal directorRemuneration,
    BigDecimal warningLetterDeduction,
    BigDecimal customerReturnDeduction,
    BigDecimal otherPretaxDeduction,
    // Cancel-after-close reversal, AUTO-REFUND. See PayrollCalculation.
    BigDecimal leaveRefundDays,
    BigDecimal leaveDeductionRefund,
    // Withholding-tax override (V88): the PER-RUN HR-typed override value stored for this
    // line (nullable; null = none typed this run). This is the TYPED per-run value only -- NOT the
    // standing employee override and NOT the resolved effective amount (the effective withheld amount
    // lives in withholdingTax above). Persisted so it carries forward to next month's run.
    BigDecimal withholdingTaxOverride,
    // ป.96/2543 compliance (V92). The two limbs คำชี้แจง ภ.ง.ด.1 keeps apart --
    // เงินได้ที่จ่ายตามปกติ (ข้อ 2.1) and เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5). Persisted on
    // payroll_line because the year-to-date carry-forward needs each limb separately: next period's
    // ข้อ 2.5 difference is netted against what was withheld on the VARIABLE limb specifically, and
    // that is unrecoverable once the two are summed. regularTaxableIncome + variableTaxableIncome ==
    // grossTaxableIncome, and regularWithholdingTax + variableWithholdingTax == withholdingTax.
    //
    // SUPERSEDED going forward: calculateLine now runs exclusively through calculateClassified (V96,
    // regular/known/cumulative 3-limb -- see the fields below), which has no concept of this older
    // 2-limb split. Kept only so older rows still round-trip through this record's shape; every line
    // processed now backfills these four the same way the 40-arg legacy constructor below backfills
    // the V96 fields for a pre-limb caller.
    BigDecimal regularTaxableIncome,
    BigDecimal variableTaxableIncome,
    BigDecimal regularWithholdingTax,
    BigDecimal variableWithholdingTax,
    // Dedicated one-off pay (V94/V96).
    BigDecimal bonusPay,
    BigDecimal otherOneOffPay,
    // Over-withholding this tax year that payroll cannot hand back (V94). Computed by
    // PayrollCalculator and surfaced all the way to the payslip: a stranded excess otherwise shows up
    // only as a 0.00 withholding line, which is indistinguishable from "nothing was due". NOT computed
    // by calculateClassified (known gap); zero on every classified-engine line.
    BigDecimal excessWithheldToDate,
    // Per-component classified withholding engine (see PayrollCalculator#calculateClassified).
    // Per-limb taxable-income / withholding-tax breakdown, persisted so the NEXT period's year-to-date
    // projection can be reconstructed per ป.96 limb rather than as one blended total (V96,
    // PayrollRepository#findYearToDateByEmployee). taxableIncomeKnownLimb is audit-only -- it is never
    // read back into a future period's projection (see PayrollYearToDate's javadoc).
    BigDecimal taxableIncomeRegularLimb,
    BigDecimal taxableIncomeKnownLimb,
    BigDecimal taxableIncomeCumulativeLimb,
    BigDecimal withholdingTaxRegularLimb,
    BigDecimal withholdingTaxCumulativeLimb,
    // Earned customer returns are post-tax clawbacks; unearned returns are netted from commission before tax.
    boolean customerReturnAlreadyEarned,
    // PayrollGarnishmentType name (handoff section 7). Nullable in memory; persisted with the V96 column default
    // 'SALARY' when null, which reproduces the pre-existing single 30%-plus-฿20,000- floor rule exactly.
    String garnishmentType,
    // V97's four columns (mealAllowance/perDiem*) are echoed back rather than dropped after the tax/SSO
    // arithmetic. Appended last so every prior positional call site keeps compiling via the legacy
    // constructor below. See PayrollCalculator#calculateClassified (mealAllowance/perDiemTaxable
    // echoed onto PayrollClassifiedCalculation) and PayrollService#calculateLine
    // (perDiemExempt/perDiemBasis passed straight through from the request -- they are never part of
    // the calculator's componentAmounts, so there is nothing for the calculator to echo back for
    // those two).
    BigDecimal mealAllowance,
    BigDecimal perDiemExempt,
    BigDecimal perDiemTaxable,
    // PerDiemBasis name, or null when no per-diem was paid this line (matches the DB CHECK: a basis is required
    // only when perDiemExempt/perDiemTaxable are non-zero).
    String perDiemBasis,
    // What HR actually TYPED this run, verbatim, regardless of customerReturnAlreadyEarned --
    // distinct from customerReturnDeduction above, which is the POST-TAX bookkeeping figure (0 in
    // the unearned path, where the amount is instead netted pre-tax out of commissionPay, per
    // PayrollCalculator's own comment on that field). Without this field, customerReturnDeduction
    // would be the ONLY place the entered amount could round-trip from, so in the unearned path it
    // would read back as 0 on reload, silently wiping the form field AND the checkbox that gates on
    // it being > 0 (PayrollPage.jsx), and a reprocess of the same month would silently stop
    // re-applying the pre-tax netting. Appended last so every prior positional call site keeps
    // compiling via the legacy constructor immediately below.
    BigDecimal customerReturnRequested,
    // Daily-rate support. daysWorked is the raw HR-typed figure for this line (nullable
    // -- distinct from zero, same reasoning as withholdingTaxOverride -- so a monthly employee's line,
    // which never has this field, round-trips as null rather than a misleading 0). payType is the
    // employee's hr.employee.pay_type code ("M"/"D"/null) AS OF THIS RUN, echoed straight from
    // PayrollEmployeeSnapshot -- not persisted on hr.payroll_line (re-read live via the employee join
    // in PayrollRepository#findLines/#findActiveEmployees), so it reflects the employee's CURRENT
    // pay_type rather than a frozen-at-processing-time snapshot. Both exist solely so the frontend can
    // gate the "days worked" input to daily-rate employees and so the value survives a reload -- see
    // PayrollPage.jsx.
    BigDecimal daysWorked,
    String payType,
    // Uncapped SSO wage base: PayrollCalculator#calculateClassified's own ssoWageBaseRaw, computed but previously
    // discarded -- ssoWageBase above is that same figure clamped to [1,650, 17,500], which is what
    // payroll_line.sso_wage_base has always stored and what tax/contribution math still uses.
    BigDecimal ssoWageGross,
    // The ป.วิ.พ. ม.302 "requested" figure -- the monthly amount actually fed into
    // PayrollCalculator as garnishmentRequested THIS run (DeductionObligationService
    // #resolveMonthlyAmount's return, obligation-clamp already applied if one exists). Carried out
    // of PayrollService#calculateLine rather than re-derived later, specifically so {@code
    // DeductionObligationService#recordGarnishmentShortfalls} never calls #resolveMonthlyAmount a
    // SECOND time. A second call is unsafe: #recordRemittances (called earlier in the same
    // #process()) may have already flipped an obligation's status (ACTIVE -> COMPLETED or back), and
    // #findDrivingObligation's ORDER BY (status='ACTIVE') DESC means a second resolve can pick a
    // DIFFERENT obligation than the one #calculateLine actually used -- fabricating a shortfall
    // "requested" figure the court order never asked for. This field is the exact number
    // #calculateLine saw, with no re-derivation possible. Intentionally NOT persisted to
    // hr.payroll_line (no column, no INSERT/SELECT reference) -- it is consumed once, in the same
    // #process() transaction, from the same in-memory PayrollLineDto list #preview() already built.
    BigDecimal legalExecutionRequested,
    // V128: approved สวัสดิการ pulled in for this payroll month. Appended last, following the same rule every
    // field above it followed -- every existing positional call site keeps compiling through the legacy
    // constructors below, which pass null here.
    BigDecimal welfarePay
) {
    /**
     * Legacy 61-arg constructor: the full signature before {@code legalExecutionRequested}
     * existed, i.e. through {@code payType}. Keeps every 61-arg positional call site compiling
     * unchanged; {@code legalExecutionRequested} defaults to {@code null} (no shortfall can be
     * computed for a line built this way -- true for every existing call site, none of which ever
     * fed this value to {@code recordGarnishmentShortfalls}).
     */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal regularTaxableIncome,
        BigDecimal variableTaxableIncome,
        BigDecimal regularWithholdingTax,
        BigDecimal variableWithholdingTax,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal excessWithheldToDate,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        boolean customerReturnAlreadyEarned,
        String garnishmentType,
        BigDecimal mealAllowance,
        BigDecimal perDiemExempt,
        BigDecimal perDiemTaxable,
        String perDiemBasis,
        BigDecimal customerReturnRequested,
        BigDecimal daysWorked,
        String payType
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            regularTaxableIncome, variableTaxableIncome, regularWithholdingTax, variableWithholdingTax,
            bonusPay, otherOneOffPay, excessWithheldToDate,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            mealAllowance, perDiemExempt, perDiemTaxable, perDiemBasis,
            customerReturnRequested, daysWorked, payType,
            // legalExecutionRequested: never derivable from this arity (see that field's own javadoc). welfarePay: V128
            // postdates this arity too -- no fixture at this size knows about welfare, and null reads as "not recorded"
            // rather than a fabricated ฿0.
            null, null, null
        );
    }

    /** As the constructor above, plus V128's welfarePay. */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal regularTaxableIncome,
        BigDecimal variableTaxableIncome,
        BigDecimal regularWithholdingTax,
        BigDecimal variableWithholdingTax,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal excessWithheldToDate,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        boolean customerReturnAlreadyEarned,
        String garnishmentType,
        BigDecimal mealAllowance,
        BigDecimal perDiemExempt,
        BigDecimal perDiemTaxable,
        String perDiemBasis,
        BigDecimal customerReturnRequested,
        BigDecimal daysWorked,
        String payType,
        BigDecimal welfarePay
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            regularTaxableIncome, variableTaxableIncome, regularWithholdingTax, variableWithholdingTax,
            bonusPay, otherOneOffPay, excessWithheldToDate,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            mealAllowance, perDiemExempt, perDiemTaxable, perDiemBasis,
            customerReturnRequested, daysWorked, payType,
            // legalExecutionRequested: never derivable from this arity (see that field's own javadoc). welfarePay: V128
            // postdates this arity too -- no fixture at this size knows about welfare, and null reads as "not recorded"
            // rather than a fabricated ฿0.
            null, null, welfarePay
        );
    }

    /**
     * Legacy 59-arg constructor: the full signature before {@code daysWorked}/{@code payType}
     * existed, i.e. through {@code customerReturnRequested}. Keeps every 59-arg positional call
     * site compiling unchanged; {@code daysWorked}/{@code payType} default to {@code null} (no
     * days-worked figure, pay type unknown to this caller -- a no-op for every call site that
     * predates either concept).
     */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal regularTaxableIncome,
        BigDecimal variableTaxableIncome,
        BigDecimal regularWithholdingTax,
        BigDecimal variableWithholdingTax,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal excessWithheldToDate,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        boolean customerReturnAlreadyEarned,
        String garnishmentType,
        BigDecimal mealAllowance,
        BigDecimal perDiemExempt,
        BigDecimal perDiemTaxable,
        String perDiemBasis,
        BigDecimal customerReturnRequested
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            regularTaxableIncome, variableTaxableIncome, regularWithholdingTax, variableWithholdingTax,
            bonusPay, otherOneOffPay, excessWithheldToDate,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            mealAllowance, perDiemExempt, perDiemTaxable, perDiemBasis,
            customerReturnRequested, null, null
        );
    }

    /**
     * Legacy 58-arg constructor: the full signature before {@code customerReturnRequested}
     * existed (i.e. through {@code perDiemBasis}). Keeps every 58-arg positional call site
     * compiling unchanged. Defaults the new field to whatever {@code customerReturnDeduction} was
     * given -- the best available reconstruction for a caller that predates the distinction
     * (correct for the already-earned path, where the two always agreed anyway; a reasonable
     * approximation for the unearned path, where the true entered figure was never separately
     * available at this arity either).
     */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal regularTaxableIncome,
        BigDecimal variableTaxableIncome,
        BigDecimal regularWithholdingTax,
        BigDecimal variableWithholdingTax,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal excessWithheldToDate,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        boolean customerReturnAlreadyEarned,
        String garnishmentType,
        BigDecimal mealAllowance,
        BigDecimal perDiemExempt,
        BigDecimal perDiemTaxable,
        String perDiemBasis
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            regularTaxableIncome, variableTaxableIncome, regularWithholdingTax, variableWithholdingTax,
            bonusPay, otherOneOffPay, excessWithheldToDate,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            mealAllowance, perDiemExempt, perDiemTaxable, perDiemBasis,
            customerReturnDeduction == null ? BigDecimal.ZERO : customerReturnDeduction
        );
    }

    /** Legacy 49-arg constructor: the full signature as it stood right before the V97 meal-allowance/per-diem
     * fields existed (i.e. through garnishmentType). */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        boolean customerReturnAlreadyEarned,
        String garnishmentType
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            // V92 two-limb fields (branch 117, unknown at this arity): backfilled the same way as the 40-arg legacy
            // constructor below -- everything regular, nothing variable.
            grossTaxableIncome == null ? BigDecimal.ZERO : grossTaxableIncome, BigDecimal.ZERO,
            withholdingTax == null ? BigDecimal.ZERO : withholdingTax, BigDecimal.ZERO,
            bonusPay, otherOneOffPay, BigDecimal.ZERO,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null
        );
    }

    /** Legacy 37-arg constructor: the full signature as it stood right before leaveRefundDays/leaveDeductionRefund
     * existed. */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            BigDecimal.ZERO, BigDecimal.ZERO, null
        );
    }

    /** Legacy 39-arg constructor: the full signature as it stood right before withholdingTaxOverride existed (i.e.
     * through leaveDeductionRefund). */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, null
        );
    }

    /** Legacy 40-arg constructor: the full signature as it stood right before the task-2 classified- engine fields
     * existed (i.e. through withholdingTaxOverride). Keeps every 40-arg positional call site compiling unchanged
     * -- including the two legacy constructors above, which both terminate here. */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            BigDecimal.ZERO, BigDecimal.ZERO,
            grossTaxableIncome, BigDecimal.ZERO, BigDecimal.ZERO, withholdingTax, BigDecimal.ZERO,
            false, null
        );
    }

    /** Legacy 47-arg constructor: branch 117's own full canonical signature (V92 two-limb split + V94 bonus/one-off
     * pay + excessWithheldToDate), before the task-2 three-limb fields existed. */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal regularTaxableIncome,
        BigDecimal variableTaxableIncome,
        BigDecimal regularWithholdingTax,
        BigDecimal variableWithholdingTax,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal excessWithheldToDate
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            regularTaxableIncome, variableTaxableIncome, regularWithholdingTax, variableWithholdingTax,
            bonusPay, otherOneOffPay, excessWithheldToDate,
            regularTaxableIncome, BigDecimal.ZERO, BigDecimal.ZERO,
            regularWithholdingTax, BigDecimal.ZERO,
            false, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null
        );
    }

    /** Legacy 53-arg constructor: branch 118's own original full canonical signature (V96 three-limb split + V97
     * meal/per-diem + garnishment/customer-return, no V92 two-limb fields at all), before the rebase merged branch
     * 117's V92 fields in. */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        boolean customerReturnAlreadyEarned,
        String garnishmentType,
        BigDecimal mealAllowance,
        BigDecimal perDiemExempt,
        BigDecimal perDiemTaxable,
        String perDiemBasis
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            grossTaxableIncome == null ? BigDecimal.ZERO : grossTaxableIncome, BigDecimal.ZERO,
            withholdingTax == null ? BigDecimal.ZERO : withholdingTax, BigDecimal.ZERO,
            bonusPay, otherOneOffPay, BigDecimal.ZERO,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            mealAllowance, perDiemExempt, perDiemTaxable, perDiemBasis
        );
    }
}
