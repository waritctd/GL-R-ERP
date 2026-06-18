package th.co.glr.hr.user;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.AuthService;
import th.co.glr.hr.common.ApiException;

@Service
public class UserService {
    private final AppUserRepository users;
    private final AuthService authService;

    public UserService(AppUserRepository users, AuthService authService) {
        this.users = users;
        this.authService = authService;
    }

    public List<UserDto> list() {
        return users.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public UserDto create(CreateUserRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "User email already exists");
        }
        try {
            long id = users.create(request, authService.hashPassword(request.password()));
            return users.findById(id).map(this::toDto).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid employee or role");
        }
    }

    @Transactional
    public UserDto update(long id, UpdateUserRequest request) {
        if (users.findById(id).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        try {
            users.update(id, request);
            return users.findById(id).map(this::toDto).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid user update");
        }
    }

    private UserDto toDto(AppUserRecord user) {
        String role = user.roles().isEmpty() ? "employee" : user.roles().getFirst();
        return new UserDto(user.id(), user.email(), user.name(), role, user.employeeId(), user.active(), user.createdAt());
    }
}
