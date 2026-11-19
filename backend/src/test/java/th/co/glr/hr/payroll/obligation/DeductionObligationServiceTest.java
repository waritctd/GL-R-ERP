package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationDto;

/** Unit-tests the DECISION: which role may do what, and how DeductionObligationService#resolveMonthlyAmount
 * picks the amount to deduct. */
class DeductionObligationServiceTest {
    private final DeductionObligationRepository repository = mock(DeductionObligationRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PayrollDeductionShortfallRepository shortfallRepository = mock(PayrollDeductionShortfallRepository.class);
    private final DeductionObligationService service =
        new DeductionObligationService(repository, employeeRepository, auditService, shortfallRepository);

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

    // ---- resolveMonthlyAmount: the draw-down decision --------------------------------------

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
