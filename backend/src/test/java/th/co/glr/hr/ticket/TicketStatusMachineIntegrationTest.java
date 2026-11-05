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
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
import th.co.glr.hr.deposit.RevisionRequest;
import th.co.glr.hr.deposit.RevisionScope;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;

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
    private RecordingSalesMailer mailer;
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
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // Mocked exactly as DealTrackingAndActivityIntegrationTest mocks it, and for the same
        // reason: only cancel() reaches PricingRequestService.cancelOpenForTicket, whose real
        // return value is irrelevant here but whose Mockito default (null) would NPE inside
        // cancel().
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            notifications, new DepositNoticePdfRenderer(), customers,
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
        // H1 lockdown: account confirms a close only on a deal inside its list scope (live, S10+).
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", ticketId));

        ticketService.confirmCloseReady(ticketId, accountActor);
        assertThat(statusOf(ticketId)).as("the account confirmation must not move the status")
            .isEqualTo(TicketStatus.DOCUMENT_ISSUED);

        assertThat(ticketService.verifyClose(ticketId, ceo).summary().status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.CLOSED);
        assertThat(closedAt(ticketId)).isNotNull();
    }

    /**
     * P1 hand-off notification (A6): account's close-ready confirmation tells the CEO to verify.
     * Pins the bell row AND the email path. The fixture owes 2,140 (2 x 1,000 + 7% VAT) and has been
     * paid in full, so {@code requireClosePrerequisites} passes with a real, non-zero payable.
     */
    @Test
    void confirmCloseReady_notifiesTheCeoWithTheDealTotal() {
        long ceoId = mailer.seedRoleEmployee("ceo");
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id", Map.of("id", ticketId));
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, qty, unit, sort_order, approved_price)
            VALUES (:t, 'Brand', 'Model', 2, 'pcs', 0, 1000)
            """, Map.of("t", ticketId));
        tickets.insertPaymentReceipt(ticketId, "BALANCE", new BigDecimal("2140.00"), salesRepId, null, null, null, null);
        TicketSummaryDto before = tickets.findById(ticketId).orElseThrow().summary();
        assertThat(before.amountPayable()).as("fixture payable").isEqualByComparingTo("2140.00");
        mailer.assertNoNotice("CLOSE_READY_FOR_CEO", "ดีลพร้อมปิด — รอ CEO ตรวจสอบ");

        ticketService.confirmCloseReady(ticketId, accountActor);

        mailer.assertRoleNotice("ceo", "CLOSE_READY_FOR_CEO", "ดีลพร้อมปิด — รอ CEO ตรวจสอบ",
            "ฝ่ายบัญชี (" + accountActor.name() + ") ยืนยันว่าดีลนี้รับเงินครบและพร้อมปิดแล้ว\n"
                + "ดีล " + before.code() + " · ลูกค้า " + before.customerName() + " · ยอดรวม 2,140 บาท"
                + "\nสิ่งที่ต้องทำต่อ: ตรวจสอบและยืนยันปิดดีล",
            "/tickets/" + ticketId, ceoId);
    }

    /** A confirmation refused for an unmet prerequisite (balance still owed, 409) tells the CEO nothing. */
    @Test
    void confirmCloseReady_refusedWhileTheBalanceIsOwed_notifiesNobody() {
        mailer.seedRoleEmployee("ceo");
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id", Map.of("id", ticketId));
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, qty, unit, sort_order, approved_price)
            VALUES (:t, 'Brand', 'Model', 2, 'pcs', 0, 1000)
            """, Map.of("t", ticketId)); // owed, nothing paid

        assertThatThrownBy(() -> ticketService.confirmCloseReady(ticketId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(409);
                assertThat(e.getMessage()).contains("ยังมียอดค้างชำระ");
            });

        mailer.assertNoNotice("CLOSE_READY_FOR_CEO", "ดีลพร้อมปิด — รอ CEO ตรวจสอบ");
    }

    // ── P2 B2: close (CEO verifies) and revoking the account's close confirmation ──────────────

    private static final String DEAL_CLOSED_TITLE = "ดีลปิดเรียบร้อยแล้ว";
    private static final String REVOKED_TITLE = "ถอนการยืนยันพร้อมปิดดีลแล้ว";
    private static final String FYI = "แจ้งเพื่อทราบ ไม่ต้องดำเนินการ";

    private String label(long ticketId) {
        TicketSummaryDto s = tickets.findById(ticketId).orElseThrow().summary();
        return "ดีล " + s.code() + " · ลูกค้า " + s.customerName();
    }

    /** A deal account has confirmed ready-to-close (the legacy document_issued route), waiting on the CEO. */
    private long dealAwaitingCeoVerification() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id", Map.of("id", ticketId));
        ticketService.confirmCloseReady(ticketId, accountActor);
        return ticketId;
    }

    /** The CEO's verification closes the deal: the rep and account hear it (FYI); the CEO who did it does not. */
    @Test
    void verifyClose_notifiesTheRepAndAccount_notTheCeoWhoClosedIt() {
        long accountId = mailer.seedRoleEmployee("account");
        long ceoId = mailer.seedRoleEmployee("ceo");
        long ticketId = dealAwaitingCeoVerification();

        ticketService.verifyClose(ticketId, ceo);

        String message = "CEO (" + ceo.name() + ") ตรวจสอบและปิดดีลเรียบร้อยแล้ว\n" + label(ticketId) + "\n" + FYI;
        mailer.assertNoticeFor(salesRepId, "DEAL_CLOSED", DEAL_CLOSED_TITLE, message, "/tickets/" + ticketId);
        mailer.assertNoticeFor(accountId, "DEAL_CLOSED", DEAL_CLOSED_TITLE, message, "/finance/deals/" + ticketId);
        mailer.assertBellRecipients("DEAL_CLOSED", salesRepId, accountId); // not ceoId
        assertThat(ceoId).as("fixture: a CEO exists to be wrongly notified").isNotEqualTo(salesRepId);
    }

    /** verifyClose refused (account never confirmed) closes nothing and tells nobody. */
    @Test
    void verifyClose_refusedWithoutTheAccountConfirmation_notifiesNobody() {
        mailer.seedRoleEmployee("account");
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);

        assertThatThrownBy(() -> ticketService.verifyClose(ticketId, ceo))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        mailer.assertNoNotice("DEAL_CLOSED", DEAL_CLOSED_TITLE);
    }

    /** Account withdraws its confirmation: the CEO's "รอ CEO ตรวจสอบ" bell is stale, so the CEO (only) is told, with the reason. */
    @Test
    void financeRevokeCloseConfirmation_byAccount_tellsTheCeoWithTheReason_notAccount() {
        long ceoId = mailer.seedRoleEmployee("ceo");
        mailer.seedRoleEmployee("account");
        long ticketId = dealAwaitingCeoVerification();

        ticketService.financeRevokeCloseConfirmation(ticketId, "ยอดเงินไม่ตรง", accountActor);

        mailer.assertRoleNotice("ceo", "CLOSE_CONFIRMATION_REVOKED", REVOKED_TITLE,
            "ฝ่ายบัญชี (" + accountActor.name() + ") ถอนการยืนยันพร้อมปิดดีลแล้ว — ยอดเงินไม่ตรง\n" + label(ticketId)
                + "\nแจ้งเพื่อทราบ ยังไม่ต้องตรวจสอบปิดดีล — รอฝ่ายบัญชียืนยันใหม่",
            "/tickets/" + ticketId, ceoId);
    }

    /** The CEO withdraws it (finance entrypoint, no note): account is told to re-confirm when ready. */
    @Test
    void financeRevokeCloseConfirmation_byCeo_tellsAccountToReconfirm_notTheCeo() {
        long accountId = mailer.seedRoleEmployee("account");
        mailer.seedRoleEmployee("ceo");
        long ticketId = dealAwaitingCeoVerification();

        ticketService.financeRevokeCloseConfirmation(ticketId, null, ceo);

        mailer.assertRoleNotice("account", "CLOSE_CONFIRMATION_REVOKED", REVOKED_TITLE,
            "CEO (" + ceo.name() + ") ถอนการยืนยันพร้อมปิดดีลแล้ว\n" + label(ticketId)
                + "\nสิ่งที่ต้องทำต่อ: ตรวจสอบเหตุผล แล้วยืนยันพร้อมปิดดีลใหม่เมื่อพร้อม",
            "/finance/deals/" + ticketId, accountId);
    }

    /** The /api/tickets entrypoint (ceo only) revokes through the same logic: same notice to account, reason included. */
    @Test
    void revokeCloseConfirmation_ceoEntrypoint_tellsAccount() {
        long accountId = mailer.seedRoleEmployee("account");
        long ticketId = dealAwaitingCeoVerification();

        ticketService.revokeCloseConfirmation(ticketId, "รอใบกำกับภาษีฉบับใหม่", ceo);

        mailer.assertRoleNotice("account", "CLOSE_CONFIRMATION_REVOKED", REVOKED_TITLE,
            "CEO (" + ceo.name() + ") ถอนการยืนยันพร้อมปิดดีลแล้ว — รอใบกำกับภาษีฉบับใหม่\n" + label(ticketId)
                + "\nสิ่งที่ต้องทำต่อ: ตรวจสอบเหตุผล แล้วยืนยันพร้อมปิดดีลใหม่เมื่อพร้อม",
            "/finance/deals/" + ticketId, accountId);
    }

    /** Nothing was confirmed, so there is nothing to revoke (409): nobody is told. */
    @Test
    void revokeCloseConfirmation_whenNothingWasConfirmed_notifiesNobody() {
        mailer.seedRoleEmployee("account");
        mailer.seedRoleEmployee("ceo");
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);

        assertThatThrownBy(() -> ticketService.revokeCloseConfirmation(ticketId, null, ceo))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        mailer.assertNoNotice("CLOSE_CONFIRMATION_REVOKED", REVOKED_TITLE);
    }

    // ── P2 B5: lost / cancelled / on hold / dormant / resumed / reopened ──────────────────────────

    private static final String IMPORT_STOP = "สิ่งที่ต้องทำต่อ: หากสั่งซื้อกับโรงงานไปแล้ว กรุณาแจ้งโรงงานให้หยุดหรือยกเลิกคำสั่งซื้อ";
    private static final String ACCOUNT_REFUND = "สิ่งที่ต้องทำต่อ: ตรวจสอบเงินมัดจำที่รับไว้ และแจ้งผู้เกี่ยวข้องหากต้องคืนเงินลูกค้า";
    private static final String IMPORT_PAUSE = "สิ่งที่ต้องทำต่อ: หากสั่งซื้อกับโรงงานไปแล้ว กรุณาแจ้งโรงงานว่าดีลถูกพักไว้";
    private static final String ACCOUNT_WAIT = "สิ่งที่ต้องทำต่อ: ตรวจสอบเงินมัดจำที่รับไว้ แล้วรอจนกว่าดีลจะกลับมาดำเนินการ";

    private record LifecycleRoles(long sm, long ceo, long importId, long account) {}

    private LifecycleRoles seedLifecycleRoles() {
        return new LifecycleRoles(mailer.seedRoleEmployee("sales_manager"), mailer.seedRoleEmployee("ceo"),
            mailer.seedRoleEmployee("import"), mailer.seedRoleEmployee("account"));
    }

    /** An ISSUED ใบขอซื้อ (IR) row for the deal — the "import has work in flight" signal. */
    private void seedIssuedImportRequest(long ticketId) {
        long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES ('Factory Lifecycle', 'IT', 'EUR') RETURNING factory_id
            """, Map.of(), Long.class);
        jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, factory_id, factory_name, brand, version, status, doc_number, issue_date,
                 import_step, import_step_at, first_issued_date)
            VALUES (:t, :f, 'Factory Lifecycle', 'Brand', 1, 'ISSUED', 'IR-LIFE-1', CURRENT_DATE,
                    'CONTACTED', CURRENT_DATE, CURRENT_DATE)
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("f", factoryId));
    }

    /** An ISSUED ใบแจ้งยอดเงินรับมัดจำ for the deal — the "account has a deposit to look after" signal. */
    private void seedIssuedDepositNotice(long ticketId) {
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, 1, 'ISSUED', 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, 32100.00)
            """, Map.of("id", ticketId));
    }

    /**
     * SM and CEO always hear it (FYI, /tickets/{id}); import only with an IR (its own deal page + a
     * "tell the factory" line); account only with a deposit notice (its finance page). Nobody else.
     */
    private void assertLifecycleNotices(long ticketId, String type, String title, String line1,
                                        LifecycleRoles r, String importLine3, String accountLine3,
                                        boolean toImport, boolean toAccount) {
        String head = line1 + "\n" + label(ticketId) + "\n";
        mailer.assertNoticeFor(r.sm(), type, title, head + FYI, "/tickets/" + ticketId);
        mailer.assertNoticeFor(r.ceo(), type, title, head + FYI, "/tickets/" + ticketId);
        java.util.List<Long> expected = new java.util.ArrayList<>(java.util.List.of(r.sm(), r.ceo()));
        if (toImport) {
            mailer.assertNoticeFor(r.importId(), type, title, head + importLine3, "/import/deals/" + ticketId);
            expected.add(r.importId());
        }
        if (toAccount) {
            mailer.assertNoticeFor(r.account(), type, title, head + accountLine3, "/finance/deals/" + ticketId);
            expected.add(r.account());
        }
        mailer.assertBellRecipients(type, expected.stream().mapToLong(Long::longValue).toArray());
    }

    /** Plain deal, no IR, no deposit notice: only the sales manager and the CEO are told; the rep who did it is not. */
    @Test
    void markLost_withNoIrAndNoDepositNotice_tellsOnlySalesManagerAndCeo() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();

        ticketService.markLost(ticketId, DealLostReason.PRICE, "แพ้ราคาคู่แข่ง", salesRep);

        assertLifecycleNotices(ticketId, "DEAL_LOST", "ดีลเสียงาน",
            salesRep.name() + " บันทึกว่าดีลเสียงาน (ราคา) — แพ้ราคาคู่แข่ง", r, IMPORT_STOP, ACCOUNT_REFUND, false, false);
    }

    /** A sales manager who marks a deal lost is not told about it themselves; the OTHER sales manager and the CEO are. */
    @Test
    void markLost_bySalesManager_doesNotNotifyThatSalesManager() {
        LifecycleRoles r = seedLifecycleRoles();
        long otherSm = mailer.seedRoleEmployee("sales_manager");
        UserPrincipal smActor = principal(r.sm(), "sales_manager");
        long ticketId = createTicket();

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, smActor);

        String head = smActor.name() + " บันทึกว่าดีลเสียงาน (ราคา)\n" + label(ticketId) + "\n" + FYI;
        mailer.assertNoticeFor(otherSm, "DEAL_LOST", "ดีลเสียงาน", head, "/tickets/" + ticketId);
        mailer.assertNoticeFor(r.ceo(), "DEAL_LOST", "ดีลเสียงาน", head, "/tickets/" + ticketId);
        // R2: somebody other than the deal's rep did it, so the rep hears it too (once, as an employee notice).
        mailer.assertNoticeFor(salesRepId, "DEAL_LOST", "ดีลเสียงาน", head, "/tickets/" + ticketId);
        mailer.assertBellRecipients("DEAL_LOST", otherSm, r.ceo(), salesRepId); // not r.sm()
    }

    /** R2 for every lifecycle change a non-rep can make: the rep is told each time, same type, FYI, link /tickets/{id}. */
    @Test
    void lifecycleChangesByAnotherUser_tellTheRepOfEachOne() {
        seedLifecycleRoles();
        long ticketId = createTicket();
        String link = "/tickets/" + ticketId;
        String tail = "\n" + label(ticketId) + "\n" + FYI;

        ticketService.placeOnHold(ticketId, null, ceo);
        mailer.assertNoticeFor(salesRepId, "DEAL_ON_HOLD", "ดีลถูกพักไว้ชั่วคราว", ceo.name() + " พักดีลไว้ชั่วคราว" + tail, link);
        ticketService.resume(ticketId, null, ceo);
        mailer.assertNoticeFor(salesRepId, "DEAL_RESUMED", "ดีลกลับมาดำเนินการต่อแล้ว", ceo.name() + " ให้ดีลกลับมาดำเนินการต่อ" + tail, link);
        ticketService.markDormant(ticketId, null, ceo);
        mailer.assertNoticeFor(salesRepId, "DEAL_DORMANT", "ดีลถูกพักไว้ระยะยาว", ceo.name() + " พักดีลไว้ระยะยาว (dormant)" + tail, link);
        ticketService.resume(ticketId, null, ceo);
        ticketService.markLost(ticketId, DealLostReason.PRICE, null, ceo);
        mailer.assertNoticeFor(salesRepId, "DEAL_LOST", "ดีลเสียงาน", ceo.name() + " บันทึกว่าดีลเสียงาน (ราคา)" + tail, link);
        ticketService.reopenDeal(ticketId, null, ceo);
        mailer.assertNoticeFor(salesRepId, "DEAL_REOPENED", "ดีลที่เสียงานถูกเปิดกลับมาอีกครั้ง", ceo.name() + " เปิดดีลที่เสียงานกลับมาอีกครั้ง" + tail, link);
    }

    /** An ISSUED IR puts import in the loop (to stop the factory order) but not account. */
    @Test
    void markLost_withAnIssuedImportRequest_alsoTellsImport_notAccount() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesRep);

        assertLifecycleNotices(ticketId, "DEAL_LOST", "ดีลเสียงาน",
            salesRep.name() + " บันทึกว่าดีลเสียงาน (ราคา)", r, IMPORT_STOP, ACCOUNT_REFUND, true, false);
    }

    /** An ISSUED deposit notice puts account in the loop (deposit/refund check) but not import. */
    @Test
    void markLost_withAnIssuedDepositNotice_alsoTellsAccount_notImport() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedDepositNotice(ticketId);

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesRep);

        assertLifecycleNotices(ticketId, "DEAL_LOST", "ดีลเสียงาน",
            salesRep.name() + " บันทึกว่าดีลเสียงาน (ราคา)", r, IMPORT_STOP, ACCOUNT_REFUND, false, true);
    }

    @Test
    void cancel_withAnIrAndADepositNotice_tellsSalesManagerCeoImportAndAccount() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);
        seedIssuedDepositNotice(ticketId);

        ticketService.cancel(ticketId, DealCancelReason.OWNER_CANCELLED, null, salesRep);

        assertLifecycleNotices(ticketId, "DEAL_CANCELLED", "ดีลถูกยกเลิก",
            salesRep.name() + " ยกเลิกดีล (เจ้าของยกเลิกโครงการ)", r, IMPORT_STOP, ACCOUNT_REFUND, true, true);
    }

    @Test
    void placeOnHold_withAnIrAndADepositNotice_tellsEveryoneWithAPause_andTheReason() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);
        seedIssuedDepositNotice(ticketId);

        ticketService.placeOnHold(ticketId, "รอเจ้าของโครงการ", salesRep);

        assertLifecycleNotices(ticketId, "DEAL_ON_HOLD", "ดีลถูกพักไว้ชั่วคราว",
            salesRep.name() + " พักดีลไว้ชั่วคราว — รอเจ้าของโครงการ", r, IMPORT_PAUSE, ACCOUNT_WAIT, true, true);
    }

    @Test
    void markDormant_withAnIrAndADepositNotice_tellsEveryoneWithAPause() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);
        seedIssuedDepositNotice(ticketId);

        ticketService.markDormant(ticketId, null, salesRep);

        assertLifecycleNotices(ticketId, "DEAL_DORMANT", "ดีลถูกพักไว้ระยะยาว",
            salesRep.name() + " พักดีลไว้ระยะยาว (dormant)", r, IMPORT_PAUSE, ACCOUNT_WAIT, true, true);
    }

    /** Resuming is good news for everyone who was told to pause: FYI to all, no action. */
    @Test
    void resume_withAnIrAndADepositNotice_tellsEveryoneFyi() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);
        seedIssuedDepositNotice(ticketId);
        ticketService.placeOnHold(ticketId, null, salesRep);

        ticketService.resume(ticketId, null, salesRep);

        assertLifecycleNotices(ticketId, "DEAL_RESUMED", "ดีลกลับมาดำเนินการต่อแล้ว",
            salesRep.name() + " ให้ดีลกลับมาดำเนินการต่อ", r, FYI, FYI, true, true);
    }

    @Test
    void reopenDeal_withAnIrAndADepositNotice_tellsEveryoneFyi() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);
        seedIssuedDepositNotice(ticketId);
        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesRep);

        ticketService.reopenDeal(ticketId, "ลูกค้ากลับมาคุย", salesRep);

        assertLifecycleNotices(ticketId, "DEAL_REOPENED", "ดีลที่เสียงานถูกเปิดกลับมาอีกครั้ง",
            salesRep.name() + " เปิดดีลที่เสียงานกลับมาอีกครั้ง — ลูกค้ากลับมาคุย", r, FYI, FYI, true, true);
    }

    /** A refused lifecycle action (reopening a deal that was never lost, 409) tells nobody. */
    @Test
    void reopenDeal_onADealThatWasNeverLost_notifiesNobody() {
        seedLifecycleRoles();
        long ticketId = createTicket();

        assertThatThrownBy(() -> ticketService.reopenDeal(ticketId, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        mailer.assertNoNotice("DEAL_REOPENED", "ดีลที่เสียงานถูกเปิดกลับมาอีกครั้ง");
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
