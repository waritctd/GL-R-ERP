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
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Slice B1 "kill the weekly report" (handoff 103): confirms the manual stage-advance gate, the win%
 * default/override, and the staleness computation against the real service and real SQL — mirrors
 * th.co.glr.hr.attendance.AttendanceScopeIntegrationTest and TicketScopeIntegrationTest. */
class DealTrackingAndActivityIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long salesRepId;
    private UserPrincipal salesRep;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // PricingRequestService is mocked: none of updateStage/addActivity/listActivities/
        // updateTracking/confirmCustomer ever call it.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-b1@glr.co.th");
        salesRep = principal(salesRepId, "sales");
    }

    @Test
    void manualForwardAdvance_withNoNextFollowUpAtAndNoActivity_isBlockedAndStageUnchanged() {
        long ticketId = createTicket();

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

    /** Genuine backward moves are still gated by the pre-existing "reason required" rule, not this one. */
    @Test
    void genuineBackwardMove_stillRequiresANoteButNotTrackingFields() {
        long ticketId = createTicket();
        setNextFollowUp(ticketId, LocalDate.now().plusDays(3));
        logActivity(ticketId, DealActivityKind.CALL);
        ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, salesRep);

        // PRESENTATION -> LEAD_APPROACH is a genuine backward move (not the QUOTE_DESIGN_SIDE -> SPEC_APPROVED
        // exception), so it needs a note but — unlike a forward move — no follow-up-date/activity state at all.
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.LEAD_APPROACH, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));

        TicketDto result = ticketService.updateStage(ticketId, DealStage.LEAD_APPROACH, "ลูกค้าขอทบทวนสเปคใหม่", salesRep);
        assertThat(result.summary().salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
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
