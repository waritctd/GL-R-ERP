package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.RemainingInvoiceDocumentDto;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.deposit.RemainingInvoiceService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.AttachType;

/**
 * Item 3: the PDF the account team downloads / sends to the customer is the document, then the deal's PO, then the
 * quotation PDF it references (whose own last page(s) are the terms & conditions form): notice, PO, quotation,
 * conditions. Real services over real Postgres, real LibreOffice. The Excel download stays notice-only.
 *
 * <p>WIRING NOTE for the implementer: {@code depositNoticeService} (and the remaining-invoice service below) are
 * built by {@link DirectQuotationDealTestBase}/this class with today's constructors. Whatever new collaborators the
 * bundle needs (attachment repository, file storage rooted at {@link #UPLOADS}, the quotation renderer) must be
 * added at those two construction sites; no assertion below depends on the shape of that wiring.
 */
class DepositNoticeBundlePdfIntegrationTest extends DirectQuotationDealTestBase {
    /** The directory the PO file is stored under; the implementer's FileStorageService must resolve the same one. */
    static final String UPLOADS = "/tmp/glr-notice-bundle-test-uploads";
    private static final String PO_SENTINEL = "PO-SENTINEL-7731";

    private final FileStorageService fileStorage = new FileStorageService(UPLOADS);
    private AttachmentRepository attachments;
    private RemainingInvoiceService remainingInvoiceService;

