package th.co.glr.hr.analytics;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every Monday 06:00 Asia/Bangkok: computes the week that just ended ({@code analytics.compute_week}, V223).
 * Re-running a week replaces its metric rows, so a restart or a manual re-run is harmless. */
@Component
public class AnalyticsWeeklyJob {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsWeeklyJob.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AnalyticsWeeklyJob(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.system(BANGKOK));
    }

    AnalyticsWeeklyJob(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 6 * * MON", zone = "Asia/Bangkok")
    public void run() {
        LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        Integer rows = jdbc.queryForObject("SELECT analytics.compute_week(:weekStart)",
            Map.of("weekStart", monday), Integer.class);
        log.info("analytics.compute_week({}) wrote {} metric rows", monday, rows);
    }
}
