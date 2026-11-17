package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.attachment.AttachmentController;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Slice D: characterises what the REAL backend enforces for the three document-row families
 * DealDocumentRegister.jsx rolls up. Every case below is written the WRONG WAY ROUND: it asks whether a caller
 * can reach data it should not, never merely that it can reach its own. */
class DealDocumentRegisterAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private PricingRequestService pricingRequestService;
    private CustomerQuotationService quotationService;
    private DepositNoticeService depositNoticeService;
    private AttachmentController attachmentController;
    private MockMvc mvc;

    private long ticketId;
    private long pricingRequestId;

    private UserPrincipal salesOwnerActor;
    private UserPrincipal salesOtherActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal importNonParticipantActor;
    private UserPrincipal importAssigneeActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal hrActor;
    private UserPrincipal employeeActor;

    @BeforeEach
    void wireRealCollaboratorsAndSeedADeal() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-deal-document-register-authz-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests,
            new th.co.glr.hr.pricingdecision.PricingDecisionRepository(jdbc), tickets, ticketService, customers,
            new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticePdfRenderer(), customers, quotationRepository);

        attachmentController = new AttachmentController(new AttachmentRepository(jdbc), new SessionContext(), tickets,
            new AuditService(new AuditLogRepository(jdbc), objectMapper), fileStorage);
        // AttachmentController#list is package-private — only reachable from within th.co.glr.hr.attachment.
        mvc = MockMvcBuilders.standaloneSetup(attachmentController)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        long salesOwnerId = createEmployee(employees, "ตัวแทนขาย เจ้าของ", "sales-owner-docreg@glr.co.th", "SALES", "แผนกขาย");
        long salesOtherId = createEmployee(employees, "ตัวแทนขาย อื่น", "sales-other-docreg@glr.co.th", "SALES", "แผนกขาย");
        long salesManagerId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-docreg@glr.co.th", "SALES", "แผนกขาย");
        long importNonParticipantId = createEmployee(employees, "ฝ่ายนำเข้า (ยังไม่รับงาน)", "import-nonparticipant-docreg@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long importAssigneeId = createEmployee(employees, "ฝ่ายนำเข้า (ผู้รับมอบหมาย)", "import-assignee-docreg@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoId = createEmployee(employees, "ผู้บริหาร", "ceo-docreg@glr.co.th", "MD", "ผู้บริหาร");
        long accountId = createEmployee(employees, "ฝ่ายบัญชี", "account-docreg@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        long hrId = createEmployee(employees, "ฝ่ายบุคคล", "hr-docreg@glr.co.th", "HR", "ฝ่ายบุคคล");
        long employeeId = createEmployee(employees, "พนักงานทั่วไป", "employee-docreg@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ");

        salesOwnerActor = actor(salesOwnerId, "sales");
        salesOtherActor = actor(salesOtherId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        importNonParticipantActor = actor(importNonParticipantId, "import");
        importAssigneeActor = actor(importAssigneeId, "import");
        ceoActor = actor(ceoId, "ceo");
        accountActor = actor(accountId, "account");
        hrActor = actor(hrId, "hr");
        employeeActor = actor(employeeId, "employee");

        CustomerDto customer = customers.create(
            "บริษัท Doc Register จำกัด", "0100000000199", "999 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0199");
        ProjectDto project = projects.create(customer.id(), "โครงการ Doc Register");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Doc Register", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, List.of()),
            salesOwnerActor);
        ticketId = created.summary().id();

        // The real pickup flow sets assigned_to — set directly here, mirroring TicketIaAuthzMatrixIntegrationTest's
        // own identical fixture comment, so this deal has a real PARTICIPANT import actor to prove the
        // participant-vs-role divergence against.
        jdbc.update("UPDATE sales.ticket SET assigned_to = :assigneeId WHERE ticket_id = :ticketId",
            Map.of("assigneeId", importAssigneeId, "ticketId", ticketId));

        // Opus review finding: the two "import cannot see legacy quotations" cases below used to assert
        // `ticket.quotations).isEmpty` on a deal that had NO legacy quotation for ANYONE.
        tickets.createQuotation(ticketId, "QT-2026-DOCREG-0001", salesOwnerId, new BigDecimal("1000.00"));

        long catalogPriceId = insertCatalogProduct("Factory Doc Register", "TH", "DOCREG-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        var item = new PricingRequestRequests.PricingRequestItemRequest(null, catalogPriceId, null,
            "Brand", "Model", "Brand Model", "White", "Matte", "60x60", "Factory Doc Register",
            null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        var createPrRequest = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "doc register authz test", UUID.randomUUID().toString(), List.of(item));
        pricingRequestId = pricingRequestService.createDraft(ticketId, createPrRequest, salesOwnerActor)
            .summary().id();
        // SUBMITTED (not DRAFT — a DRAFT is owner/ceo/sales_manager-only, which would make import's genuine positive
        // grant on the new chain untestable).
        pricingRequestService.submit(pricingRequestId, salesOwnerActor);
    }

    // account: can list deposit notices and attachments, cannot read pricing requests/quotations

    @Test
    void account_canListDepositNoticesAndAttachmentsForTheTicket_onlyOnceTheDealIsInItsListScope() throws Exception {
        // H1 lockdown: row-scoped. A fresh deal is at the lead stage -- outside account's list scope.
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, accountActor));
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'PROCUREMENT' WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", ticketId));
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, accountActor)).doesNotThrowAnyException();
        assertAttachmentsOk(accountActor);
    }

    @Test
    void account_cannotReadPricingRequestsOrCustomerQuotations() {
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, accountActor));
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, accountActor));
    }

    // import: the legacy embedded ticket.quotations field is nulled unconditionally (role-only, NOT
    // participant-scoped) — the "import cannot list customer quotations" case, at the layer this register actually
    // reads it from.

    @Test
    void import_cannotSeeTheLegacyEmbeddedQuotationsOnTheTicketDto_evenAsThisDealsAssignee() {
        // Opus review finding: without a real seeded quotation, "import sees an empty list" was true only because NO
        // ONE had a quotation on this deal — a green test that could not go red if `projectForRole` stopped stripping
        // anything.
        TicketDto asOwner = ticketService.get(ticketId, salesOwnerActor);
        assertThat(asOwner.quotations()).extracting(QuotationDto::number).containsExactly("QT-2026-DOCREG-0001");
        assertThat(asOwner.quotation()).isNotNull();

        // import no longer reaches GET /tickets/{id} AT ALL (it has its own per-deal endpoint, GET
        // /api/import/deals/{id}, which never carries a quotation) — so the strongest form of "cannot see the embedded
        // quotations" is a refusal, for the assignee and the non-participant alike.
        assertForbidden(() -> ticketService.get(ticketId, importAssigneeActor));
        assertForbidden(() -> ticketService.get(ticketId, importNonParticipantActor));
    }

    // a non-participant sales rep cannot reach another rep's documents (any of the three families)

    @Test
    void nonParticipantSalesRep_cannotReachAnotherRepsDocuments() throws Exception {
        assertForbidden(() -> ticketService.get(ticketId, salesOtherActor));
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, salesOtherActor));
        // O1 (GLA-99 step 2 review-round-1): the stateless getRemainingInvoiceXlsx this used to also assert here is
        // gone — /options shares the SAME requireTicketViewer gate (finding 4, still true) and is the one still-live
        // stateless entry point, so it alone still proves this.
        assertForbidden(() -> depositNoticeService.getRemainingInvoiceOptions(ticketId, salesOtherActor));
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, salesOtherActor));
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, salesOtherActor));
        assertAttachmentsForbidden(salesOtherActor);
    }

    // hr / employee cannot reach any of it

    @Test
    void hrAndEmployee_cannotReachAnyOfIt() throws Exception {
        for (UserPrincipal actor : List.of(hrActor, employeeActor)) {
            assertForbidden(() -> ticketService.get(ticketId, actor));
            assertForbidden(() -> depositNoticeService.listByTicket(ticketId, actor));
            assertForbidden(() -> depositNoticeService.getRemainingInvoiceOptions(ticketId, actor));
            assertForbidden(() -> pricingRequestService.get(pricingRequestId, actor));
            assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, actor));
            assertAttachmentsForbidden(actor);
        }
    }

    // REQUIRED CASE (coordinator addendum): an import rep who IS this deal's assignee is a real PARTICIPANT — it
    // reaches attachments, but stays refused BOTH quotation families and BOTH deposit-notice-shaped reads.

    @Test
    void importAssignee_reachesAttachmentsAsAParticipant() throws Exception {
        assertAttachmentsOk(importAssigneeActor);
    }

    @Test
    void importAssignee_seesZeroQuotationRows_bothTheLegacyFieldAndTheFrontendChoiceOnTheNewChain() {
        // The legacy field: backend-enforced, unconditional on participation.
        assertThat(ticketService.get(ticketId, salesOwnerActor).quotations()).isNotEmpty();
        assertForbidden(() -> ticketService.get(ticketId, importAssigneeActor));

        // The new PricingRequest/CustomerQuotation chain: the backend actually GRANTS import this read —
        // DealDocumentRegister.jsx hides it anyway, a documented FRONTEND-ONLY choice, not a backend refusal.
        assertThatCode(() -> quotationService.listForPricingRequest(pricingRequestId, importAssigneeActor))
            .doesNotThrowAnyException();
    }

    @Test
    void importAssignee_stillRefusedTheDepositNoticeAndRemainingInvoice_despiteBeingAParticipant() {
        // THE DIVERGENCE this test exists to pin: unlike attachments, being this deal's assignee does not help here —
        // DepositNoticeService#requireTicketViewer 403s import outright, before it ever asks who created or picked up
        // the ticket.
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, importAssigneeActor));
        // getRemainingInvoiceOptions calls requireTicketViewer FIRST, so this 403s even though the ticket is nowhere
        // near 'quotation_issued' — the auth check never reaches the status check for a caller requireTicketViewer
        // already refuses.
        assertForbidden(() -> depositNoticeService.getRemainingInvoiceOptions(ticketId, importAssigneeActor));
    }

    // REQUIRED CASE (coordinator addendum): a viewer admitted to none of the three row families sees NOTHING. The
    // deposit families below are unchanged: still 403.

    @Test
    void nonParticipantImport_seesNoQuotationOrDepositFamily_butMayReadAttachmentsInScope() throws Exception {
        assertForbidden(() -> ticketService.get(ticketId, importNonParticipantActor));
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, importNonParticipantActor));
        assertForbidden(() -> depositNoticeService.getRemainingInvoiceOptions(ticketId, importNonParticipantActor));
        assertAttachmentsOk(importNonParticipantActor);
    }

    // REQUIRED CASE: a `quotationId` that does not belong to THIS ticket must be refused with 400, even for the
    // deal's own owner.

    @Test
    void ownerCannotPickAQuotationIdThatDoesNotQualifyForThisTicket() {
        // No CustomerQuotation exists at all yet for this ticket's pricing request — so ANY quotationId, including one
        // that belongs to a wholly different ticket/pricing request elsewhere in the same database, is necessarily
        // outside this ticket's qualifying set.
        jdbc.update("UPDATE sales.ticket SET status = 'quotation_issued' WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId));

        // getRemainingInvoiceOptions with an explicit invalid quotationId refuses outright (400) rather than silently
        // falling back to a default — see resolveRemainingInvoice's own Javadoc: a caller-supplied id always wins or
        // is refused, never ignored.
        assertThatThrownBy(() -> depositNoticeService.getRemainingInvoiceOptions(
                ticketId, 999999L, salesOwnerActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    // helpers

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    private static MockHttpSession sessionFor(UserPrincipal actor) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return session;
    }

    private void assertAttachmentsForbidden(UserPrincipal actor) throws Exception {
        mvc.perform(get("/api/tickets/{ticketId}/attachments", ticketId).session(sessionFor(actor)))
            .andExpect(status().isForbidden());
    }

    private void assertAttachmentsOk(UserPrincipal actor) throws Exception {
        mvc.perform(get("/api/tickets/{ticketId}/attachments", ticketId).session(sessionFor(actor)))
            .andExpect(status().isOk());
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
