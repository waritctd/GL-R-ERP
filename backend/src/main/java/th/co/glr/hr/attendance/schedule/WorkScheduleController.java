package th.co.glr.hr.attendance.schedule;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * Read-only catalogue of {@code hr.work_schedule} rows, so a future admin UI can offer HR a choice
 * of schedule when creating a {@code hr.work_schedule_assignment} — see {@link
 * WorkScheduleAssignmentController}. Creating new schedules is out of scope for this branch;
 * assigning the existing seeded ones (OFFICE_5D / OPS_6D / WEEKEND_DUTY / SALES_5D) is the need.
 *
 * <p>Gated {@code "hr", "ceo"} like the rest of this admin surface, even though it is read-only:
 * this is an administrative configuration listing, not general employee-facing data, and there is
 * no existing reader of it outside HR/CEO's own assignment tooling.
 */
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
