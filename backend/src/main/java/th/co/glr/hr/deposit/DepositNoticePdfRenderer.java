package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.LibreOfficePdfConverter;

/**
 * ใบแจ้งยอดเงินรับมัดจำ: fills templates/deposit_notice_template.xls (sheet "Update") with the STORED
 * document (see {@link DocumentSheet}) and converts it with LibreOffice. 0-based (row, col) map:
 * B7 customer line, I7 date, B8 address, I8 doc no, I9 reference (H9 is its label), B11 "Project : X",
 * items from row 13, หมายเหตุ header B37 + the template's own note lines (never rewritten),
 * I44 subtotal, H45/I45 deposit %/amount, I46 VAT, I47 total, B48 "( preparer )", B49 signature date.
 */
@Component
public class DepositNoticePdfRenderer {
    private static final String TEMPLATE = "templates/deposit_notice_template.xls";
    private static final int NOTES_ROW = 36, SUBTOTAL = 43, DEPOSIT = 44, VAT = 45, TOTAL = 46, PREPARER = 47, SIGDATE = 48;

    public byte[] toXls(DepositNoticeRenderModel m) {
        Workbook wb = DocumentSheet.openTemplate(TEMPLATE);
        Sheet sh = wb.getSheet("Update");
        DocumentSheet.str(sh, 6, 1, DocumentSheet.customerLine(m.customerName(), m.customerBranch(), m.customerTaxId()));
        DocumentSheet.str(sh, 6, 8, DocumentSheet.thaiDate(m.issueDate()));
        DocumentSheet.str(sh, 7, 1, m.customerAddress());
        DocumentSheet.str(sh, 7, 8, m.docNumber());
        DocumentSheet.str(sh, 8, 8, m.reference());
        DocumentSheet.str(sh, 10, 1, "Project : " + (m.projectName() == null ? "" : m.projectName()));

        List<DocumentSheet.Line> lines = new ArrayList<>();
        for (DepositNoticeItemDto i : m.items())
            lines.add(new DocumentSheet.Line(i.seq(), i.description(), i.qty(), i.unit(), i.unitPrice(),
                i.netUnitPrice(), i.amount()));
        List<int[]> blocks = new ArrayList<>();
        int d = DocumentSheet.fillItems(sh, lines, NOTES_ROW, blocks);

        DocumentSheet.num(sh, SUBTOTAL + d, 8, m.subtotal());
        DocumentSheet.num(sh, DEPOSIT + d, 7, m.depositPercent());
        DocumentSheet.num(sh, DEPOSIT + d, 8, m.depositAmount());
        DocumentSheet.num(sh, VAT + d, 8, m.vatAmount());
        DocumentSheet.num(sh, TOTAL + d, 8, m.totalPayable());
        DocumentSheet.preparer(sh, PREPARER + d, m.preparerName());
        DocumentSheet.signatureDate(sh, SIGDATE + d, m.issueDate());

        BigDecimal maxUnit = BigDecimal.ZERO;
        for (DepositNoticeItemDto i : m.items()) {
            if (i.unitPrice() != null) maxUnit = maxUnit.max(i.unitPrice());
            if (i.netUnitPrice() != null) maxUnit = maxUnit.max(i.netUnitPrice());
        }
        DocumentSheet.fitMoney(sh, 4, maxUnit);
        DocumentSheet.fitMoney(sh, 7, maxUnit);
        DocumentSheet.fitMoney(sh, 8, m.subtotal(), m.depositAmount(), m.vatAmount(), m.totalPayable());
        DocumentSheet.finishPage(sh, d, NOTES_ROW + d, SIGDATE + d, blocks);
        return DocumentSheet.bytes(wb);
    }

    /** The XLS converted by LibreOffice: same appearance as the sheet. */
    public byte[] toPdf(DepositNoticeRenderModel m) {
        return LibreOfficePdfConverter.convert(toXls(m));
    }

    // HTML preview — used until LibreOffice is available for PDF conversion
    /** {@code today} (Bangkok) is the date a DRAFT shows; every stored text is HTML-escaped. */
    public String toPreviewHtml(DepositNoticeDto doc, LocalDate today) {
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
          .append("<div class='mono' style='font-size:13px;font-weight:700;'>").append(escHtml(nullSafe(doc.docNumber(), "DRAFT"))).append("</div>")
          .append("<div style='font-size:12px;color:#64748b;'>วันที่: ").append(thaiDate(doc.issueDate() != null ? doc.issueDate() : today)).append("</div>")
          .append("</div></div>");

        // Customer
        sb.append("<div style='margin-bottom:16px;font-size:13px;'>")
          .append("<div>เรียน: <strong>").append(escHtml(doc.customerName())).append("</strong></div>")
          .append("<div style='color:#64748b;font-size:12px;'>").append(escHtml(doc.customerAddress())).append("</div>")
          .append("<div style='font-size:12px;'>เลขภาษี: ").append(escHtml(doc.customerTaxId())).append("</div>")
          .append(doc.projectName() != null ? "<div style='font-size:12px;'>โครงการ: " + escHtml(doc.projectName()) + "</div>" : "")
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
              .append("<td>").append(escHtml(item.unit())).append("</td>")
              .append("<td class='right'>").append(fmt2(item.unitPrice())).append("</td>")
              .append("<td class='right'>").append(escHtml(discountText(item))).append("</td>")
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

        // Notes: the template's own lines, exactly what the PDF prints (stored notes never print)
        List<String> templateNotes = DocumentSheet.templateNotes(TEMPLATE);
        if (!templateNotes.isEmpty()) {
            sb.append("<div class='notes'><strong>หมายเหตุ:</strong><ul style='margin:4px 0 0;padding-left:20px;list-style:none;'>");
            templateNotes.forEach(n -> sb.append("<li>").append(escHtml(n)).append("</li>"));
            sb.append("</ul></div>");
        }

        // Preparer
        sb.append("<div style='margin-top:40px;font-size:12px;color:#64748b;'>ผู้จัดทำ: ").append(escHtml(doc.preparerName())).append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** The same single "ลด unit-net" total the PDF prints in column G; blank unless net < unit. */
    private static String discountText(DepositNoticeItemDto item) {
        return new DocumentSheet.Line(item.seq(), item.description(), item.qty(), item.unit(), item.unitPrice(),
            item.netUnitPrice(), item.amount()).discountText();
    }

    private static String thaiDate(LocalDate d) { return DocumentSheet.thaiDate(d); }
    private static String nullSafe(String s) { return s != null ? s : ""; }
    private static String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }
    private static String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
