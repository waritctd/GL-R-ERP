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
    // ยกเว้นเงินได้ 190,000 for a taxpayer aged 65+, or holding a บัตรประจำตัวคนพิการ at any age
    // (กฎกระทรวง ฉบับที่ 126). Per person, across ALL income types combined.
    private static final BigDecimal ELDERLY_DISABLED_EXEMPTION = new BigDecimal("190000.00");
    private static final int ELDERLY_EXEMPTION_AGE = 65;
    // SSF purchases were deductible for ปีภาษี 2563-2567 only; ปีภาษี 2568 = Gregorian 2025.
    private static final int SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR = 2025;
    private static final BigDecimal MIN_NET_AFTER_LEGAL_EXECUTION = new BigDecimal("20000.00");
    private static final int SPECIAL_PAY_SLOTS = 8;
    // ป.96/2543 income classification. คำชี้แจง แบบ ภ.ง.ด.1 splits employment income into
    // เงินได้ที่จ่ายตามปกติ (ข้อ 2.1, annualised by x จำนวนคราวที่ต้องจ่าย) and เงินพิเศษที่จ่ายเป็น
    // ครั้งคราว (ข้อ 2.5, taken at its actual amount and taxed as the difference it makes).
    //
    // CORRECTED 2026-07-29 ON THE OWNER'S STATEMENT. This was previously inferred from the slot labels
    // and PayrollCarryForwardDtos' javadoc, which read พิเศษ 1-5 as standing company allowances. That
    // inference was WRONG about how GL&R actually enters payroll. The owner's account:
    //
    //   "all of 1-8 is occasional for each employee in each month except พิเศษ 6 that is commission
    //    that sales get every month, just the amount varies"
    //
    // So the classification is NOT a contiguous slot range:
    //   พิเศษ 6 (คอมมิชชั่น) + commissionPay  -> REGULAR. Paid every คราว, so จำนวนคราวที่ต้องจ่าย is
    //                                            the periods remaining, not 1. A varying amount is no
    //                                            obstacle -- the year-to-date carry-forward re-projects
    //                                            from the actual figure every period.
    //   พิเศษ 1-5, 7, 8 + overtimePay        -> VARIABLE. All occasional, and this is where an annual
    //                                            bonus is actually typed (owner: "one of พิเศษ 1-5"),
    //                                            which is the case ข้อ 2.5 exists for. ข้อ 2.5 names
    //                                            ค่าล่วงเวลา and เงินโบนัส explicitly.
    //
    // Base salary and ค่าตอบแทนกรรมการ are REGULAR. Owner-confirmed 2026-07-29: "director remuneration
    // is every month but the pay may change once in a while." Paid every คราว, so ข้อ 2.1 -- a varying
    // amount is no obstacle, because ข้อ 2.4 requires exactly the re-projection this limb already does
    // (ให้คำนวณภาษีหัก ณ ที่จ่ายใหม่ทุกคราว), rebuilding from actual year-to-date each period.
    private static final int COMMISSION_SPECIAL_PAY_INDEX = 5;

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
        // The two ป.96/2543 limbs (see COMMISSION_SPECIAL_PAY_INDEX above for why the split falls here).
        // Director remuneration is REGULAR: paid every month (owner-confirmed), so เงินได้ที่จ่ายตาม
        // ปกติ under ข้อ 2.1, not a เงินพิเศษ under ข้อ 2.5. The amount changing occasionally does not
        // move it -- ข้อ 2.4 covers that, and this limb re-projects from year-to-date every period.
        // Only พิเศษ 6 is recurring; every other slot is occasional (see COMMISSION_SPECIAL_PAY_INDEX).
        // Deliberately index-based rather than a range: the classification is not contiguous, and
        // writing it as a range is exactly the mistake this replaces.
        BigDecimal recurringSpecialPayTotal = specialPays.get(COMMISSION_SPECIAL_PAY_INDEX);
        BigDecimal occasionalSpecialPayTotal = ZERO;
        for (int slot = 0; slot < SPECIAL_PAY_SLOTS; slot += 1) {
            if (slot != COMMISSION_SPECIAL_PAY_INDEX) {
                occasionalSpecialPayTotal = occasionalSpecialPayTotal.add(specialPays.get(slot));
            }
        }
        // commissionPay (fed from CommissionService) joins พิเศษ 6 in the regular limb -- it is the
        // same income by another route. overtimePay is occasional and stays in the variable limb.
        BigDecimal regularGrossEarnings = money(baseSalary.add(recurringSpecialPayTotal)
            .add(commissionPay).add(directorRemuneration));
        // Dedicated one-off pay (V94): เงินโบนัส and อื่นๆ. ข้อ 2.5 names เงินโบนัส explicitly, so this
        // is the limb it belongs in -- the same treatment the พิเศษ slots get, but from a field that
        // cannot be confused with a recurring allowance.
        BigDecimal bonusPay = money(input.bonusPay());
        BigDecimal otherOneOffPay = money(input.otherOneOffPay());
        BigDecimal variableGrossEarnings = money(occasionalSpecialPayTotal.add(overtimePay)
            .add(bonusPay).add(otherOneOffPay));
        // Taxable earnings only (sheet column A). Non-taxable income (sheet column D)
        // is excluded from tax and SSO and added back to net pay at the end. Identical to the
        // pre-split expression by construction -- the two limbs partition exactly the same terms.
        BigDecimal grossEarnings = money(regularGrossEarnings.add(variableGrossEarnings));
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
        BigDecimal preTaxDeductions = money(unpaidLeaveDeduction
            .subtract(leaveDeductionRefund)
            .add(warningLetterDeduction)
            .add(customerReturnDeduction)
            .add(otherPretaxDeduction));
        // Pre-tax deductions come off the REGULAR limb first and spill onto the variable limb only if
        // regular would go negative. They describe unworked days and chargebacks against ordinary pay,
        // so charging them to the regular limb is the faithful reading; the spill exists purely so a
        // deduction larger than the month's salary still lands somewhere instead of being lost.
        //
        // INVARIANT (pinned by test): regularTaxableIncome + variableTaxableIncome is, to the satang,
        // the same grossTaxableIncome the single-limb code produced -- max(0, gross - preTaxDeductions).
        // PayrollExcelReconciliationTest's net-pay identity and every downstream figure depend on it.
        //
        // The VARIABLE limb is deliberately NOT floored at zero. commissionPay arrives negative when a
        // month's CLAWBACK rows exceed its sales (CommissionRepository stores clawback amounts negated),
        // and a negative เงินพิเศษ is a real reduction of the year's cumulative figure under ข้อ 2.5 --
        // annualVariableIncome below is a running total, so the clawback belongs in it with its sign
        // intact. Flooring each limb independently was a defect: it discarded the reduction AND broke
        // the invariant, letting grossTaxableIncome exceed grossEarnings, so an employee paid 40,000
        // was taxed on 50,000 and that 50,000 reached their ภ.ง.ด.1 and 50 ทวิ.
        //
        // Only the COMBINED figure is floored, exactly as the single-limb code floored its one figure.
        // When the combined total would go negative the variable limb is raised to cancel the regular
        // limb rather than the other way round: a debt beyond this month's whole pay cannot be
        // annualised as if it recurred, which is what charging it to the regular limb would do.
        BigDecimal regularTaxableIncome = money(regularGrossEarnings.subtract(preTaxDeductions).max(ZERO));
        BigDecimal preTaxDeductionSpill = money(preTaxDeductions.subtract(regularGrossEarnings).max(ZERO));
        BigDecimal variableTaxableIncome = money(variableGrossEarnings.subtract(preTaxDeductionSpill));
        if (regularTaxableIncome.add(variableTaxableIncome).signum() < 0) {
            variableTaxableIncome = money(regularTaxableIncome.negate());
        }
        BigDecimal grossTaxableIncome = money(regularTaxableIncome.add(variableTaxableIncome));

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

        // จำนวนคราวที่ต้องจ่าย remaining this tax year, INCLUDING this period (คำชี้แจง ภ.ง.ด.1 ข้อ 2.1).
        // PayrollService supplies it so a leaver's final period lands on 1; falling back to
        // 13 - month reproduces the pre-ป.96 behaviour for any call site that does not.
        int monthsRemaining = input.remainingPayPeriods() > 0
            ? input.remainingPayPeriods()
            : Math.max(1, 13 - input.payrollMonthValue());

        // ข้อ 2.1 -- regular pay is annualised: multiplied by the number of times it will still be paid.
        BigDecimal annualRegularIncome = money(money(yearToDate.regularIncome())
            .add(regularTaxableIncome.multiply(BigDecimal.valueOf(monthsRemaining))));
        // ข้อ 2.5 -- เงินพิเศษที่จ่ายเป็นครั้งคราว is taken at its ACTUAL cumulative amount for the year
        // and is NOT multiplied out. This is the clause the old single-limb projection violated: it
        // multiplied a one-off bonus by the remaining months, projecting income that will never exist.
        BigDecimal annualVariableIncome = money(money(yearToDate.variableIncome()).add(variableTaxableIncome));
        BigDecimal projectedAnnualIncome = money(annualRegularIncome.add(annualVariableIncome));

        // ข้อ 2.2 run twice, exactly as ข้อ 2.5 directs: once on regular pay alone, once with the
        // เงินพิเศษ added. The allowance breakdown is recomputed for each base rather than shared,
        // because several ค่าลดหย่อน caps are percentages of เงินได้ and so legitimately differ.
        AnnualTaxAssessment regularOnly = assessAnnualTax(
            annualRegularIncome, allowances, yearToDate, socialSecurity, monthsRemaining,
            input.taxYear(), input.taxpayerAge());
        AnnualTaxAssessment withVariable = assessAnnualTax(
            projectedAnnualIncome, allowances, yearToDate, socialSecurity, monthsRemaining,
            input.taxYear(), input.taxpayerAge());

        BigDecimal taxExpenseDeduction = withVariable.expenseDeduction();
        AllowanceBreakdown allowanceBreakdown = withVariable.allowances();
        BigDecimal taxableAnnualIncome = withVariable.taxableAnnualIncome();
        BigDecimal annualTax = withVariable.tax();

        // ข้อ 2.5 -- the tax attributable to the เงินพิเศษ is the DIFFERENCE between the two runs.
        BigDecimal variableAnnualTax = money(withVariable.tax().subtract(regularOnly.tax()));

        // ข้อ 2.3 -- the regular limb's annual tax, net of what has already been withheld on it, spread
        // over the periods that remain. ข้อ 2.5 -- the variable limb's tax, net of what has already
        // been withheld on IT specifically, falls due in full in the period the เงินพิเศษ is paid.
        BigDecimal regularWithholdingRaw = regularOnly.tax()
            .subtract(money(yearToDate.regularWithholdingTax()))
            .divide(BigDecimal.valueOf(monthsRemaining), MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal variableWithholdingRaw = money(variableAnnualTax
            .subtract(money(yearToDate.variableWithholdingTax())));
        // ONE clamp, on the remitted total only. Neither limb is clamped on its own: a negative raw
        // limb is how an earlier over-withholding is handed back through lower withholding later,
        // which is precisely what ข้อ 2.9 requires. (The old code clamped the single combined limb at
        // zero, which made over-withholding permanent -- December could never give it back.) The
        // remitted figure itself can never be negative, because tax already paid to the RD cannot be
        // recovered through payroll; an employee over-withheld across the whole year reclaims it on
        // their ภ.ง.ด.91.
        //
        // When monthsRemaining == 1 this collapses to (total annual tax - total withheld YTD), which IS
        // the ข้อ 2.9 December true-up -- no special-casing needed. It would equally serve ข้อ 2.10's
        // final-period true-up for a leaver, but PayrollService never produces a 1 for that reason:
        // resignations are not recorded in this platform, so ข้อ 2.10 is a known gap and only December
        // reaches this branch in production. See PayrollService#remainingPayPeriods.
        //
        // CEILING (added after review): the two limbs are asymmetric by design -- the regular limb's
        // credit is spread over the periods that remain (ข้อ 2.3) while the variable limb's charge
        // falls due in full in the period paid (ข้อ 2.5). So in a period that carries both, only 1/N
        // of an available credit can offset the whole charge, and the engine could go on withholding
        // in a period where it already knew the year's liability was met. December then clamped at
        // zero and the excess was stranded until the employee filed their ภ.ง.ด.91.
        //
        // ข้อ 2.9's principle settles it: the total withheld across the year is meant to equal the tax
        // actually due. Nothing may be withheld beyond that, in ANY period -- not merely trued up in
        // the last one. This ceiling makes the asymmetry unreachable rather than papering over it.
        BigDecimal yearToDateWithheld = money(yearToDate.regularWithholdingTax())
            .add(money(yearToDate.variableWithholdingTax()));
        BigDecimal collectableThisYear = money(withVariable.tax().subtract(yearToDateWithheld).max(ZERO));
        BigDecimal withholdingTax = min(
            money(regularWithholdingRaw.add(variableWithholdingRaw).max(ZERO)),
            collectableThisYear);
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
        String calculationNote =
            "ป.96/2543 withholding; SSO 5% cap; legal execution floor."
            + clampedAllowanceNote(allowances);
        if (withholdingTaxOverride != null) {
            withholdingTax = withholdingTaxOverride;
            calculationNote = calculationNote
                + " Withholding tax overridden by HR to " + withholdingTaxOverride.toPlainString()
                + " (computed projection retained for transparency).";
        }

        // Over-withholding for the tax year AS AT THE END OF THIS PERIOD -- i.e. including what this
        // period itself withholds. Tax already remitted to the RD cannot be handed back through
        // payroll; the employee reclaims it on their ภ.ง.ด.91 (or ภ.ง.ด.90 if they have non-40(1)
        // income). Reported rather than left invisible: withholdingTax alone just reads 0.00, which is
        // indistinguishable from "nothing was due this period".
        //
        // Computed AFTER the override, deliberately. It used to be computed before, which made it
        // blind to the substitution: an HR override in December could push the employee further past
        // the year's liability while the payslip went on printing the smaller pre-override figure --
        // understated by exactly the override, on the one document this exists to make truthful, and
        // contradicted by the override clause in calculationNote on the same page.
        //
        // Adding this period's withholding changes NOTHING in the normal path. Without an override
        // withholdingTax is capped at collectableThisYear = max(0, annualTax - yearToDateWithheld), so
        // either yearToDateWithheld >= annualTax (the cap is 0, nothing more is withheld, and the
        // figure is unchanged) or yearToDateWithheld + withholdingTax <= annualTax (and it is 0 either
        // way). Only an override can make the two differ, which is the case this addresses.
        BigDecimal excessWithheldToDate = money(yearToDateWithheld
            .add(withholdingTax)
            .subtract(withVariable.tax())
            .max(ZERO));

        // The withheld total must be carried forward SPLIT, because next period's ข้อ 2.5 arithmetic
        // nets against what was withheld on the variable limb specifically. The two parts always sum
        // to what was actually withheld -- that is what makes the carry-forward reconcile.
        BigDecimal regularWithholdingTax;
        BigDecimal variableWithholdingTax;
        if (withholdingTaxOverride != null) {
            // An override is a flat instruction with no limb structure behind it. Attributing it all
            // to the regular limb keeps the variable limb's YTD honest: nothing was withheld under
            // ข้อ 2.5 this period, so next period must not behave as though something was.
            regularWithholdingTax = withholdingTax;
            variableWithholdingTax = ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else if (withholdingTax.signum() == 0) {
            regularWithholdingTax = withholdingTax;
            variableWithholdingTax = withholdingTax;
        } else if (regularWithholdingRaw.signum() < 0) {
            regularWithholdingTax = ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            variableWithholdingTax = withholdingTax;
        } else if (variableWithholdingRaw.signum() < 0) {
            regularWithholdingTax = withholdingTax;
            variableWithholdingTax = ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else {
            regularWithholdingTax = money(regularWithholdingRaw);
            variableWithholdingTax = money(variableWithholdingRaw);
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
            withholdingTaxOverride,
            regularTaxableIncome,
            variableTaxableIncome,
            regularWithholdingTax,
            variableWithholdingTax,
            annualRegularIncome,
            annualVariableIncome,
            regularOnly.tax(),
            variableAnnualTax,
            monthsRemaining,
            excessWithheldToDate,
            bonusPay,
            otherOneOffPay
        );
    }

    /**
     * One full คำชี้แจง ภ.ง.ด.1 ข้อ 2.2 pass: from an annualised เงินได้พึงประเมิน, take ค่าใช้จ่าย
     * (50% capped at 100,000, shared across มาตรา 40(1) and 40(2)) and ค่าลดหย่อน, then apply the
     * มาตรา 48(1) progressive scale.
     *
     * <p>Run twice per period — once on regular pay alone and once with the เงินพิเศษ included — so
     * that ข้อ 2.5 can take the difference between them.
     */
    private AnnualTaxAssessment assessAnnualTax(
        BigDecimal annualIncome,
        PayrollTaxAllowanceInput allowances,
        PayrollYearToDate yearToDate,
        BigDecimal currentSocialSecurity,
        int monthsRemaining,
        int taxYear,
        int taxpayerAge
    ) {
        // ยกเว้นเงินได้ 190,000 (V93) comes off FIRST, before ค่าใช้จ่าย. That order is the RD's own:
        // ใบแสดงสิทธิการได้รับยกเว้นเงินได้ฯ ข้อ 1 reads "1. มาตรา 40(1) เงินเดือน" -> "2. หัก เงินได้ที่
        // ได้รับการยกเว้น" -> "3. คงเหลือ (1.-2.) ยกไปกรอกใน ภ.ง.ด.90", and the 50% expense deduction
        // is then taken on that remainder. Doing it the other way round would over-deduct expenses.
        // Floored first: a clawback large enough to outweigh the year's other เงินพิเศษ can drive the
        // projected figure negative, and a negative income must not produce a negative ค่าใช้จ่าย.
        BigDecimal positiveAnnualIncome = money(annualIncome.max(ZERO));
        BigDecimal exemptIncome = exemptIncome(allowances, taxpayerAge, positiveAnnualIncome);
        BigDecimal assessableIncome = money(positiveAnnualIncome.subtract(exemptIncome).max(ZERO));

        BigDecimal expenseDeduction = min(assessableIncome.multiply(new BigDecimal("0.50")), EXPENSE_DEDUCTION_CAP);
        AllowanceBreakdown breakdown = allowanceBreakdown(
            allowances, assessableIncome, expenseDeduction, yearToDate, currentSocialSecurity,
            monthsRemaining, taxYear);
        BigDecimal taxableAnnualIncome = money(assessableIncome
            .subtract(expenseDeduction)
            .subtract(breakdown.total())
            .max(ZERO));
        return new AnnualTaxAssessment(
            expenseDeduction, breakdown, taxableAnnualIncome, progressiveTax(taxableAnnualIncome));
    }

    /**
     * ยกเว้นเงินได้ 190,000 บาท under กฎกระทรวง ฉบับที่ 126 — for a taxpayer aged 65 or over in the tax
     * year, or one holding a บัตรประจำตัวคนพิการ at any age. Capped at the income itself, and per
     * person across all income types (here, employment income is the only type the engine sees).
     *
     * <p>{@code taxpayerAge} of zero means "date of birth unknown" — the exemption then rests on the
     * disability declaration alone rather than being granted on an assumption.
     */
    /**
     * Flags a ล.ย.01 declaration that carries a baht amount but no head count, so the per-head cap has
     * clamped it to nothing. Visible on the payslip rather than silent: an allowance vanishing without
     * explanation is the failure mode a reviewer caught in the request-body path, and the note is what
     * makes the same contradiction detectable if it ever reaches here from anywhere else.
     */
    private String clampedAllowanceNote(PayrollTaxAllowanceInput allowances) {
        StringBuilder note = new StringBuilder();
        appendClampWarning(note, "ค่าลดหย่อนบุตร",
            money(allowances.childAllowance()), childAllowanceCap(allowances));
        appendClampWarning(note, "ค่าอุปการะคนพิการ",
            money(allowances.disabledCareAllowance()), disabledCareAllowanceCap(allowances));
        return note.toString();
    }

    /**
     * Fires on ANY clamp, not only a zero head count. A STALE count clamps just as silently as a
     * missing one -- a stored count of 1 against a 90,000 declaration quietly discards 60,000 -- and
     * the first version of this warning only covered the zero case, which review caught.
     */
    private void appendClampWarning(StringBuilder note, String label, BigDecimal declared, BigDecimal cap) {
        if (declared.compareTo(cap) <= 0) {
            return;
        }
        note.append(" WARNING: ").append(label).append(' ')
            .append(declared.toPlainString()).append(" > cap ").append(cap.toPlainString())
            .append(" (per ล.ย.01 head count).");
    }

    private BigDecimal exemptIncome(PayrollTaxAllowanceInput allowances, int taxpayerAge, BigDecimal annualIncome) {
        boolean eligible = taxpayerAge >= ELDERLY_EXEMPTION_AGE || allowances.disabilityCardHolder();
        return eligible ? min(ELDERLY_DISABLED_EXEMPTION, annualIncome) : ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
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
        int monthsRemaining,
        int taxYear
    ) {
        BigDecimal projectedSsoAllowance = min(
            SSO_YEAR_CAP,
            money(yearToDate.socialSecurity()).add(currentSocialSecurity.multiply(BigDecimal.valueOf(monthsRemaining)))
        );
        // Per-head caps (V93). บุตร and อุปการะคนพิการ used to be free-typed baht amounts the engine
        // trusted without limit -- the only two allowances that were not clamped. The law is per head:
        // 30,000 per child, 60,000 for the second and later child born from พ.ศ. 2561, and 60,000 per
        // disabled person cared for. The counts come from แบบ ล.ย.01, which asks for exactly that.
        BigDecimal childCap = childAllowanceCap(input);
        BigDecimal disabledCareCap = disabledCareAllowanceCap(input);

        // A declared amount with a ZERO head count is a contradiction, and the cap resolves it against
        // the amount -- deriving a count here instead would defeat the cap completely, since any figure
        // could then be claimed by simply declaring nobody. It must not pass SILENTLY though: callers
        // that legitimately hold an amount but no count (a pre-V93 row, a per-run HR entry) resolve it
        // before reaching this point -- see PayrollService#headCountFor and
        // PayrollTaxAllowanceInput's legacy constructor -- so anything arriving here contradictory is a
        // data error, and #clampedAllowanceNote surfaces it on the payslip's calculation note.
        BigDecimal family = min(money(input.spouseAllowance()), new BigDecimal("60000.00"))
            .add(min(money(input.childAllowance()), childCap))
            .add(min(money(input.parentCareAllowance()), new BigDecimal("120000.00")))
            .add(min(money(input.disabledCareAllowance()), disabledCareCap))
            .add(min(money(input.maternityAllowance()), new BigDecimal("60000.00")));

        BigDecimal lifeInsurance = min(money(input.lifeInsuranceAllowance()), new BigDecimal("100000.00"));
        BigDecimal healthInsurance = min(money(input.healthInsuranceAllowance()), new BigDecimal("25000.00"));
        BigDecimal lifeAndHealth = min(lifeInsurance.add(healthInsurance), new BigDecimal("100000.00"));
        BigDecimal parentHealth = min(money(input.parentHealthInsuranceAllowance()), new BigDecimal("15000.00"));

        BigDecimal retirement = retirementAllowance(input, projectedAnnualIncome, taxYear);
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

    /**
     * The 500,000 retirement cluster, taken in a deliberate order.
     *
     * <p>กองทุนสำรองเลี้ยงชีพ comes FIRST (V93). It is deducted from the employee's pay at source
     * every month, whether or not they ever buy an RMF unit, so it is the one item in this cluster
     * they cannot choose to stop. If the cluster is going to run out, it must run out against the
     * voluntary purchases, not against a contribution already taken from their salary.
     */
    /** 30,000 per child, plus another 30,000 for each 2nd-or-later child born from พ.ศ. 2561. */
    private BigDecimal childAllowanceCap(PayrollTaxAllowanceInput input) {
        int children = Math.max(0, input.childCount());
        int doubled = Math.max(0, Math.min(input.childCountDouble(), children));
        return money(new BigDecimal("30000.00").multiply(BigDecimal.valueOf(children))
            .add(new BigDecimal("30000.00").multiply(BigDecimal.valueOf(doubled))));
    }

    /** 60,000 per disabled person cared for. */
    private BigDecimal disabledCareAllowanceCap(PayrollTaxAllowanceInput input) {
        return money(new BigDecimal("60000.00").multiply(BigDecimal.valueOf(Math.max(0, input.disabledCareCount()))));
    }

    private BigDecimal retirementAllowance(
        PayrollTaxAllowanceInput input, BigDecimal projectedAnnualIncome, int taxYear) {
        BigDecimal remainingCluster = new BigDecimal("500000.00");

        // กองทุนสำรองเลี้ยงชีพ: 15% of ค่าจ้าง, max 500,000, inside the cluster.
        BigDecimal providentFund = min(money(input.providentFundAllowance()),
            min(percentOf(projectedAnnualIncome, "0.15"), new BigDecimal("500000.00")));
        providentFund = min(providentFund, remainingCluster);
        remainingCluster = remainingCluster.subtract(providentFund);

        BigDecimal rmf = min(money(input.rmfAllowance()), min(percentOf(projectedAnnualIncome, "0.30"), new BigDecimal("500000.00")));
        rmf = min(rmf, remainingCluster);
        remainingCluster = remainingCluster.subtract(rmf);

        // SSF (กองทุนรวมเพื่อการออม) was deductible for ปีภาษี 2563-2567 only. Purchases from ปีภาษี
        // 2568 (Gregorian 2025) carry no deduction, so the declared figure is ignored from that year.
        // The stored column and past years are untouched -- they still have to explain past filings.
        // taxYear == 0 means "not supplied" (a legacy call site) and keeps the pre-V93 behaviour.
        BigDecimal ssf = taxYear >= SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR
            ? ZERO
            : min(money(input.ssfAllowance()), min(percentOf(projectedAnnualIncome, "0.30"), new BigDecimal("200000.00")));
        ssf = min(ssf, remainingCluster);
        remainingCluster = remainingCluster.subtract(ssf);

        BigDecimal pension = min(money(input.pensionInsuranceAllowance()), min(percentOf(projectedAnnualIncome, "0.15"), new BigDecimal("200000.00")));
        pension = min(pension, remainingCluster);

        return money(providentFund.add(rmf).add(ssf).add(pension));
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

    /** The result of one คำชี้แจง ภ.ง.ด.1 ข้อ 2.2 pass — see {@link #assessAnnualTax}. */
    private record AnnualTaxAssessment(
        BigDecimal expenseDeduction,
        AllowanceBreakdown allowances,
        BigDecimal taxableAnnualIncome,
        BigDecimal tax
    ) {}
}
