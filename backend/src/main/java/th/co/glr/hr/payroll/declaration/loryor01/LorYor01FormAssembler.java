package th.co.glr.hr.payroll.declaration.loryor01;

import java.math.BigDecimal;
import java.time.LocalDate;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01AddressPayload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01Details;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.LorYor01Address;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.MaritalState;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.SpousalStatus;

/**
 * Turns a stored declaration into the shape {@link LorYor01Renderer} prints.
 *
 * <p>Pure and static on purpose: everything it needs is a parameter, so it can be unit-tested
 * without a database, and the two callers (render a saved declaration, render an unsaved draft so
 * the employee can sign it) share one mapping instead of two that drift.
 *
 * <h2>The one rule that matters here: zero is not blank</h2>
 *
 * Every amount column is {@code NOT NULL DEFAULT 0}, so an item the employee never declared reads
 * back as {@code 0}, and printing it literally would put a "0.00" on every unused line of a
 * government form — asserting to the Revenue Department that the employee actively declared nothing
 * for fifteen separate categories. A paper form leaves those boxes empty. {@link #blankIfZero} is
 * what keeps the two apart, and {@code LorYor01RendererTest} pins that a blank declaration prints a
 * blank form.
 *
 * <p>Counts are treated the same way, and the renderer's own {@code count()} drops zero as well —
 * belt and braces, because a "0 คน" in a dotted count slot reads as badly as a 0.00 in a baht box.
 */
public final class LorYor01FormAssembler {

    private LorYor01FormAssembler() {
    }

    /**
     * @param allowances            the 21 shared allowance figures (the declaration's own amounts)
     * @param form                  the ล.ย.01-only detail; {@code null} is tolerated and prints blank
     * @param withholdingAgentName  ชื่อหน่วยงานผู้มีหน้าที่หักภาษี ณ ที่จ่าย, from employer config
     * @param socialSecurityForYear ข้อ 13 — DERIVED, never declared by the employee. Actual
     *                              contributions recorded for the tax year, so a form filed in
     *                              January legitimately shows nothing here yet.
     * @param declaredOn            วัน/เดือน/ปี ที่แจ้งรายการ — submission date, or today for a draft
     */
    public static LorYor01FormData assemble(
            PayrollTaxAllowanceInput allowances,
            LorYor01Details form,
            String withholdingAgentName,
            BigDecimal socialSecurityForYear,
            LocalDate declaredOn) {

        LorYor01Details f = form == null ? LorYor01Details.empty() : form;
        LorYor01AddressPayload a = f.address();

        return new LorYor01FormData(
                blankIfEmpty(withholdingAgentName),
                declaredOn,
                blankIfEmpty(f.taxpayerId()),
                blankIfEmpty(f.firstNameTh()),
                blankIfEmpty(f.lastNameTh()),
                address(a),
                enumOrNull(MaritalState.class, f.maritalState()),
                enumOrNull(SpousalStatus.class, f.spousalStatus()),
                f.spouseHasIncome(),
                // ข้อ 3 — the headcount box, then the two claimable tiers
                zeroToNull(f.childrenTotal()),
                zeroToNull(allowances.childCount()),
                blankIfZero(allowances.childAllowance()),
                zeroToNull(allowances.childCountDouble()),
                blankIfZero(f.childExtraAllowance()),
                // ข้อ 4
                Boolean.TRUE.equals(f.ownFatherSupported()),
                Boolean.TRUE.equals(f.ownMotherSupported()),
                blankIfZero(allowances.parentCareAllowance()),
                Boolean.TRUE.equals(f.spouseFatherSupported()),
                Boolean.TRUE.equals(f.spouseMotherSupported()),
                blankIfZero(f.spouseParentCareAllowance()),
                // ข้อ 5
                zeroToNull(allowances.disabledCareCount()),
                blankIfZero(allowances.disabledCareAllowance()),
                // ข้อ 6
                Boolean.TRUE.equals(f.ownFatherHealthInsured()),
                Boolean.TRUE.equals(f.ownMotherHealthInsured()),
                Boolean.TRUE.equals(f.spouseFatherHealthInsured()),
                Boolean.TRUE.equals(f.spouseMotherHealthInsured()),
                blankIfZero(allowances.parentHealthInsuranceAllowance()),
                // ข้อ 7-9
                blankIfZero(allowances.lifeInsuranceAllowance()),
                blankIfZero(allowances.healthInsuranceAllowance()),
                blankIfZero(f.providentFundAllowance()),
                // ข้อ 10
                blankIfZero(allowances.rmfAllowance()),
                blankIfEmpty(f.rmfSellerName()),
                // ข้อ 11 — LTF ceased to be deductible after tax year 2019. The form still prints the
                // line, so it prints; there is nothing to put in it and no column behind it (V137).
                null,
                null,
                // ข้อ 12-15
                blankIfZero(allowances.homeLoanInterestAllowance()),
                blankIfZero(socialSecurityForYear),
                blankIfZero(allowances.educationDonation()),
                blankIfEmpty(f.otherDonationNote()),
                blankIfZero(allowances.generalDonation()));
    }

    private static LorYor01Address address(LorYor01AddressPayload a) {
        if (a == null) {
            return LorYor01Address.empty();
        }
        return new LorYor01Address(
                blankIfEmpty(a.building()), blankIfEmpty(a.roomNo()), blankIfEmpty(a.floor()),
                blankIfEmpty(a.village()), blankIfEmpty(a.houseNo()), blankIfEmpty(a.moo()),
                blankIfEmpty(a.soi()), blankIfEmpty(a.junction()), blankIfEmpty(a.road()),
                blankIfEmpty(a.subDistrict()), blankIfEmpty(a.district()),
                blankIfEmpty(a.province()), blankIfEmpty(a.postalCode()));
    }

    /**
     * An amount of exactly zero means "this line was never declared" — the column is
     * {@code NOT NULL DEFAULT 0}, so there is no other way for the row to say it. Print nothing.
     */
    static BigDecimal blankIfZero(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }

    static Integer zeroToNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static String blankIfEmpty(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Tolerates an unrecognised stored value rather than throwing. The columns are CHECK-constrained
     * (V137) so this should be unreachable, but a form that prints with one box un-ticked is a far
     * better failure than a 500 that stops an employee filing at all.
     */
    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
