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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice) by filling dynamic cells
 * into the official template. Template cells with formulas (H/I columns) are
 * left intact so Excel auto-calculates amounts on open.
 */
@Component
public class RemainingInvoiceRenderer {

    private static final String TEMPLATE = "templates/remaining_invoice_template.xlsx";
    private static final int ITEM_START_ROW = 12; // 0-based (= row 13 in 1-based)
    private static final int MAX_ITEM_ROWS  = 28; // rows 13-40 available (row 41 reserved)

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    public byte[] toXlsx(RemainingInvoiceDto doc) throws Exception {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = new XSSFWorkbook(tpl);
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
            List<RemainingInvoiceItemDto> items = doc.items();
            int count = Math.min(items.size(), MAX_ITEM_ROWS);
            for (int i = 0; i < count; i++) {
                RemainingInvoiceItemDto item = items.get(i);
                int rowIdx = ITEM_START_ROW + i;
                setNum(sh, rowIdx, 0, i + 1);               // A: seq
                setStr(sh, rowIdx, 1, item.description());   // B: description
                setNum(sh, rowIdx, 2, item.qty().doubleValue()); // C: qty
                setStr(sh, rowIdx, 3, item.unit());           // D: unit
                setNum(sh, rowIdx, 4, item.unitPrice().doubleValue()); // E: price
                // G,H,I column formulas calculate automatically — don't touch
            }

            // ── Deposit deduction row ─────────────────────────────────────────
            // Place right after items; uses normal row formula: H=E (if F/G empty), I=H*C
            // Setting C=-1 makes I = -depositAmount (deduction)
            if (doc.depositAmount() != null && doc.depositAmount().compareTo(BigDecimal.ZERO) != 0) {
                int depositRowIdx = ITEM_START_ROW + count;
                String depositLabel = "หัก  มัดจำ"
                    + (doc.reference() != null && !doc.reference().isBlank()
                       ? "  " + doc.reference() : "");
                setStr(sh, depositRowIdx, 1, depositLabel);              // B
                setNum(sh, depositRowIdx, 2, -1.0);                      // C = -1
                setNum(sh, depositRowIdx, 4, doc.depositAmount().doubleValue()); // E = deposit
                // H formula = IF(G="พิเศษ",J,IF(F="Net",E,...)) — with G="",F="" → H=E
                // I formula = ROUND(H*C, 2) = ROUND(E*(-1), 2) = -depositAmount ✓
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
        Cell cell = getOrCreate(sh, rowIdx, colIdx);
        cell.setCellType(CellType.STRING);
        cell.setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        Cell cell = getOrCreate(sh, rowIdx, colIdx);
        cell.setCellType(CellType.NUMERIC);
        cell.setCellValue(value);
    }

    private Cell getOrCreate(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) row = sh.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
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
