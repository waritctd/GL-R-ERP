package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.salesboard.SalesBoardDtos.MyWork;
import th.co.glr.hr.salesboard.SalesBoardDtos.RepRow;
import th.co.glr.hr.salesboard.SalesBoardDtos.TeamOverview;
import th.co.glr.hr.ticket.QuietRule;

/**
 * งานของฉัน (rep) and ภาพรวมทีมขาย (manager/CEO), spec 2026-11-15 §7.3/§7.4. The role decision lives here; the
 * own-deals scope lives in {@link SalesBoardRepository}'s WHERE clause.
 */
@Service
public class SalesBoardService {
    static final Set<String> MY_WORK_ROLES = Set.of("sales", "sales_manager", "ceo");
    static final Set<String> TEAM_ROLES = Set.of("sales_manager", "ceo");
    static final int FOLLOW_UP_LIMIT = 5;
    static final int STALE_LIMIT = 20;

    /** daysQuiet × THB value desc, then daysQuiet desc, then ticket id for a stable order. Only THB is weighted (a null
     * currency is THB, as in repRows): amounts in different currencies are not comparable, so a non-THB deal's value
     * counts as 0 and it ranks by days alone. */
    static final Comparator<BoardDeal> QUIET_RANK = Comparator
        .comparing((BoardDeal d) -> BigDecimal.valueOf(d.daysQuiet())
            .multiply(d.value() == null || !isThb(d.currency()) ? BigDecimal.ZERO : d.value())).reversed()
        .thenComparing(Comparator.comparingInt(BoardDeal::daysQuiet).reversed())
        .thenComparingLong(BoardDeal::ticketId);

    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final SalesBoardRepository repository;
    private final QuietRule quietRule;

    private final th.co.glr.hr.auth.EmployeeAuthRepository employees;
    private final VisitService visits;

    public SalesBoardService(SalesBoardRepository repository, QuietRule quietRule,
                             th.co.glr.hr.auth.EmployeeAuthRepository employees, VisitService visits) {
        this.repository = repository;
        this.quietRule = quietRule;
        this.employees = employees;
        this.visits = visits;
    }

    public MyWork myWork(UserPrincipal actor) {
        if (!MY_WORK_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        List<BoardDeal> board = repository.activeDealsOwnedBy(actor.id());
        return new MyWork(rankQuiet(board, FOLLOW_UP_LIMIT), board, quietRule.quietDays(), visits.ownThisWeek(actor));
    }

    public TeamOverview teamOverview(UserPrincipal actor) {
        if (!TEAM_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        List<BoardDeal> deals = repository.activeDealsAllReps();
        List<VisitDtos.VisitDto> weekVisits = visits.teamThisWeek(actor);
        return new TeamOverview(repRows(deals, weekVisits), rankQuiet(deals, STALE_LIMIT), quietRule.quietDays(),
            weekVisits);
    }

    private static List<BoardDeal> rankQuiet(List<BoardDeal> deals, int limit) {
        return deals.stream().filter(BoardDeal::quiet).sorted(QUIET_RANK).limit(limit).toList();
    }

    static final String THB = "THB";

    private static boolean isThb(String currency) {
        return currency == null || THB.equals(currency);
    }

    /**
     * One row per owner, ordered by THB open value desc, then employee id. Only THB values are added into
     * {@code openValue}; other currencies are summed per code. Each owner's role comes from the same derivation
     * login uses (DivisionAccessPolicy#roleFor), so a manager's or the CEO's own deals are labelled, not dropped.
     * An owner with visits this week but no open deal still gets a row (0 deals), so the week grid has their name.
     */
    private List<RepRow> repRows(List<BoardDeal> deals, List<VisitDtos.VisitDto> weekVisits) {
        Map<Long, List<BoardDeal>> byOwner = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        deals.forEach(d -> {
            byOwner.computeIfAbsent(d.ownerId(), k -> new ArrayList<>()).add(d);
            names.putIfAbsent(d.ownerId(), d.ownerName());
        });
        // Only a booked (not cancelled) visit earns a deal-less owner a row; cancelled-only is nothing to show.
        weekVisits.stream().filter(v -> !VisitDtos.CANCELLED.equals(v.status())).forEach(v -> {
            byOwner.computeIfAbsent(v.ownerId(), k -> new ArrayList<>());
            names.putIfAbsent(v.ownerId(), v.ownerName());
        });
        // One query for every rep's role, not one per rep.
        Map<Long, th.co.glr.hr.auth.EmployeeLoginRecord> owners =
            employees == null ? Map.of() : employees.findByEmployeeIds(byOwner.keySet());
        List<RepRow> rows = new ArrayList<>();
        byOwner.forEach((ownerId, own) -> {
            BigDecimal thb = BigDecimal.ZERO;
            Map<String, BigDecimal> other = new java.util.TreeMap<>();
            Instant last = null;
            int quiet = 0;
            for (BoardDeal d : own) {
                if (d.value() != null) {
                    String currency = d.currency() == null ? THB : d.currency();
                    if (THB.equals(currency)) {
                        thb = thb.add(d.value());
                    } else {
                        other.merge(currency, d.value(), BigDecimal::add);
                    }
                }
                if (d.quiet()) {
                    quiet++;
                }
                if (d.lastTouchedAt() != null && (last == null || d.lastTouchedAt().isAfter(last))) {
                    last = d.lastTouchedAt();
                }
            }
            th.co.glr.hr.auth.EmployeeLoginRecord owner = owners.get(ownerId);
            String role = owner == null ? null : th.co.glr.hr.auth.DivisionAccessPolicy.roleFor(owner);
            int done = 0;
            int booked = 0;
            for (VisitDtos.VisitDto v : weekVisits) {
                if (v.ownerId() == ownerId && !VisitDtos.CANCELLED.equals(v.status())) {
                    booked++;
                    if (VisitDtos.DONE.equals(v.status())) {
                        done++;
                    }
                }
            }
            rows.add(new RepRow(ownerId, names.get(ownerId), role, own.size(), thb, other, quiet, last, done, booked));
        });
        rows.sort(Comparator.comparing(RepRow::openValue).reversed().thenComparingLong(RepRow::employeeId));
        return rows;
    }
}
