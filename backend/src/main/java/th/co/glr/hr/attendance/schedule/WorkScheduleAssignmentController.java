package th.co.glr.hr.attendance.schedule;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/** HR/CEO write path over hr.work_schedule_assignment: assign a schedule to an employee/department/division
 * scope, end an assignment, list what is currently configured. */
@RestController
@RequestMapping("/api/work-schedule-assignments")
public class WorkScheduleAssignmentController {

    private final WorkScheduleAssignmentAdminService service;
    private final SessionContext sessions;

    public WorkScheduleAssignmentController(WorkScheduleAssignmentAdminService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    WorkScheduleAssignmentsResponse list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return new WorkScheduleAssignmentsResponse(service.listAssignments());
    }

    @PostMapping
    WorkScheduleAssignmentDto create(@RequestBody WorkScheduleAssignmentCreateRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return service.create(user, request);
    }

    @PostMapping("/{assignmentId}/end")
    WorkScheduleAssignmentDto end(
            @PathVariable("assignmentId") int assignmentId,
            @RequestBody(required = false) WorkScheduleAssignmentEndRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return service.end(user, assignmentId, request == null ? null : request.effectiveTo());
    }
}
