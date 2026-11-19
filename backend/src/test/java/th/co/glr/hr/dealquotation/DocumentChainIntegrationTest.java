package th.co.glr.hr.dealquotation;

import th.co.glr.hr.documentchain.DocumentChainDto;
import th.co.glr.hr.documentchain.DocumentChainRepository;
import th.co.glr.hr.documentchain.DocumentChainService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceDocumentDto;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.deposit.RemainingInvoiceService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * The document chain summary ({@code GET /api/tickets/{id}/document-chain}) and the quotation list's chain mark,
 * through the REAL services and repositories on real Postgres — no Mockito. Three things are pinned:
 *
 * <ol>
 *   <li>the summary walks the chain as the real writes move it (confirm, draft, issue, deposit received, invoice);</li>
 *   <li>SCOPE, wrong-way-round: a viewer who cannot read the deal's deposit notices today learns nothing through the
 *       summary or the list mark — another rep, import, hr, a can_create_quotation grantee who CAN open the
 *       quotation, account outside its scope;</li>
 *   <li>the "may act" flags agree with the real write endpoints for every persona that can read the summary.</li>
 * </ol>
 */
class DocumentChainIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private DealQuotationService quotationService;
    private DealQuotationService confirmer;
    private DepositNoticeService depositNoticeService;
    private RemainingInvoiceService remainingInvoiceService;
    private DocumentChainService chain;

    private UserPrincipal owner;
    private UserPrincipal otherSales;
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal importUser;
    private UserPrincipal account;
    private UserPrincipal hr;
    private UserPrincipal grantedQc;

    private CustomerDto customer;
    private long projectId;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        PricingRequestService pricingRequestService = new PricingRequestService(pricingRequests, tickets,
            notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-document-chain-test-uploads"), factoryQuoteCarryForward());
        DealQuotationRepository dealQuotations = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        ticketService = new TicketService(tickets, notifications, objectMapper, customers, new QuotationRenderer(),
            pricingRequestService, new EmployeeAuthRepository(jdbc), new ContactRepository(jdbc),
            new ProjectRepository(jdbc), dealQuotations);
        Mailer noMail = new Mailer() {
            @Override public void send(String to, String subject, String body) {}
            @Override public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                           List<Mailer.InlineImage> inlineImages) {}
            @Override public void sendWithAttachment(String to, String subject, String body, String filename,
                                                     byte[] bytes) {}
            @Override public void sendWithAttachments(String to, String subject, String body,
                                                      List<Mailer.Attachment> attachments) {}
        };
        CustomerQuotationRepository legacyQuotations = new CustomerQuotationRepository(jdbc);
        quotationService = new DealQuotationService(dealQuotations, tickets, customers, new ContactRepository(jdbc),
            notifications, new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, new PricingDecisionRepository(jdbc),
            legacyQuotations);
        quotationService.wireTicketService(ticketService);
        confirmer = transactional(quotationService);
        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticePdfRenderer(), customers, legacyQuotations, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));
        remainingInvoiceService = new RemainingInvoiceService(new RemainingInvoiceRepository(jdbc),
            depositNoticeService, tickets, new RemainingInvoicePdfRenderer(), notifications);
        chain = new DocumentChainService(new DocumentChainRepository(jdbc), tickets, depositNoticeService,
            new DepositNoticeRepository(jdbc), new RemainingInvoiceRepository(jdbc), quotationService);

        owner = actor(employee(employees, "ขาย เจ้าของ", "chain-owner@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        otherSales = actor(employee(employees, "ขาย อื่น", "chain-other@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        salesManager = actor(employee(employees, "ผจก.ขาย", "chain-sm@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");
        ceo = actor(employee(employees, "ผู้บริหาร", "chain-ceo@glr.co.th", "MD", "ผู้บริหาร", "กรรมการผู้จัดการ"), "ceo");
        importUser = actor(employee(employees, "นำเข้า", "chain-import@glr.co.th", "PCIM", "ฝ่ายนำเข้า", null), "import");
        account = actor(employee(employees, "บัญชี", "chain-account@glr.co.th", "ACCT", "ฝ่ายบัญชี", null), "account");
        hr = actor(employee(employees, "บุคคล", "chain-hr@glr.co.th", "HR", "ฝ่ายบุคคล", null), "hr");
        long qcId = employee(employees, "QC", "chain-qc@glr.co.th", "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qcId));
        grantedQc = actor(qcId, "qc");

        customer = customers.create("บริษัท ห่วงโซ่เอกสาร จำกัด", "0100000000777", "777 ถนนทดสอบ", "สนญ.", "02-000-0777");
        projectId = new ProjectRepository(jdbc).create(customer.id(), "โครงการ ห่วงโซ่เอกสาร").id();
    }

    // ── 1. the summary follows the real writes ─────────────────────────────────────────────────────

    @Test
    void summaryWalksTheChainAsTheRealWritesMoveIt() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);

        DocumentChainDto before = chain.forTicket(deal, owner);
        assertThat(before.ticketId()).isEqualTo(deal);
        assertThat(before.dealStatus()).isEqualTo("draft");
        assertThat(before.lifecycle()).isEqualTo("ACTIVE");
        assertThat(before.order().confirmed()).isFalse();
        assertThat(before.order().confirmable()).isTrue();
        assertThat(before.order().confirmedFromQuotationId()).isNull();
        assertThat(before.deposit().state()).isEqualTo("NOT_YET");
        assertThat(before.remaining().state()).isEqualTo("WAITING");
        assertThat(before.remaining().blockedReason()).isEqualTo("ORDER_NOT_CONFIRMED");
        assertThat(before.remaining().canCreate()).isFalse();

        confirmer.confirmOrderFromDirectQuotation(q.id(), owner);
        DocumentChainDto confirmed = chain.forTicket(deal, owner);
        assertThat(confirmed.order().confirmed()).isTrue();
        assertThat(confirmed.order().confirmable()).isFalse();
        assertThat(confirmed.order().confirmedFromQuotationId()).isEqualTo(q.id());
        assertThat(confirmed.order().confirmedFromQuotationNumber()).isEqualTo(q.number());
        assertThat(confirmed.order().confirmedAt()).isNotNull();
        assertThat(confirmed.deposit().required()).isTrue();
        assertThat(confirmed.deposit().policy()).isEqualTo("REQUIRED");
        assertThat(confirmed.deposit().percent()).isEqualTo(50);
        assertThat(confirmed.deposit().state()).isEqualTo("TO_CREATE");
        assertThat(confirmed.deposit().blockedReason()).isNull();
        assertThat(confirmed.deposit().noticeId()).isNull();
        assertThat(confirmed.remaining().blockedReason()).isEqualTo("DEPOSIT_NOT_RECEIVED");

        DepositNoticeDto draft = depositNoticeService.createDraft(deal, emptyNoticeRequest(), owner);
        DocumentChainDto drafted = chain.forTicket(deal, owner);
        assertThat(drafted.deposit().state()).isEqualTo("DRAFT");
        assertThat(drafted.deposit().draftExists()).isTrue();
        assertThat(drafted.deposit().noticeId()).isEqualTo(draft.id());
        assertThat(drafted.deposit().docNumber()).isNull();

        DepositNoticeDto issued = depositNoticeService.issue(draft.id(), owner);
        DocumentChainDto issuedChain = chain.forTicket(deal, owner);
        assertThat(issuedChain.deposit().state()).isEqualTo("ISSUED");
        assertThat(issuedChain.deposit().draftExists()).isFalse();
        assertThat(issuedChain.deposit().noticeId()).isEqualTo(issued.id());
        assertThat(issuedChain.deposit().issuedNoticeId()).isEqualTo(issued.id());
        assertThat(issuedChain.deposit().docNumber()).isEqualTo(issued.docNumber()).isNotBlank();
        assertThat(issuedChain.deposit().issueDate()).isEqualTo(issued.issueDate()).isNotNull();
        assertThat(issuedChain.deposit().received()).isFalse();

        ticketService.confirmDepositPaid(deal, account);
        DocumentChainDto paid = chain.forTicket(deal, owner);
        assertThat(paid.deposit().state()).isEqualTo("PAID");
        assertThat(paid.deposit().received()).isTrue();
        assertThat(paid.remaining().state()).isEqualTo("TO_CREATE");
        assertThat(paid.remaining().blockedReason()).isEqualTo("GOODS_NOT_READY");
        assertThat(paid.remaining().canCreate()).isFalse();

        // Fixture-only: the goods arrive (the import chain's own writes are not what this test is about).
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'GOODS_RECEIVED' WHERE ticket_id = :id",
            Map.of("id", deal));
        DocumentChainDto ready = chain.forTicket(deal, owner);
        assertThat(ready.remaining().state()).isEqualTo("TO_CREATE");
        assertThat(ready.remaining().blockedReason()).isNull();
        assertThat(ready.remaining().canCreate()).isTrue();

        RemainingInvoiceDocumentDto invoiceDraft = remainingInvoiceService.createDraft(deal, null, owner);
        DocumentChainDto invoiceDrafted = chain.forTicket(deal, owner);
        assertThat(invoiceDrafted.remaining().state()).isEqualTo("DRAFT");
        assertThat(invoiceDrafted.remaining().draftExists()).isTrue();
        assertThat(invoiceDrafted.remaining().invoiceId()).isEqualTo(invoiceDraft.id());

        RemainingInvoiceDocumentDto invoice = remainingInvoiceService.issue(invoiceDraft.id(), owner);
        DocumentChainDto invoiced = chain.forTicket(deal, owner);
        assertThat(invoiced.remaining().state()).isEqualTo("ISSUED");
        assertThat(invoiced.remaining().invoiceId()).isEqualTo(invoice.id());
        assertThat(invoiced.remaining().docNumber()).isEqualTo(invoice.docNumber()).isNotBlank();
        assertThat(invoiced.remaining().docDate()).isEqualTo(invoice.docDate());
    }

    /** {@code order.undoable} is the undo's own cut-off (DealQuotationService#undoRefusal), read off the real writes. */
    @Test
    void orderUndoableFollowsTheUndoCutOff() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        assertThat(chain.forTicket(deal, owner).order().undoable()).as("not confirmed yet").isFalse();
        assertThat(chain.forTicket(deal, owner).order().confirmationEventId()).as("not confirmed yet").isNull();

        confirmer.confirmOrderFromDirectQuotation(q.id(), owner);
        assertThat(chain.forTicket(deal, owner).order().undoable()).as("right after the confirmation").isTrue();

        DepositNoticeDto draft = depositNoticeService.createDraft(deal, emptyNoticeRequest(), owner);
        assertThat(chain.forTicket(deal, owner).order().undoable()).as("a DRAFT notice does not stop it").isTrue();

        depositNoticeService.issue(draft.id(), owner);
        assertThat(chain.forTicket(deal, owner).order().undoable()).as("an issued deposit notice stops it").isFalse();

        long other = deal(owner);
        DealQuotationDto q2 = approved(other, 50);
        confirmer.confirmOrderFromDirectQuotation(q2.id(), owner);
        assertThat(chain.forTicket(other, owner).order().confirmationEventId()).as("names the confirmation event")
            .isEqualTo(tickets.findPromotionEvent(other).orElseThrow().eventId());
        confirmer.undoOrderConfirmation(q2.id(), null, null, owner);
        assertThat(chain.forTicket(other, owner).order().confirmationEventId()).as("none once undone").isNull();
        assertThat(chain.forTicket(other, owner).order().undoable()).as("after the undo it is not confirmed").isFalse();
    }

    @Test
    void aZeroDepositQuotationConfirmsIntoANoDepositChain() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 0);
        confirmer.confirmOrderFromDirectQuotation(q.id(), owner);

        DocumentChainDto summary = chain.forTicket(deal, owner);
        assertThat(summary.deposit().required()).isFalse();
        assertThat(summary.deposit().state()).isEqualTo("NOT_REQUIRED");
        assertThat(summary.deposit().percent()).isEqualTo(0);
        assertThat(summary.remaining().state()).isEqualTo("TO_CREATE");
    }

    /** The prototype's deal-page inconsistency (main button "บันทึกผลประมูล" after a confirm from the quotation page)
     * checked against the REAL confirm: the confirmed quotation's party is AWARDED, so no party is still bidding. */
    @Test
    void confirmingFromTheQuotationLeavesNoPartyBidding() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        assertThat(q.partyId()).as("the deal's default party is on the quotation").isNotNull();
        assertThat(partyStatuses(deal)).contains("QUOTED");

        confirmer.confirmOrderFromDirectQuotation(q.id(), owner);

        assertThat(partyStatuses(deal)).doesNotContain("QUOTED", "NEGOTIATING");
        assertThat(jdbc.queryForObject("SELECT status FROM sales.deal_party WHERE party_id = :id",
            Map.of("id", q.partyId()), String.class)).isEqualTo("AWARDED");
    }

    // ── 2. scope, wrong-way-round ─────────────────────────────────────────────────────────────────

    @Test
    void aViewerWhoCannotReadTheDealsDepositNoticesCannotReadItsChain() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        confirmer.confirmOrderFromDirectQuotation(q.id(), owner);
        depositNoticeService.issue(depositNoticeService.createDraft(deal, emptyNoticeRequest(), owner).id(), owner);

        for (UserPrincipal outsider : List.of(otherSales, importUser, hr, grantedQc)) {
            assertForbidden(() -> depositNoticeService.listByTicket(deal, outsider)); // today's rule ...
            assertForbidden(() -> chain.forTicket(deal, outsider));                    // ... is the summary's
        }
        // The grantee CAN open the quotation itself — the chain is refused on the documents, not on the quotation.
        assertThat(quotationService.get(q.id(), grantedQc).id()).isEqualTo(q.id());
    }

    @Test
    void accountOutsideItsScopeIsRefused_insideItSeesTheDocumentsButNoConfirmationTime() {
        long early = deal(owner);
        approved(early, 50); // still a draft deal at the owner quote stage: outside account's list scope
        assertForbidden(() -> depositNoticeService.listByTicket(early, account));
        assertForbidden(() -> chain.forTicket(early, account));

        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        confirmer.confirmOrderFromDirectQuotation(q.id(), owner); // ORDER_RECEIVED: inside account's scope
        DepositNoticeDto issued = depositNoticeService.issue(
            depositNoticeService.createDraft(deal, emptyNoticeRequest(), owner).id(), owner);

        DocumentChainDto seen = chain.forTicket(deal, account);
        assertThat(seen.deposit().docNumber()).isEqualTo(issued.docNumber());
        assertThat(seen.order().confirmed()).isTrue();
        assertThat(seen.order().confirmedFromQuotationId()).isEqualTo(q.id());
        assertThat(seen.order().confirmedAt()).as("account reads no ticket events").isNull();
        assertThat(seen.viewer().mayConfirmOrder()).isFalse();
        assertThat(seen.viewer().mayWriteDepositNotice()).isFalse();
        assertThat(seen.viewer().mayWriteRemainingInvoice()).isFalse();
    }

    @Test
    void anUnknownDealIsNotFound() {
        Throwable thrown = catchThrowable(() -> chain.forTicket(987_654L, owner));
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theListMarkIsComputedOnlyForDealsTheViewerMayReadDocumentsOf() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        confirmer.confirmOrderFromDirectQuotation(q.id(), owner);
        depositNoticeService.issue(depositNoticeService.createDraft(deal, emptyNoticeRequest(), owner).id(), owner);
        long openDeal = deal(owner);
        DealQuotationDto open = approved(openDeal, 30);

        List<DealQuotationDto> ownerRows = quotationService.search(null, false, owner);
        assertThat(ownerRows).extracting(DealQuotationDto::id).contains(q.id(), open.id());
        Map<Long, String> ownerStages = chain.listStages(ownerRows, owner);
        assertThat(ownerStages).containsEntry(q.id(), "DEPOSIT_NOTICE_ISSUED").containsEntry(open.id(), "AWAITING_ORDER");
        assertThat(chain.listStages(ownerRows, salesManager)).containsEntry(q.id(), "DEPOSIT_NOTICE_ISSUED");
        assertThat(chain.listStages(ownerRows, ceo)).containsEntry(q.id(), "DEPOSIT_NOTICE_ISSUED");

        // The same rows handed to a viewer who may not read these deals' documents: nothing, enforced in the WHERE.
        assertThat(chain.listStages(ownerRows, otherSales)).isEmpty();
        assertThat(chain.listStages(ownerRows, importUser)).isEmpty();
        assertThat(chain.listStages(ownerRows, hr)).isEmpty();
        assertThat(chain.listStages(ownerRows, grantedQc)).isEmpty();
        // account: the confirmed deal is inside its scope, the open (pre-order) deal is not.
        assertThat(chain.listStages(ownerRows, account))
            .containsEntry(q.id(), "DEPOSIT_NOTICE_ISSUED").doesNotContainKey(open.id());
    }

    // ── 3. the flags are the write endpoints' own decisions ────────────────────────────────────────

    @Test
    void theDepositFlagsAgreeWithTheRealWrites() {
        for (UserPrincipal viewer : List.of(owner, salesManager, ceo)) {
            long deal = deal(owner);
            confirmer.confirmOrderFromDirectQuotation(approved(deal, 50).id(), owner);
            DocumentChainDto summary = chain.forTicket(deal, viewer);
            boolean allowed = notForbidden(() -> depositNoticeService.createDraft(deal, emptyNoticeRequest(), viewer));
            assertThat(summary.viewer().mayWriteDepositNotice()).as("deposit flag for %s", viewer.role())
                .isEqualTo(allowed);
            boolean invoiceAllowed = notForbidden(() -> remainingInvoiceService.createDraft(deal, null, viewer));
            assertThat(summary.viewer().mayWriteRemainingInvoice()).as("invoice flag for %s", viewer.role())
                .isEqualTo(invoiceAllowed);
        }
        // and it is not vacuous: the owner may, the others may not
        long deal = deal(owner);
        assertThat(chain.forTicket(deal, owner).viewer().mayWriteDepositNotice()).isTrue();
        assertThat(chain.forTicket(deal, salesManager).viewer().mayWriteDepositNotice()).isFalse();
    }

    @Test
    void theConfirmOrderFlagAgreesWithTheRealConfirm() {
        for (UserPrincipal viewer : List.of(owner, salesManager, ceo)) {
            long deal = deal(owner);
            DealQuotationDto q = approved(deal, 50);
            boolean flag = chain.forTicket(deal, viewer).viewer().mayConfirmOrder();
            boolean allowed = notForbidden(() -> confirmer.confirmOrderFromDirectQuotation(q.id(), viewer));
            assertThat(flag).as("confirm flag for %s", viewer.role()).isEqualTo(allowed);
        }
        long deal = deal(owner);
        approved(deal, 50);
        assertThat(chain.forTicket(deal, owner).viewer().mayConfirmOrder()).isTrue();
        assertThat(chain.forTicket(deal, ceo).viewer().mayConfirmOrder()).isFalse();
    }

    // ── fix round 1: the surviving mutations B1 / B2 / B6 ────────────────────────────────────────────

    /** B1: the KEYER (a rep who typed the deal in for its owner) reads its documents today, so its list rows carry the
     * mark; dropping keyed_by from the list SQL must fail here. */
    @Test
    void theKeyerSeesTheListMarkOfADealOwnedBySomeoneElse() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        jdbc.update("UPDATE sales.ticket SET created_by = :other, keyed_by = :keyer WHERE ticket_id = :id",
            Map.of("other", otherSales.id(), "keyer", owner.id(), "id", deal));
        assertThat(depositNoticeService.listByTicket(deal, owner)).isEmpty(); // today's read gate admits the keyer
        List<DealQuotationDto> keyerRows = quotationService.search(null, false, owner);
        assertThat(keyerRows).extracting(DealQuotationDto::id).contains(q.id());
        assertThat(chain.listStages(keyerRows, owner)).containsEntry(q.id(), "AWAITING_ORDER");
    }

    /** B2, wrong-way-round: the deal's ASSIGNEE (another sales rep) cannot read its deposit notices today, so the list
     * mark must not reach them either; widening the list SQL to assigned_to must fail here. */
    @Test
    void anAssigneeWhoIsNeitherOwnerNorKeyerGetsNoListMark() {
        long deal = deal(owner);
        DealQuotationDto q = approved(deal, 50);
        jdbc.update("UPDATE sales.ticket SET assigned_to = :assignee WHERE ticket_id = :id",
            Map.of("assignee", otherSales.id(), "id", deal));
        assertForbidden(() -> depositNoticeService.listByTicket(deal, otherSales)); // today's rule
        List<DealQuotationDto> ownerRows = quotationService.search(null, false, owner);
        assertThat(chain.listStages(ownerRows, otherSales)).doesNotContainKey(q.id()).isEmpty();
    }

    /** B6: a sales_manager who OWNS the deal still may not write its documents (every write needs the sales ROLE), so
     * the flag is false — and the real write agrees. Dropping the role half of the flag must fail here. */
    @Test
    void aSalesManagerWhoOwnsTheDealMayNotWriteItsDocuments() {
        long deal = deal(owner);
        confirmer.confirmOrderFromDirectQuotation(approved(deal, 50).id(), owner);
        jdbc.update("UPDATE sales.ticket SET created_by = :sm WHERE ticket_id = :id",
            Map.of("sm", salesManager.id(), "id", deal));
        DocumentChainDto seen = chain.forTicket(deal, salesManager);
        assertThat(seen.viewer().mayWriteDepositNotice()).isFalse();
        assertThat(seen.viewer().mayWriteRemainingInvoice()).isFalse();
        assertForbidden(() -> depositNoticeService.createDraft(deal, emptyNoticeRequest(), salesManager));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private long deal(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest("ดีล ห่วงโซ่เอกสาร", "NORMAL", customer.name(),
            customer.id(), projectId, null, null, null, List.of(), LocalDate.now().plusDays(7)), creator).summary().id();
    }

    /** An APPROVED direct quotation at {@code depositPercent} (0 = ไม่รับมัดจำ, which needs a full-payment term). */
    private DealQuotationDto approved(long ticketId, int depositPercent) {
        ItemInput tile = new ItemInput(
            null, null, null, "SCG", "Chain Tile", "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false);
        UpsertDealQuotationRequest base = new UpsertDealQuotationRequest(null, null, null, null, depositPercent,
            "CASH_ON_DELIVERY", 0, 30, null, "NET", "TH", "THB", List.of(tile)).withRecipientType("OWNER");
        UpsertDealQuotationRequest request = depositPercent == 0
            ? new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(), base.offerDate(), 0,
                base.remainderMode(), base.creditDays(), base.validityDays(), base.validityMode(), base.validityUntil(),
                base.customerNotes(), base.priceMode(), base.documentLanguage(), base.currency(), null, null, null,
                null, "BEFORE_DELIVERY", null, null, null, null, base.recipientType(), base.items())
            : base;
        DealQuotationDto created = quotationService.create(ticketId, request, owner);
        quotationService.submit(created.id(), owner);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManager);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    private static DepositNoticeDraftRequest emptyNoticeRequest() {
        return new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null);
    }

    private List<String> partyStatuses(long ticketId) {
        return jdbc.queryForList("SELECT status FROM sales.deal_party WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("expected a 403").isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** true when the call ran or failed for any reason but authorisation; false on a 403. */
    private static boolean notForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        return !(thrown instanceof ApiException e && e.getStatus() == HttpStatus.FORBIDDEN);
    }

    private static long employee(EmployeeRepository employees, String nameTh, String email, String divisionSourceCode,
                                 String divisionNameTh, String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
