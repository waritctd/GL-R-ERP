package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.LibreOfficePdfConverter;

/**
 * ใบแจ้งหนี้ส่วนที่เหลือ: fills templates/remaining_invoice_template.xls (sheet "Update") with the STORED
 * document (see {@link DocumentSheet}). 0-based (row, col) map: B7 customer line, H7 date, B8 address,
 * H8 doc no, H9 reference (D9 is its label), B11 "Project : X", items from row 13 then the
 * "หัก  มัดจำ  ref" row, หมายเหตุ header B35 + the template's own note lines (never rewritten),
 * I42 net, I43 VAT, I44 grand total, B45 "( preparer )", B46 signature date.
 */
@Component
public class RemainingInvoicePdfRenderer {
    private static final String TEMPLATE = "templates/remaining_invoice_template.xls";
    private static final int NOTES_ROW = 34, NET = 41, VAT = 42, GRAND = 43, PREPARER = 44, SIGDATE = 45;

    public byte[] toXls(RemainingInvoiceRenderModel m) {
        Workbook wb = DocumentSheet.openTemplate(TEMPLATE);
        Sheet sh = wb.getSheet("Update");
        DocumentSheet.str(sh, 6, 1, DocumentSheet.customerLine(m.customerName(), m.customerBranch(), m.customerTaxId()));
        DocumentSheet.str(sh, 6, 7, DocumentSheet.thaiDate(m.docDate()));
        DocumentSheet.str(sh, 7, 1, m.customerAddress());
        DocumentSheet.str(sh, 7, 7, m.docNumber());
        DocumentSheet.str(sh, 8, 7, m.reference());
        DocumentSheet.str(sh, 10, 1, "Project : " + (m.projectName() == null ? "" : m.projectName()));

        List<DocumentSheet.Line> lines = new ArrayList<>();
        for (RemainingInvoiceItemDto i : m.items())
            lines.add(new DocumentSheet.Line(i.seq(), i.description(), i.qty(), i.unit(), i.unitPrice(),
                i.netUnitPrice(), i.amount()));
        BigDecimal deposit = m.depositDeduction() == null ? BigDecimal.ZERO : m.depositDeduction();
        if (deposit.signum() != 0) {
            lines.add(new DocumentSheet.Line(0, "หัก  มัดจำ  " + (m.depositReference() == null ? "" : m.depositReference()),
                BigDecimal.ONE.negate(), null, deposit, deposit, deposit.negate()));
        }
        List<int[]> blocks = new ArrayList<>();
        int d = DocumentSheet.fillItems(sh, lines, NOTES_ROW, blocks);

        DocumentSheet.num(sh, NET + d, 8, m.netAmount());
        DocumentSheet.num(sh, VAT + d, 8, m.vatAmount());
        DocumentSheet.num(sh, GRAND + d, 8, m.grandTotal());
        DocumentSheet.preparer(sh, PREPARER + d, m.preparerName());
        DocumentSheet.signatureDate(sh, SIGDATE + d, m.docDate());

        BigDecimal maxUnit = deposit;
        for (RemainingInvoiceItemDto i : m.items()) {
            if (i.unitPrice() != null) maxUnit = maxUnit.max(i.unitPrice());
            if (i.netUnitPrice() != null) maxUnit = maxUnit.max(i.netUnitPrice());
        }
        DocumentSheet.fitMoney(sh, 4, maxUnit);
        DocumentSheet.fitMoney(sh, 7, maxUnit);
        DocumentSheet.fitMoney(sh, 8, m.netAmount(), m.vatAmount(), m.grandTotal(), deposit);
        DocumentSheet.finishPage(sh, d, NOTES_ROW + d, SIGDATE + d, blocks);
        return DocumentSheet.bytes(wb);
    }

    public byte[] toPdf(RemainingInvoiceRenderModel m) {
        return LibreOfficePdfConverter.convert(toXls(m));
    }
}
