package th.co.glr.hr.ticket;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
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
