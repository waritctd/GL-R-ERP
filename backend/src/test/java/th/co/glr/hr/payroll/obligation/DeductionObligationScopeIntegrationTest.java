package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationOverrideRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationProgressDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms deduction-obligation authorization against the real service and the real SQL (issue #373) -- the
 * gap Mockito-based unit tests (DeductionObligationServiceTest) cannot cover. Copy of
 * AttendanceScopeIntegrationTest's shape. */
class DeductionObligationScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private DeductionObligationRepository repository;
    private DeductionObligationService service;

    private long employeeA;
    private long employeeB;
    private long obligationForA;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new DeductionObligationRepository(jdbc);
        service = new DeductionObligationService(
            repository, mock(EmployeeRepository.class), mock(AuditService.class),
            new PayrollDeductionShortfallRepository(jdbc));

        employeeA = seedEmployee("OBL-A");
        employeeB = seedEmployee("OBL-B");
        obligationForA = repository.insert(
            new DeductionObligationCreateRequest(
                employeeA, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"),
                new BigDecimal("50000.00"), "กยศ-A-001", LocalDate.of(2026, 1, 1), null),
            employeeA);
    }

    // --- employee self-service: getOwn is scoped to the caller, never widened -----------------

    @Test
    void anEmployeesGetOwnNeverReturnsAnotherEmployeesObligation() {
        List<DeductionObligationProgressDto> ownedByB = service.getOwn(employee(employeeB));

        assertThat(ownedByB).isEmpty();
    }

    @Test
    void anEmployeesGetOwnReturnsOnlyTheirOwnObligation() {
        List<DeductionObligationProgressDto> ownedByA = service.getOwn(employee(employeeA));

        assertThat(ownedByA).hasSize(1);
        assertThat(ownedByA.get(0).obligation().id()).isEqualTo(obligationForA);
        assertThat(ownedByA.get(0).obligation().employeeId()).isEqualTo(employeeA);
    }

    // --- HR/CEO register + progress are never reachable by a plain employee --------------------

    @Test
    void anEmployeeCannotReadTheHrRegisterEvenFilteredToThemselves() {
        assertForbidden(() -> service.list(employeeA, null, null, employee(employeeA)));
    }

    @Test
    void anEmployeeCannotReadHrProgressForTheirOwnObligationById() {
        // The HR/CEO id-based progress route is deliberately closed to every employee, including the owner -- getOwn()
        // is the only employee-facing read.
        assertForbidden(() -> service.progress(obligationForA, employee(employeeA)));
    }

    @Test
    void anEmployeeCannotReadAnotherEmployeesObligationEvenIfTheyGuessTheId() {
        assertForbidden(() -> service.progress(obligationForA, employee(employeeB)));
    }

    // --- HR mutations are never reachable by a plain employee or by CEO (view != edit) --------

    @Test
    void anEmployeeCannotCreateAnObligationForThemselvesOrAnyoneElse() {
        DeductionObligationCreateRequest request = new DeductionObligationCreateRequest(
            employeeA, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("500.00"), null,
            "LED-X", LocalDate.of(2026, 2, 1), null);

        assertForbidden(() -> service.create(request, employee(employeeA)));
        assertThat(repository.findForEmployee(employeeA)).hasSize(1); // unchanged -- still only the seeded one
    }

    @Test
    void ceoCanViewTheRegisterButCannotStopAnObligation() {
        List<DeductionObligationDto> visibleToCeo = service.list(null, null, null, ceo());
        assertThat(visibleToCeo).extracting(DeductionObligationDto::id).contains(obligationForA);

        assertForbidden(() -> service.stop(obligationForA, ceo()));
        assertThat(repository.findById(obligationForA).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.ACTIVE); // unchanged
    }

    @Test
    void anEmployeeCannotOverrideOrAcknowledgeCompletion() {
        // Drive the real obligation to COMPLETED first (real remittance ledger, real SQL threshold).
        repository.upsertRemittance(obligationForA, LocalDate.of(2026, 1, 1), new BigDecimal("50000.00"), null);
        repository.applyCompletionTransition(obligationForA, true);
        assertThat(repository.findById(obligationForA).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);

        assertForbidden(() -> service.acknowledgeCompletion(obligationForA, employee(employeeA)));
        assertForbidden(() -> service.overrideContinue(
            obligationForA, new DeductionObligationOverrideRequest("อยากหักต่อ"), employee(employeeA)));

        DeductionObligationDto stillUntouched = repository.findById(obligationForA).orElseThrow();
        assertThat(stillUntouched.completionAcknowledgedAt()).isNull();
        assertThat(stillUntouched.overrideContinuePastTotal()).isFalse();
    }

    // --- helpers ------------------------------------------------------------

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "CEO", "ceo", employeeA, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(2L, "emp@glr.co.th", "emp", "employee", employeeId, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, 'ทดสอบ', :code, 30000.00, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code),
            Long.class);
    }
}
