package th.co.glr.hr.factoryquote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
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
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Change 2 (guard hardening plan): {@code FactoryQuoteRepository.supersede} must refuse to
 * supersede a quote that has already left the live/negotiable lifecycle ({@code NOT_AVAILABLE},
 * {@code SUPERSEDED}, {@code CANCELLED} — all terminal), rather than silently flipping a dead row
 * to SUPERSEDED. {@code FactoryQuoteService} already restricts its one call site to
 * {@code RESPONSE_RECEIVED}/{@code NEGOTIATING}/{@code READY_FOR_COSTING}, so these tests exercise
 * {@link FactoryQuoteRepository} directly — the layer any future, unguarded caller would actually
 * reach.
 *
 * <p>Each test drives exactly ONE {@code factory_quote} row within its own, freshly created
 * pricing request (a fresh one per {@code @Test} thanks to {@code AbstractPostgresIntegrationTest}
 * resetting the schema before every test method), so the {@code uq_factory_quote_current_factory}
 * partial unique index (V61: {@code (pricing_request_id, factory_name_snapshot) WHERE is_current =
 * TRUE AND status <> 'CANCELLED'}) never has a second row to collide with — see the plan's FIXTURE
 * TRAP note.
 */
class FactoryQuoteSupersedeGuardIntegrationTest extends AbstractPostgresIntegrationTest {
    private FactoryQuoteRepository factoryQuotes;
    private long salesRepId;
    private UserPrincipal salesActor;
    private long pricingRequestId;

    @BeforeEach
    void wireServicesAndCreatePricingRequest() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-factoryquote-supersede-guard-test-uploads");

        PricingRequestService pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        factoryQuotes = new FactoryQuoteRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย การ์ด2", "sales-guard2@glr.co.th", "SALES", "แผนกขาย");
        salesActor = actor(salesRepId, "sales");

        CustomerDto customer = customers.create(
            "บริษัท Guard2 " + UUID.randomUUID() + " จำกัด", "0100000000029", "456 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0029");
        ProjectDto project = projects.create(customer.id(), "โครงการ Guard2");
        TicketDto ticketDto = ticketService.create(
            new CreateTicketRequest("ดีล Guard2", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Guard2", "Free Text Factory"))),
            salesActor);
        long ticketId = ticketDto.summary().id();

        // A DRAFT pricing request is enough — the repository methods under test here have no
        // pricing-request-status gate of their own (only sales.factory_quote's own status column
        // and the FK to pricing_request_id matter), so submit()/pickup() would be unused ceremony.
        pricingRequestId = pricingRequestService.createDraft(ticketId, pricingRequest(), salesActor).summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Change 2 tests
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void supersede_refusesCancelledQuote_rowUnchanged() {
        long quoteId = createDraftQuote("Factory Cancelled");
        int cancelledRows = factoryQuotes.cancelOpenForPricingRequest(pricingRequestId, "no longer needed", salesRepId);
        assertThat(cancelledRows).isEqualTo(1);
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.CANCELLED);

        int result = factoryQuotes.supersede(quoteId);

        assertThat(result).isZero();
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.CANCELLED);
    }

    @Test
    void supersede_refusesNotAvailableQuote_rowUnchanged() {
        long quoteId = createDraftQuote("Factory NotAvailable");
        int requestedRows = factoryQuotes.markRequested(quoteId, "vendor@example.com", "Subject", "Body", salesRepId);
        assertThat(requestedRows).isEqualTo(1);
        int notAvailableRows = factoryQuotes.markNotAvailable(quoteId, "โรงงานไม่รับออเดอร์", salesRepId);
        assertThat(notAvailableRows).isEqualTo(1);
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.NOT_AVAILABLE);

        int result = factoryQuotes.supersede(quoteId);

        assertThat(result).isZero();
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.NOT_AVAILABLE);
    }

    @Test
    void supersede_isIdempotentRefusalOnAlreadySupersededQuote() {
        long quoteId = createDraftQuote("Factory AlreadySuperseded");
        factoryQuotes.markRequested(quoteId, "vendor@example.com", "Subject", "Body", salesRepId);
        factoryQuotes.updateFirstResponse(quoteId, "REF-1", "THB", "30 days", "45 days", null, null);
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        int firstSupersede = factoryQuotes.supersede(quoteId); // fixture setup, not under test
        assertThat(firstSupersede).isEqualTo(1);
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.SUPERSEDED);

        int result = factoryQuotes.supersede(quoteId);

        assertThat(result).isZero();
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        assertThat(isCurrentOf(quoteId)).isFalse();
    }

    @Test
    void supersede_allowsLiveResponseReceivedQuote_positiveControl() {
        long quoteId = createDraftQuote("Factory Live");
        factoryQuotes.markRequested(quoteId, "vendor@example.com", "Subject", "Body", salesRepId);
        factoryQuotes.updateFirstResponse(quoteId, "REF-1", "THB", "30 days", "45 days", null, null);
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        int result = factoryQuotes.supersede(quoteId);

        assertThat(result).isEqualTo(1);
        assertThat(statusOf(quoteId)).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        assertThat(isCurrentOf(quoteId)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private long createDraftQuote(String factoryName) {
        return factoryQuotes.createDraft(pricingRequestId, null, factoryName,
            "vendor@example.com", "Subject", "Body", salesRepId);
    }

    private String statusOf(long quoteId) {
        return jdbc.queryForObject(
            "SELECT status FROM sales.factory_quote WHERE factory_quote_id = :id", Map.of("id", quoteId), String.class);
    }

    private boolean isCurrentOf(long quoteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT is_current FROM sales.factory_quote WHERE factory_quote_id = :id", Map.of("id", quoteId), Boolean.class));
    }

    private PricingRequestRequests.CreatePricingRequestRequest pricingRequest() {
        // Free-text item: no catalog product needed since these tests never call submit() (the
        // catalog-completeness gate lives on submit(), not createDraft()) — see
        // PricingFactoryQuoteCostingIntegrationTest#freeTextPricingItem's own comment.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, null, null, null, null, "Free text item", null, null, "60x60", "Free Text Factory",
            new BigDecimal("1"), new BigDecimal("1"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "supersede guard request", UUID.randomUUID().toString(),
            List.of(item));
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
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
