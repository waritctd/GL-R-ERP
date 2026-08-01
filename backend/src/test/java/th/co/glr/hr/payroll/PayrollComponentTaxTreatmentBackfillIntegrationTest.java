package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * P0 fix (Opus review, 2026-07-30): replays {@code V100__payroll_component_tax_treatment_backfill
 * .sql}'s EXACT SQL (read verbatim from the classpath, never re-typed here, so this test cannot drift
 * from the real migration) against a freshly-inserted employee, to prove the backfill logic itself is
 * correct -- not just that some default eventually reaches the calculator (that half is covered by
 * {@link PayrollClassificationReachabilityIntegrationTest}).
 *
 * <p>The Testcontainers golden-template migrates ONCE against an empty {@code hr.employee}, so V100
 * genuinely backfills nothing during that one real run (there is nobody to backfill). Every test in
 * this suite inserts its own employees AFTER that point. Re-running V100's own idempotent SQL
 * (guarded by {@code ON CONFLICT DO NOTHING} / an {@code IS DISTINCT FROM} guard) against a
 * freshly-inserted employee is therefore the only way to exercise the ACTUAL migration statements
 * against realistic data without rebuilding the golden template per test.
 */
class PayrollComponentTaxTreatmentBackfillIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;
    private String backfillSql;

    @BeforeEach
    void wireRepositoryAndReadMigration() throws IOException {
        repository = new PayrollRepository(jdbc);
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V100__payroll_component_tax_treatment_backfill.sql")) {
            assertThat(in).as("V100 migration file must be on the test classpath").isNotNull();
            backfillSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void replayingV100ClassifiesAPreExistingEmployeeFromCarryForwardEvidenceAndSafeDefaults() {
        long employeeId = seedEmployee("EMP-V100-001", "มณฑ์ชญา", "รีเพลย์");

        // Mimics V98's real evidence for employee 10012 (handoff section 9d): SPECIAL_PAY_1 recurs
        // every sampled month for this employee, so V100 must promote it to REGULAR_REPROJECT.
        jdbc.update("""
            INSERT INTO hr.payroll_component_carry_forward (employee_id, tax_year, component, carry_forward, updated_at)
            VALUES (:employeeId, 2026, 'SPECIAL_PAY_1', TRUE, now())
            """, new MapSqlParameterSource("employeeId", employeeId));

        executeStatements(backfillSql);

        Map<PayrollComponent, PayrollTaxTreatment> treatments =
            repository.findComponentTaxTreatmentsByEmployee(2026).get(employeeId);
        assertThat(treatments).isNotNull();

        // Promoted by carry-forward evidence.
        assertThat(treatments.get(PayrollComponent.SPECIAL_PAY_1))
            .withFailMessage("V100 must promote a component to REGULAR_REPROJECT when V98's real "
                + "carry-forward evidence says this exact employee/component pair recurs")
            .isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);

        // Handoff-explicit defaults, no evidence needed.
        assertThat(treatments.get(PayrollComponent.BONUS_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
        assertThat(treatments.get(PayrollComponent.OTHER_ONE_OFF_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
        assertThat(treatments.get(PayrollComponent.OVERTIME_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        assertThat(treatments.get(PayrollComponent.COMMISSION_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);

        // No carry-forward evidence for SPECIAL_PAY_2 -- must land on the safe unknown-recurrence
        // default, NOT be promoted to REGULAR_REPROJECT just because SPECIAL_PAY_1 was.
        assertThat(treatments.get(PayrollComponent.SPECIAL_PAY_2)).isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        // Correction (owner, 2026-07-29, caught in review before merge): DIRECTOR_REMUNERATION is
        // RESOLVED, not defaulted -- "director remuneration is every month but the pay may change once
        // in a while" (owner). Paid every คราว => ป.96/2543 ข้อ 2.1/2.4 => REGULAR_REPROJECT, matching
        // PayrollCalculator's legacy calculate() engine, which hardcodes it into the REGULAR limb. See
        // PayrollRepository#defaultTaxTreatment's javadoc for the full correction history.
        assertThat(treatments.get(PayrollComponent.DIRECTOR_REMUNERATION)).isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);

        // SALARY / NON_TAXABLE_INCOME are deliberately never backfilled.
        assertThat(treatments).doesNotContainKey(PayrollComponent.SALARY);
        assertThat(treatments).doesNotContainKey(PayrollComponent.NON_TAXABLE_INCOME);
    }

    @Test
    void replayingV100NeverOverwritesAnHrClassificationMadeBeforeItRuns() {
        long employeeId = seedEmployee("EMP-V100-002", "กันตินันท์", "รีเพลย์");
        // HR has ALREADY classified SPECIAL_PAY_1 to EXTRA_KNOWN_FREQUENCY (a deliberate choice
        // diverging from what V100's own default/carry-forward promotion would pick).
        repository.upsertComponentTaxTreatment(2026, java.util.List.of(
            new PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employeeId);
        // Even with carry-forward evidence present, an existing row must never be silently promoted.
        jdbc.update("""
            INSERT INTO hr.payroll_component_carry_forward (employee_id, tax_year, component, carry_forward, updated_at)
            VALUES (:employeeId, 2026, 'SPECIAL_PAY_1', TRUE, now())
            """, new MapSqlParameterSource("employeeId", employeeId));

        executeStatements(backfillSql);

        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026).get(employeeId)
            .get(PayrollComponent.SPECIAL_PAY_1))
            .withFailMessage("V100's backfill must never overwrite an existing HR classification, "
                + "even one that carry-forward evidence would otherwise promote")
            .isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
    }

    /**
     * Strips {@code --} line comments FIRST, then splits on {@code ;} -- splitting on {@code ;}
     * before stripping comments would fold each statement's leading comment block into the same chunk
     * as the statement text, making every chunk "start with a comment" and silently skip real SQL.
     */
    private void executeStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.strip().startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        for (String statement : withoutComments.toString().split(";")) {
            String trimmed = statement.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            jdbc.getJdbcOperations().execute(trimmed);
        }
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", new BigDecimal("30000")),
            Long.class);
    }
}
