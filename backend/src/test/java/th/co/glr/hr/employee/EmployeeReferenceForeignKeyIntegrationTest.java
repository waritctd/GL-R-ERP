package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** V147 restored three foreign keys on hr.employee that V1__employee_master_schema.sql declares but that
 * production's database had been missing -- almost certainly dropped by hand during the original ETL import,
 * and never restored. */
class EmployeeReferenceForeignKeyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String V147_RESOURCE =
        "db/migration/V147__restore_employee_reference_foreign_keys.sql";

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

    // ── Behavioural evidence: the constraint actually blocks a DELETE of a referenced parent ───

    @Test
    void aDivisionCannotBeDeletedWhileAnEmployeeStillPointsAtIt() {
        long divisionId = insertDivision("FKIT-DIV");
        insertEmployee("FKIT-E1", divisionId, null, null);

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM hr.division WHERE division_id = :id", Map.of("id", divisionId)))
            .as("employee_division_id_fkey should refuse to orphan the employee row just "
                + "inserted -- this is the exact exposure issue #779 closes: before V147, this "
                + "DELETE would have succeeded silently on prod")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDepartmentCannotBeDeletedWhileAnEmployeeStillPointsAtIt() {
        long departmentId = insertDepartment("FKIT-DEPT");
        insertEmployee("FKIT-E2", null, departmentId, null);

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM hr.department WHERE department_id = :id", Map.of("id", departmentId)))
            .as("employee_department_id_fkey should refuse to orphan the employee row just "
                + "inserted")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPositionCannotBeDeletedWhileAnEmployeeStillPointsAtIt() {
        long positionId = insertPosition("FKIT-POS");
        insertEmployee("FKIT-E3", null, null, positionId);

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM hr.position WHERE position_id = :id", Map.of("id", positionId)))
            .as("employee_position_id_fkey should refuse to orphan the employee row just inserted")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Behavioural evidence: the constraint actually blocks a bad insert ───────────────────────

    @Test
    void anEmployeeCannotPointAtAForeignKeyValueThatDoesNotExist() {
        long noSuchId = -999L;

        assertThatThrownBy(() -> insertEmployee("FKIT-BADDIV", noSuchId, null, null))
            .as("employee_division_id_fkey should reject an insert whose division_id has no "
                + "matching hr.division row")
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEmployee("FKIT-BADDEPT", null, noSuchId, null))
            .as("employee_department_id_fkey should reject an insert whose department_id has no "
                + "matching hr.department row")
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEmployee("FKIT-BADPOS", null, null, noSuchId))
            .as("employee_position_id_fkey should reject an insert whose position_id has no "
                + "matching hr.position row")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** ANTI-VACUITY, from the other direction: these three columns are nullable by design, so a constraint that
     * were somehow tightened into rejecting a legitimate NULL would not be caught by any assertion above -- every
     * one of them exercises a non-NULL value. */
    @Test
    void anEmployeeCanStillLeaveAllThreeColumnsNull_theyAreNotRequired() {
        long employeeId = insertEmployee("FKIT-NULLOK", null, null, null);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT division_id, department_id, position_id FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId));

        assertThat(row.get("division_id")).isNull();
        assertThat(row.get("department_id")).isNull();
        assertThat(row.get("position_id")).isNull();
    }

    // PART 2 — V147 itself. Each test below builds the "constraint missing" state (prod's real shape) and then
    // runs V147's own SQL, read live off the classpath.

    /** The normal path: prod's actual shape (valid, fully-resolved employee data, constraint simply absent)
     * restored correctly by running V147 for real. */
    @Test
    void v147RestoresAllThreeForeignKeysOverProdsShape() {
        long divisionId = insertDivision("V147-DIV");
        long departmentId = insertDepartment("V147-DEPT");
        long positionId = insertPosition("V147-POS");

        for (ExpectedForeignKey fk : EXPECTED) {
            dropForeignKey(fk.constraintName());
        }
        long employeeId = insertEmployee("FKIT-V147-E", divisionId, departmentId, positionId);

        executeV147();

        for (ExpectedForeignKey fk : EXPECTED) {
            assertForeignKeyIsCorrectlyShaped(fk);
        }
        // V147 restores a constraint; it must not have touched the data it now protects.
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT division_id, department_id, position_id FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId));
        assertThat(((Number) row.get("division_id")).longValue()).isEqualTo(divisionId);
        assertThat(((Number) row.get("department_id")).longValue()).isEqualTo(departmentId);
        assertThat(((Number) row.get("position_id")).longValue()).isEqualTo(positionId);
    }

    /** Guard 3, exercised for real, with TWO dangling rows so both the count and the capped id list are checked,
     * not just the fact that something was reported. That leaves a genuinely dangling hr.employee.division_id. */
    @Test
    void v147RefusesAndNamesTheOffendingEmployees_whenDivisionRowsAreDangling() {
        long divisionId = insertDivision("DANG-DIV");
        long employeeId1 = insertEmployee("FKIT-DANG-E1", divisionId, null, null);
        long employeeId2 = insertEmployee("FKIT-DANG-E2", divisionId, null, null);

        dropForeignKey("employee_division_id_fkey");
        jdbc.update("DELETE FROM hr.division WHERE division_id = :id", Map.of("id", divisionId));

        assertThatThrownBy(this::executeV147)
            .as("V147 must refuse to restore employee_division_id_fkey over genuinely dangling "
                + "rows, rather than silently rewriting the employees' data (guard 3)")
            .hasMessageContaining("employee_division_id_fkey")
            .hasMessageContaining("2 hr.employee row(s)")
            .hasMessageContaining("first 10 of 2:")
            .hasMessageContaining(String.valueOf(employeeId1))
            .hasMessageContaining(String.valueOf(employeeId2));

        // The RAISE must happen BEFORE ADD CONSTRAINT -- the FK must still be absent afterwards.
        assertForeignKeyAbsent(new ExpectedForeignKey(
            "employee_division_id_fkey", "division_id", "hr.division"));
    }

    @Test
    void v147RefusesAndNamesTheOffendingEmployee_whenADepartmentRowIsDangling() {
        long departmentId = insertDepartment("DANG-DEPT");
        long employeeId = insertEmployee("FKIT-DANG-E3", null, departmentId, null);

        dropForeignKey("employee_department_id_fkey");
        jdbc.update(
            "DELETE FROM hr.department WHERE department_id = :id", Map.of("id", departmentId));

        assertThatThrownBy(this::executeV147)
            .as("V147 must refuse to restore employee_department_id_fkey over a genuinely "
                + "dangling row, rather than silently rewriting the employee's data (guard 3)")
            .hasMessageContaining("employee_department_id_fkey")
            .hasMessageContaining("1 hr.employee row(s)")
            .hasMessageContaining(String.valueOf(employeeId));

        assertForeignKeyAbsent(new ExpectedForeignKey(
            "employee_department_id_fkey", "department_id", "hr.department"));
    }

    @Test
    void v147RefusesAndNamesTheOffendingEmployee_whenAPositionRowIsDangling() {
        long positionId = insertPosition("DANG-POS");
        long employeeId = insertEmployee("FKIT-DANG-E4", null, null, positionId);

        dropForeignKey("employee_position_id_fkey");
        jdbc.update("DELETE FROM hr.position WHERE position_id = :id", Map.of("id", positionId));

        assertThatThrownBy(this::executeV147)
            .as("V147 must refuse to restore employee_position_id_fkey over a genuinely dangling "
                + "row, rather than silently rewriting the employee's data (guard 3)")
            .hasMessageContaining("employee_position_id_fkey")
            .hasMessageContaining("1 hr.employee row(s)")
            .hasMessageContaining(String.valueOf(employeeId));

        assertForeignKeyAbsent(new ExpectedForeignKey(
            "employee_position_id_fkey", "position_id", "hr.position"));
    }

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

    private void assertForeignKeyAbsent(ExpectedForeignKey fk) {
        assertThat(foreignKeyRows(fk))
            .as("%s should not exist -- V147 must RAISE before reaching ADD CONSTRAINT when a "
                + "dangling row is present (guard 3), and must not leave a partially-applied "
                + "constraint behind", fk.constraintName())
            .isEmpty();
    }

    // ── running V147's own SQL, read live off the classpath ─────────────────────────────────────

    /** Executes V147's real file contents against the test database, exactly as Flyway would. */
    private void executeV147() {
        String sql = readV147Sql();
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    private static String readV147Sql() {
        try (InputStream in = EmployeeReferenceForeignKeyIntegrationTest.class
                .getClassLoader().getResourceAsStream(V147_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "classpath resource not found: " + V147_RESOURCE + " -- is db/migration on "
                    + "the test classpath, and has V147 been renamed? (Part 2 of this class reads "
                    + "V147's SQL live rather than pasting a copy, so a rename here must be "
                    + "matched by a rename of this constant.)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private void dropForeignKey(String constraintName) {
        // Constant, hardcoded constraint names only (see EXPECTED above) -- never caller-supplied, so string
        // concatenation here is not an injection surface.
        jdbc.getJdbcTemplate().execute("ALTER TABLE hr.employee DROP CONSTRAINT " + constraintName);
    }

    private long insertDivision(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :code, TRUE) RETURNING division_id
            """, Map.of("code", code), Long.class);
    }

    private long insertDepartment(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (:code, :code, TRUE) RETURNING department_id
            """, Map.of("code", code), Long.class);
    }

    private long insertPosition(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :code, TRUE) RETURNING position_id
            """, Map.of("code", code), Long.class);
    }

    /** {@code divisionId}/{@code departmentId}/{@code positionId} may each be null. */
    private long insertEmployee(String code, Long divisionId, Long departmentId, Long positionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("departmentId", departmentId);
        params.put("positionId", positionId);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, department_id, position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :departmentId, :positionId,
                    DATE '2020-01-01', TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
