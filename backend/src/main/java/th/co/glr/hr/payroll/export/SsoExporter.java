package th.co.glr.hr.payroll.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Builds the Social Security Office สปส.1-10 contribution file: a fixed-width 135-byte CP874 record
 * layout with a type-{@code 1} header and one type-{@code 2} detail record per insured employee.
 *
 * <p>Field layout follows the published สปส.1-10 text-file spec (135 chars, header + detail). Unlike
 * the KBank and PND1 files there is <b>no golden sample</b> to pin the exact byte conventions, so the
 * following are best-effort and MUST be validated by uploading to the SSO e-service (which reports
 * format errors) before real submission. They are deliberately isolated here so a fix is a one-line
 * change:
 * <ul>
 *   <li><b>Amounts</b> — rendered as satang (baht×100), zero-padded left, no decimal point.</li>
 *   <li><b>Rate</b> — percent×100, e.g. 5% → {@code "0500"}.</li>
 *   <li><b>Wage</b> — the capped SSO wage base ({@code sso_wage_base}), so wage×rate = contribution.</li>
 *   <li><b>Title code</b> — left blank (spaces); no reliable SSO 3-char code map yet.</li>
 *   <li><b>Wage period / pay date year</b> — Gregorian 2-digit.</li>
 * </ul>
 * Only employees with a positive contribution appear (directors, whose SSO is 0, are excluded).
 */
@Component
public class SsoExporter {
    private static final int RECORD_WIDTH = 135;
    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("ddMMyy", Locale.US);
    private static final DateTimeFormatter MMYY = DateTimeFormatter.ofPattern("MMyy", Locale.US);

    /**
     * @param rows         the period's payroll lines
     * @param employer     employer SSO registration constants
     * @param payrollMonth the wage period (drives the MMyy field)
     * @param payDate      the contribution payment date (HR-picked; defaults to the 26th)
     */
    public byte[] export(List<PayrollExportRow> rows, AppProperties.Employer employer,
                        LocalDate payrollMonth, LocalDate payDate) {
        List<PayrollExportRow> insured = rows.stream()
            .filter(row -> isPositive(row.socialSecurity()))
            .toList();

        BigDecimal totalWage = BigDecimal.ZERO;
        BigDecimal totalEmployee = BigDecimal.ZERO;
        List<byte[]> details = new ArrayList<>();
        for (PayrollExportRow row : insured) {
            BigDecimal wage = orZero(row.ssoWageBase());
            BigDecimal contribution = orZero(row.socialSecurity());
            totalWage = totalWage.add(wage);
            totalEmployee = totalEmployee.add(contribution);
            details.add(detail(row, wage, contribution));
        }
        // §33: the employer matches the employee's contribution.
        BigDecimal totalEmployer = totalEmployee;
        BigDecimal totalContribution = totalEmployee.add(totalEmployer);

        List<byte[]> records = new ArrayList<>();
        records.add(header(employer, payrollMonth, payDate, insured.size(),
            totalWage, totalContribution, totalEmployee, totalEmployer));
        records.addAll(details);
        return Cp874.file(records);
    }

    private byte[] header(AppProperties.Employer employer, LocalDate payrollMonth, LocalDate payDate,
                          int count, BigDecimal totalWage, BigDecimal totalContribution,
                          BigDecimal employeePortion, BigDecimal employerPortion) {
        String establishment = employer.getEstablishmentName() == null || employer.getEstablishmentName().isBlank()
            ? employer.getCompanyNameTh()
            : employer.getEstablishmentName();
        return Cp874.record(RECORD_WIDTH,
            Cp874.bytes("1"),
            account(employer.getSsoEmployerAccount(), 10),
            Cp874.zpad(digits(employer.getSsoBranch()), 6),
            Cp874.bytes(payDate.format(DDMMYY)),
            Cp874.bytes(payrollMonth.format(MMYY)),
            Cp874.rpad(establishment, 45),
            Cp874.zpad(rateTimes100(employer.getSsoRatePercent()), 4),
            Cp874.zpad(count, 6),
            Cp874.satang(totalWage, 15),
            Cp874.satang(totalContribution, 14),
            Cp874.satang(employeePortion, 12),
            Cp874.satang(employerPortion, 12));
    }

    private byte[] detail(PayrollExportRow row, BigDecimal wage, BigDecimal contribution) {
        return Cp874.record(RECORD_WIDTH,
            Cp874.bytes("2"),
            Cp874.zpad(ssn(row), 13),
            Cp874.rpad("", 3),                 // title code — left blank pending SSO code map
            Cp874.rpad(row.firstNameTh(), 30),
            Cp874.rpad(row.lastNameTh(), 35),
            Cp874.satang(wage, 14),
            Cp874.satang(contribution, 12),
            Cp874.spaces(27));
    }

    /** SSN = the employee's social-security number, falling back to the national id. */
    private String ssn(PayrollExportRow row) {
        String n = digits(row.socialSecurityNo());
        return n.isEmpty() ? digits(row.nationalId()) : n;
    }

    private long rateTimes100(String ratePercent) {
        BigDecimal rate = ratePercent == null || ratePercent.isBlank()
            ? BigDecimal.valueOf(5)
            : new BigDecimal(ratePercent.trim());
        return rate.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private byte[] account(String account, int width) {
        return Cp874.zpad(digits(account), width);
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
