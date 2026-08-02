package th.co.glr.hr.attendance.schedule;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.attendance.schedule.BotHolidayFetchService.FetchOutcome;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * Manual trigger for {@link BotHolidayFetchService}, so HR can force a refresh (e.g. after BOT
 * publishes a mid-year special holiday) without waiting for the monthly cron.
 *
 * <p>An endpoint was chosen over a cron-only design because the codebase already has this exact
 * pattern for other admin-triggered attendance actions ({@code AttendanceController}'s device
 * import / card backfill, gated {@code "hr", "ceo"}) — adding one more small, idempotent,
 * read-mostly-safe POST is proportionate, not a new kind of surface. The handler does nothing a
 * repeated cron tick would not also eventually do; it only moves the timing under HR's control.
 *
 * <p>This is a new authorization rule (a new endpoint with a role gate), so it ships with a
 * real-Postgres integration test through this exact controller
 * ({@code HolidayControllerIntegrationTest}) proving the wrong roles get 403 — see CLAUDE.md
 * "Permission changes must ship evidence".
 *
 * <p>{@link BotHolidayFetchService#fetchNow()} — not this controller — enforces a minimum interval
 * between attempts (this fetcher's own {@code BOT_HOLIDAY_API_TOKEN} budget of 100 calls/hour — a
 * key distinct per BOT API and per environment, not shared with {@code BotFxFetchService}) and
 * throws a 429 {@link th.co.glr.hr.common.ApiException} rather than silently returning an empty
 * result when called too soon; see that method's javadoc.
 */
@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    private final BotHolidayFetchService fetchService;
    private final SessionContext sessions;

    public HolidayController(BotHolidayFetchService fetchService, SessionContext sessions) {
        this.fetchService = fetchService;
        this.sessions = sessions;
    }

    /** Same role gate as the rest of attendance's admin-triggered actions: HR and CEO only. */
    @PostMapping("/fetch")
    Map<String, List<FetchOutcome>> fetchNow(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return Map.of("outcomes", fetchService.fetchNow());
    }
}
