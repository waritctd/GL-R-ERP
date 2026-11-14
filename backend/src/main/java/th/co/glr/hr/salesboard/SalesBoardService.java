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

    /** daysQuiet × value (null = 0) desc, then daysQuiet desc, then ticket id for a stable order. */
    static final Comparator<BoardDeal> QUIET_RANK = Comparator
        .comparing((BoardDeal d) -> BigDecimal.valueOf(d.daysQuiet())
            .multiply(d.value() == null ? BigDecimal.ZERO : d.value())).reversed()
        .thenComparing(Comparator.comparingInt(BoardDeal::daysQuiet).reversed())
        .thenComparingLong(BoardDeal::ticketId);

    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final SalesBoardRepository repository;
    private final QuietRule quietRule;

    public SalesBoardService(SalesBoardRepository repository, QuietRule quietRule) {
        this.repository = repository;
        this.quietRule = quietRule;
    }

    public MyWork myWork(UserPrincipal actor) {
        if (!MY_WORK_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        List<BoardDeal> board = repository.activeDealsOwnedBy(actor.id());
        return new MyWork(rankQuiet(board, FOLLOW_UP_LIMIT), board, quietRule.quietDays());
    }

    public TeamOverview teamOverview(UserPrincipal actor) {
        if (!TEAM_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        List<BoardDeal> deals = repository.activeDealsAllReps();
        return new TeamOverview(repRows(deals), rankQuiet(deals, STALE_LIMIT), quietRule.quietDays());
    }

    private static List<BoardDeal> rankQuiet(List<BoardDeal> deals, int limit) {
        return deals.stream().filter(BoardDeal::quiet).sorted(QUIET_RANK).limit(limit).toList();
    }

    /** One row per owner, ordered by open value desc, then employee id. */
    private static List<RepRow> repRows(List<BoardDeal> deals) {
        Map<Long, RepRow> byRep = new LinkedHashMap<>();
        for (BoardDeal d : deals) {
            RepRow r = byRep.get(d.ownerId());
            BigDecimal dealValue = d.value() == null ? BigDecimal.ZERO : d.value();
            if (r == null) {
                byRep.put(d.ownerId(), new RepRow(d.ownerId(), d.ownerName(), 1, dealValue, d.quiet() ? 1 : 0,
                    d.lastTouchedAt()));
                continue;
            }
            Instant last = r.lastUpdateAt();
            if (d.lastTouchedAt() != null && (last == null || d.lastTouchedAt().isAfter(last))) {
                last = d.lastTouchedAt();
            }
            byRep.put(d.ownerId(), new RepRow(d.ownerId(), r.name(), r.openDeals() + 1, r.openValue().add(dealValue),
                r.quietDeals() + (d.quiet() ? 1 : 0), last));
        }
        List<RepRow> rows = new ArrayList<>(byRep.values());
        rows.sort(Comparator.comparing(RepRow::openValue).reversed().thenComparingLong(RepRow::employeeId));
        return rows;
    }
}
