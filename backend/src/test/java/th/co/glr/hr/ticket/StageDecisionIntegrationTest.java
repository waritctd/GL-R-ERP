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
import java.util.Set;
import java.util.stream.Collectors;
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
import th.co.glr.hr.ticket.TicketResponses.StageDecisionDto;
import th.co.glr.hr.ticket.TicketResponses.TicketActionsResponse;

/** Real-Postgres proof that the per-stage decisions TicketService.actions ships to a client say the same thing
 * TicketService.updateStage would do if the client acted on them. Why this test exists. Nothing here may be
 * inferred from mockApi.js, whose stage gates are an explicit approximation. */
class StageDecisionIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal otherRep;
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // PricingRequestService is mocked exactly as in the sibling stage classes: nothing exercised here calls it.
        // The two things under test — TicketService's gates and TicketRepository's SQL — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "decide-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        otherRep = principal(createEmployee(employees, "พนักงานขายอื่น", "decide-other@glr.co.th"), "sales");
        salesManager = principal(createEmployee(employees, "ผู้จัดการขาย", "decide-mgr@glr.co.th"), "sales_manager");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "decide-ceo@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "ฝ่ายบัญชี", "decide-account@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "decide-import@glr.co.th"), "import");
    }

    /** The headline refusal, and the one the deleted frontend copy was responsible for. A sales rep who did not
     * create the deal never receives a decision payload at all: actions runs requireViewAccess first, and a sales
     * rep is scoped to their own rows, so the whole endpoint 403s. */
    @Test
    void aSalesRepOnSomeoneElsesDeal_cannotEvenAskForTheDecisions_andTheWriteIsRefusedToo() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        assertThatThrownBy(() -> ticketService.actions(ticketId, otherRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, otherRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** The deal owner must not reach the money stages or the import stage — those belong to ฝ่ายบัญชี and
     * ฝ่ายนำเข้า. */
    @Test
    void theDealOwner_isNeverOfferedTheMoneyOrImportStages_andIsRefusedOnEachOfThem() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);
        // Sales-gated but fact-gated, so a fresh deal cannot reach them; excluded here and asserted
        // in StageFactGateIntegrationTest. ORDER_RECEIVED and DELIVERED are fact-gated (Part B); the
        // three quote stages since the 2026-10-16 ruling, their fact being a quotation addressed to
        // that recipient, which this deal has none of. They stay in SALES_TARGET_STAGES on purpose:
        // the refusal is a 409 about a missing document, not a 403 about who the rep is, which is
        // what the FORBIDDEN loop below still asserts for the money and import stages.
        Set<String> factGated = Set.of(DealStage.ORDER_RECEIVED, DealStage.DELIVERED,
            DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER, DealStage.QUOTE_BUYER);

        assertThat(allowedStages(decisionsFor(ticketId, ownerRep)))
            .doesNotContain(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID, DealStage.PROCUREMENT)
            .containsExactlyInAnyOrderElementsOf(
                DealStage.SALES_TARGET_STAGES.stream()
                    .filter(stage -> !DealStage.PRESENTATION.equals(stage))
                    .filter(stage -> !factGated.contains(stage))
                    .collect(Collectors.toSet()));

        for (String forbidden : List.of(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID, DealStage.PROCUREMENT)) {
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, forbidden, "เหตุผล", ownerRep))
                .describedAs("owner must not reach %s", forbidden)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** ฝ่ายบัญชี is confined to the two money stages, and ฝ่ายนำเข้า to the single import stage. */
    @Test
    void accountAndImport_areEachConfinedToTheirOwnStages() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);
        // H1 lockdown: account only reaches a deal inside its list scope, so its half of this test runs on its own
        // deal at ORDER_RECEIVED (S10). Import keeps the original PRESENTATION deal.
        long accountTicketId = readyToAdvanceFrom(DealStage.ORDER_RECEIVED);

        // account: DEPOSIT_RECEIVED and CLOSED_PAID are both fact-gated and the facts do not hold, so what account is
        // OFFERED here is nothing — but the reason must be the fact, not a 403, and every sales stage must still be a
        // 403.
        List<StageDecisionDto> accountDecisions = decisionsFor(accountTicketId, accountActor);
        assertThat(allowedStages(accountDecisions)).isEmpty();
        assertThat(reasonFor(accountDecisions, DealStage.SPEC_APPROVED)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThat(reasonFor(accountDecisions, DealStage.DEPOSIT_RECEIVED)).contains("ยังไม่ได้รับชำระมัดจำ");
        assertThatThrownBy(() -> ticketService.financeUpdateStage(accountTicketId, DealStage.SPEC_APPROVED, "x", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // import: PROCUREMENT is not fact-gated, so it is genuinely the only thing on offer.
        List<StageDecisionDto> importDecisions = decisionsFor(ticketId, importActor);
        assertThat(allowedStages(importDecisions)).containsExactly(DealStage.PROCUREMENT);
        assertThat(reasonFor(importDecisions, DealStage.NEGOTIATION)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.NEGOTIATION, "x", importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageOf(accountTicketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(stageChangedEvents(ticketId)).isZero();
        assertThat(stageChangedEvents(accountTicketId)).isZero();
    }

    /**
     * QA-05 (owner ruling 2026-10-28): once the order is confirmed (payment track reached
     * CUSTOMER_CONFIRMED -- the deal is at or past ขั้น 10), "แก้ไขสถานะ" must not move the deal
     * below ขั้น 10. The decision payload the modal renders says blocked, and the write is a 409.
     */
    @Test
    void afterTheOrderIsConfirmed_everyStageBelowOrderReceived_isBlockedAndRefusedWith409() {
        long ticketId = readyToAdvanceFrom(DealStage.PROCUREMENT);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);

        for (UserPrincipal actor : List.of(ownerRep, salesManager, ceo)) {
            List<StageDecisionDto> decisions = decisionsFor(ticketId, actor);
            for (String target : DealStage.ORDER) {
                if (DealStage.indexOf(target) >= DealStage.indexOf(DealStage.ORDER_RECEIVED)) continue;
                assertThat(allowedFor(decisions, target))
                    .as("%s must not be offered to %s after the order is confirmed", target, actor.role()).isFalse();
                assertThat(reasonFor(decisions, target)).isNotBlank();
            }
        }
        // The write is refused too (not only hidden), with a conflict, and nothing moved.
        for (String target : List.of(DealStage.QUOTE_OWNER, DealStage.SPEC_APPROVED, DealStage.NEGOTIATION)) {
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, target, "ย้อนกลับ", ownerRep))
                .as(target)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PROCUREMENT);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    // H1 lockdown: account reads its stage decisions through financeActions (same gates, no /tickets view check)
    // and writes a money stage through financeUpdateStage; every other role is unchanged.
    private List<StageDecisionDto> decisionsFor(long ticketId, UserPrincipal actor) {
        return actionsFor(ticketId, actor).stageDecisions();
    }

    private TicketActionsResponse actionsFor(long ticketId, UserPrincipal actor) {
        return "account".equals(actor.role())
            ? ticketService.financeActions(ticketId, actor)
            : ticketService.actions(ticketId, actor);
    }

    private static Set<String> allowedStages(List<StageDecisionDto> decisions) {
        return decisions.stream().filter(StageDecisionDto::allowed)
            .map(StageDecisionDto::stage).collect(Collectors.toSet());
    }

    private static StageDecisionDto decision(List<StageDecisionDto> decisions, String stage) {
        return decisions.stream().filter(d -> d.stage().equals(stage)).findFirst()
            .orElseThrow(() -> new AssertionError("no decision for " + stage));
    }

    private static boolean allowedFor(List<StageDecisionDto> decisions, String stage) {
        return decision(decisions, stage).allowed();
    }

    private static String reasonFor(List<StageDecisionDto> decisions, String stage) {
        return decision(decisions, stage).blockedReason();
    }

    private long readyToAdvanceFrom(String stage) {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, stage);
        logAnActivityAndFollowUp(ticketId);
        return ticketId;
    }

    private void logAnActivityAndFollowUp(long ticketId) {
        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, null, null, null, LocalDate.now().plusDays(3)), ownerRep);
        ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), ownerRep);
    }

    private long createDealWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบการตัดสินขั้นตอน", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), ownerRepId, "เจ้าของดีล ทดสอบ");
    }

    private String stageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private int stageChangedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STAGE_CHANGED), Integer.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-decide@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
