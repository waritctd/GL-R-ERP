package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only "view as employee". The ONLY callers of {@link SessionContext#requireRealUser}. */
@RestController
@RequestMapping("/api/view-as")
public class ViewAsController {
    record StartRequest(Long employeeId) {}

    private final ViewAsService service;
    private final AuthService authService;
    private final SessionContext sessions;

    public ViewAsController(ViewAsService service, AuthService authService, SessionContext sessions) {
        this.service = service;
        this.authService = authService;
        this.sessions = sessions;
    }

    @GetMapping("/candidates")
    Map<String, List<ViewAsService.Candidate>> candidates(HttpSession session) {
        return Map.of("candidates", service.candidates(sessions.requireRealUser(session)));
    }

    @PostMapping
    AuthResponse start(@RequestBody StartRequest request, HttpSession session) {
        if (request == null || request.employeeId() == null) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "ต้องระบุพนักงาน");
        }
        service.start(request.employeeId(), sessions.requireRealUser(session), session);
        return authService.me(session);
    }

    @DeleteMapping
    AuthResponse stop(HttpSession session) {
        service.stop(sessions.requireRealUser(session), session);
        return authService.me(session);
    }
}
