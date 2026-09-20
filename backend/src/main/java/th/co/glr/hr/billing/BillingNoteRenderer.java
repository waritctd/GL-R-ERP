package th.co.glr.hr.billing;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ThaiText;

/**
 * Renders ใบวางบิล (billing note, GLA-99 step 3) into the official template by filling dynamic
 * cells. The template's own {@code SUM(F12:F26)} total formula is REPLACED with a computed literal
 * (F4, GLA-99 step 3 round 1 review) rather than left for Excel/LibreOffice to recalculate on
 * open — a formula is only correct for a reader that recalculates it, and this document's printed
 * total must never be able to disagree with its own baht-text words for a reader that does not.
 * See {@link #toXlsx}'s own total/baht-text block.
 *
 * <p><b>ONE template serves BOTH types.</b> The owner's reference form ({@code
 * ~/Downloads/ฟอร์มใบวางบิล.xls}) ships two sheets — ค่าสินค้า (goods) and ค่าขนส่ง (freight) — that
 * are the IDENTICAL layout with different sample data; only the ค่าขนส่ง sheet is blank of real
 * customer data, so {@code billing_note_template.xls} was built from that one sheet alone (see its
 * own extraction, GLA-99 step 3 PR body). {@link BillingNoteDocumentDto#type} therefore never
 * selects a different template — it is a business field on the row, not a rendering switch.
 *
 * <p><b>Template layout, measured cell-by-cell with POI against the real ค่าขนส่ง sheet</b>
 * (0-based rows/cols below; 1-based in the template's own UI):
 * <ul>
 *   <li>Row 6 (0-based): ชื่อ label at B7; customer name value written starting C7.</li>
 *   <li>Row 7 (0-based): เลขที่ผู้เสียภาษี label at C8, value at D8; วันที่ label at F8, value
 *       (bill date) at G8.</li>
 *   <li>Row 8-9 (0-based): ที่อยู่ label at B9, value (line 1) at C9, line 2 at C10;
 *       เลขที่อ้างอิง label at F9, value (this note's OWN doc number, per the spec's own field
 *       description) at G9.</li>
 *   <li>Row 10 (0-based): column headers ลำดับ/เลขที่/วันที่/วันครบกำหนด/จำนวนเงิน/หมายเหตุ,
 *       columns B-G — static, never overwritten.</li>
 *   <li>Rows 11-25 (0-based), 15 rows: the line-item zone this class writes into. Measured from
 *       the template's OWN {@code SUM(F12:F26)} formula (1-based), not trusted from any summary
 *       count — see {@link #MAX_LINE_ROWS}'s own comment for why 15, not the 14 an earlier
 *       description estimated.</li>
 *   <li>Row 26 (0-based): total — F27's own {@code SUM(F12:F26)} formula is REPLACED with a
 *       computed literal (F4, GLA-99 step 3 round 1 review — a formula cell is only correct for a
 *       reader that recalculates it, and this document's own total must never disagree with its
 *       baht-text words for one that does not); B27 (merged B27:E27) gets this class's own
 *       computed {@link ThaiText#bahtText} literal of that SAME total, replacing the template's
 *       {@code _xlfn.BAHTTEXT(F27)} formula for the identical reason (same discipline {@code
 *       RemainingInvoiceRenderer#thaiDate} already documents for computed display text).</li>
 *   <li>Rows 28-30 (0-based): ผู้วางบิล/ผู้รับวางบิล/วันนัดชำระเงิน signature block with blank
 *       dotted lines — STATIC, never written. The rendered file always ships this block blank for
 *       physical pen signing; {@code sales.billing_note.received_by_name}/{@code
 *       payment_appointment_date} are this system's OWN record of a later signature, read back by
 *       a screen (GLA-99 step 4), never re-printed onto this file.</li>
 *   <li>Row 31 (0-based): "โปรดจ่ายเช็คในนาม …" — static footer, never written.</li>
 *   <li>Row 32 (0-based): "หมายเหตุ : " label at B33; the note's own free-text {@code note} value
 *       written at C33.</li>
 *   <li>Row 33 (0-based): "ราคานี้เป็นราคาที่รวมภาษีแล้ว" — static footer, never written (this
 *       document carries no separate VAT line; every line amount is already VAT-inclusive).</li>
 * </ul>
 */
