package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RequestMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RespondMoreInformationRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;

/**
 * End-to-end acceptance walk for Step 1 of the sales pricing-request separation
 * (see docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md, "commit 7").
 *
 * <p>Exercises {@link TicketService}, {@link PricingRequestService},
 * {@link PricingRequestRepository}, {@link NotificationRepository} and
 * {@link EmployeeRepository} together against a real PostgreSQL database — the gap
 * neither {@code TicketServiceTest}/{@code PricingRequestServiceTest} (Mockito, all
 * collaborators stubbed) nor {@code PricingRequestRepositoryIntegrationTest} (single
 * repository, no service-layer authz) can cover: whether the two aggregates
 * (deal/ticket vs pricing request) actually stay decoupled when driven through their
 * real service layer, not just through direct repository calls.
 *
 * <p>Collaborators that would be awkward to stand up for real — {@link PriceCalcService}
 * (pricing engine business logic, unrelated to this walk) — are mocked. Everything else
 * ({@link TicketRepository}, {@link PricingRequestRepository}, {@link PricingRequestService},
 * {@link NotificationRepository}, {@link EmployeeRepository}, {@link CustomerRepository},
 * {@link ProjectRepository}, {@link QuotationRenderer}) is real and backed by the same
 * {@code jdbc} the base class resets before every test.
 */
class PricingRequestFlowIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private NotificationRepository notifications;

    private long salesRepId;
    private long secondSalesRepId;
    private long importUserId;
    private UserPrincipal salesActor;
    private UserPrincipal secondSalesActor;
    private UserPrincipal importActor;

    private long ticketId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        ObjectMapper objectMapper = new ObjectMapper();
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc));
        // PriceCalcService is genuinely awkward business logic unrelated to this walk
        // (no price is ever proposed/calculated here) — mocked, per the task brief.
        // Every other collaborator is real and cheap to construct.
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย หนึ่ง", "sales1@glr.co.th", "SALES", "แผนกขาย");
        secondSalesRepId = createEmployee(employees, "พนักงานขาย สอง", "sales2@glr.co.th", "SALES", "แผนกขาย");
        // source_code 'PCIM' matches NotificationRepository.notifyByRole("import", ...)'s
        // "d.source_code ILIKE 'PCIM%'" filter — required for the "Import got a
        // notification" assertion below to actually find a row.
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import@glr.co.th", "PCIM", "ฝ่ายนำเข้า");

        salesActor = actor(salesRepId, "sales");
        secondSalesActor = actor(secondSalesRepId, "sales");
        importActor = actor(importUserId, "import");

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

    // ── 1. Creating a deal with 3 products starts no pricing ───────────────

    @Test
    void createDeal_withThreeItems_startsAsDraftWithOneCreatedEventAndNoNotifications() {
        String status = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(status).isEqualTo(TicketStatus.DRAFT);

        List<String> eventKinds = jdbc.query(
            "SELECT kind FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), (rs, rowNum) -> rs.getString("kind"));
        assertThat(eventKinds).containsExactly(TicketEventKind.CREATED);

        long notificationCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.notification", Map.of(), Long.class);
        assertThat(notificationCount).isZero();

        int itemCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId), Integer.class);
        assertThat(itemCount).isEqualTo(3);
    }

    // ── 2. Two pricing requests coexist under one deal ─────────────────────

    @Test
    void twoPricingRequests_coexistOnTheSameDeal_bothDraftWithDistinctCodes() {
        PricingRequestDetailDto designer = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor);
        PricingRequestDetailDto buyer = pricingRequestService.createDraft(ticketId, buyerCreateRequest(), salesActor);

        assertThat(designer.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(buyer.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(designer.summary().requestCode()).matches("PCR-" + java.time.Year.now() + "-\\d{4}");
        assertThat(buyer.summary().requestCode()).matches("PCR-" + java.time.Year.now() + "-\\d{4}");
        assertThat(designer.summary().requestCode()).isNotEqualTo(buyer.summary().requestCode());

        List<PricingRequestSummaryDto> forTicket = pricingRequestService.listForTicket(ticketId, salesActor);
        assertThat(forTicket).extracting(PricingRequestSummaryDto::id)
            .containsExactlyInAnyOrder(designer.summary().id(), buyer.summary().id());
    }

    // ── 3. Submitting one leaves the other untouched, and does not move the deal ──

    @Test
    void submittingOneRequest_leavesTheOtherUntouchedAndDoesNotMoveTheDeal() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        long buyerId = pricingRequestService.createDraft(ticketId, buyerCreateRequest(), salesActor).summary().id();

        String salesStageBefore = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);

        PricingRequestDetailDto submitted = pricingRequestService.submit(designerId, salesActor);

        assertThat(submitted.summary().status()).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(submitted.summary().submittedAt()).isNotNull();

        PricingRequestSummaryDto stillDraft = pricingRequestService.get(buyerId, salesActor).summary();
        assertThat(stillDraft.status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(stillDraft.submittedAt()).isNull();

        String statusAfter = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        String salesStageAfter = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(statusAfter).isEqualTo(TicketStatus.DRAFT);
        assertThat(salesStageAfter).isEqualTo(salesStageBefore);

        // Import notified, but never CEO — notifyByRole only reaches employees whose
        // division source_code matches the role's filter (PCIM% for import, MD%/MN%
        // for ceo), and neither sales rep nor CEO has such a division here, so a
        // plain row-count proves both halves of the guarantee at once: exactly the
        // one PCIM-division employee (import) got a row, nobody else did.
        long notificationCount = jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification", Map.of(), Long.class);
        assertThat(notificationCount).isEqualTo(1);
        Long notifiedEmployeeId = jdbc.queryForObject(
            "SELECT employee_id FROM hr.notification LIMIT 1", Map.of(), Long.class);
        assertThat(notifiedEmployeeId).isEqualTo(importUserId);
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

    // ── 5. The information loop preserves ownership ────────────────────────

    @Test
    void informationLoop_returnsToImportReviewingAndPreservesPickupOwnership() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(designerId, salesActor);
        pricingRequestService.pickup(designerId, importActor);

        PricingRequestSummaryDto beforeLoop = pricingRequestService.get(designerId, importActor).summary();
        Instant pickedUpAtBefore = beforeLoop.pickedUpAt();
        assertThat(pickedUpAtBefore).isNotNull();

        pricingRequestService.requestInformation(designerId,
            new RequestMoreInformationRequest("กรุณาระบุขนาดสินค้าเพิ่มเติม", null), importActor);
        PricingRequestDetailDto responded = pricingRequestService.respondInformation(designerId,
            new RespondMoreInformationRequest("ขนาด 60x60 ซม."), salesActor);

        assertThat(responded.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(responded.summary().pickedUpAt()).isEqualTo(pickedUpAtBefore);
        assertThat(responded.summary().assignedImportId()).isEqualTo(importUserId);
    }

    // ── extra: a second sales rep cannot read the first rep's pricing requests ──

    @Test
    void aSecondSalesRep_cannotReadTheFirstReps_pricingRequest() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.get(designerId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.listForTicket(ticketId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private CreatePricingRequestRequest designerCreateRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "note for designer",
            List.of(pricingItem("Toyota", "Hilux")));
    }

    private CreatePricingRequestRequest buyerCreateRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(21),
            new BigDecimal("1200.00"), "THB", "note for buyer",
            List.of(pricingItem("Honda", "Civic")));
    }

    private PricingRequestItemRequest pricingItem(String brand, String model) {
        return new PricingRequestItemRequest(null, null, null, brand, model, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", QuantityType.REFERENCE, null, null, null);
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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
