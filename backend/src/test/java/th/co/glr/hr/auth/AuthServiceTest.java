package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.peopleinsights.PeopleInsightsService;

class AuthServiceTest {
    private final EmployeeAuthRepository employees = mock(EmployeeAuthRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuditService audit = mock(AuditService.class);
    private final PeopleInsightsService insights = mock(PeopleInsightsService.class);
    private final AuthService service = new AuthService(employees, encoder, audit, new ViewAsService(employees, audit, ""), insights);

    @Test
    void rejectsTheEmployeeCodeAsAPassword() {
        // Regression guard for GHSA-2fm4-74wf-99rh: the employee code must no longer authenticate.
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, encoder.encode("Str0ngPass!"), true)));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "GLR-42", null),
            new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void rejectsLoginWithWrongPassword() {
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, encoder.encode("Str0ngPass!"), false)));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "wrong-password", null),
            httpRequest))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(httpRequest.getSession(false)).isNull();
    }

    @Test
    void rejectsLoginForInactiveEmployee() {
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, null, encoder.encode("Str0ngPass!"), false, false)));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "Str0ngPass!", null),
            httpRequest))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(httpRequest.getSession(false)).isNull();
    }

    @Test
    void authenticatesWithCorrectPasswordAndDerivesHrRole() {
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, encoder.encode("Str0ngPass!"), false)));

        AuthResponse response = service.login(
            new LoginRequest("hr@glr.co.th", "Str0ngPass!", null), new MockHttpServletRequest());

        assertThat(response.user().role()).isEqualTo("hr");
        assertThat(response.user().employeeId()).isEqualTo(42L);
        assertThat(response.user().mustChangePassword()).isFalse();
    }

    @Test
    void changePasswordStoresNewHashAndClearsForcedChange() {
        EmployeeLoginRecord record = employee(17L, encoder.encode("GLR-42"), true);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession(true).setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true, null, false));
        when(employees.findByEmployeeId(42L)).thenReturn(Optional.of(record));

        AuthResponse response = service.changePassword(
            new ChangePasswordRequest("GLR-42", "Br4ndNewPass!"), httpRequest.getSession());

        assertThat(response.user().mustChangePassword()).isFalse();
        verify(employees).updatePassword(eq(42L), org.mockito.ArgumentMatchers.argThat(
            hash -> encoder.matches("Br4ndNewPass!", hash)));
    }

    private EmployeeLoginRecord employee(long divisionId) {
        return employee(divisionId, (String) null);
    }

    private EmployeeLoginRecord employee(long divisionId, String positionName) {
        // Role-derivation tests log in with "GLR-42"; give them a matching hash so Wave 1's BCrypt auth lets the login
        // through to the role check.
        return employee(divisionId, positionName, encoder.encode("GLR-42"), false);
    }

    private EmployeeLoginRecord employee(long divisionId, String passwordHash, boolean mustChangePassword) {
        return employee(divisionId, null, passwordHash, mustChangePassword);
    }

    private EmployeeLoginRecord employee(long divisionId, String positionName, String passwordHash, boolean mustChangePassword) {
        return employee(divisionId, positionName, passwordHash, mustChangePassword, true);
    }

    private EmployeeLoginRecord employee(long divisionId, String positionName, String passwordHash, boolean mustChangePassword, boolean active) {
        return new EmployeeLoginRecord(
            42L,
            "GLR-42",
            "hr@glr.co.th",
            "HR",
            active,
            divisionId,
            divisionCodeFor(divisionId),
            null,
            positionName,
            LocalDate.now(),
            passwordHash,
            mustChangePassword,
            false
        );
    }

    // Maps the legacy division ids used by these tests to the ฝ่าย source_code that the data-driven
    // DivisionAccessPolicy now keys on (role no longer depends on division_id).
    private static String divisionCodeFor(long divisionId) {
        return switch ((int) divisionId) {
            case 17 -> "HR";   // HR-บุคคล
            case 9 -> "SA";    // SA-ฝ่ายขาย
            case 16 -> "MN";   // MN-บริหาร (administration; not an elevated role on its own)
            default -> null;
        };
    }
}
