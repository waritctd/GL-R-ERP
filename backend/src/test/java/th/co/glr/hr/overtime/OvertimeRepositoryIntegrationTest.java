package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
    void payrollMonthProcessedIsTrueOnlyForAProcessedPeriod() {
        insertPeriod("2026-05-01", "OPEN");
        insertPeriod("2026-06-01", "PROCESSED");

        assertThat(repository.payrollMonthProcessed(LocalDate.parse("2026-06-01"))).isTrue();
        // An open period must not lock the month — payroll has not run yet.
        assertThat(repository.payrollMonthProcessed(LocalDate.parse("2026-05-01"))).isFalse();
    }

    @Test
    void payrollMonthSeedCoveredIsTrueThroughCoveredMonthAndFalseJustAfter() {
        insertCoverage(2026, "2026-06-01");

        assertThat(repository.payrollMonthSeedCovered(LocalDate.parse("2026-01-01"))).isTrue();
        assertThat(repository.payrollMonthSeedCovered(LocalDate.parse("2026-06-01"))).isTrue();
        // The cutoff is inclusive of June and must not leak into July.
        assertThat(repository.payrollMonthSeedCovered(LocalDate.parse("2026-07-01"))).isFalse();
    }

    private void insertPeriod(String payrollMonth, String status) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                CAST(:payrollMonth AS DATE),
                CAST(:payrollMonth AS DATE),
                (CAST(:payrollMonth AS DATE) + INTERVAL '1 month - 1 day')::date,
                (CAST(:payrollMonth AS DATE) + INTERVAL '1 month - 1 day')::date,
                :status
            )
            """, Map.of("payrollMonth", payrollMonth, "status", status));
    }

    private void insertCoverage(int taxYear, String coversThrough) {
        jdbc.update("""
            INSERT INTO hr.payroll_seed_coverage (tax_year, covers_through, note)
            VALUES (:taxYear, CAST(:coversThrough AS DATE), 'test coverage')
            """, Map.of("taxYear", taxYear, "coversThrough", coversThrough));
    }
}
