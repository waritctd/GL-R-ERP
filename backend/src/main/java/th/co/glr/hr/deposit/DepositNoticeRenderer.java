package th.co.glr.hr.deposit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;

@Component
public class DepositNoticeRenderer {

    private static final String TEMPLATE = "templates/deposit_notice_template.xlsx";
    // Thai month names for date display
    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // Render to Excel bytes using Apache POI
    public byte[] toXlsx(DepositNoticeDto doc) throws Exception {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = new XSSFWorkbook(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            // Customer block (row 6 = A7, B7 in 1-based)
            setText(sh, 6, 0, "เรียน " + nullSafe(doc.customerName()));
            setText(sh, 6, 7, thaiDate(doc.issueDate() != null ? doc.issueDate() : LocalDate.now()));
            setText(sh, 7, 0, nullSafe(doc.customerAddress()));
            setText(sh, 7, 7, nullSafe(doc.docNumber()));
            if (doc.reference() != null && !doc.reference().isBlank()) {
                setText(sh, 8, 7, doc.reference());
            }
            setText(sh, 10, 1, nullSafe(doc.projectName()));

            // Item rows starting at row 12 (A13 in 1-based)
            int dataStartRow = 12;
            for (int i = 0; i < doc.items().size(); i++) {
                DepositNoticeItemDto item = doc.items().get(i);
                Row row = getOrCreateRow(sh, dataStartRow + i);
                setCell(row, 0, (double) item.seq());
                setCell(row, 1, item.description());
                setCell(row, 2, item.qty().doubleValue());
                setCell(row, 3, item.unit());
                setCell(row, 4, item.unitPrice().doubleValue());
                setCell(row, 5, nullSafe(item.discountLabel()));
                setCell(row, 6, item.netUnitPrice().doubleValue());
                setCell(row, 8, item.amount().doubleValue());
            }

            // Summary block (rows 43-46 = I44-I47 in 1-based)
            setNumeric(sh, 43, 8, doc.subtotal());
            setNumeric(sh, 44, 7, doc.depositPercent());
            setNumeric(sh, 44, 8, doc.depositAmount());
            setNumeric(sh, 45, 7, doc.vatPercent());
            setNumeric(sh, 45, 8, doc.vatAmount());
            setNumeric(sh, 46, 8, doc.totalPayable());

            // Notes block (rows 36-42 = B37-B43 in 1-based)
            List<String> notes = doc.notes();
            for (int i = 0; i < notes.size() && i < 7; i++) {
                setText(sh, 36 + i, 1, (i + 1) + ". " + notes.get(i));
            }

            // Preparer
            setText(sh, 47, 1, nullSafe(doc.preparerName()));

            wb.write(out);
            return out.toByteArray();
        }
    }

