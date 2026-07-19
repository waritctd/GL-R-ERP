package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Keeps {@code hr.attendance_daily} honest without anyone asking.
 *
 * <p>Two jobs in one place:
 * <ul>
 *   <li>a nightly sweep of the last few days, which heals rows whose punches arrived late (a device
 *       catch-up pull can deliver yesterday's scans this morning, after the day was rolled up);
 *   <li>an optional one-shot historical backfill on startup, off by default.
 * </ul>
 *
 * <p>Both are safe to run repeatedly: the upsert is idempotent and refuses to overwrite rows HR has
 * marked as manually overridden.
 */
@Component
public class AttendanceDailyRecalcJob implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AttendanceDailyRecalcJob.class);

    private final AttendanceDailyService dailyService;
    private final AppProperties properties;

    public AttendanceDailyRecalcJob(AttendanceDailyService dailyService, AppProperties properties) {
        this.dailyService = dailyService;
        this.properties = properties;
    }

    /** 02:15 Asia/Bangkok — after the day has closed, before anyone reads the morning numbers. */
    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Bangkok")
    public void recalculateRecentDays() {
        AppProperties.Daily config = properties.getAttendance().getDaily();
        if (!config.isRecalcEnabled()) {
            return;
        }
        ZoneId zone = ZoneId.of(properties.getAttendance().getSchedule().getZone());
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(Math.max(1, config.getRecalcLookbackDays()));
        int written = dailyService.recalculateRange(from, today, null);
        log.info("Attendance daily recalc {} to {} wrote {} rows", from, today, written);
    }

    /**
     * One-shot historical roll-up, for activating this feature against an existing punch ledger.
     *
     * <p>Off by default: it walks the whole of history, so it should be a deliberate act. The HR
     * endpoint {@code POST /api/attendance/daily/recalculate} is the normal way in.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getAttendance().getDaily().isBackfillOnStartup()) {
            return;
        }
        log.info("Attendance daily backfill starting over all history");
        int written = dailyService.recalculateAllHistory();
        log.info("Attendance daily backfill wrote {} rows", written);
    }
}
