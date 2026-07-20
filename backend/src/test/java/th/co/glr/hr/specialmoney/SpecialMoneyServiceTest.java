package th.co.glr.hr.specialmoney;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

/**
 * Mockito decision-level tests, modelled on {@code OvertimeServiceTest}: each gate's happy path and
 * its 403, the 409 on re-review, the over-cap-without-reason 400, and the 25th-cutoff month
 * arithmetic. Permission enforcement itself (the SQL scoping surviving into the WHERE clause) is
 * NOT provable here -- see {@code SpecialMoneyScopeIntegrationTest} for that evidence.
 */
class SpecialMoneyServiceTest {
    private final SpecialMoneyRepository repository = mock(SpecialMoneyRepository.class);
    private final SpecialMoneyPolicyEvaluator evaluator = new SpecialMoneyPolicyEvaluator();
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    private final SpecialMoneyService service = new SpecialMoneyService(
        repository, evaluator, auditService, notificationService, appProperties);

    // ---------------------------------------------------------------------
    // submit()
    // ---------------------------------------------------------------------

    @Test
    void employeesCanSubmitOwnRequestWhenPolicyIsClean() {
        SubmitSpecialMoneyRequest request = weddingRequest(null, LocalDate.of(2026, 7, 1));
        when(repository.employeeExists(10L)).thenReturn(true);
        when(repository.findEligibility(eq(10L), any(LocalDate.class)))
            .thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class)))
            .thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        when(repository.create(eq(10L), eq(10L), eq(request), eq(SpecialMoneyType.AID_WEDDING), any(PolicyDecision.class)))
            .thenReturn(55L);
        when(repository.findById(55L)).thenReturn(Optional.of(dto(55L, 10L, "SUBMITTED", "AID_WEDDING")));
        UserPrincipal employee = user("employee", 10L);

        SpecialMoneyRequestDto result = service.submit("AID_WEDDING", request, employee);

        assertThat(result.id()).isEqualTo(55L);
        verify(auditService).record(eq(employee), eq("SUBMIT_SPECIAL_MONEY_REQUEST"), eq("special_money_request"), eq(55L), isNull(), any());
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_SUBMITTED"), anyString(), anyString(), eq("/requests"), eq(true));
    }

    @Test
    void submitRejectsWhenPolicyEvaluatorFindsAViolation() {
        // Employee not active -> evaluator always adds "employee is not active".
        SubmitSpecialMoneyRequest request = weddingRequest(null, LocalDate.of(2026, 7, 1));
        when(repository.employeeExists(10L)).thenReturn(true);
        when(repository.findEligibility(eq(10L), any(LocalDate.class)))
            .thenReturn(Optional.of(inactiveEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class)))
            .thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());

        assertThatThrownBy(() -> service.submit("AID_WEDDING", request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).create(anyLong(), any(), any(), any(), any());
    }

    @Test
    void employeesCannotSubmitForAnEmployeeTheyDoNotManage() {
        when(repository.findEmployeeAccess(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit("AID_WEDDING", weddingRequest(11L, LocalDate.of(2026, 7, 1)), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // approve() -- manager stage
    // ---------------------------------------------------------------------

    @Test
    void directManagerCanApproveSubmittedRequest() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "AID_WEDDING");
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(managerApproved));
        when(repository.findEmployeeAccess(10L)).thenReturn(Optional.of(new SpecialMoneyEmployeeAccess(10L, 99L, null, true)));
        when(repository.managerApprove(77L, 99L, "ok")).thenReturn(1);

        SpecialMoneyRequestDto result = service.approve(77L, new ReviewSpecialMoneyRequest("ok", null, null), user("employee", 99L));

        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
        verify(auditService).record(any(), eq("MANAGER_APPROVE_SPECIAL_MONEY_REQUEST"), eq("special_money_request"), eq(77L), eq(submitted), eq(managerApproved));
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_MANAGER_APPROVED"), anyString(), anyString(), eq("/requests"), eq(true));
    }

    @Test
    void divisionManagerCanApproveDivisionPeerRequest() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "AID_WEDDING");
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(managerApproved));
        when(repository.findEmployeeAccess(10L)).thenReturn(Optional.of(new SpecialMoneyEmployeeAccess(10L, null, 5L, true)));
        when(repository.managerApprove(77L, 88L, null)).thenReturn(1);
        when(repository.findCeoApproverEmployeeIds()).thenReturn(List.of());

        SpecialMoneyRequestDto result = service.approve(77L, null, manager(88L, 5L));

        assertThat(result.status()).isEqualTo("MANAGER_APPROVED");
    }

    @Test
    void nonManagerCannotApproveSubmittedRequest() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "SUBMITTED", "AID_WEDDING")));
        when(repository.findEmployeeAccess(10L)).thenReturn(Optional.of(new SpecialMoneyEmployeeAccess(10L, 99L, null, true)));

        assertThatThrownBy(() -> service.approve(77L, null, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void hrCannotApproveSubmittedRequest() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "SUBMITTED", "AID_WEDDING")));
        when(repository.findEmployeeAccess(10L)).thenReturn(Optional.of(new SpecialMoneyEmployeeAccess(10L, 99L, null, true)));

        assertThatThrownBy(() -> service.approve(77L, null, user("hr", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void managerCannotCeoApproveOwnManagerApprovedRequest() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.approve(77L, null, manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reReviewingAnAlreadyDecidedRequestIsConflict() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "REJECTED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.approve(77L, null, user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------------
    // approve() -- CEO stage / cap re-check / cutoff
    // ---------------------------------------------------------------------

    @Test
    void ceoApprovalWithinCapNeedsNoOverrideReason() {
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        SpecialMoneyRequestDto approved = dto(77L, 10L, "APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(managerApproved)).thenReturn(Optional.of(approved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        when(repository.ceoApprove(eq(77L), eq(500L), eq(new BigDecimal("5000")), any(LocalDate.class), isNull(), isNull()))
            .thenReturn(1);

        SpecialMoneyRequestDto result = service.approve(77L, null, user("ceo", 500L));

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(repository).ceoApprove(eq(77L), eq(500L), eq(new BigDecimal("5000")), any(LocalDate.class), isNull(), isNull());
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_APPROVED"), anyString(), anyString(), eq("/requests"), eq(true));
    }

    @Test
    void ceoApprovalOverCapWithoutReasonIsRejected() {
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(managerApproved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        ReviewSpecialMoneyRequest overCap = new ReviewSpecialMoneyRequest(null, new BigDecimal("9000"), null);

        assertThatThrownBy(() -> service.approve(77L, overCap, user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).ceoApprove(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void ceoApprovalOverCapWithReasonSucceedsAndPersistsReason() {
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        SpecialMoneyRequestDto approved = dto(77L, 10L, "APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(managerApproved)).thenReturn(Optional.of(approved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        ReviewSpecialMoneyRequest overCap = new ReviewSpecialMoneyRequest(null, new BigDecimal("9000"), "CEO discretionary top-up");
        when(repository.ceoApprove(eq(77L), eq(500L), eq(new BigDecimal("9000")), any(LocalDate.class), eq("CEO discretionary top-up"), isNull()))
            .thenReturn(1);

        SpecialMoneyRequestDto result = service.approve(77L, overCap, user("ceo", 500L));

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(repository).ceoApprove(eq(77L), eq(500L), eq(new BigDecimal("9000")), any(LocalDate.class), eq("CEO discretionary top-up"), isNull());
    }

    @Test
    void ceoApprovalOnOrBeforeCutoffDayAssignsCurrentMonth() {
        appProperties.getSpecialMoney().setPayrollCutoffDay(31); // always "before cutoff" for this test's purposes
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        SpecialMoneyRequestDto approved = dto(77L, 10L, "APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(managerApproved)).thenReturn(Optional.of(approved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        LocalDate expectedMonth = LocalDate.now().withDayOfMonth(1);
        when(repository.payrollMonthProcessed(expectedMonth)).thenReturn(false);
        when(repository.ceoApprove(eq(77L), eq(500L), any(), eq(expectedMonth), any(), any())).thenReturn(1);

        service.approve(77L, null, user("ceo", 500L));

        verify(repository).ceoApprove(eq(77L), eq(500L), any(), eq(expectedMonth), any(), any());
    }

    @Test
    void ceoApprovalRollsForwardPastAProcessedMonth() {
        appProperties.getSpecialMoney().setPayrollCutoffDay(31);
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        SpecialMoneyRequestDto approved = dto(77L, 10L, "APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(managerApproved)).thenReturn(Optional.of(approved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate nextMonth = thisMonth.plusMonths(1);
        when(repository.payrollMonthProcessed(thisMonth)).thenReturn(true);
        when(repository.payrollMonthProcessed(nextMonth)).thenReturn(false);
        when(repository.ceoApprove(eq(77L), eq(500L), any(), eq(nextMonth), any(), any())).thenReturn(1);

        service.approve(77L, null, user("ceo", 500L));

        verify(repository).ceoApprove(eq(77L), eq(500L), any(), eq(nextMonth), any(), any());
    }

    // ---------------------------------------------------------------------
    // reject()
    // ---------------------------------------------------------------------

    @Test
    void managerRejectionTransitionsSubmittedToRejected() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "AID_WEDDING");
        SpecialMoneyRequestDto rejected = dto(77L, 10L, "REJECTED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(rejected));
        when(repository.findEmployeeAccess(10L)).thenReturn(Optional.of(new SpecialMoneyEmployeeAccess(10L, 99L, null, true)));
        when(repository.reject(77L, 99L, "no budget")).thenReturn(1);

        SpecialMoneyRequestDto result = service.reject(77L, new ReviewSpecialMoneyRequest("no budget", null, null), user("employee", 99L));

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_REJECTED"), anyString(), anyString(), eq("/requests"), eq(true));
    }

    @Test
    void managerCannotCeoRejectManagerApprovedRequest() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.reject(77L, null, manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // cancel()
    // ---------------------------------------------------------------------

    @Test
    void ownerCanCancelOwnSubmittedRequest() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "AID_WEDDING");
        SpecialMoneyRequestDto cancelled = dto(77L, 10L, "CANCELLED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(cancelled));
        when(repository.cancel(77L, 10L, null)).thenReturn(1);

        SpecialMoneyRequestDto result = service.cancel(77L, null, user("employee", 10L));

        assertThat(result.status()).isEqualTo("CANCELLED");
    }

    @Test
    void anotherEmployeeCannotCancelSomeoneElsesRequest() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "SUBMITTED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.cancel(77L, null, user("employee", 11L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cancelOfAlreadyApprovedRequestIsConflict() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.cancel(77L, null, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private SubmitSpecialMoneyRequest weddingRequest(Long employeeId, LocalDate eventDate) {
        return new SubmitSpecialMoneyRequest(
            employeeId, eventDate, null, null, BigDecimal.ONE, new BigDecimal("5000"), "Getting married", Map.of());
    }

    private EmployeeEligibilitySnapshot activeEligibility(long employeeId) {
        return new EmployeeEligibilitySnapshot(
            employeeId, LocalDate.of(2020, 1, 1), null, null, null, true, LocalDate.now());
    }

    private EmployeeEligibilitySnapshot inactiveEligibility(long employeeId) {
        return new EmployeeEligibilitySnapshot(
            employeeId, LocalDate.of(2020, 1, 1), null, null, null, false, LocalDate.now());
    }

    private UsageSnapshot emptyUsage() {
        return new UsageSnapshot(Map.of(), Map.of());
    }

    private PolicyAmounts weddingPolicy() {
        return new PolicyAmounts(Map.of("cap", new BigDecimal("5000")), 1);
    }

    private SpecialMoneyRequestDto dto(long id, long employeeId, String status, String requestType) {
        boolean managerApproved = "MANAGER_APPROVED".equals(status) || "APPROVED".equals(status);
        boolean ceoApproved = "APPROVED".equals(status);
        return new SpecialMoneyRequestDto(
            id,
            employeeId,
            "EMP001",
            "Test Employee",
            requestType,
            LocalDate.of(2026, 7, 1),
            null,
            null,
            BigDecimal.ONE,
            new BigDecimal("5000"),
            ceoApproved ? new BigDecimal("5000") : null,
            "AID",
            1,
            "Getting married",
            Map.of(),
            status,
            ceoApproved ? LocalDate.of(2026, 7, 1) : null,
            null,
            employeeId,
            "Test Employee",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            managerApproved ? 99L : null,
            managerApproved ? "Test Manager" : null,
            managerApproved ? OffsetDateTime.parse("2026-06-14T11:00:00+07:00") : null,
            ceoApproved ? 500L : null,
            ceoApproved ? "Test CEO" : null,
            ceoApproved ? OffsetDateTime.parse("2026-06-14T12:00:00+07:00") : null,
            99L,
            "Test Manager",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            null,
            null,
            99L,
            "Test Manager",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00")
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, "mgr@glr.co.th", "Manager", "employee", employeeId, true, LocalDate.now(), false, divisionId, true);
    }
}
