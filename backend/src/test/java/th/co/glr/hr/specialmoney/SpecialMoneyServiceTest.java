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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;

/** Mockito decision-level tests, modelled on OvertimeServiceTest: each gate's happy path and its 403, the 409
 * on re-review, the over-cap-without-reason 400, and the 25th-cutoff month arithmetic. */
class SpecialMoneyServiceTest {
    private final SpecialMoneyRepository repository = mock(SpecialMoneyRepository.class);
    private final SpecialMoneyPolicyEvaluator evaluator = new SpecialMoneyPolicyEvaluator();
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    private final CeoApproverRepository ceoApprovers = mock(CeoApproverRepository.class);
    private final SpecialMoneyService service = new SpecialMoneyService(
        repository, evaluator, auditService, notificationService, appProperties, ceoApprovers);

    /** Evidence-required types cannot be approved with nothing attached. Default the count to 1 so each approval
     * case below stays about the rule it names. */
    @BeforeEach
    void assumeEvidenceIsOnFile() {
        when(repository.countAttachments(anyLong())).thenReturn(1);
    }

    // submit()

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

    /** Welfare has no submit-on-behalf for anyone, so the target employee is the ONLY thing this gate looks at --
     * not the caller's role, not their ฝ่าย. All three shapes that used to differ (a plain colleague, the
     * employee's own ผู้จัดการ, HR) now take the same branch. */
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
            // THE case this change is about: employee 11 sits in division 5 and so does this ผู้จัดการ. Before 2026-08-10
            // managesEmployee() made this succeed.
            org.junit.jupiter.params.provider.Arguments.of("the employee's own ฝ่าย manager",
                new UserPrincipal(88L, "m@glr.co.th", "m", "employee", 88L, true, LocalDate.now(), false, 5L, true)),
            org.junit.jupiter.params.provider.Arguments.of("hr",
                new UserPrincipal(500L, "hr@glr.co.th", "hr", "hr", 500L, true, LocalDate.now(), false, 5L, false)),
            org.junit.jupiter.params.provider.Arguments.of("the ceo",
                new UserPrincipal(501L, "ceo@glr.co.th", "ceo", "ceo", 501L, true, LocalDate.now(), false, 5L, false)));
    }

    // approve() -- manager stage

    @Test
    void reReviewingAnAlreadyDecidedRequestIsConflict() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "REJECTED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.approve(77L, null, user("ceo", 500L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // approve() -- CEO stage / cap re-check / cutoff

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
    void ceoApprovalRollsForwardPastAProcessedMonth() {
        appProperties.getSpecialMoney().setPayrollCutoffDay(31);
        SpecialMoneyRequestDto managerApproved = dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING");
        SpecialMoneyRequestDto approved = dto(77L, 10L, "APPROVED", "AID_WEDDING");
        when(repository.findById(77L)).thenReturn(Optional.of(managerApproved)).thenReturn(Optional.of(approved));
        when(repository.findEligibility(eq(10L), any(LocalDate.class))).thenReturn(Optional.of(activeEligibility(10L)));
        when(repository.findUsage(eq(10L), anyInt())).thenReturn(emptyUsage());
        when(repository.findPolicyAmounts(eq("AID_WEDDING"), any(LocalDate.class))).thenReturn(weddingPolicy());
        when(repository.findExcludedProvinces()).thenReturn(Set.of());
        LocalDate thisMonth = LocalDate.now(SpecialMoneyService.BUSINESS_ZONE).withDayOfMonth(1);
        LocalDate nextMonth = thisMonth.plusMonths(1);
        when(repository.payrollMonthProcessed(thisMonth)).thenReturn(true);
        when(repository.payrollMonthProcessed(nextMonth)).thenReturn(false);
        when(repository.ceoApprove(eq(77L), eq(500L), any(), eq(nextMonth), any(), any())).thenReturn(1);

        service.approve(77L, null, user("ceo", 500L));

        verify(repository).ceoApprove(eq(77L), eq(500L), any(), eq(nextMonth), any(), any());
    }

    // cancel()

    @Test
    void cancelOfAlreadyApprovedRequestIsConflict() {
        when(repository.findById(77L)).thenReturn(Optional.of(dto(77L, 10L, "MANAGER_APPROVED", "AID_WEDDING")));

        assertThatThrownBy(() -> service.cancel(77L, null, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // helpers

    private SubmitSpecialMoneyRequest weddingRequest(Long employeeId, LocalDate eventDate) {
        return new SubmitSpecialMoneyRequest(
            employeeId, eventDate, null, null, BigDecimal.ONE, new BigDecimal("5000"), "Getting married", Map.of());
    }

    private EmployeeEligibilitySnapshot activeEligibility(long employeeId) {
        return new EmployeeEligibilitySnapshot(
            employeeId, LocalDate.of(2020, 1, 1), null, null, null, null, true, LocalDate.now());
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
            // feat/pending-approver-info: computed by SpecialMoneyRepository#mapRequest (a real SQL row mapper this
            // Mockito-level class never exercises), so no test in this class asserts on them via the helper.
            null,
            null
        );
    }

    // usage()

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, "mgr@glr.co.th", "Manager", "employee", employeeId, true, LocalDate.now(), false, divisionId, true);
    }
}
