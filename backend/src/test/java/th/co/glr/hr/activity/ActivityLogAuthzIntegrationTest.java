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

/**
 * Confirms the activity-log admin gate against the real service, the real repository and real SQL.
 *
 * <p>Step 2 of CLAUDE.md's requirement for an authorization change. {@link ActivityLogServiceTest}
 * proves the branch is chosen; only this proves the decision survives into the database and that a
 * refused caller really cannot read another employee's movements. Mockito cannot reach this — a
 * mocked repository passes while the SQL does whatever it likes.
 *
 * <p>Every case is asked the wrong way round: can a caller who should not see this reach it.
 */
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
    void refusesTheSummaryEndpointForTheSameCallers() {
        assertThatThrownBy(() -> service.summarize(principal(hr, "hr"), DAY, DAY))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.summarize(principal(ceo, "ceo"), DAY, DAY))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void refusesTheSemanticAuditEndpointForTheSameCallers() {
        // Third entry point, same gate. hr.audit_log carries who approved whose leave and who
        // processed payroll, so an ungated read here would be the worst of the three.
        assertThatThrownBy(() -> service.auditEvents(principal(hr, "hr"), DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.auditEvents(principal(ceo, "ceo"), DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.auditEvents(principal(plainEmployee, "admin"), DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void showsTheAdminWhoRequestedAndWhoApproved() {
        jdbc.update("""
            INSERT INTO hr.audit_log (actor_user_id, actor_email, action, entity, entity_id, at)
            VALUES (:sales, 'sales@glr.co.th', 'SUBMIT_LEAVE_REQUEST', 'leave_request', 7, :t1),
                   (:hr,    'hr@glr.co.th',    'APPROVE_LEAVE_REQUEST', 'leave_request', 7, :t2)
            """, Map.of("sales", sales, "hr", hr, "t1", at(10, 0), "t2", at(10, 5)));

        List<AuditEventDto> events = service.auditEvents(principal(admin, "sales"), DAY, DAY, null, null);

        assertThat(events).extracting(AuditEventDto::action)
            .containsExactly("APPROVE_LEAVE_REQUEST", "SUBMIT_LEAVE_REQUEST");
        assertThat(events).extracting(AuditEventDto::actorEmployeeCode)
            .containsExactly("HR-001", "SLS-001");
    }

    @Test
    void refusesTheApplicationEventEndpointForTheSameCallers() {
        // Fourth entry point, same gate. hr.app_event carries exception messages and job failures,
        // so an ungated read here would hand an ordinary employee the server's error output.
        assertThatThrownBy(() -> service.appEvents(principal(hr, "hr"), DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.appEvents(principal(ceo, "ceo"), DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.appEvents(principal(plainEmployee, "admin"), DAY, DAY, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void roundTripsApplicationEventsAndJobRunsThroughRealPostgres() {
        // Proves the INSERT is actually accepted: `kind` and `level` carry CHECK constraints and
        // `message` is NOT NULL, none of which a mocked repository would enforce.
        repository.insertAppEvents(List.of(
            new AppEvent(at(9, 40), AppEvent.KIND_LOG, "ERROR", "th.co.glr.hr.Example",
                "upload failed", "java.io.IOException", "disk full",
                "th.co.glr.hr.Example.save(Example.java:12)", "corr-1", "http-1", null),
            new AppEvent(at(9, 45), AppEvent.KIND_JOB, "INFO", "BotFxFetchService.fetch",
                "job completed", null, null, null, null, "scheduler-1", 1234)));

        List<AppEventDto> all = service.appEvents(principal(admin, "sales"), DAY, DAY, null, null);
        assertThat(all).extracting(AppEventDto::kind)
            .containsExactly(AppEvent.KIND_JOB, AppEvent.KIND_LOG);   // newest first

        List<AppEventDto> jobsOnly = service.appEvents(principal(admin, "sales"), DAY, DAY, "JOB", null);
        assertThat(jobsOnly).hasSize(1);
        assertThat(jobsOnly.get(0).logger()).isEqualTo("BotFxFetchService.fetch");
        assertThat(jobsOnly.get(0).durationMs()).isEqualTo(1234);

        AppEventDto logRow = all.stream()
            .filter(row -> AppEvent.KIND_LOG.equals(row.kind())).findFirst().orElseThrow();
        assertThat(logRow.exceptionType()).isEqualTo("java.io.IOException");
        assertThat(logRow.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void refusesACallerClaimingAdminOnTheSessionWhenTheDatabaseSaysOtherwise() {
        // The principal is session state and a stale or forged one must not be believed. The role
        // string is attacker-controlled in the sense that matters here: it is whatever the session
        // was built with. Only hr.employee.is_admin decides.
        assertForbidden(principal(hr, "admin"));
        assertForbidden(principal(plainEmployee, "admin"));
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
    void refusesAnAdminWhoHasBeenDeactivated() {
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id",
            Map.of("id", admin));

        assertForbidden(principal(admin, "sales"));
    }

    @Test
    void refusesAPrincipalWhoseEmployeeRowNoLongerExists() {
        // EXISTS, not queryForObject: a missing row must deny, not 500.
        assertForbidden(principal(9_999_999L, "admin"));
    }

    @Test
    void letsTheFlaggedAdminReadEveryEmployeesActivity() {
        // The permitted case, stated last: the whole point is cross-employee visibility, so this
        // asserts the admin sees rows belonging to people other than themselves.
        List<ActivityLogEntryDto> rows = service.list(principal(admin, "sales"), DAY, DAY, null, null);

        assertThat(rows).extracting(ActivityLogEntryDto::employeeId)
            .contains(hr, ceo, sales, plainEmployee, admin);
        assertThat(rows).extracting(ActivityLogEntryDto::path)
            .contains("/api/leave-requests/7/approve", "/api/payroll/periods");
        // Newest first — the ORDER BY is contract, because it decides which rows a LIMIT keeps.
        assertThat(rows).isSortedAccordingTo((a, b) -> b.at().compareTo(a.at()));
    }

    @Test
    void narrowsToOneEmployeeWhenAsked() {
        List<ActivityLogEntryDto> rows = service.list(principal(admin, "sales"), DAY, DAY, hr, null);

        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(ActivityLogEntryDto::employeeId).containsOnly(hr);
    }

    @Test
    void summarizesPerEmployeeWithinTheBangkokDay() {
        insertActivity(hr, "GET", "/api/employees", 200, at(9, 30));

        List<ActivityLogSummaryDto> summary = service.summarize(principal(admin, "sales"), DAY, DAY);

        ActivityLogSummaryDto hrRow = summary.stream()
            .filter(row -> row.employeeId() == hr).findFirst().orElseThrow();
        assertThat(hrRow.requestCount()).isEqualTo(2);
        assertThat(hrRow.employeeCode()).isEqualTo("HR-001");
    }

    @Test
    void excludesActivityFromTheAdjacentBangkokDay() {
        // The window trap: 2026-08-31 23:30 Bangkok is 2026-08-31 16:30Z, and 2026-09-01 00:30
        // Bangkok is 2026-08-30 17:30Z. A UTC-based window would put one of these on the wrong day.
        insertActivity(sales, "GET", "/api/late", 200,
            DAY.atTime(23, 30).atZone(BANGKOK).toOffsetDateTime());
        insertActivity(sales, "GET", "/api/tomorrow", 200,
            DAY.plusDays(1).atTime(0, 30).atZone(BANGKOK).toOffsetDateTime());

        List<ActivityLogEntryDto> rows = service.list(principal(admin, "sales"), DAY, DAY, sales, null);

        assertThat(rows).extracting(ActivityLogEntryDto::path).contains("/api/late");
        assertThat(rows).extracting(ActivityLogEntryDto::path).doesNotContain("/api/tomorrow");
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
