package th.co.glr.hr.leave;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ChromiumPdfPrinter;

/**
 * Renders the ใบลา F-HR-020 to PDF bytes, choosing the engine: headless Chromium drawing the HTML
 * replica ({@link LeaveFormHtmlRenderer}) — the same engine the direct-deal quotation uses, and the
 * one that carries the GL&R logo and matches the form's look — with the PDFBox text renderer
 * ({@link LeaveFormRenderer}) as a fallback.
 *
 * <p>Unlike the quotation (which 503s loudly when Chromium is absent), this falls back rather than
 * failing: the leave-submission email is a best-effort after-commit side effect, so a Chromium outage
 * must still produce an attachable form, not drop the email. Which engine ran is logged.
 */
@Component
public class LeaveFormPdf {
    private static final Logger log = LoggerFactory.getLogger(LeaveFormPdf.class);

    private final LeaveFormHtmlRenderer htmlRenderer;
    private final LeaveFormRenderer pdfBoxRenderer;

    public LeaveFormPdf(LeaveFormHtmlRenderer htmlRenderer, LeaveFormRenderer pdfBoxRenderer) {
        this.htmlRenderer = htmlRenderer;
        this.pdfBoxRenderer = pdfBoxRenderer;
    }

    public byte[] toPdf(LeaveFormData data) {
        if (ChromiumPdfPrinter.isAvailable()) {
            try {
                return ChromiumPdfPrinter.print(htmlRenderer.render(data));
            } catch (RuntimeException e) {
                // A Chromium that was available but died mid-render must not lose the attachment.
                log.warn("Chromium leave-form render failed, falling back to PDFBox: {}", e.getMessage());
            }
        } else {
            log.info("Chromium unavailable; rendering leave form via PDFBox fallback");
        }
        return pdfBoxRenderer.toPdf(data);
    }
}
