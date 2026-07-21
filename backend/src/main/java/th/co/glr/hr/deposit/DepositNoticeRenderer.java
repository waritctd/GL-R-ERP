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
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.LibreOfficePdfConverter;

@Component
public class DepositNoticeRenderer {

    private static final String TEMPLATE = "templates/deposit_notice_template.xls";
    // Thai month names for date display
    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // Render to Excel bytes using Apache POI
    public byte[] toXlsx(DepositNoticeDto doc) throws Exception {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            // B7 (row 6, col 1) = customer name, I7 (row 6, col 8) = date
            // B8 (row 7, col 1) = address,       I8 (row 7, col 8) = doc number
            setStr(sh, 6, 1, nullSafe(doc.customerName()));
            setStr(sh, 6, 8, thaiDate(doc.issueDate() != null ? doc.issueDate() : LocalDate.now()));
            setStr(sh, 7, 1, nullSafe(doc.customerAddress()));
            setStr(sh, 7, 8, nullSafe(doc.docNumber()));
            if (doc.reference() != null && !doc.reference().isBlank()) {
                setStr(sh, 8, 7, doc.reference());
            }
            setStr(sh, 10, 1, nullSafe(doc.projectName()));

            // Item rows start at 0-based row 12 (row 13 in 1-based). We fill:
            //   E ราคา (unit price), G ส่วนลด (discount label, display), H คงเหลือ (net unit price)
            // and let the template's amount formula  I = IF(H="","", H*C)  compute เป็นเงิน.
            // The net price is written DIRECTLY into H rather than derived from the template's
            // H = IF(G="พิเศษ",J,IF(E-E*F=0,"",E-E*F)) formula, because the very first data row
            // (row 13) has H as a plain cell (not a formula) — leaving it empty made its
            // I = ROUND(IF(H="","", H*C),2) collapse to ROUND("") = #VALUE!. (The previous code's
            // other bug — writing the label text into the numeric discount column F — made E-E*F
            // itself #VALUE! and cascaded into every total.)
            int dataStartRow = 12;
            for (int i = 0; i < doc.items().size(); i++) {
                DepositNoticeItemDto item = doc.items().get(i);
                int r = dataStartRow + i;
                BigDecimal unitPrice = item.unitPrice() != null ? item.unitPrice() : BigDecimal.ZERO;
                BigDecimal netPrice = item.netUnitPrice() != null ? item.netUnitPrice() : unitPrice;
                setNum(sh, r, 0, (double) item.seq());         // A ลำดับ
                setStr(sh, r, 1, item.description());          // B รายละเอียด
                setNum(sh, r, 2, item.qty().doubleValue());    // C จำนวน
                setStr(sh, r, 3, item.unit());                 // D หน่วย
                setNum(sh, r, 4, unitPrice.doubleValue());     // E ราคา (unit price)
                setStr(sh, r, 6, nullSafe(item.discountLabel(),
                    netPrice.compareTo(unitPrice) != 0 ? "พิเศษ" : "Net")); // G ส่วนลด (display)
                setNum(sh, r, 7, netPrice.doubleValue());      // H คงเหลือ (net) → I = H*C
            }

            // Summary: only the deposit-rate input (H45) is data. Subtotal (I44), deposit (I45),
            // VAT (I46) and total (I47) are template formulas that compute from the item rows and
            // this rate, so we leave them to the template rather than writing literals that could
            // diverge from its own arithmetic.
            if (doc.depositPercent() != null) setNum(sh, 44, 7, doc.depositPercent().doubleValue());

            // Notes block (rows 36-42 = B37-B43 in 1-based)
            List<String> notes = doc.notes();
            for (int i = 0; i < notes.size() && i < 7; i++) {
                setStr(sh, 36 + i, 1, (i + 1) + ". " + notes.get(i));
            }

            // Preparer
            setStr(sh, 47, 1, nullSafe(doc.preparerName()));

            // The template's second sheet ("Sheet1") is the customer-master list the B8 VLOOKUP
            // read. The address is now written directly, so it is unused — and LibreOffice would
            // otherwise render it as extra PDF pages. Drop every sheet except the notice itself.
            for (int s = wb.getNumberOfSheets() - 1; s >= 0; s--) {
                if (!wb.getSheetAt(s).getSheetName().equals(sh.getSheetName())) {
                    wb.removeSheetAt(s);
                }
            }
            wb.setActiveSheet(wb.getSheetIndex(sh));

            // Give the money columns (ราคา..เป็นเงิน) a floor width so values never render as "###".
            for (int c = 4; c <= 9; c++) {
                if (sh.getColumnWidth(c) < 11 * 256) sh.setColumnWidth(c, 11 * 256);
            }

            // Force fit-to-1-page-wide so LibreOffice never spills columns onto extra
            // pages when it substitutes the template's Windows Thai fonts with wider ones.
            sh.setFitToPage(true);
            sh.setAutobreaks(true);
            PrintSetup ps = sh.getPrintSetup();
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 0);

            wb.write(out);
            return out.toByteArray();
        }
    }

    // PDF is the XLS template converted to PDF by LibreOffice — same appearance as the XLS.
    public byte[] toPdf(DepositNoticeDto doc) {
        try {
            return LibreOfficePdfConverter.convert(toXlsx(doc));
        } catch (Exception e) {
            throw new RuntimeException("Deposit notice PDF render failed: " + e.getMessage(), e);
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

    // Preserve template cell style: get existing cell, only create if absent.
    // Never call setCellType — it wipes the template's number format and border.
    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        getOrKeep(sh, rowIdx, colIdx).setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        getOrKeep(sh, rowIdx, colIdx).setCellValue(value);
    }

    private void setNumeric(Sheet sh, int rowIdx, int colIdx, BigDecimal value) {
        if (value == null) return;
        getOrKeep(sh, rowIdx, colIdx).setCellValue(value.doubleValue());
    }

    private Cell getOrKeep(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) row = sh.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        // Writing a literal onto a template formula cell (e.g. B8 =VLOOKUP, I7 =TODAY) must clear
        // the formula first: POI's setCellValue only sets the cached result and leaves the formula,
        // so LibreOffice recalculates it on PDF export and the literal is lost (→ #N/A). setBlank
        // keeps the cell's style/number-format/border (unlike setCellType), just drops the formula.
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        return cell;
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
