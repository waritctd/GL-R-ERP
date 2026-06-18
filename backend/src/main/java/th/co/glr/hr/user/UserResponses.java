package th.co.glr.hr.user;

import java.util.List;

public final class UserResponses {
    private UserResponses() {
    }

    public record UsersResponse(List<UserDto> users) {
    }

    public record UserResponse(UserDto user) {
    }
}
