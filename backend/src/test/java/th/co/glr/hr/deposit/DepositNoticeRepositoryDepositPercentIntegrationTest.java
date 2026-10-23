package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Real-Postgres coverage for {@link DepositNoticeRepository#findGoverningQuotation}, the one pick
 * of the quotation a ticket-level deposit notice bills: its lines and its percent (owner rulings
 * 2026-09-29 and 2026-10-23). {@code DepositNoticeServiceTest} proves the SERVICE takes both from
 * the row this returns; it cannot prove the SQL picks the right row out of {@code sales.quotation}.
 * This class drives that SQL directly.
 *
 * <p>Fixture note: rows are written straight over JDBC rather than through {@code
 * CustomerQuotationService}/{@code DealQuotationService}, so one test can set any status, origin,
 * insertion order and stored {@code deposit_percent} (the legacy engine never writes that column:
 * {@code CreateCustomerQuotationRequest} has no such field; only {@code DealQuotationService}
 * writes it). A real {@code ticket_id} and, for the legacy-branch cases, a
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
    // The legacy PDF prints 50% whatever the row stores (ticket.QuotationRenderer), so every live
    // legacy row bills 0.50; the fixtures store other percents to prove the column is never read.

    @Test
    void legacyChain_anAcceptedQuotationBeatsANewerIssuedOne_andBillsItsPdfsFifty() {
        long ticketId = newTicketWithPricingRequestDraft();
        long accepted = insertLegacyQuotation(ticketId, "ACCEPTED", (short) 30);
        insertLegacyQuotation(ticketId, "ISSUED", (short) 20);

        assertGoverning(ticketId, accepted, true, "0.50");
    }

    @Test
    void legacyChain_fallsBackToIssuedWhenNoneAccepted_andBillsItsPdfsFifty() {
        long ticketId = newTicketWithPricingRequestDraft();
        long issued = insertLegacyQuotation(ticketId, "ISSUED", (short) 20);

        assertGoverning(ticketId, issued, true, "0.50");
    }

    /** A ticket-level legacy quotation ({@code pricing_request_id IS NULL}) never governs, even
     * ACCEPTED and newest: the scope is {@code CustomerQuotationRepository#findByTicket}'s. */
    @Test
    void legacyChain_ignoresNonLiveStatusesAndTicketLevelQuotations() {
        long ticketId = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(ticketId, "DRAFT", (short) 40);
        insertLegacyQuotation(ticketId, "REJECTED", (short) 50);
        int version = ++quotationVersionSeq;
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version)
            VALUES (:ticketId, :number, :issuedBy, 'ACCEPTED', :version)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("number", "QTD" + ticketId + "-" + version).addValue("issuedBy", employeeId)
                .addValue("version", version));

        assertThat(depositNotices.findGoverningQuotation(ticketId)).isEmpty();
    }

    @Test
    void legacyChain_nullDepositPercentOnTheLiveQuotationYieldsTheFiftyItsPdfPrints() {
        long ticketId = newTicketWithPricingRequestDraft();
        long accepted = insertLegacyQuotation(ticketId, "ACCEPTED", null);

        assertGoverning(ticketId, accepted, true, "0.50");
    }

    // ── Mixed ticket: the latest quotation the customer ANSWERED governs, whichever engine ─────
    // (owner ruling 2026-10-23). Answered = ACCEPTED, or a DEAL_DIRECT quotation's terminal
    // APPROVED. An unanswered ISSUED row governs only when nothing was answered.

    /** The legacy row wins because the customer answered it, not because it is legacy: the
     * sibling v2 row is newer. */
    @Test
    void mixedTicket_anAnsweredLegacyQuotationBeatsANewerUnansweredV2One() {
        long ticketId = newTicketWithPricingRequestDraft();
        long legacyAccepted = insertLegacyQuotation(ticketId, "ACCEPTED", null);
        insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "ISSUED", null);

        assertGoverning(ticketId, legacyAccepted, true, "0.50");
    }

    /** Newest wins whichever engine it is: both insertion orders, so neither engine is preferred. */
    @Test
    void mixedTicket_withNothingAnswered_theNewestIssuedQuotationGoverns() {
        long v2Newer = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(v2Newer, "ISSUED", null);
        long v2Issued = insertOriginTaggedQuotation(v2Newer, "PRICING_REQUEST", "ISSUED", (short) 40);
        assertGoverning(v2Newer, v2Issued, false, "0.40");

        long legacyNewer = newTicketWithPricingRequestDraft();
        insertOriginTaggedQuotation(legacyNewer, "PRICING_REQUEST", "ISSUED", (short) 40);
        long legacyIssued = insertLegacyQuotation(legacyNewer, "ISSUED", null);
        assertGoverning(legacyNewer, legacyIssued, true, "0.50");
    }

    /** The DEAL_DIRECT row is the OLDER one, so only its answered rank (not quotation_id) can pick it. */
    @Test
    void mixedTicket_aDealDirectApprovedQuotationIsAnAnswer_andBeatsANewerLegacyIssuedOne() {
        long ticketId = newTicketWithPricingRequestDraft();
        long dealDirect = insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "APPROVED", (short) 40);
        insertLegacyQuotation(ticketId, "ISSUED", null);

        assertGoverning(ticketId, dealDirect, false, "0.40");
    }

    /** A DEAL_DIRECT approval is an answer level with ACCEPTED (owner ruling 2026-10-23), so the
     * newer of the two governs. Only data from before the 2026-09-30 direct-quotation lock can
     * hold both on one ticket. */
    @Test
    void mixedTicket_aDealDirectApprovedQuotationBeatsAnOlderAcceptedOne() {
        long ticketId = newTicketWithPricingRequestDraft();
        insertLegacyQuotation(ticketId, "ACCEPTED", null);
        long dealDirect = insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "APPROVED", (short) 40);

        assertGoverning(ticketId, dealDirect, false, "0.40");
    }

    // ── V2 deal-quotation chain (origin IN ('PRICING_REQUEST','DEAL_DIRECT')) ─────────────────

    /** The new-engine PDF prints a null percent as 30% ({@code DealQuotationRenderAdapter}). */
    @Test
    void pricingRequestOrigin_nullDepositPercentOnTheLiveQuotationYieldsTheThirtyItsPdfPrints() {
        long ticketId = newBareTicket();
        long accepted = insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "ACCEPTED", null);

        assertGoverning(ticketId, accepted, false, "0.30");
    }

    @Test
    void pricingRequestOrigin_anAcceptedQuotationBeatsNewerIssuedAndApprovedOnes() {
        long ticketId = newBareTicket();
        long accepted = insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "ACCEPTED", (short) 30);
        insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "ISSUED", (short) 20);
        insertOriginTaggedQuotation(ticketId, "PRICING_REQUEST", "APPROVED", (short) 10);

        assertGoverning(ticketId, accepted, false, "0.30");
    }

    @Test
    void dealDirectOrigin_ignoresNonLiveStatusesLikeDraftOrCancelled() {
        long ticketId = newBareTicket();
        insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "DRAFT", (short) 60);
        insertOriginTaggedQuotation(ticketId, "DEAL_DIRECT", "CANCELLED", (short) 70);

        assertThat(depositNotices.findGoverningQuotation(ticketId)).isEmpty();
    }

    @Test
    void noQuotationAtAllYieldsEmpty() {
        long ticketId = newBareTicket();

        assertThat(depositNotices.findGoverningQuotation(ticketId)).isEmpty();
    }

    private void assertGoverning(long ticketId, long quotationId, boolean legacy, String depositPercent) {
        DepositNoticeRepository.GoverningQuotation governing =
            depositNotices.findGoverningQuotation(ticketId).orElseThrow();
        assertThat(governing.quotationId()).isEqualTo(quotationId);
        assertThat(governing.legacy()).isEqualTo(legacy);
        assertThat(governing.depositPercent()).isEqualByComparingTo(depositPercent);
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
    private long insertLegacyQuotation(long ticketId, String docStatus, Short depositPercent) {
        long pricingRequestId = pricingRequests.findByTicket(ticketId).get(0).id();
        int version = ++quotationVersionSeq;
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, doc_status, quotation_version,
                 pricing_request_id, origin, deposit_percent)
            VALUES
                (:ticketId, :number, :issuedBy, :docStatus, :version,
                 :pricingRequestId, NULL, :depositPercent)
            RETURNING quotation_id
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
                .addValue("depositPercent", depositPercent), Long.class);
    }

    /** V2 deal-quotation row: {@code origin IN ('PRICING_REQUEST','DEAL_DIRECT')} — no {@code
     * pricing_request_id} needed (nullable; the v2 engine's own scope is {@code origin} alone). */
    private long insertOriginTaggedQuotation(long ticketId, String origin, String docStatus, Short depositPercent) {
        int version = ++quotationVersionSeq;
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, doc_status, quotation_version, origin, deposit_percent)
            VALUES
                (:ticketId, :number, :issuedBy, :docStatus, :version, :origin, :depositPercent)
            RETURNING quotation_id
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QTD" + ticketId + "-" + version)
                .addValue("issuedBy", employeeId)
                .addValue("docStatus", docStatus)
                .addValue("version", version)
                .addValue("origin", origin)
                .addValue("depositPercent", depositPercent), Long.class);
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
