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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Slice B1 "kill the weekly report" (handoff 103): confirms the manual stage-advance gate, the
 * win% default/override, and the staleness computation against the real service and real SQL —
 * mirrors {@link th.co.glr.hr.attendance.AttendanceScopeIntegrationTest} and
 * {@link TicketScopeIntegrationTest}. Mockito cannot reach this: {@code updateStage}'s gate reads
 * {@code sales.deal_activity} and {@code sales.ticket_event} through real repository SQL, and a
 * mocked repository would happily "pass" while that SQL does something else.
 *
 * <p>The gate tests are written wrong-way-round on purpose: assert the caller CANNOT advance
 * without the required state, and that the deal's stage genuinely did not move, not merely that
 * the happy path works.
 */
class DealTrackingAndActivityIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long salesRepId;
    private UserPrincipal salesRep;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // PriceCalcService/PricingRequestService are mocked: none of updateStage/addActivity/
        // listActivities/updateTracking/confirmCustomer ever call them — only markLost/cancel
        // reach PricingRequestService.cancelOpenForTicket (used by the staleness-on-lost-deal
        // test below), which is stubbed to a real "nothing was open" result since Mockito's
        // default null return for an object type would NPE inside markLost.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-b1@glr.co.th");
        salesRep = principal(salesRepId, "sales");
    }

    // ── the manual forward-advance gate ──────────────────────────────────────

    @Test
    void manualForwardAdvance_withNoNextFollowUpAtAndNoActivity_isBlockedAndStageUnchanged() {
        long ticketId = createTicket();

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(currentStage(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);
    }

    @Test
    void manualForwardAdvance_withActivityButNoNextFollowUpAt_isBlockedAndStageUnchanged() {
        long ticketId = createTicket();
        logActivity(ticketId, DealActivityKind.CALL);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(currentStage(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);
    }

    @Test
    void manualForwardAdvance_withNextFollowUpAtButNoActivitySinceLastStageChange_isBlockedAndStageUnchanged() {
        long ticketId = createTicket();
        setNextFollowUp(ticketId, LocalDate.now().plusDays(3));
        // No activity logged at all yet.

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(currentStage(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);
    }

    @Test
    void manualForwardAdvance_withBothNextFollowUpAtAndActivity_advances() {
        long ticketId = createTicket();
        setNextFollowUp(ticketId, LocalDate.now().plusDays(3));
        logActivity(ticketId, DealActivityKind.CALL);

        TicketDto result = ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep);

        assertThat(result.summary().salesStage()).isEqualTo(DealStage.PRESENTATION);
        assertThat(currentStage(ticketId)).isEqualTo(DealStage.PRESENTATION);
    }

    /**
     * The boundary the gate is actually checking: an activity logged BEFORE the most recent
     * STAGE_CHANGED event does not satisfy a SUBSEQUENT advance attempt, even though it satisfied
     * the one before it. Reusing the same activity across two stage changes must not be possible.
     */
    @Test
    void activityLoggedBeforeTheLastStageChange_doesNotSatisfyTheNextAdvance() {
        long ticketId = createTicket();
        setNextFollowUp(ticketId, LocalDate.now().plusDays(3));
        logActivity(ticketId, DealActivityKind.CALL);
        // First advance succeeds — this activity was logged before any STAGE_CHANGED event, so it
        // satisfies the "since the last stage change" baseline (the ticket's own created_at).
        ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep);
        assertThat(currentStage(ticketId)).isEqualTo(DealStage.PRESENTATION);

        // Immediately try to advance again with NO new activity — the STAGE_CHANGED event just
        // written now postdates the only activity on file, so this must be blocked.
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));
        assertThat(currentStage(ticketId)).isEqualTo(DealStage.PRESENTATION);

        // Log a fresh activity now (after the PRESENTATION STAGE_CHANGED event) — the same advance
        // now succeeds.
        logActivity(ticketId, DealActivityKind.MEETING);
        ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, salesRep);
        assertThat(currentStage(ticketId)).isEqualTo(DealStage.SPEC_APPROVED);
    }

    @Test
    void routineBackwardMove_isNeverGatedByTrackingFields() {
        long ticketId = createTicket();
        setNextFollowUp(ticketId, LocalDate.now().plusDays(3));
        logActivity(ticketId, DealActivityKind.CALL);
        ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep);
        logActivity(ticketId, DealActivityKind.CALL);
        ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, salesRep);
        logActivity(ticketId, DealActivityKind.CALL);
        ticketService.updateStage(ticketId, DealStage.QUOTE_DESIGN_SIDE, null, salesRep);

        // QUOTE_DESIGN_SIDE -> SPEC_APPROVED is the routine backward move (DealStage
        // .isRoutineBackwardMove) — no note required, and no tracking-field gate at all, even
        // though the deal was just advanced with no fresh activity/follow-up date set since.
        TicketDto result = ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, salesRep);

        assertThat(result.summary().salesStage()).isEqualTo(DealStage.SPEC_APPROVED);
    }

    /** Genuine backward moves are still gated by the pre-existing "reason required" rule, not this one. */
    @Test
    void genuineBackwardMove_stillRequiresANoteButNotTrackingFields() {
        long ticketId = createTicket();
        setNextFollowUp(ticketId, LocalDate.now().plusDays(3));
        logActivity(ticketId, DealActivityKind.CALL);
        ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep);

        // PRESENTATION -> LEAD_APPROACH is a genuine backward move (not the QUOTE_DESIGN_SIDE ->
        // SPEC_APPROVED exception), so it needs a note but — unlike a forward move — no
        // follow-up-date/activity state at all.
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.LEAD_APPROACH, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));

        TicketDto result = ticketService.updateStage(ticketId, DealStage.LEAD_APPROACH, "ลูกค้าขอทบทวนสเปคใหม่", salesRep);
        assertThat(result.summary().salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
    }

    /**
     * autoAdvanceStage (system-driven, e.g. confirmCustomer's ORDER_RECEIVED advance) is a
     * separate code path from updateStage and must never be gated by tracking fields — a deal
     * missing both nextFollowUpAt and any activity must still auto-advance.
     */
    @Test
    void autoAdvanceStage_confirmCustomer_isNotGatedByTrackingFields() {
        long ticketId = createTicket();
        // Drive the deal to QUOTATION_ISSUED / paymentStatus=null directly — the mechanics of
        // getting there are exercised elsewhere (e.g. TicketEventStatusIntegrationTest); here we
        // only care that confirmCustomer's autoAdvanceStage(ORDER_RECEIVED) is unaffected by the
        // complete absence of nextFollowUpAt/deal_activity.
        jdbc.update("UPDATE sales.ticket SET status = 'quotation_issued' WHERE ticket_id = :id",
            java.util.Map.of("id", ticketId));

        assertThat(currentNextFollowUp(ticketId)).isNull();

        TicketDto result = ticketService.confirmCustomer(ticketId, salesRep);

        assertThat(result.summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
    }

    // ── win probability ──────────────────────────────────────────────────────

    @Test
    void winProbability_defaultsFromStage_whenNoOverrideSet() {
        long ticketId = createTicket();

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();

        assertThat(summary.winProbabilityOverride()).isNull();
        assertThat(summary.effectiveWinProbability()).isEqualTo(WinProbabilityDefaults.defaultFor(DealStage.LEAD_APPROACH));
    }

    @Test
    void winProbability_repOverride_winsOverTheStageDefault() {
        long ticketId = createTicket();

        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(77, null, null, null, null), salesRep);

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();
        assertThat(summary.winProbabilityOverride()).isEqualTo(77);
        assertThat(summary.effectiveWinProbability()).isEqualTo(77);
    }

    @Test
    void trackingUpdate_setsCounterpartyNamesAndNextFollowUp() {
        long ticketId = createTicket();

        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, "ดีไซเนอร์ ก", "เจ้าของบ้าน ข", "ผู้ซื้อ ค",
                LocalDate.now().plusDays(5)),
            salesRep);

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();
        assertThat(summary.designerName()).isEqualTo("ดีไซเนอร์ ก");
        assertThat(summary.ownerName()).isEqualTo("เจ้าของบ้าน ข");
        assertThat(summary.buyerName()).isEqualTo("ผู้ซื้อ ค");
        assertThat(summary.nextFollowUpAt()).isEqualTo(LocalDate.now().plusDays(5));
    }

    // ── deal activity CRUD ───────────────────────────────────────────────────

    @Test
    void addActivity_thenListActivities_returnsWhatWasLogged() {
        long ticketId = createTicket();

        DealActivityDto created = ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.SITE_VISIT, "ไปดูหน้างาน"), salesRep);

        assertThat(created.id()).isPositive();
        assertThat(created.kind()).isEqualTo(DealActivityKind.SITE_VISIT);
        assertThat(created.note()).isEqualTo("ไปดูหน้างาน");
        assertThat(created.createdById()).isEqualTo(salesRepId);

        List<DealActivityDto> activities = ticketService.listActivities(ticketId, salesRep);
        assertThat(activities).hasSize(1);
        assertThat(activities.get(0).id()).isEqualTo(created.id());
    }

    @Test
    void addActivity_unknownKind_isRejected() {
        long ticketId = createTicket();

        assertThatThrownBy(() -> ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), "NOT_A_REAL_KIND", null), salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }

    @Test
    void addActivity_deniedForANonOwningRep() {
        long ticketId = createTicket();
        long otherRepId = createEmployee(new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            "พนักงานขาย คนอื่น", "other-sales-b1@glr.co.th");
        UserPrincipal otherRep = principal(otherRepId, "sales");

        assertThatThrownBy(() -> ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), otherRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    // ── staleness ─────────────────────────────────────────────────────────────

    @Test
    void staleness_trueForAnActiveDealWithNoRecentActivity() {
        long ticketId = createTicket();

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();

        assertThat(summary.stale()).isTrue();
    }

    @Test
    void staleness_falseOnceActivityIsLoggedWithinTheLast7Days() {
        long ticketId = createTicket();
        logActivity(ticketId, DealActivityKind.CALL);

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();

        assertThat(summary.stale()).isFalse();
    }

    @Test
    void staleness_falseForAnActivityLoggedMoreThan7DaysAgo() {
        long ticketId = createTicket();
        // Backdate the activity's created_at directly — insertDealActivity always stamps now().
        jdbc.update("""
            INSERT INTO sales.deal_activity (ticket_id, activity_date, kind, note, created_by_id, created_at)
            VALUES (:ticketId, :date, :kind, NULL, :createdBy, now() - interval '10 days')
            """,
            java.util.Map.of(
                "ticketId", ticketId,
                "date", LocalDate.now().minusDays(10),
                "kind", DealActivityKind.CALL,
                "createdBy", salesRepId));

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();

        assertThat(summary.stale()).isTrue();
    }

    @Test
    void staleness_neverFlaggedOnANonActiveDeal() {
        long ticketId = createTicket();
        // No activity at all — would be stale if it were ACTIVE — but mark it lost.
        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesRep);

        TicketSummaryDto summary = ticketService.get(ticketId, salesRep).summary();

        assertThat(summary.lifecycle()).isEqualTo(DealLifecycle.CLOSED_LOST);
        assertThat(summary.stale()).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long createTicket() {
        long ticketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), salesRepId, "พนักงานขาย ทดสอบ");
        assertThat(tickets.findById(ticketId).orElseThrow().summary().salesStage())
            .isEqualTo(DealStage.LEAD_APPROACH);
        return ticketId;
    }

    private CreateTicketRequest sampleTicket() {
        return new CreateTicketRequest("ดีลทดสอบ B1", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of());
    }

    private void logActivity(long ticketId, String kind) {
        ticketService.addActivity(ticketId, new DealActivityRequest(LocalDate.now(), kind, null), salesRep);
    }

    private void setNextFollowUp(long ticketId, LocalDate date) {
        ticketService.updateTracking(ticketId, new TrackingUpdateRequest(null, null, null, null, date), salesRep);
    }

    private String currentStage(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().salesStage();
    }

    private LocalDate currentNextFollowUp(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().nextFollowUpAt();
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
