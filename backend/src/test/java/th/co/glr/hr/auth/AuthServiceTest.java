package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;

class AuthServiceTest {
    private final EmployeeAuthRepository employees = mock(EmployeeAuthRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuditService audit = mock(AuditService.class);
    private final AuthService service = new AuthService(employees, encoder, audit);

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
    void rejectsLoginForUnknownEmail() {
        when(employees.findByEmail("missing@glr.co.th")).thenReturn(Optional.empty());
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        assertThatThrownBy(() -> service.login(
            new LoginRequest("missing@glr.co.th", "Str0ngPass!", null),
            httpRequest))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(httpRequest.getSession(false)).isNull();
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
    void surfacesMustChangePasswordFlagFromTemporaryHash() {
        when(employees.findByEmail("employee@glr.co.th"))
            .thenReturn(Optional.of(employee(3L, encoder.encode("GLR-42"), true)));

        AuthResponse response = service.login(
            new LoginRequest("employee@glr.co.th", "GLR-42", null), new MockHttpServletRequest());

        assertThat(response.user().role()).isEqualTo("employee");
        assertThat(response.user().mustChangePassword()).isTrue();
    }

    @Test
    void derivesSalesManagerRoleFromAssistantSalesManagerPosition() {
        when(employees.findByEmail("manager@glr.co.th")).thenReturn(Optional.of(employee(9L, "ผู้ช่วยผู้จัดการฝ่ายขาย")));

        AuthResponse response = service.login(new LoginRequest("manager@glr.co.th", "GLR-42", null), new MockHttpServletRequest());

        assertThat(response.user().role()).isEqualTo("sales_manager");
    }

    @Test
    void doesNotDeriveSalesManagerRoleFromManagementDivisionAlone() {
        when(employees.findByEmail("mn@glr.co.th")).thenReturn(Optional.of(employee(16L)));

        AuthResponse response = service.login(new LoginRequest("mn@glr.co.th", "GLR-42", null), new MockHttpServletRequest());

        // MN-บริหาร with no manager/executive title is a plain employee, not sales_manager.
        assertThat(response.user().role()).isEqualTo("employee");
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
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true, null, false));
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
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true, null, false));
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
            new UserPrincipal(42L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), true, null, false));
        when(employees.findByEmployeeId(42L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.changePassword(
            new ChangePasswordRequest("GLR-42", "GLR-42"), httpRequest.getSession()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void recordsAnAuditRowOnSuccessfulLogin() {
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, encoder.encode("Str0ngPass!"), false)));

        service.login(new LoginRequest("hr@glr.co.th", "Str0ngPass!", null), new MockHttpServletRequest());

        ArgumentCaptor<UserPrincipal> actor = ArgumentCaptor.forClass(UserPrincipal.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details =
            ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(audit).record(actor.capture(), eq("LOGIN"), eq("employee"), eq(42L),
            isNull(), details.capture());
        assertThat(actor.getValue().email()).isEqualTo("hr@glr.co.th");
        assertThat(actor.getValue().id()).isEqualTo(42L);
        // The role is derived from the division at login time and the division can change later,
        // so capturing it here is a historical record, not something re-derivable from the row.
        assertThat(details.getValue()).containsEntry("role", "hr");
        assertThat(details.getValue()).containsEntry("mustChangePassword", false);
    }

    @Test
    void recordsNoAuditRowWhenTheCredentialsAreRejected() {
        // Wrong-way-round: the audit trail must not imply a login that never happened. Every
        // rejection path in login() is exercised, because each returns before the record call.
        when(employees.findByEmail("missing@glr.co.th")).thenReturn(Optional.empty());
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, encoder.encode("Str0ngPass!"), false)));
        when(employees.findByEmail("nohash@glr.co.th")).thenReturn(Optional.of(employee(17L, null, true)));
        when(employees.findByEmail("inactive@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, null, encoder.encode("Str0ngPass!"), false, false)));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("missing@glr.co.th", "Str0ngPass!", null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "wrong-password", null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(
            new LoginRequest("nohash@glr.co.th", "anything", null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(
            new LoginRequest("inactive@glr.co.th", "Str0ngPass!", null), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(
            new LoginRequest(null, null, "hr"), new MockHttpServletRequest()))
            .isInstanceOf(ApiException.class);

        verify(audit, never()).record(any(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void stillLogsInWhenTheAuditWriteFails() {
        // An audit-table problem must never become a company-wide lockout: a login mutates
        // nothing, so there is no half-written state to protect by failing the request.
        when(employees.findByEmail("hr@glr.co.th"))
            .thenReturn(Optional.of(employee(17L, encoder.encode("Str0ngPass!"), false)));
        doThrow(new RuntimeException("audit_log unavailable"))
            .when(audit).record(any(), anyString(), anyString(), anyLong(), any(), any());
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        AuthResponse response = service.login(
            new LoginRequest("hr@glr.co.th", "Str0ngPass!", null), httpRequest);

        assertThat(response.user().email()).isEqualTo("hr@glr.co.th");
        assertThat(httpRequest.getSession(false)).isNotNull();
        assertThat(httpRequest.getSession(false).getAttribute(SessionContext.SESSION_USER_KEY))
            .isInstanceOf(UserPrincipal.class);
    }

    private EmployeeLoginRecord employee(long divisionId) {
        return employee(divisionId, (String) null);
    }

    private EmployeeLoginRecord employee(long divisionId, String positionName) {
        // Role-derivation tests log in with "GLR-42"; give them a matching hash so
        // Wave 1's BCrypt auth lets the login through to the role check.
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
            mustChangePassword
        );
    }

    // Maps the legacy division ids used by these tests to the ฝ่าย source_code that the
    // data-driven DivisionAccessPolicy now keys on (role no longer depends on division_id).
    private static String divisionCodeFor(long divisionId) {
        return switch ((int) divisionId) {
            case 17 -> "HR";   // HR-บุคคล
            case 9 -> "SA";    // SA-ฝ่ายขาย
            case 16 -> "MN";   // MN-บริหาร (administration; not an elevated role on its own)
            default -> null;
        };
    }
}
