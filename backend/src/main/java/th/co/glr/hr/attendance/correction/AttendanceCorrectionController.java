package th.co.glr.hr.attendance.correction;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/** Alongside {@code AttendanceController}'s {@code /api/attendance/*} surface. */
@RestController
@RequestMapping("/api/attendance/corrections")
public class AttendanceCorrectionController {
    private final AttendanceCorrectionService service;
    private final SessionContext sessions;

    public AttendanceCorrectionController(AttendanceCorrectionService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, List<AttendanceCorrectionRequestDto>> list(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "status", required = false) String status,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("requests", service.list(user, employeeId, status));
    }

    @PostMapping
    Map<String, AttendanceCorrectionRequestDto> submit(
            @Valid @RequestBody SubmitAttendanceCorrectionRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("request", service.submit(request, user));
    }

    @PostMapping("/{id}/approve")
    Map<String, AttendanceCorrectionRequestDto> approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewAttendanceCorrectionRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("request", service.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    Map<String, AttendanceCorrectionRequestDto> reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewAttendanceCorrectionRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("request", service.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    Map<String, AttendanceCorrectionRequestDto> cancel(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("request", service.cancel(id, user));
    }
}
