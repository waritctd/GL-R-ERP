package th.co.glr.hr.ticket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.CustomerDto;

// Renders a quotation by filling the official company template (quotation_template.xls).
// Rule: open the real template, write ONLY the cells listed below, never createRow/createCell
// on rows that already exist in the template, never call setCellType (wipes style).
@Component
public class QuotationRenderer {

    // Real company XLS template — sheet "Update"
    private static final String TEMPLATE = "templates/quotation_template.xls";

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // Template layout per document-generation-fix.md §A2-A3
    // Item rows: 1 item = 3 rows starting at A10 (0-based row 9).
    // Max 4 items fit before pushing into the remark zone (rows 10–21 = 4×3).
    // Comment in the template says rows 10-22 are the item zone.
    private static final int ITEM_START_ROW = 9; // 0-based (= row 10 in 1-based)
    private static final int ROWS_PER_ITEM  = 3;
    private static final int MAX_ITEMS      = 4; // 4 items × 3 rows = 12 rows (10-21)

    public byte[] toXls(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            TicketSummaryDto s = ticket.summary();
            LocalDate issueDate = quotation.issuedAt() != null
                ? quotation.issuedAt().atZone(ZoneId.of("Asia/Bangkok")).toLocalDate()
                : LocalDate.now();

            // B4 (row 3, col 1) — issue date, overrides TODAY() formula
            setStr(sh, 3, 1, thaiDate(issueDate));

            // I3 (row 2, col 8) — salesperson code (employee_code of assigned-to)
            // issuedByName is the full name; code not yet surfaced in QuotationDto — leave blank
            setStr(sh, 2, 8, "");

            // I4 (row 3, col 8) — quotation number
            setStr(sh, 3, 8, nullSafe(quotation.number()));

            // H5 (row 4, col 7) — department (not in current data model — leave blank)
            setStr(sh, 4, 7, "");

            // H6 (row 5, col 7) — "Sales : {name} T.{phone}"
            String salesLine = quotation.issuedByName() != null
                ? "Sales : " + quotation.issuedByName()
                : "";
            setStr(sh, 5, 7, salesLine);

            // A5/B5 (row 4, col 0/1) — "เรียน: คุณ{contact} / {company} เลขที่ผู้เสียภาษี : {taxId}"
            String contactPart = s.contactName() != null && !s.contactName().isBlank()
                ? "คุณ" + s.contactName() + "   /   " : "";
            String taxIdPart = customer != null && customer.taxId() != null && !customer.taxId().isBlank()
                ? "   เลขที่ผู้เสียภาษี : " + customer.taxId() : "";
            setStr(sh, 4, 1, contactPart + nullSafe(s.customerName()) + taxIdPart);

            // B6 (row 5, col 1) — "โทร. {phone}    / E-mail : {email}"
            String phonePart = customer != null && customer.phone() != null && !customer.phone().isBlank()
                ? "โทร. " + customer.phone() : "";
            setStr(sh, 5, 1, phonePart);

            // B8 (row 7, col 1) — project
            if (s.projectName() != null && !s.projectName().isBlank()) {
                setStr(sh, 7, 1, "Project  : " + s.projectName());
            }

            // Items — 3 rows per item (A10 = 0-based row 9)
            List<TicketItemDto> priceItems = ticket.items().stream()
                .filter(it -> it.approvedPrice() != null)
                .toList();

            BigDecimal subtotal = priceItems.stream()
                .map(item -> {
                    BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
                    return item.approvedPrice().multiply(qty);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            int seq = 1;
            for (int i = 0; i < priceItems.size() && i < MAX_ITEMS; i++) {
                TicketItemDto item = priceItems.get(i);
                BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
                BigDecimal price = item.approvedPrice();
                BigDecimal amount = price.multiply(qty);

                int r = ITEM_START_ROW + (i * ROWS_PER_ITEM);

                // Row R: main item line
                setNum(sh, r, 0, seq++);                                    // A: sequence
                setStr(sh, r, 1, buildDesc(item));                          // B: description
                setNum(sh, r, 2, qty.doubleValue());                        // C: qty
                setStr(sh, r, 3, nullSafe(item.rawUnit(), "แผ่น"));         // D: unit
                setNum(sh, r, 4, price.doubleValue());                      // E: unit price
                // G (col 6) = "Net" — H and I formulas auto-calculate in template
                setStr(sh, r, 6, "Net");

                // Row R+1: pcs detail (pcs_per_box info from rawUnit)
                // Row R+2: box packing detail — leave blank if no pcs_per_box data
                // (catalog pcs_per_box data not currently in TicketItemDto snapshot)
            }

            // Remarks block — B24 (row 23), B26 (row 25), B28 (row 27)
            LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : issueDate;
            int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 50;
            int deliveryDays = quotation.deliveryLeadDays() != null ? quotation.deliveryLeadDays() : 90;

            setStr(sh, 23, 1,
                "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  " + shortThaiDate(offerDate));
            setStr(sh, 25, 1,
                "2.บริษัทฯ ขอรับมัดจำ " + depositPct + "% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือขอรับ");
            setStr(sh, 27, 1,
                "3.ขณะนี้โรงงานผู้ผลิตประเทศอิตาลีมีสินค้าในสต็อก ระยะเวลานำเข้าประมาณ " + deliveryDays + " วัน");

            // I38 (row 37, col 8) — subtotal
            setNum(sh, 37, 8, subtotal.doubleValue());
            // I39 = I38 × 0.07, I40 = I38 + I39 — template has formulas; force recalc
            wb.setForceFormulaRecalculation(true);

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Quotation render failed: " + e.getMessage(), e);
        }
    }

    // Keep toXlsx as a shim — callers get XLS bytes now (format matches template)
    public byte[] toXlsx(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        return toXls(ticket, quotation, customer);
    }

    // PDF is the XLS template converted to PDF by LibreOffice (soffice --headless).
    // This ensures the PDF is pixel-identical to the XLS output.
    // Requires LibreOffice: brew install --cask libreoffice  (macOS)
    //                       apt-get install -y libreoffice-calc  (Ubuntu/Docker)
    public byte[] toPdf(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        return LibreOfficePdfConverter.convert(toXls(ticket, quotation, customer));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildDesc(TicketItemDto item) {
        StringBuilder sb = new StringBuilder("กระเบื้อง");
        if (item.model() != null && !item.model().isBlank())   sb.append(" รุ่น ").append(item.model());
        if (item.color() != null && !item.color().isBlank())   sb.append(" สี ").append(item.color());
        if (item.size() != null && !item.size().isBlank())     sb.append(" ขนาด ").append(item.size()).append(" cm.");
        if (item.texture() != null && !item.texture().isBlank()) sb.append(" ").append(item.texture());
        return sb.toString();
    }

    // Preserve template cell style — get existing cell, only create if absent
    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        Cell cell = getOrKeep(sh, rowIdx, colIdx);
        cell.setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        Cell cell = getOrKeep(sh, rowIdx, colIdx);
        cell.setCellValue(value);
    }

    private Cell getOrKeep(Sheet sh, int rowIdx, int colIdx) {
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

    private String shortThaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + (d.getYear() + 543);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
    private String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }

    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
}