    // Renders a real PDF with an embedded Thai font (Sarabun), so Thai text displays
    // correctly instead of the previous ASCII-only fallback (which turned Thai characters
    // into "?" since this document is almost entirely Thai content).
    public byte[] toPdf(DepositNoticeDto doc) {
        try (PdfDocumentWriter pdf = new PdfDocumentWriter()) {
            PDFont regular = pdf.loadFont(PdfDocumentWriter.FONT_REGULAR);
            PDFont bold = pdf.loadFont(PdfDocumentWriter.FONT_BOLD);

            pdf.text(bold, 15, "บริษัท จี แอล แอนด์ อาร์ จำกัด");
            pdf.text(regular, 10, "เลขที่ภาษี 0105542026329");
            pdf.gap(8);
            pdf.text(bold, 13, "ใบแจ้งยอด / เงินรับมัดจำ  " + nullSafe(doc.docNumber(), "DRAFT"));
            pdf.text(regular, 10, "วันที่: " + thaiDate(doc.issueDate() != null ? doc.issueDate() : LocalDate.now()));
            pdf.gap(10);

            pdf.text(regular, 11, "เรียน " + nullSafe(doc.customerName()));
            if (doc.customerAddress() != null && !doc.customerAddress().isBlank()) {
                pdf.text(regular, 10, doc.customerAddress());
            }
            if (doc.customerTaxId() != null && !doc.customerTaxId().isBlank()) {
                pdf.text(regular, 10, "เลขภาษี: " + doc.customerTaxId());
            }
            if (doc.projectName() != null && !doc.projectName().isBlank()) {
                pdf.text(regular, 10, "โครงการ: " + doc.projectName());
            }
            pdf.gap(10);

            pdf.text(bold, 11, "รายการ");
            for (var item : doc.items()) {
                pdf.text(regular, 10, item.seq() + ". " + item.description()
                    + "  จำนวน " + fmt2(item.qty()) + " " + nullSafe(item.unit())
                    + "  ราคา/หน่วย " + fmt2(item.unitPrice())
                    + "  เป็นเงิน " + fmt2(item.amount()));
            }
            pdf.gap(10);

            pdf.text(regular, 11, "รวมเป็นเงิน: " + fmt2(doc.subtotal()) + " " + nullSafe(doc.currency(), "THB"));
            pdf.text(regular, 11, "ขอรับเงินมัดจำ: " + fmt2(doc.depositAmount()) + " " + nullSafe(doc.currency(), "THB"));
            pdf.text(regular, 11, "ภาษีมูลค่าเพิ่ม: " + fmt2(doc.vatAmount()) + " " + nullSafe(doc.currency(), "THB"));
            pdf.text(bold, 12, "รวมเป็นเงินที่ต้องชำระ: " + fmt2(doc.totalPayable()) + " " + nullSafe(doc.currency(), "THB"));
            pdf.gap(10);

            if (!doc.notes().isEmpty()) {
                pdf.text(bold, 10, "หมายเหตุ");
                for (int i = 0; i < doc.notes().size(); i++) {
                    pdf.text(regular, 9, (i + 1) + ". " + doc.notes().get(i));
                }
                pdf.gap(10);
            }

            pdf.text(regular, 10, "ผู้จัดทำ: " + nullSafe(doc.preparerName()));
            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("PDF render failed: " + e.getMessage(), e);
        }
    }

