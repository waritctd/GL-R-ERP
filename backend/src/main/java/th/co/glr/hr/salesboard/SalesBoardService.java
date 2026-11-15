package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.salesboard.SalesBoardDtos.CashMonth;
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

    private final th.co.glr.hr.auth.EmployeeAuthRepository employees;
    private final VisitService visits;
    private final CashRepository cash;
    private final Clock clock;

    @Autowired
    public SalesBoardService(SalesBoardRepository repository, QuietRule quietRule,
                             th.co.glr.hr.auth.EmployeeAuthRepository employees, VisitService visits,
                             CashRepository cash) {
        this(repository, quietRule, employees, visits, cash, Clock.system(VisitService.BANGKOK));
    }

    SalesBoardService(SalesBoardRepository repository, QuietRule quietRule,
                      th.co.glr.hr.auth.EmployeeAuthRepository employees, VisitService visits,
                      CashRepository cash, Clock clock) {
        this.repository = repository;
        this.quietRule = quietRule;
        this.employees = employees;
        this.visits = visits;
        this.cash = cash;
        this.clock = clock;
    }

    /** The current calendar month on the Asia/Bangkok clock. */
    YearMonth thisMonth() {
        return YearMonth.now(clock.withZone(VisitService.BANGKOK));
    }

    /** Cash for {@code month}: one owner's ({@code ownerId}) or, when null, the whole team's. See {@link CashMonth}. */
    public CashMonth cashForMonth(Long ownerId, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        return assemble(month, cash.expectedByWeek(ownerId, first, last), cash.receivedByDay(ownerId, first, last));
    }

    /** Each owner's month: {@code [forecast, received]} for the team overview's per-rep columns. */
    private Map<Long, CashMonth> cashByOwner(YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        Map<Long, List<CashRepository.ExpectedWeekRow>> expected = new LinkedHashMap<>();
        Map<Long, List<CashRepository.ReceivedDayRow>> received = new LinkedHashMap<>();
        cash.expectedByWeek(null, first, last)
            .forEach(r -> expected.computeIfAbsent(r.ownerId(), k -> new ArrayList<>()).add(r));
        cash.receivedByDay(null, first, last)
            .forEach(r -> received.computeIfAbsent(r.ownerId(), k -> new ArrayList<>()).add(r));
        Map<Long, CashMonth> out = new LinkedHashMap<>();
        java.util.Set<Long> owners = new java.util.LinkedHashSet<>(expected.keySet());
        owners.addAll(received.keySet());
        owners.forEach(o -> out.put(o, assemble(month, expected.getOrDefault(o, List.of()),
            received.getOrDefault(o, List.of()))));
        return out;
    }

    /**
     * Folds the repository rows into the month. Weeks are every Monday inside the month; a receipt dated before the
     * first Monday (the 1st falling mid-week) goes into the first week, so the weeks always add up to the month.
     */
    static CashMonth assemble(YearMonth month, List<CashRepository.ExpectedWeekRow> expected,
                              List<CashRepository.ReceivedDayRow> received) {
        List<LocalDate> mondays = new ArrayList<>();
        LocalDate monday = month.atDay(1).with(java.time.temporal.TemporalAdjusters.nextOrSame(
            java.time.DayOfWeek.MONDAY));
        for (; !monday.isAfter(month.atEndOfMonth()); monday = monday.plusWeeks(1)) {
            mondays.add(monday);
        }
        int n = mondays.size();
        BigDecimal[] delivered = zeros(n);
        BigDecimal[] scheduled = zeros(n);
        BigDecimal[] deposits = zeros(n);
        BigDecimal[] got = zeros(n);
        BigDecimal forecast = BigDecimal.ZERO;
        for (CashRepository.ExpectedWeekRow r : expected) {
            int i = mondays.indexOf(r.week());
            if (i < 0) {
                continue;
            }
            forecast = forecast.add(r.forecast());
            delivered[i] = delivered[i].add(r.deliveredAwaiting());
            scheduled[i] = scheduled[i].add(r.deliveriesScheduled());
            deposits[i] = deposits[i].add(r.depositsExpected());
        }
        BigDecimal receivedTotal = BigDecimal.ZERO;
        for (CashRepository.ReceivedDayRow r : received) {
            if (!YearMonth.from(r.day()).equals(month) || n == 0) {
                continue;
            }
            int i = 0;
            while (i + 1 < n && !r.day().isBefore(mondays.get(i + 1))) {
                i++;
            }
            got[i] = got[i].add(r.received());
            receivedTotal = receivedTotal.add(r.received());
        }
        List<SalesBoardDtos.CashWeek> weeks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            weeks.add(new SalesBoardDtos.CashWeek(mondays.get(i), delivered[i], scheduled[i], deposits[i], got[i]));
        }
        return new CashMonth(month.toString(), forecast, receivedTotal, sum(delivered), sum(scheduled), sum(deposits),
            weeks);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal sum(BigDecimal[] values) {
        return java.util.Arrays.stream(values).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public MyWork myWork(UserPrincipal actor) {
        if (!MY_WORK_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        List<BoardDeal> board = repository.activeDealsOwnedBy(actor.id());
        return new MyWork(rankQuiet(board, FOLLOW_UP_LIMIT), board, quietRule.quietDays(), visits.ownThisWeek(actor),
            cashForMonth(actor.id(), thisMonth()));
    }

    public TeamOverview teamOverview(UserPrincipal actor) {
        if (!TEAM_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        List<BoardDeal> deals = repository.activeDealsAllReps();
        List<VisitDtos.VisitDto> weekVisits = visits.teamThisWeek(actor);
        YearMonth month = thisMonth();
        return new TeamOverview(repRows(deals, weekVisits, cashByOwner(month)), rankQuiet(deals, STALE_LIMIT),
            quietRule.quietDays(), weekVisits, cashForMonth(null, month));
    }

    private static List<BoardDeal> rankQuiet(List<BoardDeal> deals, int limit) {
        return deals.stream().filter(BoardDeal::quiet).sorted(QUIET_RANK).limit(limit).toList();
    }

    static final String THB = "THB";

    /**
     * One row per owner, ordered by THB open value desc, then employee id. Only THB values are added into
     * {@code openValue}; other currencies are summed per code. Each owner's role comes from the same derivation
     * login uses (DivisionAccessPolicy#roleFor), so a manager's or the CEO's own deals are labelled, not dropped.
     * An owner with visits this week but no open deal still gets a row (0 deals), so the week grid has their name.
     */
    private List<RepRow> repRows(List<BoardDeal> deals, List<VisitDtos.VisitDto> weekVisits,
                                 Map<Long, CashMonth> cashByOwner) {
        Map<Long, List<BoardDeal>> byOwner = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        deals.forEach(d -> {
            byOwner.computeIfAbsent(d.ownerId(), k -> new ArrayList<>()).add(d);
            names.putIfAbsent(d.ownerId(), d.ownerName());
        });
        weekVisits.forEach(v -> {
            byOwner.computeIfAbsent(v.ownerId(), k -> new ArrayList<>());
            names.putIfAbsent(v.ownerId(), v.ownerName());
        });
        // An owner with cash this month but no open deal and no visit (every deal closed) still gets a row.
        cashByOwner.keySet().forEach(ownerId -> {
            byOwner.computeIfAbsent(ownerId, k -> new ArrayList<>());
            if (!names.containsKey(ownerId) && employees != null) {
                names.put(ownerId, employees.findByEmployeeId(ownerId).map(e -> e.name()).orElse(null));
            }
        });
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
            String role = employees == null ? null : employees.findByEmployeeId(ownerId)
                .map(th.co.glr.hr.auth.DivisionAccessPolicy::roleFor).orElse(null);
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
            CashMonth ownCash = cashByOwner.get(ownerId);
            rows.add(new RepRow(ownerId, names.get(ownerId), role, own.size(), thb, other, quiet, last, done, booked,
                ownCash == null ? BigDecimal.ZERO : ownCash.forecast(),
                ownCash == null ? BigDecimal.ZERO : ownCash.received()));
        });
        rows.sort(Comparator.comparing(RepRow::openValue).reversed().thenComparingLong(RepRow::employeeId));
        return rows;
    }
}
