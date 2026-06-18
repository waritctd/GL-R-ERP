package th.co.glr.hr.user;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.user.UserResponses.UserResponse;
import th.co.glr.hr.user.UserResponses.UsersResponse;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final SessionContext sessions;

    public UserController(UserService userService, SessionContext sessions) {
        this.userService = userService;
        this.sessions = sessions;
    }

    @GetMapping
    UsersResponse list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "admin");
        return new UsersResponse(userService.list());
    }

    @PostMapping
    UserResponse create(@Valid @RequestBody CreateUserRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "admin");
        return new UserResponse(userService.create(request));
    }

    @PatchMapping("/{id}")
    UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "admin");
        return new UserResponse(userService.update(id, request));
    }
}
