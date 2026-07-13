package th.co.glr.hr.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;
import th.co.glr.hr.customer.CustomerDto;

// Renders a quotation by filling the official company template (quotation_template.xlsx).
// Only dynamic cells are written — all borders, merged regions, and static text
// are preserved from the template. Never create a blank XSSFWorkbook here.
@Component
public class QuotationRenderer {

    private static final String TEMPLATE = "templates/quotation_template.xlsx";
    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // Item rows in the template: 1-based rows 8-22 (0-based 7-21), max 15 items.
    // Rows beyond 22 contain pre-filled note boilerplate in column B — don't overwrite.
    private static final int ITEM_START_ROW = 7;  // 0-based (= row 8 in 1-based)
    private static final int MAX_ITEMS = 15;

    public byte[] toXlsx(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = new XSSFWorkbook(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            TicketSummaryDto s = ticket.summary();
            LocalDate issueDate = quotation.issuedAt() != null
                ? quotation.issuedAt().atZone(ZoneId.of("Asia/Bangkok")).toLocalDate()
                : LocalDate.now();

            // B4 (row 3, col 1) — override TODAY() formula with actual issue date
            setStr(sh, 3, 1, thaiDate(issueDate));

            // I4 (row 3, col 8) — quotation number
            setStr(sh, 3, 8, nullSafe(quotation.number()));

            // B5 (row 4, col 1) — customer name (after static "เรียน" label in A5)
            setStr(sh, 4, 1, nullSafe(s.customerName()));

            // B6 (row 5, col 1) — customer phone/contact
            if (customer != null && customer.phone() != null && !customer.phone().isBlank()) {
                setStr(sh, 5, 1, "โทร. " + customer.phone());
            }

            // B8 (row 7, col 1) — project name (append to "Project :" label already in cell)
            if (s.projectName() != null && !s.projectName().isBlank()) {
                setStr(sh, 7, 1, "Project : " + s.projectName());
            }

            // Items
            List<TicketItemDto> priceItems = ticket.items().stream()
                .filter(it -> it.approvedPrice() != null)
                .toList();

            BigDecimal subtotal = BigDecimal.ZERO;
            int seq = 1;
            for (int i = 0; i < priceItems.size() && i < MAX_ITEMS; i++) {
                TicketItemDto item = priceItems.get(i);
                BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
                BigDecimal price = item.approvedPrice();
                BigDecimal amount = price.multiply(qty);
                subtotal = subtotal.add(amount);

                int r = ITEM_START_ROW + i;
                setNum(sh, r, 0, seq++);                        // A: sequence
                setStr(sh, r, 1, buildDesc(item));              // B: description
                setNum(sh, r, 2, qty.doubleValue());            // C: qty
                setStr(sh, r, 3, nullSafe(item.rawUnit(), "แผ่น")); // D: unit
                setNum(sh, r, 4, price.doubleValue());          // E: unit price
                // G(col 6) net-price indicator — keep "Net" from template for pre-filled rows
                // H(col 7) and I(col 8) have IF-formulas that auto-calculate; do not touch
            }

            // I38 (row 37, col 8) — subtotal; must be set before formula-dependent I39/I40
            setNum(sh, 37, 8, subtotal.doubleValue());

            // Force Excel/Calc to re-evaluate I39=I38*H39 and I40=SUM(I38+I39) on open
            wb.setForceFormulaRecalculation(true);

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Quotation render failed: " + e.getMessage(), e);
        }
    }

    public byte[] toPdf(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        try (PdfDocumentWriter pdf = new PdfDocumentWriter()) {
            PDFont regular = pdf.loadFont(PdfDocumentWriter.FONT_REGULAR);
            PDFont bold = pdf.loadFont(PdfDocumentWriter.FONT_BOLD);

            pdf.text(bold, 15, "บริษัท จี แอล แอนด์ อาร์ จำกัด");
            pdf.text(regular, 10, "เลขที่ภาษี 0105542026329");
            pdf.gap(8);
            pdf.text(bold, 13, "ใบเสนอราคา  " + nullSafe(quotation.number()));
            pdf.text(regular, 10, "วันที่: " + thaiDate(quotation.issuedAt() != null
                ? quotation.issuedAt().atZone(ZoneId.of("Asia/Bangkok")).toLocalDate()
                : LocalDate.now()));
            pdf.gap(10);

            TicketSummaryDto s = ticket.summary();
            pdf.text(regular, 11, "เรียน " + nullSafe(s.customerName()));
            if (customer != null && customer.address() != null && !customer.address().isBlank()) {
                pdf.text(regular, 10, customer.address());
            }
            if (customer != null && customer.taxId() != null && !customer.taxId().isBlank()) {
                pdf.text(regular, 10, "เลขประจำตัวผู้เสียภาษี " + customer.taxId());
            }
            if (s.projectName() != null && !s.projectName().isBlank()) {
                pdf.text(regular, 10, "โครงการ " + s.projectName());
            }
            pdf.gap(10);

            pdf.text(bold, 11, "รายการ");
            BigDecimal total = BigDecimal.ZERO;
            int seq = 1;
            for (TicketItemDto item : ticket.items()) {
                if (item.approvedPrice() == null) continue;
                BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
                BigDecimal amount = item.approvedPrice().multiply(qty);
                total = total.add(amount);
                pdf.text(regular, 10, seq++ + ". " + buildDesc(item)
                    + "  จำนวน " + fmt2(qty) + " " + nullSafe(item.rawUnit(), "แผ่น")
                    + "  ราคา/หน่วย " + fmt2(item.approvedPrice())
                    + "  เป็นเงิน " + fmt2(amount));
            }
            pdf.gap(10);

            BigDecimal grandTotal = quotation.totalAmount() != null ? quotation.totalAmount() : total;
            BigDecimal vat = grandTotal.multiply(new BigDecimal("0.07")).setScale(2, RoundingMode.HALF_UP);
            pdf.text(bold, 12, "รวมเป็นเงิน: " + fmt2(grandTotal) + " บาท");
            pdf.text(regular, 11, "ภาษีมูลค่าเพิ่ม 7%: " + fmt2(vat) + " บาท");
            pdf.text(bold, 12, "รวมทั้งสิ้น: " + fmt2(grandTotal.add(vat)) + " บาท");

            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("Quotation PDF render failed: " + e.getMessage(), e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildDesc(TicketItemDto item) {
        return List.of(item.brand(), item.model(), item.color(), item.texture(), item.size())
            .stream()
            .filter(v -> v != null && !v.isBlank())
            .reduce((a, b) -> a + " " + b)
            .orElse(nullSafe(item.brand()));
    }

    // Get existing cell to preserve template style, create only if absent
    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        Cell cell = getOrCreateCell(sh, rowIdx, colIdx);
        cell.setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        Cell cell = getOrCreateCell(sh, rowIdx, colIdx);
        cell.setCellValue(value);
    }

    private Cell getOrCreateCell(Sheet sh, int rowIdx, int colIdx) {
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
    private String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }

    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
}
