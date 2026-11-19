package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollRepository.DraftRowVersion;

/** Pure-function unit coverage for PayrollDraftETag -- the "does the decision hash the right thing" half,
 * mirroring how a resolveScope-style test proves an authz decision. */
class PayrollDraftETagTest {

    @Test
    void changingOneRowsVersionChangesTheToken() {
        String before = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 3)));
        String after = PayrollDraftETag.compute(
            List.of(new DraftRowVersion(101L, 1L, 0), new DraftRowVersion(102L, 2L, 4)));

        assertThat(after).isNotEqualTo(before);
    }

}
