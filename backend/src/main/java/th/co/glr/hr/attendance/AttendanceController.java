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

    @PostMapping("/cards/backfill")
    AttendanceCardBackfillResponse backfillCards(
            @Valid @RequestBody AttendanceCardBackfillRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return attendanceService.backfillCardNumbers(request);
    }

    /**
     * The day view. No role gate here on purpose — {@code AttendanceService.resolveScope} decides
     * what the caller sees (hr/ceo: everyone, ฝ่าย manager: their division, otherwise: themselves),
     * exactly as {@code /punches} does.
     */
    @GetMapping("/daily")
    AttendanceDailyResponse listDaily(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            java.time.LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            java.time.LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new AttendanceDailyResponse(
            attendanceService.listDaily(user, fromDate, toDate, employeeId));
    }

    /** Badges that scanned but match no employee — a data-repair queue, so HR/CEO only. */
    @GetMapping("/unmapped")
    AttendanceUnmappedResponse listUnmapped(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            java.time.LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            java.time.LocalDate toDate,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return new AttendanceUnmappedResponse(attendanceService.listUnmappedBadges(fromDate, toDate));
    }

    /**
     * Employees the caller may filter by. Session-scoped rather than role-gated: everyone gets a
     * list, it is just narrower for non-HR callers.
     */
    @GetMapping("/employees")
    AttendanceEmployeesResponse listEmployeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new AttendanceEmployeesResponse(attendanceService.listEmployeeOptions(user));
    }

    /** Re-derives daily rows for a range; also the historical backfill entry point. HR/CEO only. */
    @PostMapping("/daily/recalculate")
    AttendanceRecalculateResponse recalculateDaily(
            @Valid @RequestBody AttendanceRecalculateRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return new AttendanceRecalculateResponse(attendanceService.recalculateDaily(
            request.fromDate(), request.toDate(), request.employeeId()));
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
