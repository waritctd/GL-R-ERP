package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Proves a successful login actually lands a row in {@code hr.audit_log}, through the real
 * {@link AuthService}, the real {@link AuditService} and the real {@link AuditLogRepository},
 * against real PostgreSQL.
 *
 * <p>A Mockito {@code verify(audit).record(...)} — which {@code AuthServiceTest} also does — proves
 * only that the service <em>called</em> the collaborator. It cannot prove the INSERT is accepted:
 * {@code action} and {@code entity} are NOT NULL, {@code before_json}/{@code after_json} are
 * {@code jsonb} fed through a {@code CAST(... AS jsonb)}, and the table carries an append-only
 * trigger. Any of those could reject the write while every mock-based test stayed green. That gap
 * is exactly the failure shape CLAUDE.md's mock-contract section warns about, so the assertion has
 * to reach the database.
 *
 * <p>Note {@code hr.audit_log} is append-only by trigger ({@code V18}), so this test cannot clean up
 * after itself. Every assertion is therefore scoped to the employee row it created rather than to a
 * table-wide count.
 */
class LoginAuditIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "Str0ngPass!";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AuthService service;
    private long employeeId;

    @BeforeEach
    void wireRealCollaborators() {
        service = new AuthService(
            new EmployeeAuthRepository(jdbc),
            encoder,
            new AuditService(new AuditLogRepository(jdbc), new ObjectMapper().findAndRegisterModules()));

        long hrDivision = insertDivision("HR", "ฝ่ายบุคคล");
        employeeId = insertEmployee("AUD-001", "audit.login@glr.co.th", encoder.encode(PASSWORD), hrDivision);
    }

    @Test
    void writesOneLoginRowThatNamesTheEmployeeAndTheirDerivedRole() {
        service.login(new LoginRequest("audit.login@glr.co.th", PASSWORD, null), new MockHttpServletRequest());

        List<Map<String, Object>> rows = loginRowsFor(employeeId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(employeeId);
        assertThat(row.get("actor_email")).isEqualTo("audit.login@glr.co.th");
        assertThat(row.get("entity")).isEqualTo("employee");
        assertThat(row.get("entity_id")).isEqualTo(employeeId);
        assertThat(row.get("before_json")).isNull();
        assertThat(row.get("at")).isNotNull();
        // The role travels in after_json because it is derived from the division at login time;
        // if the employee later moves division, the row still says what they were when they logged in.
        assertThat(row.get("role")).isEqualTo("hr");
        assertThat(row.get("must_change_password")).isEqualTo("false");
    }

    @Test
    void writesOneRowPerLoginSoRepeatVisitsAreDistinguishable() {
        service.login(new LoginRequest("audit.login@glr.co.th", PASSWORD, null), new MockHttpServletRequest());
        service.login(new LoginRequest("audit.login@glr.co.th", PASSWORD, null), new MockHttpServletRequest());
        service.login(new LoginRequest("audit.login@glr.co.th", PASSWORD, null), new MockHttpServletRequest());

        assertThat(loginRowsFor(employeeId)).hasSize(3);
    }

    @Test
    void writesNothingWhenTheCredentialsAreRejected() {
        // Asked the wrong way round: the trail must never imply a login that did not happen.
        assertThatThrownBy(() -> service.login(
            new LoginRequest("audit.login@glr.co.th", "wrong-password", null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(
            new LoginRequest("nobody@glr.co.th", PASSWORD, null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);

        assertThat(loginRowsFor(employeeId)).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hr.audit_log WHERE action = 'LOGIN'", Map.of(), Long.class)).isZero();
    }

    @Test
    void writesNothingWhenTheEmployeeIsInactive() {
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id",
            Map.of("id", employeeId));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("audit.login@glr.co.th", PASSWORD, null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);

        assertThat(loginRowsFor(employeeId)).isEmpty();
    }

    private List<Map<String, Object>> loginRowsFor(long id) {
        return jdbc.queryForList("""
            SELECT actor_user_id, actor_email, entity, entity_id, before_json, at,
                   after_json ->> 'role'               AS role,
                   after_json ->> 'mustChangePassword'  AS must_change_password
              FROM hr.audit_log
             WHERE action = 'LOGIN' AND actor_user_id = :id
             ORDER BY id
            """, Map.of("id", id));
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, String email, String passwordHash, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("email", email);
        params.put("hash", passwordHash);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     email, password_hash, must_change_password,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :email, :hash, FALSE, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
