package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
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
 * WHEN a delivery may be recorded — the DEPOSIT half. The rules the owner confirmed on 2026-10-05
 * (C1, C2, C5).
 *
 * <p><b>The rule.</b> The deal's ACCEPTED QUOTATION decides whether it has a deposit: its {@code
 * deposit_percent} above 0 makes it a DEPOSIT deal, and on a deposit deal recording ANY delivery —
 * partial or complete, from stock or from the warehouse, by the owning rep or the CEO — waits until
 * ฝ่ายบัญชี has confirmed the deposit ({@code confirmDepositPaid}, ยืนยันรับมัดจำ: {@code DEPOSIT_PAID}
 * or any later state). A deposit NOTICE is not enough, neither is the customer's confirmation, and a
 * receipt recorded through the generic {@code recordPayment} is not a confirmation. A 0% quotation means
 * there is no deposit step at all, so on a 0% deal delivery is allowed once the order is confirmed.
 * There is no waiver, and no deal-level switch opens anything. DECLARING stock stays allowed: a
 * declaration is not a delivery. Delivery NEVER waits for the BALANCE — goods may be delivered before the
 * rest is paid. Whether {@code RECORD_PARTIAL_DELIVERY} and {@code COMPLETE_DELIVERY} are advertised
 * follows the same rule, so the UI never offers a button that would 409. Before this rule delivery had
 * no payment check at all: in a replay against the real Java service on a local test database, three
 * test deals were delivered in full with ฿0 received.
 *
 * <p><b>How a test says "deposit deal" and "0% deal".</b> {@link #newTicket}: the accepted quotation row,
 * with its {@code deposit_percent}, is written BEFORE the customer's order is confirmed, and the order is
 * then confirmed through the real {@code TicketService#confirmCustomer} — so the fixture is right whether
 * the gate reads the quotation when it runs or derives something when the order is confirmed. {@code
 * deposit_policy} (the old switch) is never written. A null payment status is a deal whose order is not
 * confirmed yet; payment states past the confirmation are stamped afterwards.
 *
 * <p><b>Three shapes of deliverable deal</b>, because {@code canRecordDelivery} reaches a deal through
 * one of three limbs and a fix that gated only some of them would pass the rest: every line declared
 * from stock ({@code FROM_STOCK}), goods that reached the warehouse ({@code GOODS_RECEIVED}), and a deal
 * PARTLY declared from stock (fulfilment still null — the mixed route, where the stock part goes out
 * first while the import is still on its way).
 *
 * <p><b>The two halves.</b> {@link DeliveryAuthzIntegrationTest} is the WHO half — only the CEO and
 * the owning sales rep may write a delivery — and its fixture deal is deliberately deposit-ready so
 * that role is the only thing in its way. This class is the DEPOSIT half. {@link
 * #whoMayDeliver_isCheckedBeforeTheDeposit} pins the ORDER of the two checks: an unauthorised caller
 * gets 403 and learns nothing about the deal's payment state.
 *
 * <p><b>The refusal contract</b> ({@link #assertRefusedForTheDeposit}). {@code
 * TicketService#recordDeliveryInternal} already throws other 409s (over-delivery, more than the
 * declared stock, "receive the goods into the warehouse first", an inactive deal, "nothing left to
 * deliver"), so a bare {@code CONFLICT} cannot tell them apart. A "refused because of the deposit"
 * assertion therefore checks ALL of: {@code ApiException}, {@code CONFLICT}, a message that contains
 * the deposit word (only the substring is pinned, so the sentence may be reworded) — and that
 * NOTHING was written: no {@code sales.delivery_record} row, the line's {@code qty_delivered}
 * unchanged, {@code fulfillment_status}/{@code sales_stage}/{@code payment_status} unchanged, no
 * {@code DELIVERY_RECORDED}/{@code DELIVERY_COMPLETED} event. The fixture is really deliverable, so
 * the deposit is the ONLY thing in the way: {@link #delivery_isAllowed_onceTheDepositIsReceived} is
 * the mirror case (same fixture, deposit received, the delivery lands).
 *
 * <p>Written wrong-way-round, and — like the sibling — hand-wired with {@code new}, so the
 * services' own transaction annotations are inert here: the "nothing written" assertions hold
 * because the guard throws before any write, not because a transaction rolled back.
 */
class DeliveryDepositGateIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Only the substring is pinned, so the sentence may be reworded without breaking the tests. */
    private static final String DEPOSIT_MESSAGE = "มัดจำ";

    private static final String RECORD_PARTIAL_DELIVERY = "RECORD_PARTIAL_DELIVERY";
    private static final String COMPLETE_DELIVERY = "COMPLETE_DELIVERY";

    /** Payment states in which a DEPOSIT deal's deposit has NOT been confirmed. */
    private static final String[] NOT_RECEIVED = {null, "CUSTOMER_CONFIRMED", "DEPOSIT_NOTICE_ISSUED"};
    /** Payment states in which it HAS (the balance is not required). */
    private static final String[] RECEIVED = {"DEPOSIT_PAID", "AWAITING_FINAL_PAYMENT", "FULLY_PAID"};

    /** Whole percent the accepted quotation asks for: a DEPOSIT deal... */
    private static final int DEPOSIT_DEAL = 50;
    /** ...and a 0% deal, which has no deposit step at all. */
    private static final int ZERO_PERCENT_DEAL = 0;

    /** Where the goods come from: every line declared from stock, or goods that reached the warehouse. */
    private enum Source { STOCK, WAREHOUSE }

    /** The two writers: {@code recordPartialDelivery} (4 of 10) and {@code completeDelivery}. */
    private enum Call { PARTIAL, COMPLETE }

    /** Callers who may NOT deliver, for {@link #whoMayDeliver_isCheckedBeforeTheDeposit}. */
    private enum Outsider { OTHER_SALES_REP, IMPORT, ACCOUNT, SALES_MANAGER }

    /** The two delivery writers, whose {@code actions()} list is read: the owning rep and the CEO. */
    private enum Viewer { OWNER, CEO }

    /** A deliverable deal: {@code itemId} is its one line (10 units). */
    private record Deal(long ticketId, long itemId, Source source) {}

    /** What a refusal must leave exactly as it found it. */
    private record Snapshot(String fulfillmentStatus, String salesStage, String paymentStatus,
                            BigDecimal qtyDelivered) {}

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    /** The only role that confirms a deposit, and the one that records receipts. */
    private UserPrincipal accountUser;
    private UserPrincipal salesManagerUser;

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
        ownerId = createEmployee(employees, "เจ้าของดีล ส่งมอบมัดจำ", "delivdep-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น", "delivdep-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "delivdep-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "delivdep-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "delivdep-account@glr.co.th"), "account");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "delivdep-salesmgr@glr.co.th"), "sales_manager");
    }

    // ── the refusals: the deposit is the ONLY thing in the way ───────────────────────────────

    /**
     * The bug (C5): a DEPOSIT deal whose deposit has not been confirmed could be delivered — partially
     * or in full — by its own rep. Every state in which the deposit is not yet confirmed is refused with
     * the deposit message and leaves nothing behind: no payment at all ({@code null}, an unconfirmed
     * order), the customer's confirmation only, and a deposit notice issued but nothing confirmed. One
     * invocation per (payment state, source, call) so each row — and each of the two writers — reports on
     * its own; the mirror case with the deposit confirmed is {@link
     * #delivery_isAllowed_onceTheDepositIsReceived}. (A {@code (0%, null)} row is deliberately NOT
     * written: a deal with no confirmed order at all is a different finding — the order check — and the
     * owner has not ruled on it here.)
     */
    @ParameterizedTest(name = "deposit / {0} / {1} / {2}")
    @MethodSource("notYetReceivedRows")
    void delivery_isRefused_untilTheDepositIsReceived_andWritesNothing(
            String paymentStatus, Source source, Call call) {
        Deal deal = deal(source, DEPOSIT_DEAL, paymentStatus);

        assertRefusedForTheDeposit(deal, () -> perform(call, deal, owner));
    }

    /**
     * The CEO is a delivery writer too ({@code canWriteDelivery}), so the CEO must not be a way round
     * the gate: there is no way round — no waiver, and a recorded receipt is not a confirmation — only
     * ฝ่ายบัญชี's {@code confirmDepositPaid} opens delivery, whoever then delivers.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void ceo_cannotDeliverAroundTheDeposit(Call call) {
        Deal deal = deal(Source.STOCK, DEPOSIT_DEAL, "DEPOSIT_NOTICE_ISSUED");

        assertRefusedForTheDeposit(deal, () -> perform(call, deal, ceoUser));
    }

    /**
     * The advertisement follows the same rule — advertising an action that 409s on click is worse than
     * not offering it. Read as the owner and as the CEO, for both sources: one invocation per (payment
     * state, source, viewer), so each list is observed on its own. The passing half comes first (every
     * deposit-CONFIRMED state advertises both actions to that viewer), so the "does not contain" half
     * cannot pass for an {@code actions()} that offered nothing at all.
     */
    @ParameterizedTest(name = "deposit / {0} / {1} / read as {2}")
    @MethodSource("notYetReceivedBySourceAndViewer")
    void deliveryActions_areNotAdvertised_untilTheDepositIsReceived(
            String paymentStatus, Source source, Viewer who) {
        UserPrincipal viewer = viewer(who);

        for (String received : RECEIVED) {
            Deal ready = deal(source, DEPOSIT_DEAL, received);
            assertThat(actionsOf(ready, viewer))
                .as("%s deal, deposit received (%s), read as %s", source, received, who)
                .contains(RECORD_PARTIAL_DELIVERY, COMPLETE_DELIVERY);
        }

        Deal waiting = deal(source, DEPOSIT_DEAL, paymentStatus);
        assertThat(actionsOf(waiting, viewer))
            .as("%s deal, deposit NOT received (%s), read as %s", source, paymentStatus, who)
            .doesNotContain(RECORD_PARTIAL_DELIVERY, COMPLETE_DELIVERY);
    }

    // ── no way round the confirmation ────────────────────────────────────────────────────────

    /**
     * The deposit SWITCH opens nothing (C1: there is no waiver). On a deposit deal whose order the customer
     * has confirmed, the owning rep tries to waive the deposit — the call may be refused or accepted,
     * either is fine — and afterwards delivery is exactly as closed as before: both writers refused for
     * the deposit with nothing written, and neither delivery action advertised. RED today: delivery has no
     * deposit check at all yet (and a waiver is accepted and flips the deal-level policy).
     *
     * <p>If {@code TicketService#waiveDeposit} is deleted together with the switch, delete this test with
     * it — its only subject is that method.
     */
    @Test
    void delivery_staysRefused_afterTheDepositSwitchIsWaived() {
        Deal deal = deal(Source.STOCK, DEPOSIT_DEAL, "CUSTOMER_CONFIRMED");

        tolerateRefusal(() -> ticketService.waiveDeposit(
            deal.ticketId(), DepositPolicy.WAIVED, "ลูกค้าประจำ ขอยกเว้นมัดจำ", owner));

        assertRefusedForTheDeposit(deal, () -> perform(Call.PARTIAL, deal, owner));
        assertRefusedForTheDeposit(deal, () -> perform(Call.COMPLETE, deal, owner));
        assertThat(actionsOf(deal, owner)).as("actions after the waiver attempt")
            .doesNotContain(RECORD_PARTIAL_DELIVERY, COMPLETE_DELIVERY);
    }

    /**
     * A receipt recorded through the generic {@code recordPayment} (บันทึกรับชำระ) is NOT a deposit
     * confirmation (C2): the deposit is confirmed in exactly one way, ฝ่ายบัญชี's {@code
     * confirmDepositPaid}. On a deposit deal awaiting its deposit ({@code DEPOSIT_NOTICE_ISSUED}), ฝ่ายบัญชี
     * records a DEPOSIT receipt for the full deposit amount — the call may be refused or accepted, either
     * is fine — and afterwards the payment status is still {@code DEPOSIT_NOTICE_ISSUED}, the stage has not
     * moved, and delivery is still refused for the deposit. Only {@code confirmDepositPaid} opens it. RED
     * today: any recorded receipt moves the deal to {@code DEPOSIT_PAID}.
     *
     * <p>The payment status after the confirmation is not pinned — whether the receipt above counts
     * towards it is the implementation's call — only that delivery has opened.
     */
    @Test
    void delivery_opensOnlyThroughConfirmDepositPaid_notThroughARecordedReceipt() {
        Deal deal = deal(Source.STOCK, DEPOSIT_DEAL, "DEPOSIT_NOTICE_ISSUED");
        priceTheLine(deal);
        BigDecimal deposit = tickets.payableAmount(deal.ticketId()).multiply(new BigDecimal("0.50"));
        assertThat(deposit.signum()).as("the fixture needs a payable amount").isPositive();
        Snapshot before = snapshot(deal);

        tolerateRefusal(() -> ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("DEPOSIT", deposit, null, "รับมัดจำ", null, null, false), accountUser));

        assertThat(snapshot(deal).paymentStatus()).as("a receipt does not confirm the deposit")
            .isEqualTo("DEPOSIT_NOTICE_ISSUED");
        assertThat(snapshot(deal).salesStage()).as("the stage has not moved").isEqualTo(before.salesStage());
        assertRefusedForTheDeposit(deal, () -> perform(Call.PARTIAL, deal, owner));
        assertRefusedForTheDeposit(deal, () -> perform(Call.COMPLETE, deal, owner));

        ticketService.confirmDepositPaid(deal.ticketId(), accountUser);

        perform(Call.COMPLETE, deal, owner);
        assertThat(snapshot(deal).fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(snapshot(deal).qtyDelivered()).isEqualByComparingTo("10.00");
    }

    // ── declaring stock stays open ───────────────────────────────────────────────────────────

    /**
     * The rules gate DELIVERING, never DECLARING (C4): the rep with goods on the shelf declares them from
     * stock at any payment state — before the customer has confirmed, on the confirmation, on the
     * notice, and once paid — so the goods are ready the moment the deposit lands. Pinned because a fix
     * that put the deposit check in the wrong place ({@code reserveStock}) would satisfy every refusal
     * test above while taking that away. Each row declares AT its payment state: the ticket is built to
     * that state first, and only then declared.
     */
    @ParameterizedTest(name = "deposit / {0}")
    @MethodSource("everyPaymentState")
    void declaringStock_staysAllowed_whateverTheDepositState(String paymentStatus) {
        long ticketId = newTicket(DEPOSIT_DEAL, paymentStatus);
        long itemId = onlyItemId(ticketId);

        TicketDto declared = declareAllFromStock(ticketId, itemId);

        assertThat(declared.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(declared.items().get(0).qtyFromStock()).isEqualByComparingTo("10.00");
        assertThat(declared.summary().paymentStatus()).as("a declaration never moves the payment track")
            .isEqualTo(paymentStatus);
    }

    // ── the third shape: a deal PARTLY declared from stock (the mixed route) ─────────────────
    //
    // canRecordDelivery is FROM_STOCK || stockAvailable || warehouseAvailable. The STOCK fixture is the
    // first limb and the WAREHOUSE fixture the third; this is the middle one ON ITS OWN: only PART of
    // the order (4 of 10) is declared from stock, so fulfilment stays null — it is NOT FROM_STOCK — and
    // the stage stays ORDER_RECEIVED. That is the real mixed route (the stock part is delivered first
    // while the import is still on its way), and a fix that gated only the other two limbs of the
    // advertisement would pass every test above. See partlyDeclaredDeal for why it is its own fixture.

    /**
     * The bug (C5) on the mixed route: the units already declared from stock were delivered by their own
     * rep with nothing confirmed. Only {@code recordPartialDelivery} is exercised — {@code
     * completeDelivery} deliberately is not: its remainder (6) is not covered by stock, so the service
     * picks WAREHOUSE and the existing "receive the goods into the warehouse first" 409 fires as well,
     * and after the fix which of the two 409s comes first depends on where the deposit check is put, so
     * its message must not be pinned.
     */
    @ParameterizedTest(name = "deposit / {0}")
    @MethodSource("notYetReceivedStates")
    void deliveryFromPartlyDeclaredStock_isRefused_untilTheDepositIsReceived_andWritesNothing(
            String paymentStatus) {
        Deal deal = partlyDeclaredDeal(DEPOSIT_DEAL, paymentStatus);

        assertRefusedForTheDeposit(deal, () -> perform(Call.PARTIAL, deal, owner));
    }

    /**
     * The mirror that proves the partly declared fixture really is deliverable: with the deposit
     * confirmed the same call lands — the 4 declared units are delivered, the deal is {@code
     * PARTIALLY_DELIVERED}, exactly one {@code DELIVERY_RECORDED} event is written and no {@code
     * DELIVERY_COMPLETED}, and the payment status is left where it was. The stage is deliberately not
     * asserted.
     */
    @Test
    void deliveryFromPartlyDeclaredStock_isAllowed_onceTheDepositIsReceived() {
        Deal deal = partlyDeclaredDeal(DEPOSIT_DEAL, "DEPOSIT_PAID");

        perform(Call.PARTIAL, deal, owner);

        Snapshot after = snapshot(deal);
        assertThat(after.qtyDelivered()).isEqualByComparingTo("4.00");
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_DELIVERED);
        assertThat(after.paymentStatus()).as("a delivery never moves the payment track")
            .isEqualTo("DEPOSIT_PAID");
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_RECORDED)).isEqualTo(1L);
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_COMPLETED)).isZero();
    }

    /**
     * The advertisement on the mixed route — the limb ({@code stockAvailable}) a fix could miss. One
     * invocation per (payment state, viewer). The passing half comes first and asserts ONLY {@code
     * RECORD_PARTIAL_DELIVERY}: {@code COMPLETE_DELIVERY} is advertised on this deal too even with the
     * deposit confirmed, although completing it would 409 for the warehouse reason — an existing quirk
     * this class does not pin. Then, with the deposit not confirmed, NEITHER action may be offered.
     */
    @ParameterizedTest(name = "deposit / {0} / read as {1}")
    @MethodSource("notYetReceivedByViewer")
    void deliveryActions_onAPartlyDeclaredDeal_areNotAdvertised_untilTheDepositIsReceived(
            String paymentStatus, Viewer who) {
        UserPrincipal viewer = viewer(who);

        Deal ready = partlyDeclaredDeal(DEPOSIT_DEAL, "DEPOSIT_PAID");
        assertThat(actionsOf(ready, viewer))
            .as("partly declared deal, deposit received, read as %s", who)
            .contains(RECORD_PARTIAL_DELIVERY);

        Deal waiting = partlyDeclaredDeal(DEPOSIT_DEAL, paymentStatus);
        assertThat(actionsOf(waiting, viewer))
            .as("partly declared deal, deposit NOT received (%s), read as %s", paymentStatus, who)
            .doesNotContain(RECORD_PARTIAL_DELIVERY, COMPLETE_DELIVERY);
    }

    // ── the mirror: once the deposit is confirmed the same deal delivers ─────────────────────

    /**
     * The mirror of the refusals, and what proves the fixture really is deliverable. Deposit confirmed
     * ({@code DEPOSIT_PAID}) or anything after it: a partial delivery lands (4 of 10, {@code
     * PARTIALLY_DELIVERED}), then the rest lands ({@code FULLY_DELIVERED}) — from stock and from the
     * warehouse alike.
     */
    @ParameterizedTest(name = "deposit / {0} / {1}")
    @MethodSource("receivedBySource")
    void delivery_isAllowed_onceTheDepositIsReceived(String paymentStatus, Source source) {
        Deal deal = deal(source, DEPOSIT_DEAL, paymentStatus);

        assertDeliversInTwoSteps(deal, paymentStatus);
    }

    /**
     * C5, explicit and separate so nobody "fixes" delivery to wait for the full amount: with ONLY the
     * deposit confirmed ({@code DEPOSIT_PAID}) the deal is delivered in full, the deal reaches
     * DELIVERED, and the payment status is left exactly where it was — the balance is collected
     * afterwards.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Source.class)
    void theBalanceIsNotRequired_deliveryInFullWithOnlyTheDepositReceived(Source source) {
        Deal deal = deal(source, DEPOSIT_DEAL, "DEPOSIT_PAID");

        ticketService.completeDelivery(deal.ticketId(), new CompleteDeliveryRequest(null, null), owner);

        Snapshot after = snapshot(deal);
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(after.salesStage()).isEqualTo(DealStage.DELIVERED);
        assertThat(after.paymentStatus()).as("the balance is still outstanding").isEqualTo("DEPOSIT_PAID");
        assertThat(after.qtyDelivered()).isEqualByComparingTo("10.00");
    }

    /**
     * Guard against over-correcting (C1/C5), over every full-payment term a 0% quotation can carry. A deal
     * whose accepted quotation asks 0% has no deposit step at all, so the confirmed order alone opens
     * delivery (from stock, here) — a fix that started demanding {@code DEPOSIT_PAID} from a 0% deal would
     * strand every one of them. And the owner's answer for C5 is that GOODS NEVER WAIT FOR THE BALANCE,
     * not even when the 0% quotation's term is "100% before delivery" ({@code BEFORE_DELIVERY}; V181's
     * {@code full_payment_term}, whose check constraint allows these three codes and NULL). The name of
     * that term invites exactly the wrong implementation — "nothing leaves until everything is paid" —
     * and nothing else pinned it, so each term is run, plus the term-less row (a quotation that never chose
     * one, which is what this test used to be).
     *
     * <p>The term is stamped on the 0% quotation row BEFORE the order is confirmed: the fixture writes it
     * with the row ({@link #insertQuotation}), never afterwards. Both actions are advertised to the owner
     * and the CEO, and a partial then the rest land with nothing paid. Green today — delivery has no
     * payment gate at all yet — so this is a guard for the fix, not a failing test.
     */
    @ParameterizedTest(name = "0% / full_payment_term {0}")
    @NullSource
    @ValueSource(strings = {"BEFORE_DELIVERY", "ON_DELIVERY", "ON_OR_BEFORE_DELIVERY"})
    void aZeroPercentDeal_deliversOnTheConfirmedOrder_whateverItsFullPaymentTerm(String fullPaymentTerm) {
        Deal deal = deal(Source.STOCK, ZERO_PERCENT_DEAL, fullPaymentTerm, "CUSTOMER_CONFIRMED");
        // Premise (holds today): the term really is on the accepted quotation, written with the row.
        assertThat(fullPaymentTermOf(deal)).as("the accepted quotation's full_payment_term").isEqualTo(fullPaymentTerm);

        for (UserPrincipal viewer : List.of(owner, ceoUser)) {
            assertThat(actionsOf(deal, viewer)).as("0% deal, term %s, read as %s", fullPaymentTerm, viewer.role())
                .contains(RECORD_PARTIAL_DELIVERY, COMPLETE_DELIVERY);
        }
        assertDeliversInTwoSteps(deal, "CUSTOMER_CONFIRMED");
    }

    /**
     * The remaining rows of the table — a 0% deal that is already paid past the confirmation ({@code
     * AWAITING_FINAL_PAYMENT}, {@code FULLY_PAID}) delivers and advertises just the same. Not in the
     * owner's finding; pinned so the whole table is, and so a fix keyed on "which payment state" cannot
     * quietly strand a 0% deal that happens to have paid something.
     */
    @ParameterizedTest(name = "0% / {0}")
    @ValueSource(strings = {"AWAITING_FINAL_PAYMENT", "FULLY_PAID"})
    void aZeroPercentDeal_alreadyPaidPastTheConfirmation_stillDelivers(String paymentStatus) {
        Deal deal = deal(Source.STOCK, ZERO_PERCENT_DEAL, paymentStatus);

        for (UserPrincipal viewer : List.of(owner, ceoUser)) {
            assertThat(actionsOf(deal, viewer)).as("0% / %s, read as %s", paymentStatus, viewer.role())
                .contains(RECORD_PARTIAL_DELIVERY, COMPLETE_DELIVERY);
        }
        assertDeliversInTwoSteps(deal, paymentStatus);
    }

    // ── who may deliver is checked BEFORE the deposit ────────────────────────────────────────

    /**
     * Pins the ORDER of the two checks. On a deal that is not deposit-ready, a sales rep who does not
     * own it, {@code import}, {@code account} and {@code sales_manager} each get 403 — never 409 — on
     * both writers, the message says nothing about the deposit (an unauthorised caller must not learn
     * the deal's payment state), and nothing is written. Without this a fix that put the deposit
     * check first would still pass every other test in the class.
     */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("outsiderCalls")
    void whoMayDeliver_isCheckedBeforeTheDeposit(Outsider who, Call call) {
        Deal deal = deal(Source.STOCK, DEPOSIT_DEAL, "DEPOSIT_NOTICE_ISSUED");
        UserPrincipal outsider = outsider(who);
        Snapshot before = snapshot(deal);

        assertThatThrownBy(() -> perform(call, deal, outsider))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(e.getMessage()).doesNotContain(DEPOSIT_MESSAGE);
            });

        assertNothingDelivered(deal, before);
    }

    // ── a refusal for another reason must not borrow the deposit's name ──────────────────────

    /**
     * Keeps the contract's first assertion honest: the deposit is received, but WAREHOUSE is requested
     * on a stock-only deal whose goods never reached the warehouse — the existing "receive the goods
     * into the warehouse first" 409. It is a CONFLICT like the deposit refusal but it must not mention
     * the deposit, which is exactly what lets {@link #assertRefusedForTheDeposit} tell the two apart.
     */
    @Test
    void aDeliveryRefusedForAnotherReason_doesNotMentionTheDeposit() {
        Deal deal = deal(Source.STOCK, DEPOSIT_DEAL, "DEPOSIT_PAID");
        RecordDeliveryRequest warehouse = new RecordDeliveryRequest("WAREHOUSE", null,
            List.of(new RecordDeliveryRequest.Line(deal.itemId(), new BigDecimal("4.00"))), null);
        Snapshot before = snapshot(deal);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(deal.ticketId(), warehouse, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("WAREHOUSE").doesNotContain(DEPOSIT_MESSAGE);
            });

        assertNothingDelivered(deal, before);
    }

    // ── parameter sources ────────────────────────────────────────────────────────────────────

    /** (payment state, source, call) for every row the deposit must refuse. */
    static Stream<Arguments> notYetReceivedRows() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : NOT_RECEIVED) {
            for (Source source : Source.values()) {
                for (Call call : Call.values()) {
                    rows.add(Arguments.of(status, source, call));
                }
            }
        }
        return rows.stream();
    }

    static Stream<Arguments> notYetReceivedBySourceAndViewer() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : NOT_RECEIVED) {
            for (Source source : Source.values()) {
                for (Viewer who : Viewer.values()) {
                    rows.add(Arguments.of(status, source, who));
                }
            }
        }
        return rows.stream();
    }

    static Stream<Arguments> notYetReceivedStates() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : NOT_RECEIVED) {
            rows.add(Arguments.of(status));
        }
        return rows.stream();
    }

    static Stream<Arguments> notYetReceivedByViewer() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : NOT_RECEIVED) {
            for (Viewer who : Viewer.values()) {
                rows.add(Arguments.of(status, who));
            }
        }
        return rows.stream();
    }

    static Stream<Arguments> everyPaymentState() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : NOT_RECEIVED) {
            rows.add(Arguments.of(status));
        }
        for (String status : RECEIVED) {
            rows.add(Arguments.of(status));
        }
        return rows.stream();
    }

    static Stream<Arguments> receivedBySource() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : RECEIVED) {
            for (Source source : Source.values()) {
                rows.add(Arguments.of(status, source));
            }
        }
        return rows.stream();
    }

    static Stream<Arguments> outsiderCalls() {
        List<Arguments> rows = new ArrayList<>();
        for (Outsider who : Outsider.values()) {
            for (Call call : Call.values()) {
                rows.add(Arguments.of(who, call));
            }
        }
        return rows.stream();
    }

    // ── assertions ───────────────────────────────────────────────────────────────────────────

    /**
     * The deposit-refusal contract: a 409 whose message names the deposit, AND nothing written. A bare
     * {@code CONFLICT} is not enough — see the class Javadoc. If the call does not throw at all the
     * failure says so, with the state the deal was in: the delivery SUCCEEDED.
     */
    private void assertRefusedForTheDeposit(Deal deal, ThrowableAssert.ThrowingCallable call) {
        Snapshot before = snapshot(deal);

        // catchThrowable + a described assertThat: assertThatThrownBy(..) fails with a bare "Expecting
        // code to raise a throwable", which says nothing about which delivery was accepted.
        assertThat(catchThrowable(call))
            .as("a delivery at payment status %s (fulfilment %s) must be REFUSED for the deposit -- the call was accepted",
                before.paymentStatus(), before.fulfillmentStatus())
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains(DEPOSIT_MESSAGE);
            });

        assertNothingDelivered(deal, before);
    }

    /** A refusal leaves the deal exactly as it found it. */
    private void assertNothingDelivered(Deal deal, Snapshot before) {
        Snapshot after = snapshot(deal);
        assertThat(after.fulfillmentStatus()).as("sales.ticket.fulfillment_status")
            .isEqualTo(before.fulfillmentStatus());
        assertThat(after.salesStage()).as("sales.ticket.sales_stage").isEqualTo(before.salesStage());
        assertThat(after.paymentStatus()).as("sales.ticket.payment_status").isEqualTo(before.paymentStatus());
        assertThat(after.qtyDelivered()).as("sales.ticket_item.qty_delivered")
            .isEqualByComparingTo(before.qtyDelivered());
        assertThat(deliveryRecordCount(deal)).as("sales.delivery_record rows").isZero();
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_RECORDED)).as("DELIVERY_RECORDED events").isZero();
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_COMPLETED)).as("DELIVERY_COMPLETED events").isZero();
    }

    /**
     * What an ALLOWED delivery does, whichever state allowed it: a partial (4 of 10) lands and the deal
     * is {@code PARTIALLY_DELIVERED}; completing lands the rest, the deal is {@code FULLY_DELIVERED},
     * one {@code DELIVERY_COMPLETED} event is written, the payment status is untouched, and the stage
     * is {@link #stageAfterCompleting}.
     */
    private void assertDeliversInTwoSteps(Deal deal, String paymentStatus) {
        ticketService.recordPartialDelivery(deal.ticketId(), partial(deal, "4.00"), owner);

        Snapshot partial = snapshot(deal);
        assertThat(partial.qtyDelivered()).as("qty_delivered after the partial").isEqualByComparingTo("4.00");
        assertThat(partial.fulfillmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_DELIVERED);
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_RECORDED)).isEqualTo(1L);
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_COMPLETED)).isZero();

        ticketService.completeDelivery(deal.ticketId(), new CompleteDeliveryRequest(null, null), owner);

        Snapshot complete = snapshot(deal);
        assertThat(complete.qtyDelivered()).as("qty_delivered after completing").isEqualByComparingTo("10.00");
        assertThat(complete.fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(complete.salesStage()).isEqualTo(stageAfterCompleting(paymentStatus));
        assertThat(complete.paymentStatus()).as("a delivery never moves the payment track").isEqualTo(paymentStatus);
        assertThat(eventCount(deal, TicketEventKind.DELIVERY_COMPLETED)).isEqualTo(1L);
    }

    /**
     * Completing a delivery moves the deal to DELIVERED — except when it is also paid IN FULL: paid in
     * full AND delivered closes the deal ({@code maybeAdvanceClosedPaid}), so it ends one stage
     * further, at CLOSED_PAID.
     */
    private static String stageAfterCompleting(String paymentStatus) {
        return "FULLY_PAID".equals(paymentStatus) ? DealStage.CLOSED_PAID : DealStage.DELIVERED;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /**
     * A deliverable deal in the given state: ACTIVE, ONE line of 10 units, the deposit percentage and
     * payment status as asked ({@link #newTicket} builds that part), and a source — so the deposit is the
     * only thing between the owner and a delivery. Created through {@link TicketRepository} as {@link
     * DeliveryAuthzIntegrationTest} does.
     *
     * <ul>
     *   <li>{@link Source#STOCK}: the OWNER declares all 10 units from stock via {@code reserveStock} —
     *       legal at any payment state, since the declaration is always open. That sets {@code
     *       FROM_STOCK}. Whether the declaration also moves the stage before the deposit is confirmed is
     *       finding D3 ({@code StockDeclarationDepositHoldIntegrationTest}); nothing in this class
     *       depends on the stage before a delivery.
     *   <li>{@link Source#WAREHOUSE}: {@code fulfillment_status = GOODS_RECEIVED} and stage
     *       DELIVERY_SCHEDULING, set directly — this state stands for goods that reached the
     *       warehouse. The permanent {@code GOODS_RECEIVED} EVENT is recorded as well, exactly as
     *       {@code markGoodsReceived} does: {@code warehouseDeliveryAvailable} reads the status OR
     *       that event, and the first partial delivery overwrites the status with {@code
     *       PARTIALLY_DELIVERED}, so without the event the completing call after a partial would
     *       409 on "receive the goods into the warehouse first".
     * </ul>
     *
     * The ticket is built to its payment state FIRST and only then declared (or given its goods), so a
     * STOCK row declares AT its state — {@link #declaringStock_staysAllowed_whateverTheDepositState}
     * proves that is allowed everywhere.
     */
    private Deal deal(Source source, int depositPercent, String paymentStatus) {
        return deal(source, depositPercent, null, paymentStatus);
    }

    /** As above, with the 0% quotation's V181 {@code full_payment_term} (null: a row that never chose one). */
    private Deal deal(Source source, int depositPercent, String fullPaymentTerm, String paymentStatus) {
        long ticketId = newTicket(depositPercent, fullPaymentTerm, paymentStatus);
        long itemId = onlyItemId(ticketId);
        if (source == Source.STOCK) {
            declareAllFromStock(ticketId, itemId);
        } else {
            tickets.updateSalesStage(ticketId, DealStage.DELIVERY_SCHEDULING);
            tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.GOODS_RECEIVED);
            tickets.addEvent(ticketId, ownerId, "เจ้าของดีล ส่งมอบมัดจำ", TicketEventKind.GOODS_RECEIVED,
                TicketStatus.DRAFT, TicketStatus.DRAFT, null);
        }
        return new Deal(ticketId, itemId, source);
    }

    /**
     * The ONE place a ticket of this class is built: an ACTIVE ticket with ONE line of 10 units, seeded to
     * ORDER_RECEIVED (the stage floor of a stock declaration), whose accepted quotation asks {@code
     * depositPercent} (50 = a deposit deal, 0 = a 0% deal with no deposit step at all); nothing declared.
     * The quotation row exists BEFORE the customer's order is confirmed, and a non-null {@code
     * paymentStatus} means the owning rep has REALLY confirmed it ({@code
     * TicketService#confirmCustomer}) — so the fixture is right whether the gate reads the quotation when
     * it runs or derives something when the order is confirmed. The deposit switch ({@code
     * deposit_policy}) is never written, and {@code deposit_percent} is never stamped after the
     * confirmation. A payment state past {@code CUSTOMER_CONFIRMED} is stamped afterwards, as the payment
     * track would have left it; a null payment status is an unconfirmed order.
     */
    private long newTicket(int depositPercent, String paymentStatus) {
        return newTicket(depositPercent, null, paymentStatus);
    }

    /**
     * As above, and {@code fullPaymentTerm} is written on the accepted quotation row in the same INSERT — so
     * it exists BEFORE the order is confirmed, exactly like {@code deposit_percent}, and is never stamped
     * after the confirmation.
     */
    private long newTicket(int depositPercent, String fullPaymentTerm, String paymentStatus) {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบส่งมอบ-มัดจำ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("10.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ส่งมอบมัดจำ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        insertQuotation(ticketId, depositPercent, fullPaymentTerm);
        if (paymentStatus != null) {
            jdbc.update("UPDATE sales.ticket SET status = :status WHERE ticket_id = :id",
                Map.of("status", TicketStatus.QUOTATION_ISSUED, "id", ticketId));
            ticketService.confirmCustomer(ticketId, owner);
            if (!PaymentTrack.CUSTOMER_CONFIRMED.equals(paymentStatus)) {
                tickets.updatePaymentStatusUnchecked(ticketId, paymentStatus);
            }
        }
        return ticketId;
    }

    /**
     * The deal's accepted quotation: an approved direct-deal row asking {@code depositPercent} (whole percent)
     * and, for a 0% document, carrying {@code fullPaymentTerm} (V181; null when the row never chose one).
     */
    private void insertQuotation(long ticketId, int depositPercent, String fullPaymentTerm) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version,
                                         origin, deposit_percent, full_payment_term)
            VALUES (:ticketId, :number, :by, 'APPROVED', 1, 'DEAL_DIRECT', :depositPercent, :fullPaymentTerm)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("number", "QTD-DLV-" + ticketId).addValue("by", ownerId)
                .addValue("depositPercent", (short) depositPercent)
                .addValue("fullPaymentTerm", fullPaymentTerm, java.sql.Types.VARCHAR));
    }

    /** The {@code full_payment_term} the deal's accepted quotation row carries. */
    private String fullPaymentTermOf(Deal deal) {
        return jdbc.queryForObject("SELECT full_payment_term FROM sales.quotation WHERE ticket_id = :ticketId",
            Map.of("ticketId", deal.ticketId()), String.class);
    }

    /** Gives the deal's one line (10 units) an approved price, so it has a payable amount: 10 x 1,000 + 7% VAT. */
    private void priceTheLine(Deal deal) {
        jdbc.update("UPDATE sales.ticket_item SET approved_price = 1000 WHERE item_id = :id",
            Map.of("id", deal.itemId()));
    }

    /** The call may be refused (an {@code ApiException}) or accepted; the tests using this pin what must not change either way. */
    private static void tolerateRefusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            // a refusal is as good as an acceptance that opens nothing
        }
    }

    /**
     * The THIRD shape of a deliverable deal: only PART of the order (4 of the 10 units) is declared from
     * stock, by the OWNER through {@code reserveStock}. Fulfilment therefore stays null — it is NOT
     * {@code FROM_STOCK} — so {@code canRecordDelivery} reaches the deal through {@code stockAvailable}
     * alone (declared 4 &gt; delivered 0). That is asserted HERE, in the fixture, so these tests cannot
     * silently become a copy of the {@link Source#STOCK} ones.
     *
     * <p>It is its own fixture rather than a third {@link Source}: completing such a deal is not
     * expressible as "from stock" or "from the warehouse" (the remainder is the warehouse's, and the
     * goods have not arrived), so only the partial delivery of the declared units is. The returned
     * {@link Deal} is {@link Source#STOCK}, which is what {@code partial(deal, ...)} needs to build a
     * STOCK request. The ticket is built to its payment state first and then declared, as in {@link
     * #deal}.
     */
    private Deal partlyDeclaredDeal(int depositPercent, String paymentStatus) {
        long ticketId = newTicket(depositPercent, paymentStatus);
        long itemId = onlyItemId(ticketId);
        TicketDto declared = declareFromStock(ticketId, itemId, "4.00");
        assertThat(declared.summary().fulfillmentStatus())
            .as("a PARTLY declared deal is not FROM_STOCK — it is on the stockAvailable limb").isNull();
        assertThat(declared.items().get(0).qtyFromStock()).isEqualByComparingTo("4.00");
        return new Deal(ticketId, itemId, Source.STOCK);
    }

    /** The OWNER declares all 10 units from stock — {@code reserveStock}'s own gate, no shortcut. */
    private TicketDto declareAllFromStock(long ticketId, long itemId) {
        return declareFromStock(ticketId, itemId, "10.00");
    }

    private TicketDto declareFromStock(long ticketId, long itemId, String qty) {
        return ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(itemId, new BigDecimal(qty), "ทดสอบ"))),
            owner);
    }

    private UserPrincipal viewer(Viewer who) {
        return who == Viewer.OWNER ? owner : ceoUser;
    }

    private void perform(Call call, Deal deal, UserPrincipal actor) {
        if (call == Call.PARTIAL) {
            ticketService.recordPartialDelivery(deal.ticketId(), partial(deal, "4.00"), actor);
        } else {
            ticketService.completeDelivery(deal.ticketId(), new CompleteDeliveryRequest(null, null), actor);
        }
    }

    private static RecordDeliveryRequest partial(Deal deal, String qty) {
        return new RecordDeliveryRequest(deal.source().name(), null,
            List.of(new RecordDeliveryRequest.Line(deal.itemId(), new BigDecimal(qty))), null);
    }

    private UserPrincipal outsider(Outsider who) {
        return switch (who) {
            case OTHER_SALES_REP -> otherSalesRep;
            case IMPORT -> importUser;
            case ACCOUNT -> accountUser;
            case SALES_MANAGER -> salesManagerUser;
        };
    }

    private List<String> actionsOf(Deal deal, UserPrincipal viewer) {
        return ticketService.actions(deal.ticketId(), viewer).availableActions().stream()
            .map(TicketResponses.TicketActionDto::action)
            .toList();
    }

    private Snapshot snapshot(Deal deal) {
        return jdbc.queryForObject("""
            SELECT t.fulfillment_status, t.sales_stage, t.payment_status, i.qty_delivered
              FROM sales.ticket t
              JOIN sales.ticket_item i ON i.ticket_id = t.ticket_id
             WHERE t.ticket_id = :ticketId AND i.item_id = :itemId
            """,
            Map.of("ticketId", deal.ticketId(), "itemId", deal.itemId()),
            (rs, rowNum) -> new Snapshot(rs.getString("fulfillment_status"), rs.getString("sales_stage"),
                rs.getString("payment_status"), rs.getBigDecimal("qty_delivered")));
    }

    private long deliveryRecordCount(Deal deal) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_record WHERE ticket_id = :ticketId",
            Map.of("ticketId", deal.ticketId()), Long.class);
    }

    private long eventCount(Deal deal, String kind) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :ticketId AND kind = :kind",
            Map.of("ticketId", deal.ticketId(), "kind", kind), Long.class);
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
        return new UserPrincipal(employeeId, role + "-delivdep@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
