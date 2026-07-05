package th.co.glr.hr.attendance;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {
    static final String AGENT_TOKEN_HEADER = "X-GLR-Agent-Token";

    private final AttendanceService attendanceService;
    private final SessionContext sessions;

    public AttendanceController(AttendanceService attendanceService, SessionContext sessions) {
        this.attendanceService = attendanceService;
        this.sessions = sessions;
    }

    @PostMapping("/punch")
    ResponseEntity<AttendancePunchResponse> receivePunch(
            @Valid @RequestBody AttendancePunchRequest request,
            @RequestHeader(value = AGENT_TOKEN_HEADER, required = false) String agentToken) {
        return ResponseEntity.ok(attendanceService.receivePunch(request, agentToken));
    }

    @PostMapping("/devices/{deviceCode}/agent-token")
    ResponseEntity<RotateAgentTokenResponse> rotateAgentToken(
            @PathVariable String deviceCode,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr");
        return ResponseEntity.ok(attendanceService.rotateDeviceToken(deviceCode));
    }

    @GetMapping("/devices")
    AttendanceDevicesResponse listDevices(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        // Only HR and C-level manage/attribute imports, so the scanner list is scoped to them too.
        sessions.requireAnyRole(user, "hr", "ceo");
        return new AttendanceDevicesResponse(attendanceService.listDevices());
    }

    @PostMapping("/imports/dat")
    ResponseEntity<AttendanceImportResponse> importDatFile(
            @Valid @RequestBody AttendanceDatImportRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return ResponseEntity.ok(attendanceService.importDatFile(request, user));
    }

    @PostMapping("/daily/recalculate")
    ResponseEntity<AttendanceDailyRecalcResponse> recalculateDaily(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return ResponseEntity.ok(new AttendanceDailyRecalcResponse(attendanceService.recalculateDaily(from, to)));
    }

    @PostMapping("/cards/backfill")
    AttendanceCardBackfillResponse backfillCards(
            @Valid @RequestBody AttendanceCardBackfillRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return attendanceService.backfillCardNumbers(request);
    }

    @GetMapping("/punches")
    AttendancePunchesResponse listPunches(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            java.time.LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            java.time.LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new AttendancePunchesResponse(attendanceService.listPunches(user, fromDate, toDate, employeeId, limit));
    }
}
