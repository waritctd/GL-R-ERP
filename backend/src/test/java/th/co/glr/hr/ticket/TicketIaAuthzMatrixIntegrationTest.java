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
import th.co.glr.hr.procurement.ProcurementRepository;
import th.co.glr.hr.procurement.ProcurementService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Pins the role -> data gate for every tab the redesigned ticket-detail IA
 * (docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md) will project, against the
 * REAL services and REAL repositories on REAL Postgres. */
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
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ticket-ia-authz-matrix-test-uploads");
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

        procurementService = new ProcurementService(new ProcurementRepository(jdbc), pricingRequests, tickets, notifications, ticketService);

        attachmentController = new AttachmentController(new AttachmentRepository(jdbc), new SessionContext(), tickets,
            new AuditService(new AuditLogRepository(jdbc), objectMapper), fileStorage);
        // AttachmentController#list has package-private (not public) visibility — it can only be invoked directly from
        // within th.co.glr.hr.attachment.
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

        // assignedToId is normally set by the deprecated import pickup() flow; set directly here (fixture only, not
        // the code path under test) so the attachment participant grant has a second identity besides the ticket
        // creator to prove against.
        jdbc.update("UPDATE sales.ticket SET assigned_to = :assigneeId WHERE ticket_id = :ticketId",
            Map.of("assigneeId", assigneeId, "ticketId", ticketId));

        long catalogPriceId = insertCatalogProduct("Factory IA Matrix", "TH", "IAMX-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        var item = new PricingRequestRequests.PricingRequestItemRequest(null, catalogPriceId, null,
            "Brand", "Model", "Brand Model", "White", "Matte", "60x60", "Factory IA Matrix",
            null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        var createPrRequest = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "ia matrix authz test", UUID.randomUUID().toString(), List.of(item));
        pricingRequestId = pricingRequestService.createDraft(ticketId, createPrRequest, salesOwnerActor)
            .summary().id();
        // SUBMITTED (not DRAFT): a DRAFT is only visible to its owner + ceo/sales_manager (a separate,
        // already-documented oddity — handoff 85) which would make import's POSITIVE grant untestable here.
        pricingRequestService.submit(pricingRequestId, salesOwnerActor);
    }

    // ภาพรวม — TicketService.get() / requireViewAccess (VIEWER_ROLES + sales-owner-only)

    /** import has its OWN per-deal endpoint (GET /api/import/deals/{id}, ImportDealService — see
     * ImportDealAuthzIntegrationTest) and is refused the whole-deal read, mirroring the account finance-view
     * ruling. Pinned here beside the rows it differs from. */
    @Test
    void overview_importCannotReadTheWholeDeal() {
        assertForbidden(() -> ticketService.get(ticketId, importActor));
    }

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
    void overview_ownerCeoSalesManagerCanAllReadIt() {
        assertThatCode(() -> ticketService.get(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // H1 lockdown: account no longer reads a deal through GET /tickets/{id} at ALL -- not even one inside its list
    // scope (it reads the finance view, GET /api/finance/deals/{id}). This row used to be in the "can all read it"
    // list above.
    @Test
    void overview_accountCannotReadTheTicket_evenInsideItsListScope() {
        assertForbidden(() -> ticketService.get(ticketId, accountActor));
        putDealInAccountScope();
        assertForbidden(() -> ticketService.get(ticketId, accountActor));
    }

    /** A deal at S12 is inside account's list scope (H1). */
    private void putDealInAccountScope() {
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'PROCUREMENT' WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", ticketId));
    }

    // ราคา — PricingRequestService.get() / requireViewable (VIEWER_ROLES excludes account)

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

    // Exercise the shared quotation view gate through listing, avoiding an unrelated full quotation lifecycle
    // fixture.

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

    // การเงิน (payment ledger) — TicketService.listPayments() / requireViewAccess + explicit IMPORT_ROLES 403

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
    void ledger_ownerCeoSalesManagerCanAllReadIt() {
        assertThatCode(() -> ticketService.listPayments(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listPayments(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listPayments(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // H1 lockdown: the ledger is read by account inside the finance deal view only.
    @Test
    void ledger_accountIsRefusedOnTheTicketsPath_evenInsideItsListScope() {
        putDealInAccountScope();
        assertForbidden(() -> ticketService.listPayments(ticketId, accountActor));
    }

    // การเงิน (deposit notice) — DepositNoticeService.listByTicket() / requireTicketViewer ⚠ CORRECTS THE BRIEFED
    // MATRIX: the source (and its own comment — "Phase B: import is denied outright, unlike
    // TicketService.requireViewAccess... every read here...

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
    void depositNotice_ownerCeoSalesManagerCanAllListIt() {
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, salesManagerActor)).doesNotThrowAnyException();
    }

    // H1 lockdown: account is ROW-SCOPED here -- refused on a deal outside its list scope (this deal is still at
    // the lead stage), allowed once the deal is inside it.
    @Test
    void depositNotice_accountIsRowScoped() {
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, accountActor));
        putDealInAccountScope();
        assertThatCode(() -> depositNoticeService.listByTicket(ticketId, accountActor)).doesNotThrowAnyException();
    }

    // จัดซื้อ-ส่งมอบ (deliveries) — TicketService.listDeliveries() / requireViewAccess ONLY (no extra
    // IMPORT_ROLES-style carve-out, unlike listPayments)

    @Test
    void deliveries_hrCannotReadDeliveries() {
        assertForbidden(() -> ticketService.listDeliveries(ticketId, hrActor));
    }

    @Test
    void deliveries_employeeCannotReadDeliveries() {
        assertForbidden(() -> ticketService.listDeliveries(ticketId, employeeActor));
    }

    @Test
    void deliveries_everyOtherViewerRoleCanReadThem_exceptAccount() {
        assertThatCode(() -> ticketService.listDeliveries(ticketId, salesOwnerActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, importActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, ceoActor)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.listDeliveries(ticketId, salesManagerActor)).doesNotThrowAnyException();
        // H1 lockdown: account no longer reads deliveries through /tickets, even inside its list scope.
        putDealInAccountScope();
        assertForbidden(() -> ticketService.listDeliveries(ticketId, accountActor));
    }

    // จัดซื้อ-ส่งมอบ (raw factory PO) — ProcurementService.list() / RAW_PO_ROLES = {import, ceo}

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

    // กิจกรรม — TicketService.listActivities() / requireDealOwnership (owner + sales_manager + ceo ONLY — the
    // oddity: import and account are refused, unlike every other viewer-role tab)

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

    // เอกสาร (attachments) — AttachmentController.requireTicketReadAccess() / TicketAccessPolicy.canViewDocuments:
    // participant (createdById or assignedToId) OR a deal VIEWER_ROLE, with sales AND import both scoped to deals
    // they participate in.

    /** Import's document access is ROW-SCOPED (owner ruling 2026-09-30, superseding the blanket refusal that issue
     * #389's review pinned here). This matrix deal carries a live SUBMITTED pricing request, so it is in scope;
     * the out-of-scope refusal is proven against a second, pipeline-early deal. */
    @Test
    void attachments_importReadsAnInScopeDealsDocumentsButNotAnOutOfScopeDeals() throws Exception {
        assertAttachmentsOk(importActor);

        long outOfScopeTicketId = jdbc.queryForObject(
            "INSERT INTO sales.ticket (code, title, created_by) VALUES ('IAM-OOS-1', 'out of import scope', :by) "
                + "RETURNING ticket_id",
            Map.of("by", salesOwnerActor.id()), Long.class);
        mvc.perform(get("/api/tickets/{ticketId}/attachments", outOfScopeTicketId)
                .session(sessionFor(importActor)))
            .andExpect(status().isForbidden());
    }

    // H1 lockdown: account reads the documents only on a deal inside its list scope (row-scoped); the ticket read
    // itself is refused. This used to assert account reads both, on any deal.
    @Test
    void attachments_accountReadsTheDocumentsItMustConfirmMoneyAgainst_onlyInsideItsListScope() throws Exception {
        assertAttachmentsForbidden(accountActor);
        putDealInAccountScope();
        assertAttachmentsOk(accountActor);
        assertForbidden(() -> ticketService.get(ticketId, accountActor));
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

    /** The wider path this matrix used to pin as a known divergence is CLOSED (issue #389). */
    @Test
    void attachments_hrCannotReadThemAnyMoreThanItCanReadTheTicketItself() throws Exception {
        assertForbidden(() -> ticketService.get(ticketId, hrActor));
        assertAttachmentsForbidden(hrActor);
    }

    @Test
    void attachments_ownerAndSalesManagerAndCeoCanAllReadThem() throws Exception {
        assertAttachmentsOk(salesOwnerActor);
        assertAttachmentsOk(salesManagerActor);
        assertAttachmentsOk(ceoActor);
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
