package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import th.co.glr.hr.common.ApiException;

class AuthServiceTest {
    private final EmployeeAuthRepository employees = mock(EmployeeAuthRepository.class);
    private final AuthService service = new AuthService(employees);

    @Test
    void usesGenericCredentialErrorForWrongEmployeeCodePassword() {
        when(employees.findByEmail("hr@glr.co.th")).thenReturn(Optional.of(employee(17L)));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "999", null),
            new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                org.assertj.core.api.Assertions.assertThat(exception.getMessage()).isEqualTo("Invalid email or password");
            });
    }

    @Test
    void derivesHrRoleFromHrMdMnDivision() {
        when(employees.findByEmail("hr@glr.co.th")).thenReturn(Optional.of(employee(17L)));

        AuthResponse response = service.login(new LoginRequest("hr@glr.co.th", "GLR-42", null), new MockHttpServletRequest());

        assertThat(response.user().role()).isEqualTo("hr");
        assertThat(response.user().employeeId()).isEqualTo(42L);
    }

    @Test
    void derivesEmployeeRoleFromOtherDivisions() {
        when(employees.findByEmail("employee@glr.co.th")).thenReturn(Optional.of(employee(3L)));

        AuthResponse response = service.login(new LoginRequest("employee@glr.co.th", "GLR-42", null), new MockHttpServletRequest());

        assertThat(response.user().role()).isEqualTo("employee");
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

        assertThat(response.user().role()).isEqualTo("hr");
    }

    @Test
    void rejectsRoleOnlyLogin() {
        assertThatThrownBy(() -> service.login(
            new LoginRequest(null, null, "hr"),
            new MockHttpServletRequest()
        ))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private EmployeeLoginRecord employee(long divisionId) {
        return employee(divisionId, null);
    }

    private EmployeeLoginRecord employee(long divisionId, String positionName) {
        return new EmployeeLoginRecord(
            42L,
            "GLR-42",
            "hr@glr.co.th",
            "HR",
            true,
            divisionId,
            null,
            null,
            positionName,
            LocalDate.now()
        );
    }
}
