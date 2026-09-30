package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
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
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;

/**
 * Real-Postgres coverage for {@link DepositNoticeRepository#findDealQuotationDepositPercent} —
 * owner ruling (2026-09-29): the deposit-notice PERCENT is LOCKED to the source quotation's own
 * {@code sales.quotation.deposit_percent}, never editable at the deposit-notice stage. A Mockito
 * unit test on {@code DepositNoticeServiceTest} already proves the SERVICE calls this repository
 * method and trusts its result over a caller-supplied value — it cannot prove the method's own
 * SQL actually reads the right row out of {@code sales.quotation}. This class drives that SQL
 * directly.
 *
 * <p>Fixture note: rows are written straight over JDBC rather than through {@code
 * CustomerQuotationService}/{@code DealQuotationService} — neither ever writes {@code
 * deposit_percent} today (see the repository method's own Javadoc: the legacy engine's {@code
 * CreateCustomerQuotationRequest} has no such field at all, and only {@code DealQuotationService}'s
 * v2 engine writes it in production). A real {@code ticket_id} and, for the legacy-branch cases, a
 * real {@code pricing_request_id} are still required — both are real FK targets on {@code
 * sales.quotation} — so this still builds a genuine ticket (+ a draft pricing request for the
 * legacy cases only; the v2 branch's own FK, {@code origin}, needs no pricing request at all).
 */
class DepositNoticeRepositoryDepositPercentIntegrationTest extends AbstractPostgresIntegrationTest {
    private DepositNoticeRepository depositNotices;
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private long employeeId;
    private int quotationVersionSeq;

