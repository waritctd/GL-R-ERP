package th.co.glr.hr.payroll.export;

import java.math.BigDecimal;

/**
 * One employee's data for the statutory export files, joining the already-computed
 * {@code hr.payroll_line} figures with the identity/bank/address fields the file formats need
 * (national id, SSN, tax id, Thai + English names, bank account, current address).
 *
 * <p>The monetary fields are read verbatim from {@code payroll_line} — no payroll/tax/SSO math is
 * recomputed here; these formatters only render already-approved numbers into the required layouts.
 */
public record PayrollExportRow(
    long employeeId,
    String employeeCode,
    String titleTh,        // hr.title.name_th (คำนำหน้า)
    String firstNameTh,
    String lastNameTh,
    String firstNameEn,
    String nationalId,     // employee_pii.national_id (13 digits)
    String taxId,          // employee_pii.tax_id (legacy 10-digit) — may be null
    String socialSecurityNo, // employee_pii.social_security_no — may be null (fall back to nationalId)
    String bankAccount,    // employee_bank_account.account_no (KBank credit account)
    String houseNo,
    String addressRest,    // building/soi/road/subdistrict/district/province joined
    String postalCode,
    BigDecimal netAmount,          // payroll_line.net_amount → KBank transfer amount
    BigDecimal grossTaxableIncome, // → PND1 income
    BigDecimal withholdingTax,     // → PND1 tax withheld
    BigDecimal ssoWageBase,        // capped SSO wage base
    BigDecimal socialSecurity      // employee SSO contribution (0 for directors)
) {}
