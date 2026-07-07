package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

class OvertimeRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private OvertimeRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new OvertimeRepository(jdbc);
    }

    @Test
    void cancelSubmittedRequestWithOwnerNullReviewFieldsDoesNotFailJdbcUpdate() {
        Long employeeId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code) VALUES ('OT-001') RETURNING employee_id",
            Map.of(),
            Long.class);
        Long overtimeId = jdbc.queryForObject("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at,
                planned_minutes, reason, payroll_month
            )
            VALUES (
                :employeeId, DATE '2026-07-15',
                TIMESTAMPTZ '2026-07-15 18:00:00+07',
                TIMESTAMPTZ '2026-07-15 20:00:00+07',
                120, 'Owner cancel integration test', DATE '2026-07-01'
            )
            RETURNING overtime_request_id
            """,
            Map.of("employeeId", employeeId),
            Long.class);

        int updated = repository.cancel(overtimeId, null, null);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT status
              FROM hr.overtime_request
             WHERE overtime_request_id = :id
            """, Map.of("id", overtimeId), String.class)).isEqualTo("CANCELLED");
    }
}
