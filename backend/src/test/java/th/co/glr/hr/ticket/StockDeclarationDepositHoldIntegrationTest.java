package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Finding D3, under the rules the owner confirmed on 2026-10-05 (C1, C2, C4): declaring FULL stock must
 * not move the deal past ขั้น 10 ({@code ORDER_RECEIVED}) while the deal still has a deposit to confirm.
 *
 * <p><b>The finding.</b> {@code TicketService#reserveStock}: when every line is declared from stock and
 * the deal is not on the import axis, it sets {@code fulfillment_status = FROM_STOCK} <em>and</em> calls
 * {@code autoAdvanceStage(DELIVERY_SCHEDULING)}. The only precondition on that move is the stage floor
 * (ORDER_RECEIVED, ขั้น 10), so a deal jumps from ขั้น 10 to ขั้น 13 (นัดส่งสินค้า) with no deposit notice
 * and nothing confirmed. Declaring stock stays allowed from ขั้น 10, but on a deposit deal the stage move
 * has to WAIT for the deposit and happen by itself when it is confirmed.
 *
 * <p><b>The contract — only the stage move is deferred.</b> A full-coverage declaration (every line
 * {@code qty_from_stock = qty}, the deal not on the import axis) still saves each line, still writes the
 * {@code STOCK_RESERVED} event, still sets {@code FROM_STOCK} (the deal leaves the import axis) and still
 * notifies ผจก.ขาย when the rep declares — whatever the deposit state. The one thing that changes is
 * {@code sales_stage}: on a DEPOSIT deal it moves to {@code DELIVERY_SCHEDULING} only once the deposit is
 * confirmed; until then the stage stays where it was, and the move happens at the moment of the
 * confirmation. On a 0% deal there is nothing to confirm, so it moves at once. A PARTIAL declaration is
 * unaffected: it never sets {@code FROM_STOCK} and never moves the stage, today or after.
 *
 * <p><b>"The deposit is confirmed"</b> (C1/C2): the deal's ACCEPTED QUOTATION decides whether it has a
 * deposit — {@code deposit_percent} above 0 means a deposit deal, 0 means no deposit step at all — and
 * there is no waiver: no deal-level switch opens anything. A deposit is confirmed in exactly one way,
 * ฝ่ายบัญชี's {@code confirmDepositPaid}; a receipt recorded through the generic {@code recordPayment} is
 * not a confirmation. So the stage is free to move when a deposit deal's {@code payment_status} is {@code
 * DEPOSIT_PAID}, {@code AWAITING_FINAL_PAYMENT} or {@code FULLY_PAID} (a deposit NOTICE is a document, not
 * money, and the customer's confirmation is not a deposit either), and when a 0% deal's order is
 * confirmed ({@code CUSTOMER_CONFIRMED} or later). A {@code (0%, null)} case is deliberately NOT written: a
 * deal with no confirmed order at all is a different finding (the order check), on which the owner has not
 * ruled here.
 *
 * <p><b>How a test says "deposit deal" and "0% deal".</b> {@link #deal}: the accepted quotation row, with
 * its {@code deposit_percent}, is written BEFORE the customer's order is confirmed, and the order is then
 * confirmed through the real {@code TicketService#confirmCustomer} — so the fixture is right whether the
 * gate reads the quotation when it runs or derives something when the order is confirmed. {@code
 * deposit_policy} (the old switch) is never written. A null payment status is a deal whose order is not
 * confirmed yet; payment states past the confirmation are stamped afterwards.
 *
 * <p><b>How the tests fit together.</b>
 *
 * <ul>
 *   <li><b>A — the hold at declaration (RED today).</b> {@link
 *       #fullDeclaration_beforeTheDepositIsReady_isSaved_butDoesNotMoveTheStage}: the declaration is
 *       saved in full, and the stage stays put.
 *   <li><b>B — no over-holding (GREEN today).</b> {@link
 *       #fullDeclaration_onceTheDepositIsReady_movesTheDealToDeliveryScheduling}: a fix that held the
 *       stage unconditionally would break every deal that is ready — a confirmed deposit, or a 0% deal.
 *   <li><b>C — the release (RED today).</b> {@link #theHeldRoute_appliesWhenAccountConfirmsTheDeposit}: a
 *       HELD deal moves to {@code DELIVERY_SCHEDULING} at the moment ฝ่ายบัญชี confirms the deposit —
 *       the only way it becomes ready. Today's service cannot produce a held deal (the declaration jumps),
 *       so {@link #heldDeal} builds the state A requires the service to leave; A and C together describe
 *       the whole behaviour.
 *   <li><b>D — guards on the release (GREEN today).</b> The same story through real calls only, a partial
 *       declaration that must NOT be released, and the declaration staying open while the stage is held.
 *   <li><b>E — no way round the confirmation (RED today).</b> The deposit switch opens nothing ({@link
 *       #theStage_staysHeld_afterTheDepositSwitchIsWaived}), and a recorded receipt is not a confirmation
 *       ({@link #theHeldStage_isReleasedOnlyByConfirmDepositPaid_notByARecordedReceipt}).
 * </ul>
 *
 * <p><b>Neighbours.</b> {@link StageFactGateIntegrationTest} owns the stage FLOOR (a declaration below
 * ORDER_RECEIVED is refused); {@link StockDeclarationAuthzIntegrationTest} owns WHO may declare; {@link
 * DeliveryDepositGateIntegrationTest} owns "no delivery before the deposit". This class owns only whether
 * a declaration may move the stage. The notification to ผจก.ขาย is pinned by {@link
 * StockDeclarationNotificationIntegrationTest} on a deal with nothing received, which is the existing
 * proof that {@code FROM_STOCK} is set at declaration whatever was paid; it is not repeated here.
 *
 * <p><b>Deliberately not pinned.</b> Whether {@code DEPOSIT_RECEIVED} appears in the stage history on the
 * way to {@code DELIVERY_SCHEDULING} (only the resulting {@code sales_stage} is); what happens to a held
 * deal whose declaration is later LOWERED (today a lowering does not take {@code FROM_STOCK} back, and the
 * owner has not ruled on whether the release still fires); and the warehouse route, where nothing is
 * declared.
 *
 * <p>Written wrong-way-round, and — like its siblings — hand-wired with {@code new}, so the services'
 * transaction annotations are inert here: what a declaration leaves behind is read straight out of
 * Postgres, and nothing below depends on a rollback.
 */
class StockDeclarationDepositHoldIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String RESERVE_STOCK = "RESERVE_STOCK";

    /** Whole percent the accepted quotation asks for: a DEPOSIT deal... */
    private static final int DEPOSIT_DEAL = 50;
    /** ...and a 0% deal, which has no deposit step at all. */
    private static final int ZERO_PERCENT_DEAL = 0;

    /** Payment states in which a DEPOSIT deal's deposit is NOT yet confirmed. */
    private static final String[] NOT_READY = {
        null, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED};
    /** …and in which it HAS been (the balance is not required). */
    private static final String[] RECEIVED = {
        PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID};
    /** For a 0% deal, "ready" starts at the customer's confirmed order. */
    private static final String[] ZERO_PERCENT_READY = {
        PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID};

    /** The two roles that may declare stock on a deal they can reach: the owning rep and the CEO. */
    private enum Declarer { OWNER, CEO }

    /** A one-line deal: {@code itemId} is its only line (10 units). */
    private record Deal(long ticketId, long itemId) {}

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ถือสเตจสต็อก", "stockhold-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "stockhold-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "stockhold-account@glr.co.th"), "account");
    }

    // ── A: the hold at declaration ───────────────────────────────────────────────────────────

    /**
     * The D3 bug: a DEPOSIT deal whose deposit is not yet confirmed jumps from ขั้น 10 to {@code
     * DELIVERY_SCHEDULING} on a full declaration. Every payment state in which the deposit is not
     * confirmed is driven — an unconfirmed order ({@code null}), the customer's confirmation only, a
     * deposit notice issued — once for each of the two roles that may declare (the owning rep and the
     * CEO), one invocation per row so each reports on its own.
     *
     * <p>The assertions that already hold today come FIRST — the declaration is saved in full, exactly one
     * {@code STOCK_RESERVED} event is written, {@code FROM_STOCK} is set, the payment status is untouched
     * — so the failure lands on the stage and on nothing else; the contract is that only the stage move
     * waits. The deal is built to its payment state BEFORE the declaration, so each row declares AT that
     * state.
     */
    @ParameterizedTest(name = "deposit / {0} / declared by {1}")
    @MethodSource("notReadyAtDeclaration")
    void fullDeclaration_beforeTheDepositIsReady_isSaved_butDoesNotMoveTheStage(
            String paymentStatus, Declarer who) {
        Deal deal = deal(DEPOSIT_DEAL, paymentStatus);
        String stageBefore = stageOf(deal);
        assertThat(stageBefore).isEqualTo(DealStage.ORDER_RECEIVED);

        ticketService.reserveStock(deal.ticketId(), declareAll(deal), declarer(who));

        // Everything the declaration writes today, it still writes (all of these hold today).
        assertThat(qtyFromStockOf(deal)).as("sales.ticket_item.qty_from_stock").isEqualByComparingTo("10.00");
        assertThat(eventCount(deal, TicketEventKind.STOCK_RESERVED)).as("STOCK_RESERVED events").isEqualTo(1L);
        assertThat(fulfilmentOf(deal)).as("sales.ticket.fulfillment_status").isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(paymentStatusOf(deal)).as("a declaration never moves the payment track").isEqualTo(paymentStatus);

        // Only the stage waits for the deposit.
        assertThat(stageOf(deal))
            .as("sales.ticket.sales_stage after a full declaration at deposit / %s (it was %s before)",
                paymentStatus, stageBefore)
            .isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(stageChangedTo(deal, DealStage.DELIVERY_SCHEDULING))
            .as("STAGE_CHANGED events to DELIVERY_SCHEDULING while the deposit is not confirmed")
            .isZero();
    }

    // ── B: no over-holding ───────────────────────────────────────────────────────────────────

    /**
     * Guard against over-holding: when the deal is ready at the moment of the declaration, the move to
     * {@code DELIVERY_SCHEDULING} is immediate, as it always was — the three deposit-CONFIRMED states of a
     * deposit deal, and a 0% deal at each of the states in which it is ready (6 rows: a 0% deal has no
     * deposit step, so its confirmed order is enough). A fix that held the stage unconditionally would
     * strand every one of them, and still pass every test in A. Exactly one {@code STAGE_CHANGED} event
     * targets {@code DELIVERY_SCHEDULING}.
     *
     * <p>(A {@code (0%, null)} row is deliberately NOT written: a deal with no confirmed order at all is a
     * different finding — the order check — and the owner has not ruled on it here.)
     */
    @ParameterizedTest(name = "{0}% / {1}")
    @MethodSource("depositReadyAtDeclaration")
    void fullDeclaration_onceTheDepositIsReady_movesTheDealToDeliveryScheduling(
            int depositPercent, String paymentStatus) {
        Deal deal = deal(depositPercent, paymentStatus);

        ticketService.reserveStock(deal.ticketId(), declareAll(deal), owner);

        assertThat(fulfilmentOf(deal)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(deal)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(stageChangedTo(deal, DealStage.DELIVERY_SCHEDULING))
            .as("STAGE_CHANGED events to DELIVERY_SCHEDULING").isEqualTo(1L);
    }

    // ── C: the release — a HELD deal moves when ฝ่ายบัญชี confirms the deposit ───────────────
    //
    // Starts from a deal that is ALREADY held (see heldDeal). The deposit is confirmed in exactly one
    // way (C2), so this is the only release there is: a recorded receipt is not one (see E), and neither
    // is a waiver — there is none. The assertions that hold today come first (the payment status the call
    // produced, FROM_STOCK still set); the last one — the stage — is the one that is red.

    /**
     * ฝ่ายบัญชี confirms the deposit on a held deal at {@code DEPOSIT_NOTICE_ISSUED}: payment becomes
     * {@code DEPOSIT_PAID} and the deal goes straight on to {@code DELIVERY_SCHEDULING}. (Today it stops
     * at {@code DEPOSIT_RECEIVED}.)
     */
    @Test
    void theHeldRoute_appliesWhenAccountConfirmsTheDeposit() {
        Deal deal = heldDeal(PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        ticketService.confirmDepositPaid(deal.ticketId(), accountUser);

        assertThat(paymentStatusOf(deal)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(fulfilmentOf(deal)).as("the release keeps the deal off the import axis")
            .isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(deal)).as("sales_stage once account confirms the deposit on a HELD deal")
            .isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    // ── D: guards on the release ─────────────────────────────────────────────────────────────

    /**
     * The release through REAL calls only — no constructed state: a deal whose order the customer has
     * confirmed, the owner declares every line through {@code reserveStock}, the notice status is stamped
     * (a real notice is built end to end in {@code PaymentTrackIntegrationTest}), ฝ่ายบัญชี confirms the
     * deposit, and the deal ends at {@code DELIVERY_SCHEDULING}, {@code FROM_STOCK}, {@code DEPOSIT_PAID}.
     *
     * <p>It asserts ONLY the end state, never the hold in the middle. It is green today because the stage
     * jumped early; after a fix that held the stage but FORGOT the release it goes red — it is the test
     * that ties A and C together through real calls, so a held deal can never be stranded.
     */
    @Test
    void declareThenDeposit_throughTheRealService_endsAtDeliveryScheduling() {
        Deal deal = deal(DEPOSIT_DEAL, PaymentTrack.CUSTOMER_CONFIRMED);

        ticketService.reserveStock(deal.ticketId(), declareAll(deal), owner);
        tickets.updatePaymentStatusUnchecked(deal.ticketId(), PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(deal.ticketId(), accountUser);

        assertThat(paymentStatusOf(deal)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(fulfilmentOf(deal)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(deal)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    /**
     * Guard against releasing on "any stock declared": the owner declares only PART of the line (4 of 10),
     * so fulfilment stays null — the deal is NOT {@code FROM_STOCK} — and when the deposit lands the deal
     * goes only to {@code DEPOSIT_RECEIVED}, as it always did. A release keyed on {@code qty_from_stock &gt;
     * 0} instead of on a fully covered, {@code FROM_STOCK} deal would send it on to {@code
     * DELIVERY_SCHEDULING} with the other 6 units still to be imported.
     */
    @Test
    void aPartlyDeclaredDeal_goesOnlyToDepositReceived_whenTheDepositLands() {
        Deal deal = deal(DEPOSIT_DEAL, PaymentTrack.CUSTOMER_CONFIRMED);

        ticketService.reserveStock(deal.ticketId(), declare(deal, "4.00"), owner);
        assertThat(fulfilmentOf(deal)).as("a PARTLY declared deal is not FROM_STOCK").isNull();
        tickets.updatePaymentStatusUnchecked(deal.ticketId(), PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(deal.ticketId(), accountUser);

        assertThat(paymentStatusOf(deal)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(fulfilmentOf(deal)).isNull();
        assertThat(stageOf(deal)).isEqualTo(DealStage.DEPOSIT_RECEIVED);
    }

    /**
     * Holding the stage must not freeze the declaration: after a full declaration on a deposit deal at
     * {@code DEPOSIT_NOTICE_ISSUED}, {@code RESERVE_STOCK} is still advertised to the owning rep and to
     * the CEO, so the number can be corrected, and a second declaration by the owner (lowering the line to
     * 6 of 10) is accepted and saved. The stage is deliberately not asserted here — that is A's job.
     */
    @Test
    void theDeclarationStaysOpen_whileTheStageIsHeld() {
        Deal deal = deal(DEPOSIT_DEAL, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.reserveStock(deal.ticketId(), declareAll(deal), owner);

        for (UserPrincipal viewer : List.of(owner, ceoUser)) {
            assertThat(actionsOf(deal, viewer))
                .as("%s read as %s after a full declaration", RESERVE_STOCK, viewer.role())
                .contains(RESERVE_STOCK);
        }

        ticketService.reserveStock(deal.ticketId(), declare(deal, "6.00"), owner);

        assertThat(qtyFromStockOf(deal)).as("the corrected declaration is saved").isEqualByComparingTo("6.00");
        assertThat(eventCount(deal, TicketEventKind.STOCK_RESERVED)).as("STOCK_RESERVED events").isEqualTo(2L);
    }

    // ── E: no way round the confirmation ─────────────────────────────────────────────────────

    /**
     * The deposit SWITCH opens nothing (C1: there is no waiver). On a deposit deal whose order the customer
     * has confirmed, the owning rep tries to waive the deposit — the call may be refused or accepted,
     * either is fine — and the stage is still held when the stock is then declared in full: the
     * declaration is saved ({@code FROM_STOCK}, every unit), the payment status is untouched, and the stage
     * has not moved. RED today: nothing holds the stage at all yet (the declaration jumps), whatever the
     * deposit; after the fix it is also what stops a deal that consults the old switch from counting as
     * ready.
     *
     * <p>The waiver comes BEFORE the declaration on purpose — the declaration is where a gate that still
     * read the switch would let the stage through. (The other order, waiving a deal that is ALREADY held,
     * is green today — a waiver moves no stage — and is not pinned here.)
     *
     * <p>If {@code TicketService#waiveDeposit} is deleted together with the switch, delete this test with
     * it — its only subject is that method.
     */
    @Test
    void theStage_staysHeld_afterTheDepositSwitchIsWaived() {
        Deal deal = deal(DEPOSIT_DEAL, PaymentTrack.CUSTOMER_CONFIRMED);

        tolerateRefusal(() -> ticketService.waiveDeposit(
            deal.ticketId(), DepositPolicy.WAIVED, "ลูกค้าประจำ ขอยกเว้นมัดจำ", owner));
        ticketService.reserveStock(deal.ticketId(), declareAll(deal), owner);

        assertThat(qtyFromStockOf(deal)).as("sales.ticket_item.qty_from_stock").isEqualByComparingTo("10.00");
        assertThat(fulfilmentOf(deal)).as("sales.ticket.fulfillment_status").isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(paymentStatusOf(deal)).as("a declaration never moves the payment track")
            .isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
        assertThat(stageOf(deal)).as("sales_stage after a waiver attempt and a full declaration on a deposit deal")
            .isEqualTo(DealStage.ORDER_RECEIVED);
    }

    /**
     * A receipt recorded through the generic {@code recordPayment} (บันทึกรับชำระ) is NOT a deposit
     * confirmation (C2): the deposit is confirmed in exactly one way, ฝ่ายบัญชี's {@code
     * confirmDepositPaid}. On a HELD deal awaiting its deposit ({@code DEPOSIT_NOTICE_ISSUED}), ฝ่ายบัญชี
     * records a DEPOSIT receipt for the full deposit amount — the call may be refused or accepted, either
     * is fine — and afterwards the payment status is still {@code DEPOSIT_NOTICE_ISSUED} and the stage is
     * still held. Only {@code confirmDepositPaid} releases it. RED today: any recorded receipt moves the
     * deal to {@code DEPOSIT_PAID} (and the stage to {@code DEPOSIT_RECEIVED}).
     *
     * <p>The payment status after the confirmation is not pinned — whether the receipt above counts
     * towards it is the implementation's call — only that the stage has been released.
     */
    @Test
    void theHeldStage_isReleasedOnlyByConfirmDepositPaid_notByARecordedReceipt() {
        Deal deal = heldDeal(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        BigDecimal deposit = payableOf(deal).multiply(new BigDecimal("0.50"));

        tolerateRefusal(() -> ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("DEPOSIT", deposit, null, "รับมัดจำ", null, null, false), accountUser));

        assertThat(paymentStatusOf(deal)).as("a receipt does not confirm the deposit")
            .isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertThat(fulfilmentOf(deal)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(deal)).as("the stage is still held after a recorded receipt")
            .isEqualTo(DealStage.ORDER_RECEIVED);

        ticketService.confirmDepositPaid(deal.ticketId(), accountUser);

        assertThat(stageOf(deal)).as("sales_stage once account confirms the deposit")
            .isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    // ── parameter sources ────────────────────────────────────────────────────────────────────

    /** (payment state, declarer) for every row the hold must cover. */
    static Stream<Arguments> notReadyAtDeclaration() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : NOT_READY) {
            for (Declarer who : Declarer.values()) {
                rows.add(Arguments.of(status, who));
            }
        }
        return rows.stream();
    }

    /** (deposit percent, payment state) for every row in which the deal is ready at declaration. */
    static Stream<Arguments> depositReadyAtDeclaration() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : RECEIVED) {
            rows.add(Arguments.of(DEPOSIT_DEAL, status));
        }
        for (String status : ZERO_PERCENT_READY) {
            rows.add(Arguments.of(ZERO_PERCENT_DEAL, status));
        }
        return rows.stream();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /**
     * The ONE place a deal of this class is built: an ACTIVE ticket with ONE line of 10 units, seeded to
     * {@code ORDER_RECEIVED} (the floor a declaration must clear), whose accepted quotation asks {@code
     * depositPercent} (50 = a deposit deal, 0 = a 0% deal with no deposit step at all); nothing declared.
     * The line has an approved price — payable = 10 x 1,000 + 7% VAT = 10,700 — so {@code
     * confirmDepositPaid} and {@code recordPayment} have an amount to work with (a deal without one is
     * refused "no deposit amount").
     *
     * <p>The quotation row exists BEFORE the customer's order is confirmed, and a non-null {@code
     * paymentStatus} means the owning rep has REALLY confirmed it ({@code
     * TicketService#confirmCustomer}) — so the fixture is right whether the gate reads the quotation when
     * it runs or derives something when the order is confirmed. {@code deposit_policy} (the old switch) is
     * never written, and {@code deposit_percent} is never stamped after the confirmation. A payment state
     * past {@code CUSTOMER_CONFIRMED} is stamped afterwards, as the payment track would have left it; a
     * null payment status is an unconfirmed order.
     */
    private Deal deal(int depositPercent, String paymentStatus) {
        long ticketId = newTicket();
        Deal deal = new Deal(ticketId, onlyItemId(ticketId));
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        setApprovedPrice(deal.itemId(), "1000.00");
        insertQuotation(ticketId, depositPercent);
        if (paymentStatus != null) {
            setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
            ticketService.confirmCustomer(ticketId, owner);
            if (!PaymentTrack.CUSTOMER_CONFIRMED.equals(paymentStatus)) {
                tickets.updatePaymentStatusUnchecked(ticketId, paymentStatus);
            }
        }
        assertThat(paymentStatusOf(deal)).as("the fixture is built to its payment state").isEqualTo(paymentStatus);
        assertThat(stageOf(deal)).as("the fixture stands at the stage floor").isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(payableOf(deal).signum()).as("the fixture must have a payable amount").isPositive();
        return deal;
    }

    /**
     * A HELD deal: the state a fixed service leaves behind after a full declaration made before the deposit
     * is confirmed — every line {@code qty_from_stock = qty}, {@code FROM_STOCK}, stage still {@code
     * ORDER_RECEIVED}, deposit deal not yet confirmed. It is EXACTLY the state test A requires the service
     * to leave, so A and C together describe the whole behaviour.
     *
     * <p>Today's service cannot produce it (the declaration jumps to {@code DELIVERY_SCHEDULING}), so it is
     * built from a deposit {@link #deal} at {@code CUSTOMER_CONFIRMED} plus three REPOSITORY calls — {@code
     * TicketRepository#reserveStock}, {@code #addEvent} (the one {@code STOCK_RESERVED} event the service
     * writes) and {@code #updateFulfillmentStatus}, NOT the service — and then the payment status if it is
     * not {@code CUSTOMER_CONFIRMED}. The fixture asserts the deal is what it claims, so a release test can
     * only go red on the release.
     */
    private Deal heldDeal(String paymentStatus) {
        Deal deal = deal(DEPOSIT_DEAL, PaymentTrack.CUSTOMER_CONFIRMED);
        tickets.reserveStock(deal.ticketId(), declareAll(deal).lines());
        tickets.addEvent(deal.ticketId(), ownerId, "เจ้าของดีล ถือสเตจสต็อก", TicketEventKind.STOCK_RESERVED,
            TicketStatus.QUOTATION_ISSUED, TicketStatus.QUOTATION_ISSUED, "qty_from_stock=10");
        tickets.updateFulfillmentStatus(deal.ticketId(), FulfilmentStatus.FROM_STOCK);
        if (!PaymentTrack.CUSTOMER_CONFIRMED.equals(paymentStatus)) {
            tickets.updatePaymentStatusUnchecked(deal.ticketId(), paymentStatus);
        }

        assertThat(qtyFromStockOf(deal)).as("held deal: every unit declared").isEqualByComparingTo("10.00");
        assertThat(eventCount(deal, TicketEventKind.STOCK_RESERVED)).as("held deal: STOCK_RESERVED events")
            .isEqualTo(1L);
        assertThat(fulfilmentOf(deal)).as("held deal: off the import axis").isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(deal)).as("held deal: the stage is held").isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(paymentStatusOf(deal)).as("held deal: the deposit is not confirmed").isEqualTo(paymentStatus);
        assertThat(stageChangedTo(deal, DealStage.DELIVERY_SCHEDULING)).isZero();
        return deal;
    }

    /** An ACTIVE ticket with ONE line of 10 units; nothing declared, nothing paid, no quotation yet. */
    private long newTicket() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบถือสเตจสต็อก", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("10.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ถือสเตจสต็อก");
    }

    /** The deal's accepted quotation: an approved direct-deal row asking {@code depositPercent} (whole percent). */
    private void insertQuotation(long ticketId, int depositPercent) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version,
                                         origin, deposit_percent)
            VALUES (:ticketId, :number, :by, 'APPROVED', 1, 'DEAL_DIRECT', :depositPercent)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("number", "QTD-HOLD-" + ticketId).addValue("by", ownerId)
                .addValue("depositPercent", (short) depositPercent));
    }

    /** The call may be refused (an {@code ApiException}) or accepted; the tests using this pin what must not change either way. */
    private static void tolerateRefusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            // a refusal is as good as an acceptance that opens nothing
        }
    }

    /** Every unit of the line declared from stock. */
    private static StockReservationRequest declareAll(Deal deal) {
        return declare(deal, "10.00");
    }

    private static StockReservationRequest declare(Deal deal, String qtyFromStock) {
        return new StockReservationRequest(List.of(
            new StockReservationRequest.Line(deal.itemId(), new BigDecimal(qtyFromStock), "ทดสอบ")));
    }

    private UserPrincipal declarer(Declarer who) {
        return who == Declarer.OWNER ? owner : ceoUser;
    }

    /**
     * The last fallback of {@code TicketRepository.payableAmount} is {@code SUM(approved_price * qty)}, so
     * this is the smallest honest way to give a deal a payable amount without dragging the whole pricing
     * request chain into a test about stages.
     */
    private void setApprovedPrice(long itemId, String price) {
        jdbc.update("UPDATE sales.ticket_item SET approved_price = :price WHERE item_id = :id",
            new MapSqlParameterSource().addValue("price", new BigDecimal(price)).addValue("id", itemId));
    }

    private void setStatus(long ticketId, String status) {
        jdbc.update("UPDATE sales.ticket SET status = :status WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("status", status).addValue("id", ticketId));
    }

    private BigDecimal payableOf(Deal deal) {
        return tickets.payableAmount(deal.ticketId());
    }

    private List<String> actionsOf(Deal deal, UserPrincipal viewer) {
        return ticketService.actions(deal.ticketId(), viewer).availableActions().stream()
            .map(TicketResponses.TicketActionDto::action)
            .toList();
    }

    // ── reads, straight out of Postgres ──────────────────────────────────────────────────────

    private String stageOf(Deal deal) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal.ticketId()), String.class);
    }

    private String fulfilmentOf(Deal deal) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal.ticketId()), String.class);
    }

    private String paymentStatusOf(Deal deal) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal.ticketId()), String.class);
    }

    private BigDecimal qtyFromStockOf(Deal deal) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", deal.itemId()), BigDecimal.class);
    }

    private long eventCount(Deal deal, String kind) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :ticketId AND kind = :kind",
            Map.of("ticketId", deal.ticketId(), "kind", kind), Long.class);
    }

    private long stageChangedTo(Deal deal, String stage) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind AND to_status = :stage
            """,
            Map.of("ticketId", deal.ticketId(), "kind", TicketEventKind.STAGE_CHANGED, "stage", stage),
            Long.class);
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-stockhold@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
