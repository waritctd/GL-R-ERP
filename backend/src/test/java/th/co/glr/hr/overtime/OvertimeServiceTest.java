package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

class OvertimeServiceTest {
    private final OvertimeRepository overtimeRepository = mock(OvertimeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    private final OvertimeService overtimeService = new OvertimeService(
        overtimeRepository,
        auditService,
        notificationService,
        appProperties
    );

    // Work date must stay within the 3-day advance-notice window from "now" (see the
    // advance-notice check in OvertimeService.submit). Computed relative to today so these
    // fixtures don't rot into failures as the calendar advances past a hardcoded date.
    private static final LocalDate WORK_DATE = LocalDate.now().plusDays(10);
    private static final LocalDate PAYROLL_MONTH = WORK_DATE.withDayOfMonth(1);
    private static final OffsetDateTime WORK_START = WORK_DATE.atTime(18, 0).atOffset(java.time.ZoneOffset.ofHours(7));
    private static final OffsetDateTime WORK_END = WORK_DATE.atTime(20, 0).atOffset(java.time.ZoneOffset.ofHours(7));

    @Test
    void employeesCanSubmitOwnOvertime() {
        SubmitOvertimeRequest request = validSubmit(null);
        OvertimeRequestDto created = requestDto(55L, 10L, "SUBMITTED");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(PAYROLL_MONTH)))
            .thenReturn(55L);
        when(overtimeRepository.findById(55L)).thenReturn(Optional.of(created));
        UserPrincipal employee = user("employee", 10L);

        OvertimeRequestDto result = overtimeService.submit(request, employee);

