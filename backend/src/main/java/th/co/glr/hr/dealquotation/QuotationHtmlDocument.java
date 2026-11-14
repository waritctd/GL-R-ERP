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

/** Quotation v2 HTML/Chromium PDF — the direct-deal quotation drawn as HTML. The owner's acceptance ("visually
 * EXACTLY the same as the Excel render, including border coverage and line thickness") is pinned by
 * HtmlXlsFidelityTest. */
public final class QuotationHtmlDocument {
    private QuotationHtmlDocument() {}

    private static final String SHEET = "Update";

    /** Renders the model through the XLS engine, then draws that sheet. */
    public static String render(QuotationRenderModel model) {
        return render(new QuotationRenderer().toXls(model), model);
    }

    /** Draws an already-rendered quotation workbook. model (nullable) only lets the approver's signature picture be
     * tagged sig-image for readers of the HTML. */
    public static String render(byte[] xlsBytes, QuotationRenderModel model) {
        return rendered(xlsBytes, model).html();
    }

    public static SheetHtmlRenderer.Rendered rendered(byte[] xlsBytes, QuotationRenderModel model) {
        byte[] signature = model != null && model.signatories() != null ? model.signatories().approverSignaturePng() : null;
        java.util.List<byte[]> itemPictures = new java.util.ArrayList<>();
        if (model != null && model.items() != null) {
            for (QuotationRenderModel.RenderItem item : model.items()) {
                if (item.picture() != null && item.picture().data() != null) {
                    itemPictures.add(item.picture().data());
                }
            }
        }
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsBytes))) {
            Sheet sheet = wb.getSheet(SHEET);
            if (sheet == null) sheet = wb.getSheetAt(0);
            return SheetHtmlRenderer.render(wb, sheet, pic -> classify(pic, signature, itemPictures));
        } catch (IOException e) {
            throw new RuntimeException("Quotation HTML render failed: " + e.getMessage(), e);
        }
    }

    // The template embeds exactly two pictures — the GL&R wordmark (a JPEG) and the URS/UKAS badge pair (a PNG);
    // the XLS path adds the approver's signature and (GLA-75) each item's picture. Item pictures are matched by
    // content BEFORE the mime fallback, or a JPEG product photo would be tagged "logo".
    private static String classify(SheetPlan.Picture pic, byte[] signature, java.util.List<byte[]> itemPictures) {
        if (signature != null && Arrays.equals(signature, pic.data())) return "sig-image";
        for (byte[] itemPicture : itemPictures) {
            if (Arrays.equals(itemPicture, pic.data())) return "item-image";
        }
        return pic.mimeType() != null && pic.mimeType().contains("jpeg") ? "logo" : "badge";
    }
}
