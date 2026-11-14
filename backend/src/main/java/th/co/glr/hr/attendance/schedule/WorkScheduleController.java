package th.co.glr.hr.attendance.schedule;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/** Read-only catalogue of hr.work_schedule rows, so a future admin UI can offer HR a choice of schedule when
 * creating a hr.work_schedule_assignment — see WorkScheduleAssignmentController. */
@RestController
@RequestMapping("/api/work-schedules")
public class WorkScheduleController {

    private final WorkScheduleAssignmentAdminService service;
    private final SessionContext sessions;

    public WorkScheduleController(WorkScheduleAssignmentAdminService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    WorkSchedulesResponse list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return new WorkSchedulesResponse(service.listSchedules());
    }
}
