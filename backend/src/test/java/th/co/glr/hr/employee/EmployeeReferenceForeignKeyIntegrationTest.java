package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** V147 restored three foreign keys on hr.employee that V1__employee_master_schema.sql declares but that
 * production's database had been missing -- almost certainly dropped by hand during the original ETL import,
 * and never restored. */
class EmployeeReferenceForeignKeyIntegrationTest extends AbstractPostgresIntegrationTest {

    private record ExpectedForeignKey(
        String constraintName, String column, String referencedTable) {
    }

    private static final List<ExpectedForeignKey> EXPECTED = List.of(
        new ExpectedForeignKey("employee_division_id_fkey", "division_id", "hr.division"),
        new ExpectedForeignKey("employee_department_id_fkey", "department_id", "hr.department"),
        new ExpectedForeignKey("employee_position_id_fkey", "position_id", "hr.position"));

    // PART 1 — schema backstop (see class Javadoc: real coverage, but NOT evidence about V147).

    /** Matched the same way V147's own guard matches it: column + referenced table via pg_constraint/pg_attribute,
     * never by conname alone (a differently -named FK on the same column would enforce the identical rule, and
     * this test must recognise that too, not just V147's SQL). */
    @Test
    void allThreeForeignKeysExistByColumnAndReferencedTable_notJustByName() {
        for (ExpectedForeignKey fk : EXPECTED) {
            assertForeignKeyIsCorrectlyShaped(fk);
        }
    }

    // ── Behavioural evidence: the constraint actually blocks a bad insert ───────────────────────

    // PART 2 — V147 itself. Each test below builds the "constraint missing" state (prod's real shape) and then
    // runs V147's own SQL, read live off the classpath.

    // ── catalog assertions, shared by Part 1 and Part 2 ─────────────────────────────────────────

    private List<Map<String, Object>> foreignKeyRows(ExpectedForeignKey fk) {
        return jdbc.queryForList("""
            SELECT c.conname::text AS conname,
                   c.convalidated AS convalidated,
                   c.confdeltype::text AS confdeltype
              FROM pg_constraint c
              JOIN pg_attribute a
                ON a.attrelid = c.conrelid
               AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'hr.employee'::regclass
               AND c.contype = 'f'
               AND c.confrelid = (:referencedTable)::regclass
               AND a.attname = :column
               AND cardinality(c.conkey) = 1
            """,
            Map.of("referencedTable", fk.referencedTable(), "column", fk.column()));
    }

    private void assertForeignKeyIsCorrectlyShaped(ExpectedForeignKey fk) {
        List<Map<String, Object>> rows = foreignKeyRows(fk);

        assertThat(rows)
            .as("expected exactly one foreign key from hr.employee.%s to %s, matched by column + "
                + "referenced table -- zero means it is missing; more than one means a duplicate "
                + "was added alongside it", fk.column(), fk.referencedTable())
            .hasSize(1);

        Map<String, Object> row = rows.get(0);
        assertThat(row.get("conname"))
            .as("hr.employee.%s's foreign key exists but under a different name than V1's inline "
                + "REFERENCES would generate -- V147 must match V1's naming exactly (guard 5)",
                fk.column())
            .isEqualTo(fk.constraintName());
        assertThat(row.get("convalidated"))
            .as("%s exists but was never validated -- it would enforce only new writes, not the "
                + "rows that existed when it was added (V147 should always run ADD CONSTRAINT ... "
                + "NOT VALID followed by VALIDATE CONSTRAINT in the same migration, so this "
                + "should never be observable)", fk.constraintName())
            .isEqualTo(true);
        assertThat(row.get("confdeltype"))
            .as("%s is not ON DELETE NO ACTION ('a') -- V1's inline REFERENCES has no ON DELETE "
                + "clause, which defaults to NO ACTION, and V147 must match that exactly (guard 5)",
                fk.constraintName())
            .isEqualTo("a");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** {@code divisionId}/{@code departmentId}/{@code positionId} may each be null. */
}
