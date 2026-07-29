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
 *       จำนวนคราวที่ต้องจ่าย. Base salary, <b>พิเศษ 6 (คอมมิชชั่น)</b> and the commission feed, and
 *       ค่าตอบแทนกรรมการ — the items paid every คราว, whatever the amount.</li>
 *   <li><b>variable</b> — เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5): ค่าล่วงเวลา, <b>พิเศษ 1-5, 7, 8</b>,
 *       and the dedicated bonus / อื่นๆ fields. Taken at its ACTUAL cumulative amount and never
 *       multiplied out, then taxed as the DIFFERENCE between the tax with it and the tax without it.</li>
 * </ul>
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
    BigDecimal variableWithholdingTax
) {
    public static PayrollYearToDate empty() {
        return new PayrollYearToDate(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Legacy 3-arg constructor {@code (taxableIncome, socialSecurity, withholdingTax)}, kept so every
     * call site written before the ป.96 limb split still compiles.
     *
     * <p>The un-split figures are attributed ENTIRELY to the regular limb. That is the correct
     * reading for pre-split history rather than a guess: months processed before this change
     * annualised everything as if it were regular pay, so "regular" is what actually happened to
     * them. It also means a legacy YTD reproduces the old projection shape for the regular limb
     * exactly.
     */
    public PayrollYearToDate(
        BigDecimal taxableIncome,
        BigDecimal socialSecurity,
        BigDecimal withholdingTax
    ) {
        this(taxableIncome, BigDecimal.ZERO, socialSecurity, withholdingTax, BigDecimal.ZERO);
    }

    /** Total year-to-date taxable income across both limbs. */
    public BigDecimal taxableIncome() {
        return nz(regularIncome).add(nz(variableIncome));
    }

    /** Total year-to-date withholding tax actually withheld across both limbs. */
    public BigDecimal withholdingTax() {
        return nz(regularWithholdingTax).add(nz(variableWithholdingTax));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
