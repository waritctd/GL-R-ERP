package th.co.glr.hr.deposit;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import th.co.glr.hr.attachment.AttachmentDto;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * What the customer receives with a ใบแจ้งยอดเงินรับมัดจำ / ใบแจ้งหนี้ส่วนที่เหลือ PDF: the document, then the deal's
 * PO(s) (oldest first), then the quotation PDF it references (its terms & conditions pages included). Best effort:
 * the caller has already been authorised on the deal, so the quotation is rendered without its own view gate (but
 * only if it belongs to that deal). A missing/unreadable PO or an unrenderable quotation is logged at WARN and skipped, never a failed download; the skipped kinds ("po", "quotation") are returned so the
 * download endpoints can say so in X-Document-Bundle-Skipped.
 * PDF POs are appended as-is, PNG/JPG POs are fitted onto one A4 page, any other type is skipped.
 */
@Component
public class DocumentBundle {
    private static final Logger log = LoggerFactory.getLogger(DocumentBundle.class);

    private final AttachmentRepository attachments;
    private final DealQuotationService quotations;
    private final DepositNoticeRepository docs;

    public DocumentBundle(AttachmentRepository attachments, @Lazy DealQuotationService quotations,
                          DepositNoticeRepository docs) {
        this.attachments = attachments;
        this.quotations = quotations;
        this.docs = docs;
    }

    /** The merged PDF and which parts were left out ("po", "quotation"); empty when nothing was skipped. */
    public record Result(byte[] pdf, List<String> skipped) {}

    public static final String SKIPPED_HEADER = "X-Document-Bundle-Skipped";

    /** The response header naming what was skipped, e.g. {@code po,quotation}; none when the bundle is complete. */
    public static org.springframework.http.HttpHeaders skippedHeader(Result bundle) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (!bundle.skipped().isEmpty()) {
            headers.set(SKIPPED_HEADER, String.join(",", bundle.skipped()));
        }
        return headers;
    }

    public Result bundle(byte[] documentPdf, long ticketId, String quotationNumber) {
        byte[] out = documentPdf;
        List<String> skipped = new ArrayList<>();
        List<AttachmentDto> pos = attachments.findByTicketId(ticketId).stream()
            .filter(a -> AttachType.PO.equals(a.attachType()))
            .sorted(Comparator.comparingLong(AttachmentDto::id)).toList();
        for (AttachmentDto po : pos) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(attachments.findFilePathById(po.id())));
                byte[] pdf = isPdf(bytes) ? bytes : imagePage(bytes);
                if (pdf == null) {
                    log.warn("PO attachment {} on ticket {} is neither PDF nor PNG/JPG; skipped", po.id(), ticketId);
                    addOnce(skipped, "po");
                    continue;
                }
                out = QuotationRenderer.appendPdf(out, pdf);
            } catch (Exception e) {
                log.warn("PO attachment {} on ticket {} could not be added to the PDF bundle", po.id(), ticketId, e);
                addOnce(skipped, "po");
            }
        }
        if (quotationNumber != null && !quotationNumber.isBlank()) {
            try {
                Long quotationId = docs.findNewEngineQuotationId(ticketId, quotationNumber).orElse(null);
                if (quotationId != null) {
                    out = QuotationRenderer.appendPdf(out, quotations.renderPdfForDealDocument(quotationId, ticketId));
                }
            } catch (Exception e) {
                log.warn("Quotation {} could not be added to the PDF bundle of ticket {}", quotationNumber, ticketId, e);
                addOnce(skipped, "quotation");
            }
        }
        return new Result(out, skipped);
    }

    private static void addOnce(List<String> skipped, String part) {
        if (!skipped.contains(part)) {
            skipped.add(part);
        }
    }

    private static boolean isPdf(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** One A4 page with the PNG/JPG fitted inside a 36pt margin; null when the bytes are not an image PDFBox reads. */
    private static byte[] imagePage(byte[] bytes) throws Exception {
        boolean png = bytes.length > 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P';
        boolean jpg = bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        if (!png && !jpg) {
            return null;
        }
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, bytes, "po");
            float box = 36f;
            float scale = Math.min((page.getMediaBox().getWidth() - 2 * box) / image.getWidth(),
                (page.getMediaBox().getHeight() - 2 * box) / image.getHeight());
            float w = image.getWidth() * scale;
            float h = image.getHeight() * scale;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, (page.getMediaBox().getWidth() - w) / 2, page.getMediaBox().getHeight() - box - h, w, h);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
