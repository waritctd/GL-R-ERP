package th.co.glr.hr.payroll;

/**
 * Which limb of มาตรา 42 a เบี้ยเลี้ยง payment was made under.
 *
 * <p>Recorded per payroll line, not per employee, because GL&amp;R pays both ways depending on the
 * trip (owner-confirmed 2026-07-29). The two limbs carry different evidence obligations, so without
 * this a later audit cannot tell whether receipts ought to exist for a given payment.
 */
public enum PerDiemBasis {
    /**
     * มาตรา 42(2) — "ค่าพาหนะและเบี้ยเลี้ยงเดินทางตามอัตราที่รัฐบาลกำหนดไว้โดยพระราชกฤษฎีกา".
     * เหมาจ่าย at the government rate. Exempt up to that rate with NO proof of actual spending; the
     * excess above it is ordinary taxable income.
     */
    FLAT_RATE_S42_2,

    /**
     * มาตรา 42(1) — "ค่าเบี้ยเลี้ยง หรือค่าพาหนะซึ่งลูกจ้าง...ได้จ่ายไปโดยสุจริตตามความจำเป็นเฉพาะใน
     * การที่ต้องปฏิบัติการตามหน้าที่ของตนและได้จ่ายไปทั้งหมดในการนั้น". Reimbursed against actual
     * spending. Exempt in full provided it was necessary for duty and entirely spent — the receipts
     * are the evidence and must be retained.
     */
    REIMBURSED_S42_1
}
