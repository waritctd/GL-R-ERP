package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.user.AppUserRecord;
import th.co.glr.hr.user.AppUserRepository;

class AuthServiceTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final Environment environment = mock(Environment.class);
    private final AppProperties properties = new AppProperties();
    private final AuthService service;

    AuthServiceTest() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        service = new AuthService(users, properties, environment);
    }

    @Test
    void usesGenericCredentialErrorForWrongPassword() {
        AppUserRecord user = userWithHash(service.hashPassword("correct-password"));
        when(users.findByEmail("hr@glr.co.th")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "wrong-password", null),
            new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                org.assertj.core.api.Assertions.assertThat(exception.getMessage()).isEqualTo("Invalid email or password");
            });
    }

    @Test
    void rejectsNoopPasswordHashesOutsideDemoProfile() {
        when(users.findByEmail("hr@glr.co.th")).thenReturn(Optional.of(userWithHash("{noop}demo1234")));

        assertThatThrownBy(() -> service.login(
            new LoginRequest("hr@glr.co.th", "demo1234", null),
            new MockHttpServletRequest()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private AppUserRecord userWithHash(String passwordHash) {
        return new AppUserRecord(
            1L,
            "hr@glr.co.th",
            passwordHash,
            "HR",
            10L,
            true,
            LocalDate.now(),
            List.of("hr")
        );
    }
}
