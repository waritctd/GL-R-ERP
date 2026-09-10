package th.co.glr.hr.dealquotation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import th.co.glr.hr.common.sheet.SheetHtmlRenderer;
import th.co.glr.hr.common.sheet.SheetPlan;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * Quotation v2 HTML/Chromium PDF — the direct-deal quotation drawn as HTML. There is NO separate
 * HTML layout of the quotation: the document is the POI workbook the XLS engine
 * ({@link QuotationRenderer#toXls(QuotationRenderModel)}) just wrote, read back as ONE row plan
 * ({@link SheetPlan}) and drawn cell-for-cell, rule-for-rule by {@link SheetHtmlRenderer} with
 * LibreOffice's page geometry. Whatever the XLS path emits — its v2 row arithmetic, cloned item
 * rows, compacted remark rows, relocated footer block, money-column sizing, fit zoom, row breaks,
 * signature rows and the anchored approver image — is what prints here, by construction. The
 * owner's acceptance ("visually EXACTLY the same as the Excel render, including border coverage
 * and line thickness") is pinned by {@code HtmlXlsFidelityTest}.
 */
public final class QuotationHtmlDocument {
    private QuotationHtmlDocument() {}

    private static final String SHEET = "Update";

    /** Renders the model through the XLS engine, then draws that sheet. */
    public static String render(QuotationRenderModel model) {
        return render(new QuotationRenderer().toXls(model), model);
    }

    /** Draws an already-rendered quotation workbook. {@code model} (nullable) only lets the
     * approver's signature picture be tagged {@code sig-image} for readers of the HTML. */
    public static String render(byte[] xlsBytes, QuotationRenderModel model) {
        return rendered(xlsBytes, model).html();
    }

    public static SheetHtmlRenderer.Rendered rendered(byte[] xlsBytes, QuotationRenderModel model) {
        byte[] signature = model != null && model.signatories() != null ? model.signatories().approverSignaturePng() : null;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsBytes))) {
            Sheet sheet = wb.getSheet(SHEET);
            if (sheet == null) sheet = wb.getSheetAt(0);
            return SheetHtmlRenderer.render(wb, sheet, pic -> classify(pic, signature));
        } catch (IOException e) {
            throw new RuntimeException("Quotation HTML render failed: " + e.getMessage(), e);
        }
    }

    // The template embeds exactly two pictures — the GL&R wordmark (a JPEG) and the URS/UKAS badge
    // pair (a PNG); the only other picture the XLS path ever adds is the approver's signature.
    private static String classify(SheetPlan.Picture pic, byte[] signature) {
        if (signature != null && Arrays.equals(signature, pic.data())) return "sig-image";
        return pic.mimeType() != null && pic.mimeType().contains("jpeg") ? "logo" : "badge";
    }
}
