package th.co.glr.hr.salesanalytics;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.salesanalytics.SalesAnalyticsService.SnapshotDto;

/** Sales-insights snapshot: ceo + sales_manager read, ceo uploads. Roles are enforced in the service. */
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
        return service.latest(sessions.requireUser(session));
    }

    /** Raw body so the service can validate the document's shape and size itself. */
    @PutMapping
    Map<String, Boolean> upload(@RequestBody String body, HttpSession session) {
        service.upload(sessions.requireUser(session), body);
        return Map.of("ok", true);
    }
}
