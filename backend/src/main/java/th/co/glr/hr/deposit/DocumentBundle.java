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
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * What the customer receives with a ใบแจ้งยอดเงินรับมัดจำ / ใบแจ้งหนี้ส่วนที่เหลือ PDF: the document, then the deal's
 * chosen attachments (by default every PO, oldest first), then the quotation PDF it references (by default) (its terms & conditions pages included). Best effort:
 * the quotation is rendered with the requesting user's own quotation view gate (and only if it belongs to the deal), so
 * a viewer the quotation endpoint refuses gets the document without it. A missing/unreadable PO, an unknown, refused
 * or unrenderable quotation is logged at WARN and skipped, never a failed download; the skipped kinds ("po",
 * "quotation") are returned so the download endpoints can say so in X-Document-Bundle-Skipped.
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

    /** The merged PDF, which parts were left out ("po", "attachment", "quotation"), and whether the chosen files add up
     * to more than {@link #LARGE_BYTES} (a heavy email attachment). */
    public record Result(byte[] pdf, List<String> skipped, boolean large) {
        public Result(byte[] pdf, List<String> skipped) {
            this(pdf, skipped, false);
        }
    }

    /** What goes into the bundle: whether the referenced quotation does, and which deal attachments, in order. */
    public record Selection(boolean quotation, List<Long> attachmentIds) {}

    public static final String SKIPPED_HEADER = "X-Document-Bundle-Skipped";
    public static final String LARGE_HEADER = "X-Document-Bundle-Large";
    static final long LARGE_BYTES = 15L * 1024 * 1024;

    /** The response headers naming what was skipped, e.g. {@code po,quotation}, and a too-large bundle; none when clean. */
    public static org.springframework.http.HttpHeaders skippedHeader(Result bundle) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (!bundle.skipped().isEmpty()) {
            headers.set(SKIPPED_HEADER, String.join(",", bundle.skipped()));
        }
        if (bundle.large()) {
            headers.set(LARGE_HEADER, "true");
        }
        return headers;
    }

    /** The choice a download would use with no parameters: the remembered one, else the quotation and every PO. */
    public Selection selection(String kind, long documentId, long ticketId) {
        var saved = kind == null ? null : docs.findBundleSelection(kind, documentId).orElse(null);
        if (saved != null) {
            return new Selection(saved.includeQuotation(), saved.attachmentIds());
        }
        return new Selection(true, attachments.findByTicketId(ticketId).stream()
            .filter(a -> AttachType.PO.equals(a.attachType()))
            .sorted(Comparator.comparingLong(AttachmentDto::id)).map(AttachmentDto::id).toList());
    }

    /** The {@code attachments=} query value: null = not supplied, "" = none, else comma-separated ids (400 if not numbers). */
    public static List<Long> parseIds(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .map(Long::valueOf).toList();
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รายการไฟล์แนบไม่ถูกต้อง");
        }
    }

    /** The defaults, nothing remembered (callers that have no stored document). */
    public Result bundle(byte[] documentPdf, long ticketId, String quotationNumber, UserPrincipal actor) {
        return bundle(documentPdf, ticketId, quotationNumber, actor, null, 0, null, null);
    }

    /**
     * @param kind / documentId  the document whose last choice is remembered ({@code kind == null}: defaults, no memory)
     * @param quotationParam     null = not supplied (remembered / default applies)
     * @param attachmentParam    null = not supplied; every id must be an attachment of THIS deal (400 otherwise).
     *                           Supplying either parameter saves the resulting choice as the document's last one.
     */
    public Result bundle(byte[] documentPdf, long ticketId, String quotationNumber, UserPrincipal actor,
                         String kind, long documentId, Boolean quotationParam, List<Long> attachmentParam) {
        byte[] out = documentPdf;
        List<String> skipped = new ArrayList<>();
        // No per-attachment gate here: every role that may read a notice (owner/keyer, grant holder, sales_manager, ceo,
        // account in scope) may also list and download the deal's attachments (TicketAccessPolicy.canViewDocuments).
        java.util.Map<Long, AttachmentDto> deal = new java.util.LinkedHashMap<>();
        try {
            attachments.findByTicketId(ticketId).stream().sorted(Comparator.comparingLong(AttachmentDto::id))
                .forEach(a -> deal.put(a.id(), a));
        } catch (Exception e) {
            log.warn("Attachments of ticket {} could not be listed for the PDF bundle", ticketId, e);
            addOnce(skipped, "po");
        }
        if (attachmentParam != null) {
            for (Long id : attachmentParam) {
                if (!deal.containsKey(id)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์แนบที่เลือกไม่ใช่ของงานนี้");
                }
            }
        }
        Selection chosen = selection(kind, documentId, ticketId);
        boolean withQuotation = quotationParam != null ? quotationParam : chosen.quotation();
        List<Long> ids = (attachmentParam != null ? attachmentParam : chosen.attachmentIds()).stream().distinct()
            .filter(deal::containsKey).toList();
        if (kind != null && (quotationParam != null || attachmentParam != null)) {
            docs.saveBundleSelection(kind, documentId, withQuotation, ids);
        }
        long totalBytes = 0;
        for (Long id : ids) {
            AttachmentDto file = deal.get(id);
            String part = AttachType.PO.equals(file.attachType()) ? "po" : "attachment";
            totalBytes += file.fileSize() == null ? 0 : file.fileSize();
            try {
                byte[] bytes = Files.readAllBytes(Path.of(attachments.findFilePathById(id)));
                byte[] pdf = isPdf(bytes) ? bytes : imagePage(bytes);
                if (pdf == null) {
                    log.warn("Attachment {} on ticket {} is neither PDF nor PNG/JPG; skipped", id, ticketId);
                    addOnce(skipped, part);
                    continue;
                }
                out = QuotationRenderer.appendPdf(out, pdf);
            } catch (Exception e) {
                log.warn("Attachment {} on ticket {} could not be added to the PDF bundle", id, ticketId, e);
                addOnce(skipped, part);
            }
        }
        if (withQuotation && quotationNumber != null && !quotationNumber.isBlank()) {
            try {
                Long quotationId = docs.findNewEngineQuotationId(ticketId, quotationNumber).orElse(null);
                if (quotationId == null) {
                    // a legacy / old-engine reference: nothing to attach, which is not a skip
                    log.info("Quotation {} of ticket {} is not a new-engine quotation; nothing to attach", quotationNumber, ticketId);
                } else {
                    out = QuotationRenderer.appendPdf(out,
                        quotations.renderPdfForDealDocument(quotationId, ticketId, actor));
                }
            } catch (Exception e) {
                log.warn("Quotation {} could not be added to the PDF bundle of ticket {}", quotationNumber, ticketId, e);
                addOnce(skipped, "quotation");
            }
        }
        return new Result(out, skipped, totalBytes > LARGE_BYTES);
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