        assertThat(result.id()).isEqualTo(55L);
        verify(overtimeRepository).create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(PAYROLL_MONTH));
        verify(auditService).record(employee, "SUBMIT_OVERTIME_REQUEST", "overtime_request", 55L, null, created);
        verify(notificationService).notify(eq(10L), eq("OVERTIME_SUBMITTED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(99L), eq("OVERTIME_PENDING_MANAGER"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void employeesCannotSubmitForAnotherEmployee() {
        when(overtimeRepository.findEmployeeAccess(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overtimeService.submit(validSubmit(11L), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(overtimeRepository).findEmployeeAccess(11L);
    }

    @Test
    void nonApproversCanOnlyListOwnRequests() {
        when(overtimeRepository.findEmployeeAccess(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overtimeService.list(
                user("employee", 10L),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                11L,
                null))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void hrCanListAllRequests() {
        when(overtimeRepository.findRequests(any(OvertimeFilter.class))).thenReturn(List.of());

        overtimeService.list(
            user("hr", 10L),
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-30"),
            null,
            "submitted"
        );

        verify(overtimeRepository).findRequests(new OvertimeFilter(
            null,
            null,
            null,
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-30"),
            OvertimeStatus.SUBMITTED
        ));
    }

    @Test
    void directManagersCanSubmitOvertimeForReportsWithAdvanceNotice() {
        SubmitOvertimeRequest request = new SubmitOvertimeRequest(
            10L,
            WORK_DATE,
            WORK_START,
            WORK_END,
            "HOLIDAY",
            "Urgent delivery"
        );
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, null, true)));
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(99L), eq(request), eq(120), eq(OvertimeDayType.HOLIDAY), eq(PAYROLL_MONTH)))
            .thenReturn(56L);
        when(overtimeRepository.findById(56L)).thenReturn(Optional.of(requestDto(56L, 10L, "SUBMITTED")));

        OvertimeRequestDto result = overtimeService.submit(request, user("employee", 99L));

        assertThat(result.id()).isEqualTo(56L);
        verify(overtimeRepository).create(eq(10L), eq(99L), eq(request), eq(120), eq(OvertimeDayType.HOLIDAY), eq(PAYROLL_MONTH));
    }

    @Test
    void submitRequiresThreeDayAdvanceNotice() {
        SubmitOvertimeRequest request = new SubmitOvertimeRequest(
            null,
            LocalDate.now().plusDays(2),
            OffsetDateTime.now().plusDays(2),
            OffsetDateTime.now().plusDays(2).plusHours(2),
            "WORKDAY",
            "Too soon"
        );
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void managerApprovalTransitionsSubmittedToManagerApprovedAndCalculatesPayableMinutes() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(managerApproved));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, null, true)));
        when(overtimeRepository.findAttendanceBounds(eq(10L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(new OvertimeAttendanceBounds(
                OffsetDateTime.parse("2026-07-15T08:05:00+07:00"),
                OffsetDateTime.parse("2026-07-15T19:40:00+07:00")
            )));
        when(overtimeRepository.managerApprove(eq(77L), eq(99L), any(OvertimeCalculation.class), eq("ok")))
            .thenReturn(1);
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));
        UserPrincipal actor = user("employee", 99L);

        OvertimeRequestDto result = overtimeService.approve(77L, new ReviewOvertimeRequest("ok"), actor);

        ArgumentCaptor<OvertimeCalculation> captor = ArgumentCaptor.forClass(OvertimeCalculation.class);
        verify(overtimeRepository).managerApprove(eq(77L), eq(99L), captor.capture(), eq("ok"));
        OvertimeCalculation calculation = captor.getValue();
        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
        assertThat(calculation.actualStartAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T18:00:00+07:00"));
        assertThat(calculation.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T19:40:00+07:00"));
        assertThat(calculation.actualMinutes()).isEqualTo(100);
        assertThat(calculation.payableMinutes()).isEqualTo(100);
        verify(auditService).record(eq(actor), eq("MANAGER_APPROVE_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(submitted), any(OvertimeRequestDto.class));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_MANAGER_APPROVED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(500L), eq("OVERTIME_PENDING_CEO"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void ceoApprovalTransitionsManagerApprovedToApproved() {
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        OvertimeRequestDto approved = requestDto(77L, 10L, "APPROVED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(managerApproved))
            .thenReturn(Optional.of(approved));
        when(overtimeRepository.ceoApprove(77L, 500L, "ok")).thenReturn(1);
        UserPrincipal ceo = user("ceo", 500L);

        OvertimeRequestDto result = overtimeService.approve(77L, new ReviewOvertimeRequest("ok"), ceo);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(overtimeRepository).ceoApprove(77L, 500L, "ok");
        verify(auditService).record(eq(ceo), eq("CEO_APPROVE_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(managerApproved), eq(approved));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_APPROVED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(99L), eq("OVERTIME_APPROVED"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void managerRejectionTransitionsSubmittedToRejected() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto rejected = requestDto(77L, 10L, "REJECTED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(rejected));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, null, true)));
        when(overtimeRepository.reject(77L, 99L, "no budget")).thenReturn(1);
        UserPrincipal manager = user("employee", 99L);

        OvertimeRequestDto result = overtimeService.reject(77L, new ReviewOvertimeRequest("no budget"), manager);

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(overtimeRepository).reject(77L, 99L, "no budget");
        verify(auditService).record(eq(manager), eq("REJECT_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(submitted), eq(rejected));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_REJECTED"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void ceoRejectionTransitionsManagerApprovedToRejected() {
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        OvertimeRequestDto rejected = requestDto(77L, 10L, "REJECTED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(managerApproved))
            .thenReturn(Optional.of(rejected));
        when(overtimeRepository.ceoReject(77L, 500L, "not justified")).thenReturn(1);
        UserPrincipal ceo = user("ceo", 500L);

        OvertimeRequestDto result = overtimeService.reject(77L, new ReviewOvertimeRequest("not justified"), ceo);

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(overtimeRepository).ceoReject(77L, 500L, "not justified");
        verify(auditService).record(eq(ceo), eq("CEO_REJECT_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(managerApproved), eq(rejected));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_REJECTED"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void managerCannotCeoRejectManagerApprovedOvertime() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "MANAGER_APPROVED")));

        assertThatThrownBy(() -> overtimeService.reject(77L, new ReviewOvertimeRequest(null), manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void employeesCannotApproveOvertime() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED")));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, null, true)));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ReviewOvertimeRequest(null), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void divisionManagerCanApproveOvertimeForDivisionPeer() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(managerApproved));
        // Employee 10 is in division 5 with no reports-to link; the actor is a division-5 manager.
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true)));
        when(overtimeRepository.findAttendanceBounds(eq(10L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        when(overtimeRepository.managerApprove(eq(77L), eq(88L), any(OvertimeCalculation.class), eq("ok"))).thenReturn(1);
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of());

        OvertimeRequestDto result = overtimeService.approve(77L, new ReviewOvertimeRequest("ok"), manager(88L, 5L));

        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
        verify(overtimeRepository).managerApprove(eq(77L), eq(88L), any(OvertimeCalculation.class), eq("ok"));
    }

    @Test
    void managerCannotCeoApproveManagerApprovedOvertime() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "MANAGER_APPROVED")));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ReviewOvertimeRequest(null), manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void divisionManagerCannotApproveOvertimeForOtherDivision() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED")));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 7L, true)));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ReviewOvertimeRequest(null), manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ownerCanCancelOwnSubmittedOvertime() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto cancelled = requestDto(77L, 10L, "CANCELLED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L))
            .thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));
        when(overtimeRepository.cancel(77L, null, "owner cancel")).thenReturn(1);

        OvertimeRequestDto result = overtimeService.cancel(77L, new ReviewOvertimeRequest(" owner cancel "), user("employee", 10L));

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(overtimeRepository).cancel(77L, null, "owner cancel");
    }

    @Test
    void cancelInvalidStateReturnsConflict() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "REJECTED")));

        assertThatThrownBy(() -> overtimeService.cancel(77L, new ReviewOvertimeRequest(null), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unauthorizedCancelReturnsForbidden() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED")));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overtimeService.cancel(77L, new ReviewOvertimeRequest(null), user("employee", 11L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cancelMissingRequestReturnsNotFound() {
        when(overtimeRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overtimeService.cancel(404L, new ReviewOvertimeRequest(null), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private SubmitOvertimeRequest validSubmit(Long employeeId) {
        return new SubmitOvertimeRequest(
            employeeId,
            WORK_DATE,
            WORK_START,
            WORK_END,
            "WORKDAY",
            "Customer shipment"
        );
    }

    private OvertimeRequestDto requestDto(long id, long employeeId, String status) {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-15T18:00:00+07:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-07-15T20:00:00+07:00");
        return new OvertimeRequestDto(
            id,
            employeeId,
            "EMP001",
            "Test Employee",
            LocalDate.parse("2026-07-15"),
            start,
            end,
            120,
            "WORKDAY",
            new BigDecimal("1.50"),
            "Customer shipment",
            status,
            null,
            null,
            0,
            0,
            null,
            LocalDate.parse("2026-07-01"),
            employeeId,
            "Test Employee",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            isManagerApproved(status) ? 99L : null,
            isManagerApproved(status) ? "Test Manager" : null,
            isManagerApproved(status) ? OffsetDateTime.parse("2026-06-14T11:00:00+07:00") : null,
            "APPROVED".equals(status) ? 500L : null,
            "APPROVED".equals(status) ? "Test CEO" : null,
            "APPROVED".equals(status) ? OffsetDateTime.parse("2026-06-14T12:00:00+07:00") : null,
            null,
            null,
            null,
            null,
            null,
            99L,
            "Test Manager",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00")
        );
    }

    private boolean isManagerApproved(String status) {
        return "MANAGER_APPROVED".equals(status) || "APPROVED".equals(status);
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }

    // A ฝ่าย manager: base employee role, manager flag set, scoped to a division.
    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, "mgr@glr.co.th", "Manager", "employee", employeeId, true, LocalDate.now(), false, divisionId, true);
    }
}
