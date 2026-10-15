package th.co.glr.hr.attendance;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
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
import th.co.glr.hr.mail.Mailer;

/**
 * Emails one recipient, at 08:00 Asia/Bangkok on the 1st of each month, how many days each
 * configured employee attended in the previous calendar month.
 *
 * <p>A day counts when the employee has at least one {@code hr.attendance_punch} row with that
 * {@code work_date} — a lone clock-in or a lone clock-out is a day attended. Punches resolve to an
 * employee the same way the rest of attendance does ({@link AttendanceSql}: stored employee_id, else
 * badge = card number or employee code). A blank recipient (or no employee codes) turns the report
 * off. A mail failure propagates so the scheduled-run event recorder logs it as an error.
 */
@Component
public class AttendanceMonthlyReportJob {
    private static final Logger log = LoggerFactory.getLogger(AttendanceMonthlyReportJob.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final NamedParameterJdbcTemplate jdbc;
    private final Mailer mailer;
    private final String to;
    private final List<String> employeeCodes;
    private final Clock clock;

    @Autowired
    public AttendanceMonthlyReportJob(
        NamedParameterJdbcTemplate jdbc,
        Mailer mailer,
        @Value("${app.attendance.monthly-report.to:}") String to,
        @Value("${app.attendance.monthly-report.employee-codes:}") List<String> employeeCodes
    ) {
        this(jdbc, mailer, to, employeeCodes, Clock.system(BANGKOK));
    }

    /** Lets tests pin "today" so the 1st-of-month and New Year rollovers are deterministic. */
    AttendanceMonthlyReportJob(NamedParameterJdbcTemplate jdbc, Mailer mailer, String to,
                               List<String> employeeCodes, Clock clock) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.to = to == null ? "" : to.trim();
        this.employeeCodes = employeeCodes.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 8 1 * *", zone = "Asia/Bangkok")
    public void sendPreviousMonth() {
        send(YearMonth.now(clock).minusMonths(1));
    }

    void send(YearMonth month) {
        if (to.isEmpty() || employeeCodes.isEmpty()) {
            log.info("Monthly attendance report is off (no recipient or no employee codes configured)");
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

        String label = month.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("th"))
            + " " + (month.getYear() + 543);
        StringBuilder body = new StringBuilder("สรุปจำนวนวันเข้างาน ").append(label).append('\n')
            .append("นับทุกวันที่มีการสแกนอย่างน้อย 1 ครั้ง (สแกนเข้าหรือสแกนออกอย่างใดอย่างหนึ่งก็นับ)\n\n");
        for (String code : employeeCodes) {
            body.append("- ")
                .append(daysByCode.getOrDefault(code, code + ": ไม่พบรหัสพนักงานนี้ในระบบ"))
                .append('\n');
        }
        mailer.send(to, "สรุปจำนวนวันเข้างาน " + label, body.toString());
    }
}
