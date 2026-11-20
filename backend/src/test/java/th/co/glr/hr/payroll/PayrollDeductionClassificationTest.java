package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Table test for issue #376's classification table -- every PayrollDeductionKind the engine supports must have
 * an Entry, and the entry must match the legal position the issue settles: กยศ. */
class PayrollDeductionClassificationTest {

    @Test
    void everyDeductionKindHasAClassificationEntry() {
        for (PayrollDeductionKind kind : PayrollDeductionKind.values()) {
            assertThat(PayrollDeductionClassification.classify(kind)).isNotNull();
        }
        assertThat(PayrollDeductionClassification.all()).hasSize(PayrollDeductionKind.values().length);
    }

}
