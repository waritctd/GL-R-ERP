package th.co.glr.hr.attendance;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import th.co.glr.hr.attendance.daily.AttendanceWfhRosterResult;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {
    static final String AGENT_TOKEN_HEADER = "X-GLR-Agent-Token";
    private static final String XLSX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AttendanceService attendanceService;
    private final AttendanceMonthlySummaryService monthlySummaryService;
    private final SessionContext sessions;

    public AttendanceController(
            AttendanceService attendanceService,
            AttendanceMonthlySummaryService monthlySummaryService,
            SessionContext sessions) {
        this.attendanceService = attendanceService;
        this.monthlySummaryService = monthlySummaryService;
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
            @RequestParam(value = "divisionId", required = false) Long divisionId,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new AttendanceDailyResponse(
            attendanceService.listDaily(user, fromDate, toDate, employeeId, divisionId));
    }

    /**
     * HR's monthly attendance summary workbook (xlsx) -- a pure aggregation over exactly the rows
     * {@link #listDaily} already returns for this caller with these filters, plus an APPROVED-leave
     * overlay so ขาดงาน means genuinely unexcused absence rather than conflating it with approved
     * ลา (see {@link AttendanceMonthlySummaryService}'s javadoc for the full rule set).
     *
     * <p><strong>No {@code @PreAuthorize}, no {@code requireAnyRole} -- deliberately, mirroring
     * {@link #listDaily} above.</strong> {@code AttendanceService.resolveScope} already decides what
     * this caller may see (hr/ceo: everyone; ฝ่าย manager: their division; everyone else: self only,
     * with another id 403ing). This endpoint adds ZERO new authorization semantics on top of that:
     * it can never contain a row {@link #listDaily} would not also hand back for the same caller and
     * filters, so a separate role gate here would only ever duplicate (and risk drifting from) the
     * one {@code resolveScope} already enforces.
     */
    @GetMapping("/monthly-summary.xlsx")
    ResponseEntity<byte[]> monthlySummary(
            // required = false so a MISSING month reaches parseMonth's own null-check and the Thai
            // ApiException message below, rather than Spring's generic English
            // MissingServletRequestParameterException 400 -- "month is required" per the plan means
            // both the absent and the malformed case get the same, translated error surface.
            @RequestParam(value = "month", required = false) String monthParam,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "divisionId", required = false) Long divisionId,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        YearMonth month = parseMonth(monthParam);
        byte[] workbook = monthlySummaryService.export(user, month, employeeId, divisionId);
        String fileName = "attendance-summary-" + month + ".xlsx"; // YearMonth#toString() is ISO "YYYY-MM".
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(fileName)
                .build()
                .toString())
            .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
            .body(workbook);
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

    /**
     * The CEO/HR stand-up roster: marks everyone in {@code employee_ids} present for
     * {@code work_date} with no punches (WFH, §76 reporting only — never touches payroll).
     * Resubmitting for the same date reconciles the roster: anyone left off is un-marked. HR/CEO
     * only; the ids are still re-validated against the caller's own scope in the service, never
     * trusted from the request body outright.
     */
    @PostMapping("/daily/mark-present")
    AttendanceMarkPresentResponse markPresent(
            @Valid @RequestBody AttendanceMarkPresentRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "ceo", "hr");
        AttendanceWfhRosterResult result =
            attendanceService.markPresent(user, request.workDate(), request.employeeIds(), request.notes());
        return new AttendanceMarkPresentResponse(result.markedCount(), result.clearedCount());
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

    /** {@code YYYY-MM}, required -- mirrors {@code PayrollController#parseMonth}'s "reject rather
     * than silently default" stance for a month-scoped export. */
    private YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเดือน");
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รูปแบบเดือนไม่ถูกต้อง (ต้องเป็น YYYY-MM)");
        }
    }
}
