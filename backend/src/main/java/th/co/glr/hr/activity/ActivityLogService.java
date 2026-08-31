package th.co.glr.hr.activity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/**
 * Serves the cross-employee activity log to admins only.
 *
 * <p>This reads every employee's movements through the portal, so the gate is the whole point of
 * the class. It is enforced here rather than in the controller so that no future caller can reach
 * the repository without passing it.
 */
@Service
public class ActivityLogService {

    /** Bangkok, explicitly. A bare {@code LocalDate.now()} would resolve to UTC on Render. */
    static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 5000;

    private final ActivityLogRepository repository;

    public ActivityLogService(ActivityLogRepository repository) {
        this.repository = repository;
    }

    public List<ActivityLogEntryDto> list(UserPrincipal actor, LocalDate from, LocalDate to,
                                          Long employeeId, Integer limit) {
        requireAdmin(actor);
        return repository.findRecent(startOf(from), endOf(to), employeeId, clampLimit(limit));
    }

    public List<ActivityLogSummaryDto> summarize(UserPrincipal actor, LocalDate from, LocalDate to) {
        requireAdmin(actor);
        return repository.summarize(startOf(from), endOf(to));
    }

    /** Semantic actions — the "who requested what, who approved what" view. Same gate. */
    public List<AuditEventDto> auditEvents(UserPrincipal actor, LocalDate from, LocalDate to,
                                           Long employeeId, Integer limit) {
        requireAdmin(actor);
        return repository.findAuditEvents(startOf(from), endOf(to), employeeId, clampLimit(limit));
    }

    /**
     * The gate.
     *
     * <p>Checks {@code hr.employee.is_admin} live rather than trusting anything on the session
     * principal, so revoking the capability takes effect on the next request instead of at the
     * holder's next login. Deliberately does <em>not</em> consult {@code UserPrincipal.role()} —
     * admin is orthogonal to the derived role (see V157), and no role grants it.
     */
    void requireAdmin(UserPrincipal actor) {
        if (actor == null || !repository.isAdmin(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    /** Defaults to today in Bangkok when the caller names no window. */
    private OffsetDateTime startOf(LocalDate from) {
        LocalDate day = from == null ? LocalDate.now(BANGKOK) : from;
        return day.atStartOfDay(BANGKOK).toOffsetDateTime();
    }

    /** Exclusive upper bound: {@code to} is inclusive as a date, so the bound is the next midnight. */
    private OffsetDateTime endOf(LocalDate to) {
        LocalDate day = to == null ? LocalDate.now(BANGKOK) : to;
        return day.plusDays(1).atStartOfDay(BANGKOK).toOffsetDateTime();
    }
}
