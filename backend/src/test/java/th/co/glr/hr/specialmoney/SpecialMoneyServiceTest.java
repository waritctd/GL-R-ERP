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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
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

    /**
     * Evidence-required types cannot be approved with nothing attached. Default the count to 1 so
     * each approval case below stays about the rule it names; the gate itself is proven by
     * {@link #approvalIsRefusedWhenAnEvidenceRequiredTypeHasNoAttachment()}.
     */
    @BeforeEach
    void assumeEvidenceIsOnFile() {
        when(repository.countAttachments(anyLong())).thenReturn(1);
    }

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
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_SUBMITTED"), anyString(), anyString(), eq("/employee-requests"), eq(true));
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

    /**
     * Welfare has no submit-on-behalf for anyone, so the target employee is the ONLY thing this
     * gate looks at -- not the caller's role, not their ฝ่าย. All three shapes that used to differ
     * (a plain colleague, the employee's own ผู้จัดการ, HR) now take the same branch.
     */
    @ParameterizedTest(name = "{0} cannot submit a welfare request for someone else")
    @MethodSource("nonSelfSubmitters")
    void nobodyCanSubmitAWelfareRequestForAnotherEmployee(String label, UserPrincipal caller) {
        assertThatThrownBy(() -> service.submit(
                "AID_WEDDING", weddingRequest(11L, LocalDate.of(2026, 7, 1)), caller))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        // Refused before the row exists, not after.
        verify(repository, never()).create(anyLong(), any(), any(), any(), any());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> nonSelfSubmitters() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("an unrelated colleague",
                new UserPrincipal(10L, "e@glr.co.th", "e", "employee", 10L, true, LocalDate.now(), false, 5L, false)),
            // THE case this change is about: employee 11 sits in division 5 and so does this
            // ผู้จัดการ. Before 2026-08-10 managesEmployee() made this succeed.
            org.junit.jupiter.params.provider.Arguments.of("the employee's own ฝ่าย manager",
                new UserPrincipal(88L, "m@glr.co.th", "m", "employee", 88L, true, LocalDate.now(), false, 5L, true)),
            org.junit.jupiter.params.provider.Arguments.of("hr",
                new UserPrincipal(500L, "hr@glr.co.th", "hr", "hr", 500L, true, LocalDate.now(), false, 5L, false)),
            org.junit.jupiter.params.provider.Arguments.of("the ceo",
                new UserPrincipal(501L, "ceo@glr.co.th", "ceo", "ceo", 501L, true, LocalDate.now(), false, 5L, false)));
    }

    // ---------------------------------------------------------------------
    // The read-scope DECISION (which branch list()/canAccessEmployee take).
    // Enforcement -- that the branch survives into the SQL WHERE clause -- is NOT provable here;
    // see SpecialMoneyScopeIntegrationTest.
    // ---------------------------------------------------------------------

    /**
     * A ฝ่าย manager must be scoped to their OWN employee id, exactly like a plain employee. The
     * captured filter is the decision: {@link SpecialMoneyFilter} no longer even has a division
     * field, so the only way to widen the scope would be to leave {@code ownEmployeeId} null, which
     * is the hr/ceo branch.
     */
    @Test
    void managerListIsScopedToTheirOwnEmployeeIdNotTheirDivision() {
        UserPrincipal divisionManager = manager(88L, 5L);

        service.list(divisionManager, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

        ArgumentCaptor<SpecialMoneyFilter> captor = ArgumentCaptor.forClass(SpecialMoneyFilter.class);
        verify(repository).findRequests(captor.capture());
        assertThat(captor.getValue().ownEmployeeId()).isEqualTo(88L);
        assertThat(captor.getValue().employeeId()).isNull();
    }

    @Test
    void hrAndCeoListsAreNotScopedToAnEmployeeAtAll() {
        for (UserPrincipal viewAll : List.of(user("hr", 500L), user("ceo", 501L))) {
            service.list(viewAll, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);
        }

        ArgumentCaptor<SpecialMoneyFilter> captor = ArgumentCaptor.forClass(SpecialMoneyFilter.class);
        verify(repository, org.mockito.Mockito.times(2)).findRequests(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(filter -> assertThat(filter.ownEmployeeId()).isNull());
    }

    /**
     * The employee picker must not offer a ผู้จัดการ their ฝ่าย: {@code includeAll} false means
     * "self only" now that {@code findEmployeeOptions} has no division parameter to widen it.
     */
    @Test
    void managerEmployeePickerAsksForSelfOnly() {
        service.employeeOptions(manager(88L, 5L));

        verify(repository).findEmployeeOptions(88L, false);
    }

    @Test
    void managerCannotReadATeamMembersUsageQuota() {
        assertThatThrownBy(() -> service.usage(10L, 2026, manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).findUsage(anyLong(), anyInt());
    }

    @Test
    void managerCannotFilterTheListToATeamMembersRequests() {
        assertThatThrownBy(() -> service.list(
                manager(88L, 5L), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 10L, null, null))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).findRequests(any());
    }

    /**
     * {@code requested_by_id} is no longer a key to anybody else's row. Both gates that honoured it
     * are asserted here because they are separate methods with separate copies of the check --
     * fixing one and not the other is the realistic mistake.
     */
    @Test
    void aLegacyOnBehalfFilerCanNoLongerCancelOrAttachToTheEmployeesRow() {
        // employee 10's row, filed back when a ผู้จัดการ could file on their behalf: requested_by_id 88.
        SpecialMoneyRequestDto legacyOnBehalfRow = dtoRequestedBy(77L, 10L, "SUBMITTED", "AID_WEDDING", 88L);
        when(repository.findById(77L)).thenReturn(Optional.of(legacyOnBehalfRow));
        UserPrincipal filer = manager(88L, 5L);

        assertThatThrownBy(() -> service.cancel(77L, null, filer))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.requireCanAttach(77L, filer))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(repository, never()).cancel(anyLong(), any(), any());
        verify(repository, never()).addAttachment(anyLong(), any(), any(), any(), any(), any());
    }

    /** ...and the employee whose claim it is keeps both, so nothing is stranded by the line above. */
    @Test
    void theEmployeeThemselvesStillCancelsAndAttachesOnALegacyOnBehalfRow() {
        SpecialMoneyRequestDto legacyOnBehalfRow = dtoRequestedBy(77L, 10L, "SUBMITTED", "AID_WEDDING", 88L);
        // Three reads in order: requireCanAttach's, then cancel's "existing", then cancel's "after".
        when(repository.findById(77L))
            .thenReturn(Optional.of(legacyOnBehalfRow))
            .thenReturn(Optional.of(legacyOnBehalfRow))
            .thenReturn(Optional.of(dtoRequestedBy(77L, 10L, "CANCELLED", "AID_WEDDING", 88L)));
        when(repository.cancel(77L, 10L, null)).thenReturn(1);

        service.requireCanAttach(77L, user("employee", 10L));
        assertThat(service.cancel(77L, null, user("employee", 10L)).status()).isEqualTo("CANCELLED");
    }

    // ---------------------------------------------------------------------
    // approve() -- manager stage
    // ---------------------------------------------------------------------

    /**
     * Welfare is CEO-only in ONE stage. These are the shapes that could each be mistaken for an
     * approver: the requester's ผู้จัดการ (the tempting one -- it is the rule overtime uses), HR,
     * and the requester themselves.
     */
    @ParameterizedTest(name = "{0} cannot approve a welfare request")
    @MethodSource("nonCeoApprovers")
    void nobodyBelowCeoCanApproveSubmittedRequest(String label, UserPrincipal caller) {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "SUBMITTED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.approve(77L, null, caller))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        // No stage advanced and nothing was written -- a service that threw after writing would
        // still satisfy a status-code-only assertion.
        verify(repository, never()).ceoDirectApprove(anyLong(), anyLong(), any(), any(), any(), any());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> nonCeoApprovers() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("the division manager",
                new UserPrincipal(88L, "m@glr.co.th", "m", "employee", 88L, true, LocalDate.now(), false, 5L, true)),
            org.junit.jupiter.params.provider.Arguments.of("hr",
                new UserPrincipal(500L, "hr@glr.co.th", "hr", "hr", 500L, true, LocalDate.now(), false, null, false)),
            org.junit.jupiter.params.provider.Arguments.of("the requester themselves",
                new UserPrincipal(10L, "e@glr.co.th", "e", "employee", 10L, true, LocalDate.now(), false, 5L, false)));
    }

    @Test
    void ceoApprovesStraightFromSubmitted() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "AID_WEDDING");
        SpecialMoneyRequestDto approved = dto(77L, 10L, "APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(approved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        when(repository.ceoDirectApprove(eq(77L), eq(500L), any(), any(), isNull(), isNull())).thenReturn(1);

        SpecialMoneyRequestDto result = service.approve(77L, null, user("ceo", 500L));

        assertThat(result.status()).isEqualTo("APPROVED");
        // The SUBMITTED-guarded statement, not the MANAGER_APPROVED-guarded one: picking the wrong
        // statement would update zero rows and surface as a spurious 409.
        verify(repository).ceoDirectApprove(eq(77L), eq(500L), any(), any(), isNull(), isNull());
        verify(repository, never()).ceoApprove(anyLong(), anyLong(), any(), any(), any(), any());
        verify(auditService).record(any(), eq("CEO_APPROVE_SPECIAL_MONEY_REQUEST"), eq("special_money_request"), eq(77L), eq(submitted), eq(approved));
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
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_APPROVED"), anyString(), anyString(), eq("/employee-requests"), eq(true));
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

    /**
     * The regression test for gap #4. OTHER is a DISCRETIONARY type: its "eligible amount" IS the
     * requested amount, so when the cap re-check was fed the CEO's own figure the comparison
     * `approved > eligible` reduced to `approved > approved` and could never fire. A CEO could
     * approve ฿50,000 against a ฿5,000 request and never be asked why.
     */
    @Test
    void approvingMoreThanRequestedOnAnUncappedTypeNeedsAReason() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "OTHER");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("OTHER"), any(LocalDate.class))).thenReturn(new PolicyAmounts(Map.of(), 1));
        when(repository.findExcludedProvinces()).thenReturn(Set.of());

        // Requested ฿5,000 (see dto()); approving ฿50,000 with no reason must be refused.
        assertThatThrownBy(() -> service.approve(
                77L, new ReviewSpecialMoneyRequest(null, new BigDecimal("50000"), null), user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).ceoDirectApprove(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void approvingMoreThanRequestedOnAnUncappedTypeSucceedsWithAReason() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "OTHER");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(dto(77L, 10L, "APPROVED", "OTHER")));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("OTHER"), any(LocalDate.class))).thenReturn(new PolicyAmounts(Map.of(), 1));
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        when(repository.ceoDirectApprove(eq(77L), eq(500L), any(), any(), eq("ค่าใช้จ่ายจริงสูงกว่าที่ประเมินไว้"), any()))
            .thenReturn(1);

        SpecialMoneyRequestDto result = service.approve(
            77L,
            new ReviewSpecialMoneyRequest(null, new BigDecimal("50000"), "ค่าใช้จ่ายจริงสูงกว่าที่ประเมินไว้"),
            user("ceo", 500L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void approvalIsRefusedWhenAnEvidenceRequiredTypeHasNoAttachment() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "SUBMITTED", "AID_WEDDING")));
        when(repository.countAttachments(77L)).thenReturn(0);

        assertThatThrownBy(() -> service.approve(77L, null, user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        // Refused BEFORE any write -- approving money with no document trail is the whole failure
        // this gate exists to stop.
        verify(repository, never()).ceoDirectApprove(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void approvalIsAllowedWithoutEvidenceForATypeThatDoesNotRequireIt() {
        // TRAVEL_PER_DIEM is the one evidenceRequired=false type; the gate must not block it, or
        // the rule would read as "always require evidence" and the flag would be decoration.
        Map<String, String> driverInBangkok = Map.of("destination", "DOMESTIC", "role", "driver", "province", "ตาก");
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "TRAVEL_PER_DIEM", driverInBangkok);
        when(repository.findById(77L)).thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(dto(77L, 10L, "APPROVED", "TRAVEL_PER_DIEM", driverInBangkok)));
        when(repository.countAttachments(77L)).thenReturn(0);
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("TRAVEL_PER_DIEM"), any(LocalDate.class)))
            .thenReturn(new PolicyAmounts(Map.of("rate_driver", new BigDecimal("400")), 1));
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        when(repository.ceoDirectApprove(eq(77L), eq(500L), any(), any(), any(), any())).thenReturn(1);

        // ฿400/day x 1 day; approving exactly the policy figure needs no override reason.
        SpecialMoneyRequestDto result = service.approve(
            77L, new ReviewSpecialMoneyRequest(null, new BigDecimal("400"), null), user("ceo", 500L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void ceoRejectionTransitionsSubmittedToRejected() {
        SpecialMoneyRequestDto submitted = dto(77L, 10L, "SUBMITTED", "AID_WEDDING");
        SpecialMoneyRequestDto rejected = dto(77L, 10L, "REJECTED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(submitted)).thenReturn(Optional.of(rejected));
        when(repository.reject(77L, 500L, "no budget")).thenReturn(1);

        SpecialMoneyRequestDto result = service.reject(77L, new ReviewSpecialMoneyRequest("no budget", null, null), user("ceo", 500L));

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(notificationService).notify(eq(10L), eq("SPECIAL_MONEY_REJECTED"), anyString(), anyString(), eq("/employee-requests"), eq(true));
    }

    @Test
    void managerCannotRejectASubmittedRequest() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "SUBMITTED", "AID_WEDDING")));

        // Reject must be gated exactly as approve is; a manager who can refuse but not approve
        // still controls the outcome.
        assertThatThrownBy(() -> service.reject(77L, null, manager(88L, 5L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).reject(anyLong(), anyLong(), any());
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
            employeeId, LocalDate.of(2020, 1, 1), null, null, null, null, true, LocalDate.now());
    }

    private EmployeeEligibilitySnapshot inactiveEligibility(long employeeId) {
        return new EmployeeEligibilitySnapshot(
            employeeId, LocalDate.of(2020, 1, 1), null, null, null, null, false, LocalDate.now());
    }

    private UsageSnapshot emptyUsage() {
        return new UsageSnapshot(Map.of(), Map.of(), Map.of());
    }

    private PolicyAmounts weddingPolicy() {
        return new PolicyAmounts(Map.of("cap", new BigDecimal("5000")), 1);
    }

    private SpecialMoneyRequestDto dto(long id, long employeeId, String status, String requestType) {
        return dto(id, employeeId, status, requestType, Map.of());
    }

    /**
     * A row whose {@code requested_by_id} is someone OTHER than the employee -- i.e. one filed
     * on-behalf before that capability was removed. Nothing can create this shape any more, which
     * is precisely why the gates that used to key on it need a fixture that still can.
     */
    private SpecialMoneyRequestDto dtoRequestedBy(
            long id, long employeeId, String status, String requestType, long requestedById) {
        return dto(id, employeeId, status, requestType, Map.of(), requestedById);
    }

    private SpecialMoneyRequestDto dto(
            long id, long employeeId, String status, String requestType, Map<String, String> detail) {
        return dto(id, employeeId, status, requestType, detail, employeeId);
    }

    private SpecialMoneyRequestDto dto(
            long id, long employeeId, String status, String requestType, Map<String, String> detail,
            long requestedById) {
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
            detail,
            status,
            ceoApproved ? LocalDate.of(2026, 7, 1) : null,
            null,
            requestedById,
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
            0,
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            // feat/pending-approver-info: computed by SpecialMoneyRepository#mapRequest (a real SQL
            // row mapper this Mockito-level class never exercises), so no test in this class
            // asserts on them via the helper.
            null,
            null
        );
    }

    // ---------------------------------------------------------------------
    // usage()
    // ---------------------------------------------------------------------

    /**
     * The snapshot has always carried a per-year count (the once-per-year uniform gate needs it),
     * but {@code usage()} built the DTO from only two of the three maps and silently dropped it. The
     * UI therefore could not warn "you already filed this year" and the employee learned it from the
     * 400 on submit. The three maps here hold three different values on purpose: copying the wrong
     * one through fails this test rather than passing by coincidence.
     */
    @Test
    void usageCarriesThePerYearCountThroughToTheDtoInsteadOfDroppingIt() {
        when(repository.findUsage(10L, 2026)).thenReturn(new UsageSnapshot(
            Map.of(SpecialMoneyType.MEDICAL, new BigDecimal("1500")),
            Map.of(SpecialMoneyType.MEDICAL, 3, SpecialMoneyType.UNIFORM_ANNUAL, 2),
            Map.of(SpecialMoneyType.MEDICAL, 2, SpecialMoneyType.UNIFORM_ANNUAL, 1)));

        SpecialMoneyUsageDto dto = service.usage(10L, 2026, user("employee", 10L));

        assertThat(dto.activeCountThisYearByType())
            .containsEntry("MEDICAL", 2)
            .containsEntry("UNIFORM_ANNUAL", 1);
        // ...and it is not just an alias of one of the other two maps.
        assertThat(dto.approvedCountLifetimeByType()).containsEntry("MEDICAL", 3);
        assertThat(dto.approvedAmountThisYearByType().get("MEDICAL")).isEqualByComparingTo("1500");
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, "mgr@glr.co.th", "Manager", "employee", employeeId, true, LocalDate.now(), false, divisionId, true);
    }
}
