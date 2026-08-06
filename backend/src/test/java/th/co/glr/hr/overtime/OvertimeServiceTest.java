package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

class OvertimeServiceTest {
    private final OvertimeRepository overtimeRepository = mock(OvertimeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    // Approving/cancelling overtime re-derives that attendance day; stubbed here so these cases
    // stay about the overtime rules. The real sync is covered by the integration tests.
    private final AttendanceDailyService attendanceDailyService = mock(AttendanceDailyService.class);
    private final ManagerApproverRepository managerApproverRepository = mock(ManagerApproverRepository.class);
    private final OvertimeService overtimeService = new OvertimeService(
        overtimeRepository,
        managerApproverRepository,
        auditService,
        notificationService,
        appProperties,
        attendanceDailyService
    );

    /**
     * A Mockito boolean stub defaults to {@code false}, which here would mean "no manager stage"
     * and would silently route every SUBMITTED approval in this class down the CEO-direct path —
     * the manager-approval cases would then pass while testing something else entirely. Default to
     * {@code true} so each test exercises the two-stage flow unless it opts out explicitly.
     */
    @BeforeEach
    void assumeAManagerStageExists() {
        when(managerApproverRepository.hasManagerApprover(anyLong())).thenReturn(true);
    }

    @Test
    void employeesCanSubmitOwnOvertime() {
        SubmitOvertimeRequest request = validSubmit(null);
        OvertimeRequestDto created = requestDto(55L, 10L, "SUBMITTED");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(request.workDate().withDayOfMonth(1))))
            .thenReturn(55L);
        when(overtimeRepository.findById(55L)).thenReturn(Optional.of(created));
        // The submit notification now goes to whoever can approve, read from the same source as
        // the routing decision -- NOT to reports_to, who no longer approves anything.
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of(99L));
        UserPrincipal employee = user("employee", 10L);

        OvertimeRequestDto result = overtimeService.submit(request, employee);

        assertThat(result.id()).isEqualTo(55L);
        verify(overtimeRepository).create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(request.workDate().withDayOfMonth(1)));
        verify(auditService).record(employee, "SUBMIT_OVERTIME_REQUEST", "overtime_request", 55L, null, created);
        verify(notificationService).notify(eq(10L), eq("OVERTIME_SUBMITTED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(99L), eq("OVERTIME_PENDING_MANAGER"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void submittingAManagerlessRequestNotifiesTheCeoNotAManager() {
        SubmitOvertimeRequest request = validSubmit(null);
        OvertimeRequestDto created = requestDto(55L, 10L, "SUBMITTED");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(anyLong(), any(), any(), anyInt(), any(), any())).thenReturn(55L);
        when(overtimeRepository.findById(55L)).thenReturn(Optional.of(created));
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of());
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));

        overtimeService.submit(request, user("employee", 10L));

        // The failure this guards against is silent: a request routed to the CEO while only a
        // manager -- who cannot clear it -- is told about it, so it sits unreviewed with everyone
        // believing someone else has it.
        verify(notificationService).notify(eq(500L), eq("OVERTIME_PENDING_CEO"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService, never()).notify(anyLong(), eq("OVERTIME_PENDING_MANAGER"), anyString(), anyString(), anyString(), anyBoolean());
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
    void divisionManagersCanSubmitOvertimeForTheirTeam() {
        LocalDate workDate = LocalDate.now().plusDays(4);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(java.time.ZoneOffset.ofHours(7));
        SubmitOvertimeRequest request = new SubmitOvertimeRequest(
            10L,
            workDate,
            startAt,
            startAt.plusHours(2),
            "HOLIDAY",
            "Urgent delivery"
        );
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(99L), eq(request), eq(120), eq(OvertimeDayType.HOLIDAY), eq(workDate.withDayOfMonth(1))))
            .thenReturn(56L);
        when(overtimeRepository.findById(56L)).thenReturn(Optional.of(requestDto(56L, 10L, "SUBMITTED")));

        OvertimeRequestDto result = overtimeService.submit(request, manager(99L, 5L));

        assertThat(result.id()).isEqualTo(56L);
        verify(overtimeRepository).create(eq(10L), eq(99L), eq(request), eq(120), eq(OvertimeDayType.HOLIDAY), eq(workDate.withDayOfMonth(1)));
    }

    @Test
    void submitAllowsSameDayOvertime() {
        SubmitOvertimeRequest request = backdatedSubmit(0, "Same day");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), any(LocalDate.class)))
            .thenReturn(60L);
        when(overtimeRepository.findById(60L)).thenReturn(Optional.of(requestDto(60L, 10L, "SUBMITTED")));

        assertThat(overtimeService.submit(request, user("employee", 10L)).id()).isEqualTo(60L);
    }

    // The rule the CEO asked for: an employee may file their own overtime after the fact.
    // Before this change the service returned 403 unless a manager filed it for them.
    @Test
    void employeesCanSelfFileRetroactiveOvertimeWithDetailedReason() {
        SubmitOvertimeRequest request = backdatedSubmit(2, "Emergency line stoppage, filed late after the shift ended");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), any(LocalDate.class)))
            .thenReturn(61L);
        when(overtimeRepository.findById(61L)).thenReturn(Optional.of(requestDto(61L, 10L, "SUBMITTED")));

        assertThat(overtimeService.submit(request, user("employee", 10L)).id()).isEqualTo(61L);
    }

    @Test
    void retroactiveOvertimeRequiresDetailedReason() {
        SubmitOvertimeRequest request = backdatedSubmit(2, "OT");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retroactiveOvertimeBeyondTheWindowIsRejected() {
        SubmitOvertimeRequest request = backdatedSubmit(120, "Found an unclaimed shift while tidying up old records");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Payroll derives overtime by payroll_month and writes a period once, so a request landing in a
    // processed month would be approved and then never paid.
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
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));
        when(overtimeRepository.payrollMonthProcessed(submitted.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.approve(78L, new ReviewOvertimeRequest("ok"), manager(99L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(overtimeRepository, never())
            .managerApprove(anyLong(), anyLong(), any(OvertimeCalculation.class), any(BigDecimal.class), anyString());
    }

    @Test
    void ceoApprovalIntoProcessedPayrollMonthIsRejected() {
        OvertimeRequestDto managerApproved = requestDto(79L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(79L)).thenReturn(Optional.of(managerApproved));
        when(overtimeRepository.payrollMonthProcessed(managerApproved.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.approve(79L, new ReviewOvertimeRequest("ok"), user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(overtimeRepository, never()).ceoApprove(anyLong(), anyLong(), anyString());
    }

    // V114: a month can be closed to OT without ever being PROCESSED in this system -- it was paid
    // outside the ERP and is already covered by hr.payroll_year_to_date_seed (PND1 filed). This is
    // the OTHER branch of requirePayrollMonthOpen; payrollMonthProcessed stays false throughout.
    // These three assert on MESSAGE CONTENT ("จ่ายนอกระบบ"), not just HTTP status. A status-only
    // check is not enough to prove the seed-covered branch fired: managerApprove/ceoApprove return
    // an unstubbed `int` (Mockito default 0), so the pre-existing "already reviewed" conflict path
    // also throws 409 if the guard is bypassed -- verified directly by mutation-testing this file
    // (temporarily short-circuiting the seed-covered check in OvertimeService reproduced exactly
    // this: status-only assertions kept passing while overtimeRepository.managerApprove/ceoApprove
    // were invoked with a write that should have been refused).
    @Test
    void retroactiveOvertimeIntoASeedCoveredPayrollMonthIsRejected() {
        SubmitOvertimeRequest request = backdatedSubmit(2, "Filed late after the customer escalation closed");
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.payrollMonthProcessed(request.workDate().withDayOfMonth(1))).thenReturn(false);
        when(overtimeRepository.payrollMonthSeedCovered(request.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("จ่ายนอกระบบ")
            .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.CONFLICT));
        verify(overtimeRepository, never()).create(
            anyLong(), anyLong(), any(SubmitOvertimeRequest.class), anyInt(), any(OvertimeDayType.class), any(LocalDate.class));
    }

    @Test
    void managerApprovalIntoASeedCoveredPayrollMonthIsRejected() {
        OvertimeRequestDto submitted = requestDto(78L, 10L, "SUBMITTED");
        when(overtimeRepository.findById(78L)).thenReturn(Optional.of(submitted));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));
        when(overtimeRepository.payrollMonthProcessed(submitted.workDate().withDayOfMonth(1))).thenReturn(false);
        when(overtimeRepository.payrollMonthSeedCovered(submitted.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.approve(78L, new ReviewOvertimeRequest("ok"), manager(99L, 5L)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("จ่ายนอกระบบ")
            .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void ceoApprovalIntoASeedCoveredPayrollMonthIsRejected() {
        OvertimeRequestDto managerApproved = requestDto(79L, 10L, "MANAGER_APPROVED");
        when(overtimeRepository.findById(79L)).thenReturn(Optional.of(managerApproved));
        when(overtimeRepository.payrollMonthProcessed(managerApproved.workDate().withDayOfMonth(1))).thenReturn(false);
        when(overtimeRepository.payrollMonthSeedCovered(managerApproved.workDate().withDayOfMonth(1))).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.approve(79L, new ReviewOvertimeRequest("ok"), user("ceo", 500L)))
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
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));
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
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));
        UserPrincipal actor = manager(99L, 5L);

        OvertimeRequestDto result = overtimeService.approve(77L, new ReviewOvertimeRequest("ok"), actor);

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
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));
        when(overtimeRepository.reject(77L, 99L, "no budget")).thenReturn(1);
        UserPrincipal manager = manager(99L, 5L);

        OvertimeRequestDto result = overtimeService.reject(77L, new ReviewOvertimeRequest("no budget"), manager);

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(overtimeRepository).reject(77L, 99L, "no budget");
        verify(auditService).record(eq(manager), eq("REJECT_OVERTIME_REQUEST"), eq("overtime_request"), eq(77L), eq(submitted), eq(rejected));
        verify(notificationService).notify(eq(10L), eq("OVERTIME_REJECTED"), anyString(), anyString(), eq("/overtime"), eq(true));
        // Notification coverage gap C, wrong-way-round: the manager IS the actor here and CEO never
        // saw this request (rejected straight from SUBMITTED) -- nobody upstream needs telling.
        verify(notificationService, never())
            .notify(eq(99L), eq("OVERTIME_REJECTED"), anyString(), anyString(), anyString(), anyBoolean());
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

    /**
     * Notification coverage gap C, positive case. {@code requestDto()}'s own helper ties
     * {@code managerApprovedBy} to the CURRENT status only, which would build a REJECTED row with
     * {@code managerApprovedBy == null} regardless -- not the real shape {@code OvertimeRepository
     * #ceoReject} produces (it never clears that column; see that method's SQL). This builds the
     * "after" row directly to reflect what a real DB row looks like, so the positive branch of the
     * {@code managerApprovedBy() != null} guard is actually proven, not just its (structurally
     * identical) null branch above.
     */
    @Test
    void ceoRejectionOfManagerApprovedRequestNotifiesTheApprovingManagerToo() {
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        OvertimeRequestDto rejected = rejectedWithManagerApprovedBy(77L, 10L, 99L);
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(managerApproved))
            .thenReturn(Optional.of(rejected));
        when(overtimeRepository.ceoReject(77L, 500L, "not justified")).thenReturn(1);
        UserPrincipal ceo = user("ceo", 500L);

        overtimeService.reject(77L, new ReviewOvertimeRequest("not justified"), ceo);

        verify(notificationService).notify(eq(10L), eq("OVERTIME_REJECTED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(99L), eq("OVERTIME_REJECTED"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    /**
     * S-6 (review, second pass): {@code notifyRejected} had no actor self-skip. Reachable: a
     * กรรมการผู้จัดการ (division MD) has {@code manager() == true} AND role {@code ceo} -- for an
     * MD-division non-manager's OT they can {@code managerApprove} it and then, as CEO, {@code
     * ceoReject} the SAME request. {@code managerApprovedBy} then equals the rejecting actor, who
     * must not be told about their own rejection.
     */
    @Test
    void ceoRejectionSkipsNotifyingSelfWhenActorIsTheApprovingManager() {
        OvertimeRequestDto managerApproved = requestDto(77L, 10L, "MANAGER_APPROVED");
        OvertimeRequestDto rejected = rejectedWithManagerApprovedBy(77L, 10L, 10500L);
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(managerApproved))
            .thenReturn(Optional.of(rejected));
        when(overtimeRepository.ceoReject(77L, 10500L, "not justified")).thenReturn(1);
        UserPrincipal ceoWhoAlsoApproved = user("ceo", 10500L);

        overtimeService.reject(77L, new ReviewOvertimeRequest("not justified"), ceoWhoAlsoApproved);

        verify(notificationService).notify(eq(10L), eq("OVERTIME_REJECTED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService, never())
            .notify(eq(10500L), eq("OVERTIME_REJECTED"), anyString(), anyString(), anyString(), anyBoolean());
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
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, 5L, true)));

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
        when(overtimeRepository.findSalaryBasisAsOf(10L, LocalDate.parse("2026-07-15")))
            .thenReturn(new BigDecimal("30000.00"));
        when(overtimeRepository.managerApprove(
                eq(77L), eq(88L), any(OvertimeCalculation.class), any(BigDecimal.class), eq("ok")))
            .thenReturn(1);
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of());

        OvertimeRequestDto result = overtimeService.approve(77L, new ReviewOvertimeRequest("ok"), manager(88L, 5L));

        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
        verify(overtimeRepository).managerApprove(
            eq(77L), eq(88L), any(OvertimeCalculation.class), any(BigDecimal.class), eq("ok"));
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

    // ─────────────────────────────────────────────────────────────────────
    // Notification coverage gap B: cancel() must notify the requester and whoever the request was
    // still pending with, resolved from the SAME source notifySubmitted/notifyManagerApproved use --
    // but NOT an already-decided request's approvers.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void selfCancelOfSubmittedOvertimeWithAManagerStageNotifiesEmployeeAndManagers() {
        OvertimeRequestDto submitted = cancelFixtureDto(90L, "SUBMITTED", null, null);
        OvertimeRequestDto cancelled = cancelFixtureDto(90L, "CANCELLED", null, null);
        when(overtimeRepository.findById(90L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.cancel(90L, null, "no longer needed")).thenReturn(1);
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of(199L));

        overtimeService.cancel(90L, new ReviewOvertimeRequest("no longer needed"), user("employee", 10L));

        verify(notificationService).notify(eq(10L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(199L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        // S3: this status is SUBMITTED, so the CEO approver source must never be consulted -- absent
        // this, Mockito's default empty-list stub would let a wrongful call pass silently.
        verify(overtimeRepository, never()).findCeoApproverEmployeeIds();
    }

    @Test
    void selfCancelOfSubmittedOvertimeWithNoManagerStageNotifiesCeo() {
        OvertimeRequestDto submitted = cancelFixtureDto(91L, "SUBMITTED", null, null);
        OvertimeRequestDto cancelled = cancelFixtureDto(91L, "CANCELLED", null, null);
        when(overtimeRepository.findById(91L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.cancel(91L, null, null)).thenReturn(1);
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of());
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));

        overtimeService.cancel(91L, new ReviewOvertimeRequest(null), user("employee", 10L));

        verify(notificationService).notify(eq(10L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(500L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
    }

    @Test
    void managerCancelOfManagerApprovedOvertimeNotifiesEmployeeAndCeo() {
        OvertimeRequestDto managerApproved = cancelFixtureDto(92L, "MANAGER_APPROVED", null, null);
        OvertimeRequestDto cancelled = cancelFixtureDto(92L, "CANCELLED", 99L, "Test Manager");
        when(overtimeRepository.findById(92L)).thenReturn(Optional.of(managerApproved)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true)));
        when(overtimeRepository.cancel(92L, 99L, null)).thenReturn(1);
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));
        UserPrincipal mgr = manager(99L, 5L);

        overtimeService.cancel(92L, new ReviewOvertimeRequest(null), mgr);

        // Reviewer-cancel: after.reviewedById() (99L) is non-null, the same shape OvertimeRepository
        // #cancel writes for `manager ? actorEmployeeId : null`.
        verify(notificationService).notify(eq(10L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(500L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        // S3: this status is MANAGER_APPROVED, so the "pending manager" resolver must never be
        // consulted -- absent this, Mockito's default empty-list stub would let a wrongful call pass.
        verify(managerApproverRepository, never()).findManagerApproverEmployeeIds(anyLong());
    }

    /**
     * BLOCKING 1's deterministic failure case: employee E's OT is SUBMITTED, and E's ผู้จัดการ M --
     * who IS the manager {@code findManagerApproverEmployeeIds(E)} resolves to, since {@code
     * managesEmployee}/{@code PEER_IS_MANAGER_APPROVER} key off the same ผู้จัดการ match -- cancels
     * it. M must not be told about the cancellation M themselves just performed.
     */
    @Test
    void managerCancelOfSubmittedOvertimeDoesNotNotifyThemselfAsThePendingApprover() {
        // S-1 (review, second pass): manager id raised from 99L to 10099L -- a realistic 4-5 digit
        // id, OUTSIDE Java's Long cache (-128..127). 99L could never catch a regression from the
        // primitive-`long` self-skip comparison back to a boxed-`Long` reference comparison: cached
        // Longs of the same small value are always `==` equal regardless, so the assertion below
        // would stay green either way. Proven: see the mutation-check in the PR report.
        OvertimeRequestDto submitted = cancelFixtureDto(95L, "SUBMITTED", null, null);
        OvertimeRequestDto cancelled = cancelFixtureDto(95L, "CANCELLED", 10099L, "Test Manager");
        when(overtimeRepository.findById(95L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true)));
        when(overtimeRepository.cancel(95L, 10099L, null)).thenReturn(1);
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of(10099L));
        UserPrincipal mgr = manager(10099L, 5L);

        overtimeService.cancel(95L, new ReviewOvertimeRequest(null), mgr);

        verify(notificationService).notify(eq(10L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService, never())
            .notify(eq(10099L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), anyString(), anyBoolean());
    }

    /**
     * S1: proves the approver-facing message is actually worded from the ACTING manager, not the
     * employee whose request it is -- the two names differ here on purpose ("Acting Manager" vs.
     * {@code cancelFixtureDto}'s fixed "Test Employee"), so an inverted/reverted wording branch is
     * observable, not just "some string was passed".
     */
    @Test
    void managerCancelOfSubmittedOvertimeWordsTheApproverMessageFromTheActingManager() {
        OvertimeRequestDto submitted = cancelFixtureDto(96L, "SUBMITTED", null, null);
        OvertimeRequestDto cancelled = cancelFixtureDto(96L, "CANCELLED", 88L, "Acting Manager");
        when(overtimeRepository.findById(96L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true)));
        when(overtimeRepository.cancel(96L, 88L, null)).thenReturn(1);
        when(managerApproverRepository.findManagerApproverEmployeeIds(10L)).thenReturn(List.of(199L));
        UserPrincipal actingManager =
            new UserPrincipal(88L, "acting@glr.co.th", "Acting Manager", "employee", 88L, true, LocalDate.now(), false, 5L, true);

        overtimeService.cancel(96L, new ReviewOvertimeRequest(null), actingManager);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(199L), eq("OVERTIME_CANCELLED"), anyString(), body.capture(), eq("/overtime"), eq(true));
        assertThat(body.getValue()).contains("Acting Manager").doesNotContain("Test Employee");
    }

    /**
     * D1 (owner ruling): cancelling an ALREADY-APPROVED request reverses payroll-relevant state
     * ({@link OvertimeService#syncAttendanceDay} strips the credited minutes back out) -- the manager
     * and CEO who actually approved it must be told, resolved from the row's OWN approver columns
     * ({@code managerApprovedBy}/the CEO approver set), never a "pending" lookup (see the
     * wrong-way-round assertion below).
     */
    @Test
    void managerCancelOfApprovedOvertimeNotifiesEmployeeApprovingManagerAndCeo() {
        // requestDto(status="APPROVED") bakes in managerApprovedBy=99L, ceoApprovedBy=500L.
        OvertimeRequestDto approved = requestDto(93L, 10L, "APPROVED");
        OvertimeRequestDto cancelled = cancelFixtureDto(93L, "CANCELLED", 77L, "Second Manager");
        when(overtimeRepository.findById(93L)).thenReturn(Optional.of(approved)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true)));
        when(overtimeRepository.cancel(93L, 77L, null)).thenReturn(1);
        when(overtimeRepository.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));
        UserPrincipal secondManager = manager(77L, 5L);

        overtimeService.cancel(93L, new ReviewOvertimeRequest(null), secondManager);

        verify(notificationService).notify(eq(10L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(99L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService).notify(eq(500L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        // D1 must never consult the PENDING-approver resolver -- an APPROVED request has no pending
        // stage; its approvers are read from the row itself.
        verify(managerApproverRepository, never()).findManagerApproverEmployeeIds(anyLong());
    }

    /**
     * BLOCKING 1 regression for D1: the manager who APPROVED the request is the SAME person
     * cancelling it -- must not be told about their own action.
     */
    @Test
    void managerCancelOfApprovedOvertimeSkipsNotifyingSelfWhenActorIsTheApprovingManager() {
        // S-1 (review, second pass): manager id raised from 99L to 10099L -- same cache-blind-spot
        // reasoning as managerCancelOfSubmittedOvertimeDoesNotNotifyThemselfAsThePendingApprover
        // above. requestDto()'s own helper bakes managerApprovedBy at a fixed 99L (other tests --
        // e.g. managerCancelOfApprovedOvertimeNotifiesEmployeeApprovingManagerAndCeo -- rely on that
        // constant with a DIFFERENT actor, so it is not changed here); approvedFixtureDto below is a
        // dedicated fixture so this one test alone can use a realistic, out-of-cache id.
        OvertimeRequestDto approved = approvedFixtureDto(94L, 10L, 10099L);
        OvertimeRequestDto cancelled = cancelFixtureDto(94L, "CANCELLED", 10099L, "Test Manager");
        when(overtimeRepository.findById(94L)).thenReturn(Optional.of(approved)).thenReturn(Optional.of(cancelled));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, null, 5L, true)));
        when(overtimeRepository.cancel(94L, 10099L, null)).thenReturn(1);
        UserPrincipal sameManager = manager(10099L, 5L);

        overtimeService.cancel(94L, new ReviewOvertimeRequest(null), sameManager);

        verify(notificationService).notify(eq(10L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        // S-7: the CEO-facing recipient is now read from the row's own ceoApprovedBy (500L, baked
        // into approvedFixtureDto), not a broadcast to the whole CEO approver set.
        verify(notificationService).notify(eq(500L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), eq("/overtime"), eq(true));
        verify(notificationService, never())
            .notify(eq(10099L), eq("OVERTIME_CANCELLED"), anyString(), anyString(), anyString(), anyBoolean());
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

    /**
     * A self-filed request {@code daysAgo} in the past (0 = today), with the given reason.
     *
     * <p>"Today" is resolved in {@code Asia/Bangkok} to match {@code OvertimeService.BUSINESS_ZONE}.
     * Using the JVM default zone makes {@code daysAgo = 0} flake: in CI (UTC) between 17:00 and 23:59
     * the default-zone date is a day behind the Bangkok date the service compares against, so a
     * "same day" request is seen as backdated and rejected for a too-short reason.
     */
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
            // feat/pending-approver-info: computed by OvertimeRepository#mapRequest (a real SQL row
            // mapper this Mockito-level class never exercises), so no test in this class asserts on
            // them via the helper.
            null,
            null
        );
    }

    private boolean isManagerApproved(String status) {
        return "MANAGER_APPROVED".equals(status) || "APPROVED".equals(status);
    }

    /**
     * Like {@link #requestDto}, but with {@code reviewedById}/{@code reviewedByName} directly
     * controllable -- needed to prove the self-cancel-vs-reviewer-cancel wording in the gap B cancel
     * tests. {@code employeeId} is fixed at 10L (every cancel test above uses that id).
     */
    private OvertimeRequestDto cancelFixtureDto(long id, String status, Long reviewedById, String reviewedByName) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-14T10:00:00+07:00");
        return new OvertimeRequestDto(
            id, 10L, "EMP001", "Test Employee",
            LocalDate.parse("2026-07-15"),
            OffsetDateTime.parse("2026-07-15T18:00:00+07:00"),
            OffsetDateTime.parse("2026-07-15T20:00:00+07:00"),
            120, "WORKDAY", new BigDecimal("1.50"), "Customer shipment",
            status,
            null, null, 0, 0, null,
            LocalDate.parse("2026-07-01"),
            10L, "Test Employee", timestamp,
            null, null, null,
            null, null, null,
            reviewedById, reviewedByName, reviewedById == null ? null : timestamp, null,
            "CANCELLED".equals(status) ? timestamp : null,
            99L, "Test Manager",
            true,
            timestamp, timestamp,
            // feat/pending-approver-info: no test using this fixture asserts on these.
            null, null
        );
    }

    /**
     * An APPROVED row with {@code managerApprovedBy} directly controllable (ceoApprovedBy fixed at
     * 500L/"Test CEO", matching {@link #requestDto}'s own APPROVED shape) -- needed so
     * {@code managerCancelOfApprovedOvertimeSkipsNotifyingSelfWhenActorIsTheApprovingManager} can use
     * a realistic, out-of-{@code Long}-cache manager id instead of {@link #requestDto}'s baked-in
     * 99L (S-1, review second pass) without disturbing the other tests that rely on that constant.
     */
    private OvertimeRequestDto approvedFixtureDto(long id, long employeeId, Long managerApprovedBy) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-14T10:00:00+07:00");
        return new OvertimeRequestDto(
            id, employeeId, "EMP001", "Test Employee",
            LocalDate.parse("2026-07-15"),
            OffsetDateTime.parse("2026-07-15T18:00:00+07:00"),
            OffsetDateTime.parse("2026-07-15T20:00:00+07:00"),
            120, "WORKDAY", new BigDecimal("1.50"), "Customer shipment",
            "APPROVED",
            null, null, 0, 0, null,
            LocalDate.parse("2026-07-01"),
            employeeId, "Test Employee", timestamp,
            managerApprovedBy, managerApprovedBy == null ? null : "Test Manager",
            managerApprovedBy == null ? null : OffsetDateTime.parse("2026-06-14T11:00:00+07:00"),
            500L, "Test CEO", OffsetDateTime.parse("2026-06-14T12:00:00+07:00"),
            null, null, null, null,
            null,
            99L, "Test Manager",
            true,
            timestamp, timestamp,
            // feat/pending-approver-info: no test using this fixture asserts on these.
            null, null
        );
    }

    /**
     * A REJECTED row with {@code managerApprovedBy} set -- the real shape {@code OvertimeRepository
     * #ceoReject} produces for a request that WAS manager-approved before the CEO rejected it (that
     * column is never cleared by a reject). {@link #requestDto}'s own helper cannot express this: it
     * ties {@code managerApprovedBy} to the row's CURRENT status only.
     */
    private OvertimeRequestDto rejectedWithManagerApprovedBy(long id, long employeeId, Long managerApprovedBy) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-14T10:00:00+07:00");
        return new OvertimeRequestDto(
            id, employeeId, "EMP001", "Test Employee",
            LocalDate.parse("2026-07-15"),
            OffsetDateTime.parse("2026-07-15T18:00:00+07:00"),
            OffsetDateTime.parse("2026-07-15T20:00:00+07:00"),
            120, "WORKDAY", new BigDecimal("1.50"), "Customer shipment",
            "REJECTED",
            null, null, 0, 0, null,
            LocalDate.parse("2026-07-01"),
            employeeId, "Test Employee", timestamp,
            managerApprovedBy, managerApprovedBy == null ? null : "Test Manager", managerApprovedBy == null ? null : timestamp,
            null, null, null,
            500L, "Test CEO", timestamp, "not justified",
            null,
            99L, "Test Manager",
            true,
            timestamp, timestamp,
            // feat/pending-approver-info: no test using this fixture asserts on these.
            null, null
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }

    // A ฝ่าย manager: base employee role, manager flag set, scoped to a division.
    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, "mgr@glr.co.th", "Manager", "employee", employeeId, true, LocalDate.now(), false, divisionId, true);
    }
}
