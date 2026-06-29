package th.co.glr.hr.leave;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.leave.LeaveResponses.LeaveBalancesResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveDetailResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveEmployeeOptionsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveListResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveTypesResponse;

@RestController
@RequestMapping("/api/leave")
public class LeaveController {
    private final LeaveService leaveService;
    private final SessionContext sessions;

    public LeaveController(LeaveService leaveService, SessionContext sessions) {
        this.leaveService = leaveService;
        this.sessions = sessions;
    }

    @GetMapping
    LeaveListResponse list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "status", required = false) String status,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveListResponse(leaveService.list(user, fromDate, toDate, employeeId, status));
    }

    @PostMapping
    LeaveDetailResponse submit(@Valid @RequestBody SubmitLeaveRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.submit(request, user));
    }

    @GetMapping("/employees")
    LeaveEmployeeOptionsResponse employeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveEmployeeOptionsResponse(leaveService.employeeOptions(user));
    }

    @GetMapping("/types")
    LeaveTypesResponse leaveTypes(HttpSession session) {
        sessions.requireUser(session);
        return new LeaveTypesResponse(leaveService.leaveTypes());
    }

    @GetMapping("/balances")
    LeaveBalancesResponse balances(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "year", required = false) Integer year,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveBalancesResponse(leaveService.balances(user, employeeId, year));
    }

    @PostMapping("/{id}/approve")
    LeaveDetailResponse approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    LeaveDetailResponse reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    LeaveDetailResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.cancel(id, request, user));
    }
}
