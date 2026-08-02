package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallDtos.PayrollDeductionShortfallDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms shortfall-ledger read authorization against the real service and real SQL (issue #376)
 * -- the gap Mockito-based unit tests ({@link PayrollDeductionShortfallServiceTest}) cannot cover.
 * Copy of {@link DeductionObligationScopeIntegrationTest}'s shape, written wrong-way-round per
 * CLAUDE.md's requirement for any role-gated read/write.
 */
class PayrollDeductionShortfallScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollDeductionShortfallRepository repository;
    private PayrollDeductionShortfallService service;

    private long employeeA;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new PayrollDeductionShortfallRepository(jdbc);
        service = new PayrollDeductionShortfallService(repository);

        employeeA = seedEmployee("SHORTFALL-SCOPE-A");
        repository.upsert(employeeA, LocalDate.of(2026, 1, 1), PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT,
            new BigDecimal("20000.00"), new BigDecimal("9000.00"), new BigDecimal("11000.00"),
            "ป.วิ.พ. ม.302 -- test row", null);
    }

    @Test
    void anEmployeeCannotReadTheShortfallLedgerEvenFilteredToThemselves() {
        assertForbidden(() -> service.list(employeeA, null, employee(employeeA)));
    }

    @Test
    void anEmployeeCannotReadTheShortfallLedgerUnfiltered() {
        assertForbidden(() -> service.list(null, null, employee(employeeA)));
    }

    @Test
    void hrCanReadTheShortfallLedger() {
        List<PayrollDeductionShortfallDto> rows = service.list(employeeA, null, hr());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).employeeId()).isEqualTo(employeeA);
    }

    @Test
    void ceoCanReadTheShortfallLedger() {
        List<PayrollDeductionShortfallDto> rows = service.list(employeeA, null, ceo());
        assertThat(rows).hasSize(1);
    }

    // --- helpers ------------------------------------------------------------

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", employeeA, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(4L, "ceo@glr.co.th", "CEO", "ceo", employeeA, true, LocalDate.now(), false, null, false);
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
