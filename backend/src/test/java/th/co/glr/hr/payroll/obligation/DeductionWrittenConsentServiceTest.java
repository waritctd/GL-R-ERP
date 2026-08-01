package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentUpsertRequest;

/**
 * Unit-tests the DECISION: which role may view/edit the written-consent record, and which deduction
 * kinds are even eligible (issue #376 restricts this to the four ม.76-item(2)-(5)-shaped kinds).
 * Mockito proves the branch chosen; {@link DeductionWrittenConsentScopeIntegrationTest} proves it
 * survives into real SQL.
 */
class DeductionWrittenConsentServiceTest {
    private final DeductionWrittenConsentRepository repository = mock(DeductionWrittenConsentRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final DeductionWrittenConsentService service =
        new DeductionWrittenConsentService(repository, employeeRepository, auditService);

    @Test
    void anEmployeeCannotListConsentRecords() {
        assertForbidden(() -> service.list(1L, null, employee(1L)));
        verifyNoInteractions(repository);
    }

    @Test
    void anEmployeeCannotUpsertAConsentRecord() {
        assertForbidden(() -> service.upsert(request(PayrollDeductionKind.WARNING_LETTER), employee(1L)));
        verifyNoInteractions(repository);
    }

    @Test
    void ceoCanListButCannotUpsert() {
        when(repository.findAll(null, null)).thenReturn(List.of());
        service.list(null, null, ceo());

        assertForbidden(() -> service.upsert(request(PayrollDeductionKind.WARNING_LETTER), ceo()));
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void hrCanUpsertAConsentApplicableKind() {
        when(employeeRepository.exists(1L)).thenReturn(true);
        when(repository.findAll(1L, PayrollDeductionKind.WARNING_LETTER)).thenReturn(List.of());

        service.upsert(request(PayrollDeductionKind.WARNING_LETTER), hr());

        verify(repository).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(99L));
    }

    @Test
    void statutoryOrCourtOrderedKindsAreRejectedAsNotConsentApplicable() {
        for (PayrollDeductionKind kind : new PayrollDeductionKind[] {
            PayrollDeductionKind.WITHHOLDING_TAX, PayrollDeductionKind.SOCIAL_SECURITY,
            PayrollDeductionKind.STUDENT_LOAN, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT
        }) {
            assertThatThrownBy(() -> service.upsert(request(kind), hr()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void upsertingForAnUnknownEmployeeIs404() {
        when(employeeRepository.exists(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.upsert(request(PayrollDeductionKind.CUSTOMER_RETURN), hr()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    // --- helpers ------------------------------------------------------------

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private DeductionWrittenConsentUpsertRequest request(PayrollDeductionKind kind) {
        return new DeductionWrittenConsentUpsertRequest(1L, kind, true, "DOC-1", LocalDate.of(2026, 1, 1), "note");
    }

    private UserPrincipal hr() {
        return new UserPrincipal(99L, "hr@glr.co.th", "HR", "hr", 99L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(2L, "ceo@glr.co.th", "CEO", "ceo", 2L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true, LocalDate.now(), false, null, false);
    }
}
