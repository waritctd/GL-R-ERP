package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises the batched .dat import SQL (multi-row INSERT ... ON CONFLICT DO NOTHING RETURNING,
 * plus employee resolution) against a real PostgreSQL database. The Mockito unit tests cannot cover
 * the dynamically built SQL. V7 seeds the SHOWROOM site and SHOWROOM_SC700 device.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AttendanceRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private AttendanceRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new AttendanceRepository(jdbc, new ObjectMapper());
    }

    private NormalizedAttendancePunch punch(String badge, OffsetDateTime time) {
        return new NormalizedAttendancePunch(
            "SHOWROOM", "SHOWROOM_SC700", badge, time, time.toLocalDate(),
            (short) 1, (short) 0, "0", "0", "BIOMETRIC", "USB_DAT_IMPORT", Map.of("k", "v"));
    }

    @Test
    void batchInsertResolvesEmployeeReportsCountAndDedups() {
        Long employeeId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, badge_card_no) VALUES ('10036','10036') RETURNING employee_id",
            Map.of(), Long.class);

        OffsetDateTime morning = OffsetDateTime.parse("2024-01-02T08:00:00+07:00");
        OffsetDateTime evening = OffsetDateTime.parse("2024-01-02T17:00:00+07:00");

        int inserted = repository.batchInsertPunches(List.of(
            punch("10036", morning),    // resolves to the seeded employee
            punch("999999", evening))); // no matching employee -> employee_id stays null
        assertThat(inserted).isEqualTo(2);

        assertThat(jdbc.queryForObject(
            "SELECT employee_id FROM hr.attendance_punch WHERE badge_code = '10036'",
            Map.of(), Long.class)).isEqualTo(employeeId);
        assertThat(jdbc.queryForObject(
            "SELECT employee_id FROM hr.attendance_punch WHERE badge_code = '999999'",
            Map.of(), Long.class)).isNull();

        // Re-import an overlapping punch plus a new one: only the new row is inserted (dedup).
        OffsetDateTime nextDay = OffsetDateTime.parse("2024-01-03T08:00:00+07:00");
        int insertedAgain = repository.batchInsertPunches(List.of(
            punch("10036", morning),    // duplicate of the first insert
            punch("10036", nextDay)));  // new
        assertThat(insertedAgain).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hr.attendance_punch WHERE badge_code = '10036'",
            Map.of(), Long.class)).isEqualTo(2L);
    }

    @Test
    void batchInsertHandlesMoreRowsThanOneChunk() {
        // Exceed IMPORT_INSERT_CHUNK (500) to prove multi-statement chunking works end to end.
        OffsetDateTime base = OffsetDateTime.parse("2024-02-01T00:00:00+07:00");
        List<NormalizedAttendancePunch> punches = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            punches.add(punch("777", base.plusMinutes(i)));
        }
        assertThat(repository.batchInsertPunches(punches)).isEqualTo(1200);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hr.attendance_punch WHERE badge_code = '777'",
            Map.of(), Long.class)).isEqualTo(1200L);
    }

    @Test
    void batchInsertOfEmptyListIsNoop() {
        assertThat(repository.batchInsertPunches(List.of())).isZero();
    }
}
