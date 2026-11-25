package th.co.glr.hr.salesboard;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyReport;

/** GET /api/sales/weekly-report (sales: own only; sales_manager, ceo: the team or one rep). Rules live in
 * {@link WeeklyReportService}. */
@RestController
@RequestMapping("/api/sales")
public class WeeklyReportController {
    private final WeeklyReportService service;
    private final SessionContext sessions;

    public WeeklyReportController(WeeklyReportService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/weekly-report")
    WeeklyReport weeklyReport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                              @RequestParam(required = false) Long ownerId, HttpSession session) {
        return service.report(sessions.requireUser(session), week, ownerId);
    }
}
