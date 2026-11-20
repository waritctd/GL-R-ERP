package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UndoImportStepRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Slice 5 (spec 2026-11-15 §7.11 item 3, plan task 5.3): an advance of a factory shipment's step can be undone for
 * 30 seconds — the snackbar on /fulfilment — because advancing is forward-only and otherwise final. The undo is a NEW
 * write path, so its authorisation is pinned here through the real service, repository and Postgres (V216), and the
 * cases are written wrong-way-round: who may NOT undo, and WHEN it is refused.
 *
 * <p>Rules: same role gate as advance (import / ceo); only the actor who made that advance; only within 30 s of it
 * (the database clock decides); only while the step is still the one that advance set (a later advance by anyone
 * ends the window); one level only; never an advance that reached RECEIVED (that rolled the deal up and notified the
 * rep — undoing it is a revision, not a snackbar). The undo is recorded as a ticket event.
 * Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ImportStepUndoIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;
    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal importUser;
    private UserPrincipal otherImportUser;
    private UserPrincipal ceoUser;
    private UserPrincipal salesManager;
    private UserPrincipal accountUser;
    private long ticketId;
    private long irId;

    @BeforeEach
    void wire() {
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, new RecordingSalesMailer(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), new FileStorageService("/tmp/glr-ir-undo-test-uploads"), factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, new CustomerRepository(jdbc),
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc),
            new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        service = new ImportRequestService(new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(), stored,
            new FactoryConfigRepository(jdbc), tickets, ticketService, notifications);

        ownerId = employee("UNDO-OWN");
        owner = principal(ownerId, "sales");
        importUser = principal(employee("UNDO-IMP"), "import");
        otherImportUser = principal(employee("UNDO-IMP2"), "import");
        ceoUser = principal(employee("UNDO-CEO"), "ceo");
        salesManager = principal(employee("UNDO-SM"), "sales_manager");
        accountUser = principal(employee("UNDO-ACC"), "account");

        jdbc.update("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('Undo Factory', 'IT', 'EUR')",
            Map.of());
        ticketId = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, sales_stage)
            VALUES ('IRUNDO-1', 'ทดสอบเลิกทำ', :by, 'ลูกค้า', 'quotation_issued', 'DEPOSIT_PAID', :stage)
            RETURNING ticket_id""", new MapSqlParameterSource("by", ownerId).addValue("stage", DealStage.ORDER_RECEIVED),
            Long.class);
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, 'Undo Factory', 'Raku', '20x20', :q, 'pcs', 0, 'Undo Factory')""",
            new MapSqlParameterSource("t", ticketId).addValue("q", new BigDecimal("40")));
        irId = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();
        service.issue(irId, null, owner);
    }

    // ── the happy path ──────────────────────────────────────────────────────────────────────────

    @Test
    void theActorUndoesTheirOwnAdvanceWithin30Seconds_theStepAndItsStampAreRestored_andAnEventIsRecorded() {
        Map<String, Object> before = stepColumns();
        int eventsBefore = stepEvents();
        advance(importUser, ImportRequestStep.ORDERED);

        ImportRequestDto undone = service.undoStep(irId, new UndoImportStepRequest(ImportRequestStep.ORDERED), importUser);

        assertThat(undone.importStep()).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(stepColumns()).isEqualTo(before);
        assertThat(stepEvents()).isEqualTo(eventsBefore + 2);
        assertThat(lastStepEventMessage()).contains("เลิกทำ");
    }

    // ── who may NOT ─────────────────────────────────────────────────────────────────────────────

    @Test
    void anotherImportUserCannotUndoSomeoneElsesAdvance() {
        advance(importUser, ImportRequestStep.ORDERED);
        assertRefused(() -> service.undoStep(irId, new UndoImportStepRequest(ImportRequestStep.ORDERED), otherImportUser),
            HttpStatus.FORBIDDEN);
        assertThat(currentStep()).isEqualTo(ImportRequestStep.ORDERED);
    }

    // ── when it is refused ──────────────────────────────────────────────────────────────────────

    @Test
    void after30SecondsTheUndoIsRefused() {
        advance(importUser, ImportRequestStep.ORDERED);
        jdbc.update("UPDATE sales.import_request SET step_advanced_at = now() - interval '31 seconds'"
            + " WHERE import_request_id = :id", Map.of("id", irId));
        assertRefused(() -> service.undoStep(irId, new UndoImportStepRequest(ImportRequestStep.ORDERED), importUser),
            HttpStatus.CONFLICT);
        assertThat(currentStep()).isEqualTo(ImportRequestStep.ORDERED);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private ImportRequestRepository stored() {
        return new ImportRequestRepository(jdbc);
    }

    private void advance(UserPrincipal who, String step) {
        service.advanceStep(irId, new AdvanceImportStepRequest(step, null, null), who);
    }

    private String currentStep() {
        return jdbc.queryForObject("SELECT import_step FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", irId), String.class);
    }

    private Map<String, Object> stepColumns() {
        return jdbc.queryForMap("""
            SELECT import_step, import_step_at, import_step_by_id, import_step_by_name, import_step_note
              FROM sales.import_request WHERE import_request_id = :id""", Map.of("id", irId));
    }

    private int stepEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t"
            + " AND kind = 'IMPORT_STEP_ADVANCED'", Map.of("t", ticketId), Integer.class);
    }

    private String lastStepEventMessage() {
        return jdbc.queryForObject("SELECT message FROM sales.ticket_event WHERE ticket_id = :t"
            + " AND kind = 'IMPORT_STEP_ADVANCED' ORDER BY event_id DESC LIMIT 1", Map.of("t", ticketId), String.class);
    }

    private static void assertRefused(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-undo@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long employee(String code) {
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, first_name_th, last_name_th)"
            + " VALUES (:c, 'ทดสอบ', 'เลิกทำ') RETURNING employee_id", Map.of("c", code), Long.class);
    }
}
