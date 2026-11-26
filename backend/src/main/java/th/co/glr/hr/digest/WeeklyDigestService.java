package th.co.glr.hr.digest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.EmployeeLoginRecord;
import th.co.glr.hr.digest.WeeklyDigestMail.Rendered;
import th.co.glr.hr.digest.WeeklyDigestMail.SalesWeek;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.salesboard.WeeklyReportRepository;
import th.co.glr.hr.ticket.QuietRule;

/** Monday 08:30 Asia/Bangkok digests: one to account (cc every active account employee), one "สัปดาห์ของคุณ" per
 * sales rep. Off unless {@code app.digest.weekly.enabled=true}. */
@Service
public class WeeklyDigestService {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final WeeklyDigestRepository repo;
    private final WeeklyReportRepository weekly;
    private final EmployeeAuthRepository employees;
    private final NotificationEmailService mail;
    private final QuietRule quietRule;
    private final boolean enabled;
    private final String accountTo;
    private final Clock clock;

    @Autowired
    public WeeklyDigestService(WeeklyDigestRepository repo, WeeklyReportRepository weekly,
                               EmployeeAuthRepository employees, NotificationEmailService mail, QuietRule quietRule,
                               @Value("${app.digest.weekly.enabled:false}") boolean enabled,
                               @Value("${app.digest.weekly.account-to:account@glr.co.th}") String accountTo) {
        this(repo, weekly, employees, mail, quietRule, enabled, accountTo, Clock.system(BANGKOK));
    }

    WeeklyDigestService(WeeklyDigestRepository repo, WeeklyReportRepository weekly, EmployeeAuthRepository employees,
                        NotificationEmailService mail, QuietRule quietRule, boolean enabled, String accountTo,
                        Clock clock) {
        this.repo = repo;
        this.weekly = weekly;
        this.employees = employees;
        this.mail = mail;
        this.quietRule = quietRule;
        this.enabled = enabled;
        this.accountTo = accountTo == null ? "" : accountTo.trim();
        this.clock = clock;
    }

    /** The scheduled run. Each mail is sent on its own so one failure never stops the rest. */
    public void runWeekly() {
        if (!enabled) {
            log.info("Weekly digest is off (app.digest.weekly.enabled=false)");
            return;
        }
        try {
            Optional<Rendered> account = renderAccount();
            if (account.isEmpty() || accountTo.isEmpty()) {
                log.info("Weekly account digest skipped: nothing waiting or no recipient");
            } else {
                mail.sendDigest(accountTo, accountCc(), account.get().subject(),
                    account.get().html(), account.get().text());
            }
        } catch (Exception e) {
            log.error("Weekly account digest failed: {}", e.getMessage());
        }
        int sent = 0;
        for (EmployeeLoginRecord rep : salesReps()) {
            try {
                Optional<Rendered> r = renderSales(rep, lastWeekMonday());
                if (r.isPresent()) {
                    mail.sendDigest(rep.email(), List.of(), r.get().subject(), r.get().html(), r.get().text());
                    sent++;
                }
            } catch (Exception e) {
                log.error("Weekly sales recap failed for employee {}: {}", rep.employeeId(), e.getMessage());
            }
        }
        log.info("Weekly sales recaps sent: {}", sent);
    }

    /** Admin test: sends one digest to {@code to} regardless of the enabled flag. Returns the subject, or empty when
     * there was nothing to send. {@code employeeId}: which rep's recap (default: the first one that has content). */
    public Optional<String> sendTest(String kind, String to, LocalDate weekStart, Long employeeId) {
        Optional<Rendered> r;
        if ("account".equals(kind)) {
            r = renderAccount();
        } else {
            LocalDate monday = weekStart == null ? lastWeekMonday()
                : weekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            r = salesReps().stream().filter(e -> employeeId == null || e.employeeId() == employeeId)
                .map(e -> renderSales(e, monday)).flatMap(Optional::stream).findFirst();
        }
        r.ifPresent(x -> mail.sendDigest(to, List.of(), "[ทดสอบ] " + x.subject(), x.html(), x.text()));
        return r.map(Rendered::subject);
    }

    Optional<Rendered> renderAccount() {
        var items = repo.accountItems();
        return items.isEmpty() ? Optional.empty()
            : Optional.of(WeeklyDigestMail.account(items, LocalDate.now(clock), mail::portalUrl));
    }

    Optional<Rendered> renderSales(EmployeeLoginRecord rep, LocalDate monday) {
        Instant from = monday.atStartOfDay(BANGKOK).toInstant();
        Instant to = monday.plusWeeks(1).atStartOfDay(BANGKOK).toInstant();
        Map<String, Map<Long, Integer>> counts = weekly.weekCounts(rep.employeeId(), from, to);
        int stage = counts.get("stage").getOrDefault(rep.employeeId(), 0);
        int quotes = counts.get("quotations").getOrDefault(rep.employeeId(), 0);
        BigDecimal received = repo.receivedByOwner(from, to).getOrDefault(rep.employeeId(), BigDecimal.ZERO);
        var stale = repo.staleDeals(rep.employeeId());
        if (stage == 0 && quotes == 0 && received.signum() <= 0 && stale.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(WeeklyDigestMail.sales(
            new SalesWeek(rep.name(), monday, stage, quotes, received, stale, quietRule.quietDays()),
            mail::portalUrl));
    }

    private LocalDate lastWeekMonday() {
        return LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    private List<String> accountCc() {
        return employees.findAllActive().stream()
            .filter(e -> "account".equals(DivisionAccessPolicy.roleFor(e)) && hasEmail(e))
            .map(EmployeeLoginRecord::email).toList();
    }

    /** Active sales-role employees with an email who own at least one open deal. */
    private List<EmployeeLoginRecord> salesReps() {
        Map<Long, Integer> open = repo.openDealCounts();
        return employees.findAllActive().stream()
            .filter(e -> "sales".equals(DivisionAccessPolicy.roleFor(e)) && hasEmail(e)
                && open.containsKey(e.employeeId()))
            .toList();
    }

    private static boolean hasEmail(EmployeeLoginRecord e) {
        return e.email() != null && e.email().contains("@");
    }
}
