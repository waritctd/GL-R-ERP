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
import org.springframework.http.HttpStatus;
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
 * WHO may record a delivery — stages 13–14 (ส่งมอบสินค้า).
 *
 * <p><b>The change under test.</b> Owner ruling 2026-08-17: stage 12 (PROCUREMENT) belongs to
 * Import, stages 13–14 belong to Sales. {@code recordPartialDelivery}/{@code completeDelivery} were
 * gated {@code requireRole(actor, FULFILMENT_ROLES)} = {import, ceo}, so the deal's own rep could
 * not record that goods went out. The gate is now {@link TicketService}'s
 * {@code canWriteDelivery} — import/CEO, or the {@code sales} rep who owns the deal.
 *
 * <p><b>Additive, and that is asserted.</b> Import and CEO KEEP access; the owning rep gains it.
 * Half the cases below exist to prove nothing was taken away, because a refusal-only suite would
 * pass just as happily if the change had been a transfer.
 *
 * <p><b>Written wrong-way-round.</b> The tests that matter are the refusals, and each re-reads
 * {@code sales.ticket_item.qty_delivered} out of Postgres afterwards to prove nothing moved.
 * "The owner can deliver their own deal" is necessary but is not the evidence.
 *
 * <p><b>{@code sales_manager} is refused, deliberately.</b> {@code ROLE_PERMISSIONS} in
 * {@code frontend/src/api/routes.js} records that it "is read+comment oversight ONLY … it must never
 * be added to" the write permissions. Note the asymmetry that creates:
 * {@code requireStageWriteAccess} DOES let sales_manager set DELIVERY_SCHEDULING/DELIVERED by hand,
 * because that is the manual stage-correction fallback. Oversight may correct the pipeline; it may
 * not record that goods went out. Without the test below that distinction is just a comment.
 *
 * <p>Per CLAUDE.md this is the required real-DB evidence: a mocked {@link TicketRepository} would
 * pass while the {@code UPDATE} did something else. Mirrors {@link StockDeclarationAuthzIntegrationTest},
 * which pins the identical predicate for {@code reserveStock} — the two share
 * {@code isFulfilmentOrOwningRep}, so a mutation to that one expression should turn cases red in
 * BOTH classes.
 *
 * <p>Note the suite-wide trap on {@link AbstractPostgresIntegrationTest}: services are hand-wired
 * with {@code new}, so {@code @Transactional} is inert and no rollback is exercised. The "unmoved"
 * assertions hold because the guard throws before any write.
 */
class DeliveryAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal hrUser;
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
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ส่งมอบ", "deliv-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น", "deliv-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "deliv-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "deliv-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "deliv-account@glr.co.th"), "account");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล", "deliv-hr@glr.co.th"), "hr");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "deliv-salesmgr@glr.co.th"), "sales_manager");
    }

    // ── the refusals: these are the evidence ─────────────────────────────────────────────────

    /**
     * The case the widened gate must NOT open. {@code otherSalesRep} holds the same {@code sales}
     * role as the owner, so the only thing between them and another rep's deal is the per-row
     * ownership test.
     */
    @Test
    void salesRepWhoDoesNotOwnTheDeal_isRefused_andNothingIsDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(
                ticketId, deliver(itemId, "3.00"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    /**
     * The rule this exists for: sales_manager is read+comment oversight and must never gain a write.
     * It is the one refusal a reader might expect to be a grant, since {@code requireStageWriteAccess}
     * lets it set these very stages by hand.
     */
    @Test
    void salesManager_isRefused_becauseOversightIsReadAndCommentOnly() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(
                ticketId, deliver(itemId, "3.00"), salesManagerUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    @Test
    void account_isRefused_andNothingIsDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(
                ticketId, deliver(itemId, "3.00"), accountUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    @Test
    void hr_isRefused_andNothingIsDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(
                ticketId, deliver(itemId, "3.00"), hrUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    /** completeDelivery carries its own gate, so it needs its own refusal — not an inference. */
    @Test
    void completeDelivery_salesRepWhoDoesNotOwnTheDeal_isRefused_andNothingIsDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.completeDelivery(
                ticketId, new CompleteDeliveryRequest(null, null), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    @Test
    void completeDelivery_salesManager_isRefused() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.completeDelivery(
                ticketId, new CompleteDeliveryRequest(null, null), salesManagerUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    // ── the grants: these prove it is a WIDENING, not a transfer ─────────────────────────────

    /** The new capability, and the point of the change. */
    @Test
    void dealOwner_recordsDelivery_andTheQuantityLands() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        ticketService.recordPartialDelivery(ticketId, deliver(itemId, "4.00"), owner);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("4.00");
    }

    @Test
    void dealOwner_completesDelivery_andTheDealReachesFullyDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest(null, null), owner);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("10.00");
        assertThat(tickets.findById(ticketId).orElseThrow().summary().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
    }

    /**
     * Import KEEPS it. If this ever goes red the change has silently become a transfer, which is a
     * different decision from the one the owner made.
     */
    @Test
    void import_stillRecordsDelivery_theChangeIsAdditive() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        ticketService.recordPartialDelivery(ticketId, deliver(itemId, "2.00"), importUser);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("2.00");
    }

    @Test
    void ceo_stillRecordsDelivery() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        ticketService.recordPartialDelivery(ticketId, deliver(itemId, "1.00"), ceoUser);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("1.00");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /**
     * A deal with 10 units, all declared from stock, so a delivery is actually possible —
     * {@code canRecordDelivery} needs remaining quantity AND a source (FROM_STOCK, stock on the
     * lines, or a received warehouse quantity). Declared by the OWNER, which is itself only legal
     * because {@code canDeclareStockCoverage} shares this branch's predicate; using import here
     * instead would work equally well and is why that is not what is under test.
     *
     * <p>Seeded to ORDER_RECEIVED through the real {@code updateSalesStage}: {@code reserveStock} has
     * a stage floor there, and {@code StageFactGateIntegrationTest} owns that floor's coverage.
     */
    private long deliverableDeal() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบส่งมอบ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("10.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ส่งมอบ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(itemId, new BigDecimal("10.00"), "ทดสอบ"))),
            owner);
        return ticketId;
    }

    private static RecordDeliveryRequest deliver(long itemId, String qty) {
        return new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal(qty))), null);
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private BigDecimal qtyDelivered(long itemId) {
        return jdbc.queryForObject("SELECT qty_delivered FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-deliv@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
