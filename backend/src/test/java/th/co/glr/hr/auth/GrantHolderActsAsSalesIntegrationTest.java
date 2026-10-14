package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB proof that the per-employee {@code can_create_quotation} grant reaches the session
 * principal: {@link SessionAccountResolver#current} re-reads the row on every call, so the grant
 * (and its revocation) takes effect without a re-login. Owner ruling 2026-10-14.
 */
class GrantHolderActsAsSalesIntegrationTest extends AbstractPostgresIntegrationTest {

    private SessionAccountResolver resolver;
    private EmployeeRepository employees;

    @BeforeEach
    void wire() {
        resolver = new SessionAccountResolver(new EmployeeAuthRepository(jdbc));
        employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
    }

    @Test
    void qcEmployeeWithGrantResolvesToSales_andRevokingItIsLiveWithoutRelogin() {
        long id = createEmployee("QC กรองสิทธิ์", "qc-grant-gas@glr.co.th", "QC", "QC-QC&ISO");
        assertThat(resolver.current(id)).get().extracting(UserPrincipal::role).isEqualTo("qc");

        setGrant(id, true);
        assertThat(resolver.current(id)).get().extracting(UserPrincipal::role).isEqualTo("sales");

        setGrant(id, false);
        assertThat(resolver.current(id)).get().extracting(UserPrincipal::role).isEqualTo("qc");
    }

    @Test
    void accountEmployeeWithGrantKeepsAccountRole() {
        long id = createEmployee("บัญชี กรองสิทธิ์", "ac-grant-gas@glr.co.th", "AC", "AC-ฝ่ายบัญชี");
        setGrant(id, true);
        assertThat(resolver.current(id)).get().extracting(UserPrincipal::role).isEqualTo("account");
    }

    private void setGrant(long id, boolean granted) {
        jdbc.update("UPDATE hr.employee SET can_create_quotation = :g WHERE employee_id = :id",
            Map.of("g", granted, "id", id));
    }

    private long createEmployee(String name, String email, String divisionCode, String divisionName) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionName, divisionName,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
