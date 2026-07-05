package th.co.glr.hr.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;
import th.co.glr.hr.customer.CustomerDto;

// Renders a quotation to Excel on the fly from the live ticket + quotation record
// (no draft/preview phase for quotations, so there is nothing else to persist).
@Component
public class QuotationRenderer {
    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    public byte[] toXlsx(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.createSheet("Quotation");
            sh.setColumnWidth(0, 2200);
            sh.setColumnWidth(1, 12000);
            sh.setColumnWidth(2, 2500);
            sh.setColumnWidth(3, 2500);
            sh.setColumnWidth(4, 3500);
            sh.setColumnWidth(5, 4000);

            CellStyle title = style(wb, 16, true, null);
            CellStyle label = style(wb, 11, false, null);
            CellStyle bold  = style(wb, 11, true, null);
            CellStyle header = style(wb, 11, true, HorizontalAlignment.CENTER);
            CellStyle money  = style(wb, 11, false, HorizontalAlignment.RIGHT);
            CellStyle moneyBold = style(wb, 11, true, HorizontalAlignment.RIGHT);

            int r = 0;
            setText(sh, r++, 0, "บริษัท จี แอล แอนด์ อาร์ จำกัด", title);
            setText(sh, r++, 0, "เลขที่ภาษี 0105542026329", label);
            r++;
            setText(sh, r, 0, "ใบเสนอราคา", bold);
            setText(sh, r++, 4, quotation.number(), bold);
            setText(sh, r, 0, "วันที่", label);
            setText(sh, r++, 1, thaiDate(quotation.issuedAt() != null
                ? quotation.issuedAt().atZone(ZoneId.systemDefault()).toLocalDate()
                : LocalDate.now()), label);

            TicketSummaryDto s = ticket.summary();
            r++;
            setText(sh, r++, 0, "เรียน " + nullSafe(s.customerName()), bold);
            if (customer != null) {
                setText(sh, r++, 0, nullSafe(customer.address()), label);
                setText(sh, r++, 0, "เลขประจำตัวผู้เสียภาษี " + nullSafe(customer.taxId()), label);
            }
            if (s.projectName() != null) {
                setText(sh, r++, 0, "โครงการ " + s.projectName(), label);
            }
            r++;

            int headerRow = r;
            String[] headers = {"ลำดับ", "รายละเอียด", "จำนวน", "หน่วย", "ราคา/หน่วย", "เป็นเงิน (บาท)"};
            for (int c = 0; c < headers.length; c++) {
                setText(sh, headerRow, c, headers[c], header);
            }
            r++;

            int seq = 1;
            BigDecimal total = BigDecimal.ZERO;
            for (TicketItemDto item : ticket.items()) {
                if (item.approvedPrice() == null) continue;
                String desc = List.of(item.brand(), item.model(), item.color(), item.texture(), item.size())
                    .stream().filter(v -> v != null && !v.isBlank())
                    .reduce((a, b) -> a + " " + b).orElse(item.brand());
                BigDecimal amount = item.approvedPrice().multiply(item.qty());
                total = total.add(amount);

                Row row = sh.createRow(r++);
                setCell(row, 0, seq++, label);
                setCell(row, 1, desc, label);
                setCell(row, 2, item.qty(), money);
                setCell(row, 3, nullSafe(item.rawUnit(), "แผ่น"), label);
                setCell(row, 4, item.approvedPrice(), money);
                setCell(row, 5, amount, money);
            }

            r++;
            setText(sh, r, 4, "รวมเป็นเงิน", bold);
            setCell(sh.createRow(r), 5, quotation.totalAmount() != null ? quotation.totalAmount() : total, moneyBold);
            r++;

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
                ? quotation.issuedAt().atZone(ZoneId.systemDefault()).toLocalDate()
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
                String desc = List.of(item.brand(), item.model(), item.color(), item.texture(), item.size())
                    .stream().filter(v -> v != null && !v.isBlank())
                    .reduce((a, b) -> a + " " + b).orElse(item.brand());
                BigDecimal amount = item.approvedPrice().multiply(item.qty());
                total = total.add(amount);
                pdf.text(regular, 10, seq++ + ". " + desc
                    + "  จำนวน " + fmt2(item.qty()) + " " + nullSafe(item.rawUnit(), "แผ่น")
                    + "  ราคา/หน่วย " + fmt2(item.approvedPrice())
                    + "  เป็นเงิน " + fmt2(amount));
            }
            pdf.gap(10);

            BigDecimal grandTotal = quotation.totalAmount() != null ? quotation.totalAmount() : total;
            pdf.text(bold, 12, "รวมเป็นเงิน: " + fmt2(grandTotal) + " " + nullSafe(quotation.currency(), "THB"));

            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("Quotation PDF render failed: " + e.getMessage(), e);
        }
    }

    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(java.util.Locale.US, "%,.2f", v);
    }

    private CellStyle style(Workbook wb, int fontSize, boolean bold, HorizontalAlignment align) {
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) fontSize);
        font.setBold(bold);
        CellStyle cs = wb.createCellStyle();
        cs.setFont(font);
        if (align != null) cs.setAlignment(align);
        return cs;
    }

    private void setText(Sheet sh, int rowIdx, int colIdx, String value, CellStyle style) {
        Row row = sh.getRow(rowIdx);
        if (row == null) row = sh.createRow(rowIdx);
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value.doubleValue() : 0d);
        cell.setCellStyle(style);
    }

    private void setCell(Row row, int col, int value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private String thaiDate(LocalDate d) {
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
    private String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }
}
