package th.co.glr.hr.payroll.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Builds the Social Security Office สปส.1-10 contribution submission as an <b>Excel workbook</b>:
 * one sheet per branch sequence, each holding a row per insured employee under the six columns the
 * SSO e-service reads — เลขบัตรประชาชน / คำนำหน้า / ชื่อ / สกุล / ค่าจ้าง / เงินสมทบ.
 *
 * <p><b>This replaced a fixed-width CP874 .txt exporter on 2026-08-31 (owner ruling).</b> Both
 * formats are accepted by the e-service, but GL&amp;R has always filed the workbook — the .txt was
 * built from a published spec with no golden sample and was never actually submitted, so it was
 * pure unvalidated surface area. The layout here is pinned to GL&amp;R's real June 2026 filing
 * ({@code SSO-twotabjube.xls}: sheets {@code 000000} and {@code 110001}), whose per-branch
 * contribution totals — ฿13,725.00 and ฿8,863.00 — reconcile to the SSO's own สปส.1-10/1 receipt.
 * See {@code SsoExporterTest#juneGoldenReconciliation...} and the August companion.
 *
 * <p><b>Branches (ลำดับที่สาขา).</b> An employer registered with several branch sequences files each
 * as its own sheet, named for the branch code, containing only that branch's insured — the
 * ยื่นรวมสาขา (สปส.1-10/1) shape. Each row's branch is {@code employee.sso_branch_code}, falling back
 * to {@code app.payroll.employer.sso-branch} when unset, and sheets are emitted in ascending branch
 * order so the file is deterministic. Within a sheet, rows are ordered by เลขบัตรประชาชน ascending,
 * matching the reference filing.
 *
 * <p>Conventions, all pinned to the reference filing rather than to a spec:
 * <ul>
 *   <li><b>Amounts</b> — real numeric cells (not text), so the e-service reads values rather than
 *       parsing strings. No employer/period/rate fields appear anywhere in the workbook: the
 *       e-service already knows those from the account the file is uploaded under, which is why
 *       this exporter needs no employer identity beyond the fallback branch code.</li>
 *   <li><b>ค่าจ้าง (เงินค่าจ้างทั้งสิ้น)</b> — the wage actually PAID, <b>uncapped</b> ({@code
 *       payroll_line.sso_wage_gross}), falling back to the capped {@code sso_wage_base} when gross
 *       is {@code null} (rows processed before V123). The capped base understates every employee at
 *       the ฿17,500 ceiling, which is most of the workforce — GL&amp;R's June filing reports one
 *       employee at ค่าจ้าง 124,849 against a เงินสมทบ of 875.</li>
 *   <li><b>เงินสมทบ rounding</b> — {@link RoundingMode#HALF_UP} to whole baht PER INSURED PERSON —
 *       <b>พ.ร.บ.ประกันสังคม พ.ศ. 2533 มาตรา 46 วรรคท้าย</b>: "สำหรับเศษของเงินสมทบที่มีจำนวนตั้งแต่
 *       ห้าสิบสตางค์ขึ้นไปให้นับเป็นหนึ่งบาท ถ้าน้อยกว่านั้นให้ปัดทิ้ง". This is FILING-ONLY: it does not touch
 *       {@code payroll_line.social_security} or the payslip deduction, which keep the unrounded
 *       amount (e.g. ฿562.50) while the employer remits the filed whole-baht ฿563.</li>
 *   <li><b>ค่าจ้าง rounding</b> — also HALF_UP to whole baht per person (owner ruling 2026-08-31).
 *       Unlike the contribution rounding this is NOT stated by any statute this codebase has found;
 *       it is inferred from the reference filing, where all 27 ค่าจ้าง figures were integral (a
 *       monthly-salary company rarely pays satang). If a future wage genuinely carries satang this
 *       rounds it, which is unconfirmed but matches every observed real filing.</li>
 *   <li><b>คำนำหน้า</b> — the Thai title word ({@code hr.title.name_th}: นาย / นาง / นางสาว) exactly as
 *       the reference filing spells it, NOT an SSO 3-char code. The .txt format wanted a code and
 *       had none, so it shipped three spaces; the workbook wants the word, which the ERP has.</li>
 * </ul>
 * Only employees with a positive contribution appear — directors, whose SSO is 0, are not insured
 * under this filing and are excluded.
 */
@Component
public class SsoExporter {
    private static final List<String> HEADERS =
        List.of("เลขบัตรประชาชน", "คำนำหน้า", "ชื่อ", "สกุล", "ค่าจ้าง", "เงินสมทบ");
    /** Widths copied from the reference filing's own sheet, in POI's 1/256th-of-a-character units. */
    private static final int[] COLUMN_WIDTHS = {16, 16, 20, 20, 17, 17};
    private static final String ID_FORMAT = "0000000000000";
    private static final String MONEY_FORMAT = "0.00";

    /**
     * @param rows     the period's payroll lines
     * @param employer employer SSO registration — only {@code ssoBranch} is read, as the fallback
     *                 sheet for employees with no {@code sso_branch_code} of their own
     */
    public byte[] export(List<PayrollExportRow> rows, AppProperties.Employer employer) {
        // TreeMap: sheets in ascending branch order. Within a branch, order by national id ascending
        // to match the reference filing (findExportRows' own ORDER BY is employee_code, which is a
        // different order — the workbook is re-sorted here rather than at the query, so this class
        // owns its layout end to end).
        Map<String, List<PayrollExportRow>> byBranch = new TreeMap<>();
        for (PayrollExportRow row : rows) {
            if (!isPositive(row.socialSecurity())) {
                continue; // directors: no §33 contribution, not insured under this filing
            }
            byBranch.computeIfAbsent(branchOf(row, employer), key -> new ArrayList<>()).add(row);
        }
        if (byBranch.isEmpty()) {
            // Nobody insured this period. Emit a single header-only sheet at the employer's own
            // branch rather than a workbook with no sheets at all, which Excel refuses to open.
            byBranch.put(digits(employer.getSsoBranch()), List.of());
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat formats = workbook.createDataFormat();
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle idStyle = numberStyle(workbook, formats, ID_FORMAT);
            CellStyle moneyStyle = numberStyle(workbook, formats, MONEY_FORMAT);

            for (Map.Entry<String, List<PayrollExportRow>> entry : byBranch.entrySet()) {
                Sheet sheet = workbook.createSheet(sheetName(entry.getKey()));
                writeHeaderRow(sheet, headerStyle);

                List<PayrollExportRow> insured = new ArrayList<>(entry.getValue());
                insured.sort(Comparator.comparing(this::ssn));

                int rowIndex = 1;
                for (PayrollExportRow row : insured) {
                    // ค่าจ้าง is the uncapped wage actually paid, falling back to the capped base for
                    // pre-V123 rows; both figures round to whole baht PER PERSON (see the javadoc).
                    BigDecimal wage =
                        wholeBaht(row.ssoWageGross() != null ? row.ssoWageGross() : row.ssoWageBase());
                    BigDecimal contribution = wholeBaht(row.socialSecurity());
                    writeDetailRow(sheet.createRow(rowIndex++), row, wage, contribution, idStyle, moneyStyle);
                }
                for (int column = 0; column < COLUMN_WIDTHS.length; column++) {
                    sheet.setColumnWidth(column, COLUMN_WIDTHS[column] * 256);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build the สปส.1-10 workbook", e);
        }
    }

    private void writeHeaderRow(Sheet sheet, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int column = 0; column < HEADERS.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(HEADERS.get(column));
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeDetailRow(Row row, PayrollExportRow source, BigDecimal wage,
                                BigDecimal contribution, CellStyle idStyle, CellStyle moneyStyle) {
        String id = ssn(source);
        Cell idCell = row.createCell(0);
        if (id.isEmpty()) {
            // No national id AND no social_security_no. Leave the cell blank rather than writing a
            // fabricated 0, so the e-service rejects the row instead of filing it under id zero.
            idCell.setBlank();
        } else {
            idCell.setCellValue(Double.parseDouble(id));
        }
        idCell.setCellStyle(idStyle);

        row.createCell(1).setCellValue(orBlank(source.titleTh()));
        row.createCell(2).setCellValue(orBlank(source.firstNameTh()));
        row.createCell(3).setCellValue(orBlank(source.lastNameTh()));

        Cell wageCell = row.createCell(4);
        wageCell.setCellValue(wage.doubleValue());
        wageCell.setCellStyle(moneyStyle);

        Cell contributionCell = row.createCell(5);
        contributionCell.setCellValue(contribution.doubleValue());
        contributionCell.setCellStyle(moneyStyle);
    }

    /**
     * The employee's own SSO branch, falling back to the employer default when unset — so an
     * installation that has never assigned branches keeps producing exactly one sheet.
     */
    private String branchOf(PayrollExportRow row, AppProperties.Employer employer) {
        String branch = digits(row.ssoBranchCode());
        return branch.isEmpty() ? digits(employer.getSsoBranch()) : branch;
    }

    /**
     * Excel refuses a blank sheet name, so an employer whose fallback branch is itself unset (or
     * non-numeric) would otherwise blow up here rather than at the export guard. {@code
     * PayrollService} already refuses the export when {@code sso-branch} is blank; this is the
     * belt-and-braces so a future caller that skips that guard still produces a valid workbook.
     */
    private String sheetName(String branch) {
        return branch.isEmpty() ? "000000" : branch;
    }

    /**
     * The เลขบัตรประชาชน column keys on the <b>national id</b>, falling back to {@code
     * social_security_no} only when an employee has no national id on file.
     *
     * <p>For modern Thai records the two are the same 13 digits, but any employee whose {@code
     * social_security_no} holds something else (a legacy SSO number, or a stale value) would be
     * filed under a number the SSO cannot match to the person. National id is the field the SSO
     * actually reconciles against, so it wins — owner ruling 2026-08-03, carried over from the .txt
     * exporter this class replaced.
     */
    private String ssn(PayrollExportRow row) {
        String n = digits(row.nationalId());
        return n.isEmpty() ? digits(row.socialSecurityNo()) : n;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle numberStyle(XSSFWorkbook workbook, DataFormat formats, String pattern) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(formats.getFormat(pattern));
        return style;
    }

    private String orBlank(String value) {
        return value == null ? "" : value;
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Rounds to whole baht, {@link RoundingMode#HALF_UP} — มาตรา 46 วรรคท้าย for เงินสมทบ (rounding is
     * explicit in the statute); inferred from the reference filing for ค่าจ้าง (see this class's
     * javadoc). Applied PER PERSON: the e-service totals the column itself, so what it sums must be
     * the already-rounded per-person figures, never a rounded grand total — the two can differ.
     */
    private BigDecimal wholeBaht(BigDecimal value) {
        return orZero(value).setScale(0, RoundingMode.HALF_UP);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
