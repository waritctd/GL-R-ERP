package th.co.glr.hr.deposit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice) by filling dynamic cells
 * into the official template. Template cells with formulas (H/I columns) are
 * left intact so Excel auto-calculates amounts on open.
 */
@Component
public class RemainingInvoiceRenderer {

    private static final String TEMPLATE = "templates/remaining_invoice_template.xls";
    private static final int ITEM_START_ROW = 12; // 0-based (= row 13 in 1-based)
    private static final int MAX_ITEM_ROWS  = 28; // rows 13-40 available (row 41 reserved)

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    public byte[] toXlsx(RemainingInvoiceDto doc) throws Exception {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            // ── Header block ──────────────────────────────────────────────────
            // H7 = date (override =TODAY() formula)
            setStr(sh, 6, 7, thaiDate(doc.issueDate() != null ? doc.issueDate() : LocalDate.now()));
            // B7 = customer name + tax id
            setStr(sh, 6, 1, nullSafe(doc.customerName()));
            // H8 = document number
            setStr(sh, 7, 7, nullSafe(doc.docNumber()));
            // B8 = customer address (override broken VLOOKUP)
            setStr(sh, 7, 1, nullSafe(doc.customerAddress()));
            // H9 = reference (quotation / PO)
            if (doc.reference() != null && !doc.reference().isBlank()) {
                setStr(sh, 8, 7, doc.reference());
            }
            // B11 = project name
            setStr(sh, 10, 1, "Project : " + nullSafe(doc.projectName()));

            // ── Item rows ─────────────────────────────────────────────────────
            // The template ships pre-filled EXAMPLE content in this zone — a "พิเศษ" line at row
            // 13 (G13/J13) and a "หัก มัดจำ" deduction at row 15 — and it has a deliberate formula
            // GAP at row 14 (no I formula). Placing dynamic rows over that layout duplicated the
            // deduction and left #VALUE! in SUM(I11:I41). So we clear the whole dynamic zone first
            // (setBlank keeps each cell's style/format) and then write the amount (I) DIRECTLY,
            // independent of which template rows happen to carry a formula.
            for (int r = ITEM_START_ROW; r <= ITEM_START_ROW + MAX_ITEM_ROWS; r++) {
                for (int c = 0; c <= 9; c++) blank(sh, r, c);
            }
            List<RemainingInvoiceItemDto> items = doc.items();
            int count = Math.min(items.size(), MAX_ITEM_ROWS);
            for (int i = 0; i < count; i++) {
                RemainingInvoiceItemDto item = items.get(i);
                int r = ITEM_START_ROW + i;
                double price = item.unitPrice() != null ? item.unitPrice().doubleValue() : 0d;
                double qty = item.qty() != null ? item.qty().doubleValue() : 0d;
                setNum(sh, r, 0, i + 1);              // A ลำดับ
                setStr(sh, r, 1, item.description()); // B รายละเอียด
                setNum(sh, r, 2, qty);                // C จำนวน
                setStr(sh, r, 3, item.unit());        // D หน่วย
                setNum(sh, r, 4, price);              // E ราคา
                setStr(sh, r, 6, "Net");              // G ส่วนลด (display)
                setNum(sh, r, 7, price);              // H คงเหลือ (net)
                setNum(sh, r, 8, price * qty);        // I เป็นเงิน (direct)
            }

            // ── Deposit deduction row ─────────────────────────────────────────
            // Directly after the last item; C=-1 and I=-deposit render it as a deduction so
            // SUM(I11:I41) = items − deposit = remaining balance (VAT/total then compute on it).
            if (doc.depositAmount() != null && doc.depositAmount().compareTo(BigDecimal.ZERO) != 0) {
                int r = ITEM_START_ROW + count;
                double deposit = doc.depositAmount().doubleValue();
                String depositLabel = "หัก  มัดจำ"
                    + (doc.reference() != null && !doc.reference().isBlank()
                       ? "  " + doc.reference() : "");
                setStr(sh, r, 1, depositLabel);       // B
                setNum(sh, r, 2, -1.0);               // C = -1
                setNum(sh, r, 4, deposit);            // E = deposit
                setStr(sh, r, 6, "Net");              // G label
                setNum(sh, r, 7, deposit);            // H net
                setNum(sh, r, 8, -deposit);           // I = -deposit (direct)
            }

            // ── Summary rows auto-calculated by existing formulas ─────────────
            // I42 = SUM(I11:I41) includes item amounts and -depositAmount → remainder
            // I43 = I42 * 0.07 (VAT)
            // I44 = I42 + I43 (total payable)
            wb.setForceFormulaRecalculation(true);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        getOrCreate(sh, rowIdx, colIdx).setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        getOrCreate(sh, rowIdx, colIdx).setCellValue(value);
    }

    /** Blank an existing cell (content + formula), keeping its style — used to wipe the template's
     * pre-filled example rows before writing the real data. No-op if the cell doesn't exist. */
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
        // Writing a literal onto a template formula cell (B8 =VLOOKUP(#REF!…), H7 =TODAY(), or the
        // net-price H column) must clear the formula first: POI's setCellValue only sets the cached
        // result, so LibreOffice recalculates on PDF export and the literal is lost (→ #REF!/#N/A,
        // or the row-13 example's stale "พิเศษ"/J price). setBlank keeps the cell style/format.
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        return cell;
    }

    private String thaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    @SuppressWarnings("unused")
    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
}
