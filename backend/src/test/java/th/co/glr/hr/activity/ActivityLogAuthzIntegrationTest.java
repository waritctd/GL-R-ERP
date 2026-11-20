package th.co.glr.hr.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms the activity-log admin gate against the real service, the real repository and real SQL.
 * ActivityLogServiceTest proves the branch is chosen; only this proves the decision survives into the database
 * and that a refused caller really cannot read another employee's movements. */
class ActivityLogAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 31);

    private ActivityLogRepository repository;
    private ActivityLogService service;

    private long admin;
    private long hr;
    private long ceo;
    private long sales;
    private long plainEmployee;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new ActivityLogRepository(jdbc);
        service = new ActivityLogService(repository);

        admin = insertEmployee("ADM-001", true);
        hr = insertEmployee("HR-001", false);
        ceo = insertEmployee("CEO-001", false);
        sales = insertEmployee("SLS-001", false);
        plainEmployee = insertEmployee("EMP-001", false);

        // Something for each of them to be found in, so a leak would have data to leak.
        insertActivity(admin, "GET", "/api/activity-log", 200, at(9, 0));
        insertActivity(hr, "POST", "/api/leave-requests/7/approve", 200, at(9, 5));
        insertActivity(ceo, "GET", "/api/payroll/periods", 200, at(9, 10));
        insertActivity(sales, "GET", "/api/tickets", 200, at(9, 15));
        insertActivity(plainEmployee, "GET", "/api/tax-allowance", 200, at(9, 20));
    }

    @Test
    void refusesEveryRoleThatIsNotFlaggedAdmin() {
        // Not one of these may read the portal-wide activity of everyone else, however senior.
        assertForbidden(principal(hr, "hr"));
        assertForbidden(principal(ceo, "ceo"));
        assertForbidden(principal(sales, "sales"));
        assertForbidden(principal(plainEmployee, "employee"));
    }

    @Test
    void refusesAnAdminWhoseFlagWasRevokedWithoutThemLoggingOut() {
        UserPrincipal stillHoldingASession = principal(admin, "sales");
        assertThat(service.list(stillHoldingASession, DAY, DAY, null, null)).isNotEmpty();

        jdbc.update("UPDATE hr.employee SET is_admin = FALSE WHERE employee_id = :id",
            Map.of("id", admin));

        // No re-login, same principal object: the gate reads live, so access is gone immediately.
        assertForbidden(stillHoldingASession);
    }

    @Test
    void letsTheFlaggedAdminReadEveryEmployeesActivity() {
        // The permitted case, stated last: the whole point is cross-employee visibility, so this asserts the admin
        // sees rows belonging to people other than themselves.
        List<ActivityLogEntryDto> rows = service.list(principal(admin, "sales"), DAY, DAY, null, null);

        assertThat(rows).extracting(ActivityLogEntryDto::employeeId)
            .contains(hr, ceo, sales, plainEmployee, admin);
        assertThat(rows).extracting(ActivityLogEntryDto::path)
            .contains("/api/leave-requests/7/approve", "/api/payroll/periods");
        // Newest first — the ORDER BY is contract, because it decides which rows a LIMIT keeps.
        assertThat(rows).isSortedAccordingTo((a, b) -> b.at().compareTo(a.at()));
    }

    private void assertForbidden(UserPrincipal caller) {
        assertThatThrownBy(() -> service.list(caller, DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private OffsetDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(BANGKOK).toOffsetDateTime();
    }

    private UserPrincipal principal(long id, String role) {
        return new UserPrincipal(id, "u" + id + "@glr.co.th", "U" + id, role, id, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code, boolean isAdmin) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("isAdmin", isAdmin);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     hire_date, is_active, is_admin)
            VALUES (:code, :code, 'ทดสอบ', :code, :hireDate, TRUE, :isAdmin)
            RETURNING employee_id
            """, params, Long.class);
    }

    private void insertActivity(long employeeId, String method, String path, int status,
                                OffsetDateTime at) {
        jdbc.update("""
            INSERT INTO hr.activity_log (employee_id, actor_email, method, path, status, duration_ms, at)
            VALUES (:employeeId, :email, :method, :path, :status, 12, :at)
            """, Map.of("employeeId", employeeId, "email", "u" + employeeId + "@glr.co.th",
                "method", method, "path", path, "status", status, "at", at));
    }
}
