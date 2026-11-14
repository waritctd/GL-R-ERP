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
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.StatementCountingDataSource;

/** Real-Postgres proof that TicketService.actions issues a number of SQL statements that does not scale with
 * the length of the deal pipeline. The equality assertion is the only one that catches this shape, which is
 * why all three are here. */
class TicketActionsQueryCountIntegrationTest extends AbstractPostgresIntegrationTest {

    private StatementCountingDataSource counter;
    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private Map<String, UserPrincipal> actors;

    @BeforeEach
    void wireServicesOnACountingDataSource() {
        // The same physical datasource the base class's own `jdbc` uses, wrapped. Test setup keeps using the unwrapped
        // `jdbc`, and every measurement resets the counter immediately before the call under test, so nothing but
        // `actions` is ever counted.
        counter = new StatementCountingDataSource(
            ((JdbcTemplate) jdbc.getJdbcOperations()).getDataSource());
        NamedParameterJdbcTemplate counted = new NamedParameterJdbcTemplate(counter);

        tickets = new TicketRepository(counted);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        // Every collaborator that owns SQL is wired on the counted template, so a future gate that reaches for a
        // different repository is counted too rather than silently invisible.
        ticketService = new TicketService(tickets, new NotificationRepository(counted, SalesNotificationMailer.NO_OP),
            new ObjectMapper(), new CustomerRepository(counted), new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

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

    /** The guard proper: one actions call must never execute the same SQL twice. A repository call added inside the
     * per-stage loop runs fifteen times with identical SQL, so it shows up here as a single query with a count of
     * 15 and names itself in the failure message. */
    @Test
    void actionsNeverExecutesTheSameSqlTwice_forAnyRoleAtAnyStageOfThePipeline() {
        for (String stage : DealStage.ORDER) {
            long ticketId = readyToAdvanceFrom(stage);
            actors.forEach((label, actor) -> {
                if (accountOutsideScope(actor, stage)) return;
                counter.reset();
                actionsFor(ticketId, actor);

                // The harness must have observed SOMETHING, or "no query ran twice" is vacuously true and this test is
                // worthless. See theCounterItselfDetectsARepeatedQuery for the positive control that it can also go red.
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

    /** The same invariant stated as a count: the totals are identical at every stage, and are fewer than there are
     * stages. Equality across stages catches a repository call reached only for some targets — it would fire a
     * different number of times depending on where the deal currently sits. */
    @Test
    void actionsIssuesTheSameNumberOfStatementsAtEveryStage_andFewerThanThereAreStages() {
        Map<String, Map<String, Integer>> countsByActorAndStage = new LinkedHashMap<>();
        for (String stage : DealStage.ORDER) {
            long ticketId = readyToAdvanceFrom(stage);
            actors.forEach((label, actor) -> {
                if (accountOutsideScope(actor, stage)) return;
                counter.reset();
                actionsFor(ticketId, actor);
                countsByActorAndStage
                    .computeIfAbsent(label, k -> new LinkedHashMap<>())
                    .put(stage, counter.total());
            });
        }

        countsByActorAndStage.forEach((label, byStage) -> {
            int atFirstStage = byStage.values().iterator().next();
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
     * GET /api/tickets (listPage) must not run per-deal queries. On prod (2026-10-30) every listed deal
     * cost ~8 round trips in enrichSummary, so the CEO dashboard's full list of 272 deals took minutes
     * and every page that loads the deal list sat on its skeleton.
     */
    @Test
    void listPageDoesNotRunAQueryPerDeal() {
        int deals = 5;
        for (int i = 0; i < deals; i++) {
            readyToAdvanceFrom(DealStage.ORDER.get(i));
        }

        counter.reset();
        var page = ticketService.listPage(null, null, actors.get("ceo"), PageRequest.resolve(null, null));

        assertThat(page.items()).hasSizeGreaterThanOrEqualTo(deals);
        assertThat(counter.repeatedStatements())
            .as("listPage repeated a query per deal — the N+1 shape. %s", counter.describe())
            .isEmpty();
        assertThat(counter.total())
            .as("listPage issued %d statements for %d deals. %s", counter.total(), deals, counter.describe())
            .isLessThan(deals);
    }

    // H1 lockdown: account no longer reaches TicketService.actions (the /tickets path); it reads its action list
    // through financeActions -- the SAME buildActions, plus one scope query -- and only on deals inside its list
    // scope (S10+ here: these deals carry no payment status).
    private static boolean accountOutsideScope(UserPrincipal actor, String stage) {
        return "account".equals(actor.role())
            && DealStage.indexOf(stage) < DealStage.indexOf(DealStage.ORDER_RECEIVED);
    }

    private void actionsFor(long ticketId, UserPrincipal actor) {
        if ("account".equals(actor.role())) {
            ticketService.financeActions(ticketId, actor);
        } else {
            ticketService.actions(ticketId, actor);
        }
    }

    /** Positive control for the counting harness itself. This runs the same query twice on purpose and requires the
     * counter to say so, so a harness that cannot detect an N+1 fails here rather than reporting a clean bill of
     * health there. */
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

    // Seed follow-up data and an activity so readiness checks reach the full guard chain.

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
