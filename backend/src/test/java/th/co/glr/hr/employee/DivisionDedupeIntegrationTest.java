package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V149 deletes dead, zero-reference {@code hr.division} duplicates that share a {@code name_th}
 * with a division that survives -- see that migration's own header comment for the full guard
 * design and the owner ruling behind it (issue: five candidate rows measured against production
 * 2026-08-16, only two of which actually qualify once the guard is applied correctly).
 *
 * <p><b>Every test below runs V149's own SQL, read live off the classpath</b> (see {@link
 * #executeV149()}), never a pasted copy -- a test asserting against its own stale copy of a
 * migration proves nothing about the migration, the same vacuity {@link
 * EmployeeReferenceForeignKeyIntegrationTest}'s Javadoc describes for V147. Because Flyway already
 * applies V149 once (as a no-op: the schema starts with zero {@code hr.division} rows, see V1's own
 * header -- division master data comes from a one-time ETL import outside the migration chain, not
 * from any migration) while building the test database, every test method re-applies V149's DELETE
 * directly against fixture rows it inserts itself, exactly as {@code EmployeeReferenceForeignKeyIntegrationTest}
 * does for V147.
 *
 * <p><b>Mutation-checked:</b> dropping V149's {@code hr.department} NOT EXISTS clause (guard 2)
 * turns {@link #rowWithOneDepartment_isNotDeleted_evenWithZeroRefSameNameDuplicate()} red -- the
 * division that should survive because it owns one department gets deleted instead, once its only
 * protection is removed. See the migration itself for the sha256-verified restore. Every other test
 * below stayed green under that specific mutation, which is expected: each isolates a different
 * guard, and {@link #rowWithOneDepartment_isNotDeleted_evenWithZeroRefSameNameDuplicate()} is the
 * one whose fixture depends on the department guard alone.
 */
class DivisionDedupeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String V149_RESOURCE =
        "db/migration/V149__drop_dead_duplicate_division_rows.sql";

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Confirms the migration comment's "exactly three FKs" claim against pg_constraint, rather
    // than letting the comment assert it unchecked -- a fourth FK added later (to hr.division, in
    // ANY schema Flyway owns) fails this test instead of silently making V149's guard incomplete.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void exactlyThreeForeignKeysReferenceHrDivision_confirmedFromPgConstraint() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT c.conrelid::regclass::text AS table_name,
                   a.attname AS column_name,
                   c.conname::text AS constraint_name
              FROM pg_constraint c
              JOIN pg_attribute a
                ON a.attrelid = c.conrelid
               AND a.attnum = ANY (c.conkey)
             WHERE c.contype = 'f'
               AND c.confrelid = 'hr.division'::regclass
               AND cardinality(c.conkey) = 1
             ORDER BY table_name
            """, new MapSqlParameterSource());

        assertThat(rows)
            .as("exactly three foreign keys should reference hr.division across every schema "
                + "Flyway owns (hr, hr_restricted, sales, customers, price_catalog) -- "
                + "hr.employee.division_id, hr.department.division_id and "
                + "hr.employee_assignment.division_id. If this is not 3, V149's guard is built on "
                + "a stale premise and must be re-derived before it is trusted.")
            .hasSize(3)
            .extracting(row -> row.get("table_name") + "." + row.get("column_name"))
            .containsExactlyInAnyOrder(
                "hr.employee.division_id",
                "hr.department.division_id",
                "hr.employee_assignment.division_id");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // The core paired scenario: a dead duplicate is deleted, its canonical same-name sibling
    // (carrying real references) survives by id. Mirrors division_id 38/18 (QC&ISO) exactly.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void deadDuplicate_pairedWithSurvivingCanonical_isDeleted_andCanonicalSurvivesById() {
        long canonical = insertDivision("V149-QCAN", "QC&ISO");
        insertEmployeeForDivision("V149-QC-E1", canonical);
        insertEmployeeForDivision("V149-QC-E2", canonical);
        insertDepartmentForDivision("V149-QC-D1", canonical);
        long deadDuplicate = insertDivision("V149-QDUP", "QC&ISO");

        int deleted = executeV149();

        assertThat(deleted).isEqualTo(1);
        assertThat(divisionExists(deadDuplicate))
            .as("the zero-reference QC&ISO duplicate should be deleted -- it pairs with the "
                + "surviving canonical row under guard 5")
            .isFalse();
        assertThat(divisionExists(canonical))
            .as("the canonical QC&ISO row (2 employees, 1 department) must survive BY ID -- it "
                + "fails guard 1/2 and can never be a delete candidate")
            .isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Guard isolation: a row that would otherwise qualify but carries exactly one reference in
    // ONE of the three FK tables must survive. Tested separately per table, so a migration that
    // only checks hr.employee cannot pass all three silently.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void rowWithOneEmployee_isNotDeleted_evenWithZeroRefSameNameDuplicate() {
        // Duplicate inserted FIRST (lower division_id) so that, if guard 1 were ever removed, the
        // employee-holding row -- inserted second, higher id, same non-numeric source_code class --
        // would LOSE the guard-6 tie-break and be deleted, making that regression visible here
        // instead of being masked by insertion-order luck (see the department test's Javadoc for
        // the mutation-check that proved this ordering matters).
        long zeroRefDuplicate = insertDivision("V149-EDUP", "Guard Employee Group");
        long protectedByEmployee = insertDivision("V149-EKEEP", "Guard Employee Group");
        insertEmployeeForDivision("V149-E-EMP1", protectedByEmployee);

        executeV149();

        assertThat(divisionExists(protectedByEmployee))
            .as("a division with exactly one hr.employee row must survive even though a "
                + "zero-reference duplicate shares its name_th -- guard 1")
            .isTrue();
        assertThat(divisionExists(zeroRefDuplicate))
            .as("the zero-reference duplicate should still be deleted -- it pairs with the "
                + "surviving employee-holding row")
            .isFalse();
    }

    /**
     * MUTATION-CHECK TARGET (named in the class Javadoc): dropping V149's {@code hr.department}
     * NOT EXISTS clause (guard 2) turns this test red. {@code zeroRefDuplicate} is inserted FIRST
     * (lower {@code division_id}); {@code protectedByDepartment} second (higher id). Both
     * source_codes are non-numeric alike, so guard 6's tie-break -- if it were ever reached for
     * this pair -- falls to the LOWER id, i.e. {@code zeroRefDuplicate}. That means
     * {@code protectedByDepartment} survives ONLY because guard 2 excludes it from
     * {@code reference_free} entirely; it would lose a tie-break outright. Confirmed empirically:
     * with guard 2 removed, this test goes red (protectedByDepartment gets deleted instead of its
     * duplicate) while the rest of the suite stays green -- the mutation this class's own Javadoc
     * describes. An earlier version of this fixture inserted the rows in the opposite order, which
     * let {@code protectedByDepartment} win the tie-break by accident regardless of guard 2 -- i.e.
     * the test passed for the wrong reason. Keep this order; do not swap it back.
     */
    @Test
    void rowWithOneDepartment_isNotDeleted_evenWithZeroRefSameNameDuplicate() {
        long zeroRefDuplicate = insertDivision("V149-DDUP", "Guard Department Group");
        long protectedByDepartment = insertDivision("V149-DKEEP", "Guard Department Group");
        insertDepartmentForDivision("V149-DDEP1", protectedByDepartment);

        executeV149();

        assertThat(divisionExists(protectedByDepartment))
            .as("a division with exactly one hr.department row must survive even though a "
                + "zero-reference duplicate shares its name_th -- guard 2")
            .isTrue();
        assertThat(divisionExists(zeroRefDuplicate))
            .as("the zero-reference duplicate should still be deleted -- it pairs with the "
                + "surviving department-holding row")
            .isFalse();
    }

    @Test
    void rowWithOneAssignment_isNotDeleted_evenWithZeroRefSameNameDuplicate() {
        // See the department test's Javadoc: duplicate inserted FIRST (lower id) so the
        // assignment-holding row cannot win guard 6's tie-break by insertion-order luck alone.
        long zeroRefDuplicate = insertDivision("V149-ADUP", "Guard Assignment Group");
        long protectedByAssignment = insertDivision("V149-AKEEP", "Guard Assignment Group");
        insertAssignmentForDivision(protectedByAssignment);

        executeV149();

        assertThat(divisionExists(protectedByAssignment))
            .as("a division with exactly one hr.employee_assignment row must survive even though "
                + "a zero-reference duplicate shares its name_th -- guard 3")
            .isTrue();
        assertThat(divisionExists(zeroRefDuplicate))
            .as("the zero-reference duplicate should still be deleted -- it pairs with the "
                + "surviving assignment-holding row")
            .isFalse();
    }

    /**
     * Guard 4: the non-FK polymorphic {@code hr.work_schedule_assignment} reference that {@code
     * pg_constraint} cannot see (see V149's own comment). Added beyond the three FK tables because
     * V146's migration comment already documents this exact table as a hazard for hr.division.
     */
    @Test
    void rowWithWorkScheduleAssignment_isNotDeleted_evenWithZeroRefSameNameDuplicate() {
        // See the department test's Javadoc: duplicate inserted FIRST (lower id) so the
        // schedule-holding row cannot win guard 6's tie-break by insertion-order luck alone.
        long zeroRefDuplicate = insertDivision("V149-WDUP", "Guard Schedule Group");
        long protectedBySchedule = insertDivision("V149-WKEEP", "Guard Schedule Group");
        insertWorkScheduleAssignmentForDivision(protectedBySchedule);

        executeV149();

        assertThat(divisionExists(protectedBySchedule))
            .as("a division with a DIVISION-scope hr.work_schedule_assignment row must survive "
                + "even though a zero-reference duplicate shares its name_th -- guard 4, the "
                + "non-FK reference pg_constraint cannot see")
            .isTrue();
        assertThat(divisionExists(zeroRefDuplicate))
            .as("the zero-reference duplicate should still be deleted -- it pairs with the "
                + "surviving schedule-holding row")
            .isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Guard 5: a uniquely-named empty division -- no same-name partner at all -- is never touched,
    // regardless of how empty it is.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void uniquelyNamedEmptyDivision_isNotDeleted() {
        long lonelyDivision = insertDivision("V149-LONE", "Uniquely Named Empty Division");

        int deleted = executeV149();

        assertThat(deleted).isZero();
        assertThat(divisionExists(lonelyDivision))
            .as("a zero-reference division with no same-name duplicate must survive -- guard 5 "
                + "requires ANOTHER surviving division with the same name_th, and none exists here")
            .isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Guard 6, the deterministic tie-break, exercised directly.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void tieBreak_prefersNonNumericSourceCode_overNumeric() {
        long numericCode = insertDivision("9001", "Tie Break Alpha");
        long nonNumericCode = insertDivision("ALPHA-X", "Tie Break Alpha");

        int deleted = executeV149();

        assertThat(deleted).isEqualTo(1);
        assertThat(divisionExists(nonNumericCode))
            .as("guard 6 prefers a non-numeric source_code as the keeper")
            .isTrue();
        assertThat(divisionExists(numericCode))
            .as("the numeric-source_code sibling loses the tie-break and is deleted")
            .isFalse();
    }

    /** Mirrors division_id 6/12 ('Sales Support 1', source_codes '0011'/'0013') exactly. */
    @Test
    void tieBreak_prefersLowestDivisionId_whenBothSourceCodesNumeric() {
        long insertedFirst = insertDivision("0011", "Sales Support 1");
        long insertedSecond = insertDivision("0013", "Sales Support 1");

        int deleted = executeV149();

        assertThat(deleted).isEqualTo(1);
        assertThat(divisionExists(insertedFirst))
            .as("both source_codes are fully numeric, so guard 6 falls back to the lowest "
                + "division_id -- the row inserted first (lower id) must be the keeper")
            .isTrue();
        assertThat(divisionExists(insertedSecond))
            .as("the higher-id sibling loses the numeric-tie-break and is deleted")
            .isFalse();
    }

    /**
     * Mirrors division_id 8/13 ('Sales Support 2' vs 'Sales Support  2', double space) exactly:
     * byte-exact name matching means these two rows never pair, so BOTH survive even though both
     * are zero-reference.
     */
    @Test
    void distinctWhitespaceVariants_neitherPairs_bothSurvive() {
        long singleSpace = insertDivision("0014", "Sales Support 2");
        long doubleSpace = insertDivision("0012", "Sales Support  2");

        int deleted = executeV149();

        assertThat(deleted).isZero();
        assertThat(divisionExists(singleSpace))
            .as("'Sales Support 2' (one space) has no exact-string partner -- guard 5 fails, so "
                + "it survives")
            .isTrue();
        assertThat(divisionExists(doubleSpace))
            .as("'Sales Support  2' (two spaces) is a different string and likewise has no "
                + "partner -- it survives too, not merged with the single-space row")
            .isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Idempotency.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void rerunningAfterDelete_deletesZeroRows() {
        long canonical = insertDivision("V149-RRCAN", "Rerun Group");
        insertEmployeeForDivision("V149-RR-E1", canonical);
        long deadDuplicate = insertDivision("V149-RRDUP", "Rerun Group");

        int firstRun = executeV149();
        assertThat(firstRun).isEqualTo(1);
        assertThat(divisionExists(deadDuplicate)).isFalse();

        int secondRun = executeV149();
        assertThat(secondRun)
            .as("re-running V149 after it has already deleted the duplicate must delete 0 rows -- "
                + "idempotent by construction, matched by name_th + emptiness, never by a "
                + "hardcoded division_id")
            .isZero();
        assertThat(divisionExists(canonical)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // End-to-end: replay the exact six-row shape measured against production 2026-08-16 and
    // confirm precisely which rows this migration deletes and which it deliberately leaves alone.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void fullProductionShape_deletesExactlyTheDocumentedTwoRows_andSurvivorsMatch() {
        // division_id 38 equivalent: canonical QC&ISO, 2 employees + 1 department.
        long canonicalQc = insertDivision("QC", "QC&ISO");
        insertEmployeeForDivision("V149-PS-E1", canonicalQc);
        insertEmployeeForDivision("V149-PS-E2", canonicalQc);
        insertDepartmentForDivision("V149-PS-D1", canonicalQc);
        // division_id 18 equivalent: dead QC&ISO duplicate.
        long deadQcDuplicate = insertDivision("0009", "QC&ISO");
        // division_id 6 equivalent: Sales Support 1, inserted first (lower id).
        long salesSupport1Keeper = insertDivision("0011", "Sales Support 1");
        // division_id 12 equivalent: Sales Support 1 duplicate, inserted second (higher id).
        long salesSupport1Duplicate = insertDivision("0013", "Sales Support 1");
        // division_id 8 equivalent: Sales Support 2, single space.
        long salesSupport2 = insertDivision("0014", "Sales Support 2");
        // division_id 13 equivalent: Sales Support  2, double space.
        long salesSupportDoubleSpace2 = insertDivision("0012", "Sales Support  2");

        int deleted = executeV149();

        assertThat(deleted)
            .as("exactly two of the six rows qualify for deletion: the QC&ISO duplicate and the "
                + "losing half of the Sales Support 1 pair")
            .isEqualTo(2);

        assertThat(divisionExists(canonicalQc)).as("canonical QC&ISO survives untouched").isTrue();
        assertThat(divisionExists(deadQcDuplicate)).as("dead QC&ISO duplicate is deleted").isFalse();
        assertThat(divisionExists(salesSupport1Keeper))
            .as("Sales Support 1's deterministic keeper (lowest division_id) survives")
            .isTrue();
        assertThat(divisionExists(salesSupport1Duplicate))
            .as("Sales Support 1's losing duplicate is deleted")
            .isFalse();
        assertThat(divisionExists(salesSupport2))
            .as("Sales Support 2 (single space) has no exact-name partner and survives")
            .isTrue();
        assertThat(divisionExists(salesSupportDoubleSpace2))
            .as("Sales Support  2 (double space) has no exact-name partner and survives")
            .isTrue();

        int secondRun = executeV149();
        assertThat(secondRun)
            .as("re-running against the post-migration shape deletes 0 rows")
            .isZero();
    }

    // ── running V149's own SQL, read live off the classpath ─────────────────────────────────────

    /**
     * Executes V149's real file contents against the test database and returns the DELETE's
     * affected-row count. Deliberately NOT a copy of the SQL pasted into this test -- a pasted
     * copy would drift from the real file the moment either one changed, and a test asserting
     * against its own stale copy of a migration proves nothing about the migration.
     */
    private int executeV149() {
        String sql = readV149Sql();
        return jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        });
    }

    private static String readV149Sql() {
        try (InputStream in = DivisionDedupeIntegrationTest.class
                .getClassLoader().getResourceAsStream(V149_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "classpath resource not found: " + V149_RESOURCE + " -- is db/migration on "
                    + "the test classpath, and has V149 been renamed? This test reads V149's SQL "
                    + "live rather than pasting a copy, so a rename here must be matched by a "
                    + "rename of this constant.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private boolean divisionExists(long divisionId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.division WHERE division_id = :id",
            new MapSqlParameterSource().addValue("id", divisionId), Integer.class);
        return count != null && count > 0;
    }

    private long insertDivision(String sourceCode, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """,
            new MapSqlParameterSource().addValue("code", sourceCode).addValue("name", nameTh),
            Long.class);
    }

    private long insertDepartmentForDivision(String sourceCode, long divisionId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, division_id, is_active)
            VALUES (:code, :code, :divisionId, TRUE) RETURNING department_id
            """,
            new MapSqlParameterSource().addValue("code", sourceCode).addValue("divisionId", divisionId),
            Long.class);
    }

    /** {@code divisionId} may be null -- used to attach an employee without also touching guard 1. */
    private long insertEmployeeForDivision(String employeeCode, Long divisionId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, DATE '2020-01-01', TRUE)
            RETURNING employee_id
            """,
            new MapSqlParameterSource().addValue("code", employeeCode).addValue("divisionId", divisionId),
            Long.class);
    }

    /**
     * Creates exactly one {@code hr.employee_assignment} row scoped to {@code divisionId}. The
     * backing employee's OWN {@code division_id} is left NULL so this fixture exercises guard 3
     * alone, never incidentally also satisfying guard 1.
     */
    private long insertAssignmentForDivision(long divisionId) {
        long employeeId = insertEmployeeForDivision("V149-ASG-" + divisionId, null);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee_assignment (employee_id, division_id, effective_from, is_current)
            VALUES (:employeeId, :divisionId, DATE '2020-01-01', TRUE)
            RETURNING assignment_id
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("divisionId", divisionId),
            Long.class);
    }

    /** Creates exactly one DIVISION-scope {@code hr.work_schedule_assignment} row for {@code divisionId}. */
    private void insertWorkScheduleAssignmentForDivision(long divisionId) {
        Long workScheduleId = jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule ORDER BY work_schedule_id LIMIT 1",
            new MapSqlParameterSource(), Long.class);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from)
            VALUES ('DIVISION', :divisionId, :workScheduleId, DATE '2024-10-01')
            """,
            new MapSqlParameterSource()
                .addValue("divisionId", divisionId)
                .addValue("workScheduleId", workScheduleId));
    }
}
