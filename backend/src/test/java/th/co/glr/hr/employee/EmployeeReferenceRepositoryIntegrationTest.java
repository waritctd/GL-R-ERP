package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB coverage for {@code EmployeeReferenceRepository#findOrInsertDivisionByName}, reached
 * through the public {@code ensureDivision(sourceCode, name)} with a blank/null {@code
 * sourceCode} -- the same path {@code EmployeeRepository.create()/update()} drive from the
 * hr-gated {@code POST /api/employees} and {@code PATCH /api/employees/{id}}. Mockito cannot cover
 * this: the behaviour under test IS the SQL's deterministic row pick and its {@code ORDER BY},
 * which a mocked repository never runs.
 *
 * <p>See {@link EmployeeReferenceRepository}'s javadoc on that method for the owner's ruling this
 * class verifies: reuse an existing same-name row's {@code source_code}, preferring a CODED row
 * over a blank one and tie-breaking on the LOWEST {@code division_id} -- and that a genuinely new
 * name still inserts a codeless row, a documented, NOT fully closed, remaining gap.
 */
class EmployeeReferenceRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private EmployeeReferenceRepository references;

    @BeforeEach
    void wireRepository() {
        references = new EmployeeReferenceRepository(jdbc);
    }

    @Test
    void nameMatchingAnExistingCodedRow_reusesThatSourceCode() {
        insertDivision("ฝ่ายขาย", "SA");

        Long divisionId = references.ensureDivision(null, "ฝ่ายขาย");

        assertThat(sourceCodeOf(divisionId)).isEqualTo("SA");
    }

    @Test
    void duplicateNamesWithDifferentCodes_lowestDivisionIdWinsDeterministically() {
        // Mirrors a real production shape: "Sales Support 1" carries both source_code '0011' and
        // '0013' on two separate rows. Both codes stay non-blank and different from each other
        // for the whole test, so the code-preference sort key always ties between the two rows
        // and ONLY `division_id ASC` can decide the winner -- if it could not, this test would
        // prove nothing about that half of the ORDER BY.
        //
        // Insertion order alone is NOT a real adversary for `division_id ASC`: on a freshly
        // inserted, never-updated table, a sequential scan happens to return rows in insertion
        // order, which is ALSO division_id order here -- so even a plain unordered `LIMIT 1`
        // would land on the right row by coincidence, and a silently-removed `division_id ASC`
        // would go undetected. To make physical scan order diverge from division_id order, the
        // LOWER-id row is UPDATED after both inserts, changing its UNIQUE-indexed source_code (so
        // this cannot be a no-op HOT update that leaves its heap position untouched): Postgres
        // MVCC writes the update as a brand-new heap tuple, physically placed AFTER the
        // higher-id row's never-touched tuple, so an unordered scan now reaches the HIGHER-id row
        // first. The fix's `division_id ASC` must still correctly return the LOWER-id row despite
        // that -- confirmed by mutation check: removing only `division_id ASC` from the ORDER BY
        // (leaving the code-preference term) turns this specific test red.
        long lowerId = insertDivision("Sales Support 1", "0011");
        long higherId = insertDivision("Sales Support 1", "0013");
        assertThat(lowerId).isLessThan(higherId);
        updateSourceCode(lowerId, "0011R"); // still non-blank, still distinct from '0013'

        Long divisionId = references.ensureDivision(null, "Sales Support 1");

        assertThat(divisionId).isEqualTo(lowerId);
        assertThat(sourceCodeOf(divisionId)).isEqualTo("0011R");
    }

    @Test
    void duplicateNamesOneBlankOneCoded_theCodedRowWins() {
        // The blank row is inserted FIRST (so it gets the LOWER division_id) and the coded row
        // SECOND, deliberately: this proves the "prefer a coded row" rule outranks the "lowest
        // division_id" tie-break, not merely that the lowest id happens to also be coded.
        insertDivision("QC&ISO", null);
        long codedId = insertDivision("QC&ISO", "QC");

        Long divisionId = references.ensureDivision(null, "QC&ISO");

        assertThat(divisionId).isEqualTo(codedId);
        assertThat(sourceCodeOf(divisionId)).isEqualTo("QC");
    }

    @Test
    void genuinelyNewName_stillInsertsWithNullSourceCode_knownRemainingGap() {
        // KNOWN GAP, asserted rather than left untested: a name matching no existing row has
        // nothing to reuse a source_code from, so it inserts codeless -- exactly the shape V146
        // cleaned up, and this change does not claim to close. See the repository's javadoc.
        Long divisionId = references.ensureDivision(null, "แผนกทดสอบที่ไม่เคยมีมาก่อน");

        assertThat(divisionId).isNotNull();
        assertThat(sourceCodeOf(divisionId)).isNull();
    }

    @Test
    void wrongWayRound_codedSiblingExists_resultIsNeverLeftCodeless() {
        // Worst case for a naive "first row wins" / unordered LIMIT 1: TWO blank rows are
        // inserted before the coded row, so both a plain insertion-order scan and a lowest-id
        // tie-break with no code-preference would land on a blank row. The fix must still resolve
        // to the coded one -- the resulting division must never be left codeless when a coded
        // same-name sibling exists.
        insertDivision("ผู้บริหาร", null);
        insertDivision("ผู้บริหาร", null);
        long codedId = insertDivision("ผู้บริหาร", "MN");

        Long divisionId = references.ensureDivision(null, "ผู้บริหาร");

        assertThat(divisionId).isEqualTo(codedId);
        String code = sourceCodeOf(divisionId);
        assertThat(code).as("must not resolve to a codeless division when a coded sibling exists").isNotBlank();
        assertThat(code).isEqualTo("MN");
    }

    private long insertDivision(String nameTh, String sourceCode) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.division(source_code, name_th, is_active)
            VALUES (:sourceCode, :name, TRUE)
            RETURNING division_id
            """, new MapSqlParameterSource().addValue("sourceCode", sourceCode).addValue("name", nameTh), Long.class);
        return id;
    }

    private String sourceCodeOf(long divisionId) {
        return jdbc.queryForObject("SELECT source_code FROM hr.division WHERE division_id = :id",
            Map.of("id", divisionId), String.class);
    }

    /**
     * Forces a real (non-HOT) tuple rewrite of an existing row: {@code source_code} carries a
     * UNIQUE index, so changing its value cannot take the same-page/same-slot HOT-update shortcut
     * that would leave the row's position in a sequential scan unchanged. See the caller for why
     * this matters -- it is what makes physical scan order diverge from {@code division_id} order.
     */
    private void updateSourceCode(long divisionId, String sourceCode) {
        jdbc.update("UPDATE hr.division SET source_code = :sourceCode WHERE division_id = :id",
            new MapSqlParameterSource().addValue("sourceCode", sourceCode).addValue("id", divisionId));
    }
}
