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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.InvoiceCalculation;
import th.co.glr.hr.commission.SubmitCommissionRequest;
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
 * Owner decision 2026-09-28 — <b>"Sales declares; the CEO may correct/override; Import is
 * excluded."</b> S18 (จัดตารางส่งมอบ / stock coverage) belongs to Sales, the same direction V184 took
 * delivery recording (S19). This SUPERSEDES the 2026-08-13 "Sales declares, Import can correct"
 * ruling this suite was originally written for — import no longer declares OR corrects stock
 * coverage; only the owning sales rep declares, with the CEO as oversight/override.
 *
 * <p>Real-DB enforcement coverage for {@link TicketService#reserveStock}'s gate, which is the
 * required evidence under {@code CLAUDE.md}'s "permission changes must ship evidence": a role gate
 * needs a real-DB integration test through the real service AND the real repository, because
 * Mockito cannot reach this — a mocked {@link TicketRepository} passes happily while the
 * {@code UPDATE} does something else entirely. {@link TicketServiceTest}'s companion cases pin which
 * branch is chosen; only these prove the decision survives into the SQL.
 *
 * <p><b>Written wrong-way-round on purpose.</b> The tests that matter are the refusals, and each
 * one re-reads {@code sales.ticket_item.qty_from_stock} (plus the deal's fulfilment status, stage
 * and event log) straight out of Postgres afterwards to prove nothing moved. "The owner can
 * declare their own deal" is necessary but is not the evidence. {@code importRole_isRefused_...} is
 * the case the 2026-09-28 narrowing exists for: import used to be able to correct here and now must
 * not, re-read from Postgres to prove its number never landed.
 *
 * <p>Why {@code qty_from_stock} is worth this much care: it is the sole input to the owning rep's
 * own STOCK_BONUS ({@code CommissionRepository#sumActiveStockActualReceived} =
 * {@code SUM(actual_received × SUM(qty_from_stock)/SUM(qty))}), so a rep who could write another
 * rep's row — or a role with no business here at all — would be writing someone's pay. The last
 * test closes that loop through the real, unmodified commission SQL.
 *
 * <p><b>Mutation-check.</b> The gate has two layers, each with a test that fails alone if the layer
 * is deleted: the coarse {@code requireRole(actor, STOCK_DECLARATION_ROLES)} is pinned by
 * {@code roleThatCanNeverDeclare_isRefusedBeforeTheTicketIsRead} (403-not-404, and import is now in
 * that set), and the per-row {@code canDeclareStockCoverage} by
 * {@code salesRepWhoDoesNotOwnTheDeal_...} / {@code importRole_isRefused_...} /
 * {@code refusedDeclaration_...}. Reverting {@code canDeclareStockCoverage} to the pre-change
 * {@code FULFILMENT_ROLES}-based gate turns {@code importRole_isRefused_andTheRowIsUnmoved} red
 * (import would be granted again) while the owner/CEO grants stay green — that opposition is what
 * proves the suite exercises the NARROWING and not merely the refusals. (Historical: the original
 * 2026-08-13 widening's own three-mutation record — per-row check, coarse filter, and a revert to
 * {@code FULFILMENT_ROLES} — is in this file's git history at the commit before the S18 change.)
 * This class's integration tests run against real Postgres in CI (Testcontainers /
 * {@code TEST_DB_URL}); they were not run on the authoring machine, which had neither.
 *
 * <p>Mirrors {@code th.co.glr.hr.attendance.AttendanceScopeIntegrationTest} and
 * {@link DealTrackingAndActivityIntegrationTest}. Note the suite-wide trap documented on {@link
 * AbstractPostgresIntegrationTest}: services are hand-wired with {@code new}, so {@code
 * @Transactional} is inert here and no rollback is ever exercised — nothing below asserts one.
 * The "unmoved" assertions hold because the guard throws before any write, not because a
 * transaction rolled back.
 */
class StockDeclarationAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private CommissionRepository commissions;

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
        commissions = new CommissionRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // PricingRequestService is mocked exactly as in DealTrackingAndActivityIntegrationTest:
        // reserveStock never calls it. The stub on cancelOpenForTicket exists only so Mockito's
        // default null return cannot NPE if a future edit routes through markLost/cancel. The
        // two things under test here — TicketService's gate and TicketRepository's UPDATE — are
        // both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "stock-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น", "stock-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "stock-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "stock-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "stock-account@glr.co.th"), "account");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล", "stock-hr@glr.co.th"), "hr");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "stock-salesmgr@glr.co.th"), "sales_manager");
    }

    // ── the refusals: these are the evidence ─────────────────────────────────

    /**
     * The case the widened gate must NOT open. {@code otherSalesRep} holds the same {@code sales}
     * role as the owner and passes the coarse role pre-filter — the only thing standing between
     * them and another rep's commission input is the per-row ownership test.
     */
    @Test
    void salesRepWhoDoesNotOwnTheDeal_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "100.00"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertNothingMoved(ticketId, itemId);
    }

    /**
     * Neither ownership nor a fulfilment role. {@code sales_manager} is in this list deliberately:
     * {@code requireDealOwnership} grants it elsewhere, but the ruling is "Sales declares", and
     * this write lands in the OWNING rep's STOCK_BONUS input — so oversight is refused here, and
     * that exclusion is pinned rather than left to be re-litigated by a future reader.
     */
    @Test
    void roleWithNeitherOwnershipNorFulfilment_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        for (UserPrincipal stranger : List.of(accountUser, hrUser, salesManagerUser)) {
            assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "100.00"), stranger))
                .describedAs("role %s must not be able to declare stock coverage", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertNothingMoved(ticketId, itemId);
    }

    /**
     * The coarse pre-filter's own reason to exist, and the only test that can fail if it is
     * deleted (the per-row check below it already refuses these roles on a deal that exists).
     * A role that can never declare must be refused BEFORE the ticket is read, so widening this
     * endpoint cannot turn it into a "does ticket N exist?" probe: 403, never 404.
     */
    @Test
    void roleThatCanNeverDeclare_isRefusedBeforeTheTicketIsRead() {
        long neverCreated = 9_999_999L;
        assertThat(tickets.findById(neverCreated)).isEmpty();

        // import joined this set on 2026-09-28: it left STOCK_DECLARATION_ROLES entirely, so it is
        // now refused at the coarse pre-filter (403) before the ticket is read, exactly like the
        // roles that could never declare.
        for (UserPrincipal stranger : List.of(accountUser, hrUser, salesManagerUser, importUser)) {
            assertThatThrownBy(() -> ticketService.reserveStock(neverCreated, declare(1L, "1.00"), stranger))
                .describedAs("role %s must get 403 (not 404) for a ticket it may never touch", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    /**
     * A refusal is not enough on its own: a partly-applied write would leave the row moved even
     * though the call threw. This drives the refusal AFTER a legitimate declaration, so the
     * assertion is that the stranger's number did not overwrite the owner's — a stronger check
     * than "still zero", which a no-op would also satisfy.
     */
    @Test
    void refusedDeclaration_doesNotOverwriteTheDeclarationAlreadyOnFile() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId, declare(itemId, "40.00"), owner);
        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("40.00");

        assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "100.00"), otherSalesRep))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "0.00"), accountUser))
            .isInstanceOf(ApiException.class);

        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("40.00");
    }

    // ── the grants: necessary, but not the evidence ──────────────────────────

    @Test
    void dealOwner_declares_andTheRowIsActuallyWritten() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        ticketService.reserveStock(ticketId, declare(itemId, "40.00"), owner);

        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("40.00");
        assertThat(stockNote(itemId)).isEqualTo("มีของในสต็อก");
        assertThat(stockReservedEvents(ticketId)).isEqualTo(1);
    }

    /**
     * The case the 2026-09-28 narrowing exists for: import used to be able to correct a rep's figure
     * after the fact, and now must not. Driven AFTER a legitimate owner declaration, so the assertion
     * is that import's number did not overwrite the owner's — a stronger check than "still zero",
     * which a no-op would also satisfy — and re-read straight from Postgres.
     */
    @Test
    void importRole_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId, declare(itemId, "80.00"), owner);
        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("80.00");

        assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "25.00"), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyFromStock(itemId))
            .describedAs("import may no longer correct stock coverage (owner decision 2026-09-28)")
            .isEqualByComparingTo("80.00");
    }

    /** The CEO keeps the correction/override path — through oversight, not ownership. */
    @Test
    void ceoCorrectsTheOwnersDeclaration() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId, declare(itemId, "80.00"), owner);

        ticketService.reserveStock(ticketId, declare(itemId, "30.00"), ceoUser);
        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("30.00");
    }

    /**
     * The routing half of the ruling: "auto-routing behaviour stays identical, whoever declares."
     * Two deals in the same starting state, full coverage declared on one by the owner and on the
     * other by the CEO — both must land on exactly the same fulfilment status and sales stage.
     * (Was owner-vs-import before the 2026-09-28 narrowing removed import from this gate.)
     *
     * <p>Both start at {@code ORDER_RECEIVED}. They used to start at {@code LEAD_APPROACH}, and
     * this Javadoc used to record the residual risk that came with it: {@code autoAdvanceStage} has
     * no stage precondition, so a full-coverage declaration jumped the deal straight to
     * {@code DELIVERY_SCHEDULING} from wherever it was — including an untouched lead — which also
     * blocked {@code ISSUE_IMPORT_REQUEST}. That risk is now closed by {@code reserveStock}'s stage
     * floor (see {@code TicketService.stockCoverageStageReached} and
     * {@code StageFactGateIntegrationTest}); the ROUTING above the floor is deliberately unchanged,
     * which is what this test still pins.
     *
     * <p>Routing to {@code DELIVERY_SCHEDULING} needs the deposit to be ready (C4 of the owner rules of
     * 2026-10-05: a stock declaration holds the stage until ฝ่ายบัญชี has confirmed the deposit; pinned in
     * {@code StockDeclarationDepositHoldIntegrationTest}), so both deals are made deposit-ready here to keep
     * this test about the routing being the same whoever declares.
     */
    @Test
    void fullCoverage_routesIdenticallyWhicheverRoleDeclares() {
        long ownerDeclared = createTicketWithOneItem();
        long ceoDeclared = createTicketWithOneItem();
        assertThat(salesStage(ownerDeclared)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(salesStage(ceoDeclared)).isEqualTo(DealStage.ORDER_RECEIVED);
        tickets.updatePaymentStatusUnchecked(ownerDeclared, PaymentTrack.DEPOSIT_PAID);
        tickets.updatePaymentStatusUnchecked(ceoDeclared, PaymentTrack.DEPOSIT_PAID);

        ticketService.reserveStock(ownerDeclared, declare(onlyItemId(ownerDeclared), "100.00"), owner);
        ticketService.reserveStock(ceoDeclared, declare(onlyItemId(ceoDeclared), "100.00"), ceoUser);

        assertThat(fulfillmentStatus(ownerDeclared)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(fulfillmentStatus(ceoDeclared)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(salesStage(ownerDeclared)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(salesStage(ceoDeclared)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        // PROCUREMENT is skipped either way — a fully-stocked deal has no import journey.
        assertThat(salesStage(ownerDeclared)).isEqualTo(salesStage(ceoDeclared));
    }

    /**
     * Closes the loop the whole change exists for, through the REAL and deliberately UNMODIFIED
     * {@link CommissionRepository#sumActiveStockActualReceived}: the number the owning rep just
     * declared is the number their STOCK_BONUS input is computed from. Nothing here reimplements
     * the formula — that would be the "mock mirrors the computation" failure {@code CLAUDE.md}
     * catalogues; the assertion is on the real SQL's output.
     *
     * <p>Stock share 40/100 = 0.4 on ฿500,000 received -> ฿200,000 of stock receipts. The
     * commission record is forced to APPROVED directly because that repository method filters on
     * {@code status = 'APPROVED'} (tightened 2026-08-02); driving the manager+CEO approval chain
     * would test CommissionService, which this class does not touch.
     */
    @Test
    void ownersOwnDeclaration_feedsTheRealStockBonusCommissionInput() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);
        LocalDate payrollMonth = LocalDate.of(2026, 8, 1);
        seedApprovedTicketLinkedCommission(ticketId, ownerId, new BigDecimal("500000.00"), payrollMonth);

        assertThat(commissions.sumActiveStockActualReceived(ownerId, payrollMonth))
            .describedAs("nothing declared yet -> stock share 0")
            .isEqualByComparingTo("0.00");

        ticketService.reserveStock(ticketId, declare(itemId, "40.00"), owner);

        assertThat(commissions.sumActiveStockActualReceived(ownerId, payrollMonth))
            .isEqualByComparingTo("200000.00");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Every axis the refused call could have touched, re-read from Postgres. */
    private void assertNothingMoved(long ticketId, long itemId) {
        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("0.00");
        assertThat(stockNote(itemId)).isNull();
        assertThat(fulfillmentStatus(ticketId)).isNull();
        assertThat(salesStage(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(stockReservedEvents(ticketId)).isZero();
    }

    private static StockReservationRequest declare(long itemId, String qtyFromStock) {
        return new StockReservationRequest(List.of(
            new StockReservationRequest.Line(itemId, new BigDecimal(qtyFromStock), "มีของในสต็อก")));
    }

    /**
     * A deal parked at {@link DealStage#ORDER_RECEIVED} — the stage floor a stock declaration must
     * clear since the stage-fact-gate branch (below it every call 409s regardless of role, which
     * would make every case in this class pass for the wrong reason). Seeded through the real
     * {@code updateSalesStage} writer; this class is about WHO may declare, and
     * {@code StageFactGateIntegrationTest} owns the floor's own coverage.
     */
    private long createTicketWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบสต็อก", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ทดสอบ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private BigDecimal qtyFromStock(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private String stockNote(long itemId) {
        return jdbc.queryForObject("SELECT stock_note FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), String.class);
    }

    private String fulfillmentStatus(long ticketId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), String.class);
    }

    private String salesStage(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), String.class);
    }

    private int stockReservedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STOCK_RESERVED), Integer.class);
    }

    /**
     * A real APPROVED, ticket-linked SALE commission, built through the real
     * {@link CommissionRepository} writers and then moved to APPROVED with one statement.
     * {@link InvoiceCalculation} is constructed directly rather than via {@code
     * CommissionCalculator} on purpose: this test is about the stock-share join, and it must not
     * depend on (or appear to validate) the invoice math.
     */
    private void seedApprovedTicketLinkedCommission(long ticketId, long salesRepId,
                                                    BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-STOCKAUTHZ-" + UUID.randomUUID(), LocalDate.of(2026, 8, 5),
            actualReceived, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long invoiceId = commissions.createInvoice(request);
        long commissionId = commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId,
            payrollMonth, new InvoiceCalculation(actualReceived, actualReceived));
        jdbc.update("UPDATE sales.commission_record SET status = 'APPROVED' WHERE commission_id = :id",
            new MapSqlParameterSource().addValue("id", commissionId));
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-stock@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
