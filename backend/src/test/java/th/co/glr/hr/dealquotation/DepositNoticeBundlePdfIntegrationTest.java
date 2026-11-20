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

    /** The ungated render is bound to the deal: asked for another deal's quotation it refuses (404). */
    @Test
    void renderPdfForDealDocument_refusesAQuotationOfAnotherDeal() {
        Deal deal = confirmedDepositDeal();
        long otherTicket = pipelineTicket(salesActor);

        assertStatus(org.springframework.http.HttpStatus.NOT_FOUND,
            () -> quotationService.renderPdfForDealDocument(deal.quotation().id(), otherTicket, salesActor));
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

    // ── customer header follows the quotation's party ───────────────────────────────────────

    /** A second BUYER party (another customer) and the quotation re-addressed to it. */
    private th.co.glr.hr.customer.CustomerDto addressToSecondBuyer(long ticketId, long quotationId) {
        var buyer = customers.create("คุณมะนาว", "0100000000444", "44 ถนนทดสอบ", "สนญ.", "02-000-0444");
        Long partyId = jdbc.queryForObject("INSERT INTO sales.deal_party (ticket_id, customer_id, role, status)"
            + " VALUES (:t, :c, 'BUYER', 'QUOTED') RETURNING party_id",
            java.util.Map.of("t", ticketId, "c", buyer.id()), Long.class);
        jdbc.update("UPDATE sales.quotation SET party_id = :p WHERE quotation_id = :id",
            java.util.Map.of("p", partyId, "id", quotationId));
        return buyer;
    }

    /** A deal with two BUYER parties: the notice built from the second party's quotation names THAT customer, not the
     * deal's headline one. */
    @Test
    void noticeCustomerComesFromTheReferencedQuotationsParty() throws Exception {
        long ticketId = quotationOnlyTicket(salesActor);
        DealQuotationDto quotation = approvedDirectQuotation(ticketId, salesActor);
        confirmer.confirmOrderFromDirectQuotation(quotation.id(), salesActor);
        var buyer = addressToSecondBuyer(ticketId, quotation.id());

        DepositNoticeDto notice = depositNoticeService.createDraft(ticketId,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);

        assertThat(notice.reference()).isEqualTo(quotation.number());
        assertThat(notice.customerName()).isEqualTo(buyer.name());
        assertThat(notice.customerTaxId()).isEqualTo(buyer.taxId());
        assertThat(notice.customerAddress()).contains("44 ถนนทดสอบ");
        assertThat(notice.customerName()).isNotEqualTo(customer.name());

        // an already-stored notice naming the headline customer still reads and prints the quotation's customer
        jdbc.update("UPDATE sales.deposit_notice SET customer_name = :n, customer_tax_id = '999' WHERE deposit_notice_id = :id",
            java.util.Map.of("n", customer.name(), "id", notice.id()));
        assertThat(depositNoticeService.getById(notice.id(), salesActor).customerName()).isEqualTo(buyer.name());
        assertThat(pageTexts(depositNoticeService.getPdfBundle(notice.id(), salesActor).pdf()).get(0))
            .contains(buyer.name()).doesNotContain(customer.name());
    }

    @Test
    void remainingInvoiceCustomerComesFromTheQuotationsParty() {
        long ticketId = quotationOnlyTicket(salesActor);
        DealQuotationDto quotation = approvedZeroDepositQuotation(ticketId);
        confirmer.confirmOrderFromDirectQuotation(quotation.id(), salesActor);
        var buyer = addressToSecondBuyer(ticketId, quotation.id());

        RemainingInvoiceDocumentDto invoice = remainingInvoiceService.createDraft(ticketId, null, salesActor);

        assertThat(invoice.customerName()).isEqualTo(buyer.name());
        assertThat(invoice.customerTaxId()).isEqualTo(buyer.taxId());
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
