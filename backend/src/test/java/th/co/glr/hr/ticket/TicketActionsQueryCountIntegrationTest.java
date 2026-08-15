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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.StatementCountingDataSource;

/**
 * Real-Postgres proof that {@code TicketService.actions} issues a number of SQL statements that
 * does <strong>not</strong> scale with the length of the deal pipeline.
 *
 * <p><b>Why this test exists.</b> {@code actions} is the hottest sales endpoint — the ticket
 * workspace calls it on load and the client invalidates it after every mutation — and #713 gave it
 * a loop that evaluates the whole stage guard chain once per stage in {@link DealStage#ORDER},
 * fifteen times per call. That is fine <i>today</i>: the gates are pure in-memory predicates and
 * the one query they need ({@code hasActivitySinceLastStageChange}) is deliberately hoisted out of
 * the loop by {@code requireStageAdvanceReadiness}'s {@code hasRecentActivity} parameter. Measured
 * on this suite, the fifteen evaluations cost ~21µs against a ~2.4ms call: under 1% of the
 * endpoint, and about a tenth of a single query round trip. <b>Nothing here is an optimisation, and
 * none is warranted.</b>
 *
 * <p>The risk is the next edit, not this one. Anybody adding a repository call anywhere inside
 * {@code requireStageMoveAllowed} / {@code requireStageFactsHold} / {@code requireStageWriteAccess}
 * — the natural way to add a gate that consults, say, a delivery record or a pricing request —
 * turns one query into fifteen on the busiest endpoint in the app, and every existing test stays
 * green because the <i>behaviour</i> is unchanged. This class is what goes red instead.
 *
 * <p><b>How it counts.</b> {@link StatementCountingDataSource} wraps the datasource the measured
 * repositories sit on and records every {@code Statement.execute*}. There is no Spring context here
 * (services are hand-wired with {@code new}, see {@link AbstractPostgresIntegrationTest}), so the
 * datasource is the only seam the object graph shares — which is also why the count is honest:
 * it sees SQL issued by any repository the service reaches, whether or not this test knew about it.
 *
 * <p><b>The assertion is scale-free on purpose.</b> The primary check is not "exactly N statements"
 * — a pinned N would go red for an unrelated repository refactor and tell a reader nothing about
 * N+1 — but <b>no SQL string is executed more than once per call</b>. That is precisely the shape
 * of an N+1, it needs no magic number, and its failure message names the repeated query. The
 * observed totals when this was written were 10 statements for {@code sales}/{@code sales_manager}/
 * {@code account} and 11 for {@code ceo}/{@code import} (the extra one is
 * {@code hasReceivedGoods}, reached only by {@code FULFILMENT_ROLES} in {@code canRecordDelivery});
 * those numbers are recorded as provenance, not asserted.
 *
 * <p><b>MUTATION-CHECK RECORD (actually run, not simulated; reverted to a byte-identical
 * {@code TicketService.java}, SHA-256 confirmed, after {@code rm -rf target/classes}).</b> Two
 * separate vulnerabilities were injected, because the assertions here cover different holes:
 *
 * <ol>
 *   <li><b>A uniform per-stage query</b> — {@code tickets.hasReceivedGoods(s.id())} added at the
 *       top of {@code requireStageMoveAllowed}. {@code actionsNeverExecutesTheSameSqlTwice} went
 *       red naming the query at {@code 15x}, and the count assertion went red at 25 statements for
 *       15 stages. <b>All 456 other tests in {@code th.co.glr.hr.ticket} stayed green</b> —
 *       including {@code StageDecisionIntegrationTest} and {@code StageFactGateIntegrationTest},
 *       which cover this exact payload — because the injected call changes no behaviour whatsoever.
 *       That is the entire argument for this class existing.
 *   <li><b>Un-hoisting the readiness query</b> — {@code requireStageAdvanceReadiness} reading
 *       {@code tickets.hasActivitySinceLastStageChange(s.id())} itself instead of trusting its
 *       {@code hasRecentActivity} argument, i.e. exactly the regression the hoist exists to
 *       prevent. The per-stage totals became a staircase tracking the number of forward targets
 *       (19, 18, 17 … 11, 10, 10, 10) and the "same number at every stage" assertion went red.
 *       Note the tail: at {@code DELIVERY_SCHEDULING} and later there are no forward targets, so
 *       the total is the normal 10 and neither the "never twice" nor the "fewer than there are
 *       stages" check fires at those stages. The equality assertion is the only one that catches
 *       this shape, which is why all three are here.
 * </ol>
 */
class TicketActionsQueryCountIntegrationTest extends AbstractPostgresIntegrationTest {

    private StatementCountingDataSource counter;
    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private Map<String, UserPrincipal> actors;

    @BeforeEach
    void wireServicesOnACountingDataSource() {
        // The same physical datasource the base class's own `jdbc` uses, wrapped. Test setup keeps
        // using the unwrapped `jdbc`, and every measurement resets the counter immediately before
        // the call under test, so nothing but `actions` is ever counted.
        counter = new StatementCountingDataSource(
            ((JdbcTemplate) jdbc.getJdbcOperations()).getDataSource());
        NamedParameterJdbcTemplate counted = new NamedParameterJdbcTemplate(counter);

        tickets = new TicketRepository(counted);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        // Every collaborator that owns SQL is wired on the counted template, so a future gate that
        // reaches for a different repository is counted too rather than silently invisible.
        ticketService = new TicketService(tickets, new NotificationRepository(counted, SalesNotificationMailer.NO_OP),
            mock(PriceCalcService.class), new ObjectMapper(), new CustomerRepository(counted),
            new QuotationRenderer(), pricingRequests);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "count-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        actors = new LinkedHashMap<>();
        actors.put("sales (deal owner)", ownerRep);
        actors.put("sales_manager", principal(createEmployee(employees, "ผู้จัดการขาย", "count-mgr@glr.co.th"), "sales_manager"));
        actors.put("ceo", principal(createEmployee(employees, "ซีอีโอ", "count-ceo@glr.co.th"), "ceo"));
        actors.put("account", principal(createEmployee(employees, "ฝ่ายบัญชี", "count-acct@glr.co.th"), "account"));
        actors.put("import", principal(createEmployee(employees, "ฝ่ายนำเข้า", "count-imp@glr.co.th"), "import"));
    }