    @BeforeEach
    void setUp() {
        depositNotices = new DepositNoticeRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        employeeId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย มัดจำทดสอบ", null, null, null, null, null, null, null,
            "sales-deppct-" + UUID.randomUUID().toString().substring(0, 8) + "@glr.co.th", null,
            "SALES", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-deposit-pct-test-uploads");
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications,
            new com.fasterxml.jackson.databind.ObjectMapper(), new ContactRepository(jdbc), fileStorage,
            factoryQuoteCarryForward());
    }

    // ── Legacy chain (origin IS NULL, pricing_request_id IS NOT NULL): ACCEPTED > ISSUED ──────

    @Test
    void legacyChain_prefersAcceptedQuotationsDepositPercentOverAnIssuedOne() {
        long ticketId = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(ticketId, "ISSUED", (short) 20);
        insertLegacyQuotation(ticketId, "ACCEPTED", (short) 30);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0.30");
    }

    @Test
    void legacyChain_fallsBackToIssuedWhenNoneAccepted() {
        long ticketId = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(ticketId, "ISSUED", (short) 20);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0.20");
    }

    @Test
    void legacyChain_ignoresNonLiveStatusesLikeDraftOrRejected() {
        long ticketId = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(ticketId, "DRAFT", (short) 40);
        insertLegacyQuotation(ticketId, "REJECTED", (short) 50);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isEmpty();
    }

    @Test
    void legacyChain_nullDepositPercentOnTheLiveQuotationYieldsEmpty() {
        long ticketId = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(ticketId, "ACCEPTED", null);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isEmpty();
    }

    // ── V2 deal-quotation chain (origin IN ('PRICING_REQUEST','DEAL_DIRECT')) ─────────────────

    @Test
    void dealDirectOrigin_readsApprovedQuotationsDepositPercent() {
        long ticketId = newBareTicket();
        insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "APPROVED", (short) 50);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0.50");
    }

    @Test
    void pricingRequestOrigin_prefersAcceptedOverIssuedOverApproved() {
        long ticketId = newBareTicket();
        insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "APPROVED", (short) 10);
        insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "ISSUED", (short) 20);
        insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "ACCEPTED", (short) 30);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0.30");
    }

    @Test
    void dealDirectOrigin_ignoresNonLiveStatusesLikeDraftOrCancelled() {
        long ticketId = newBareTicket();
        insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "DRAFT", (short) 60);
        insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "CANCELLED", (short) 70);

        Optional<BigDecimal> result = depositNotices.findDealQuotationDepositPercent(ticketId);

        assertThat(result).isEmpty();
    }

    @Test
    void noQuotationAtAllYieldsEmpty() {
        long ticketId = newBareTicket();

        assertThat(depositNotices.findDealQuotationDepositPercent(ticketId)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private long newBareTicket() {
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        CustomerDto customer = customers.create(
            "บริษัท มัดจำทดสอบ " + UUID.randomUUID() + " จำกัด", "0100000000099", "1 ถนนทดสอบ", "สนญ", "02-000-0099");
        ProjectDto project = projects.create(customer.id(), "โครงการมัดจำทดสอบ");
        return tickets.create(
            new CreateTicketRequest("ดีลมัดจำทดสอบ", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, List.of(ticketItem())),
            tickets.nextTicketCode(), employeeId, "Sales");
    }

    /** A ticket with one DRAFT pricing request attached — enough to satisfy {@code
     * sales.quotation.pricing_request_id}'s FK for the legacy-branch fixtures; the request is
     * never submitted/priced any further because these tests write the quotation row themselves. */
    private long newTicketWithPricingRequestDraft() {
        long catalogProductId = insertCatalogProduct("Factory DepPct", "IT",
            "TEST-DEPPCT-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");
        long ticketId = newBareTicket();
        long ticketItemId = tickets.findById(ticketId).orElseThrow().items().get(0).id();

        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile DepPct", "SCG Tile DepPct",
            "White", "Matte", "60x60", "Factory DepPct", null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "deposit-percent lock test", UUID.randomUUID().toString(), List.of(item));
        pricingRequestService.createDraft(ticketId, request, actorFor(employeeId));

        return ticketId;
    }

    /** Legacy quotation row: {@code origin IS NULL}, {@code pricing_request_id IS NOT NULL} —
     * exactly the scope {@code CustomerQuotationRepository#findByTicket} uses. */
    private void insertLegacyQuotation(long ticketId, String docStatus, Short depositPercent) {
        long pricingRequestId = pricingRequests.findByTicket(ticketId).get(0).id();
        int version = ++quotationVersionSeq;
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, doc_status, quotation_version,
                 pricing_request_id, origin, deposit_percent)
            VALUES
                (:ticketId, :number, :issuedBy, :docStatus, :version,
                 :pricingRequestId, NULL, :depositPercent)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                // number is VARCHAR(30) and UNIQUE — "QTD<ticketId>-<version>" is both short
                // enough and unique per (ticket, test method), since each test method gets its
                // own fresh ticket and quotationVersionSeq restarts per test instance.
                .addValue("number", "QTD" + ticketId + "-" + version)
                .addValue("issuedBy", employeeId)
                .addValue("docStatus", docStatus)
                .addValue("version", version)
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("depositPercent", depositPercent));
    }

    /** V2 deal-quotation row: {@code origin IN ('PRICING_REQUEST','DEAL_DIRECT')} — no {@code
     * pricing_request_id} needed (nullable; the v2 engine's own scope is {@code origin} alone). */
    private void insertOriginTaggedQuotation(long ticketId, String origin, String docStatus, Short depositPercent) {
        int version = ++quotationVersionSeq;
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, doc_status, quotation_version, origin, deposit_percent)
            VALUES
                (:ticketId, :number, :issuedBy, :docStatus, :version, :origin, :depositPercent)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QTD" + ticketId + "-" + version)
                .addValue("issuedBy", employeeId)
                .addValue("docStatus", docStatus)
                .addValue("version", version)
                .addValue("origin", origin)
                .addValue("depositPercent", depositPercent));
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile DepPct", "White", "Matte", "60x60", "Factory DepPct",
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private th.co.glr.hr.auth.UserPrincipal actorFor(long employeeId) {
        return new th.co.glr.hr.auth.UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId,
            "sales", employeeId, true, LocalDate.now(), false, null, false);
    }
}
