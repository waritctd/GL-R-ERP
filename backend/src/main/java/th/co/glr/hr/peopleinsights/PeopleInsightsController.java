package th.co.glr.hr.peopleinsights;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api/people-insights")
public class PeopleInsightsController {
    private final PeopleInsightsService service;
    private final SessionContext sessions;

    public PeopleInsightsController(PeopleInsightsService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    PeopleInsightsDtos.Response get(@RequestParam(required = false) Integer year,
                                    @RequestParam(required = false) String scope, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        // Checked before the service reads year/scope, so an unauthorised caller gets 403 for a well-formed request (a malformed year is a 400 at binding, before this method runs).
        if (!service.mayView(user)) throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        return service.get(user, year, scope);
    }
}
