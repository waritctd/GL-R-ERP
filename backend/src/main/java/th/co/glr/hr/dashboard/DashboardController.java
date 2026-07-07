package th.co.glr.hr.dashboard;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;
    private final SessionContext sessions;

    public DashboardController(DashboardService dashboardService, SessionContext sessions) {
        this.dashboardService = dashboardService;
        this.sessions = sessions;
    }

    @GetMapping("/summary")
    DashboardSummaryDto summary(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return dashboardService.summary(user);
    }
}
