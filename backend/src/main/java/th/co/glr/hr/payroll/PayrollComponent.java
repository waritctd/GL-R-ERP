package th.co.glr.hr.payroll;

/**
 * The canonical pay-component enumeration shared by the per-employee tax-treatment matrix
 * ({@code hr.payroll_component_tax_treatment}) and the per-employee SSO-inclusion matrix
 * ({@code hr.payroll_component_sso_inclusion}), introduced V95. One value per {@code
 * hr.payroll_line} money column that represents earned/taxable income HR or the engine can put a
 * non-zero amount into for a given employee and run -- not deductions or tax allowances, which are
 * governed elsewhere ({@code hr.employee_tax_allowance}, the {@code *_deduction} columns).
 *
 * <p>The พิเศษ labels mirror {@link PayrollService#specialPayDtos}. Mirrors {@code
 * hr.payroll_pay_component}'s seed rows in V95 -- treat both as append-only: a value already
 * written into a tax-treatment or SSO-inclusion row must never be renumbered, relabeled in meaning,
 * or removed, or it silently redefines a stored classification.
 *
 * <p><b>Known gap (2026-07-29):</b> handoff section 10 also names {@code bonusPay} / {@code
 * otherOneOffPay} as future HR-typed fields on the payroll run. Neither has a {@code payroll_line}
 * column yet, so neither is a value here -- adding the column is out of scope for this migration.
 * Add {@code BONUS} / {@code OTHER_ONE_OFF_PAY} here (and to {@code hr.payroll_pay_component}) as
 * an appended addition when those columns land.
 */
public enum PayrollComponent {
    /** เงินเดือน. Locked to {@link PayrollTaxTreatment#REGULAR_REPROJECT} -- see that enum's javadoc. */
    SALARY,
    /** พิเศษ 1 (ค่าครองชีพ). */
    SPECIAL_PAY_1,
    /** พิเศษ 2 (เบี้ยเลี้ยงประจำ). */
    SPECIAL_PAY_2,
    /** พิเศษ 3 (ค่าตำแหน่ง). */
    SPECIAL_PAY_3,
    /** พิเศษ 4 (เบี้ยขยันประจำ). */
    SPECIAL_PAY_4,
    /** พิเศษ 5 (ค่า GPRS). */
    SPECIAL_PAY_5,
    /** พิเศษ 6 (คอมมิชชั่น) -- the historical พิเศษ slot, distinct from {@link #COMMISSION_PAY}. */
    SPECIAL_PAY_6,
    /** พิเศษ 7 (ทำได้ตาม KPI). */
    SPECIAL_PAY_7,
    /** พิเศษ 8 (เงินรางวัล/เงินช่วยเหลืออื่นๆ). */
    SPECIAL_PAY_8,
    /** พิเศษ 9 (ค่าเช่าบ้าน) -- appended 2026-07-29, never renumbered into 1-8's positions. */
    SPECIAL_PAY_9,
    /** ค่าล่วงเวลา. */
    OVERTIME_PAY,
    /** คอมมิชชั่น -- the auto-fed {@code payroll_line.commission_pay} column (CommissionService). */
    COMMISSION_PAY,
    /** ค่าตอบแทนกรรมการ. SSO-excluded by default (not wages under the Social Security Act). */
    /**
     * เงินโบนัส and อื่นๆ — the dedicated one-off fields. Their {@code payroll_line} columns arrive
     * with branch 117's V94, which the handoff mandates landing BEFORE this branch; V95 sorts after
     * V94, so by the time this enum is read the columns exist.
     *
     * <p>Present here from the start deliberately. The spec names โบนัส in the ประกันสังคม wage base
     * and calls bonus the archetypal {@code EXTRA_KNOWN_FREQUENCY} — a matrix that cannot express
     * bonus would be unable to state the one classification the spec is most explicit about.
     */
    BONUS_PAY,
    OTHER_ONE_OFF_PAY,

    DIRECTOR_REMUNERATION,
    /** รายได้ไม่คิดภาษี. SSO-excluded by default and out of scope for tax treatment. */
    NON_TAXABLE_INCOME
}
