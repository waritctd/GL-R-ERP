package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.RecordPaymentRequest;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketResponses;
import th.co.glr.hr.ticket.TicketService;

/**
 * Against real Postgres, through the real services: what a deal must have before an import request
 * (ใบขอซื้อ) may be ISSUED — the rules the owner confirmed on 2026-10-05 (C1–C3).
 *
 * <p><b>The rules.</b> The deal's ACCEPTED QUOTATION decides whether it has a deposit: its {@code
 * deposit_percent} above 0 means the deal must collect one, and 0 means there is NO deposit step at
 * all. There is no waiver, and no deal-level switch opens anything. A deposit is confirmed in exactly
 * one way — ฝ่ายบัญชี's {@code confirmDepositPaid} (ยืนยันรับมัดจำ); a receipt recorded through the
 * generic {@code recordPayment} is not a confirmation, whatever its amount. So on a DEPOSIT deal the
 * import request may be issued only once that confirmation is in ({@code DEPOSIT_PAID} or later) — a
 * deposit NOTICE ({@code DEPOSIT_NOTICE_ISSUED}) is not enough, and neither is the customer's
 * confirmation — and on a 0% deal right after the order is confirmed ({@code CUSTOMER_CONFIRMED}).
 * Preparing the DRAFT stays allowed from ขั้น 10 in both cases: the rules gate ISSUING, not drafting.
 * They hold on BOTH routes (the stored per-factory {@code ImportRequestService#issue} and the legacy
 * one-click {@code TicketService#issueImportRequest}), and for whether {@code ISSUE_IMPORT_REQUEST}
 * is advertised, which is decided by the same predicate ({@code
 * TicketService#requireImportRequestIssuable}).
 *
 * <p><b>How a test says "deposit deal" and "0% deal".</b> {@link #insertTicket}: the accepted
 * quotation row, with its {@code deposit_percent}, is written BEFORE the customer's order is
 * confirmed, and the order is then confirmed through the real {@code TicketService#confirmCustomer} —
 * so the fixture is right whether the gate reads the quotation when it runs or derives something when
 * the order is confirmed. {@code deposit_policy} (the old switch) is never written. A null payment
 * status is a deal whose order is not confirmed yet; payment states past the confirmation
 * ({@code DEPOSIT_NOTICE_ISSUED}, {@code DEPOSIT_PAID}, ...) are stamped afterwards.
 *
 * <p>REVIEW ROUND 2, S-C — what this class was first written for, and still true: the STORED
 * per-factory {@code issue()} path accepts any payment status AT OR AFTER the deposit is confirmed
 * ({@code AWAITING_FINAL_PAYMENT}/{@code FULLY_PAID} too, in addition to {@code DEPOSIT_PAID}), so a
 * deal's second/third factory's FIRST issue, or ANY factory's REVISION issue, is not blocked just
 * because the customer finished paying the rest of the deal in the meantime — see {@code
 * TicketService#requireImportRequestIssuable}'s {@code allowAdvancedPayment} overload. A deal with
 * nothing confirmed yet must still be refused, on both paths — this is a WIDENING of what counts as
 * "ready", never a way around the floor itself.
 *
 * <p><b>Every refusal asserted below is a refusal BECAUSE OF THE DEPOSIT, and says so</b> (the one
 * exception is a 0% deal whose order is not confirmed: that refusal is about the order, and its
 * wording is not pinned). {@code ImportRequestService#issue} throws other 409s before it reaches the
 * gate (already issued, no items, no lead time) and the legacy route throws one for "already
 * issued", so a bare {@code CONFLICT} cannot tell a deposit refusal from those. {@link
 * #assertRefusedForTheDeposit} therefore also pins the message, and each refusal case re-reads the
 * rows to prove nothing was written.
 */
class ImportRequestPaymentGateIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Only the substring is pinned, so the sentence may be reworded without breaking the tests. */
    private static final String DEPOSIT_MESSAGE = "มัดจำ";

    private static final String PAYGATE_FACTORY = "Pay Gate Shared Factory";

    /** The action code {@code TicketService#actions} advertises for the legacy one-click import request. */
    private static final String ISSUE_IMPORT_REQUEST = "ISSUE_IMPORT_REQUEST";

    /** Whole percent the accepted quotation asks for: a DEPOSIT deal (the default deal of this class)... */
    private static final int DEPOSIT_DEAL = 50;
    /** ...and a 0% deal, which has no deposit step at all. */
    private static final int ZERO_PERCENT_DEAL = 0;

    private ImportRequestService service;
    private TicketService ticketService;
    private TicketRepository tickets;

    private long ownerId;
    private UserPrincipal owner;
    /** Role {@code import}: may run the legacy one-click action (FULFILMENT_ROLES = import/ceo). */
    private UserPrincipal importUser;
    /** Role {@code ceo}: import is refused the whole-deal view, so {@code actions} is read as ceo. */
    private UserPrincipal ceoUser;
    /** Role {@code account}: the only role that confirms a deposit, and the one that records receipts. */
    private UserPrincipal accountUser;
    private int codeSeq;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-payment-gate-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth);

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService);

        ownerId = insertEmployee("PAYGATE-OWN");
        owner = principal(ownerId, "sales");
        // Distinct codes from the "PAYGATE-IMP*" the older tests insert inline (employee_code is unique).
        importUser = principal(insertEmployee("PAYGATE-GATE-IMP"), "import");
        ceoUser = principal(insertEmployee("PAYGATE-GATE-CEO"), "ceo");
        accountUser = principal(insertEmployee("PAYGATE-GATE-ACC"), "account");
    }

    @Test
    void secondFactorysFirstIssue_isAllowed_afterTheDealHasAdvancedToFullyPaid() {
        long factoryA = insertFactory("Pay Gate Factory A");
        long factoryB = insertFactory("Pay Gate Factory B");
        long ticketId = insertTicket("GATE-FULLYPAID", "DEPOSIT_PAID");
        insertItem(ticketId, "Pay Gate Factory A", "Line A", "60x60", "10", "pcs", 0);
        insertItem(ticketId, "Pay Gate Factory B", "Line B", "60x60", "10", "pcs", 1);

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        long aId = byFactory(drafts, factoryA).id();
        long bId = byFactory(drafts, factoryB).id();
        assertThat(service.issue(aId, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);

        // The customer finishes paying the deal in full before factory B's own IR is ever touched —
        // the original (narrower) gate would have refused this; the widened one must not.
        setPaymentStatus(ticketId, "FULLY_PAID");

        assertThat(service.issue(bId, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    @Test
    void aRevisionsIssue_isAllowed_afterTheDealHasAdvancedToAwaitingFinalPayment() {
        insertFactory("Pay Gate Revise Factory");
        long ticketId = insertTicket("GATE-AWAITING", "DEPOSIT_PAID");
        insertItem(ticketId, "Pay Gate Revise Factory", "Line", "60x60", "10", "pcs", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        assertThat(service.issue(v1, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);

        setPaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");

        ImportRequestDto v2 = service.revise(v1, owner);
        assertThat(service.issue(v2.id(), null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    /**
     * The floor itself is UNCHANGED — S-C widens what counts as "ready", it never removes the
     * requirement that the deal has been confirmed at all. (A null payment status is a deal whose
     * order is not confirmed yet — see {@link #insertTicket}.)
     */
    @Test
    void issue_isStillRefused_onADealWithNothingConfirmedYet() {
        insertFactory("Pay Gate No Deposit Factory");
        long ticketId = insertTicket("GATE-NODEPOSIT", null);
        insertItem(ticketId, "Pay Gate No Deposit Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * Cheap nit (REVIEW ROUND 3): {@code CUSTOMER_CONFIRMED} on a DEPOSIT deal (the two-argument
     * {@code insertTicket}: a 50% quotation, the order really confirmed) is STILL refused even under
     * the WIDENED {@code allowAdvancedPayment} gate: it is neither one of the two original
     * deposit-confirmed statuses, nor is it one of the two statuses S-C added ({@code
     * AWAITING_FINAL_PAYMENT}/{@code FULLY_PAID}). Distinguishes "the floor moved" from "the floor
     * widened" — S-C must never be misread as accepting ANY non-null payment status.
     */
    @Test
    void issue_isStillRefused_onCustomerConfirmed_onADepositDeal() {
        insertFactory("Pay Gate Customer Confirmed Factory");
        long ticketId = insertTicket("GATE-CUSTCONF", "CUSTOMER_CONFIRMED");
        insertItem(ticketId, "Pay Gate Customer Confirmed Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The gate is "deposit received", NOT "quotation issued": a deal whose ticket never left
     * {@code draft} (the redesigned pricing chain does not always advance it) can still issue once
     * the deposit condition holds.
     */
    @Test
    void issue_isAllowed_onADraftTicket_whenTheDepositIsPaid() {
        insertFactory("Pay Gate Draft Factory");
        long ticketId = insertTicket("GATE-DRAFT-PAID", "DEPOSIT_PAID");
        setTicketStatus(ticketId, "draft");
        insertItem(ticketId, "Pay Gate Draft Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThat(service.issue(id, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    /** Same rule on the legacy deal-level action behind POST /api/tickets/{id}/import-request. */
    @Test
    void legacyIssueImportRequest_isAllowed_onADraftTicket_whenTheDepositIsPaid() {
        long ticketId = insertTicket("GATE-DRAFT-LEGACY", "DEPOSIT_PAID");
        setTicketStatus(ticketId, "draft");

        ticketService.issueImportRequest(ticketId, principal(insertEmployee("PAYGATE-IMP"), "import"));

        assertThat(jdbc.queryForObject(
            "SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo("IR_ISSUED");
    }

    /** Dropping the status requirement must not drop the deposit floor — wrong-way-round cases. */
    @Test
    void issue_isStillRefused_onADraftTicket_withNothingConfirmed_orOnlyCustomerConfirmed() {
        insertFactory("Pay Gate Draft Refused Factory");
        for (String paymentStatus : new String[] {null, "CUSTOMER_CONFIRMED"}) {
            long ticketId = insertTicket("GATE-DR-" + (paymentStatus == null ? "NULL" : "CC"), paymentStatus);
            setTicketStatus(ticketId, "draft");
            insertItem(ticketId, "Pay Gate Draft Refused Factory", "Line", "60x60", "10", "pcs", 0);
            long id = service.createDrafts(ticketId, null, owner).get(0).id();

            assertThatThrownBy(() -> service.issue(id, null, owner))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThatThrownBy(() -> ticketService.issueImportRequest(ticketId,
                principal(insertEmployee("PAYGATE-IMP-" + (paymentStatus == null ? "N" : "C")), "import")))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
    }

    // ── C3 (owner rules, 2026-10-05): the deposit must be CONFIRMED — a notice is not enough ───────
    //
    // The truth table walked below (fulfilment null and the deal ACTIVE throughout; "deposit" = the
    // accepted quotation asks 50%, "0%" = it asks 0%). Every row is pinned on BOTH routes and for the
    // advertisement (ISSUE_IMPORT_REQUEST); every refusal WITH the deposit reason and the
    // nothing-written contract:
    //   deposit, null                     refused, not advertised
    //   deposit, CUSTOMER_CONFIRMED       refused, not advertised
    //       -> issue_isRefusedForTheDeposit_beforeAnythingIsReceived_onBothRoutes_andNotAdvertised
    //   deposit, DEPOSIT_NOTICE_ISSUED    REFUSED, NOT advertised   <- RED today (a notice counts as ready)
    //       -> the notice-only pair, and issueImportRequestAction_isNotAdvertised_...
    //   deposit, DEPOSIT_PAID             allowed, advertised
    //       -> the deposit-paid pair, and the "contains" half of issueImportRequestAction_isNotAdvertised_...
    //   0%,      null                     refused (the order is not confirmed), not advertised
    //   0%,      CUSTOMER_CONFIRMED       allowed, advertised       <- RED today (the code ignores the quotation)
    //       -> issue_onAZeroPercentDeal_needsOnlyTheConfirmedOrder
    //   "paid ahead" row                  its own flagged group, further down
    // Also pinned: not advertised once an import request exists (deposit, DEPOSIT_PAID, fulfilment
    // IR_ISSUED), and the two ways the deposit must NOT be got round —
    //   issue_staysRefused_afterTheDepositSwitchIsWaived   (the switch opens nothing; RED today)
    //   issue_opensOnlyThroughConfirmDepositPaid_...       (a recorded receipt is not a confirmation; RED today)
    // Deliberately NOT written: (deposit, AWAITING_FINAL_PAYMENT, fulfilment null) — a deposit deal only
    // reaches AWAITING_FINAL_PAYMENT once its goods are received, by which time fulfilment is no longer
    // null — and any 0% row past CUSTOMER_CONFIRMED (no ruling on them).

    /**
     * The bug (C3), stored per-factory route. A deposit NOTICE is a document, not money in hand, yet
     * {@code DEPOSIT_NOTICE_ISSUED} let the owning rep issue the import request — the form that goes
     * to the factory — before ฝ่ายบัญชี had confirmed that anything was received. Preparing the draft
     * is fine (see {@link #draftsCanStillBePrepared_beforeIssuingIsAllowed}); ISSUING it must wait.
     */
    @Test
    void issue_isRefused_whenOnlyTheDepositNoticeIsIssued_andWritesNothing() {
        insertFactory(PAYGATE_FACTORY);
        DraftedDeal deal = draftedDeal("NOTICE-P", "DEPOSIT_NOTICE_ISSUED", DEPOSIT_DEAL,
            DealStage.ORDER_RECEIVED);

        assertRefusedForTheDeposit(() -> service.issue(deal.draftId(), null, owner));

        assertStillAnUnissuedDraft(deal.draftId());
        assertNothingIssuedOn(deal.ticketId(), "DEPOSIT_NOTICE_ISSUED", DealStage.ORDER_RECEIVED);
    }

    /** The same bug on the legacy deal-level one-click route (POST /api/tickets/{id}/import-request). */
    @Test
    void legacyIssueImportRequest_isRefused_whenOnlyTheDepositNoticeIsIssued_andWritesNothing() {
        long ticketId = insertTicket(nextCode("NOTICE-L"), "DEPOSIT_NOTICE_ISSUED", DEPOSIT_DEAL,
            DealStage.ORDER_RECEIVED);

        assertRefusedForTheDeposit(() -> ticketService.issueImportRequest(ticketId, importUser));

        assertNothingIssuedOn(ticketId, "DEPOSIT_NOTICE_ISSUED", DealStage.ORDER_RECEIVED);
    }

    /**
     * The two DEPOSIT rows below the notice: nothing has been confirmed, so they are refused — on both
     * routes, and the action is not offered. Pinned here WITH the reason and the nothing-written
     * contract because the older status-only tests above cannot tell a deposit refusal from the other
     * 409s on these routes. The "contains" half that keeps the advertisement assertion honest
     * (deposit confirmed -> advertised) is in {@link
     * #issueImportRequestAction_isNotAdvertised_whileOnlyTheDepositNoticeIsIssued}.
     */
    @ParameterizedTest(name = "deposit / {0}")
    @NullSource
    @ValueSource(strings = {"CUSTOMER_CONFIRMED"})
    void issue_isRefusedForTheDeposit_beforeAnythingIsReceived_onBothRoutes_andNotAdvertised(
            String paymentStatus) {
        insertFactory(PAYGATE_FACTORY);

        // Stored per-factory route.
        DraftedDeal deal = draftedDeal("EARLY-P", paymentStatus, DEPOSIT_DEAL, DealStage.ORDER_RECEIVED);
        assertRefusedForTheDeposit(() -> service.issue(deal.draftId(), null, owner));
        assertStillAnUnissuedDraft(deal.draftId());
        assertNothingIssuedOn(deal.ticketId(), paymentStatus, DealStage.ORDER_RECEIVED);

        // Legacy one-click route, on a ticket of its own.
        long ticketId = insertTicket(nextCode("EARLY-L"), paymentStatus, DEPOSIT_DEAL,
            DealStage.ORDER_RECEIVED);
        assertRefusedForTheDeposit(() -> ticketService.issueImportRequest(ticketId, importUser));
        assertNothingIssuedOn(ticketId, paymentStatus, DealStage.ORDER_RECEIVED);

        // ...and the action is not offered for it either (the ticket is exactly as the row describes
        // it — the line above proved nothing was written to it).
        assertThat(availableActionsFor(ticketId)).as("actions at deposit / %s", paymentStatus)
            .doesNotContain(ISSUE_IMPORT_REQUEST);
    }

    /**
     * The other half of the notice-only pair, and what makes that refusal specific to the NOTICE
     * rather than "the gate got stricter": once ฝ่ายบัญชี has confirmed the deposit RECEIVED ({@code
     * DEPOSIT_PAID}, the deal standing at DEPOSIT_RECEIVED) the same form issues — and the deal moves
     * into PROCUREMENT. (The state is stamped here; {@link
     * #issue_opensOnlyThroughConfirmDepositPaid_notThroughARecordedReceipt} reaches it through the
     * real confirmation.)
     */
    @Test
    void issue_isAllowed_onceTheDepositIsPaid_andMovesTheDealToProcurement() {
        insertFactory(PAYGATE_FACTORY);
        DraftedDeal deal = draftedDeal("PAID-P", "DEPOSIT_PAID", DEPOSIT_DEAL,
            DealStage.DEPOSIT_RECEIVED);

        ImportRequestDto issued = service.issue(deal.draftId(), null, owner);

        assertThat(issued.status()).isEqualTo(ImportRequestStatus.ISSUED);
        assertThat(issued.docNumber()).isNotBlank();
        assertIssuedOn(deal.ticketId());
    }

    /** Legacy route, same state: allowed once the deposit is received, with the same deal-level effect. */
    @Test
    void legacyIssueImportRequest_isAllowed_onceTheDepositIsPaid_andMovesTheDealToProcurement() {
        long ticketId = insertTicket(nextCode("PAID-L"), "DEPOSIT_PAID", DEPOSIT_DEAL,
            DealStage.DEPOSIT_RECEIVED);

        ticketService.issueImportRequest(ticketId, importUser);

        assertIssuedOn(ticketId);
    }

    /**
     * Guard against over-correcting (C1/C3). A deal whose accepted quotation asks 0% has NO deposit step
     * at all, so the customer's confirmed order alone opens the gate — on both routes — and until the
     * order is confirmed it is still refused. A fix that started demanding {@code DEPOSIT_PAID} here
     * would strand every 0% deal. RED today: the code reads the deal-level switch and ignores the
     * quotation, so a 0% deal is refused like any deposit deal. Each case gets its own ticket, because
     * an allowed issue changes the ticket it ran on. The advertisement follows the same rule — offered
     * once the order is confirmed, not before — and is read on tickets of its own for the same reason.
     *
     * <p>The refusal while the order is NOT confirmed is about the order rather than the deposit, so only
     * its status (409) and the nothing-written contract are pinned — not its wording. The assertions
     * that hold today (the unconfirmed half) come first.
     */
    @Test
    void issue_onAZeroPercentDeal_needsOnlyTheConfirmedOrder() {
        insertFactory(PAYGATE_FACTORY);

        // The order is not confirmed yet — refused on both routes, and nothing is written.
        DraftedDeal unconfirmedP = draftedDeal("ZERO-NULL-P", null, ZERO_PERCENT_DEAL, DealStage.ORDER_RECEIVED);
        assertRefused(() -> service.issue(unconfirmedP.draftId(), null, owner));
        assertStillAnUnissuedDraft(unconfirmedP.draftId());
        assertNothingIssuedOn(unconfirmedP.ticketId(), null, DealStage.ORDER_RECEIVED);

        long unconfirmedL = insertTicket(nextCode("ZERO-NULL-L"), null, ZERO_PERCENT_DEAL, DealStage.ORDER_RECEIVED);
        assertRefused(() -> ticketService.issueImportRequest(unconfirmedL, importUser));
        assertNothingIssuedOn(unconfirmedL, null, DealStage.ORDER_RECEIVED);

        // ...and not advertised. (Read on a fresh ticket; the "contains" half is below.)
        long advertisedUnconfirmed = insertTicket(nextCode("ZERO-NULL-A"), null, ZERO_PERCENT_DEAL,
            DealStage.ORDER_RECEIVED);
        assertThat(availableActionsFor(advertisedUnconfirmed)).as("0% deal, order not confirmed yet")
            .doesNotContain(ISSUE_IMPORT_REQUEST);

        // The order is confirmed — allowed on both routes, with the deal-level effect.
        DraftedDeal confirmedP = draftedDeal("ZERO-CONF-P", "CUSTOMER_CONFIRMED", ZERO_PERCENT_DEAL,
            DealStage.ORDER_RECEIVED);
        assertThatCode(() -> service.issue(confirmedP.draftId(), null, owner))
            .as("stored route, 0% deal, order confirmed").doesNotThrowAnyException();
        assertThat(storedRow(confirmedP.draftId()).status()).isEqualTo(ImportRequestStatus.ISSUED);
        assertThat(storedRow(confirmedP.draftId()).docNumber()).isNotBlank();
        assertIssuedOn(confirmedP.ticketId());

        long confirmedL = insertTicket(nextCode("ZERO-CONF-L"), "CUSTOMER_CONFIRMED", ZERO_PERCENT_DEAL,
            DealStage.ORDER_RECEIVED);
        assertThatCode(() -> ticketService.issueImportRequest(confirmedL, importUser))
            .as("legacy route, 0% deal, order confirmed").doesNotThrowAnyException();
        assertIssuedOn(confirmedL);

        // The advertisement follows the same rule: offered once the order is confirmed.
        long advertisedConfirmed = insertTicket(nextCode("ZERO-CONF-A"), "CUSTOMER_CONFIRMED", ZERO_PERCENT_DEAL,
            DealStage.ORDER_RECEIVED);
        assertThat(availableActionsFor(advertisedConfirmed)).as("0% deal, order confirmed")
            .contains(ISSUE_IMPORT_REQUEST);
    }

    /**
     * The deposit SWITCH opens nothing (C1: there is no waiver). On a deposit deal whose order the
     * customer has confirmed, the owning rep tries to waive the deposit — the call may be refused or
     * accepted, either is fine — and afterwards the gate is exactly as closed as before: refused for the
     * deposit on both routes, nothing written, not advertised. RED today: {@code waiveDeposit} flips the
     * deal-level policy, and the gate reads that policy, so the very next issue goes through.
     *
     * <p>If {@code TicketService#waiveDeposit} is deleted together with the switch, delete this test with
     * it — its only subject is that method.
     */
    @Test
    void issue_staysRefused_afterTheDepositSwitchIsWaived() {
        insertFactory(PAYGATE_FACTORY);
        DraftedDeal deal = draftedDeal("SWITCH", "CUSTOMER_CONFIRMED", DEPOSIT_DEAL, DealStage.ORDER_RECEIVED);

        tolerateRefusal(() -> ticketService.waiveDeposit(
            deal.ticketId(), DepositPolicy.WAIVED, "ลูกค้าประจำ ขอยกเว้นมัดจำ", owner));

        assertRefusedForTheDeposit(() -> service.issue(deal.draftId(), null, owner));
        assertStillAnUnissuedDraft(deal.draftId());
        assertRefusedForTheDeposit(() -> ticketService.issueImportRequest(deal.ticketId(), importUser));
        assertNothingIssuedOn(deal.ticketId(), "CUSTOMER_CONFIRMED", DealStage.ORDER_RECEIVED);
        assertThat(availableActionsFor(deal.ticketId())).as("actions after the waiver attempt")
            .doesNotContain(ISSUE_IMPORT_REQUEST);
    }

    /**
     * A receipt recorded through the generic {@code recordPayment} (บันทึกรับชำระ) is NOT a deposit
     * confirmation (C2): the deposit is confirmed in exactly one way, ฝ่ายบัญชี's {@code
     * confirmDepositPaid}. On a deposit deal awaiting its deposit ({@code DEPOSIT_NOTICE_ISSUED}), ฝ่ายบัญชี
     * records a DEPOSIT receipt for the full deposit amount — the call may be refused or accepted, either
     * is fine — and afterwards the payment status is still {@code DEPOSIT_NOTICE_ISSUED}, the stage has not
     * moved, and the import request is still refused for the deposit. Only {@code confirmDepositPaid}
     * opens it. RED today: any recorded receipt moves the deal to {@code DEPOSIT_PAID} (and the stage to
     * {@code DEPOSIT_RECEIVED}).
     *
     * <p>The payment status after the confirmation is not pinned — whether the receipt above counts
     * towards it is the implementation's call — only that the gate has opened.
     */
    @Test
    void issue_opensOnlyThroughConfirmDepositPaid_notThroughARecordedReceipt() {
        insertFactory(PAYGATE_FACTORY);
        DraftedDeal deal = draftedDeal("RECEIPT", "DEPOSIT_NOTICE_ISSUED", DEPOSIT_DEAL, DealStage.ORDER_RECEIVED);
        priceTheLine(deal.ticketId());
        BigDecimal deposit = tickets.payableAmount(deal.ticketId()).multiply(new BigDecimal("0.50"));
        assertThat(deposit.signum()).as("the fixture needs a payable amount").isPositive();

        tolerateRefusal(() -> ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("DEPOSIT", deposit, null, "รับมัดจำ", null, null, false), accountUser));

        assertThat(paymentStatusOf(deal.ticketId())).as("a receipt does not confirm the deposit")
            .isEqualTo("DEPOSIT_NOTICE_ISSUED");
        assertThat(salesStageOf(deal.ticketId())).as("the stage has not moved").isEqualTo(DealStage.ORDER_RECEIVED);
        assertRefusedForTheDeposit(() -> service.issue(deal.draftId(), null, owner));
        assertStillAnUnissuedDraft(deal.draftId());

        ticketService.confirmDepositPaid(deal.ticketId(), accountUser);

        assertThatCode(() -> service.issue(deal.draftId(), null, owner))
            .as("stored route, once ฝ่ายบัญชี has confirmed the deposit").doesNotThrowAnyException();
        assertThat(storedRow(deal.draftId()).status()).isEqualTo(ImportRequestStatus.ISSUED);
        assertIssuedOn(deal.ticketId());
    }

    /**
     * The rules gate ISSUING, never drafting (C3): the rep keeps preparing the form from ขั้น 10 — while
     * the deposit is still being collected on a deposit deal, and on a 0% deal too — so it is ready the
     * moment the gate opens. Pinned because the obvious-looking fix — moving the gate into {@code
     * createDrafts} — would satisfy every refusal test above while taking that away.
     */
    @ParameterizedTest(name = "{0}% / {1}")
    @CsvSource({"50, CUSTOMER_CONFIRMED", "50, DEPOSIT_NOTICE_ISSUED", "0, CUSTOMER_CONFIRMED"})
    void draftsCanStillBePrepared_beforeIssuingIsAllowed(int depositPercent, String paymentStatus) {
        insertFactory(PAYGATE_FACTORY);
        long ticketId = insertTicket(nextCode("DRAFT"), paymentStatus, depositPercent, DealStage.ORDER_RECEIVED);
        insertItem(ticketId, PAYGATE_FACTORY, "Line", "60x60", "10", "pcs", 0);

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).status()).isEqualTo(ImportRequestStatus.DRAFT);
        assertThat(drafts.get(0).docNumber()).isNull();
    }

    /**
     * The gate decides whether {@code ISSUE_IMPORT_REQUEST} is advertised too (the same predicate,
     * plus fulfilment == null), and advertising an action that 409s on click is worse than not
     * offering it. Read as {@code ceo}: import is refused the whole-deal view. The deposit-RECEIVED
     * half is what keeps the notice-only half honest — without it, an {@code actions} that returned
     * nothing at all would pass.
     */
    @Test
    void issueImportRequestAction_isNotAdvertised_whileOnlyTheDepositNoticeIsIssued() {
        long noticeOnly = insertTicket(nextCode("ACT-NOTICE"), "DEPOSIT_NOTICE_ISSUED", DEPOSIT_DEAL,
            DealStage.ORDER_RECEIVED);
        long depositPaid = insertTicket(nextCode("ACT-PAID"), "DEPOSIT_PAID", DEPOSIT_DEAL,
            DealStage.DEPOSIT_RECEIVED);
        long alreadyIssued = insertTicket(nextCode("ACT-ISSUED"), "DEPOSIT_PAID", DEPOSIT_DEAL,
            DealStage.PROCUREMENT);
        setFulfillmentStatus(alreadyIssued, "IR_ISSUED");

        // The two that hold today come first, so the one the rules change (notice only) is the last thing to
        // be evaluated — a red run then proves the rest of the test ran, not that it stopped early.
        assertThat(availableActionsFor(depositPaid)).as("deposit received").contains(ISSUE_IMPORT_REQUEST);
        assertThat(availableActionsFor(alreadyIssued)).as("an import request already exists")
            .doesNotContain(ISSUE_IMPORT_REQUEST);
        assertThat(availableActionsFor(noticeOnly)).as("only the deposit notice issued")
            .doesNotContain(ISSUE_IMPORT_REQUEST);
    }

    /**
     * Keeps the deposit assertions above honest about WHICH 409 they saw. The legacy action also
     * refuses once fulfilment has started; that refusal is about the import request, not the
     * deposit, and its text carries no deposit word — the discriminator {@link
     * #assertRefusedForTheDeposit} relies on. Deposit received, fulfilment already {@code IR_ISSUED}.
     */
    @Test
    void legacyIssueImportRequest_isStillRefused_onceAnImportRequestExists() {
        long ticketId = insertTicket(nextCode("ISSUED-L"), "DEPOSIT_PAID", DEPOSIT_DEAL,
            DealStage.PROCUREMENT);
        setFulfillmentStatus(ticketId, "IR_ISSUED");

        assertThatThrownBy(() -> ticketService.issueImportRequest(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).doesNotContain(DEPOSIT_MESSAGE);
            });

        assertThat(fulfillmentStatusOf(ticketId)).isEqualTo("IR_ISSUED");
        assertThat(irEventCount(ticketId)).as("IR_ISSUED events").isZero();
    }

    // ── "PAID AHEAD" — a SEPARATE, FLAGGED group (keep or drop as one unit) ───────────────────────
    //
    // NOT part of the confirmed rules' headline. A DEPOSIT deal that is already paid PAST the deposit
    // before any import request exists — the one row (deposit, FULLY_PAID) — is refused on the legacy
    // route today with the "deposit not received" message, although the stored per-factory route already
    // issues it (S-C above). Money past the deposit includes the deposit; the owner decides whether the
    // legacy route should follow. The two tests below pin the target. (The rows this group used to carry
    // for deals whose deposit was waived went with the switch; no 0% paid-ahead row is written — no
    // ruling on them.)

    /**
     * Legacy route: money already received past the deposit must not leave the deal unable to raise
     * its first import request — today this is refused with the "deposit not received" message.
     */
    @Test
    void legacyIssueImportRequest_paidAhead_isAllowed_whenTheDealIsAlreadyPaidPastTheDeposit() {
        long ticketId = insertTicket(nextCode("AHEAD-L"), "FULLY_PAID", DEPOSIT_DEAL, DealStage.DEPOSIT_RECEIVED);

        assertThatCode(() -> ticketService.issueImportRequest(ticketId, importUser))
            .as("legacy route, deposit deal, FULLY_PAID")
            .doesNotThrowAnyException();

        assertIssuedOn(ticketId);
    }

    /** The advertisement follows the same predicate as the click, so a paid-ahead deal is offered the action too. */
    @Test
    void issueImportRequestAction_paidAhead_isAdvertised_whenTheDealIsAlreadyPaidPastTheDeposit() {
        long ticketId = insertTicket(nextCode("AHEAD-A"), "FULLY_PAID", DEPOSIT_DEAL, DealStage.DEPOSIT_RECEIVED);

        assertThat(availableActionsFor(ticketId)).as("actions at deposit / FULLY_PAID")
            .contains(ISSUE_IMPORT_REQUEST);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    /** A deal with one factory line and the unissued per-factory DRAFT its owning rep prepared. */
    private record DraftedDeal(long ticketId, long draftId) {}

    /** The columns of {@code sales.import_request} a refusal must leave alone. */
    private record StoredRow(String status, String docNumber) {}

    /**
     * Deposit-refusal contract: a 409 whose message names the deposit. A bare {@code CONFLICT} is
     * not enough — see the class Javadoc. If the call does not throw at all, the failure says so:
     * the gate let the issue through.
     */
    private static void assertRefusedForTheDeposit(ThrowableAssert.ThrowingCallable call) {
        // catchThrowable + a described assertThat: assertThatThrownBy(..) fails with a bare "Expecting
        // code to raise a throwable", which says nothing about what happened.
        assertThat(catchThrowable(call))
            .as("the import request must be REFUSED for the deposit -- the call was accepted")
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains(DEPOSIT_MESSAGE);
            });
    }

    /** A refusal about something other than the deposit (so its wording is not pinned): a 409, whatever it says. */
    private static void assertRefused(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** The per-factory row is still an unnumbered DRAFT — a refusal must not have issued it. */
    private void assertStillAnUnissuedDraft(long importRequestId) {
        StoredRow row = storedRow(importRequestId);
        assertThat(row.status()).as("import_request.status").isEqualTo(ImportRequestStatus.DRAFT);
        assertThat(row.docNumber()).as("import_request.doc_number").isNull();
    }

    /** A refusal leaves the deal exactly as it found it: no fulfilment, no stage move, no IR_ISSUED event. */
    private void assertNothingIssuedOn(long ticketId, String paymentStatusBefore, String salesStageBefore) {
        assertThat(fulfillmentStatusOf(ticketId)).as("sales.ticket.fulfillment_status").isNull();
        assertThat(salesStageOf(ticketId)).as("sales.ticket.sales_stage").isEqualTo(salesStageBefore);
        assertThat(paymentStatusOf(ticketId)).as("sales.ticket.payment_status").isEqualTo(paymentStatusBefore);
        assertThat(irEventCount(ticketId)).as("IR_ISSUED events").isZero();
    }

    /** What a successful first issue does to the deal, whichever route raised it. */
    private void assertIssuedOn(long ticketId) {
        assertThat(fulfillmentStatusOf(ticketId)).as("sales.ticket.fulfillment_status").isEqualTo("IR_ISSUED");
        assertThat(salesStageOf(ticketId)).as("sales.ticket.sales_stage").isEqualTo(DealStage.PROCUREMENT);
        assertThat(irEventCount(ticketId)).as("IR_ISSUED events").isEqualTo(1L);
    }

    private List<String> availableActionsFor(long ticketId) {
        return ticketService.actions(ticketId, ceoUser).availableActions().stream()
            .map(TicketResponses.TicketActionDto::action).toList();
    }

    /** {@code sales.ticket.code} is VARCHAR(20) and UNIQUE — a short tag plus a counter keeps every fixture distinct. */
    private String nextCode(String tag) {
        return "PG-" + tag + "-" + (++codeSeq);
    }

    /**
     * A ticket that REALLY reaches the gate: one line on {@link #PAYGATE_FACTORY} (country {@code IT}, which
     * gives the draft a default lead time), no fulfilment, ACTIVE, and a draft prepared by its owning
     * rep. The caller inserts the factory first.
     */
    private DraftedDeal draftedDeal(String tag, String paymentStatus, int depositPercent, String salesStage) {
        long ticketId = insertTicket(nextCode(tag), paymentStatus, depositPercent, salesStage);
        insertItem(ticketId, PAYGATE_FACTORY, "Line", "60x60", "10", "pcs", 0);
        long draftId = service.createDrafts(ticketId, null, owner).get(0).id();
        return new DraftedDeal(ticketId, draftId);
    }

    private StoredRow storedRow(long importRequestId) {
        return jdbc.queryForObject(
            "SELECT status, doc_number FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", importRequestId),
            (rs, rowNum) -> new StoredRow(rs.getString("status"), rs.getString("doc_number")));
    }

    private String fulfillmentStatusOf(long ticketId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String salesStageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String paymentStatusOf(long ticketId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private long irEventCount(long ticketId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            Map.of("id", ticketId, "kind", TicketEventKind.IR_ISSUED), Long.class);
    }

    private void setFulfillmentStatus(long ticketId, String fulfillmentStatus) {
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = :s WHERE ticket_id = :id",
            Map.of("s", fulfillmentStatus, "id", ticketId));
    }

    private static ImportRequestDto byFactory(List<ImportRequestDto> rows, long factoryId) {
        return rows.stream().filter(r -> r.factoryId() == factoryId).findFirst().orElseThrow();
    }

    private void setTicketStatus(long ticketId, String status) {
        jdbc.update("UPDATE sales.ticket SET status = :s WHERE ticket_id = :id", Map.of("s", status, "id", ticketId));
    }

    private void setPaymentStatus(long ticketId, String paymentStatus) {
        jdbc.update("UPDATE sales.ticket SET payment_status = :s WHERE ticket_id = :id",
            Map.of("s", paymentStatus, "id", ticketId));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-paygate@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'เกตชำระเงิน') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, 'IT', 'EUR') RETURNING factory_id
            """, Map.of("name", name), Long.class);
    }

    /**
     * {@code paymentStatus} nullable — a null deal is one whose order is not confirmed yet. The default
     * deal: a DEPOSIT deal (its accepted quotation asks 50%), standing at ORDER_RECEIVED.
     */
    private long insertTicket(String code, String paymentStatus) {
        return insertTicket(code, paymentStatus, DEPOSIT_DEAL, DealStage.ORDER_RECEIVED);
    }

    /**
     * The ONE place a deal of this class is built. Its accepted quotation asks {@code depositPercent}
     * (50 = a deposit deal, 0 = a 0% deal with no deposit step at all). The quotation row exists BEFORE
     * the customer's order is confirmed, and a non-null {@code paymentStatus} means the owning rep has
     * REALLY confirmed the order ({@link TicketService#confirmCustomer}) — so the fixture is right whether
     * the gate reads the quotation when it runs or derives something when the order is confirmed. The
     * deposit switch ({@code deposit_policy}) is never written, and {@code deposit_percent} is never
     * stamped after the confirmation. A payment state past {@code CUSTOMER_CONFIRMED} is stamped
     * afterwards, as the payment track would have left it.
     */
    private long insertTicket(String code, String paymentStatus, int depositPercent, String salesStage) {
        long ticketId = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, sales_stage)
            VALUES (:code, 'ทดสอบเกตชำระเงิน', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", salesStage), Long.class);
        insertQuotation(ticketId, depositPercent);
        if (paymentStatus != null) {
            ticketService.confirmCustomer(ticketId, owner);
            if (!"CUSTOMER_CONFIRMED".equals(paymentStatus)) {
                setPaymentStatus(ticketId, paymentStatus);
            }
        }
        return ticketId;
    }

    /** The deal's accepted quotation: an approved direct-deal row asking {@code depositPercent} (whole percent). */
    private void insertQuotation(long ticketId, int depositPercent) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version,
                                         origin, deposit_percent)
            VALUES (:ticketId, :number, :by, 'APPROVED', 1, 'DEAL_DIRECT', :depositPercent)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("number", "QTD-GATE-" + ticketId).addValue("by", ownerId)
                .addValue("depositPercent", (short) depositPercent));
    }

    /** Gives the deal's one line (10 units) an approved price, so it has a payable amount: 10 x 1,000 + 7% VAT. */
    private void priceTheLine(long ticketId) {
        jdbc.update("UPDATE sales.ticket_item SET approved_price = 1000 WHERE ticket_id = :id", Map.of("id", ticketId));
    }

    /** The call may be refused (an {@code ApiException}) or accepted; the tests using this pin what must not change either way. */
    private static void tolerateRefusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            // a refusal is as good as an acceptance that opens nothing
        }
    }

    private void insertItem(long ticket, String factory, String model, String size, String qty,
                            String unit, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", factory)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory));
    }
}
