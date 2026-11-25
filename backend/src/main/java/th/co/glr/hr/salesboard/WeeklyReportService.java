package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyLedger;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyLedgerWeek;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyNextPayment;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyOrderRow;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyRepRow;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyTodayEntry;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyReport;
import th.co.glr.hr.ticket.DealStage;

/**
 * The weekly report (spec 0003). A sales rep reads only their own; a sales_manager or ceo reads the team, or one rep
 * with {@code ownerId}. Oversight readers are not admitted (as for teamOverview). The ledger is built here, not by
 * {@code SalesBoardService.assemble}: a slipped OPEN payment must count once, in the current week.
 */
@Service
public class WeeklyReportService {
    static final Set<String> ROLES = Set.of("sales", "sales_manager", "ceo");
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    /** The order rows, and which of them are ACTIVE (the open-order counts skip COMPLETED ones). */
    private record Orders(List<WeeklyOrderRow> rows, Set<Long> active) {}

    private final WeeklyReportRepository repository;
    private final CashRepository cash;
    private final VisitRepository visits;
    private final EmployeeAuthRepository employees;
    private final Clock clock;

    @Autowired
    public WeeklyReportService(WeeklyReportRepository repository, CashRepository cash, VisitRepository visits,
                               EmployeeAuthRepository employees) {
        this(repository, cash, visits, employees, Clock.system(CashRepository.BANGKOK));
    }

    WeeklyReportService(WeeklyReportRepository repository, CashRepository cash, VisitRepository visits,
                        EmployeeAuthRepository employees, Clock clock) {
        this.repository = repository;
        this.cash = cash;
        this.visits = visits;
        this.employees = employees;
        this.clock = clock;
    }

