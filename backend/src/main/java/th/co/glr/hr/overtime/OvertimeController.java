package th.co.glr.hr.overtime;

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
import th.co.glr.hr.overtime.OvertimeResponses.OvertimeDetailResponse;
import th.co.glr.hr.overtime.OvertimeResponses.OvertimeEmployeeOptionsResponse;
import th.co.glr.hr.overtime.OvertimeResponses.OvertimeListResponse;

@RestController
@RequestMapping("/api/overtime")
public class OvertimeController {
    private final OvertimeService overtimeService;
    private final SessionContext sessions;

    public OvertimeController(OvertimeService overtimeService, SessionContext sessions) {
        this.overtimeService = overtimeService;
        this.sessions = sessions;
    }

    @GetMapping
    OvertimeListResponse list(
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
        return new OvertimeListResponse(overtimeService.list(user, fromDate, toDate, employeeId, status));
    }

    @PostMapping
    OvertimeDetailResponse submit(@Valid @RequestBody SubmitOvertimeRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new OvertimeDetailResponse(overtimeService.submit(request, user));
    }

    @GetMapping("/employees")
    OvertimeEmployeeOptionsResponse employeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new OvertimeEmployeeOptionsResponse(overtimeService.employeeOptions(user));
    }

    @PostMapping("/{id}/approve")
    OvertimeDetailResponse approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewOvertimeRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new OvertimeDetailResponse(overtimeService.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    OvertimeDetailResponse reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewOvertimeRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new OvertimeDetailResponse(overtimeService.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    OvertimeDetailResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewOvertimeRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new OvertimeDetailResponse(overtimeService.cancel(id, request, user));
    }
}