    // HTML preview — used until LibreOffice is available for PDF conversion
    public String toPreviewHtml(DepositNoticeDto doc) {
        var sb = new StringBuilder();
        String fmt = "font-family:'Sarabun',sans-serif;font-size:13px;color:#1e293b;";
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
          .append("<style>body{").append(fmt).append("padding:24px;max-width:900px;margin:0 auto;}")
          .append("table{width:100%;border-collapse:collapse;margin-top:12px;}")
          .append("th{background:#1e3a5f;color:#fff;padding:6px 8px;font-size:12px;text-align:left;}")
          .append("td{padding:5px 8px;border-bottom:1px solid #e2e8f0;font-size:12px;}")
          .append(".right{text-align:right;} .mono{font-family:monospace;}")
          .append(".summary{margin-top:16px;float:right;width:300px;}")
          .append(".summary td{padding:4px 8px;} .summary .label{color:#64748b;} .summary .val{font-weight:700;text-align:right;}")
          .append(".header{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:2px solid #1e3a5f;padding-bottom:12px;margin-bottom:16px;}")
          .append(".company{font-size:11px;color:#64748b;} .doctitle{font-size:18px;font-weight:800;color:#1e3a5f;}")
          .append(".notes{margin-top:60px;font-size:11px;color:#475569;}")
          .append("</style></head><body>");

        // Header
        sb.append("<div class='header'>")
          .append("<div><div style='font-weight:800;font-size:15px;'>บริษัท จี แอล แอนด์ อาร์ จำกัด</div>")
          .append("<div class='company'>เลขที่ภาษี 0105542026329</div></div>")
          .append("<div style='text-align:right;'>")
          .append("<div class='doctitle'>ใบแจ้งยอด / เงินรับมัดจำ</div>")
          .append("<div class='mono' style='font-size:13px;font-weight:700;'>").append(nullSafe(doc.docNumber(), "DRAFT")).append("</div>")
          .append("<div style='font-size:12px;color:#64748b;'>วันที่: ").append(thaiDate(doc.issueDate() != null ? doc.issueDate() : LocalDate.now())).append("</div>")
          .append("</div></div>");

        // Customer
        sb.append("<div style='margin-bottom:16px;font-size:13px;'>")
          .append("<div>เรียน: <strong>").append(nullSafe(doc.customerName())).append("</strong></div>")
          .append("<div style='color:#64748b;font-size:12px;'>").append(nullSafe(doc.customerAddress())).append("</div>")
          .append("<div style='font-size:12px;'>เลขภาษี: ").append(nullSafe(doc.customerTaxId())).append("</div>")
          .append(doc.projectName() != null ? "<div style='font-size:12px;'>โครงการ: " + doc.projectName() + "</div>" : "")
          .append("</div>");

        // Items table
        sb.append("<table><thead><tr>")
          .append("<th style='width:30px'>ลำดับ</th><th>รายละเอียด</th>")
          .append("<th class='right'>จำนวน</th><th>หน่วย</th>")
          .append("<th class='right'>ราคา/หน่วย</th><th>ส่วนลด</th>")
          .append("<th class='right'>ราคาสุทธิ</th><th class='right'>เป็นเงิน (บาท)</th>")
          .append("</tr></thead><tbody>");

        for (var item : doc.items()) {
            sb.append("<tr>")
              .append("<td>").append(item.seq()).append("</td>")
              .append("<td>").append(escHtml(item.description())).append("</td>")
              .append("<td class='right'>").append(fmt2(item.qty())).append("</td>")
              .append("<td>").append(nullSafe(item.unit())).append("</td>")
              .append("<td class='right'>").append(fmt2(item.unitPrice())).append("</td>")
              .append("<td>").append(nullSafe(item.discountLabel())).append("</td>")
              .append("<td class='right'>").append(fmt2(item.netUnitPrice())).append("</td>")
              .append("<td class='right'>").append(fmt2(item.amount())).append("</td>")
              .append("</tr>");
        }
        sb.append("</tbody></table>");

        // Summary
        String depositPctStr = doc.depositPercent() != null
            ? doc.depositPercent().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%"
            : "50%";
        sb.append("<table class='summary'><tbody>")
          .append("<tr><td class='label'>รวมเป็นเงิน</td><td class='val'>").append(fmt2(doc.subtotal())).append(" บาท</td></tr>")
          .append("<tr><td class='label'>ขอรับเงินมัดจำ (").append(depositPctStr).append(")</td><td class='val'>").append(fmt2(doc.depositAmount())).append(" บาท</td></tr>")
          .append("<tr><td class='label'>ภาษีมูลค่าเพิ่ม 7%</td><td class='val'>").append(fmt2(doc.vatAmount())).append(" บาท</td></tr>")
          .append("<tr style='border-top:2px solid #1e3a5f;'><td class='label'><strong>รวมเป็นเงินที่ต้องชำระ</strong></td>")
          .append("<td class='val'><strong>").append(fmt2(doc.totalPayable())).append(" บาท</strong></td></tr>")
          .append("</tbody></table><div style='clear:both;'></div>");

        // Notes
        if (!doc.notes().isEmpty()) {
            sb.append("<div class='notes'><strong>หมายเหตุ:</strong><ol style='margin:4px 0 0;padding-left:20px;'>");
            doc.notes().forEach(n -> sb.append("<li>").append(escHtml(n)).append("</li>"));
            sb.append("</ol></div>");
        }

        // Preparer
        sb.append("<div style='margin-top:40px;font-size:12px;color:#64748b;'>ผู้จัดทำ: ").append(nullSafe(doc.preparerName())).append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String thaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private void setText(Sheet sh, int rowIdx, int colIdx, String value) {
        getOrCreateRow(sh, rowIdx).createCell(colIdx, CellType.STRING).setCellValue(value != null ? value : "");
    }

    private void setNumeric(Sheet sh, int rowIdx, int colIdx, BigDecimal value) {
        if (value == null) return;
        getOrCreateRow(sh, rowIdx).createCell(colIdx, CellType.NUMERIC).setCellValue(value.doubleValue());
    }

    private void setCell(Row row, int col, double value) {
        row.createCell(col, CellType.NUMERIC).setCellValue(value);
    }

    private void setCell(Row row, int col, String value) {
        row.createCell(col, CellType.STRING).setCellValue(value != null ? value : "");
    }

    private Row getOrCreateRow(Sheet sh, int rowIdx) {
        Row row = sh.getRow(rowIdx);
        return row != null ? row : sh.createRow(rowIdx);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
    private String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }
    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

}
