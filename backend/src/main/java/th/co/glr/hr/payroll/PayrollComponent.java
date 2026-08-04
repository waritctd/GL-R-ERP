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
 *
 * <p><b>Finding 6 (fourth Opus review, 2026-07-30) -- adding a value here is not just an enum
 * edit.</b> {@link PayrollCalculator#requireEveryNonZeroComponentClassified} 409s the ENTIRE payroll
 * run for any employee/component pair with a non-zero amount and no stored {@code
 * hr.payroll_component_tax_treatment} row -- so a brand-new component added here with no backfill
 * blocks payroll for every existing employee who is ever paid a non-zero amount of it, the instant
 * that value is used, until an {@code INSERT INTO hr.payroll_pay_component} plus a
 * {@code hr.payroll_component_tax_treatment}/{@code hr.payroll_component_sso_inclusion} backfill
 * migration exists for it (see {@code V100__payroll_component_tax_treatment_backfill.sql} for the
 * pattern this repeats: CROSS JOIN every employee, default per the handoff's own recurrence rules).
 * Adding a value here without that follow-up migration is therefore incomplete by construction, not
 * merely under-tested.
 */
public enum PayrollComponent {
    /** เงินเดือน. Locked to {@link PayrollTaxTreatment#REGULAR_REPROJECT} -- see that enum's javadoc. */
    SALARY,
    /** พิเศษ 1 (ค่าครองชีพ). */
    SPECIAL_PAY_1,
    /**
     * พิเศษ 2 (ค่าเช่าบ้าน). Renumbered here from slot 9 (2026-07-29, aligned to the accountant's
     * workbook -- see {@link PayrollService#specialPayDtos}'s javadoc). Every payroll period is VOID
     * at the time of this relabel, so this is a pure label change: no stored figure moves.
     */
    SPECIAL_PAY_2,
    /** พิเศษ 3 (เบี้ยเลี้ยงประจำ). */
    SPECIAL_PAY_3,
    /** พิเศษ 4 (ค่าตำแหน่ง). */
    SPECIAL_PAY_4,
    /** พิเศษ 5 (เบี้ยขยันประจำ). */
    SPECIAL_PAY_5,
    /** พิเศษ 6 (ค่า GPRS). */
    SPECIAL_PAY_6,
    /**
     * พิเศษ 7 (คอมมิชชั่น) -- the historical พิเศษ slot, distinct from {@link #COMMISSION_PAY}.
     * Was slot 6 before the 2026-07-29 workbook realignment. {@code PayrollCalculator}'s legacy
     * {@code calculate()} method (zero production callers; {@code calculateClassified} is the live
     * engine) carried a now-corrected {@code COMMISSION_SPECIAL_PAY_INDEX} constant that hardcoded
     * the OLD slot 6 -- see that constant's javadoc for the fix, applied 2026-07-29 as a follow-up
     * once branch 117 was rebased in.
     */
    SPECIAL_PAY_7,
    /** พิเศษ 8 (ทำได้ตาม KPI). */
    SPECIAL_PAY_8,
    /** พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ). */
    SPECIAL_PAY_9,
    /** ค่าล่วงเวลา. */
    OVERTIME_PAY,
    /** คอมมิชชั่น -- the auto-fed {@code payroll_line.commission_pay} column (CommissionService). */
    COMMISSION_PAY,
    /**
     * เงินโบนัส and อื่นๆ — the dedicated one-off fields. <b>Correction (task 2, 2026-07-29):</b> the
     * original comment here assumed their {@code payroll_line} columns would arrive with branch 117's
     * V94. Branch 117 was never merged into this worktree, so V94 does not exist here; the columns
     * are added by V96 instead (see that migration's comment for the full reasoning) rather than
     * waiting on 117.
     *
     * <p>Present here from the start deliberately. The spec names โบนัส in the ประกันสังคม wage base
     * and calls bonus the archetypal {@code EXTRA_KNOWN_FREQUENCY} — a matrix that cannot express
     * bonus would be unable to state the one classification the spec is most explicit about.
     */
    /**
     * ค่าอาหาร (workbook col K) and เบี้ยเลี้ยง ตจว/ตปท (col P) — V97. Present in the accountant's
     * ledger since before this system existed and absent from it until 2026-07-29; found by reading
     * `2026.xlsx`, not by anyone reporting them missing. Both sit inside the workbook's W total and
     * are therefore taxable. Deliberately not พิเศษ slots — the workbook does not number them as
     * พิเศษ either.
     */
    MEAL_ALLOWANCE,

    /**
     * เบี้ยเลี้ยง ONLY the portion above the มาตรา 42 exempt limit. The exempt portion is deliberately
     * NOT a component: like non_taxable_income it sits outside both the tax base and the ประกันสังคม
     * wage base, so it has no ป.96 treatment to classify. Only the excess is taxable income, and only
     * the excess appears here.
     */
    PER_DIEM_TAXABLE,

    BONUS_PAY,
    OTHER_ONE_OFF_PAY,

    /**
     * สวัสดิการที่อนุมัติแล้ว — approved welfare claims (เงินช่วยเหลือ, ค่ารักษาพยาบาล, ชุดฟอร์ม,
     * เบี้ยเลี้ยงเดินทาง) summed from {@code hr.special_money_request} for the payroll month. V128.
     *
     * <p><b>Auto-fed, exactly like {@link #OVERTIME_PAY} and {@link #COMMISSION_PAY}</b>, and for the
     * same reason those are not พิเศษ slots: {@link #SPECIAL_PAY_9} is labelled
     * เงินรางวัล/เงินช่วยเหลืออื่นๆ and is the obvious-looking home, but it is a MANUAL entry slot.
     * Sharing it would make the double-payment undetectable — there would be no way to tell the
     * computed figure from the hand-keyed one. See {@link #SPECIAL_PAY_7}, which records the same
     * distinction for commission.
     *
     * <p>SSO-excluded: ค่าจ้าง under พ.ร.บ.ประกันสังคม ม.5 is money paid in return for work, and
     * welfare is not.
     */
    WELFARE_PAY,
    /** ค่าตอบแทนกรรมการ. SSO-excluded by default (not wages under the Social Security Act). */
    DIRECTOR_REMUNERATION,
    /** รายได้ไม่คิดภาษี. SSO-excluded by default and out of scope for tax treatment. */
    NON_TAXABLE_INCOME
}
