package th.co.glr.hr.payroll;

import java.math.BigDecimal;

/**
 * Year-to-date carry-forward feeding {@link PayrollCalculator}'s annual projection.
 *
 * <p>ป.96/2543 compliance: income and withholding are carried as TWO limbs, because
 * คำชี้แจง แบบ ภ.ง.ด.1 treats them under different clauses and they cannot be recombined afterwards:
 *
 * <ul>
 *   <li><b>regular</b> — เงินได้ที่จ่ายตามปกติ (ข้อ 2.1): annualised by multiplying by
 *       จำนวนคราวที่ต้องจ่าย. Base salary, <b>พิเศษ 7 (คอมมิชชั่น)</b> and the commission feed, and
 *       ค่าตอบแทนกรรมการ — the items paid every คราว, whatever the amount.</li>
 *   <li><b>variable</b> — เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5): ค่าล่วงเวลา, <b>พิเศษ 1-6, 8, 9</b>,
 *       and the dedicated bonus / อื่นๆ fields. Taken at its ACTUAL cumulative amount and never
 *       multiplied out, then taxed as the DIFFERENCE between the tax with it and the tax without it.</li>
 * </ul>
 *
 * <p>The slot numbers above follow the accountant's workbook numbering: คอมมิชชั่น is พิเศษ 7 (พิเศษ
 * 2, ค่าเช่าบ้าน, sits ahead of it). See {@code PayrollCalculator}'s {@code
 * COMMISSION_SPECIAL_PAY_INDEX} for the same numbering applied to the index used by {@code
 * calculate()}.
 *
 * <p>The split is owner-stated, not inferred from the slot labels (พิเศษ 1-5 are NOT standing
 * allowances). See {@code PayrollCalculator}'s
 * {@code COMMISSION_SPECIAL_PAY_INDEX} for the owner's account and why the classification is not a
 * contiguous slot range.
 *
 * <p>Withholding is split the same way so the ข้อ 2.5 difference can be netted against what has
 * already been withheld on the variable limb specifically. Summing the two before storing would make
 * next month's variable-limb arithmetic unrecoverable.
 */
public record PayrollYearToDate(
    BigDecimal regularIncome,
    BigDecimal variableIncome,
    BigDecimal socialSecurity,
    BigDecimal regularWithholdingTax,
    BigDecimal variableWithholdingTax,
    // Per-limb breakdown: withholdingTax below stays the TOTAL across all three
    // ป.96 limbs (still used by callers that only need the aggregate). The classified engine
    // (PayrollCalculator#calculateClassified) needs the REGULAR and CUMULATIVE limbs' own prior
    // contributions separately -- ข้อ 1(4)'s reprojection reads regularLimbTaxableIncome /
    // regularLimbWithholdingTax, and ข้อ 1(6)'s "less tax already withheld" reads
    // cumulativeLimbTaxableIncome / cumulativeLimbWithholdingTax. The known limb (ข้อ 1(5)) is
    // deliberately NOT carried here -- each known-frequency payment is taxed as its own period's
    // marginal difference and never reprojected forward, so it needs no YTD state.
    //
    // The regular/variable pair above is superseded: calculateLine runs exclusively through
    // calculateClassified, which reads the *Limb fields below, not
    // regularIncome/variableIncome/regularWithholdingTax/variableWithholdingTax. Those four (and the
    // legacy calculate() method that still reads them) exist only for
    // PayrollExcelReconciliationTest's frozen regression suite and other calculate()-only callers.
    BigDecimal withholdingTax,
    BigDecimal regularLimbTaxableIncome,
    BigDecimal regularLimbWithholdingTax,
    BigDecimal cumulativeLimbTaxableIncome,
    BigDecimal cumulativeLimbWithholdingTax,
    // A settled EXTRA_KNOWN_FREQUENCY payment (ข้อ 1(5), e.g. a
    // bonus) is taxed and fully withheld in the period it is paid and is deliberately NOT reprojected
    // -- but it is still real income that must stay part of the running total the CUMULATIVE limb is
    // layered on top of in every LATER period. Without this field, PayrollCalculator#calculateClassified
    // could not know a known-limb payment had already happened this year once its own period ended,
    // so the cumulative limb's marginal tax would drop it from the bracket position -- the KNOWN
    // and CUMULATIVE limbs would consume the same tax brackets twice and the year under-withhold. This
    // is the sum of every period's own taxable_income_known_limb (payroll_line), i.e. the running total
    // of known-limb income SETTLED so far this year -- not reprojected, just accumulated.
    BigDecimal knownLimbTaxableIncome
) {
    public static PayrollYearToDate empty() {
        return new PayrollYearToDate(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    /** Legacy 5-arg constructor (regularIncome, variableIncome, socialSecurity, regularWithholdingTax,
     * variableWithholdingTax), kept so every call site written before task 2's three-limb breakdown (including
     * PayrollCalculatorTest and the other PayrollCalculator#calculate-only review tests) still compiles. */
    public PayrollYearToDate(
        BigDecimal regularIncome,
        BigDecimal variableIncome,
        BigDecimal socialSecurity,
        BigDecimal regularWithholdingTax,
        BigDecimal variableWithholdingTax
    ) {
        this(
            regularIncome, variableIncome, socialSecurity, regularWithholdingTax, variableWithholdingTax,
            nz(regularWithholdingTax).add(nz(variableWithholdingTax)),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    /** Legacy 3-arg constructor (taxableIncome, socialSecurity, withholdingTax), kept so every call site written
     * before EITHER limb split existed still compiles. */
    public PayrollYearToDate(
        BigDecimal taxableIncome,
        BigDecimal socialSecurity,
        BigDecimal withholdingTax
    ) {
        this(
            taxableIncome, BigDecimal.ZERO, socialSecurity, withholdingTax, BigDecimal.ZERO,
            withholdingTax, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    /** Total year-to-date taxable income across the two calculate()-only limbs. */
    public BigDecimal taxableIncome() {
        return nz(regularIncome).add(nz(variableIncome));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
