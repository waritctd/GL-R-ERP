package th.co.glr.hr.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms the widened profile-change-request gate against the real service and the real SQL.
 *
 * <p>The gate used to be a role list ({@code sessions.requireAnyRole(user, "employee")} at the
 * controller); it is now an identity check inside {@link ProfileRequestService#create} —
 * {@code employeeId != null}. Unit tests on the controller and service prove the decision was
 * made correctly; only this proves the decision survives into the {@code WHERE} clause of
 * {@link ProfileRequestRepository#findByEmployee} and actually filters rows, exactly as {@code
 * AttendanceScopeIntegrationTest} does for attendance (CLAUDE.md, "Permission changes must ship
 * evidence — not a claim").
 *
 * <p>Every refusal case here asks the question the wrong way round — can this caller reach or
 * write data they should not — rather than confirming they can reach their own.
 */
class ProfileRequestScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    // Mocked deliberately: neither collaborator participates in the authorization decision under
    // test. EmployeeRepository only shapes the DTO's nested `employee` summary; AuditService is
    // only called from #update, which this class does not exercise. ProfileRequestRepository is
    // the one that must be real, since it is what carries the decision into SQL.
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private ProfileRequestService service;
    private ProfileRequestRepository profileRequests;

    private long division;
    private long salesEmployeeA;
    private long salesEmployeeB;

    @BeforeEach
    void wireRealCollaborators() {
        when(employees.findEmployeeSummariesByIds(any())).thenReturn(Map.of());

        profileRequests = new ProfileRequestRepository(jdbc);
        // @Transactional on ProfileRequestService#create is inert without a real AOP proxy (no
        // Spring context here) -- see AbstractPostgresIntegrationTest#transactional's Javadoc.
        // Wrap it so the annotation is actually exercised, matching AttendanceScopeIntegrationTest.
        service = transactional(new ProfileRequestService(profileRequests, employees, auditService));

        division = insertDivision("SLS", "ฝ่ายขาย");
        salesEmployeeA = insertEmployee("S001", division);
        salesEmployeeB = insertEmployee("S002", division);
    }

    // --- create: identity gate ----------------------------------------------

    @Test
    void callerWithNoEmployeeIdCannotCreateAndNoRowIsWritten() {
        UserPrincipal noEmployeeId = sessionUser(900L, "sales", null);

        assertThatThrownBy(() -> service.create(phoneRequest(), noEmployeeId))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(rowCount()).isZero();
    }

    @Test
    void salesCallerCreatesLandsOnlyOnTheirOwnEmployeeId() {
        // `id` (the app_user/session id) is deliberately different from `employeeId` here, so a
        // mutation that swapped one for the other is caught rather than coincidentally passing.
        UserPrincipal sales = sessionUser(500L, "sales", salesEmployeeA);

        service.create(phoneRequest(), sales);

        List<ProfileRequestRecord> rows = profileRequests.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).employeeId()).isEqualTo(salesEmployeeA);
    }

    @Test
    void salesCallerWithEmployeeIdCanCreate() {
        // Positive control, kept minimal: this is the point of the change.
        UserPrincipal sales = sessionUser(501L, "sales", salesEmployeeB);

        ProfileRequestDto created = service.create(phoneRequest(), sales);

        assertThat(created.employeeId()).isEqualTo(salesEmployeeB);
        assertThat(created.status()).isEqualTo("pending");
    }

    // --- list: per-employee scope --------------------------------------------

    @Test
    void salesCallerListReturnsOnlyTheirOwnRequests() {
        UserPrincipal salesA = sessionUser(500L, "sales", salesEmployeeA);
        UserPrincipal salesB = sessionUser(501L, "sales", salesEmployeeB);
        service.create(phoneRequest(), salesA);
        service.create(emailRequest(), salesB);

        List<ProfileRequestDto> ownRequests = service.list(salesA);

        assertThat(ownRequests).extracting(ProfileRequestDto::employeeId).containsOnly(salesEmployeeA);
    }

    @Test
    void hrCallerListStillReturnsEveryonesRequests() {
        UserPrincipal salesA = sessionUser(500L, "sales", salesEmployeeA);
        UserPrincipal salesB = sessionUser(501L, "sales", salesEmployeeB);
        UserPrincipal hr = sessionUser(1L, "hr", null);
        service.create(phoneRequest(), salesA);
        service.create(emailRequest(), salesB);

        List<ProfileRequestDto> everyRequest = service.list(hr);

        assertThat(everyRequest).extracting(ProfileRequestDto::employeeId)
            .contains(salesEmployeeA, salesEmployeeB);
    }

    // --- helpers --------------------------------------------------------------

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*)::int FROM hr.profile_change_request", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private CreateProfileRequestRequest phoneRequest() {
        return new CreateProfileRequestRequest("phone", "เบอร์โทรศัพท์", "02-000-0000", "089-999-9999");
    }

    private CreateProfileRequestRequest emailRequest() {
        return new CreateProfileRequestRequest("email", "อีเมล", "old@glr.co.th", "new@glr.co.th");
    }

    private UserPrincipal sessionUser(long id, String role, Long employeeId) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, employeeId, true,
            LocalDate.now(), false, division, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
