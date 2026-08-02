package th.co.glr.hr.payroll.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Builds the Social Security Office สปส.1-10 contribution file: a fixed-width 135-byte CP874 record
 * layout with a type-{@code 1} header and one type-{@code 2} detail record per insured employee.
 *
 * <p><b>Branches (ลำดับที่สาขา).</b> The branch code appears ONLY in the header record (positions
 * 12-17); a detail record carries no branch of its own. An employer registered with several branch
 * sequences therefore files them as several header blocks in one file, each immediately followed by
 * its own detail records and totalling only its own employees — that is the ยื่นรวมสาขา (สปส.1-10/1)
 * shape. Emitting a single header for everyone is what the e-service rejects with <i>"จำนวนสาขาที่
 * เลือกทำธุรกรรมไม่ตรงกับจำนวนสาขาที่พบในไฟล์"</i> when more than one branch is selected. Each row's
 * branch is {@code employee.sso_branch_code}, falling back to {@code app.payroll.employer.sso-branch}
 * when unset, and blocks are emitted in ascending branch order so the file is deterministic.
 *
 * <p>Field layout follows the published สปส.1-10 text-file spec (135 chars, header + detail). Unlike
 * the KBank and PND1 files there is <b>no golden sample</b> to pin the exact byte conventions, so the
 * following are best-effort and MUST be validated by uploading to the SSO e-service (which reports
 * format errors) before real submission. They are deliberately isolated here so a fix is a one-line
 * change:
 * <ul>
 *   <li><b>Amounts</b> — rendered as satang (baht×100), zero-padded left, no decimal point. This is
 *       an UNCONFIRMED convention (no public spec states it, and there is no golden sample) kept
 *       isolated in {@link Cp874#satang} for exactly this reason — if the SSO e-service actually
 *       wants whole-baht fields, the fix is contained to that one method.</li>
 *   <li><b>Rate</b> — percent×100, e.g. 5% → {@code "0500"}.</li>
 *   <li><b>Wage (เงินค่าจ้างทั้งสิ้น)</b> — the wage actually PAID, uncapped ({@code
 *       payroll_line.sso_wage_gross}, {@code PayrollExportRow#ssoWageGross}), falling back to the
 *       capped {@code sso_wage_base} when gross is {@code null} (rows processed before V123). Fixed
 *       2026-08 — GL&amp;R's actual June 2026 filing reports the uncapped figure (one employee shows
 *       ค่าจ้าง 124,849 with เงินสมทบ 875); the capped base understates every employee at the SSO
 *       ceiling, which is most of the workforce.</li>
 *   <li><b>เงินสมทบ (contribution) rounding</b> — rounded {@link RoundingMode#HALF_UP} to whole baht
 *       PER INSURED PERSON, then summed for the block total — <b>พ.ร.บ.ประกันสังคม พ.ศ. 2533 มาตรา 46
 *       วรรคท้าย</b>: "สำหรับเศษของเงินสมทบที่มีจำนวนตั้งแต่ห้าสิบสตางค์ขึ้นไปให้นับเป็นหนึ่งบาท
 *       ถ้าน้อยกว่านั้นให้ปัดทิ้ง" ("...applied per insured person"). The employer portion mirrors the
 *       already-rounded employee portion, matching §33. This is a FILING-ONLY rounding: it does not
 *       touch {@code payroll_line.social_security} or the payslip deduction, which keeps deducting
 *       the unrounded amount (e.g. ฿562.50) while the employer remits the filed whole-baht figure
 *       (฿563) — a known, deliberate divergence; see the PR body.</li>
 *   <li><b>ค่าจ้าง whole-baht rounding</b> — also rounded {@link RoundingMode#HALF_UP} to 0 decimal
 *       places per person. Unlike the contribution rounding above, this is NOT stated by any statute
 *       this codebase has found — it is inferred from GL&amp;R's reference filing, where all 27
 *       ค่าจ้าง figures happened to be integral already (a monthly-salary company rarely pays satang).
 *       If a future wage genuinely carries satang, this rounds it, which is unconfirmed but matches
 *       every observed real filing.</li>
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
        // TreeMap: blocks in ascending branch order. Within a block the ArrayList preserves the
        // caller's order, which is findExportRows' ORDER BY employee_code.
        Map<String, List<PayrollExportRow>> byBranch = new TreeMap<>();
        for (PayrollExportRow row : rows) {
            if (!isPositive(row.socialSecurity())) {
                continue; // directors: no §33 contribution, not insured under this filing
            }
            byBranch.computeIfAbsent(branchOf(row, employer), key -> new ArrayList<>()).add(row);
        }
        if (byBranch.isEmpty()) {
            // Nobody insured this period. Grouping would otherwise yield a zero-record file; keep the
            // pre-branch behaviour of one empty header at the employer's own branch.
            byBranch.put(digits(employer.getSsoBranch()), List.of());
        }

        List<byte[]> records = new ArrayList<>();
        for (Map.Entry<String, List<PayrollExportRow>> entry : byBranch.entrySet()) {
            List<PayrollExportRow> insured = entry.getValue();
            BigDecimal totalWage = BigDecimal.ZERO;
            BigDecimal totalEmployee = BigDecimal.ZERO;
            List<byte[]> details = new ArrayList<>();
            for (PayrollExportRow row : insured) {
                // Defect 2: ค่าจ้าง is the uncapped wage actually paid, falling back to the capped
                // base for pre-V123 rows (see this class's javadoc). Defect 3: both figures round to
                // whole baht PER PERSON before they ever reach a total — the block total below is
                // therefore the SUM of these already-rounded per-person amounts, not a rounded sum
                // of raw amounts (see the golden reconciliation test for why that distinction is
                // load-bearing: the two can differ).
                BigDecimal wage = wholeBaht(row.ssoWageGross() != null ? row.ssoWageGross() : row.ssoWageBase());
                BigDecimal contribution = wholeBaht(row.socialSecurity());
                totalWage = totalWage.add(wage);
                totalEmployee = totalEmployee.add(contribution);
                details.add(detail(row, wage, contribution));
            }
            // §33: the employer matches the employee's contribution.
            BigDecimal totalEmployer = totalEmployee;
            BigDecimal totalContribution = totalEmployee.add(totalEmployer);

            records.add(header(employer, entry.getKey(), payrollMonth, payDate, insured.size(),
                totalWage, totalContribution, totalEmployee, totalEmployer));
            records.addAll(details);
        }
        return Cp874.file(records);
    }

    /**
     * The employee's own SSO branch, falling back to the employer default when unset — so an
     * installation that has never assigned branches keeps producing exactly one block, as before.
     */
    private String branchOf(PayrollExportRow row, AppProperties.Employer employer) {
        String branch = digits(row.ssoBranchCode());
        return branch.isEmpty() ? digits(employer.getSsoBranch()) : branch;
    }

    private byte[] header(AppProperties.Employer employer, String branch,
                          LocalDate payrollMonth, LocalDate payDate,
                          int count, BigDecimal totalWage, BigDecimal totalContribution,
                          BigDecimal employeePortion, BigDecimal employerPortion) {
        String establishment = employer.getEstablishmentName() == null || employer.getEstablishmentName().isBlank()
            ? employer.getCompanyNameTh()
            : employer.getEstablishmentName();
        return Cp874.record(RECORD_WIDTH,
            Cp874.bytes("1"),
            account(employer.getSsoEmployerAccount(), 10),
            Cp874.zpad(branch, 6),
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

    /**
     * Rounds to whole baht, {@link RoundingMode#HALF_UP} — มาตรา 46 วรรคท้าย for เงินสมทบ (rounding
     * is explicit in the statute); inferred from the reference filing for ค่าจ้าง (see this class's
     * javadoc). Applied PER PERSON, before any total accumulates — see the golden reconciliation
     * test for why summing raw and rounding once is a different, wrong number.
     */
    private BigDecimal wholeBaht(BigDecimal value) {
        return orZero(value).setScale(0, RoundingMode.HALF_UP);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
