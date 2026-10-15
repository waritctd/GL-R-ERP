package th.co.glr.hr.attendance;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import th.co.glr.hr.notification.NotificationEmailService;

/**
 * Emails the configured recipient (and CC list), at 12:00 noon Asia/Bangkok on the last day of each
 * month, how many days each configured employee attended in that month. The run happens at noon, so
 * the last day counts only scans made before then.
 *
 * <p>A day counts when the employee has at least one {@code hr.attendance_punch} row with that
 * {@code work_date} — a lone clock-in or a lone clock-out is a day attended. Punches resolve to an
 * employee the same way the rest of attendance does ({@link AttendanceSql}: stored employee_id,
 * else badge = card number or employee code). A blank recipient (or no employee codes) turns the
 * report off.
 *
 * <p>Sent through {@link NotificationEmailService#sendLeaveSubmission}, the leave-email path
 * (branded HTML, CC list, subject suffix), so its log lines say "leave submission email". That
 * method is {@code @Async} and best-effort: a send failure is logged there and not rethrown, so the
 * scheduled run itself never fails on a mail outage.
 */
@Component
public class AttendanceMonthlyReportJob {
    private static final Logger log = LoggerFactory.getLogger(AttendanceMonthlyReportJob.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationEmailService emailService;
    private final List<String> cc;
    private final String to;
    private final List<String> employeeCodes;
    private final Clock clock;

    @Autowired
    public AttendanceMonthlyReportJob(
        NamedParameterJdbcTemplate jdbc,
        NotificationEmailService emailService,
        @Value("${app.attendance.monthly-report.to:}") String to,
        @Value("${app.attendance.monthly-report.cc:}") List<String> cc,
        @Value("${app.attendance.monthly-report.employee-codes:}") List<String> employeeCodes
    ) {
        this(jdbc, emailService, to, cc, employeeCodes, Clock.system(BANGKOK));
    }

    /** Lets tests pin "now" so month-end and New Year's Eve runs are deterministic. */
    AttendanceMonthlyReportJob(NamedParameterJdbcTemplate jdbc,
                               NotificationEmailService emailService, String to,
                               List<String> cc, List<String> employeeCodes, Clock clock) {
        this.jdbc = jdbc;
        this.emailService = emailService;
        this.to = to == null ? "" : to.trim();
        this.cc = cc;
        this.employeeCodes = trimmed(employeeCodes);
        this.clock = clock;
    }

    private static List<String> trimmed(List<String> values) {
        return values.stream()
            .map(String::trim)
            .filter(c -> !c.isEmpty())
            .toList();
    }

    @Scheduled(cron = "0 0 12 L * *", zone = "Asia/Bangkok")
    public void sendThisMonth() {
        send(YearMonth.now(clock));
    }

    void send(YearMonth month) {
        if (to.isEmpty() || employeeCodes.isEmpty()) {
            log.info("Monthly attendance report is off (no recipient or employee codes configured)");
            return;
        }
        Map<String, String> daysByCode = new HashMap<>();
        jdbc.query("""
            WITH days AS (
                SELECT %s AS employee_id, COUNT(DISTINCT p.work_date) AS days
                  FROM hr.attendance_punch p
                %s
                 WHERE p.work_date BETWEEN :from AND :to
                 GROUP BY %s
            )
            SELECT e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS full_name,
                   COALESCE(d.days, 0) AS days
              FROM hr.employee e
              LEFT JOIN days d ON d.employee_id = e.employee_id
             WHERE e.employee_code IN (:codes)
            """.formatted(AttendanceSql.RESOLVED_EMPLOYEE_ID, AttendanceSql.RESOLVED_EMPLOYEE_JOIN,
                          AttendanceSql.RESOLVED_EMPLOYEE_ID),
            new MapSqlParameterSource()
                .addValue("from", month.atDay(1))
                .addValue("to", month.atEndOfMonth())
                .addValue("codes", employeeCodes),
            rs -> {
                String code = rs.getString("employee_code");
                String name = rs.getString("full_name").trim();
                daysByCode.put(code, (name.isEmpty() ? "" : name + " ")
                    + "(" + code + "): " + rs.getInt("days") + " วัน");
            });

        String label = thaiMonthYear(month);
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(BANGKOK);
        StringBuilder body = new StringBuilder("สรุปจำนวนวันเข้างาน ").append(label).append('\n')
            .append("นับทุกวันที่มีการสแกนอย่างน้อย 1 ครั้ง "
                + "(สแกนเข้าหรือสแกนออกอย่างใดอย่างหนึ่งก็นับ)\n")
            .append("ข้อมูลถึงวันที่ ").append(now.getDayOfMonth()).append(' ')
            .append(thaiMonthYear(YearMonth.from(now))).append(" เวลา ")
            .append(now.format(DateTimeFormatter.ofPattern("HH:mm"))).append(" น.\n\n");
        for (String code : employeeCodes) {
            body.append("- ")
                .append(daysByCode.getOrDefault(code, code + ": ไม่พบรหัสพนักงานนี้ในระบบ"))
                .append('\n');
        }
        emailService.sendLeaveSubmission(
            to, cc, "สรุปจำนวนวันเข้างาน " + label, body.toString(), null, List.of());
    }

    private static String thaiMonthYear(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("th"))
            + " " + (month.getYear() + 543);
    }
}