    public WeeklyReport report(UserPrincipal actor, LocalDate week, Long ownerId) {
        if (!ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        boolean team = !"sales".equals(actor.role());
        if (!team && ownerId != null && ownerId != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        if (team && ownerId != null && !employees.findByEmployeeIds(Set.of(ownerId)).containsKey(ownerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบพนักงานที่ระบุ");
        }
        Long subject = team ? ownerId : Long.valueOf(actor.id());

        LocalDate today = LocalDate.now(clock.withZone(CashRepository.BANGKOK));
        LocalDate monday = (week == null ? today : week).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        YearMonth month = YearMonth.from(monday.plusDays(3));

        Orders orders = orders(subject, monday, month);
        List<WeeklyTodayEntry> todayEntries = subject != null && subject == actor.id() ? todayEntries(subject, today)
            : List.of();
        return new WeeklyReport(monday, monday.plusDays(6), cash.moneyFrom(), subject == null ? "TEAM" : "OWN",
            ledger(subject, month, currentMonday, today), orders.rows(),
            reps(subject, monday, LocalDateTime.now(clock.withZone(CashRepository.BANGKOK)), orders), todayEntries);
    }

    // ── ledger (D5) ──────────────────────────────────────────────────────────────

    private WeeklyLedger ledger(Long owner, YearMonth month, LocalDate currentMonday, LocalDate today) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<LocalDate> mondays = new ArrayList<>();
        for (LocalDate m = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !m.isAfter(last);
             m = m.plusWeeks(1)) {
            if (YearMonth.from(m.plusDays(3)).equals(month)) {
                mondays.add(m);
            }
        }
        int n = mondays.size();
        BigDecimal[] got = zeros(n);
        BigDecimal[][] open = {zeros(n), zeros(n), zeros(n)};
        // Receipts stay calendar-month cash: a day before the first Monday folds into week 1, one after the last
        // week into the last, so the weeks add up to the month.
        for (CashRepository.ReceivedDayRow r : cash.receivedByDay(owner, first, last)) {
            int i = 0;
            while (i + 1 < n && !r.day().isBefore(mondays.get(i + 1))) {
                i++;
            }
            got[i] = got[i].add(r.received());
        }
        // A week before the current one carries no OPEN money: its rows are slips, counted once below.
        for (CashRepository.ExpectedWeekRow r : cash.expectedByWeek(owner, first, last)) {
            int i = mondays.indexOf(r.week());
            if (i >= 0 && !r.week().isBefore(currentMonday)) {
                open[0][i] = open[0][i].add(r.deliveredAwaiting());
                open[1][i] = open[1][i].add(r.deliveriesScheduled());
                open[2][i] = open[2][i].add(r.depositsExpected());
            }
        }
        BigDecimal[] slip = zeros(3);
        for (CashRepository.ExpectedWeekRow r : cash.expectedByWeek(owner, EPOCH, currentMonday.plusDays(2))) {
            slip[0] = slip[0].add(r.deliveredAwaiting());
            slip[1] = slip[1].add(r.deliveriesScheduled());
            slip[2] = slip[2].add(r.depositsExpected());
        }
        int cur = mondays.indexOf(currentMonday);
        BigDecimal slipped = BigDecimal.ZERO;
        if (cur >= 0) {
            for (int k = 0; k < 3; k++) {
                open[k][cur] = open[k][cur].add(slip[k]);
                slipped = slipped.add(slip[k]);
            }
        }
        List<WeeklyLedgerWeek> weeks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate m = mondays.get(i);
            weeks.add(new WeeklyLedgerWeek(m, got[i], open[0][i], open[1][i], open[2][i], i == cur ? slipped : BigDecimal.ZERO,
                i == cur, m.plusDays(6).isBefore(today)));
        }
        BigDecimal received = sum(got);
        BigDecimal a = sum(open[0]);
        BigDecimal b = sum(open[1]);
        BigDecimal c = sum(open[2]);
        return new WeeklyLedger(month.toString(), weeks, received, a, b, c, received.add(a).add(b).add(c));
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal sum(BigDecimal[] v) {
        return java.util.Arrays.stream(v).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── orders (D12) ─────────────────────────────────────────────────────────────

    private Orders orders(Long owner, LocalDate monday, YearMonth month) {
        Instant moneyFrom = cash.moneyFrom() == null ? null
            : cash.moneyFrom().atStartOfDay(CashRepository.BANGKOK).toInstant();
        List<WeeklyReportRepository.OrderTicket> tickets = repository.orders(owner, moneyFrom,
            month.atDay(1).atStartOfDay(CashRepository.BANGKOK).toInstant(),
            month.plusMonths(1).atDay(1).atStartOfDay(CashRepository.BANGKOK).toInstant());
        Set<Long> active = new LinkedHashSet<>();
        tickets.stream().filter(t -> "ACTIVE".equals(t.lifecycle())).forEach(t -> active.add(t.ticketId()));
        Map<Long, WeeklyReportRepository.OpenPayment> next = new LinkedHashMap<>();
        repository.openPayments(active).forEach(p -> next.putIfAbsent(p.ticketId(), p));
        Map<Long, String> names = names(tickets.stream().map(WeeklyReportRepository.OrderTicket::ownerId).toList());
        List<WeeklyOrderRow> rows = new ArrayList<>();
        for (WeeklyReportRepository.OrderTicket t : tickets) {
            WeeklyReportRepository.OpenPayment p = next.get(t.ticketId());
            WeeklyNextPayment np = p == null ? null : new WeeklyNextPayment(p.id(), p.kind(), p.remaining(), p.week());
            rows.add(new WeeklyOrderRow(t.ticketId(), t.code(), t.title(), t.customerName(), t.projectName(), t.ownerId(),
                names.get(t.ownerId()), t.salesStage(), t.confirmedAt(), t.value(), t.received(),
                t.value().subtract(t.received()).max(BigDecimal.ZERO), np, np != null && np.week().isBefore(monday)));
        }
        return new Orders(rows, active);
    }

    private Map<Long, String> names(java.util.Collection<Long> ids) {
        Map<Long, String> out = new LinkedHashMap<>();
        employees.findByEmployeeIds(new LinkedHashSet<>(ids)).forEach((id, e) -> out.put(id, e.name()));
        return out;
    }

    // ── reps (D7) ────────────────────────────────────────────────────────────────

    private List<WeeklyRepRow> reps(Long owner, LocalDate monday, LocalDateTime now, Orders orders) {
        LocalDate today = now.toLocalDate();
        LocalDate sunday = monday.plusDays(6);
        Instant from = monday.atStartOfDay(CashRepository.BANGKOK).toInstant();
        Instant to = sunday.plusDays(1).atStartOfDay(CashRepository.BANGKOK).toInstant();
        Instant dayFrom = today.atStartOfDay(CashRepository.BANGKOK).toInstant();
        Map<String, Map<Long, Integer>> counts = repository.weekCounts(owner, from, to);
        Map<Long, Instant> last = repository.lastEntries(owner);
        Map<Long, Integer> entriesToday = new LinkedHashMap<>();
        repository.entries(owner, dayFrom, today.plusDays(1).atStartOfDay(CashRepository.BANGKOK).toInstant())
            .forEach(e -> entriesToday.merge(e.ownerId(), 1, Integer::sum));
        List<VisitDtos.VisitDto> weekVisits = owner == null ? visits.findAll(monday, sunday)
            : visits.findOwn(owner, monday, sunday);

        Set<Long> ids = new LinkedHashSet<>();
        if (owner != null) {
            ids.add(owner);
        } else {
            ids.addAll(repository.activeOwners());
            counts.values().forEach(m -> ids.addAll(m.keySet()));
            ids.addAll(entriesToday.keySet());
            orders.rows().forEach(o -> ids.add(o.ownerId()));
            weekVisits.stream().filter(v -> !VisitDtos.CANCELLED.equals(v.status())).forEach(v -> ids.add(v.ownerId()));
        }
        Map<Long, String> names = names(ids);
        List<WeeklyRepRow> rows = new ArrayList<>();
        for (long id : ids) {
            int done = 0;
            int planned = 0;
            int due = 0;
            int withResult = 0;
            for (VisitDtos.VisitDto v : weekVisits) {
                if (v.ownerId() != id) {
                    continue;
                }
                boolean cancelled = VisitDtos.CANCELLED.equals(v.status());
                boolean finished = VisitDtos.DONE.equals(v.status());
                done += finished ? 1 : 0;
                planned += cancelled ? 0 : 1;
                // Due = needs a result: an earlier day, or today at a time that has passed (no time = not due yet).
                boolean overdue = v.plannedDate().isBefore(today)
                    || (v.plannedDate().isEqual(today) && v.plannedTime() != null && !v.plannedTime().isAfter(now.toLocalTime()));
                if (overdue) {
                    due++;
                    withResult += finished || cancelled ? 1 : 0;
                }
            }
            int open = 0;
            int dated = 0;
            for (WeeklyOrderRow o : orders.rows()) {
                if (o.ownerId() == id && orders.active().contains(o.ticketId()) && o.outstanding().signum() > 0) {
                    open++;
                    dated += o.nextPayment() != null ? 1 : 0;
                }
            }
            rows.add(new WeeklyRepRow(id, names.get(id), count(counts, "new", id), count(counts, "stage", id),
                count(counts, "quotations", id), count(counts, "confirmed", id), count(counts, "lost", id), done,
                planned, due, withResult, open, dated, entriesToday.getOrDefault(id, 0), last.get(id)));
        }
        rows.sort(Comparator.comparing((WeeklyRepRow r) -> r.name() == null ? "" : r.name())
            .thenComparingLong(WeeklyRepRow::ownerId));
        return rows;
    }

    private static int count(Map<String, Map<Long, Integer>> counts, String key, long id) {
        return counts.get(key).getOrDefault(id, 0);
    }

    // ── today (the caller's own entries) ─────────────────────────────────────────

    private List<WeeklyTodayEntry> todayEntries(long owner, LocalDate today) {
        Instant from = today.atStartOfDay(CashRepository.BANGKOK).toInstant();
        return repository.entries(owner, from, today.plusDays(1).atStartOfDay(CashRepository.BANGKOK).toInstant())
            .stream().map(e -> new WeeklyTodayEntry(e.at(), e.kind(), label(e), e.ticketId(), e.ticketTitle())).toList();
    }

    private static String label(WeeklyReportRepository.Entry e) {
        return switch (e.kind()) {
            case "STAGE" -> {
                int from = DealStage.displayNoOf(e.a());
                int to = DealStage.displayNoOf(e.b());
                yield (to > from ? "เลื่อนขั้น " : "ย้ายขั้น ") + from + " เป็น " + to;
            }
            case "QUOTATION" -> "ออกใบเสนอราคา " + e.a();
            case "VISIT" -> "บันทึกเข้าพบ";
            default -> switch (e.a() == null ? "" : e.a()) {
                case "CALL" -> "โทรศัพท์";
                case "MEETING" -> "พบลูกค้า";
                case "SITE_VISIT" -> "ดูหน้างาน";
                case "SAMPLE_SENT" -> "ส่งตัวอย่าง";
                case "EMAIL" -> "อีเมล";
                case "MESSAGE" -> "ส่งข้อความ";
                case "QUOTATION_FOLLOWUP" -> "ติดตามใบเสนอราคา";
                default -> "บันทึกความคืบหน้า";
            };
        };
    }
}
