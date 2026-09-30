package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Shared real-Postgres wiring for the quotation ↔ deal linking SLICE 2 backend tests
 * ({@code .design/quotation-deal-link/SLICE-2-FLOW-A.md} Part 1: S2-B1 recipient, one pricing route
 * per deal, S2-B3 N6, S2-B4 {@code liveDirectQuotation}).
 *
 * <p>Every service is the production class, hand-wired against real Postgres exactly as
 * {@link DealQuotationConfirmOrderIntegrationTest} wires it — no Mockito anywhere, so every refusal
 * asserted by a subclass is the real service's, and every "nothing was written" is read back from
 * the real tables. The pricing-request rows the one-route rule keys on are inserted with an explicit
 * {@code status} (see {@link #insertPricingRequest}): the rule reads nothing else, and driving a
 * request through the whole import/CEO chain to reach, say, {@code QUOTATION_ACCEPTED} would test
 * that chain rather than this rule. The HTTP test creates its live request through the real
 * {@code PricingRequestController} instead.
 */
public abstract class AbstractDealQuotationSlice2IntegrationTest extends AbstractPostgresIntegrationTest {

    protected TicketRepository tickets;
    protected TicketService ticketService;
    protected DealQuotationService quotationService;
    protected PricingRequestService pricingRequestService;
    protected CustomerRepository customers;
    protected EmployeeRepository employees;

    protected UserPrincipal salesActor;
    protected UserPrincipal otherSalesActor;
    protected UserPrincipal salesManagerActor;

    protected CustomerDto customer;
    protected long projectId;

    @BeforeEach
    void wireSlice2Services() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), new FileStorageService("/tmp/glr-slice2-quotation-link-test-uploads"),
            factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, customers, new QuotationRenderer(),
            pricingRequestService, new EmployeeAuthRepository(jdbc));

        Mailer noMail = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {}

            @Override
            public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                 List<Mailer.InlineImage> inlineImages) {}

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Mailer.Attachment> attachments) {}
        };
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)),
            tickets, customers, new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, new PricingDecisionRepository(jdbc),
            new CustomerQuotationRepository(jdbc));
        quotationService.wireTicketService(ticketService);

        salesActor = actor(createEmployee("พนักงานขาย S2", "sales-s2@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        otherSalesActor = actor(createEmployee("พนักงานขาย S2 อื่น", "sales-s2-other@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
        salesManagerActor = actor(createEmployee("ผจก.ขาย S2", "sm-s2@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");

        customer = customers.create("บริษัท ผู้รับใบเสนอราคา จำกัด", "0100000000202", "202 ถนนทดสอบ", "สนญ.",
            "02-000-0202");
        projectId = projects.create(customer.id(), "โครงการ slice 2").id();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** An ordinary pipeline deal owned by {@code creator}, at LEAD_APPROACH, with a real entry channel. */
    protected long deal(UserPrincipal creator, String entryChannel) {
        return ticketService.create(new CreateTicketRequest("ดีล slice 2", "NORMAL", customer.name(), customer.id(),
            projectId, null, null, entryChannel, List.of(), LocalDate.now().plusDays(7)), creator).summary().id();
    }

    protected long deal(UserPrincipal creator) {
        return deal(creator, "OWNER_DIRECT");
    }

    /** One TILE line and one PLAIN line — the same shape DealQuotationConfirmOrderIntegrationTest uses. */
    protected static UpsertDealQuotationRequest draft(String recipientType) {
        ItemInput tile = new ItemInput(
            null, null, null, "SCG", "Slice2 Tile", "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false);
        ItemInput plain = new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, new BigDecimal("500.00"), BigDecimal.ZERO, null,
            null, null, null, WastageCalculator.LINE_TYPE_PLAIN, "ค่าขนส่ง", new BigDecimal("2"), "JOB", null, null,
            null, null, null, null, null, null);
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(tile, plain)).withRecipientType(recipientType);
    }

    protected DealQuotationDto approved(long ticketId, UserPrincipal creator, String recipientType) {
        DealQuotationDto created = quotationService.create(ticketId, draft(recipientType), creator);
        quotationService.submit(created.id(), creator);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    /** A {@code sales.pricing_request} row on {@code ticketId} in exactly {@code status} (with the
     * {@code cancelled_at} that {@code chk_pricing_request_cancelled_pair} demands of a CANCELLED row). */
    protected long insertPricingRequest(long ticketId, long requestedBy, String status) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, requested_by, status,
                                               cancelled_at)
            VALUES (:code, :ticketId, 'DESIGNER', :by, :status,
                    CASE WHEN CAST(:status AS varchar) = 'CANCELLED' THEN now() END)
            RETURNING pricing_request_id
            """, new MapSqlParameterSource()
                .addValue("code", "PCR-S2-" + ticketId + "-" + System.nanoTime())
                .addValue("ticketId", ticketId)
                .addValue("by", requestedBy)
                .addValue("status", status), Long.class);
    }

    protected void setDocStatus(long quotationId, String docStatus) {
        jdbc.update("UPDATE sales.quotation SET doc_status = :s WHERE quotation_id = :id",
            Map.of("s", docStatus, "id", quotationId));
    }

    // ── read-backs ───────────────────────────────────────────────────────────────────────────────

    protected TicketSummaryDto summary(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary();
    }

    protected int quotationCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    protected int eventCount(long ticketId, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            Map.of("id", ticketId, "kind", kind), Integer.class);
    }

    protected Map<String, Object> recipientColumns(long quotationId) {
        return jdbc.queryForMap("SELECT recipient_type, recipient_label FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId));
    }

    // ── assertions ───────────────────────────────────────────────────────────────────────────────

    protected static ApiException refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).as("expected a %s refusal", status).isInstanceOf(ApiException.class);
        ApiException e = (ApiException) thrown;
        assertThat(e.getStatus()).as("status of: %s", e.getMessage()).isEqualTo(status);
        return e;
    }

    protected static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── people ───────────────────────────────────────────────────────────────────────────────────

    protected long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh,
                                  String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    protected static UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
