package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.AuthResponse;
import th.co.glr.hr.auth.AuthService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.EmployeeLoginRecord;
import th.co.glr.hr.auth.LoginRequest;
import th.co.glr.hr.auth.TemporaryPasswordGenerator;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.profile.ProfileRequestRepository;

/**
 * Proves the HR reset flow end-to-end at the service layer: a fresh random temp password is issued,
 * BCrypt-hashed, forces a change on next login, authenticates via {@link AuthService}, and the old
 * low-entropy {@code employee_code} no longer authenticates.
 */
class EmployeeServiceResetPasswordTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final EmployeeAuthRepository employeeAuth = mock(EmployeeAuthRepository.class);
    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final EmployeeService service = new EmployeeService(
        employees, profileRequests, auditService, employeeAuth, generator, encoder);

    private final UserPrincipal hrActor =
        new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);

    @Test
    void issuesARandomTempPasswordThatAuthenticatesAndForcesChangeWhileTheEmployeeCodeDoesNot() {
        when(employees.exists(42L)).thenReturn(true);

        PasswordResetResult result = service.resetPassword(42L, hrActor);

        // The returned plaintext is high-entropy and not the employee code.
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(result.temporaryPassword()).isNotEqualTo("GLR-42");

        // Capture the hash persisted via setTemporaryPassword and feed it into a real login.
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(employeeAuth).setTemporaryPassword(eq(42L), hash.capture());
        assertThat(encoder.matches(result.temporaryPassword(), hash.getValue())).isTrue();
        assertThat(hash.getValue()).isNotEqualTo(result.temporaryPassword()); // stored hashed, not plaintext

        EmployeeAuthRepository loginRepo = mock(EmployeeAuthRepository.class);
        EmployeeLoginRecord record = new EmployeeLoginRecord(
            42L, "GLR-42", "user@glr.co.th", "User", true, null, null, null, null,
            LocalDate.now(), hash.getValue(), /* mustChangePassword */ true);
        when(loginRepo.findByEmail("user@glr.co.th")).thenReturn(Optional.of(record));
        AuthService authService = new AuthService(loginRepo, encoder);

        // The temp password logs in and surfaces mustChangePassword=true.
        AuthResponse ok = authService.login(
            new LoginRequest("user@glr.co.th", result.temporaryPassword(), null), new MockHttpServletRequest());
        assertThat(ok.user().mustChangePassword()).isTrue();

        // The employee_code does NOT authenticate.
        assertThatThrownBy(() -> authService.login(
            new LoginRequest("user@glr.co.th", "GLR-42", null), new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void auditsTheResetWithActorAndTargetButNeverThePlaintext() {
        when(employees.exists(42L)).thenReturn(true);

        PasswordResetResult result = service.resetPassword(42L, hrActor);

        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(eq(hrActor), eq("RESET_EMPLOYEE_PASSWORD"), eq("employee"),
            eq(42L), before.capture(), after.capture());
        // No password material in the audit payload.
        assertThat(before.getValue()).isNull();
        assertThat(after.getValue()).isNull();
        assertThat(result.temporaryPassword()).isNotBlank();
    }

    @Test
    void rejectsResetForAnUnknownEmployee() {
        when(employees.exists(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword(99L, hrActor))
            .isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
