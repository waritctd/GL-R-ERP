package th.co.glr.hr.digest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every Monday 08:30 Asia/Bangkok. The enable flag is checked inside the service. */
@Component
public class WeeklyDigestJob {
    private final WeeklyDigestService service;

    public WeeklyDigestJob(WeeklyDigestService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 30 8 * * MON", zone = "Asia/Bangkok")
    public void run() {
        service.runWeekly();
    }
}
