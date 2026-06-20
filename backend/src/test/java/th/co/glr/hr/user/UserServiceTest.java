package th.co.glr.hr.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.AuthService;
import th.co.glr.hr.common.ApiException;

class UserServiceTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final UserService service = new UserService(users, authService);

    @Test
    void rejectsUnknownRolesBeforePersistence() {
        CreateUserRequest request = new CreateUserRequest(10L, "owner@glr.co.th", "owner", "temporary1", null);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
