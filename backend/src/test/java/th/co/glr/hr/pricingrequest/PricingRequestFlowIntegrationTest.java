package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** End-to-end acceptance walk for Step 1 of the sales pricing-request separation (see
 * docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md, "commit 7"). */
class PricingRequestFlowIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private NotificationRepository notifications;

    private long salesRepId;
    private long secondSalesRepId;
    private long importUserId;
    private long secondImportUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal secondSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal secondImportActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;

    private long ticketId;
    // Financial-integrity review Finding A (commit 3): submit() now requires every item's catalog snapshot to be
    // fully resolved, so pricingItem() below must reference a real, ACTIVE catalog product rather than pure free
    // text.
    private long catalogProductId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        ObjectMapper objectMapper = new ObjectMapper();
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-pricing-flow-test-uploads"), factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        salesRepId = createEmployee(employees, "พนักงานขาย หนึ่ง", "sales1@glr.co.th", "SALES", "แผนกขาย");
        secondSalesRepId = createEmployee(employees, "พนักงานขาย สอง", "sales2@glr.co.th", "SALES", "แผนกขาย");
        // source_code 'PCIM' matches NotificationRepository.notifyByRole("import", ...)'s "d.source_code ILIKE
        // 'PCIM%'" filter — required for the "Import got a notification" assertion below to actually find a row.
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        // Principal role drives service authz; keep this employee out of PCIM so submit-notification tests still have
        // exactly one Import recipient.
        secondImportUserId = createEmployee(employees, "ฝ่ายนำเข้า สำรอง", "import2@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ");
        ceoUserId = createManagingDirector(employees, "ผู้บริหาร ทดสอบ", "ceo@glr.co.th");
        accountUserId = createEmployee(employees, "ฝ่ายบัญชี ทดสอบ", "account@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager@glr.co.th", "SALES", "ฝ่ายขาย");

        salesActor = actor(salesRepId, "sales");
        secondSalesActor = actor(secondSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        secondImportActor = actor(secondImportUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        catalogProductId = insertCatalogProduct("Test Catalog Factory", "TH", "TEST-PROD-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท ทดสอบ จำกัด", "0100000000000", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบ");

        TicketDto created = ticketService.create(
            new CreateTicketRequest("ใบเสนอราคา", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null,
                List.of(
                    item("Toyota", "Hilux"),
                    item("Honda", "Civic"),
                    item("Mazda", "BT-50"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ── 4. Import pickup assigns the request, never the deal (the landmine guard) ──

    @Test
    void importPickup_assignsTheRequestOnly_neverTheDealOrItsEventLog() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        long buyerId = pricingRequestService.createDraft(ticketId, buyerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(designerId, salesActor);

        PricingRequestDetailDto pickedUp = pricingRequestService.pickup(designerId, importActor);

        assertThat(pickedUp.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(pickedUp.summary().assignedImportId()).isEqualTo(importUserId);

        Long ticketAssignedTo = jdbc.queryForObject(
            "SELECT assigned_to FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), Long.class);
        assertThat(ticketAssignedTo).isNull();

        List<String> eventKinds = jdbc.query(
            "SELECT kind FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), (rs, rowNum) -> rs.getString("kind"));
        assertThat(eventKinds).containsExactly(TicketEventKind.CREATED); // still exactly one, unchanged

        PricingRequestSummaryDto stillDraft = pricingRequestService.get(buyerId, salesActor).summary();
        assertThat(stillDraft.status()).isEqualTo(PricingRequestStatus.DRAFT);
    }


    // Checks statuses and retained assignment data through pickup, information request, and response.


    // ── extra: a second sales rep cannot discover draft, then cannot read submitted ──

    @Test
    void aSecondSalesRep_cannotReadTheFirstReps_pricingRequest() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.get(designerId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        pricingRequestService.submit(designerId, salesActor);
        assertThatThrownBy(() -> pricingRequestService.get(designerId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.listForTicket(ticketId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── Pricing Request attachments (V69, review remediation COMMIT 4) ──────

    @Test
    void draftPricingRequest_isPrivateToOwnerAndOversightUntilSubmitted() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        assertThat(pricingRequestService.get(id, salesActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, salesActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);
        assertThat(pricingRequestService.list(null, null, false, salesActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);

        assertThat(pricingRequestService.get(id, ceoActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, ceoActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);
        assertThat(pricingRequestService.get(id, salesManagerActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, salesManagerActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);

        assertThatThrownBy(() -> pricingRequestService.get(id, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(pricingRequestService.list(null, null, false, secondSalesActor))
            .extracting(PricingRequestSummaryDto::id)
            .doesNotContain(id);
        assertThatThrownBy(() -> pricingRequestService.listForTicket(ticketId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> pricingRequestService.get(id, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(pricingRequestService.listForTicket(ticketId, importActor))
            .extracting(PricingRequestSummaryDto::id)
            .doesNotContain(id);
        assertThat(pricingRequestService.list(null, null, false, importActor))
            .extracting(PricingRequestSummaryDto::id)
            .doesNotContain(id);
    }


    @Test
    void concurrentCreateDraft_sameClientRequestId_returnsOneRequestWithOneItemSetAndOneCreatedEvent() throws Exception {
        String clientRequestId = "55555555-5555-5555-5555-555555555555";
        var racingRequests = new RacingPricingRequestRepository(jdbc, salesRepId, clientRequestId);
        var racingService = new PricingRequestService(
            racingRequests, tickets, notifications, new ObjectMapper(), new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-pricing-flow-test-uploads"), factoryQuoteCarryForward());
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "race request", clientRequestId,
            List.of(pricingItem("Toyota", "Hilux")));
        Callable<PricingRequestDetailDto> task = () -> racingService.createDraft(ticketId, request, salesActor);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(task);
            var second = executor.submit(task);

            PricingRequestDetailDto firstResult = first.get(5, TimeUnit.SECONDS);
            PricingRequestDetailDto secondResult = second.get(5, TimeUnit.SECONDS);

            long id = firstResult.summary().id();
            assertThat(secondResult.summary().id()).isEqualTo(id);
            assertOneRequestOneItemSetOneCreatedEvent(id, salesRepId, clientRequestId);
        } finally {
            executor.shutdownNow();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private CreatePricingRequestRequest designerCreateRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "note for designer", "33333333-3333-3333-3333-333333333333",
            List.of(pricingItem("Toyota", "Hilux")));
    }

    private CreatePricingRequestRequest buyerCreateRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(21),
            new BigDecimal("1200.00"), "THB", "note for buyer", "44444444-4444-4444-4444-444444444444",
            List.of(pricingItem("Honda", "Civic")));
    }

    // V185 (direct-deal-form parity): requestedQty/requestedQtySqm/requestedUnit/requestedUnitBasis are no longer
    // sent — PricingRequestService#resolveItems derives them from the tile fields below.
    private PricingRequestItemRequest pricingItem(String brand, String model) {
        return new PricingRequestItemRequest(null, catalogProductId, null, brand, model, brand + " " + model,
            "สีทดสอบ", "ผิวทดสอบ", "60x60", null,
            null, null, null, null, QuantityType.REFERENCE, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 1, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            null, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private TicketItemRequest item(String brand, String model) {
        return new TicketItemRequest(brand, model, "White", "Matte", "L", null,
            new BigDecimal("1"), null, null, null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** The CEO fixture -- needs a real position, see CustomerQuotationIntegrationTest for why: CeoApproverRule keys
     * the notified set on position กรรมการผู้จัดการ alone, and #createEmployee leaves positionTh null. Division
     * stays MD, so the `ceo` role and every authz assertion here are unchanged. */
    private long createManagingDirector(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    private void assertOneRequestOneItemSetOneCreatedEvent(long id, long requestedBy, String clientRequestId) {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request
             WHERE requested_by = :requestedBy
               AND client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("requestedBy", requestedBy, "clientRequestId", clientRequestId), Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
            """, Map.of("id", id), Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = :eventKind
            """, Map.of("id", id, "eventKind", PricingRequestEventKind.PRICING_REQUEST_CREATED), Long.class))
            .isEqualTo(1L);
    }

    private static class RacingPricingRequestRepository extends PricingRequestRepository {
        private final long requestedBy;
        private final String clientRequestId;
        private final AtomicInteger emptyLookups = new AtomicInteger();
        private final CountDownLatch bothThreadsChecked = new CountDownLatch(2);

        RacingPricingRequestRepository(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                                       long requestedBy, String clientRequestId) {
            super(jdbc);
            this.requestedBy = requestedBy;
            this.clientRequestId = clientRequestId;
        }

        @Override
        public Optional<PricingRequestSummaryDto> findByClientRequestId(long requestedBy, String clientRequestId) {
            Optional<PricingRequestSummaryDto> existing = super.findByClientRequestId(requestedBy, clientRequestId);
            if (existing.isEmpty()
                    && requestedBy == this.requestedBy
                    && this.clientRequestId.equals(clientRequestId)
                    && emptyLookups.incrementAndGet() <= 2) {
                bothThreadsChecked.countDown();
                try {
                    if (!bothThreadsChecked.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for both createDraft calls to reach the race point");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for concurrent createDraft race", e);
                }
            }
            return existing;
        }
    }
}
