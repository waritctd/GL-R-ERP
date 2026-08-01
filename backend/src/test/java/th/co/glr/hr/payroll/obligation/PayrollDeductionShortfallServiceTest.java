package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/**
 * Unit-tests the DECISION: which role may read the shortfall ledger. Mockito proves the branch
 * chosen; {@link PayrollDeductionShortfallScopeIntegrationTest} proves it survives into real SQL.
 */
class PayrollDeductionShortfallServiceTest {
    private final PayrollDeductionShortfallRepository repository = mock(PayrollDeductionShortfallRepository.class);
    private final PayrollDeductionShortfallService service = new PayrollDeductionShortfallService(repository);

    @Test
    void anEmployeeCannotListTheShortfallLedger() {
        assertForbidden(() -> service.list(1L, null, employee(1L)));
        verifyNoInteractions(repository);
    }

    @Test
    void hrCanListTheShortfallLedger() {
        when(repository.findAll(null, null)).thenReturn(List.of());
        assertThat(service.list(null, null, hr())).isEmpty();
    }

    @Test
    void ceoCanListTheShortfallLedger() {
        when(repository.findAll(null, null)).thenReturn(List.of());
        assertThat(service.list(null, null, ceo())).isEmpty();
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
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