    @BeforeEach
    void requirePdfEngine() {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(), "LibreOffice (soffice) not installed");
        attachments = new AttachmentRepository(jdbc);
        remainingInvoiceService = new RemainingInvoiceService(new RemainingInvoiceRepository(jdbc),
            depositNoticeService, tickets, new RemainingInvoicePdfRenderer(),
            new NotificationRepository(jdbc, mailer));
    }

    // ── deposit notice ────────────────────────────────────────────────────────────────────────

    @Test
    void depositNoticePdf_isFollowedByThePo_thenTheReferencedQuotation_thenTheConditions() throws Exception {
        Deal deal = confirmedDepositDeal();
        attachPo(deal.ticketId(), onePagePdf(PO_SENTINEL));

        List<String> pages = pageTexts(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).pdf());

        int quotationPages = pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor));
        int poPage = firstPageContaining(pages, PO_SENTINEL);
        assertThat(poPage).as("the PO follows the notice, which is page 0").isGreaterThanOrEqualTo(1);
        assertThat(pages).as("notice pages + 1 PO page + the whole quotation PDF (its conditions included)")
            .hasSize(poPage + 1 + quotationPages);
        assertThat(firstPageContaining(pages.subList(poPage + 1, pages.size()), deal.quotation().number()))
            .as("the quotation starts right after the PO").isZero();
        assertThat(pages.get(pages.size() - 1))
            .as("the conditions page is last").isEqualTo(lastPage(quotationService.renderPdf(deal.quotation().id(), salesActor)));
    }

    @Test
    void depositNoticePdf_withNoPo_isStillFollowedByTheQuotationAndItsConditions() throws Exception {
        Deal deal = confirmedDepositDeal();

        List<String> pages = pageTexts(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).pdf());

        byte[] quotationPdf = quotationService.renderPdf(deal.quotation().id(), salesActor);
        assertThat(pages).as("a single notice page + the quotation PDF").hasSize(1 + pageCount(quotationPdf));
        assertThat(pages.get(pages.size() - 1)).isEqualTo(lastPage(quotationPdf));
        assertThat(pages.stream().filter(p -> p.contains(PO_SENTINEL))).isEmpty();
    }

    /** Guard (green today, must stay green): the Excel download is the notice alone. */
    @Test
    void depositNoticeXlsx_staysNoticeOnly_evenWhenAPoExists() throws Exception {
        Deal deal = confirmedDepositDeal();
        attachPo(deal.ticketId(), onePagePdf(PO_SENTINEL));

        byte[] xls = depositNoticeService.getXlsx(deal.notice().id(), salesActor);

        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xls))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
        }
    }

    /** Ploy's ruling: anyone who can open the notice gets the quotation it references, even after that quotation was
     * revised (SUPERSEDED), which the account role may not open on the quotation endpoint. */
    @Test
    void aSupersededQuotation_isStillAttached_forAnAccountViewer() throws Exception {
        Deal deal = confirmedDepositDeal();
        jdbc.update("UPDATE sales.quotation SET doc_status = 'SUPERSEDED' WHERE quotation_id = :id",
            java.util.Map.of("id", deal.quotation().id())); // as a later revision leaves it
        assertForbidden(() -> quotationService.renderPdf(deal.quotation().id(), accountActor)); // the premise

        var bundled = depositNoticeService.getPdfBundle(deal.notice().id(), accountActor);

        assertThat(bundled.skipped()).isEmpty();
        assertThat(pageTexts(bundled.pdf())).as("notice + the referenced quotation")
            .hasSize(1 + pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor)));
    }

    /** The same ruling for a PRICING_REQUEST-origin quotation: account (already scoped to the deal by the notice access)
     * still gets it attached once it is SUPERSEDED; no status filter applies. */
    @Test
    void aSupersededPricingRequestQuotation_isStillAttached_forAnAccountViewer() throws Exception {
        Deal deal = confirmedDepositDeal();
        jdbc.update("UPDATE sales.quotation SET origin = 'PRICING_REQUEST', doc_status = 'SUPERSEDED'"
            + " WHERE quotation_id = :id", java.util.Map.of("id", deal.quotation().id()));
        assertForbidden(() -> quotationService.renderPdf(deal.quotation().id(), accountActor)); // the premise

        var bundled = depositNoticeService.getPdfBundle(deal.notice().id(), accountActor);

        assertThat(bundled.skipped()).isEmpty();
        assertThat(pageTexts(bundled.pdf())).as("notice + the quotation")
            .hasSize(1 + pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor)));
    }

    /** account reaches an issued notice of a DEAD deal outside its list scope (the issued-notice exception); that must
     * not hand it the CEO-approved prices of a PRICING_REQUEST-origin quotation. */
    @Test
    void anOutOfScopeAccountOnADeadDeal_getsTheNoticeButNotAPricingRequestQuotation() throws Exception {
        Deal deal = confirmedDepositDeal();
        jdbc.update("UPDATE sales.quotation SET origin = 'PRICING_REQUEST' WHERE quotation_id = :id",
            java.util.Map.of("id", deal.quotation().id()));
        jdbc.update("UPDATE sales.deposit_notice SET status = 'ISSUED', doc_number = 'DEAD-1' WHERE deposit_notice_id = :id",
            java.util.Map.of("id", deal.notice().id()));
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :id",
            java.util.Map.of("id", deal.ticketId()));
        assertThat(tickets.isInAccountScope(deal.ticketId())).as("premise: outside account's scope").isFalse();
        assertThat(tickets.isDeadDealWithIssuedDepositNotice(deal.ticketId())).as("premise: the exception").isTrue();

        var bundled = depositNoticeService.getPdfBundle(deal.notice().id(), accountActor);

        assertThat(pageTexts(bundled.pdf())).as("the notice only").hasSize(1);
        assertThat(bundled.skipped()).containsExactly("quotation");
    }

    /** What stays gated: a PRICING_REQUEST-origin quotation carries the CEO's approved prices, which import never sees. */
    @Test
    void aPricingRequestQuotation_isLeftOutForImport_andSkippedSaysSo() throws Exception {
        Deal deal = confirmedDepositDeal();
        jdbc.update("UPDATE sales.quotation SET origin = 'PRICING_REQUEST' WHERE quotation_id = :id",
            java.util.Map.of("id", deal.quotation().id()));
        assertForbidden(() -> quotationService.renderPdf(deal.quotation().id(), importActor)); // the premise
        var bundle = new th.co.glr.hr.deposit.DocumentBundle(attachments, quotationService,
            new th.co.glr.hr.deposit.DepositNoticeRepository(jdbc));

        var bundled = bundle.bundle(onePagePdf("NOTICE"), deal.ticketId(), deal.quotation().number(), importActor);

        assertThat(pageTexts(bundled.pdf())).as("the document only").hasSize(1);
        assertThat(bundled.skipped()).containsExactly("quotation");
    }

    /** A reference that names no new-engine quotation (a legacy / old-engine number) has nothing to attach: not a skip. */
    @Test
    void aReferenceWithNoNewEngineQuotation_isNotReportedAsSkipped() {
        Deal deal = confirmedDepositDeal();
        jdbc.update("UPDATE sales.deposit_notice SET reference = 'NO-SUCH-QT' WHERE deposit_notice_id = :id",
            java.util.Map.of("id", deal.notice().id()));

        assertThat(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).skipped()).isEmpty();
    }

    /** The ungated render is bound to the deal: asked for another deal's quotation it refuses (404). */
    @Test
    void renderPdfForDealDocument_refusesAQuotationOfAnotherDeal() {
        Deal deal = confirmedDepositDeal();
        long otherTicket = pipelineTicket(salesActor);

        assertStatus(org.springframework.http.HttpStatus.NOT_FOUND,
            () -> quotationService.renderPdfForDealDocument(deal.quotation().id(), otherTicket, salesActor));
    }

    /** A PNG PO is fitted onto one A4 page between the notice and the quotation; an unsupported type is skipped. */
    @Test
    void anImagePo_becomesOnePage_andAnUnsupportedPoTypeIsSkipped() throws Exception {
        Deal deal = confirmedDepositDeal();
        attachPoFile(deal.ticketId(), "po.png", "image/png", onePixelPng());
        attachPoFile(deal.ticketId(), "po.txt", "text/plain", "not a pdf".getBytes());

        byte[] bundled = depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).pdf();

        int quotationPages = pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor));
        List<String> pages = pageTexts(bundled);
        assertThat(pages).as("notice (1) + the image page + the quotation PDF").hasSize(1 + 1 + quotationPages);
        try (PDDocument doc = Loader.loadPDF(bundled)) {
            assertThat(doc.getPage(1).getMediaBox().getWidth()).as("the image page is A4").isCloseTo(595.28f, org.assertj.core.api.Assertions.within(0.1f));
        }
    }

    /** Several POs: all of them, oldest upload first, between the notice and the quotation. */
    @Test
    void severalPos_areAllIncluded_oldestFirst() throws Exception {
        Deal deal = confirmedDepositDeal();
        attachPo(deal.ticketId(), onePagePdf("PO-FIRST-1111"));
        attachPo(deal.ticketId(), onePagePdf("PO-SECOND-2222"));

        List<String> pages = pageTexts(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).pdf());

        int first = firstPageContaining(pages, "PO-FIRST-1111");
        int second = firstPageContaining(pages, "PO-SECOND-2222");
        assertThat(first).isGreaterThanOrEqualTo(1);
        assertThat(second).isEqualTo(first + 1);
        assertThat(pages).hasSize(second + 1 + pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor)));
    }

    /** A PO whose file is gone from disk is skipped with a warning: the download still succeeds with the rest. */
    @Test
    void aPoWhoseFileIsMissing_isSkipped_andTheDownloadStillSucceeds() throws Exception {
        Deal deal = confirmedDepositDeal();
        attachPo(deal.ticketId(), onePagePdf(PO_SENTINEL));
        jdbc.update("UPDATE sales.attachment SET file_path = '/tmp/glr-notice-bundle-test-uploads/gone.pdf'"
            + " WHERE ticket_id = :t", java.util.Map.of("t", deal.ticketId()));

        List<String> pages = pageTexts(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).pdf());

        assertThat(firstPageContaining(pages, PO_SENTINEL)).isEqualTo(-1);
        assertThat(pages).as("notice + quotation + conditions, no 500")
            .hasSize(1 + pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor)));
    }

    /** What was left out is reported (it becomes the X-Document-Bundle-Skipped header); a clean bundle reports none. */
    @Test
    void skippedPartsAreReported_andACleanBundleReportsNone() throws Exception {
        Deal deal = confirmedDepositDeal();
        assertThat(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).skipped()).isEmpty();

        attachPo(deal.ticketId(), onePagePdf(PO_SENTINEL));
        assertThat(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).skipped())
            .as("a readable PO is not a skip").isEmpty();

        jdbc.update("UPDATE sales.attachment SET file_path = '/tmp/glr-notice-bundle-test-uploads/gone.pdf'"
            + " WHERE ticket_id = :t", java.util.Map.of("t", deal.ticketId()));
        assertThat(depositNoticeService.getPdfBundle(deal.notice().id(), salesActor).skipped()).containsExactly("po");
    }

    // ── remaining invoice (the bypass/zero-deposit deal: its quotation is the answered one) ─────────

    @Test
    void remainingInvoicePdf_isFollowedByThePo_thenTheQuotation_thenTheConditions() throws Exception {
        long ticketId = quotationOnlyTicket(salesActor);
        DealQuotationDto quotation = approvedZeroDepositQuotation(ticketId);
        confirmer.confirmOrderFromDirectQuotation(quotation.id(), salesActor);
        attachPo(ticketId, onePagePdf(PO_SENTINEL));
        RemainingInvoiceDocumentDto invoice = remainingInvoiceService.createDraft(ticketId, null, salesActor);

        List<String> pages = pageTexts(remainingInvoiceService.getPdfBundle(invoice.id(), salesActor).pdf());

        int quotationPages = pageCount(quotationService.renderPdf(quotation.id(), salesActor));
        int poPage = firstPageContaining(pages, PO_SENTINEL);
        assertThat(poPage).isGreaterThanOrEqualTo(1);
        assertThat(pages).hasSize(poPage + 1 + quotationPages);
        assertThat(pages.get(pages.size() - 1))
            .isEqualTo(lastPage(quotationService.renderPdf(quotation.id(), salesActor)));
    }

    @Test
    void remainingInvoicePdf_withNoPo_isStillFollowedByTheQuotationAndItsConditions() throws Exception {
        long ticketId = quotationOnlyTicket(salesActor);
        DealQuotationDto quotation = approvedZeroDepositQuotation(ticketId);
        confirmer.confirmOrderFromDirectQuotation(quotation.id(), salesActor);
        RemainingInvoiceDocumentDto invoice = remainingInvoiceService.createDraft(ticketId, null, salesActor);

        List<String> pages = pageTexts(remainingInvoiceService.getPdfBundle(invoice.id(), salesActor).pdf());

        byte[] quotationPdf = quotationService.renderPdf(quotation.id(), salesActor);
        assertThat(pages).hasSize(1 + pageCount(quotationPdf));
        assertThat(pages.get(pages.size() - 1)).isEqualTo(lastPage(quotationPdf));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private record Deal(long ticketId, DealQuotationDto quotation, DepositNoticeDto notice) {}

    /** A confirmed direct quotation (50% deposit) and its DRAFT notice, whose reference is the quotation number. */
    private Deal confirmedDepositDeal() {
        long ticketId = quotationOnlyTicket(salesActor);
        DealQuotationDto quotation = approvedDirectQuotation(ticketId, salesActor);
        confirmer.confirmOrderFromDirectQuotation(quotation.id(), salesActor);
        DepositNoticeDto notice = depositNoticeService.createDraft(ticketId,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        assertThat(notice.reference()).as("the notice references its quotation").isEqualTo(quotation.number());
        return new Deal(ticketId, quotation, notice);
    }

    /** The PO as the account team uploads it: a PDF attachment of type PO on the deal. */
    private void attachPo(long ticketId, byte[] pdf) {
        attachPoFile(ticketId, "po.pdf", "application/pdf", pdf);
    }

    private void attachPoFile(long ticketId, String name, String mime, byte[] bytes) {
        FileStorageService.StoredFile stored = fileStorage.store("tickets", ticketId,
            new MockMultipartFile("file", name, mime, bytes), Set.of());
        attachments.save(ticketId, null, name, stored.filePath(), stored.mimeType(), stored.fileSize(),
            AttachType.PO, salesActor.id());
    }

    private static byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");
    }

    private static byte[] onePagePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> pageTexts(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc));
            }
            return pages;
        }
    }

    private static int pageCount(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    private static String lastPage(byte[] pdf) throws Exception {
        List<String> pages = pageTexts(pdf);
        return pages.get(pages.size() - 1);
    }

    private static int firstPageContaining(List<String> pages, String text) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
