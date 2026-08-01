package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationOverrideRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationUpdateRequest;

/**
 * Unit-tests the DECISION: which role may do what, and how {@link
 * DeductionObligationService#resolveMonthlyAmount} picks the amount to deduct. Mockito proves the
 * branch chosen here; it cannot prove the decision survives into real SQL (a mocked repository
 * "finds" whatever the test tells it to) — that half is {@link
 * DeductionObligationScopeIntegrationTest}, against real Postgres.
 */
class DeductionObligationServiceTest {
    private final DeductionObligationRepository repository = mock(DeductionObligationRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final DeductionObligationService service =
        new DeductionObligationService(repository, employeeRepository, auditService);

    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    // ---- role gating: HR-only mutations -----------------------------------------------------

    @Test
    void anEmployeeCannotCreateAnObligation() {
        assertForbidden(() -> service.create(createRequest(), employee(10L)));
        verifyNoInteractions(repository);
    }

    @Test
    void ceoCannotCreateAnObligation_viewIsNotEdit() {
        assertForbidden(() -> service.create(createRequest(), ceo()));
        verifyNoInteractions(repository);
    }

    @Test
    void hrCanCreateAnObligation() {
        when(employeeRepository.exists(10L)).thenReturn(true);
        when(repository.insert(any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(dto(1L, 10L, DeductionObligationStatus.ACTIVE, false)));

        DeductionObligationDto created = service.create(createRequest(), hr());

        assertThat(created.id()).isEqualTo(1L);
        verify(auditService).record(any(), eq("CREATE_DEDUCTION_OBLIGATION"), eq("deduction_obligation"), eq(1L), eq(null), any());
    }

    @Test
    void creatingADuplicateActiveObligationIsAConflictNotACrash() {
        when(employeeRepository.exists(10L)).thenReturn(true);
        when(repository.insert(any(), any())).thenThrow(mock(DuplicateKeyException.class));

        assertThatThrownBy(() -> service.create(createRequest(), hr()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anEmployeeCannotUpdateStopAcknowledgeOrOverride() {
        long id = 5L;
        when(repository.findById(id)).thenReturn(Optional.of(dto(id, 10L, DeductionObligationStatus.COMPLETED, false)));

        assertForbidden(() -> service.update(id, updateRequest(), employee(10L)));
        assertForbidden(() -> service.stop(id, employee(10L)));
        assertForbidden(() -> service.acknowledgeCompletion(id, employee(10L)));
        assertForbidden(() -> service.overrideContinue(id, new DeductionObligationOverrideRequest("เหตุผล"), employee(10L)));
        assertForbidden(() -> service.clearOverrideContinue(id, employee(10L)));

        verify(repository, never()).update(anyLong(), any(), anyLong());
        verify(repository, never()).stop(anyLong(), anyLong());
        verify(repository, never()).acknowledgeCompletion(anyLong(), anyLong());
        verify(repository, never()).setOverrideContinue(anyLong(), anyLong(), any());
        verify(repository, never()).clearOverrideContinue(anyLong(), anyLong());
    }

    @Test
    void overrideContinueRefusesAnObligationThatHasNotReachedItsTotal() {
        long id = 7L;
        when(repository.findById(id)).thenReturn(Optional.of(dto(id, 10L, DeductionObligationStatus.ACTIVE, false)));

        assertThatThrownBy(() -> service.overrideContinue(id, new DeductionObligationOverrideRequest("เหตุผล"), hr()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).setOverrideContinue(anyLong(), anyLong(), any());
    }

    // ---- role gating: view (hr/ceo) vs employee self-service (getOwn) ------------------------

    @Test
    void anEmployeeCannotReadTheHrRegister() {
        assertForbidden(() -> service.list(null, null, null, employee(10L)));
        verifyNoInteractions(repository);
    }

    @Test
    void anEmployeeCannotReadHrProgressByIdEvenForTheirOwnObligation() {
        // Deliberately NOT stubbing repository.findById -- requireRole must fail before any lookup,
        // so an employee gets 403, never a 200 for their own row and never a 404 leaking existence.
        assertForbidden(() -> service.progress(5L, employee(10L)));
        verifyNoInteractions(repository);
    }

    @Test
    void ceoCanListAndReadProgressButNotEdit() {
        when(repository.findAll(null, null, null)).thenReturn(List.of());
        assertThat(service.list(null, null, null, ceo())).isEmpty();

        when(repository.findById(5L)).thenReturn(Optional.of(dto(5L, 10L, DeductionObligationStatus.ACTIVE, false)));
        when(repository.sumRemittanceTotal(5L)).thenReturn(BigDecimal.ZERO);
        when(repository.countRemittedPeriods(5L)).thenReturn(0);
        when(repository.findLedger(5L)).thenReturn(List.of());
        assertThat(service.progress(5L, ceo()).obligation().id()).isEqualTo(5L);
    }

    @Test
    void getOwnIsScopedToTheCallersOwnEmployeeIdOnly() {
        when(repository.findForEmployee(10L)).thenReturn(List.of(dto(1L, 10L, DeductionObligationStatus.ACTIVE, false)));
        when(repository.sumRemittanceTotal(1L)).thenReturn(BigDecimal.ZERO);
        when(repository.countRemittedPeriods(1L)).thenReturn(0);
        when(repository.findLedger(1L)).thenReturn(List.of());

        service.getOwn(employee(10L));

        // The decision under test: getOwn() must ask the repository for THIS caller's employeeId,
        // never any other -- there is no employeeId parameter anywhere on this call for a caller to
        // widen. The real "does employee A's row leak to employee B" proof needs the actual SQL
        // (DeductionObligationScopeIntegrationTest); this only proves the service asks the right
        // question.
        verify(repository).findForEmployee(10L);
        verify(repository, never()).findForEmployee(eq(20L));
    }

    @Test
    void getOwnRefusesAnActorWithNoLinkedEmployee() {
        UserPrincipal noEmployee = new UserPrincipal(9L, "x@glr.co.th", "x", "employee", null, true, LocalDate.now(), false, null, false);
        assertForbidden(() -> service.getOwn(noEmployee));
        verifyNoInteractions(repository);
    }

    // ---- resolveMonthlyAmount: the draw-down decision --------------------------------------

    @Test
    void noObligationOnFileFallsBackToTheManualFigure() {
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.empty());

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, new BigDecimal("1000"));

        assertThat(resolved).isEqualByComparingTo("1000.00");
    }

    @Test
    void noObligationAndNoManualFigureFallsBackToZero() {
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.empty());

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, null);

        assertThat(resolved).isEqualByComparingTo("0.00");
    }

    /**
     * D2 regression (Opus review): an obligation with a start_date after this payroll month must be
     * invisible to this call -- enforced by {@link DeductionObligationRepository#findDrivingObligation}'s
     * own {@code start_date <= :payrollMonth} filter (proven against real Postgres in {@link
     * DeductionObligationPayrollEngineIntegrationTest}). At the service layer this collapses to the
     * same "no obligation found" fallback as the tests above -- documented here so the connection is
     * explicit: a future-start obligation must never surface as a hit.
     */
    @Test
    void anObligationThatHasNotStartedYetIsInvisibleForThisMonth() {
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.empty());

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, new BigDecimal("500"));

        assertThat(resolved).isEqualByComparingTo("500.00"); // falls back exactly like "no obligation at all"
    }

