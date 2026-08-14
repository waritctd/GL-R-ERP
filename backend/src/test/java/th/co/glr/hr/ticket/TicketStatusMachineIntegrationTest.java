package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.deposit.DepositNoticeRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceRenderer;
import th.co.glr.hr.deposit.RevisionRequest;
import th.co.glr.hr.deposit.RevisionScope;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres proof for the {@code sales.ticket.status} state machine:
 * {@link TicketStatus#canTransition} + {@link TicketRepository#transitionStatus} + every production
 * write site that moves the column. {@link TicketStatusTest} covers the table in isolation; this
 * class covers the two things a pure function cannot show —
 *
 * <ol>
 *   <li>the compare-and-set really is in the {@code WHERE} clause, so a stale {@code expected}
 *       leaves the row alone instead of overwriting it (Mockito cannot reach this: a mocked
 *       repository happily returns whatever it is told while the SQL does something else); and
 *   <li>the SERVICE composition still reaches the same status each site reached before the write
 *       was separated out of {@code addEvent} — the behaviour-preservation claim, one test per
 *       site, driven through the real service.
 * </ol>
 *
 * <p><strong>Fixture note.</strong> Several tests seed a ticket at a given status with a direct
 * {@code UPDATE}. That is deliberate and is the same two-tier approach
 * {@code PaymentTrackIntegrationTest} documents: the fact under test is the transition out of that
 * state, not how the deal arrived there, and most of these statuses are now reachable only through
 * the legacy pre-PricingRequest loop (ticket-level {@code submit()} 409s by design since the
 * pricing-chain redesign), so there is no live path that could build the fixture instead.
 *
 * <p><strong>No rollback is asserted anywhere.</strong> {@code AbstractPostgresIntegrationTest} has
 * no Spring context and every service here is hand-wired with {@code new}, so {@code @Transactional}
 * is inert — an assertion that depended on a rollback would be asserting nothing.
 */
class TicketStatusMachineIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;

    private long salesRepId;
    private UserPrincipal salesRep;
    private UserPrincipal importActor;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // Mocked exactly as DealTrackingAndActivityIntegrationTest mocks them, and for the same
        // reason: none of the status transitions under test call PriceCalcService, and only
        // cancel() reaches PricingRequestService.cancelOpenForTicket, whose real return value is
        // irrelevant here but whose Mockito default (null) would NPE inside cancel().
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            notifications, new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers,
            new CustomerQuotationRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = createEmployee(employees, "พนักงานขาย สถานะ", "sales-status-machine@glr.co.th");
        salesRep = principal(salesRepId, "sales");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า สถานะ", "import-status-machine@glr.co.th"), "import");
        ceo = principal(createEmployee(employees, "ซีอีโอ สถานะ", "ceo-status-machine@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี สถานะ", "account-status-machine@glr.co.th"), "account");
    }

    // ── the guard itself, at the repository ──────────────────────────────────

    /**
     * Wrong-way-round #1: an edge the machine does not declare is refused BEFORE any SQL runs, and
     * the row is untouched. {@code draft -> closed} was a perfectly happy write before this change
     * — {@code isValid("closed")} is true, and nothing looked at the current state at all.
     */
    @Test
    void anUndeclaredEdgeThrowsAndLeavesTheRowAlone() {
        long ticketId = createTicket();
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.DRAFT);

        assertThatThrownBy(() -> tickets.transitionStatus(ticketId, TicketStatus.DRAFT, TicketStatus.CLOSED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft -> closed");

        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.DRAFT);
        assertThat(closedAt(ticketId)).isNull();
    }

    /**
     * Wrong-way-round #2, and the one that only a real database can show: the edge is DECLARED
     * ({@code price_proposed -> cancelled} is a legal cancel), but the row is not at
     * {@code price_proposed}. The compare-and-set must match nothing and report 0 rows — the old
     * unguarded UPDATE had no {@code AND status = :expected} at all and would have cancelled a
     * deal that was sitting in {@code in_review}.
     */
    @Test
    void aStaleExpectedValueUpdatesZeroRowsRatherThanOverwriting() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.IN_REVIEW);

        int rows = tickets.transitionStatus(ticketId, TicketStatus.PRICE_PROPOSED, TicketStatus.CANCELLED);

        assertThat(rows).isZero();
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.IN_REVIEW);
    }

    @Test
    void aDeclaredEdgeOnAMatchingRowMovesItAndReportsOneRow() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.SUBMITTED);

        assertThat(tickets.transitionStatus(ticketId, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW))
            .isEqualTo(1);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.IN_REVIEW);
    }

    /**
     * The OrderConfirmationService bridge, folded onto {@code transitionStatus} but keeping its
     * observable contract: hardcoded FROM {@code draft}, so a non-draft row yields 0 rows (which
     * the service turns into a 409) and never an {@code IllegalStateException} — the edge check is
     * a constant that always passes.
     */
    @Test
    void theOrderConfirmationBridgeStillRefusesANonDraftRowWithZeroRowsNotAThrow() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.APPROVED);

        assertThat(tickets.markQuotationIssuedForOrderConfirmation(ticketId)).isZero();
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.APPROVED);

        long draftTicket = createTicket();
        assertThat(tickets.markQuotationIssuedForOrderConfirmation(draftTicket)).isEqualTo(1);
        assertThat(statusOf(draftTicket)).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    // ── behaviour preservation, one test per production write site ───────────

    /** Site 1 — {@code TicketRepository.create}: the INSERT's initial state, not an edge. */
    @Test
    void creation_stillLeavesTheDealAtDraftEvenThoughTheCreatedEventNoLongerWritesIt() {
        long ticketId = createTicket();

        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.DRAFT);
        assertThat(eventCount(ticketId, TicketEventKind.CREATED)).isEqualTo(1);
    }

    /** Site 2 — {@code TicketService.pickup}: submitted -> in_review. */
    @Test
    void pickup_stillReachesInReview() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.SUBMITTED);

        assertThat(ticketService.pickup(ticketId, importActor).summary().status())
            .isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.IN_REVIEW);
    }

    /** Site 3 — {@code TicketService.proposePrice}: all three admitted states -> price_proposed. */
    @Test
    void proposePrice_stillReachesPriceProposedFromEveryAdmittedState() {
        for (String from : List.of(TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED)) {
            long ticketId = createTicket();
            seedStatus(ticketId, from);

            ticketService.proposePrice(ticketId, new ProposePriceRequest(List.of(sampleItem()), "ราคา"), importActor);

            assertThat(statusOf(ticketId)).as("proposePrice from %s", from)
                .isEqualTo(TicketStatus.PRICE_PROPOSED);
        }
    }

    /** Sites 4 and 5 — {@code approve} / {@code reject}, both out of price_proposed. */
    @Test
    void approveAndReject_stillReachApprovedAndInReview() {
        long approved = createTicket();
        seedStatus(approved, TicketStatus.PRICE_PROPOSED);
        assertThat(ticketService.approve(approved, ceo).summary().status()).isEqualTo(TicketStatus.APPROVED);
        assertThat(statusOf(approved)).isEqualTo(TicketStatus.APPROVED);

        long rejected = createTicket();
        seedStatus(rejected, TicketStatus.PRICE_PROPOSED);
        assertThat(ticketService.reject(rejected, new RejectRequest("แพงไป"), ceo).summary().status())
            .isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(statusOf(rejected)).isEqualTo(TicketStatus.IN_REVIEW);
    }

    /** Site 6 — {@code generateQuotation}, including the amended re-issue self-edge. */
    @Test
    void generateQuotation_stillReachesQuotationIssuedAndCanReIssue() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.APPROVED);

        ticketService.generateQuotation(ticketId, designerQuotation(), salesRep);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.QUOTATION_ISSUED);

        // approved -> quotation_issued, then the quotation_issued -> quotation_issued self-edge.
        ticketService.generateQuotation(ticketId, designerQuotation(), salesRep);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    /**
     * Site 7 — {@code verifyClose}, via the legacy {@code document_issued} route
     * ({@code requireClosePrerequisites}'s {@code legacyOk} branch: a pre-dual-track deal whose
     * payment track was never started). {@code closed_at} is still stamped, which is the side
     * effect that stayed behind in {@code addEvent} when the status write moved out.
     */
    @Test
    void verifyClose_stillReachesClosedAndStillStampsClosedAt() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);

        ticketService.confirmCloseReady(ticketId, accountActor);
        assertThat(statusOf(ticketId)).as("the account confirmation must not move the status")
            .isEqualTo(TicketStatus.DOCUMENT_ISSUED);

        assertThat(ticketService.verifyClose(ticketId, ceo).summary().status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.CLOSED);
        assertThat(closedAt(ticketId)).isNotNull();
    }

    /** Site 8 — {@code cancel}, reachable from every non-terminal status. */
    @Test
    void cancel_stillReachesCancelledFromEveryNonTerminalStatus() {
        for (String from : TicketStatus.VALUES) {
            if (TicketStatus.CLOSED.equals(from) || TicketStatus.CANCELLED.equals(from)) {
                continue;
            }
            long ticketId = createTicket();
            seedStatus(ticketId, from);

            ticketService.cancel(ticketId, DealCancelReason.OTHER, "ทดสอบ", salesRep);

            assertThat(statusOf(ticketId)).as("cancel from %s", from).isEqualTo(TicketStatus.CANCELLED);
            assertThat(closedAt(ticketId)).as("cancel from %s still stamps closed_at", from).isNotNull();
        }
    }

    /** …and a terminal deal is still refused by the service's own pre-guard, not by the machine. */
    @Test
    void cancel_onAnAlreadyClosedDeal_is409AndTheRowStaysClosed() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.CLOSED);

        assertThatThrownBy(() -> ticketService.cancel(ticketId, DealCancelReason.OTHER, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.CLOSED);
    }

    /**
     * Site 9 — {@code DepositNoticeService.requestRevision}, the deliberate BACKWARD moves. This is
     * the site the status write had to be re-attached to by hand: it is the only one where the
     * event's {@code toStatus} was computed rather than constant, so without an explicit
     * {@code transitionStatus} call the revision would have logged its event and left the deal
     * exactly where it was.
     */
    @Test
    void requestRevision_stillMovesTheDealBackwardsAccordingToScope() {
        for (String from : List.of(TicketStatus.APPROVED, TicketStatus.DOCUMENT_ISSUED)) {
            assertRevisionLands(from, RevisionScope.QTY_OR_NOTE, TicketStatus.APPROVED);
            assertRevisionLands(from, RevisionScope.PRICE_CHANGE, TicketStatus.PRICE_PROPOSED);
            assertRevisionLands(from, RevisionScope.NEW_ITEM, TicketStatus.IN_REVIEW);
        }
    }

    private void assertRevisionLands(String from, RevisionScope scope, String expected) {
        long ticketId = createTicket();
        seedStatus(ticketId, from);

        depositNoticeService.requestRevision(ticketId, new RevisionRequest(scope, "เหตุผล"), salesRep);

        assertThat(statusOf(ticketId)).as("%s + %s", from, scope).isEqualTo(expected);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long createTicket() {
        return tickets.create(
            new CreateTicketRequest("ดีลทดสอบสถานะ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย สถานะ");
    }

    /**
     * Seeds the row at a status directly. See the class Javadoc: the fact under test is always the
     * transition OUT of this state, and most of these states are no longer reachable through a live
     * code path at all.
     */
    private void seedStatus(long ticketId, String status) {
        jdbc.update("UPDATE sales.ticket SET status = :status WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("status", status).addValue("id", ticketId));
    }

    private String statusOf(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private java.sql.Timestamp closedAt(long ticketId) {
        return jdbc.queryForObject("SELECT closed_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), java.sql.Timestamp.class);
    }

    private long eventCount(long ticketId, String kind) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            new MapSqlParameterSource().addValue("id", ticketId).addValue("kind", kind), Long.class);
    }

    private static TicketItemRequest sampleItem() {
        return new TicketItemRequest("Padana", "รุ่นทดสอบ", null, null, "60x60", "PAD",
            new BigDecimal("10"), null, "PIECE", new BigDecimal("100"), "EUR", "SQM",
            new BigDecimal("500"), "THB");
    }

    private static GenerateQuotationRequest designerQuotation() {
        return new GenerateQuotationRequest(QuotationRecipient.DESIGNER,
            null, null, null, null, null, null, null, null, null);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-status@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
