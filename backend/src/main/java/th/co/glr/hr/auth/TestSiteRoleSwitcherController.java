package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.common.ApiException;

/** Test site only: absent unless the flag is on, and refuses to boot on the prod profile. */
@RestController
@RequestMapping("/api/test-site")
@ConditionalOnProperty(name = "app.test-site.role-switcher.enabled", havingValue = "true")
public class TestSiteRoleSwitcherController {
    record SwitchRequest(String email) {}

    private final TestSiteRoleSwitcher switcher;
    private final AuthService authService;
    private final SessionContext sessions;

    public TestSiteRoleSwitcherController(TestSiteRoleSwitcher switcher, AuthService authService,
                                          SessionContext sessions, Environment environment) {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("app.test-site.role-switcher.enabled must never be set on the prod profile");
        }
        this.switcher = switcher;
        this.authService = authService;
        this.sessions = sessions;
    }

    @GetMapping("/accounts")
    Map<String, List<TestSiteRoleSwitcher.Account>> accounts(HttpSession session) {
        return Map.of("accounts", switcher.accounts(sessions.requireUser(session)));
    }

    @PostMapping("/switch")
    AuthResponse switchTo(@RequestBody SwitchRequest body, HttpServletRequest request, HttpSession session) {
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุบัญชี");
        }
        switcher.switchTo(body.email(), sessions.requireUser(session), request);
        return authService.me(request.getSession(false));
    }
}
