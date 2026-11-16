package th.co.glr.hr.salesanalytics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.salesanalytics.SalesAnalyticsService.SnapshotDto;

/** Sales-insights snapshot: ceo + sales_manager read, ceo uploads. Roles are checked here, before the body is touched. */
@RestController
@RequestMapping("/api/sales-analytics/snapshot")
public class SalesAnalyticsController {
    private final SalesAnalyticsService service;
    private final SessionContext sessions;

    public SalesAnalyticsController(SalesAnalyticsService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, SnapshotDto> latest(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "ceo", "sales_manager");
        return service.latest();
    }

    /** The raw stream, not a parsed body, so the service can cap how much of it is ever read. */
    @PutMapping
    Map<String, Boolean> upload(HttpServletRequest request, HttpSession session) throws IOException {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "ceo");
        service.upload(user, request.getInputStream(), request.getContentLengthLong());
        return Map.of("ok", true);
    }
}
