package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollRepository.DraftRowVersion;

/** Pure-function unit coverage for PayrollDraftETag -- the "does the decision hash the right thing" half,
 * mirroring how a resolveScope-style test proves an authz decision. */
class PayrollDraftETagTest {

    @Test
    void emptyRowSetHasAWellDefinedNonBlankToken() {
        String token = PayrollDraftETag.compute(List.of());

        assertThat(token).isEqualTo(PayrollDraftETag.EMPTY);
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void sameRowSetProducesTheSameTokenAcrossTwoComputations() {
        List<DraftRowVersion> rows = List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 3));

        assertThat(PayrollDraftETag.compute(rows)).isEqualTo(PayrollDraftETag.compute(rows));
    }

    @Test
    void changingOneRowsVersionChangesTheToken() {
        String before = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 3)));
        String after = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 4)));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void addingARowChangesTheToken() {
        String before = PayrollDraftETag.compute(List.of(new DraftRowVersion(101L, 1L, 0)));
        String after = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 0)));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void removingARowChangesTheToken() {
        String before = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 0)));
        String after = PayrollDraftETag.compute(List.of(new DraftRowVersion(101L, 1L, 0)));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void goingFromNonEmptyBackToEmptyReturnsTheSameWellDefinedEmptyToken() {
        String populated = PayrollDraftETag.compute(List.of(new DraftRowVersion(101L, 1L, 0)));
        String emptied = PayrollDraftETag.compute(List.of());

        assertThat(emptied).isEqualTo(PayrollDraftETag.EMPTY).isNotEqualTo(populated);
    }

    @Test
    void tokenIsSensitiveToWhichEmployeeHoldsWhichVersionNotJustTheMultiset() {
        // Same two version VALUES {0, 1}, but swapped between employee 1 and 2 -- must not collide.
        String first = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 1)));
        String second = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 1), new DraftRowVersion(102L, 2L, 0)));

        assertThat(first).isNotEqualTo(second);
    }

    /** Opus review finding F1 (ABA hazard): the SAME (employeeId, version) pairs, but different draftIds -- exactly
     * what a delete-and-reinsert (process() clearing a month's drafts, then a fresh save landing back at version
     * 0) produces. */
    @Test
    void sameEmployeeAndVersionPairsButDifferentDraftIdsProduceDifferentTokens() {
        String beforeProcess = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 0)));
        // After process() deleted rows 101/102 and HR re-saved: brand-new draft_ids (203/204, an IDENTITY sequence
        // never reuses a value), but versions land back at 0 either way.
        String afterReinsert = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(203L, 1L, 0), new DraftRowVersion(204L, 2L, 0)));

        assertThat(afterReinsert)
            .as("a delete-and-reinsert cycle must not silently reproduce an earlier token")
            .isNotEqualTo(beforeProcess);
    }

    /** Opus review finding F6: a two-row set where digit boundaries could plausibly collide if the fields were
     * concatenated without a real separator -- draftId=1/employeeId=23 vs draftId=12/employeeId=3 both stringify
     * to "123" if simply glued together. */
    @Test
    void twoDigitIdsThatWouldCollideIfFieldsWereUnseparatedProduceDifferentTokens() {
        String first = PayrollDraftETag.compute(List.of(new DraftRowVersion(1L, 23L, 0)));
        String second = PayrollDraftETag.compute(List.of(new DraftRowVersion(12L, 3L, 0)));

        assertThat(first).isNotEqualTo(second);
    }
}
