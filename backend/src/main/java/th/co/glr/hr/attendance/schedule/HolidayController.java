package th.co.glr.hr.attendance.schedule;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.attendance.schedule.BotHolidayFetchService.FetchOutcome;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * {@code hr.holiday} admin surface: the manual BOT-refresh trigger, plus create/update/delete/list
 * for HR's own company-day additions ({@link HolidayAdminService}). Every handler is gated {@code
 * "hr", "ceo"}, matching the existing pattern for other admin-triggered attendance actions ({@code
 * AttendanceController}'s device import / card backfill).
 *
 * <p>This is a new authorization surface (five new/extended endpoints with a role gate), so it
 * ships with a real-Postgres integration test through this exact controller
 * ({@code HolidayControllerIntegrationTest}) proving the wrong roles get 403 for every handler —
 * see CLAUDE.md "Permission changes must ship evidence".
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
    private final HolidayAdminService adminService;
    private final SessionContext sessions;

    public HolidayController(
            BotHolidayFetchService fetchService, HolidayAdminService adminService, SessionContext sessions) {
        this.fetchService = fetchService;
        this.adminService = adminService;
        this.sessions = sessions;
    }

    /** Same role gate as the rest of attendance's admin-triggered actions: HR and CEO only. */
    @PostMapping("/fetch")
    Map<String, List<FetchOutcome>> fetchNow(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return Map.of("outcomes", fetchService.fetchNow());
    }

    /** The admin list view (both BANK and COMPANY rows) for a date range. HR/CEO only. */
    @GetMapping
    HolidaysResponse list(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return new HolidaysResponse(adminService.list(from, to));
    }

    /** Adds a one-off company holiday. Always lands as {@code source = 'COMPANY'}. HR/CEO only. */
    @PostMapping
    HolidayDto create(@RequestBody HolidayCreateRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return adminService.create(user, request.holidayDate(), request.nameTh());
    }

    /**
     * Renames an existing holiday (bank- or company-sourced) and forces it to {@code source =
     * 'COMPANY'} — see {@link HolidayAdminService}. HR/CEO only.
     */
    @PutMapping("/{date}")
    HolidayDto update(
            @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody HolidayUpdateRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        return adminService.update(user, date, request.nameTh());
    }

    /** Removes a holiday outright (bank- or company-sourced). HR/CEO only. */
    @DeleteMapping("/{date}")
    void delete(
            @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        adminService.delete(user, date);
    }
}
