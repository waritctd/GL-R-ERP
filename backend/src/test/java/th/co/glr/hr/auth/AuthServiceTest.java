package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.common.ApiException;

class AuthServiceTest {
    private final EmployeeAuthRepository employees = mock(EmployeeAuthRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthService service = new AuthService(employees, encoder);

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
    void rejectsLoginWhenNoPasswordHashIsSet() {
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, null, true)));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "anything", null),
            new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
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
    void surfacesMustChangePasswordFlagFromTemporaryHash() {
        when(employees.findByEmail("employee@glr.co.th"))
            .thenReturn(Optional.of(employee(3L, encoder.encode("GLR-42"), true)));

        AuthResponse response = service.login(
            new LoginRequest("employee@glr.co.th", "GLR-42", null), new MockHttpServletRequest());

        assertThat(response.user().role()).isEqualTo("employee");
        assertThat(response.user().mustChangePassword()).isTrue();
    }

    @Test
    void rejectsRoleOnlyLogin() {
        assertThatThrownBy(() -> service.login(
            new LoginRequest(null, null, "hr"),
            new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void changePasswordStoresNewHashAndClearsForcedChange() {
        EmployeeLoginRecord record = employee(17L, encoder.encode("GLR-42"), true);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession(true).setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true));
        when(employees.findByEmployeeId(42L)).thenReturn(Optional.of(record));

        AuthResponse response = service.changePassword(
            new ChangePasswordRequest("GLR-42", "Br4ndNewPass!"), httpRequest.getSession());

        assertThat(response.user().mustChangePassword()).isFalse();
        verify(employees).updatePassword(eq(42L), org.mockito.ArgumentMatchers.argThat(
            hash -> encoder.matches("Br4ndNewPass!", hash)));
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        EmployeeLoginRecord record = employee(17L, encoder.encode("GLR-42"), true);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession(true).setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true));
        when(employees.findByEmployeeId(42L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.changePassword(
            new ChangePasswordRequest("wrong-current", "Br4ndNewPass!"), httpRequest.getSession()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(employees, never()).updatePassword(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void changePasswordRejectsReusingTheEmployeeCode() {
        EmployeeLoginRecord record = employee(17L, encoder.encode("GLR-42"), true);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession(true).setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true));
        when(employees.findByEmployeeId(42L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.changePassword(
            new ChangePasswordRequest("GLR-42", "GLR-42"), httpRequest.getSession()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private EmployeeLoginRecord employee(long divisionId, String passwordHash, boolean mustChangePassword) {
        return new EmployeeLoginRecord(
            42L,
            "GLR-42",
            "hr@glr.co.th",
            "HR",
            true,
            divisionId,
            null,
            null,
            LocalDate.now(),
            passwordHash,
            mustChangePassword
        );
    }
}
