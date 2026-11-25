package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.RemainingInvoiceDocumentDto;
import th.co.glr.hr.deposit.RemainingInvoiceDraftRequest;
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
        // a read-all-flag holder (hr) sees the approved prices on that row, as the sales manager does
        UserPrincipal holder = actor(createEmployee("ผู้อ่านราคา 222", "price-reader-222@glr.co.th", "HR", "ฝ่ายบุคคล", null), "hr");
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id = :id", java.util.Map.of("id", holder.id()));
        assertThat(quotationService.renderPdf(deal.quotation().id(), holder)).isNotEmpty();
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

    // ── the download sheet's choice ─────────────────────────────────────────────────────────────

    /** Defaults are the quotation + every PO, oldest first; the sheet PUTs a choice (PO off, another file on, quotation
     * off), which is what the next PDF holds; a foreign attachment id is a 400; chosen files over the cap are a 413. */
    @Test
    void theDownloadSheetChoice_picksTheFiles_andIsRemembered() throws Exception {
        Deal deal = confirmedDepositDeal();
        long id = deal.notice().id();
        attachPo(deal.ticketId(), onePagePdf("PO-FIRST-1111"));
        attachPo(deal.ticketId(), onePagePdf(PO_SENTINEL));
        long other = attachFile(deal.ticketId(), "x.pdf", "application/pdf", onePagePdf("OTHER-SENTINEL"), AttachType.OTHER);
        int quotationPages = pageCount(quotationService.renderPdf(deal.quotation().id(), salesActor));

        List<String> defaults = pageTexts(depositNoticeService.getPdfBundle(id, salesActor).pdf());
        assertThat(firstPageContaining(defaults, "PO-FIRST-1111")).as("oldest PO first")
            .isLessThan(firstPageContaining(defaults, PO_SENTINEL));
        assertThat(firstPageContaining(defaults, "OTHER-SENTINEL")).as("an OTHER file is unticked").isEqualTo(-1);
        assertThat(defaults).hasSize(1 + 2 + quotationPages);

        var choice = new th.co.glr.hr.deposit.DocumentBundle.Selection(false, List.of(other));
        depositNoticeService.saveBundleSelection(id, salesActor, choice);
        depositNoticeService.saveBundleSelection(id, salesActor, choice); // a double tap is idempotent
        List<String> chosen = pageTexts(depositNoticeService.getPdfBundle(id, salesActor).pdf());
        assertThat(firstPageContaining(chosen, "OTHER-SENTINEL")).isGreaterThanOrEqualTo(1);
        assertThat(firstPageContaining(chosen, PO_SENTINEL)).isEqualTo(-1);
        assertThat(chosen).as("notice + the ticked file, no quotation").hasSize(2);
        assertThat(depositNoticeService.bundleSelection(id, salesActor).attachmentIds()).containsExactly(other);

        // a reader (sales_manager reads every deal) can download but not overwrite the owner's remembered choice
        assertStatus(org.springframework.http.HttpStatus.FORBIDDEN, () -> depositNoticeService.saveBundleSelection(id,
            salesManagerActor, new th.co.glr.hr.deposit.DocumentBundle.Selection(true, List.of())));
        assertThat(depositNoticeService.bundleSelection(id, salesActor).attachmentIds()).containsExactly(other);

        long foreign = attachFile(pipelineTicket(salesActor), "f.pdf", "application/pdf", onePagePdf("F"), AttachType.OTHER);
        assertStatus(org.springframework.http.HttpStatus.BAD_REQUEST, () -> depositNoticeService.saveBundleSelection(id,
            salesActor, new th.co.glr.hr.deposit.DocumentBundle.Selection(true, List.of(foreign))));

        jdbc.update("UPDATE sales.attachment SET file_size = 41943041 WHERE attachment_id = :id", java.util.Map.of("id", other));
        assertStatus(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
            () -> depositNoticeService.getPdfBundle(id, salesActor));
    }

    /** A read-all-flag reader's bundle leaves out files of a DRAFT delivery round the owner ticked (sales' private work);
     * the owner and the sales manager still get them. */
    @Test
    void aFlagOnlyReadersBundle_skipsDraftRoundFiles() throws Exception {
        Deal deal = confirmedDepositDeal();
        long id = deal.notice().id();
        long roundId = jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status, requested_by)
            VALUES (:t, 1, 'DRAFT', :u) RETURNING delivery_request_id
            """, java.util.Map.of("t", deal.ticketId(), "u", salesActor.id()), Long.class);
        long draftFile = attachFile(deal.ticketId(), "d.pdf", "application/pdf", onePagePdf("DRAFT-ROUND-SENTINEL"), AttachType.OTHER);
        jdbc.update("UPDATE sales.attachment SET delivery_request_id = :r WHERE attachment_id = :a",
            java.util.Map.of("r", roundId, "a", draftFile));
        depositNoticeService.saveBundleSelection(id, salesActor,
            new th.co.glr.hr.deposit.DocumentBundle.Selection(false, List.of(draftFile)));
        UserPrincipal reader = actor(createEmployee("ผู้อ่าน 222", "reader-222@glr.co.th", "HR", "ฝ่ายบุคคล", null), "hr");
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id = :id", java.util.Map.of("id", reader.id()));

        assertThat(firstPageContaining(pageTexts(depositNoticeService.getPdfBundle(id, reader).pdf()), "DRAFT-ROUND-SENTINEL"))
            .as("reader").isEqualTo(-1);
        assertThat(firstPageContaining(pageTexts(depositNoticeService.getPdfBundle(id, salesActor).pdf()), "DRAFT-ROUND-SENTINEL"))
            .as("owner").isGreaterThanOrEqualTo(1);
        assertThat(firstPageContaining(pageTexts(depositNoticeService.getPdfBundle(id, salesManagerActor).pdf()), "DRAFT-ROUND-SENTINEL"))
            .as("sales manager").isGreaterThanOrEqualTo(1);
        // the remembered choice never hands a flag-only reader the id of a DRAFT-round file
        assertThat(depositNoticeService.bundleSelection(id, reader).attachmentIds()).as("reader ids").isEmpty();
        assertThat(depositNoticeService.bundleSelection(id, salesActor).attachmentIds()).as("owner ids").containsExactly(draftFile);
    }

    // ── customer header follows the quotation's party ───────────────────────────────────────

    /** A second BUYER party (another customer) and the quotation re-addressed to it. */
    private long customer(String name, String taxId) {
        return jdbc.queryForObject("INSERT INTO customers.customer (name, tax_id) VALUES (:n, :t) RETURNING customer_id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("n", name).addValue("t", taxId), Long.class);
    }

    private static DepositNoticeDraftRequest noticeNames(Long addressee, Boolean addresseeReset) {
        return new DepositNoticeDraftRequest(null, null, null, "โครงการใหม่", null, null, null, null, null, null,
            addressee, addresseeReset);
    }

    private static RemainingInvoiceDraftRequest invoiceNames(Long addressee) {
        return new RemainingInvoiceDraftRequest(null, null, null, null, null, null, addressee, null);
    }

    private th.co.glr.hr.customer.CustomerDto addressToSecondBuyer(long ticketId, long quotationId) {
        return addressToBuyer(ticketId, quotationId, "คุณมะนาว", "0100000000444", "44 ถนนทดสอบ");
    }

    private th.co.glr.hr.customer.CustomerDto addressToBuyer(long ticketId, long quotationId, String name, String taxId,
                                                              String address) {
        var buyer = customers.create(name, taxId, address, "สนญ.", "02-000-0444");
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

        // เรียน (feedback #74), as the deposit notice: another customer from the list replaces the whole header, stored
        // too (receipt import reads it); picking the quotation's own customer is no override; a revision keeps it
        long addressee = customer("บริษัท เรียน ทดสอบ จำกัด", "0105500000075");
        assertThat(remainingInvoiceService.updateDraft(invoice.id(), invoiceNames(buyer.id()), salesActor)
            .addresseeCustomerId()).isNull();
        RemainingInvoiceDocumentDto named = remainingInvoiceService.updateDraft(invoice.id(), invoiceNames(addressee), salesActor);
        assertThat(named.customerName()).isEqualTo("บริษัท เรียน ทดสอบ จำกัด");
        assertThat(named.customerTaxId()).isEqualTo("0105500000075");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(invoice.id(), salesActor);
        assertThat(jdbc.queryForObject("SELECT customer_name FROM sales.remaining_invoice WHERE id = :id",
            java.util.Map.of("id", issued.id()), String.class)).isEqualTo("บริษัท เรียน ทดสอบ จำกัด");
        RemainingInvoiceDocumentDto revision = remainingInvoiceService.revise(issued.id(), salesActor);
        assertThat(revision.names()).isEqualTo(issued.names());
    }

    /** The read joins on the invoice's own customer_quotation_id; its free-text reference (which users edit) is only the
     * fallback for a row with no id. */
    @Test
    void remainingInvoiceReadKeysOnItsQuotationId_notOnTheEditableReference() {
        long ticketId = quotationOnlyTicket(salesActor);
        DealQuotationDto a = approvedZeroDepositQuotation(ticketId);
        confirmer.confirmOrderFromDirectQuotation(a.id(), salesActor);
        var buyerA = addressToSecondBuyer(ticketId, a.id());
        // a second new-engine quotation of the deal (a copy of A's row: one live quotation per deal is a service rule)
        long bId = jdbc.queryForObject("""
            INSERT INTO sales.quotation OVERRIDING SYSTEM VALUE SELECT (jsonb_populate_record(NULL::sales.quotation, to_jsonb(q)
                || jsonb_build_object('quotation_id', nextval(pg_get_serial_sequence('sales.quotation', 'quotation_id')),
                                      'number', 'QT-COPY-B', 'quotation_version', 99))).*
              FROM sales.quotation q WHERE q.quotation_id = :a RETURNING quotation_id""",
            java.util.Map.of("a", a.id()), Long.class);
        var buyerB = addressToBuyer(ticketId, bId, "คุณส้ม", "0100000000555", "55 ถนนทดสอบ");
        RemainingInvoiceDocumentDto invoice = remainingInvoiceService.createDraft(ticketId, null, salesActor);
        assertThat(invoice.customerName()).isEqualTo(buyerA.name());

        // no id: the reference number decides; with an id: the reference text naming A's number cannot move it off B
        jdbc.update("UPDATE sales.remaining_invoice SET customer_quotation_id = NULL, reference = :r WHERE id = :id",
            java.util.Map.of("r", "QT-COPY-B", "id", invoice.id()));
        assertThat(remainingInvoiceService.get(invoice.id(), salesActor).customerName()).isEqualTo(buyerB.name());
        jdbc.update("UPDATE sales.remaining_invoice SET customer_quotation_id = :q, reference = :r WHERE id = :id",
            java.util.Map.of("q", a.id(), "r", "QT-COPY-B", "id", invoice.id()));
        assertThat(remainingInvoiceService.get(invoice.id(), salesActor).customerName()).isEqualTo(buyerA.name());
    }

    /** Typed customer fields are ignored once the notice's reference resolves to a party (the STORED columns, which
     * receipt import matches on, hold the party's values). A DRAFT may be re-pointed to another party quotation of the
     * deal; any other changed reference is refused with 409, never silently ignored. */
    @Test
    void noticeUpdateKeepsTheQuotationCustomer_andRepointsOnlyADraftToAPartyQuotationOfTheDeal() {
        Deal deal = confirmedDepositDeal();
        var buyer = addressToSecondBuyer(deal.ticketId(), deal.quotation().id());
        DepositNoticeDto n = deal.notice();

        DepositNoticeDto updated = depositNoticeService.update(n.id(), new DepositNoticeDraftRequest("พิมพ์เอง", "111",
            "ที่อยู่พิมพ์เอง", "โครงการใหม่", null, null, null, null), salesActor);
        assertThat(updated.customerName()).isEqualTo(buyer.name());
        assertThat(updated.reference()).isEqualTo(deal.quotation().number());
        assertThat(updated.projectName()).isEqualTo("โครงการใหม่");
        assertThat(updated.customerFromQuotation()).isTrue();

        // เรียน (feedback #74, Ploy: "select the same customer for quotation (default) or create an entirely new
        // customer"): a picked customer replaces the whole header (served and stored: receipt import reads it); a new
        // version for the same quotation keeps it; an unknown id is a 400; the reset goes back to the quotation's customer
        long addressee = customer("บริษัท เรียน ทดสอบ จำกัด", "0105500000076");
        DepositNoticeDto addressed = depositNoticeService.update(n.id(), noticeNames(addressee, null), salesActor);
        assertThat(addressed.customerName()).isEqualTo("บริษัท เรียน ทดสอบ จำกัด");
        assertThat(addressed.customerTaxId()).isEqualTo("0105500000076");
        assertThat(addressed.reference()).isEqualTo(deal.quotation().number());
        assertThat(jdbc.queryForObject("SELECT customer_name FROM sales.deposit_notice WHERE deposit_notice_id = :id",
            java.util.Map.of("id", n.id()), String.class)).isEqualTo("บริษัท เรียน ทดสอบ จำกัด");
        DepositNoticeDto next = depositNoticeService.createDraft(deal.ticketId(),
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        assertThat(next.names()).isEqualTo(addressed.names());
        assertThat(next.customerName()).isEqualTo("บริษัท เรียน ทดสอบ จำกัด");
        assertThat(catchThrowableOfType(() -> depositNoticeService.update(n.id(), noticeNames(-1L, null), salesActor),
            ApiException.class).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        DepositNoticeDto back = depositNoticeService.update(n.id(), noticeNames(null, true), salesActor);
        assertThat(back.customerName()).isEqualTo(buyer.name());
        assertThat(back.addresseeCustomerId()).isNull();

        // not a party quotation of this deal (unknown number, or one without a party): 409, notice untouched
        cloneQuotation(deal.quotation().id(), "QT-NOPARTY", 91, false);
        String noParty = "QT-NOPARTY";
        for (String ref : List.of("QT-OTHER", noParty)) {
            assertThat(catchThrowableOfType(() -> depositNoticeService.update(n.id(),
                new DepositNoticeDraftRequest(null, null, null, null, ref, null, null, null), salesActor), ApiException.class)
                .getStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        // a party quotation of the same deal: the draft takes that party's customer
        long otherId = cloneQuotation(deal.quotation().id(), "QT-OTHER-PARTY", 92, true);
        var otherBuyer = addressToBuyer(deal.ticketId(), otherId, "คุณส้ม", "0100000000555", "55 ถนนทดสอบ");
        var repoint = new DepositNoticeDraftRequest(null, null, null, null, "QT-OTHER-PARTY", null, null, null);
        // refused: a status create would not bill, a 0% quotation, a quotation with no lines (each 409, notice untouched)
        jdbc.update("UPDATE sales.quotation SET doc_status = 'DRAFT' WHERE quotation_id = :id", java.util.Map.of("id", otherId));
        assertThat(catchThrowableOfType(() -> depositNoticeService.update(n.id(), repoint, salesActor), ApiException.class)
            .getStatus()).isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE sales.quotation SET doc_status = 'APPROVED', deposit_percent = 0 WHERE quotation_id = :id", java.util.Map.of("id", otherId));
        assertThat(catchThrowableOfType(() -> depositNoticeService.update(n.id(), repoint, salesActor), ApiException.class)
            .getMessage()).contains("ไม่มีมัดจำ");
        jdbc.update("UPDATE sales.quotation SET deposit_percent = 40 WHERE quotation_id = :id", java.util.Map.of("id", otherId));
        jdbc.update("CREATE TEMP TABLE qi_keep AS SELECT * FROM sales.quotation_item WHERE quotation_id = :id", java.util.Map.of("id", otherId));
        jdbc.update("DELETE FROM sales.quotation_item WHERE quotation_id = :id", java.util.Map.of("id", otherId));
        assertThat(catchThrowableOfType(() -> depositNoticeService.update(n.id(), repoint, salesActor), ApiException.class)
            .getStatus()).isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("INSERT INTO sales.quotation_item OVERRIDING SYSTEM VALUE SELECT * FROM qi_keep", java.util.Map.of());
        jdbc.update("DROP TABLE qi_keep", java.util.Map.of());
        assertThat(depositNoticeService.getById(n.id(), salesActor).reference()).isEqualTo(deal.quotation().number());

        DepositNoticeDto moved = depositNoticeService.update(n.id(), repoint, salesActor);
        assertThat(moved.depositPercent()).isEqualByComparingTo("0.40");
        assertThat(moved.reference()).isEqualTo("QT-OTHER-PARTY");
        assertThat(moved.customerName()).isEqualTo(otherBuyer.name());
        assertThat(moved.customerTaxId()).isEqualTo(otherBuyer.taxId());
        var newLines = depositNoticeService.itemsFromDealQuotation(otherId);
        assertThat(moved.subtotal()).isNotEqualByComparingTo(updated.subtotal());
        assertThat(moved.items()).extracting(i -> i.amount()).containsExactlyElementsOf(newLines.stream().map(i -> i.amount()).toList());
        assertThat(moved.subtotal()).isEqualByComparingTo(newLines.stream().map(i -> i.amount())
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        assertThat(jdbc.queryForObject("SELECT customer_name FROM sales.deposit_notice WHERE deposit_notice_id = :id",
            java.util.Map.of("id", n.id()), String.class)).isEqualTo(otherBuyer.name());

        // ISSUED: a changed reference is refused
        jdbc.update("UPDATE sales.deposit_notice SET status = 'ISSUED' WHERE deposit_notice_id = :id", java.util.Map.of("id", n.id()));
        assertThat(catchThrowableOfType(() -> depositNoticeService.update(n.id(),
            new DepositNoticeDraftRequest(null, null, null, null, deal.quotation().number(), null, null, null), salesActor),
            ApiException.class).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE sales.deposit_notice SET status = 'DRAFT' WHERE deposit_notice_id = :id", java.util.Map.of("id", n.id()));

        // the party row gone: the stored values show again and the editor is told they are editable
        jdbc.update("UPDATE sales.quotation SET party_id = NULL WHERE quotation_id = :id", java.util.Map.of("id", otherId));
        assertThat(depositNoticeService.getById(n.id(), salesActor).customerFromQuotation()).isFalse();
    }

    /** Once the referenced quotation is no longer billable the notice cannot be issued (409). (quotation.number is
     * unique, so one number never has several rows; the customer lookups still prefer a billable row.) */
    @Test
    void issueIsRefusedOnceTheReferencedQuotationIsCancelled() {
        Deal deal = confirmedDepositDeal();
        jdbc.update("UPDATE sales.quotation SET doc_status = 'CANCELLED' WHERE quotation_id = :id",
            java.util.Map.of("id", deal.quotation().id()));
        assertThat(catchThrowableOfType(() -> depositNoticeService.issue(deal.notice().id(), salesActor), ApiException.class)
            .getMessage()).contains("ใบเสนอราคาอ้างอิง");
    }

    /** A second quotation of the same deal (a copy of {@code id} under a new number/version, party cleared); a deal
     * holds only one live direct quotation, so the service cannot create it. */
    private long cloneQuotation(long id, String number, int version, boolean unused) {
        jdbc.update("CREATE TEMP TABLE q_clone AS SELECT * FROM sales.quotation WHERE quotation_id = :id", java.util.Map.of("id", id));
        long newId = jdbc.queryForObject("SELECT max(quotation_id) + 1000 FROM sales.quotation", java.util.Map.of(), Long.class);
        jdbc.update("UPDATE q_clone SET quotation_id = :n, number = :num, quotation_version = :v, party_id = NULL",
            java.util.Map.of("n", newId, "num", number, "v", version));
        jdbc.update("INSERT INTO sales.quotation OVERRIDING SYSTEM VALUE SELECT * FROM q_clone", java.util.Map.of());
        jdbc.update("DROP TABLE q_clone", java.util.Map.of());
        // its lines too, with doubled amounts so they differ from the original's
        jdbc.update("CREATE TEMP TABLE qi_clone AS SELECT * FROM sales.quotation_item WHERE quotation_id = :id", java.util.Map.of("id", id));
        jdbc.update("UPDATE qi_clone SET quotation_item_id = quotation_item_id + (SELECT max(quotation_item_id) FROM sales.quotation_item),"
            + " quotation_id = :n, amount = amount * 2", java.util.Map.of("n", newId));
        jdbc.update("INSERT INTO sales.quotation_item OVERRIDING SYSTEM VALUE SELECT * FROM qi_clone", java.util.Map.of());
        jdbc.update("DROP TABLE qi_clone", java.util.Map.of());
        return newId;
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
        attachFile(ticketId, name, mime, bytes, AttachType.PO);
    }

    private long attachFile(long ticketId, String name, String mime, byte[] bytes, String type) {
        FileStorageService.StoredFile stored = fileStorage.store("tickets", ticketId,
            new MockMultipartFile("file", name, mime, bytes), Set.of());
        return attachments.save(ticketId, null, name, stored.filePath(), stored.mimeType(), stored.fileSize(),
            type, salesActor.id()).id();
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
