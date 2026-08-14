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
import th.co.glr.hr.deposit.DepositNoticeRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceRenderer;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Slice D ("the เอกสาร document register"): characterises what the REAL backend enforces for the
 * three document-row families {@code DealDocumentRegister.jsx} rolls up — customer quotations
 * (both the PricingRequest/CustomerQuotation chain and the legacy embedded {@code
 * ticket.quotations}), the deposit notice + remaining invoice, and formal attachments — through
 * the real services and repositories on real Postgres, per CLAUDE.md's "Permission changes must
 * ship evidence" section ({@link th.co.glr.hr.attendance.AttendanceScopeIntegrationTest} is the
 * cited reference shape; {@link TicketIaAuthzMatrixIntegrationTest} is this test's direct sibling
 * — it already pins the per-tab matrix this document register draws its three predicates from).
 * Every case below is written the WRONG WAY ROUND: it asks whether a caller can reach data it
 * should not, never merely that it can reach its own.
 *
 * <p>This test does not re-litigate the whole IA matrix — see
 * {@code TicketIaAuthzMatrixIntegrationTest} for the exhaustive per-tab pin. It adds exactly what
 * Slice D's own frontend gate needs proven, that the matrix did not already need to state:
 *
 * <ul>
 *   <li><b>The remaining invoice shares {@link DepositNoticeService}'s deposit-notice gate, NOT
 *       the attachments gate.</b> {@code getRemainingInvoiceXlsx} calls {@code
 *       requireTicketViewer} FIRST, before its own {@code quotation_issued} status check — so the
 *       403 fires (or does not) purely off the same role/participation rule as {@code
 *       listByTicket}, independent of the ticket's business status. This branch's brief originally
 *       sketched the remaining invoice under the SAME "participant OR canViewTicketDocuments" shape
 *       as attachments; reading the source shows that is wrong — an import rep who IS this deal's
 *       assignee (a participant) reaches attachments but is STILL refused the remaining invoice,
 *       exactly like the deposit notice. See {@code importAssignee_*} below and this branch's PR
 *       body for the full note.</li>
 *   <li><b>The legacy embedded {@code ticket.quotations} field is nulled for {@code import}
 *       unconditionally by {@code TicketService#projectForRole} — regardless of participation.</b>
 *       This is a role-only strip, not identity-scoped, so it fires even for this deal's own
 *       import assignee. It is a SEPARATE mechanism from the new PricingRequest/CustomerQuotation
 *       chain, which {@code CustomerQuotationService.VIEW_ROLES} genuinely grants {@code import}
 *       (pinned by the sibling matrix's own {@code quotation_salesManagerCeoImportCanAllList}) —
 *       DealDocumentRegister.jsx additionally hides that chain from {@code import} too, but that
 *       half is a documented FRONTEND-ONLY choice (salesViewScope.js), not something this backend
 *       enforces. See {@code importAssignee_seesZeroQuotationRows} below, which asserts both halves
 *       precisely so a future reader cannot confuse one for the other.</li>
 * </ul>
 */
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

        // PriceCalcService prices item lines, unrelated to every gate under test here — mocked
        // exactly as TicketIaAuthzMatrixIntegrationTest and CustomerQuotationIntegrationTest do.
        PriceCalcService priceCalcUnused = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcUnused, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService);

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests,
            new th.co.glr.hr.pricingdecision.PricingDecisionRepository(jdbc), tickets, ticketService, customers,
            new QuotationRenderer(), notifications);

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        attachmentController = new AttachmentController(new AttachmentRepository(jdbc), new SessionContext(), tickets,
            new AuditService(new AuditLogRepository(jdbc), objectMapper), fileStorage);
        // AttachmentController#list is package-private — only reachable from within
        // th.co.glr.hr.attachment. Going through MockMvc against the real route is the actually
        // correct shape anyway (a bare UserPrincipal call would skip SessionContext.requireUser
        // entirely) — same pattern as TicketIaAuthzMatrixIntegrationTest/AttachmentControllerTest.
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

        // The real pickup flow (import claiming a deal) sets assigned_to — set directly here
        // (fixture only, not the code path under test), mirroring
        // TicketIaAuthzMatrixIntegrationTest's own identical fixture comment, so this deal has a
        // real PARTICIPANT import actor to prove the participant-vs-role divergence against.
        jdbc.update("UPDATE sales.ticket SET assigned_to = :assigneeId WHERE ticket_id = :ticketId",
            Map.of("assigneeId", importAssigneeId, "ticketId", ticketId));

        // Opus review finding: the two "import cannot see legacy quotations" cases below used to
        // assert `ticket.quotations()).isEmpty()` on a deal that had NO legacy quotation for
        // ANYONE — that assertion is true whether or not `TicketService#projectForRole` strips
        // anything, so mutating the stripping away left every affected test green (a "cannot
        // fail" test, exactly what CLAUDE.md's mutation-check rule exists to catch). Seeding one
        // real legacy row here — via the same `TicketRepository#createQuotation` the retired
        // ticket-native quotation flow itself used (see TicketRepositoryIntegrationTest for the
        // same call shape) — makes the comparison mean something: a role that MAY see it (sales,
        // the owner) must see exactly one row on this same deal where import sees zero.
        tickets.createQuotation(ticketId, "QT-2026-DOCREG-0001", salesOwnerId, new BigDecimal("1000.00"));

        long catalogPriceId = insertCatalogProduct("Factory Doc Register", "TH", "DOCREG-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        var item = new PricingRequestRequests.PricingRequestItemRequest(null, catalogPriceId, null,
            "Brand", "Model", "Brand Model", null, null, "60x60", "Factory Doc Register",
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        var createPrRequest = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "doc register authz test", UUID.randomUUID().toString(), List.of(item));
        pricingRequestId = pricingRequestService.createDraft(ticketId, createPrRequest, salesOwnerActor)
            .summary().id();
        // SUBMITTED (not DRAFT — a DRAFT is owner/ceo/sales_manager-only, which would make
        // import's genuine positive grant on the new chain untestable). Every refusal under test
        // fires on the role/participation gate itself, before pricing-request status is even
        // inspected, so it holds at every status — same reasoning as the sibling matrix.
        pricingRequestService.submit(pricingRequestId, salesOwnerActor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // account: can list deposit notices and attachments, cannot read pricing requests/quotations
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void account_canListDepositNoticesAndAttachmentsForTheTicket() throws Exception {
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, accountActor)).doesNotThrowAnyException();
        assertAttachmentsOk(accountActor);
    }

    @Test
    void account_cannotReadPricingRequestsOrCustomerQuotations() {
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, accountActor));
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, accountActor));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // import: the legacy embedded ticket.quotations field is nulled unconditionally (role-only,
    // NOT participant-scoped) — the "import cannot list customer quotations" case, at the layer
    // this register actually reads it from.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void import_cannotSeeTheLegacyEmbeddedQuotationsOnTheTicketDto_evenAsThisDealsAssignee() {
        // Opus review finding: without a real seeded quotation, "import sees an empty list" was
        // true only because NO ONE had a quotation on this deal — a green test that could not go
        // red if `projectForRole` stopped stripping anything. The fixture now seeds exactly one
        // legacy quotation (QT-2026-DOCREG-0001) as the deal owner — assert the SAME deal both
        // ways: the owner genuinely sees it, import genuinely does not.
        TicketDto asOwner = ticketService.get(ticketId, salesOwnerActor);
        assertThat(asOwner.quotations()).extracting(QuotationDto::number).containsExactly("QT-2026-DOCREG-0001");
        assertThat(asOwner.quotation()).isNotNull();

        TicketDto asAssignee = ticketService.get(ticketId, importAssigneeActor);
        assertThat(asAssignee.quotations()).isEmpty();
        assertThat(asAssignee.quotation()).isNull();

        TicketDto asNonParticipant = ticketService.get(ticketId, importNonParticipantActor);
        assertThat(asNonParticipant.quotations()).isEmpty();
        assertThat(asNonParticipant.quotation()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // a non-participant sales rep cannot reach another rep's documents (any of the three families)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonParticipantSalesRep_cannotReachAnotherRepsDocuments() throws Exception {
        assertForbidden(() -> ticketService.get(ticketId, salesOtherActor));
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, salesOtherActor));
        assertForbidden(() -> depositNoticeService.getRemainingInvoiceXlsx(ticketId, salesOtherActor));
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, salesOtherActor));
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, salesOtherActor));
        assertAttachmentsForbidden(salesOtherActor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // hr / employee cannot reach any of it
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void hrAndEmployee_cannotReachAnyOfIt() throws Exception {
        for (UserPrincipal actor : List.of(hrActor, employeeActor)) {
            assertForbidden(() -> ticketService.get(ticketId, actor));
            assertForbidden(() -> depositNoticeService.listByTicket(ticketId, actor));
            assertForbidden(() -> depositNoticeService.getRemainingInvoiceXlsx(ticketId, actor));
            assertForbidden(() -> pricingRequestService.get(pricingRequestId, actor));
            assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, actor));
            assertAttachmentsForbidden(actor);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // REQUIRED CASE (coordinator addendum): an import rep who IS this deal's assignee is a real
    // PARTICIPANT — it reaches attachments, but stays refused BOTH quotation families and BOTH
    // deposit-notice-shaped reads. This is the row where "participant OR canViewTicketDocuments"
    // (attachments) and "role, unconditional on participation" (deposit/remaining-invoice,
    // legacy-quotations) visibly disagree — the most likely future regression in this slice if
    // someone ever collapses the register's three predicates into one.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void importAssignee_reachesAttachmentsAsAParticipant() throws Exception {
        assertThatCode(() -> ticketService.get(ticketId, importAssigneeActor)).doesNotThrowAnyException();
        assertAttachmentsOk(importAssigneeActor);
    }

    @Test
    void importAssignee_seesZeroQuotationRows_bothTheLegacyFieldAndTheFrontendChoiceOnTheNewChain() {
        // The legacy field: backend-enforced, unconditional on participation. Same seeded deal as
        // import_cannotSeeTheLegacyEmbeddedQuotationsOnTheTicketDto_evenAsThisDealsAssignee above
        // (QT-2026-DOCREG-0001, seeded as the owner) — asserting the owner side here too, not just
        // re-trusting the other test, since Opus review found the un-seeded version of this exact
        // assertion could not fail (mutating projectForRole's stripping away left it green).
        assertThat(ticketService.get(ticketId, salesOwnerActor).quotations()).isNotEmpty();
        assertThat(ticketService.get(ticketId, importAssigneeActor).quotations()).isEmpty();

        // The new PricingRequest/CustomerQuotation chain: the backend actually GRANTS import this
        // read (CustomerQuotationService.VIEW_ROLES includes it, pinned by the sibling matrix's
        // quotation_salesManagerCeoImportCanAllList) — DealDocumentRegister.jsx hides it anyway, a
        // documented FRONTEND-ONLY choice (ticketDetailTabs.js's own "KNOWN GAP"), not a backend
        // refusal. Asserted here, explicitly NOT as `assertForbidden`, so this test cannot be
        // mistaken for proving a 403 that does not exist.
        assertThatCode(() -> quotationService.listForPricingRequest(pricingRequestId, importAssigneeActor))
            .doesNotThrowAnyException();
    }

    @Test
    void importAssignee_stillRefusedTheDepositNoticeAndRemainingInvoice_despiteBeingAParticipant() {
        // THE DIVERGENCE this test exists to pin: unlike attachments, being this deal's assignee
        // does not help here — DepositNoticeService#requireTicketViewer 403s import outright,
        // before it ever asks who created or picked up the ticket.
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, importAssigneeActor));
        // getRemainingInvoiceXlsx calls requireTicketViewer FIRST, so this 403s even though the
        // ticket is nowhere near 'quotation_issued' — the auth check never reaches the status
        // check for a caller requireTicketViewer already refuses.
        assertForbidden(() -> depositNoticeService.getRemainingInvoiceXlsx(ticketId, importAssigneeActor));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // REQUIRED CASE (coordinator addendum): a viewer admitted to none of the three row families
    // sees NOTHING — not merely "the tab renders". A non-participant import rep is the real-world
    // instance of this: every VIEWER_ROLE the backend recognises (sales/import/ceo/account/
    // sales_manager) already has a positive grant somewhere in at least one of
    // canViewTicketDocuments/PRICING_AND_QUOTATION_ROLES EXCEPT this one specific combination —
    // import that has not yet picked the deal up loses all three: the legacy quotations field
    // (role-only strip), the new-chain quotations (hidden by frontend choice, not a 403 — see
    // above), and both deposit-notice-shaped reads AND attachments (both genuinely 403, since it
    // is neither a participant nor in either role list).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonParticipantImport_seesZeroRowsAcrossAllThreeFamilies() throws Exception {
        assertThatCode(() -> ticketService.get(ticketId, importNonParticipantActor)).doesNotThrowAnyException();
        assertThat(ticketService.get(ticketId, importNonParticipantActor).quotations()).isEmpty();
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, importNonParticipantActor));
        assertForbidden(() -> depositNoticeService.getRemainingInvoiceXlsx(ticketId, importNonParticipantActor));
        assertAttachmentsForbidden(importNonParticipantActor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

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