@Component
public class BillingNoteRenderer {

    private static final String TEMPLATE = "templates/billing_note_template.xls";
    private static final int LINE_START_ROW = 11; // 0-based (= row 12 in 1-based)
    // Measured directly against the template's own SUM(F12:F26) formula (1-based rows 12-26 = 15
    // rows), NOT the "14 rows max" an earlier summary of the owner's form estimated — see this
    // class's own Javadoc for the measurement. A caller sizing a draft against this constant gets
    // the real ceiling the template can actually hold.
    public static final int MAX_LINE_ROWS = 15;
    private static final int TOTAL_ROW = 26; // 0-based (= row 27 in 1-based)

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    public byte[] toXlsx(BillingNoteDto doc) throws Exception {
        List<BillingNoteLineRenderDto> lines = doc.lines() != null ? doc.lines() : List.of();
        if (lines.size() > MAX_LINE_ROWS) {
            // Defensive-only: BillingNoteService gates this BEFORE calling the renderer (409, Thai
            // message naming the limit — spec: "refuse beyond it ... no silent truncation"). Kept
            // here so a future direct caller of this class cannot silently drop rows.
            throw new IllegalStateException(
                "billing note needs " + lines.size() + " rows but the template only has " + MAX_LINE_ROWS);
        }

        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl)) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            // The item/deposit-row zone (rows 11-25) is entirely BLANK in the source ค่าขนส่ง
            // sheet — the template extraction (see this class's own Javadoc) never creates a cell
            // for a blank source cell, so these rows carry no borrowed number format to clone the
            // way RemainingInvoiceRenderer clones its own row 13's style. A plain "#,##0.00"
            // accounting format applied here, once, is what makes จำนวนเงิน print as money instead
            // of a bare double's "100000"/"26977.2".
            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            // ── Header block ──────────────────────────────────────────────────
            setStr(sh, 6, 2, nullSafe(doc.customerName())
                + (doc.customerBranch() != null && !doc.customerBranch().isBlank() ? " (" + doc.customerBranch() + ")" : ""));
            setStr(sh, 7, 3, nullSafe(doc.customerTaxId()));
            // Issuance freezes the bill date; an undated draft leaves this field blank.
            setStr(sh, 7, 6, thaiDate(doc.billDate()));
            String[] addressLines = splitAddress(doc.customerAddress());
            setStr(sh, 8, 2, addressLines[0]);
            setStr(sh, 9, 2, addressLines[1]);
            setStr(sh, 8, 6, nullSafe(doc.docNumber() != null ? doc.docNumber() : "(ร่าง)"));

            // ── Line rows ─────────────────────────────────────────────────────
            for (int r = LINE_START_ROW; r < LINE_START_ROW + MAX_LINE_ROWS; r++) {
                for (int c = 1; c <= 6; c++) {
                    blank(sh, r, c);
                }
            }
            for (int i = 0; i < lines.size(); i++) {
                BillingNoteLineRenderDto line = lines.get(i);
                int r = LINE_START_ROW + i;
                setNum(sh, r, 1, i + 1);                              // B ลำดับ
                setStr(sh, r, 2, nullSafe(line.docNumber()));         // C เลขที่
                setStr(sh, r, 3, thaiDateOrBlank(line.docDate()));    // D วันที่
                setStr(sh, r, 4, thaiDateOrBlank(line.dueDate()));    // E วันครบกำหนด
                Cell amountCell = getOrCreate(sh, r, 5);              // F จำนวนเงิน
                amountCell.setCellStyle(moneyStyle);
                amountCell.setCellValue(line.amount() != null ? line.amount().doubleValue() : 0d);
                setStr(sh, r, 6, nullSafe(line.note()));              // G หมายเหตุ
            }

            // ── Total + baht-text — BOTH derived from the SAME `total`, not from F27's own SUM
            // formula (F4, Opus review, GLA-99 step 3 round 1): the template's SUM(F12:F26) formula
            // is REPLACED with this literal, because a consumer that opens the file without
            // recalculating formulas (most non-Excel readers) would otherwise show F27's stale
            // cached template value while B27's baht-text literal shows the real total — two
            // numbers that can disagree for exactly the readers this discipline exists to protect
            // against. One computed value, written to both cells, cannot disagree with itself.
            //
            // S5 (Opus review, GLA-99 step 3 round 2) — F4's own fix regressed F27's STYLE: it
            // applied `moneyStyle` (a fresh, borderless, default-font style meant only for the 15
            // blank line-amount cells, which borrow no style of their own from the source sheet —
            // see that style's own comment) to F27 too, discarding the template's own borders and
            // Thai font on the printed total cell. `getOrCreate` already blanks a FORMULA cell's
            // stored formula before a literal write; F27's STYLE index is untouched by that blank,
            // so leaving it alone here is enough to keep exactly what the template shipped with.
            BigDecimal total = doc.totalAmount() != null ? doc.totalAmount() : sumAmounts(lines);
            Cell totalCell = getOrCreate(sh, TOTAL_ROW, 5); // F27 — NO setCellStyle here (S5)
            totalCell.setCellValue(total.doubleValue());
            setStr(sh, TOTAL_ROW, 1, ThaiText.bahtText(total));

            // ── หมายเหตุ footer (single free-text line, not the numbered note block the
            // remaining-invoice template uses — this template has exactly one) ─────────────────
            setStr(sh, 32, 2, nullSafe(doc.note()));

            // Force fit-to-one-page explicitly rather than trusting the template's own copied
            // PrintSetup fidelity (the extraction — see this class's own Javadoc — measured the
            // source's print area but a programmatic sheet copy does not always reproduce Excel's
            // own "fit to 1 page" checkbox behaviour bit-for-bit). The whole document is a single
            // header + up to 15 line rows + one signature block — it must always fit one page.
            sh.setFitToPage(true);
            sh.setAutobreaks(true);
            sh.getPrintSetup().setFitWidth((short) 1);
            sh.getPrintSetup().setFitHeight((short) 1);

            wb.setForceFormulaRecalculation(true);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    private BigDecimal sumAmounts(List<BillingNoteLineRenderDto> lines) {
        return lines.stream().map(l -> l.amount() != null ? l.amount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** ที่อยู่ prints on two lines (spec: "ที่อยู่ (2 lines)") — a stored single-string address is
     * split on its first newline if it has one, else the whole string goes on line 1 and line 2 is
     * blank. Mirrors nothing upstream exactly because no existing renderer has a genuinely 2-line
     * address field; this is the simplest split that never loses text. */
    private String[] splitAddress(String address) {
        if (address == null || address.isBlank()) return new String[] {"", ""};
        int nl = address.indexOf('\n');
        if (nl < 0) return new String[] {address, ""};
        return new String[] {address.substring(0, nl), address.substring(nl + 1).replace('\n', ' ')};
    }

    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        getOrCreate(sh, rowIdx, colIdx).setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        getOrCreate(sh, rowIdx, colIdx).setCellValue(value);
    }

    private void blank(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) return;
        Cell cell = row.getCell(colIdx);
        if (cell != null) cell.setBlank();
    }

    private Cell getOrCreate(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) row = sh.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        // A literal write onto a template formula cell must clear the formula first — POI's
        // setCellValue only sets the cached result, so a later recalculation would discard it.
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        return cell;
    }

    private String thaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private String thaiDateOrBlank(LocalDate d) {
        return d == null ? "" : thaiDate(d);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
}
