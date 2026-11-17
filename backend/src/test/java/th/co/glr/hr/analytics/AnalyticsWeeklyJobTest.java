package th.co.glr.hr.analytics;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AnalyticsWeeklyJobTest {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private void assertRunsFor(String instant, LocalDate expectedMonday) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        new AnalyticsWeeklyJob(jdbc, Clock.fixed(Instant.parse(instant), BANGKOK)).run();
        verify(jdbc).queryForObject(contains("analytics.compute_week"),
            argThat((Map<String, ?> p) -> expectedMonday.equals(p.get("weekStart"))), eq(Integer.class));
    }

    @Test
    void midWeekRunsForTheWeekThatJustEnded() {
        assertRunsFor("2026-11-18T03:00:00Z", LocalDate.of(2026, 11, 9));
    }

    @Test
    void bangkokMondayMorningComputesTheWeekThatJustEndedEvenThoughUtcIsSunday() {
        assertRunsFor("2026-11-22T20:00:00Z", LocalDate.of(2026, 11, 16));
    }
}
