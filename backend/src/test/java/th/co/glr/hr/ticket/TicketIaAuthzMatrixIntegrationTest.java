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
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.procurement.ProcurementRepository;
import th.co.glr.hr.procurement.ProcurementService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Pins the role -&gt; data gate for every tab the redesigned ticket-detail IA
 * (docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md) will project,
 * against the REAL services and REAL repositories on REAL Postgres. This exists so the coming UI
 * rebuild can trust "the backend would 403" without re-auditing the Java on every PR.
 *
 * <p>Every case below asks the question the wrong way round — can this caller reach data they
 * should not — per CLAUDE.md's "Permission changes must ship evidence" section, which names
 * {@code AttendanceScopeIntegrationTest} as the reference shape this test follows. This test
 * changes no production behaviour: every assertion pins what the code already does today.
 *
 * <p>Three places diverge from the surface-level IA doc / the briefed role matrix, confirmed by
 * reading the source (not inferred from the mock or the frontend):
 * <ul>
 *   <li>{@link TicketService#listActivities} gates on {@code requireDealOwnership} (deal owner /
 *       sales_manager / ceo only) — import and account are refused the activity feed, not just
 *       hr/employee. Comment at {@code TicketService.requireDealOwnership}: "Lost/reopen belong
 *       to the sales side."</li>
 *   <li>{@link AttachmentController#requireTicketAccess} is participant-or-manager, and
 *       {@code MANAGER_ROLES} includes {@code hr} — so hr, which cannot read a ticket anywhere
 *       else in this matrix, CAN read/upload/delete its attachments. This is pinned as-is (a
 *       known wider path under separate review), not fixed here.</li>
 *   <li>{@link th.co.glr.hr.deposit.DepositNoticeService#listByTicket} denies import too, despite
 *       its own {@code VIEWER_ROLES} {@code Set.of(...)} literal still listing {@code "import"}
 *       as a member — {@code requireTicketViewer} has a second, explicit import check right after
 *       the role check that the constant name doesn't advertise. A briefed version of this matrix
 *       claimed deposit notices were the one financial document import COULD read (unlike the
 *       payment ledger); that claim does not survive reading the method body. Both financial reads
 *       deny import, by two separate mechanisms — see the tab table correction this test file's
 *       Javadoc feeds into.</li>
 * </ul>
 */
class TicketIaAuthzMatrixIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private PricingRequestService pricingRequestService;
    private CustomerQuotationService quotationService;
    private DepositNoticeService depositNoticeService;
    private ProcurementService procurementService;
    private AttachmentController attachmentController;
    private MockMvc mvc;

    private long ticketId;
    private long pricingRequestId;

    private UserPrincipal salesOwnerActor;
    private UserPrincipal salesOtherActor;
    private UserPrincipal assigneeActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal hrActor;
    private UserPrincipal employeeActor;

    @BeforeEach
    void wireRealCollaboratorsAndSeedADeal() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ticket-ia-authz-matrix-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);

        // PriceCalcService is unrelated to every gate under test here (it prices item lines, not
        // authz) — mocked exactly as the existing CustomerQuotationIntegrationTest does, never the
        // service/repository actually being proven.
        PriceCalcService priceCalcUnused = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcUnused, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService);

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests,
            new th.co.glr.hr.pricingdecision.PricingDecisionRepository(jdbc), tickets, ticketService, customers,
            new QuotationRenderer(), notifications);

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer());

        procurementService = new ProcurementService(new ProcurementRepository(jdbc), pricingRequests, tickets, notifications);

        attachmentController = new AttachmentController(new AttachmentRepository(jdbc), new SessionContext(), tickets,
            new AuditService(new AuditLogRepository(jdbc), objectMapper), fileStorage);
        // AttachmentController#list has package-private (not public) visibility — it can only be
        // invoked directly from within th.co.glr.hr.attachment. Going through MockMvc against the
        // real HTTP route is not a workaround for that: it is the actually-correct shape anyway,
        // since a plain UserPrincipal call would skip SessionContext.requireUser entirely, and
        // AttachmentControllerTest (the existing unit test for this controller) uses the identical
        // pattern.
        mvc = MockMvcBuilders.standaloneSetup(attachmentController)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        long salesOwnerId = createEmployee(employees, "ตัวแทนขาย เจ้าของ", "sales-owner-iamatrix@glr.co.th", "SALES", "แผนกขาย");
        long salesOtherId = createEmployee(employees, "ตัวแทนขาย อื่น", "sales-other-iamatrix@glr.co.th", "SALES", "แผนกขาย");
        long assigneeId = createEmployee(employees, "ตัวแทนขาย ผู้รับมอบหมาย", "assignee-iamatrix@glr.co.th", "SALES", "แผนกขาย");
        long salesManagerId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-iamatrix@glr.co.th", "SALES", "แผนกขาย");
        long importId = createEmployee(employees, "ฝ่ายนำเข้า", "import-iamatrix@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoId = createEmployee(employees, "ผู้บริหาร", "ceo-iamatrix@glr.co.th", "MD", "ผู้บริหาร");
        long accountId = createEmployee(employees, "ฝ่ายบัญชี", "account-iamatrix@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        long hrId = createEmployee(employees, "ฝ่ายบุคคล", "hr-iamatrix@glr.co.th", "HR", "ฝ่ายบุคคล");
        long employeeId = createEmployee(employees, "พนักงานทั่วไป", "employee-iamatrix@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ");

        salesOwnerActor = actor(salesOwnerId, "sales");
        salesOtherActor = actor(salesOtherId, "sales");
        assigneeActor = actor(assigneeId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        importActor = actor(importId, "import");
        ceoActor = actor(ceoId, "ceo");
        accountActor = actor(accountId, "account");
        hrActor = actor(hrId, "hr");
        employeeActor = actor(employeeId, "employee");

        CustomerDto customer = customers.create(
            "บริษัท IA Matrix จำกัด", "0100000000099", "999 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0099");
        ProjectDto project = projects.create(customer.id(), "โครงการ IA Matrix");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล IA Matrix", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, List.of()),
            salesOwnerActor);
        ticketId = created.summary().id();

        // assignedToId is normally set by the deprecated import pickup() flow; set directly here
        // (fixture only, not the code path under test) so the attachment participant grant has a
        // second identity besides the ticket creator to prove against.
        jdbc.update("UPDATE sales.ticket SET assigned_to = :assigneeId WHERE ticket_id = :ticketId",
            Map.of("assigneeId", assigneeId, "ticketId", ticketId));

        long catalogPriceId = insertCatalogProduct("Factory IA Matrix", "TH", "IAMX-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        var item = new PricingRequestRequests.PricingRequestItemRequest(null, catalogPriceId, null,
            "Brand", "Model", "Brand Model", null, null, "60x60", "Factory IA Matrix",
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        var createPrRequest = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "ia matrix authz test", UUID.randomUUID().toString(), List.of(item));
        pricingRequestId = pricingRequestService.createDraft(ticketId, createPrRequest, salesOwnerActor)
            .summary().id();
        // SUBMITTED (not DRAFT): a DRAFT is only visible to its owner + ceo/sales_manager (a
        // separate, already-documented oddity — handoff 85) which would make import's POSITIVE
        // grant untestable here. The refusals under test (account/hr/employee) fire on the role
        // gate itself, before status is even inspected, so they hold at every status.
        pricingRequestService.submit(pricingRequestId, salesOwnerActor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ภาพรวม — TicketService.get() / requireViewAccess (VIEWER_ROLES + sales-owner-only)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void overview_hrCannotReadTheTicket() {
        assertForbidden(() -> ticketService.get(ticketId, hrActor));
    }

    @Test
    void overview_employeeCannotReadTheTicket() {
        assertForbidden(() -> ticketService.get(ticketId, employeeActor));
    }

    @Test
    void overview_nonOwnerSalesRepCannotReadAnotherRepsTicket() {
        assertForbidden(() -> ticketService.get(ticketId, salesOtherActor));
    }

    @Test
    void overview_ownerImportCeoAccountSalesManagerCanAllReadIt() {
        assertThatCode(() -> ticketService.get(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, importActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, accountActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ราคา — PricingRequestService.get() / requireViewable (VIEWER_ROLES excludes account)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void pricing_accountCannotReadAPricingRequest() {
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, accountActor));
    }

    @Test
    void pricing_hrCannotReadAPricingRequest() {
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, hrActor));
    }

    @Test
    void pricing_employeeCannotReadAPricingRequest() {
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, employeeActor));
    }

    @Test
    void pricing_salesImportCeoSalesManagerCanAllReadIt() {
        assertThatCode(() -> pricingRequestService.get(pricingRequestId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> pricingRequestService.get(pricingRequestId, importActor)).doesNotThrowAnyException();
        assertThatCode(() -> pricingRequestService.get(pricingRequestId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> pricingRequestService.get(pricingRequestId, salesManagerActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ใบเสนอราคา — CustomerQuotationService.listForPricingRequest() / VIEW_ROLES excludes account
    //
    // Exercised via listForPricingRequest rather than get(quotationId, ...) because both share
    // the identical VIEW_ROLES gate (requireRole is checked first in both), and
    // listForPricingRequest needs only the pricing request this fixture already builds — not the
    // full submit -> factory-quote -> costing -> pricing-decision -> quotation chain that get()
    // would additionally require just to have a row to look up.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void quotation_accountCannotListCustomerQuotations() {
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, accountActor));
    }

    @Test
    void quotation_hrCannotListCustomerQuotations() {
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, hrActor));
    }

    @Test
    void quotation_employeeCannotListCustomerQuotations() {
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, employeeActor));
    }

    @Test
    void quotation_nonOwnerSalesRepCannotListAnotherRepsCustomerQuotations() {
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, salesOtherActor));
    }

    @Test
    void quotation_salesManagerCeoImportCanAllList() {
        assertThatCode(() -> quotationService.listForPricingRequest(pricingRequestId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> quotationService.listForPricingRequest(pricingRequestId, salesManagerActor)).doesNotThrowAnyException();
        assertThatCode(() -> quotationService.listForPricingRequest(pricingRequestId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> quotationService.listForPricingRequest(pricingRequestId, importActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // การเงิน (payment ledger) — TicketService.listPayments() / requireViewAccess + explicit
    // IMPORT_ROLES 403
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ledger_importCannotReadThePaymentLedger() {
        assertForbidden(() -> ticketService.listPayments(ticketId, importActor));
    }

    @Test
    void ledger_hrCannotReadThePaymentLedger() {
        assertForbidden(() -> ticketService.listPayments(ticketId, hrActor));
    }

    @Test
    void ledger_employeeCannotReadThePaymentLedger() {
        assertForbidden(() -> ticketService.listPayments(ticketId, employeeActor));
    }

    @Test
    void ledger_ownerCeoAccountSalesManagerCanAllReadIt() {
        assertThatCode(() -> ticketService.listPayments(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listPayments(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listPayments(ticketId, accountActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listPayments(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // การเงิน (deposit notice) — DepositNoticeService.listByTicket() / requireTicketViewer
    //
    // ⚠ CORRECTS THE BRIEFED MATRIX: the source (and its own comment — "Phase B: import is
    // denied outright, unlike TicketService.requireViewAccess... every read here... is
    // off-limits") explicitly 403s import here too. The briefed claim that "import IS allowed
    // here" is wrong; both financial gates deny import, they just deny it through two different
    // mechanisms (an inline IMPORT_ROLES check in TicketService.listPayments, and the same check
    // baked into DepositNoticeService.requireTicketViewer itself, despite the VIEWER_ROLES
    // constant's Set.of(...) literal still listing "import" as a member).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void depositNotice_importCannotListDepositNoticesEitherDespiteBeingInTheViewerRolesConstant() {
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, importActor));
    }

    @Test
    void depositNotice_hrCannotListDepositNotices() {
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, hrActor));
    }

    @Test
    void depositNotice_employeeCannotListDepositNotices() {
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, employeeActor));
    }

    @Test
    void depositNotice_ownerCeoAccountSalesManagerCanAllListIt() {
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, accountActor)).doesNotThrowAnyException();
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // จัดซื้อ-ส่งมอบ (deliveries) — TicketService.listDeliveries() / requireViewAccess ONLY
    // (no extra IMPORT_ROLES-style carve-out, unlike listPayments)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void deliveries_hrCannotReadDeliveries() {
        assertForbidden(() -> ticketService.listDeliveries(ticketId, hrActor));
    }

    @Test
    void deliveries_employeeCannotReadDeliveries() {
        assertForbidden(() -> ticketService.listDeliveries(ticketId, employeeActor));
    }

    @Test
    void deliveries_everyOtherViewerRoleCanReadThem() {
        assertThatCode(() -> ticketService.listDeliveries(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, importActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, accountActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // จัดซื้อ-ส่งมอบ (raw factory PO) — ProcurementService.list() / RAW_PO_ROLES = {import, ceo}
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void rawPo_salesManagerCannotReadRawFactoryPurchaseOrders() {
        assertForbidden(() -> procurementService.list(null, salesManagerActor));
    }

    @Test
    void rawPo_salesOwnerCannotReadRawFactoryPurchaseOrders() {
        assertForbidden(() -> procurementService.list(null, salesOwnerActor));
    }

    @Test
    void rawPo_accountCannotReadRawFactoryPurchaseOrders() {
        assertForbidden(() -> procurementService.list(null, accountActor));
    }

    @Test
    void rawPo_hrCannotReadRawFactoryPurchaseOrders() {
        assertForbidden(() -> procurementService.list(null, hrActor));
    }

    @Test
    void rawPo_employeeCannotReadRawFactoryPurchaseOrders() {
        assertForbidden(() -> procurementService.list(null, employeeActor));
    }

    @Test
    void rawPo_importAndCeoCanReadThem() {
        assertThatCode(() -> procurementService.list(null, importActor)).doesNotThrowAnyException();
        assertThatCode(() -> procurementService.list(null, ceoActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // กิจกรรม — TicketService.listActivities() / requireDealOwnership (owner + sales_manager +
    // ceo ONLY — the oddity: import and account are refused, unlike every other viewer-role tab)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void activities_importCannotReadTheActivityFeed() {
        assertForbidden(() -> ticketService.listActivities(ticketId, importActor));
    }

    @Test
    void activities_accountCannotReadTheActivityFeed() {
        assertForbidden(() -> ticketService.listActivities(ticketId, accountActor));
    }

    @Test
    void activities_hrCannotReadTheActivityFeed() {
        assertForbidden(() -> ticketService.listActivities(ticketId, hrActor));
    }

    @Test
    void activities_employeeCannotReadTheActivityFeed() {
        assertForbidden(() -> ticketService.listActivities(ticketId, employeeActor));
    }

    @Test
    void activities_nonOwnerSalesRepCannotReadAnotherRepsActivityFeed() {
        assertForbidden(() -> ticketService.listActivities(ticketId, salesOtherActor));
    }

    @Test
    void activities_ownerSalesManagerCeoCanAllReadIt() {
        assertThatCode(() -> ticketService.listActivities(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listActivities(ticketId, salesManagerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listActivities(ticketId, ceoActor)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // เอกสาร (attachments) — AttachmentController.requireTicketAccess() / participant
    // (createdById or assignedToId) OR MANAGER_ROLES = {hr, sales_manager, ceo}
    //
    // Exercised through MockMvc against the real HTTP route (GET /api/tickets/{id}/attachments)
    // rather than by calling attachmentController.list(...) directly: that method is
    // package-private (visible only inside th.co.glr.hr.attachment, and this test must not touch
    // production code to relax that), and going through the real endpoint is the more faithful
    // shape anyway — a plain UserPrincipal call would skip SessionContext.requireUser entirely.
    // Same pattern AttachmentControllerTest already uses for this controller.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void attachments_importIsNeitherParticipantNorManagerAndIsRefused() throws Exception {
        assertAttachmentsForbidden(importActor);
    }

    @Test
    void attachments_accountIsNeitherParticipantNorManagerAndIsRefused() throws Exception {
        assertAttachmentsForbidden(accountActor);
    }

    @Test
    void attachments_aSalesRepWhoIsNeitherCreatorNorAssigneeIsRefused() throws Exception {
        assertAttachmentsForbidden(salesOtherActor);
    }

    @Test
    void attachments_employeeIsNeitherParticipantNorManagerAndIsRefused() throws Exception {
        assertAttachmentsForbidden(employeeActor);
    }

    @Test
    void attachments_theAssignedToParticipantCanReadThemEvenThoughTheyDidNotCreateTheTicket() throws Exception {
        assertAttachmentsOk(assigneeActor);
    }

    /**
     * Pins the known wider path (owner-confirmed, tracked separately, NOT fixed here): hr cannot
     * read a ticket, its pricing, its quotations, its ledger, its deposit notices, or its
     * activity feed anywhere else in this matrix — but IS in AttachmentController.MANAGER_ROLES,
     * so hr CAN list/read/upload/delete this ticket's attachments. If this test ever goes red
     * because someone "fixed" MANAGER_ROLES, that is an intentional authz change requiring its
     * own review, not a regression to silently patch around.
     */
    @Test
    void attachments_hrCanReadThemDespiteBeingUnableToReadTheTicketItself() throws Exception {
        assertForbidden(() -> ticketService.get(ticketId, hrActor));
        assertAttachmentsOk(hrActor);
    }

    @Test
    void attachments_ownerAndSalesManagerAndCeoCanAllReadThem() throws Exception {
        assertAttachmentsOk(salesOwnerActor);
        assertAttachmentsOk(salesManagerActor);
        assertAttachmentsOk(ceoActor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Forbidden");
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
