package th.co.glr.hr.payroll;

import java.math.BigDecimal;

/**
 * Year-to-date carry-forward feeding {@link PayrollCalculator}'s annual projection.
 *
 * <p>ป.96/2543 compliance (2026-07-28): income and withholding are now carried as TWO limbs, because
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
 * <p>F7 correction (Opus review, 2026-07-30): the slot numbers above were updated to match the
 * accountant's-workbook renumbering (handoff section 9d, 2026-07-29) -- คอมมิชชั่น moved from พิเศษ 6
 * to พิเศษ 7 (a new พิเศษ 2, ค่าเช่าบ้าน, was inserted ahead of it, shifting every later slot by one).
 * See {@code PayrollCalculator}'s {@code COMMISSION_SPECIAL_PAY_INDEX}, already correct, for the same
 * numbering applied to the actual index used by {@code calculate()}.
 *
 * <p>The split is owner-stated, not inferred from the slot labels — an earlier version read พิเศษ 1-5
 * as standing allowances and had this exactly backwards. See {@code PayrollCalculator}'s
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
    // Per-limb breakdown (task 2, 2026-07-29): withholdingTax below stays the TOTAL across all three
    // ป.96 limbs (still used by callers that only need the aggregate). The classified engine
    // (PayrollCalculator#calculateClassified) needs the REGULAR and CUMULATIVE limbs' own prior
    // contributions separately -- ข้อ 1(4)'s reprojection reads regularLimbTaxableIncome /
    // regularLimbWithholdingTax, and ข้อ 1(6)'s "less tax already withheld" reads
    // cumulativeLimbTaxableIncome / cumulativeLimbWithholdingTax. The known limb (ข้อ 1(5)) is
    // deliberately NOT carried here -- each known-frequency payment is taxed as its own period's
    // marginal difference and never reprojected forward, so it needs no YTD state.
    //
    // SUPERSEDED going forward for the regular/variable pair above (rebase onto branch 117, commit
    // c52080b6, 2026-07-29 -- see docs/agent-handoffs/118_feat-payroll-classification-and-hr-
    // declarations.md, "Progress -- task 4: rebase report + F1-F7 fixes" for what that rebase actually
    // changed; F4 correction, Opus review 2026-07-30, that section did not exist when this comment was
    // first written): calculateLine now runs exclusively through calculateClassified, which reads the
    // *Limb fields below, not regularIncome/variableIncome/regularWithholdingTax/variableWithholdingTax.
    // Those four (and the legacy calculate() method that still reads them) exist only for
    // PayrollExcelReconciliationTest's
    // frozen regression suite and other calculate()-only callers.
    BigDecimal withholdingTax,
    BigDecimal regularLimbTaxableIncome,
    BigDecimal regularLimbWithholdingTax,
    BigDecimal cumulativeLimbTaxableIncome,
    BigDecimal cumulativeLimbWithholdingTax,
    // F1 fix (Opus review, 2026-07-30): a settled EXTRA_KNOWN_FREQUENCY payment (ข้อ 1(5), e.g. a
    // bonus) is taxed and fully withheld in the period it is paid and is deliberately NOT reprojected
    // -- but it is still real income that must stay part of the running total the CUMULATIVE limb is
    // layered on top of in every LATER period. Without this field, PayrollCalculator#calculateClassified
    // had no way to know a known-limb payment had already happened this year once its own period ended,
    // so the cumulative limb's marginal tax silently dropped it from the bracket position -- the KNOWN
    // and CUMULATIVE limbs then consumed the same tax brackets twice and the year under-withheld. This
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

    /**
     * Legacy 5-arg constructor {@code (regularIncome, variableIncome, socialSecurity,
     * regularWithholdingTax, variableWithholdingTax)}, kept so every call site written before task 2's
     * three-limb breakdown (including {@code PayrollCalculatorTest} and the other
     * {@code PayrollCalculator#calculate}-only review tests) still compiles. {@code withholdingTax}
     * defaults to the sum of the two known limbs; the three-limb-only fields default to zero -- a
     * no-op for {@code calculate()}, which never reads them, matching how the classified engine's own
     * legacy constructor below defaults fields it does not know about.
     */
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

    /**
     * Legacy 3-arg constructor {@code (taxableIncome, socialSecurity, withholdingTax)}, kept so every
     * call site written before EITHER limb split existed still compiles.
     *
     * <p>The un-split figures are attributed ENTIRELY to the regular limb of the two-limb model
     * (regularIncome/regularWithholdingTax) -- the correct reading for pre-split history rather than a
     * guess: months processed before that change annualised everything as if it were regular pay. The
     * three-limb-only fields default to zero, exactly as the classified engine's own original 3-arg
     * legacy constructor already did -- unknown at this arity, not fabricated.
     */
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
