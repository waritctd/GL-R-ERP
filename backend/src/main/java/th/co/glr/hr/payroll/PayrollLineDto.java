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
    // Reconciliation additions (2026-07-21, C3/C4).
    BigDecimal directorRemuneration,
    BigDecimal warningLetterDeduction,
    BigDecimal customerReturnDeduction,
    BigDecimal otherPretaxDeduction,
    // Cancel-after-close reversal, AUTO-REFUND (2026-07-23). See PayrollCalculation.
    BigDecimal leaveRefundDays,
    BigDecimal leaveDeductionRefund,
    // Withholding-tax override (2026-07-24, V88): the PER-RUN HR-typed override value stored for this
    // line (nullable; null = none typed this run). This is the TYPED per-run value only -- NOT the
    // standing employee override and NOT the resolved effective amount (the effective withheld amount
    // lives in withholdingTax above). Persisted so it carries forward to next month's run.
    BigDecimal withholdingTaxOverride,
    // ป.96/2543 compliance (2026-07-28, V92). The two limbs คำชี้แจง ภ.ง.ด.1 keeps apart --
    // เงินได้ที่จ่ายตามปกติ (ข้อ 2.1) and เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5). Persisted on
    // payroll_line because the year-to-date carry-forward needs each limb separately: next period's
    // ข้อ 2.5 difference is netted against what was withheld on the VARIABLE limb specifically, and
    // that is unrecoverable once the two are summed. regularTaxableIncome + variableTaxableIncome ==
    // grossTaxableIncome, and regularWithholdingTax + variableWithholdingTax == withholdingTax.
    //
    // SUPERSEDED going forward (rebase, 2026-07-29): calculateLine now runs exclusively through
    // calculateClassified (V96, regular/known/cumulative 3-limb -- see the fields below), which has no
    // concept of this older 2-limb split. Kept only so pre-task-2 rows still round-trip through this
    // record's shape; every line processed from this rebase forward backfills these four the same way
    // the 40-arg legacy constructor below already backfills the V96 fields for a pre-limb caller.
    BigDecimal regularTaxableIncome,
    BigDecimal variableTaxableIncome,
    BigDecimal regularWithholdingTax,
    BigDecimal variableWithholdingTax,
    // Dedicated one-off pay (2026-07-29, V94/V96 -- both branches added this column; declared once).
    BigDecimal bonusPay,
    BigDecimal otherOneOffPay,
    // Over-withholding this tax year that payroll cannot hand back (2026-07-29, V94). Computed by
    // PayrollCalculator and surfaced all the way to the payslip: a stranded excess otherwise shows up
    // only as a 0.00 withholding line, which is indistinguishable from "nothing was due". NOT computed
    // by calculateClassified (known gap -- see the rebase report); zero on every classified-engine line.
    BigDecimal excessWithheldToDate,
    // ---- Task 2 additions (2026-07-29): per-component classified withholding engine. See
    // PayrollCalculator#calculateClassified and docs/agent-handoffs/118_feat-payroll-classification-
    // and-hr-declarations.md.
    // Per-limb taxable-income / withholding-tax breakdown, persisted so the NEXT period's year-to-date
    // projection can be reconstructed per ป.96 limb rather than as one blended total (V96,
    // PayrollRepository#findYearToDateByEmployee). taxableIncomeKnownLimb is audit-only -- it is never
    // read back into a future period's projection (see PayrollYearToDate's javadoc).
    BigDecimal taxableIncomeRegularLimb,
    BigDecimal taxableIncomeKnownLimb,
    BigDecimal taxableIncomeCumulativeLimb,
    BigDecimal withholdingTaxRegularLimb,
    BigDecimal withholdingTaxCumulativeLimb,
    // ลูกค้าคืนสินค้า earned/unearned flag (handoff section 6): true = already earned and paid, so
    // customerReturnDeduction above is a POST-TAX clawback that does not reduce taxable income; false
    // (default) = not yet earned, already netted OUT of commissionPay pre-tax by the service layer, so
    // customerReturnDeduction is 0 in that case and this flag is purely a record of which treatment
    // applied.
    boolean customerReturnAlreadyEarned,
    // PayrollGarnishmentType name (handoff section 7). Nullable in memory; persisted with the V96
    // column default 'SALARY' when null, which reproduces the pre-existing single 30%-plus-฿20,000-
    // floor rule exactly.
    String garnishmentType,
    // ---- Defect fix (Opus review, 2026-07-29): V97's four columns were write-only -- read from the
    // request, folded into tax/SSO arithmetic, then discarded. Appended last so every prior
    // positional call site keeps compiling via the legacy constructor below. See PayrollCalculator
    // #calculateClassified (mealAllowance/perDiemTaxable now echoed onto PayrollClassifiedCalculation)
    // and PayrollService#calculateLine (perDiemExempt/perDiemBasis passed straight through from the
    // request -- they are never part of the calculator's componentAmounts, so there is nothing for
    // the calculator to echo back for those two).
    BigDecimal mealAllowance,
    BigDecimal perDiemExempt,
    BigDecimal perDiemTaxable,
    // PerDiemBasis name, or null when no per-diem was paid this line (matches the DB CHECK: a basis
    // is required only when perDiemExempt/perDiemTaxable are non-zero).
    String perDiemBasis
) {
    /**
     * Legacy 49-arg constructor: the full signature as it stood right before the V97
     * meal-allowance/per-diem fields existed (i.e. through {@code garnishmentType}). Keeps every
     * 49-arg positional call site compiling unchanged (including the 40-arg legacy constructor below,
     * which terminates here); the four new V97 fields default to zero/null (no meal allowance or
     * per-diem on the line). The V92 two-limb fields (added in parallel by branch 117, unknown at this
     * arity) default the same way the 40-arg legacy constructor below does -- everything attributed to
     * the regular limb, nothing to the variable limb.
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
            // V92 two-limb fields (branch 117, unknown at this arity): backfilled the same way as the
            // 40-arg legacy constructor below -- everything regular, nothing variable.
            grossTaxableIncome == null ? BigDecimal.ZERO : grossTaxableIncome, BigDecimal.ZERO,
            withholdingTax == null ? BigDecimal.ZERO : withholdingTax, BigDecimal.ZERO,
            bonusPay, otherOneOffPay, BigDecimal.ZERO,
            taxableIncomeRegularLimb, taxableIncomeKnownLimb, taxableIncomeCumulativeLimb,
            withholdingTaxRegularLimb, withholdingTaxCumulativeLimb,
            customerReturnAlreadyEarned, garnishmentType,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null
        );
    }

    /**
     * Legacy 37-arg constructor: the full signature as it stood right before {@code
     * leaveRefundDays}/{@code leaveDeductionRefund} existed. Several test files (including {@code
     * PayrollServiceTest} and {@code PayrollRepositoryIntegrationTest}, which must not be edited)
     * construct this record positionally at that arity; both new fields default to zero, which is a
     * no-op on every downstream figure (see {@link PayrollCalculator}).
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

    /**
     * Legacy 39-arg constructor: the full signature as it stood right before {@code
     * withholdingTaxOverride} existed (i.e. through {@code leaveDeductionRefund}). Keeps every 39-arg
     * positional call site compiling unchanged; {@code withholdingTaxOverride} defaults to {@code null}
     * ("no per-run override typed"), a no-op on every stored/displayed figure.
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

    /**
     * Legacy 40-arg constructor: the full signature as it stood right before the task-2 classified-
     * engine fields existed (i.e. through {@code withholdingTaxOverride}). Keeps every 40-arg
     * positional call site compiling unchanged -- including the two legacy constructors above, which
     * both terminate here.
     *
     * <p>{@code taxableIncomeRegularLimb}/{@code withholdingTaxRegularLimb} default to {@code
     * grossTaxableIncome}/{@code withholdingTax} -- NOT zero -- mirroring V96's one-time backfill of
     * every pre-existing processed row (a caller using this constructor is, by definition, describing
     * a line with no concept of limbs, i.e. 100% regular limb, exactly like every payroll_line row
     * that predates task 2). Defaulting these to zero instead would silently erase that history from
     * next period's year-to-date reprojection (see PayrollRepository#findYearToDateByEmployee) --
     * this bit a real test (PayrollYtdAndSsoIntegrationTest, which hand-crafts a prior processed line
     * via this constructor and asserts the next period's withholding against it) before it was fixed
     * here. {@code taxableIncomeKnownLimb}/{@code taxableIncomeCumulativeLimb}/{@code
     * withholdingTaxCumulativeLimb} stay zero correctly -- those limbs genuinely did not exist before
     * task 2.
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

    /**
     * Legacy 47-arg constructor: branch 117's own full canonical signature (V92 two-limb split +
     * V94 bonus/one-off pay + excessWithheldToDate), before the task-2 three-limb fields existed.
     * Kept so pre-merge branch-117 call sites that construct the two-limb fields explicitly --
     * including {@code PayslipRendererTest}/{@code PayslipMaximalLayoutReviewTest}/{@code
     * PayslipProvisionalNoticeGeometryReviewTest} -- still compile.
     *
     * <p>The task-2-only fields are backfilled from these two-limb figures the same way the 40-arg
     * legacy constructor above already backfills them from grossTaxableIncome/withholdingTax: the
     * regular limb takes the caller's regular-limb figures, the known/cumulative limbs stay zero
     * (this caller has no concept of them), and the V97 meal/per-diem/garnishment/customer-return
     * fields default the same as every other legacy arity in this file.
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

    /**
     * Legacy 53-arg constructor: branch 118's own original full canonical signature (V96 three-limb
     * split + V97 meal/per-diem + garnishment/customer-return, no V92 two-limb fields at all), before
     * the rebase merged branch 117's V92 fields in. Kept so pre-merge task-2 call sites that construct
     * the FULL V96/V97 tail explicitly -- including {@code PayrollCarryForwardSuggestionsIntegrationTest}
     * and {@code PayrollMealAndPerDiemIntegrationTest} -- still compile.
     *
     * <p>The V92 two-limb fields (unknown to this arity) are backfilled from grossTaxableIncome /
     * withholdingTax, the same way the 40-arg legacy constructor above backfills them.
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
