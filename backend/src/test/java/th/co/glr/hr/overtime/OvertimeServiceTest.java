package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;

class OvertimeServiceTest {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    /** See {@link #scheduleResolver}'s field comment and {@link #assumeEveryDayIsAScheduledWorkday}. */
    private static final WorkSchedule EVERY_DAY_IS_A_WORKDAY = new WorkSchedule(
        BANGKOK, LocalTime.of(8, 30), LocalTime.of(17, 30), 15, EnumSet.allOf(DayOfWeek.class));

    private final OvertimeRepository overtimeRepository = mock(OvertimeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    // Approving/cancelling overtime re-derives that attendance day; stubbed here so these cases stay about the
    // overtime rules. The real sync is covered by the integration tests.
    private final AttendanceDailyService attendanceDailyService = mock(AttendanceDailyService.class);
    private final ManagerApproverRepository managerApproverRepository = mock(ManagerApproverRepository.class);
    // Defaults every date to "not a holiday" (Mockito's boolean default is false) unless a test stubs a specific
    // date -- i.e. every case in this class gets WORKDAY/1.50x unless it opts in. Year coverage is handled
    // separately by assumeTheHolidayCalendarIsLoadedForTheYear below.
    private final HolidayCalendar holidayCalendar = mock(HolidayCalendar.class);
    // feat/ot-nonworkday-rate-suggestion: a Mockito object-return stub defaults to null, which would NPE the
    // instant suggestDayType calls schedule.isWorkday(...).
    private final WorkScheduleResolver scheduleResolver = mock(WorkScheduleResolver.class);
    private final CeoApproverRepository ceoApprovers = mock(CeoApproverRepository.class);
    private final OvertimeService overtimeService = new OvertimeService(
        overtimeRepository,
        managerApproverRepository,
        auditService,
        notificationService,
        appProperties,
        attendanceDailyService,
        holidayCalendar,
        scheduleResolver,
        ceoApprovers
    );

    /** A Mockito boolean stub defaults to false, which here would mean "no manager stage" and would silently route
     * every SUBMITTED approval in this class down the CEO-direct path — the manager-approval cases would then pass
     * while testing something else entirely. */
    @BeforeEach
    void assumeAManagerStageExists() {
        when(managerApproverRepository.hasManagerApprover(anyLong())).thenReturn(true);
    }

    /** A Mockito boolean stub defaults to false, which here would mean "the calendar has never been loaded for this
     * year" and would attach OvertimeService#resolveDayTypeSubmitNote's unverified-calendar flag to every
     * submission in this class that does not stub a work date's year explicitly. */
    @BeforeEach
    void assumeTheHolidayCalendarIsLoadedForTheYear() {
        when(holidayCalendar.hasHolidaysForYear(anyInt())).thenReturn(true);
    }

    /** feat/ot-nonworkday-rate-suggestion: see #scheduleResolver's field comment. */
    @BeforeEach
    void assumeEveryDayIsAScheduledWorkday() {
        when(scheduleResolver.resolve(anyLong(), any(), any(), any(LocalDate.class)))
            .thenReturn(EVERY_DAY_IS_A_WORKDAY);
    }

    @Test
    void employeesCanSubmitOwnOvertime() {
        SubmitOvertimeRequest request = validSubmit(null);
        OvertimeRequestDto created = requestDto(55L, 10L, "SUBMITTED");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(request.workDate().withDayOfMonth(1)), isNull()))
            .thenReturn(55L);
        when(overtimeRepository.findById(55L)).thenReturn(Optional.of(created));
        // The submit notification now goes to whoever can approve, read from the same source as the routing decision
        // -- NOT to reports_to, who no longer approves anything.
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of(99L));
        UserPrincipal employee = user("employee", 10L);

        OvertimeRequestDto result = overtimeService.submit(request, employee);

        assertThat(result.id()).isEqualTo(55L);
        verify(overtimeRepository).create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(request.workDate().withDayOfMonth(1)), isNull());
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

    // Payroll derives overtime by payroll_month and writes a period once, so a request landing in a processed
    // month would be approved and then never paid.
    @Test
    void retroactiveOvertimeIntoProcessedPayrollMonthIsRejected() {
        SubmitOvertimeRequest request = backdatedSubmit(2, "Filed late after the customer escalation closed");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.payrollMonthProcessed(request.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // A request filed before the cut-off can still reach approval after payroll has run.
    @Test
    void managerApprovalIntoProcessedPayrollMonthIsRejected() {
        OvertimeRequestDto submitted = requestDto(78L, 10L, "SUBMITTED");
        when(overtimeRepository.findById(78L)).thenReturn(Optional.of(submitted));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true, false)));
        when(overtimeRepository.payrollMonthProcessed(submitted.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.approve(78L, new ApproveOvertimeRequest("ok", null), manager(99L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(overtimeRepository, never())
            .managerApprove(anyLong(), anyLong(), any(OvertimeCalculation.class), any(BigDecimal.class), anyString());
    }

    @Test
    void ceoApprovalIntoASeedCoveredPayrollMonthIsRejected() {
        OvertimeRequestDto managerApproved = requestDto(79L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(79L)).thenReturn(Optional.of(managerApproved));
        when(overtimeRepository.payrollMonthProcessed(managerApproved.workDate().withDayOfMonth(1))).thenReturn(false);
        when(overtimeRepository.payrollMonthSeedCovered(managerApproved.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.approve(79L, new ApproveOvertimeRequest("ok", null), user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("จ่ายนอกระบบ")
            .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void managerApprovalTransitionsSubmittedToManagerApprovedAndCalculatesPayableMinutes() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(managerApproved));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true, false)));
        when(overtimeRepository.findAttendanceBounds(eq(10L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(new OvertimeAttendanceBounds(
                OffsetDateTime.parse("2026-07-15T08:05:00+07:00"),
                OffsetDateTime.parse("2026-07-15T19:40:00+07:00")
            )));
        when(overtimeRepository.findSalaryBasisAsOf(10L, LocalDate.parse("2026-07-15")))
            .thenReturn(new BigDecimal("30000.00"));
        when(overtimeRepository.managerApprove(
                eq(77L), eq(99L), any(OvertimeCalculation.class), eq(new BigDecimal("30000.00")), eq("ok")))
            .thenReturn(1);
        when(ceoApprovers.findEmployeeIds()).thenReturn(List.of(500L));
        UserPrincipal actor = manager(99L, 5L);

        OvertimeRequestDto result = overtimeService.approve(77L, new ApproveOvertimeRequest("ok", null), actor);

        ArgumentCaptor<OvertimeCalculation> captor = ArgumentCaptor.forClass(OvertimeCalculation.class);
        verify(overtimeRepository).managerApprove(
            eq(77L), eq(99L), captor.capture(), eq(new BigDecimal("30000.00")), eq("ok"));
        OvertimeCalculation calculation = captor.getValue();
        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
        assertThat(calculation.actualStartAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T18:00:00+07:00"));
        assertThat(calculation.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T19:40:00+07:00"));
        assertThat(calculation.actualMinutes()).isEqualTo(100);
        assertThat(calculation.payableMinutes()).isEqualTo(100);
        verify(auditService).record(eq(actor), eq("MANAGER_APPROVE_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(submitted), any(OvertimeRequestDto.class));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_MANAGER_APPROVED"), anyString(), anyString(), eq("/overtime"), eq(true));
        ArgumentCaptor<String> ceoBody = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(500L), eq("OVERTIME_PENDING_CEO"), anyString(), ceoBody.capture(), eq("/overtime"), eq(true));
        // this is a DIFFERENT "OVERTIME_PENDING_CEO" body than the manager-less route's (notifyManagerApproved's, not
        // notifySubmitted's) and never carried the retired framing -- pinned anyway so a future edit that unifies the
        // two CEO notification bodies cannot reintroduce it here either.
        assertThat(ceoBody.getValue()).doesNotContain("ขั้นอนุมัติของหัวหน้างาน");
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

        OvertimeRequestDto result = overtimeService.approve(77L, new ApproveOvertimeRequest("ok", null), ceo);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(overtimeRepository).ceoApprove(77L, 500L, "ok");
        verify(auditService).record(eq(ceo), eq("CEO_APPROVE_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(managerApproved), eq(approved));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_APPROVED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(99L), eq("OVERTIME_APPROVED"), anyString(), anyString(), eq("/overtime"), eq(true));
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
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true, false)));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ApproveOvertimeRequest(null, null), user("employee", 10L)))
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
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true, false)));
        when(overtimeRepository.findAttendanceBounds(eq(10L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        when(overtimeRepository.findSalaryBasisAsOf(10L, LocalDate.parse("2026-07-15")))
            .thenReturn(new BigDecimal("30000.00"));
        when(overtimeRepository.managerApprove(
                eq(77L), eq(88L), any(OvertimeCalculation.class), any(BigDecimal.class), eq("ok")))
            .thenReturn(1);
        when(ceoApprovers.findEmployeeIds()).thenReturn(List.of());

        OvertimeRequestDto result = overtimeService.approve(77L, new ApproveOvertimeRequest("ok", null), manager(88L, 5L));

        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
        verify(overtimeRepository).managerApprove(
            eq(77L), eq(88L), any(OvertimeCalculation.class), any(BigDecimal.class), eq("ok"));
    }

    /** CEO-approval-reach follow-on: decision-level twin of #divisionManagerCanApproveOvertimeForDivisionPeer with
     * reportsToExecutive flipped true. */
    @Test
    void divisionManagerCannotApproveOvertimeForAnEmployeeReportingToAnExecutive() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED")));
        // Same division-5 shape as divisionManagerCanApproveOvertimeForDivisionPeer -- only reportsToExecutive
        // differs.
        when(overtimeRepository.findEmployeeAccess(10L))
            .thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true, true)));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ApproveOvertimeRequest("ok", null), manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(overtimeRepository, never()).managerApprove(
            anyLong(), anyLong(), any(OvertimeCalculation.class), any(BigDecimal.class), anyString());
    }

    @Test
    void divisionManagerCannotApproveOvertimeForOtherDivision() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED")));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 7L, true, false)));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ApproveOvertimeRequest(null, null), manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
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

    /** CEO-approval-reach follow-on (2026-09-01 owner ruling): "whoever approves it can also cancel it". A ceo
     * actor now gets manager-equivalent reach here. */
    @Test
    void ceoCanCancelApprovedOvertimeForAnEmployeeWithNoManagerStage() {
        OvertimeRequestDto approved = requestDto(77L, 10L, "APPROVED");
        OvertimeRequestDto cancelled = requestDto(77L, 10L, "CANCELLED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(approved))
            .thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.empty());
        // Overrides this class's assumeAManagerStageExists default (true) for employee 10L only -- the manager-less
        // case this owner ruling is about.
        when(managerApproverRepository.hasManagerApprover(10L)).thenReturn(false);
        when(overtimeRepository.cancel(77L, 500L, "ceo cancel")).thenReturn(1);

        OvertimeRequestDto result = overtimeService.cancel(
            77L, new ReviewOvertimeRequest("ceo cancel"), user("ceo", 500L));

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(overtimeRepository).cancel(77L, 500L, "ceo cancel");
    }

    /** Wrong-way-round #1 for the ruling above: the new reach is conditioned on !hasManagerStage, not on role
     * alone. */
    @Test
    void ceoCannotCancelOvertimeForAnEmployeeWhoHasAManagerStage() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "APPROVED")));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.empty());
        // hasManagerApprover(10L) stays at this class's default true (assumeAManagerStageExists) -- this employee DOES
        // have a manager stage, unlike the positive-control test above.

        assertThatThrownBy(() -> overtimeService.cancel(
                77L, new ReviewOvertimeRequest(null), user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(overtimeRepository, never()).cancel(anyLong(), any(), any());
    }

    // feat/ot-nonworkday-rate-suggestion: fast, mock-level companions to
    // OvertimeDayTypeDerivedFromCalendarIntegrationTest's real-DB proof (Cases 6-9 there).

    /** The approver's DECISION wins over the suggestion. This fixture's default stubs (every day a workday, not a
     * holiday) suggest WORKDAY for requestDto's fixed 2026-07-15 workDate -- the explicit "HOLIDAY" override must
     * still be what freezes into the calculation. */
    @Test
    void managerApproveHonoursAnExplicitDayTypeOverride() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(managerApproved));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true, false)));
        when(overtimeRepository.findAttendanceBounds(eq(10L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
            .thenReturn(Optional.empty());
        when(overtimeRepository.findSalaryBasisAsOf(10L, LocalDate.parse("2026-07-15")))
            .thenReturn(new BigDecimal("30000.00"));
        when(overtimeRepository.managerApprove(
                eq(77L), eq(99L), any(OvertimeCalculation.class), any(BigDecimal.class), eq("override, data was wrong")))
            .thenReturn(1);

        overtimeService.approve(77L, new ApproveOvertimeRequest("override, data was wrong", "HOLIDAY"), manager(99L, 5L));

        ArgumentCaptor<OvertimeCalculation> captor = ArgumentCaptor.forClass(OvertimeCalculation.class);
        verify(overtimeRepository).managerApprove(
            eq(77L), eq(99L), captor.capture(), any(BigDecimal.class), eq("override, data was wrong"));
        assertThat(captor.getValue().dayType())
            .as("the approver's explicit override must win over the suggestion (this fixture's default: WORKDAY)")
            .isEqualTo(OvertimeDayType.HOLIDAY);
    }

    private SubmitOvertimeRequest validSubmit(Long employeeId) {
        LocalDate workDate = LocalDate.now().plusDays(4);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(java.time.ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(
            employeeId,
            workDate,
            startAt,
            startAt.plusHours(2),
            "WORKDAY",
            "Customer shipment"
        );
    }

    /** A self-filed request daysAgo in the past (0 = today), with the given reason. "Today" is resolved in
     * Asia/Bangkok to match OvertimeService.BUSINESS_ZONE. */
    private SubmitOvertimeRequest backdatedSubmit(int daysAgo, String reason) {
        LocalDate workDate = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).minusDays(daysAgo);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(java.time.ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(null, workDate, startAt, startAt.plusHours(2), "WORKDAY", reason);
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
            true,
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            // feat/pending-approver-info: computed by OvertimeRepository#mapRequest (a real SQL row mapper this
            // Mockito-level class never exercises), so no test in this class asserts on them via the helper.
            null,
            null,
            // feat/ot-nonworkday-rate-suggestion: this value is DISCARDED and recomputed the moment this fixture
            // round-trips through OvertimeService#requireRequest, via OvertimeService#withSuggestedDayType.
            "WORKDAY"
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