    @Test
    void anUncappedObligationDeductsTheMonthlyFigureEveryPeriod() {
        DeductionObligationDto uncapped = new DeductionObligationDto(
            1L, 10L, "E10", "พนักงาน", DeductionObligationKind.STUDENT_LOAN,
            new BigDecimal("1000.00"), null, "กยศ-001", LocalDate.of(2026, 1, 1),
            DeductionObligationStatus.ACTIVE, null, null, null, false, null, null, null, null, null, null);
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.of(uncapped));

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, new BigDecimal("9999"));

        assertThat(resolved).isEqualByComparingTo("1000.00"); // manual figure ignored -- the obligation drives it
    }

    @Test
    void theFinalInstalmentIsClampedToTheRemainderNotTheFullMonthlyFigure() {
        DeductionObligationDto capped = new DeductionObligationDto(
            2L, 10L, "E10", "พนักงาน", DeductionObligationKind.LEGAL_EXECUTION,
            new BigDecimal("1000.00"), new BigDecimal("50640.00"), "LED-002", LocalDate.of(2026, 1, 1),
            DeductionObligationStatus.ACTIVE, null, null, null, false, null, null, null, null, null, null);
        when(repository.findDrivingObligation(10L, DeductionObligationKind.LEGAL_EXECUTION, MONTH)).thenReturn(Optional.of(capped));
        // 50 whole periods already remitted (50,000), 640 left -- less than the ฿1,000 monthly figure.
        when(repository.sumRemittanceExcludingMonth(2L, MONTH)).thenReturn(new BigDecimal("50000.00"));

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.LEGAL_EXECUTION, MONTH, null);

        assertThat(resolved).isEqualByComparingTo("640.00"); // the remainder, never an overshoot
    }

    /**
     * D1 fix (Opus review): the DTO's stored {@code status} is COMPLETED (a stale snapshot from a
     * PRIOR run of this very month), but the ledger EXCLUDING this month shows only 2,000 remitted
     * against a 2,640 total -- 640 genuinely remains. The decision must be driven by the ledger, not
     * the stale status column, or the final instalment vanishes on every re-run of the completing
     * month (see the integration-level reproduction in {@code
     * DeductionObligationPayrollEngineIntegrationTest#theCompletingMonthYieldsTheSameFinalInstalmentOnEveryReprocess}).
     */
    @Test
    void theAmountIsDerivedFromTheLedgerNotTheStaleStoredStatus() {
        DeductionObligationDto staleCompleted = new DeductionObligationDto(
            5L, 10L, "E10", "พนักงาน", DeductionObligationKind.STUDENT_LOAN,
            new BigDecimal("1000.00"), new BigDecimal("2640.00"), "กยศ-STALE", LocalDate.of(2026, 1, 1),
            DeductionObligationStatus.COMPLETED, OffsetDateTime.now(), null, null,
            false, null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now());
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.of(staleCompleted));
        when(repository.sumRemittanceExcludingMonth(5L, MONTH)).thenReturn(new BigDecimal("2000.00"));

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, null);

        assertThat(resolved).isEqualByComparingTo("640.00"); // NOT zero -- the stale COMPLETED status must not win
    }

    @Test
    void aCompletedObligationWithoutOverrideDeductsNothing() {
        DeductionObligationDto completed = dto(3L, 10L, DeductionObligationStatus.COMPLETED, false);
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.of(completed));
        // Every OTHER period already reaches the 50,000.00 total -- genuinely complete, not stale.
        when(repository.sumRemittanceExcludingMonth(3L, MONTH)).thenReturn(new BigDecimal("50000.00"));

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, new BigDecimal("1000"));

        assertThat(resolved).isEqualByComparingTo("0.00"); // auto-stopped, manual figure ignored too
    }

    @Test
    void hrOverrideResumesDeductionPastTheCompletedTotal() {
        DeductionObligationDto overridden = dto(4L, 10L, DeductionObligationStatus.COMPLETED, true);
        when(repository.findDrivingObligation(10L, DeductionObligationKind.STUDENT_LOAN, MONTH)).thenReturn(Optional.of(overridden));
        // Genuinely complete from other periods alone -- override is what resumes it.
        when(repository.sumRemittanceExcludingMonth(4L, MONTH)).thenReturn(new BigDecimal("50000.00"));

        BigDecimal resolved = service.resolveMonthlyAmount(10L, DeductionObligationKind.STUDENT_LOAN, MONTH, null);

        assertThat(resolved).isEqualByComparingTo("1000.00"); // resumes at the monthly figure, uncapped
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private DeductionObligationCreateRequest createRequest() {
        return new DeductionObligationCreateRequest(
            10L, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), null, "กยศ-001",
            LocalDate.of(2026, 1, 1), null);
    }

    private DeductionObligationUpdateRequest updateRequest() {
        return new DeductionObligationUpdateRequest(new BigDecimal("1200.00"), null, "กยศ-001-แก้ไข", null);
    }

    private DeductionObligationDto dto(long id, long employeeId, DeductionObligationStatus status, boolean override) {
        return new DeductionObligationDto(
            id, employeeId, "E" + employeeId, "พนักงาน", DeductionObligationKind.STUDENT_LOAN,
            new BigDecimal("1000.00"), new BigDecimal("50000.00"), "กยศ-001", LocalDate.of(2026, 1, 1),
            status,
            status == DeductionObligationStatus.COMPLETED ? OffsetDateTime.now() : null,
            null, null,
            override, override ? 99L : null, override ? OffsetDateTime.now() : null, override ? "อนุมัติให้หักต่อ" : null,
            null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(2L, "ceo@glr.co.th", "CEO", "ceo", 2L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true, LocalDate.now(), false, null, false);
    }
}