    /**
     * The guard proper: one {@code actions} call must never execute the same SQL twice.
     *
     * <p>A repository call added inside the per-stage loop runs fifteen times with identical SQL,
     * so it shows up here as a single query with a count of 15 and names itself in the failure
     * message. Asserted for every viewer role at every stage in the pipeline, because the gate
     * chain short-circuits at different points depending on both — a query added to
     * {@code requireStageFactsHold}, for instance, is only reached by the role that clears
     * {@code requireStageWriteAccess} first, so a single-role check could miss it entirely.
     */
    @Test
    void actionsNeverExecutesTheSameSqlTwice_forAnyRoleAtAnyStageOfThePipeline() {
        for (String stage : DealStage.ORDER) {
            long ticketId = readyToAdvanceFrom(stage);
            actors.forEach((label, actor) -> {
                counter.reset();
                ticketService.actions(ticketId, actor);

                // The harness must have observed SOMETHING, or "no query ran twice" is vacuously
                // true and this test is worthless. See theCounterItselfDetectsARepeatedQuery for
                // the positive control that it can also go red.
                assertThat(counter.total())
                    .as("actions() issued no SQL at all as %s at %s — the counter is not wired in",
                        label, stage)
                    .isPositive();
                assertThat(counter.repeatedStatements())
                    .as("actions() repeated a query as %s at %s — this is the N+1 shape. %s",
                        label, stage, counter.describe())
                    .isEmpty();
            });
        }
    }

    /**
     * The same invariant stated as a count: the totals are identical at every stage, and are fewer
     * than there are stages.
     *
     * <p>Two different failures are covered. <b>Equality across stages</b> catches a repository
     * call reached only for some targets — it would fire a different number of times depending on
     * where the deal currently sits. <b>Fewer statements than stages</b> catches the case the
     * "never twice" check above cannot see: a per-stage query whose SQL <i>text</i> varies by stage
     * (dynamic SQL), which would look like fifteen distinct statements rather than one repeated
     * one. Any per-stage call, however it is written, pushes the total to at least
     * {@code DealStage.ORDER.size()}.
     *
     * <p>The second assertion doubles as a deliberate ceiling on the endpoint's load path: at the
     * time of writing it costs 10–11 statements against 15 stages, so there is room for a handful
     * more before this complains. On the hottest sales endpoint, being made to justify the
     * fifteenth query is the intended behaviour, not a false positive.
     */
    @Test
    void actionsIssuesTheSameNumberOfStatementsAtEveryStage_andFewerThanThereAreStages() {
        Map<String, Map<String, Integer>> countsByActorAndStage = new LinkedHashMap<>();
        for (String stage : DealStage.ORDER) {
            long ticketId = readyToAdvanceFrom(stage);
            actors.forEach((label, actor) -> {
                counter.reset();
                ticketService.actions(ticketId, actor);
                countsByActorAndStage
                    .computeIfAbsent(label, k -> new LinkedHashMap<>())
                    .put(stage, counter.total());
            });
        }

        countsByActorAndStage.forEach((label, byStage) -> {
            int atFirstStage = byStage.get(DealStage.ORDER.get(0));
            assertThat(byStage.values())
                .as("statement count as %s must not depend on the deal's stage, but was %s",
                    label, byStage)
                .containsOnly(atFirstStage);
            assertThat(atFirstStage)
                .as("actions() as %s issued %d statements for %d stages — that is the scaling "
                        + "an N+1 in the stage guard chain produces (counts: %s)",
                    label, atFirstStage, DealStage.ORDER.size(), byStage)
                .isLessThan(DealStage.ORDER.size());
        });
    }

    /**
     * Positive control for the counting harness itself.
     *
     * <p>Both assertions above are of the form "nothing bad was observed", which is exactly the
     * shape that passes when nothing is observed at all — a broken proxy, a repository wired on the
     * wrong template, a driver that bypasses the seam. This runs the same query twice on purpose
     * and requires the counter to say so, so a harness that cannot detect an N+1 fails here rather
     * than reporting a clean bill of health there.
     */
    @Test
    void theCounterItselfDetectsARepeatedQuery() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);

        counter.reset();
        tickets.hasActivitySinceLastStageChange(ticketId);
        tickets.hasActivitySinceLastStageChange(ticketId);

        assertThat(counter.total()).isEqualTo(2);
        assertThat(counter.repeatedStatements()).hasSize(1);
        assertThat(counter.repeatedStatements().values()).containsExactly(2);
        assertThat(counter.repeatedStatements().keySet().iterator().next())
            .contains("sales.deal_activity");
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    // Deliberately the same fixture shape as StageDecisionIntegrationTest: a deal with a follow-up
    // date and one logged activity clears the Slice B1 readiness gate, so the guard chain runs to
    // completion for the forward targets instead of short-circuiting early on every one of them.

    private long readyToAdvanceFrom(String stage) {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, stage);
        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, null, null, null, LocalDate.now().plusDays(3)), ownerRep);
        ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), ownerRep);
        return ticketId;
    }

    private long createDealWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบจำนวนคิวรี", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), ownerRepId, "เจ้าของดีล ทดสอบ");
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-count@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
