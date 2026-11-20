package th.co.glr.hr.ticket;

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
            new DepositNoticePdfRenderer(), customers, quotationRepository, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));

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

    /** import has its OWN per-deal endpoint (GET /api/import/deals/{id}, ImportDealService — see
     * ImportDealAuthzIntegrationTest) and is refused the whole-deal read, mirroring the account finance-view
     * ruling. Pinned here beside the rows it differs from. */
    @Test
    void overview_importCannotReadTheWholeDeal() {
        assertForbidden(() -> ticketService.get(ticketId, importActor));
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

    @Test
    void pricing_accountCannotReadAPricingRequest() {
        assertForbidden(() -> pricingRequestService.get(pricingRequestId, accountActor));
    }

    @Test
    void quotation_accountCannotListCustomerQuotations() {
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, accountActor));
    }

    @Test
    void quotation_nonOwnerSalesRepCannotListAnotherRepsCustomerQuotations() {
        assertForbidden(() -> quotationService.listForPricingRequest(pricingRequestId, salesOtherActor));
    }

    @Test
    void ledger_importCannotReadThePaymentLedger() {
        assertForbidden(() -> ticketService.listPayments(ticketId, importActor));
    }

    // H1 lockdown: the ledger is read by account inside the finance deal view only.
    @Test
    void ledger_accountIsRefusedOnTheTicketsPath_evenInsideItsListScope() {
        putDealInAccountScope();
        assertForbidden(() -> ticketService.listPayments(ticketId, accountActor));
    }

    @Test
    void depositNotice_importCannotListDepositNoticesEitherDespiteBeingInTheViewerRolesConstant() {
        assertForbidden(() -> depositNoticeService.listByTicket(ticketId, importActor));
    }

    @Test
    void rawPo_salesOwnerCannotReadRawFactoryPurchaseOrders() {
        assertForbidden(() -> procurementService.list(null, salesOwnerActor));
    }

    @Test
    void activities_importCannotReadTheActivityFeed() {
        assertForbidden(() -> ticketService.listActivities(ticketId, importActor));
    }

    /** The per-person read-all grant (V238): reads the deal page and its shared readers on a deal the holder does not own,
     * whatever the role (hr, a plain employee, the qc person who acts as sales), and writes nothing: no comment, no
     * activity, no expected payment. Without the flag every one of those reads is still refused; import stays refused even
     * with the flag. */
    @Test
    void readAllSalesHolder_readsTheDealAndItsTabsButWritesNothing() {
        var expected = new th.co.glr.hr.salesboard.ExpectedPaymentService(
            new th.co.glr.hr.salesboard.ExpectedPaymentRepository(jdbc), ticketService, tickets);
        var monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        var payment = new th.co.glr.hr.salesboard.ExpectedPaymentDtos.ExpectedPaymentRequest("DEPOSIT", new BigDecimal("100.00"), monday);
        var activity = new DealActivityRequest(LocalDate.now(), "CALL", "x");
        List<UserPrincipal> holders = List.of(hrActor, employeeActor, salesOtherActor);

        // No flag: unchanged refusals (hr / employee / a non-owning rep), reads and the read-only expected payments alike.
        for (UserPrincipal who : holders) {
            assertForbidden(() -> ticketService.get(ticketId, who));
            assertForbidden(() -> ticketService.listActivities(ticketId, who));
            assertForbidden(() -> expected.list(ticketId, who));
        }

        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id IN (:a, :b, :c, :d)",
            Map.of("a", hrActor.id(), "b", employeeActor.id(), "c", salesOtherActor.id(), "d", importActor.id()));
        for (UserPrincipal who : holders) {
            assertThatCode(() -> ticketService.get(ticketId, who)).as("get %s", who.role()).doesNotThrowAnyException();
            assertThatCode(() -> ticketService.actions(ticketId, who)).doesNotThrowAnyException();
            assertThatCode(() -> ticketService.listPayments(ticketId, who)).doesNotThrowAnyException();
            assertThatCode(() -> ticketService.listDeliveries(ticketId, who)).doesNotThrowAnyException();
            assertThatCode(() -> ticketService.requireDealReadAccess(ticketId, who)).doesNotThrowAnyException();
            assertThatCode(() -> ticketService.listActivities(ticketId, who)).doesNotThrowAnyException();
            assertThatCode(() -> expected.list(ticketId, who)).doesNotThrowAnyException();
            // The flag is a READ right only.
            assertForbidden(() -> ticketService.comment(ticketId, new CommentRequest("hello"), who));
            assertForbidden(() -> ticketService.addActivity(ticketId, activity, who));
            assertForbidden(() -> expected.create(ticketId, payment, who));
            assertForbidden(() -> expected.update(ticketId, 1L, payment, who));
            assertForbidden(() -> expected.cancel(ticketId, 1L, who));
        }
        // import stays price-free whatever the flag says.
        assertForbidden(() -> ticketService.get(ticketId, importActor));
        assertForbidden(() -> ticketService.requireDealReadAccess(ticketId, importActor));
        assertForbidden(() -> ticketService.listActivities(ticketId, importActor));
        assertForbidden(() -> expected.list(ticketId, importActor));
        // account keeps its finance-only path even with the flag.
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id = :a", Map.of("a", accountActor.id()));
        assertForbidden(() -> ticketService.get(ticketId, accountActor));
    }

    @Test
    void attachments_aSalesRepWhoIsNeitherCreatorNorAssigneeIsRefused() throws Exception {
        assertAttachmentsForbidden(salesOtherActor);
    }

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
