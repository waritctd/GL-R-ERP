package th.co.glr.hr.activity;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

/** Admin-only read of {@code hr.activity_log}. The gate lives in {@link ActivityLogService}. */
@RestController
@RequestMapping("/api/activity-log")
public class ActivityLogController {

    private final ActivityLogService service;
    private final SessionContext sessionContext;

    public ActivityLogController(ActivityLogService service, SessionContext sessionContext) {
        this.service = service;
        this.sessionContext = sessionContext;
    }

    @GetMapping
    List<ActivityLogEntryDto> list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) Integer limit,
        HttpSession session
    ) {
        return service.list(sessionContext.requireUser(session), from, to, employeeId, limit);
    }

    @GetMapping("/audit")
    List<AuditEventDto> audit(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) Integer limit,
        HttpSession session
    ) {
        return service.auditEvents(sessionContext.requireUser(session), from, to, employeeId, limit);
    }

    @GetMapping("/summary")
    List<ActivityLogSummaryDto> summary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        HttpSession session
    ) {
        return service.summarize(sessionContext.requireUser(session), from, to);
    }
}
