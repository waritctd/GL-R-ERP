package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentDto;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms written-consent authorization against the real service and the real SQL (issue #376) --
 * the gap Mockito-based unit tests ({@link DeductionWrittenConsentServiceTest}) cannot cover. Copy
 * of {@link DeductionObligationScopeIntegrationTest}'s shape, per CLAUDE.md's requirement that a
 * role-gate change ships a real-Postgres integration test, written wrong-way-round.
 */
class DeductionWrittenConsentScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private DeductionWrittenConsentRepository repository;
    private DeductionWrittenConsentService service;
    private EmployeeRepository employeeRepository;

    private long employeeA;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new DeductionWrittenConsentRepository(jdbc);
        employeeRepository = mock(EmployeeRepository.class);
        service = new DeductionWrittenConsentService(repository, employeeRepository, mock(AuditService.class));

        employeeA = seedEmployee("CONSENT-A");
        when(employeeRepository.exists(employeeA)).thenReturn(true);
        repository.upsert(new DeductionWrittenConsentUpsertRequest(
            employeeA, PayrollDeductionKind.WARNING_LETTER, true, "DOC-A-1",
            LocalDate.of(2026, 1, 1), null), employeeA);
    }

    @Test
    void anEmployeeCannotListConsentRecordsEvenFilteredToThemselves() {
        assertForbidden(() -> service.list(employeeA, null, employee(employeeA)));
    }

    @Test
    void anEmployeeCannotUpsertAConsentRecordForThemselves() {
        DeductionWrittenConsentUpsertRequest request = new DeductionWrittenConsentUpsertRequest(
            employeeA, PayrollDeductionKind.CUSTOMER_RETURN, true, "SELF-SERVE", LocalDate.of(2026, 2, 1), null);

        assertForbidden(() -> service.upsert(request, employee(employeeA)));
        assertThat(repository.findAll(employeeA, PayrollDeductionKind.CUSTOMER_RETURN)).isEmpty(); // unchanged
    }

    @Test
    void ceoCanViewButCannotUpsert() {
        List<DeductionWrittenConsentDto> visibleToCeo = service.list(employeeA, null, ceo());
        assertThat(visibleToCeo).hasSize(1);

        DeductionWrittenConsentUpsertRequest request = new DeductionWrittenConsentUpsertRequest(
            employeeA, PayrollDeductionKind.WARNING_LETTER, false, null, null, "ceo trying to edit");
        assertForbidden(() -> service.upsert(request, ceo()));

        // Unchanged -- the CEO's attempted edit never reached the repository.
        DeductionWrittenConsentDto stillOriginal = repository.findAll(employeeA, PayrollDeductionKind.WARNING_LETTER).get(0);
        assertThat(stillOriginal.consentOnFile()).isTrue();
        assertThat(stillOriginal.consentDocumentReference()).isEqualTo("DOC-A-1");
    }

    @Test
    void hrCanUpsertAConsentApplicableKindButNotAStatutoryOrCourtOrderedOne() {
        DeductionWrittenConsentUpsertRequest applicable = new DeductionWrittenConsentUpsertRequest(
            employeeA, PayrollDeductionKind.OTHER_PRETAX, true, "DOC-HR-1", LocalDate.of(2026, 3, 1), null);
        service.upsert(applicable, hr());
        assertThat(repository.findAll(employeeA, PayrollDeductionKind.OTHER_PRETAX)).hasSize(1);

        DeductionWrittenConsentUpsertRequest notApplicable = new DeductionWrittenConsentUpsertRequest(
            employeeA, PayrollDeductionKind.STUDENT_LOAN, true, "SHOULD-NOT-APPLY", LocalDate.of(2026, 3, 1), null);
        assertThatThrownBy(() -> service.upsert(notApplicable, hr()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- helpers ------------------------------------------------------------

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "CEO", "ceo", employeeA, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(4L, "hr@glr.co.th", "HR", "hr", employeeA, true, LocalDate.now(), false, null, false);
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
