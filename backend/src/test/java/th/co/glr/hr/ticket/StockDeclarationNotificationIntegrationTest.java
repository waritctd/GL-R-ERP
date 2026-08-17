package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationDto;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Owner ruling 2026-08-13 — <b>the supervision half of "Sales declares, Import can correct."</b>
 * PR #706 let the deal owner declare {@code qty_from_stock}; the stated mitigation for an
 * uncorroborated number feeding that same rep's STOCK_BONUS was "Import can correct it", and
 * nothing told anyone to look. {@link TicketService#reserveStock} now raises a notification to
 * ฝ่ายขาย's ผู้จัดการ. This class is the real-DB evidence for all three things that can go wrong
 * with it, none of which Mockito can reach:
 *
 * <ol>
 *   <li><b>Recipient resolution</b> — {@code notifyByRole("sales_manager", ...)} is a {@code WHERE}
 *       clause over {@code hr.employee}/{@code hr.division}/{@code hr.position}, so only real rows
 *       can say who it selects. A mocked repository "passes" while the SQL picks the whole ฝ่าย, or
 *       nobody. Written wrong-way-round: the assertions that matter are the six people who must
 *       NOT receive it.</li>
 *   <li><b>Rollback</b> — {@code @Transactional} is inert across this suite (services hand-wired
 *       with {@code new}, no Spring context, no AOP), so a test that merely calls the service
 *       proves nothing about transactional behaviour. The rollback case below goes through {@link
 *       AbstractPostgresIntegrationTest#transactional} so the annotation itself supplies the
 *       transaction, and is built so it cannot pass vacuously — see its own comment.</li>
 *   <li><b>Trigger</b> — an import/ceo correction must stay silent. That is the control working,
 *       not the risk, and notifying on it would bury the case that matters.</li>
 * </ol>
 *
 * <p><b>Commission math is untouched and is not exercised here.</b> The notification quotes two
 * quantities read off the deal; {@code CommissionRepository} is not on this class's wiring at all.
 * {@code StockDeclarationAuthzIntegrationTest} owns the "declaration really does land in the real
 * STOCK_BONUS input" loop, through the real, unmodified commission SQL.
 *
 * <p><b>MUTATION-CHECK RECORD</b> — see this class's PR body; each mutation was run with
 * {@code rm -rf backend/target/classes} first and restored to a byte-identical file after.
 */
class StockDeclarationNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The one used by the notification; anything else must not be selected. */
    private static final String SALES_DIVISION = "SA";
    private static final String IMPORT_DIVISION = "PCIM";
    private static final String EXECUTIVE_DIVISION = "MD";

    private TicketRepository tickets;
    private NotificationRepository notifications;
    private TicketService ticketService;

    private long ownerId;
    private long salesManagerId;
    private long assistantSalesManagerId;
    private long inactiveSalesManagerId;
    private long otherRepId;
    private long salesEmployeeWithNoPositionId;
    private long importManagerId;
    private long ceoId;

    private UserPrincipal owner;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        ticketService = newTicketService(tickets, notifications);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        // The declaring rep. A plain ฝ่ายขาย position, so DivisionAccessPolicy would derive
        // "sales" — not "sales_manager" — for them, which is what makes them a non-recipient of
        // their own notification.
        ownerId = employee(employees, "เจ้าของดีล ทดสอบ", "notif-owner", SALES_DIVISION, "พนักงานขาย");
        owner = principal(ownerId, "เจ้าของดีล ทดสอบ", "sales");

        // The intended recipient, and the assistant that DivisionAccessPolicy documents as also
        // being a sales_manager ("ผู้ช่วยผู้จัดการ" contains "ผู้จัดการ").
        salesManagerId = employee(employees, "ผู้จัดการ ฝ่ายขาย", "notif-sm", SALES_DIVISION, "ผู้จัดการฝ่ายขาย");
        assistantSalesManagerId = employee(employees, "ผู้ช่วย ฝ่ายขาย", "notif-asm",
            SALES_DIVISION, "ผู้ช่วยผู้จัดการฝ่ายขาย");

        // Every category that must NOT be selected.
        inactiveSalesManagerId = employee(employees, "อดีตผู้จัดการ ฝ่ายขาย", "notif-exsm",
            SALES_DIVISION, "ผู้จัดการฝ่ายขาย");
        deactivate(inactiveSalesManagerId);
        otherRepId = employee(employees, "พนักงานขาย อื่น", "notif-rep2", SALES_DIVISION, "พนักงานขาย");
        salesEmployeeWithNoPositionId = employee(employees, "ไม่มีตำแหน่ง ฝ่ายขาย", "notif-nopos",
            SALES_DIVISION, "พนักงานขาย");
        clearPosition(salesEmployeeWithNoPositionId);
        importManagerId = employee(employees, "ผู้จัดการ ฝ่ายจัดซื้อ", "notif-im",
            IMPORT_DIVISION, "ผู้จัดการฝ่ายจัดซื้อต่างประเทศ");
        ceoId = employee(employees, "กรรมการ ผู้จัดการ", "notif-ceo", EXECUTIVE_DIVISION, "กรรมการผู้จัดการ");

        importUser = principal(employee(employees, "ฝ่ายนำเข้า ทดสอบ", "notif-import",
            IMPORT_DIVISION, "เจ้าหน้าที่จัดซื้อ"), "ฝ่ายนำเข้า ทดสอบ", "import");
        ceoUser = principal(ceoId, "กรรมการ ผู้จัดการ", "ceo");
    }

    // ── recipient resolution: the refusals are the evidence ──────────────────

    /**
     * The whole point of not reusing {@code notifyByRole("sales")}: that branch selects the entire
     * ฝ่าย, which would hand the notification to the rep who just declared and to every one of
     * their peers. Six people are pinned as non-recipients here, each for a different reason, and
     * the set assertion catches anyone this test did not think to name.
     */
    @Test
    void onlyActiveSalesManagersAreNotified_andTheDeclaringRepIsNot() {
        long ticketId = createTicketWithOneItem();

        ticketService.reserveStock(ticketId, declare(onlyItemId(ticketId), "40.00"), owner);

        assertThat(everyRecipientOf(TicketEventKind.STOCK_RESERVED))
            .describedAs("the sales manager and the assistant manager, and nobody else at all")
            .containsExactlyInAnyOrder(salesManagerId, assistantSalesManagerId);

        // Spelled out one by one, so a failure names the category that leaked rather than a set diff.
        assertThat(inbox(ownerId))
            .describedAs("the rep who declared must not be told about their own declaration")
            .isEmpty();
        assertThat(inbox(otherRepId))
            .describedAs("a peer rep has no supervisory interest in another rep's bonus input")
            .isEmpty();
        assertThat(inbox(inactiveSalesManagerId))
            .describedAs("a departed manager (is_active = FALSE) must be excluded, as for every other role")
            .isEmpty();
        assertThat(inbox(salesEmployeeWithNoPositionId))
            .describedAs("a NULL position_id must not resolve to a manager via the LEFT JOIN")
            .isEmpty();
        assertThat(inbox(importManagerId))
            .describedAs("a ผู้จัดการ of a DIFFERENT ฝ่าย — the position match must not escape ฝ่ายขาย")
            .isEmpty();
        assertThat(inbox(ceoId))
            .describedAs("the ruling is 'notify the sales manager', not 'notify up the whole chain'")
            .isEmpty();
    }

    /**
     * The predicate behind {@code notifyByRole("sales_manager", ...)} is a hand-copy of
     * {@code CommissionRepository#findSalesManagerApproverEmployeeIds} — deliberately, so that "who
     * supervises a rep's commission INPUT" and "who signs a rep's commission OFF" cannot become two
     * different people. A hand-copy with nothing checking it is the unguarded-mirror family this
     * repo keeps getting bitten by (#714, PR #717), so this runs both against the same rows and
     * requires them to agree. Read-only: {@code CommissionRepository} is not modified by this
     * change and the commission math is not exercised.
     *
     * <p>Known and deliberate divergence from {@link th.co.glr.hr.auth.DivisionAccessPolicy#roleFor},
     * which is a third answer to a similar question: {@code roleFor} tests executives FIRST, so a ฝ่ายขาย
     * member whose position contains "กรรมการ" logs in as {@code ceo} while both predicates here
     * would still select them. Inherited from the commission predicate on purpose — agreeing with
     * the approver list matters more than agreeing with the login derivation, and no such person
     * exists in the personnel data. Do not "fix" one of the two in isolation.
     */
    @Test
    void theNotifiedSetMatchesTheCommissionApproverPredicateItMirrors() {
        long ticketId = createTicketWithOneItem();

        ticketService.reserveStock(ticketId, declare(onlyItemId(ticketId), "40.00"), owner);

        List<Long> commissionApprovers =
            new th.co.glr.hr.commission.CommissionRepository(jdbc).findSalesManagerApproverEmployeeIds();
        assertThat(commissionApprovers)
            .describedAs("guard on the guard: an empty fixture would make the comparison vacuous")
            .isNotEmpty();
        assertThat(everyRecipientOf(TicketEventKind.STOCK_RESERVED))
            .containsExactlyInAnyOrderElementsOf(commissionApprovers);
    }

    // ── content: enough to act on without opening the ticket ─────────────────

    @Test
    void theNotificationNamesTheDealTheRepAndTheDeclaredQuantities() {
        long ticketId = createTicketWithOneItem();
        String code = tickets.findById(ticketId).orElseThrow().summary().code();

        ticketService.reserveStock(ticketId, declare(onlyItemId(ticketId), "40.00"), owner);

        NotificationDto row = onlyNotification(salesManagerId);
        assertThat(row.type()).isEqualTo(TicketEventKind.STOCK_RESERVED);
        assertThat(row.title())
            .describedAs("must not fall through to the generic 'อัปเดตสถานะคำขอราคา'")
            .isEqualTo("พนักงานขายประกาศสินค้าจากสต็อกเอง");
        assertThat(row.link()).isEqualTo("/tickets/" + ticketId);
        assertThat(row.message())
            .contains(code)                       // which deal
            .contains("ลูกค้าทดสอบ")                 // whose deal
            .contains("เจ้าของดีล ทดสอบ")             // which rep
            .contains("40/100")                   // how much, deal-wide
            .contains("STOCK_BONUS");             // why it is worth reading
        assertThat(row.message())
            .describedAs("a partial declaration must not claim the deal left the import axis")
            .doesNotContain("ครบทั้งดีล");
    }

    /**
     * Full coverage is the case that also reroutes the deal (FROM_STOCK, skipping the entire import
     * journey), so the message has to say so — that is the difference between "worth a look
     * eventually" and "this deal just stopped being an import".
     */
    @Test
    void fullCoverageIsCalledOutBecauseItReroutesTheDeal() {
        long ticketId = createTicketWithOneItem();

        ticketService.reserveStock(ticketId, declare(onlyItemId(ticketId), "100.00"), owner);

        assertThat(onlyNotification(salesManagerId).message())
            .contains("100/100")
            .contains("ครบทั้งดีล");
        assertThat(fulfillmentStatus(ticketId))
            .describedAs("precondition for the claim the message makes")
            .isEqualTo(FulfilmentStatus.FROM_STOCK);
    }

    /**
     * The per-request total the STOCK_RESERVED event records is NOT the number the manager needs:
     * a request touching one line of two understates the coverage they are being asked to judge.
     * Line A is declared first, then line B alone is declared — the notification for the second
     * call must report the deal-wide 100/160, not the 60 that call carried.
     */
    @Test
    void theQuantitiesAreDealWideNotPerRequest() {
        long ticketId = createTicketWithTwoItems();
        List<Long> itemIds = itemIds(ticketId);

        ticketService.reserveStock(ticketId, declare(itemIds.get(0), "40.00"), owner);
        ticketService.reserveStock(ticketId, declare(itemIds.get(1), "60.00"), owner);

        List<NotificationDto> inbox = inbox(salesManagerId);
        assertThat(inbox).hasSize(2);
        // findByEmployeeId is ORDER BY created_at DESC, so element 0 is the second declaration.
        assertThat(inbox.get(0).message()).contains("100/160").doesNotContain("ครบทั้งดีล");
        assertThat(inbox.get(1).message()).contains("40/160");
    }

    // ── trigger: only the rep's own declaration ──────────────────────────────

    /**
     * Import and the CEO hold the identical ability and are the correction path — them declaring is
     * the mitigation working, not the risk. Notifying on it would double the volume with the half
     * that needs no supervision. Pinned rather than left implicit, because "notify on every
     * declaration" is the obvious-looking change a future reader might make by accident.
     */
    @Test
    void anImportOrCeoCorrectionNotifiesNobody() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        ticketService.reserveStock(ticketId, declare(itemId, "25.00"), importUser);
        ticketService.reserveStock(ticketId, declare(itemId, "30.00"), ceoUser);

        assertThat(everyRecipientOf(TicketEventKind.STOCK_RESERVED)).isEmpty();
        assertThat(qtyFromStock(itemId))
            .describedAs("precondition: those corrections really did land, so the silence is a choice")
            .isEqualByComparingTo("30.00");
    }

    // ── rollback ─────────────────────────────────────────────────────────────

    /**
     * The defect class PR #708 fixed for files: a side effect that outlives the transaction that
     * caused it. Here it would be a notification telling a manager to review a declaration that
     * never happened.
     *
     * <p><b>Why this cannot pass vacuously.</b> The failure is armed BY the notification: the
     * {@code TicketRepository} spy throws from {@code findById} only once {@code notifyByRole} has
     * been observed, and the only {@code findById} after that point is {@code reserveStock}'s
     * closing re-read. So if the notification were never issued, nothing would throw,
     * {@code reserveStock} would return normally and {@code assertThatThrownBy} would fail. "No
     * notification row" can therefore only be reached through a path that really did insert one.
     * The spy delegates to the real repository, so the real {@code INSERT} really runs.
     *
     * <p>The declaration itself is asserted rolled back too. Without that, "no notification row"
     * would also be satisfied by an insert that silently matched nobody — a green for the wrong
     * reason.
     *
     * <p>Called through {@link AbstractPostgresIntegrationTest#transactional}, so the transaction
     * comes from {@code reserveStock}'s own {@code @Transactional} and not from the test. Deleting
     * that annotation turns this test red (both the row and the declaration survive), which is what
     * makes it evidence about the annotation rather than about {@code transactionTemplate}.
     */
    @Test
    void aRolledBackDeclarationTakesItsNotificationWithIt() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        AtomicBoolean notified = new AtomicBoolean(false);
        NotificationRepository notificationSpy = spy(new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();  // the real INSERT really happens
            notified.set(true);
            return result;
        }).when(notificationSpy).notifyByRole(eq("sales_manager"), anyLong(), anyString(), anyString());

        TicketRepository ticketSpy = spy(new TicketRepository(jdbc));
        doAnswer(invocation -> {
            if (notified.get()) {
                throw new IllegalStateException("simulated failure after the notification was written");
            }
            return invocation.callRealMethod();
        }).when(ticketSpy).findById(anyLong());

        TicketService service = transactional(newTicketService(ticketSpy, notificationSpy));

        assertThatThrownBy(() -> service.reserveStock(ticketId, declare(itemId, "40.00"), owner))
            .describedAs("the failure is armed by the notification, so reaching it proves one was issued")
            .isInstanceOf(IllegalStateException.class);

        verify(notificationSpy).notifyByRole(eq("sales_manager"), eq(ticketId),
            eq(TicketEventKind.STOCK_RESERVED), anyString());
        assertThat(inbox(salesManagerId))
            .describedAs("the notification was inserted and then rolled back with the declaration")
            .isEmpty();
        assertThat(inbox(assistantSalesManagerId)).isEmpty();
        assertThat(qtyFromStock(itemId))
            .describedAs("the declaration itself rolled back — otherwise the empty inbox above "
                + "would only mean the INSERT matched nobody")
            .isEqualByComparingTo("0.00");
        assertThat(stockReservedEvents(ticketId)).isZero();
    }

    /** The commit side of the same wiring: with nothing failing, both halves persist together. */
    @Test
    void aCommittedDeclarationKeepsItsNotification() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);
        NotificationRepository notificationSpy = spy(new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP));
        TicketService service = transactional(newTicketService(new TicketRepository(jdbc), notificationSpy));

        service.reserveStock(ticketId, declare(itemId, "40.00"), owner);

        verify(notificationSpy).notifyByRole(eq("sales_manager"), eq(ticketId),
            eq(TicketEventKind.STOCK_RESERVED), anyString());
        verify(notificationSpy, never()).notifyByRole(eq("sales"), anyLong(), anyString(), anyString());
        assertThat(inbox(salesManagerId)).hasSize(1);
        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("40.00");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TicketService newTicketService(TicketRepository ticketRepository,
                                           NotificationRepository notificationRepository) {
        // PricingRequestService is mocked exactly as in StockDeclarationAuthzIntegrationTest:
        // reserveStock never calls it. The stub exists only so a future edit routing through
        // markLost/cancel cannot NPE on Mockito's default null.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        return new TicketService(ticketRepository, notificationRepository,
            new ObjectMapper(), new CustomerRepository(jdbc), new QuotationRenderer(), pricingRequests);
    }

    private static StockReservationRequest declare(long itemId, String qtyFromStock) {
        return new StockReservationRequest(List.of(
            new StockReservationRequest.Line(itemId, new BigDecimal(qtyFromStock), "มีของในสต็อก")));
    }

    /** A deal parked at the {@link DealStage#ORDER_RECEIVED} floor a declaration must clear. */
    private long createTicketWithOneItem() {
        return createTicket(List.of(item("100.00")));
    }

    private long createTicketWithTwoItems() {
        return createTicket(List.of(item("100.00"), item("60.00")));
    }

    private long createTicket(List<TicketItemRequest> items) {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบแจ้งเตือนสต็อก", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, items);
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ทดสอบ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private static TicketItemRequest item(String qty) {
        return new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
            new BigDecimal(qty), null, "PIECE", null, null, null, null, "THB");
    }

    private long onlyItemId(long ticketId) {
        List<Long> ids = itemIds(ticketId);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    /** Ordered as {@code TicketRepository} returns them, so index 0/1 match the creation order. */
    private List<Long> itemIds(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().items().stream().map(TicketItemDto::id).toList();
    }

    private List<NotificationDto> inbox(long employeeId) {
        return notifications.findByEmployeeId(employeeId);
    }

    private NotificationDto onlyNotification(long employeeId) {
        List<NotificationDto> rows = inbox(employeeId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** Every employee holding a notification of this type — the set assertion's raw material. */
    private List<Long> everyRecipientOf(String type) {
        return jdbc.queryForList("""
            SELECT employee_id FROM hr.notification WHERE type = :type ORDER BY employee_id
            """, Map.of("type", type), Long.class);
    }

    private BigDecimal qtyFromStock(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private String fulfillmentStatus(long ticketId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), String.class);
    }

    private int stockReservedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :ticketId AND kind = :kind
            """, Map.of("ticketId", ticketId, "kind", TicketEventKind.STOCK_RESERVED), Integer.class);
    }

    private long employee(EmployeeRepository employees, String name, String emailPrefix,
                          String divisionCode, String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            emailPrefix + "@glr.co.th", null, divisionCode, divisionCode + " Division", "แผนก" + divisionCode,
            positionTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private void deactivate(long employeeId) {
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id",
            new MapSqlParameterSource().addValue("id", employeeId));
    }

    private void clearPosition(long employeeId) {
        jdbc.update("UPDATE hr.employee SET position_id = NULL WHERE employee_id = :id",
            new MapSqlParameterSource().addValue("id", employeeId));
    }

    /**
     * {@code name} is the real employee name, not the role: the notification quotes
     * {@code actor.name()}, and a principal whose name were "sales" would let the message
     * assertions pass while saying nothing about which rep declared.
     */
    private static UserPrincipal principal(long employeeId, String name, String role) {
        return new UserPrincipal(employeeId, role + "-notif@glr.co.th", name, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
