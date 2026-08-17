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
 * V148 (per-item stock-commission weighting) — real-DB enforcement coverage for {@link
 * TicketService#setItemWeightMultipliers}'s gate, the required evidence under CLAUDE.md's
 * "permission changes must ship evidence": a role gate needs a real-DB integration test through
 * the real service AND the real repository, because Mockito cannot reach this — a mocked {@link
 * TicketRepository} passes happily while the {@code UPDATE} does something else entirely.
 *
 * <p><b>Written wrong-way-round on purpose.</b> The tests that matter are the refusals, and each
 * one re-reads {@code sales.ticket_item.weight_multiplier} straight out of Postgres afterwards to
 * prove nothing moved — "the manager can set it" is necessary but is not the evidence.
 *
 * <p><b>A DIFFERENT role set from {@code STOCK_DECLARATION_ROLES}, deliberately.</b> {@code
 * reserveStock} (the sibling per-item write on this exact table) is gated to {@code {sales-owner,
 * import, ceo}} — the rep DECLARES their own stock coverage. This endpoint is the opposite
 * direction: a manager APPROVES a weight against a rep's commission input, gated to {@code
 * {sales_manager, ceo}} instead. This suite proves BOTH halves of that separation: the deal's own
 * owner (a {@code sales} role, who legitimately may call {@code reserveStock} on this exact
 * ticket) is REFUSED here, and {@code import} (who may also call {@code reserveStock}) is REFUSED
 * here too — the two endpoints do not share an authorization boundary just because they share a
 * table.
 *
 * <p>Mirrors {@code th.co.glr.hr.ticket.StockDeclarationAuthzIntegrationTest} and {@code
 * th.co.glr.hr.attendance.AttendanceScopeIntegrationTest}. Note the suite-wide trap documented on
 * {@link AbstractPostgresIntegrationTest}: services are hand-wired with {@code new}, so {@code
 * @Transactional} is inert here and no rollback is ever exercised — nothing below asserts one. The
 * "unmoved" assertions hold because the guard throws before any write, not because a transaction
 * rolled back.
 */
class ItemWeightMultiplierAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

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

        // Same collaborator-mocking rationale as StockDeclarationAuthzIntegrationTest:
        // setItemWeightMultipliers never calls PricingRequestService. The stub on
        // cancelOpenForTicket only guards against a future edit routing through markLost/cancel.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล น้ำหนัก", "weight-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น น้ำหนัก", "weight-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า น้ำหนัก", "weight-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ น้ำหนัก", "weight-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี น้ำหนัก", "weight-account@glr.co.th"), "account");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล น้ำหนัก", "weight-hr@glr.co.th"), "hr");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย น้ำหนัก", "weight-salesmgr@glr.co.th"), "sales_manager");
    }

    // ── the refusals: these are the evidence ─────────────────────────────────

    /**
     * The case this gate exists for: {@code reserveStock}'s own authorized declarer — the deal's
     * OWNER, a {@code sales} role — must NOT also be able to approve this deal's commission weight.
     * The two decisions (declare quantity vs. approve weight) are deliberately held by different
     * people.
     */
    @Test
    void dealOwner_salesRole_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertNothingMoved(itemId);
    }

    /** A sales rep who does not even own the deal — the same role as the owner, still refused. */
    @Test
    void otherSalesRep_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertNothingMoved(itemId);
    }

    /**
     * {@code import} holds the identical {@code reserveStock} ability owner/ceo do, and must be
     * refused here just as certainly — the same "declare vs. approve" separation this whole gate
     * exists for.
     */
    @Test
    void importRole_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 3), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertNothingMoved(itemId);
    }

    /** Neither ownership nor a fulfilment role, and no manager oversight role either. */
    @Test
    void accountAndHrRoles_areRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        for (UserPrincipal stranger : List.of(accountUser, hrUser)) {
            assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), stranger))
                .describedAs("role %s must not be able to set the item weight multiplier", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertNothingMoved(itemId);
    }

    /**
     * The coarse pre-filter's own reason to exist: a role that can never set this must be refused
     * BEFORE the ticket is read, so this endpoint cannot become a "does ticket N exist?" probe —
     * 403, never 404 — for every non-{@code sales_manager}/{@code ceo} role, sales/owner included.
     */
    @Test
    void roleThatCanNeverSetWeight_isRefusedBeforeTheTicketIsRead() {
        long neverCreated = 9_999_998L;
        assertThat(tickets.findById(neverCreated)).isEmpty();

        for (UserPrincipal stranger : List.of(owner, otherSalesRep, importUser, accountUser, hrUser)) {
            assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(neverCreated, weightRequest(1L, 2), stranger))
                .describedAs("role %s must get 403 (not 404) for a ticket it may never touch", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    /**
     * A refusal is not enough on its own: this drives the refusal AFTER a legitimate manager
     * approval, so the assertion is that the stranger's attempt did not overwrite the manager's
     * decision — a stronger check than "still the default", which a no-op would also satisfy.
     */
    @Test
    void refusedAttempt_doesNotOverwriteTheWeightAlreadyOnFile() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);
        ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), salesManagerUser);
        assertThat(weightMultiplier(itemId)).isEqualTo(2);

        assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 3), owner))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 1), importUser))
            .isInstanceOf(ApiException.class);

        assertThat(weightMultiplier(itemId)).isEqualTo(2);
    }

    // ── the grants: necessary, but not the evidence ──────────────────────────

    @Test
    void salesManager_setsTheWeight_andTheRowIsActuallyWritten() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), salesManagerUser);

        assertThat(weightMultiplier(itemId)).isEqualTo(2);
    }

    /** {@code ceo} may also set it — the existing record-level fallback setter ({@code
     * CommissionService#updateDeductions}) is manager-or-CEO, and this task's brief asks the more
     * granular per-item control to be no MORE restrictive than the coarser one it complements. */
    @Test
    void ceo_setsTheWeight_andTheRowIsActuallyWritten() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 3), ceoUser);

        assertThat(weightMultiplier(itemId)).isEqualTo(3);
    }

    /** {@link TicketService#actions} must advertise {@code SET_ITEM_WEIGHT_MULTIPLIER} for a
     * sales_manager and must NOT advertise it for the deal's own owner — the same "one predicate
     * drives both the gate and the advertisement" discipline {@code canReserveStock} documents,
     * proven here rather than merely asserted in a comment. */
    @Test
    void actionsAdvertisement_matchesTheGate_forManagerAndOwner() {
        long ticketId = createTicketWithOneItem();

        boolean managerSees = ticketService.actions(ticketId, salesManagerUser).availableActions().stream()
            .anyMatch(a -> "SET_ITEM_WEIGHT_MULTIPLIER".equals(a.action()));
        boolean ownerSees = ticketService.actions(ticketId, owner).availableActions().stream()
            .anyMatch(a -> "SET_ITEM_WEIGHT_MULTIPLIER".equals(a.action()));

        assertThat(managerSees).isTrue();
        assertThat(ownerSees).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertNothingMoved(long itemId) {
        assertThat(weightMultiplier(itemId)).isEqualTo(1);
    }

    private static ItemWeightMultiplierRequest weightRequest(long itemId, int weightMultiplier) {
        return new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, weightMultiplier)));
    }

    /** Same fixture shape as {@code StockDeclarationAuthzIntegrationTest#createTicketWithOneItem}:
     * a deal parked at {@link DealStage#ORDER_RECEIVED} with exactly one item. This gate carries
     * no stage floor of its own (see {@code TicketService#setItemWeightMultipliers}'s Javadoc), so
     * the stage choice here is only for fixture parity with its sibling suite, not a precondition
     * under test. */
    private long createTicketWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบน้ำหนัก", "NORMAL", "ลูกค้าทดสอบน้ำหนัก", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล น้ำหนัก");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private int weightMultiplier(long itemId) {
        Integer value = jdbc.queryForObject("SELECT weight_multiplier FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), Integer.class);
        return value == null ? 0 : value;
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-weight@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
