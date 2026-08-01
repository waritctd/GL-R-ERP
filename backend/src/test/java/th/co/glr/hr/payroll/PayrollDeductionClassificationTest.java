package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollDeductionClassification.Entry;

/**
 * Table test for issue #376's classification table -- every {@link PayrollDeductionKind} the
 * engine supports must have an {@link Entry}, and the entry must match the legal position the issue
 * settles: กยศ. is {@code STATUTORY_AUTHORITY_DIRECTED}/{@code NONE} (NOT ม.76-capped -- the
 * corrected position, see issue #373/#376), and the ป.วิ.พ. ม.302 garnishment is the one deduction
 * classified {@code LED_GARNISHMENT} (already enforced, reused rather than duplicated).
 */
class PayrollDeductionClassificationTest {

    @Test
    void everyDeductionKindHasAClassificationEntry() {
        for (PayrollDeductionKind kind : PayrollDeductionKind.values()) {
            assertThat(PayrollDeductionClassification.classify(kind)).isNotNull();
        }
        assertThat(PayrollDeductionClassification.all()).hasSize(PayrollDeductionKind.values().length);
    }

    @Test
    void everyEntryCitesALegalBasis() {
        for (PayrollDeductionKind kind : PayrollDeductionKind.values()) {
            Entry entry = PayrollDeductionClassification.classify(kind);
            assertThat(entry.legalBasis()).isNotBlank();
        }
    }

    @Test
    void anEntryWithAnyNullFieldCarriesANeedsReviewReason() {
        for (PayrollDeductionKind kind : PayrollDeductionKind.values()) {
            Entry entry = PayrollDeductionClassification.classify(kind);
            if (entry.ruleClass() == null || entry.capGroup() == null) {
                assertThat(entry.needsReviewReason())
                    .as("kind %s has a null field but no needsReviewReason", kind)
                    .isNotBlank();
            } else {
                assertThat(entry.needsReviewReason())
                    .as("kind %s is fully classified but still carries a needsReviewReason", kind)
                    .isNull();
            }
        }
    }

    @Test
    void studentLoanIsStatutoryWithNoLabourActCap() {
        // The exact correction #376 exists to encode: item (1) territory, NOT ม.76-capped.
        Entry entry = PayrollDeductionClassification.classify(PayrollDeductionKind.STUDENT_LOAN);
        assertThat(entry.ruleClass()).isEqualTo(DeductionRuleClass.STATUTORY_AUTHORITY_DIRECTED);
        assertThat(entry.capGroup()).isEqualTo(DeductionCapGroup.NONE);
        assertThat(entry.fullyClassified()).isTrue();
    }

    @Test
    void withholdingTaxAndSocialSecurityAreAlsoItemOneStatutoryWithNoCap() {
        for (PayrollDeductionKind kind : new PayrollDeductionKind[] {
            PayrollDeductionKind.WITHHOLDING_TAX, PayrollDeductionKind.SOCIAL_SECURITY
        }) {
            Entry entry = PayrollDeductionClassification.classify(kind);
            assertThat(entry.ruleClass()).isEqualTo(DeductionRuleClass.STATUTORY_AUTHORITY_DIRECTED);
            assertThat(entry.capGroup()).isEqualTo(DeductionCapGroup.NONE);
        }
    }

    @Test
    void legalExecutionGarnishmentReusesTheAlreadyEnforcedLedCap() {
        Entry entry = PayrollDeductionClassification.classify(PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT);
        assertThat(entry.ruleClass()).isEqualTo(DeductionRuleClass.COURT_OR_LED_ORDER);
        assertThat(entry.capGroup()).isEqualTo(DeductionCapGroup.LED_GARNISHMENT);
        assertThat(entry.fullyClassified()).isTrue();
    }

    @Test
    void warningLetterAndCustomerReturnAreInternalRecoveryWithCapGroupNeedingReview() {
        for (PayrollDeductionKind kind : new PayrollDeductionKind[] {
            PayrollDeductionKind.WARNING_LETTER, PayrollDeductionKind.CUSTOMER_RETURN
        }) {
            Entry entry = PayrollDeductionClassification.classify(kind);
            assertThat(entry.ruleClass()).isEqualTo(DeductionRuleClass.INTERNAL_RECOVERY);
            assertThat(entry.capGroup()).isNull();
            assertThat(entry.needsReviewReason()).isNotBlank();
            assertThat(entry.fullyClassified()).isFalse();
        }
    }

    @Test
    void theTwoFreeTypedCatchAllsAreLeftEntirelyUnclassifiedRatherThanGuessed() {
        for (PayrollDeductionKind kind : new PayrollDeductionKind[] {
            PayrollDeductionKind.OTHER_PRETAX, PayrollDeductionKind.OTHER_POST_TAX
        }) {
            Entry entry = PayrollDeductionClassification.classify(kind);
            assertThat(entry.ruleClass()).isNull();
            assertThat(entry.capGroup()).isNull();
            assertThat(entry.needsReviewReason()).isNotBlank();
        }
    }

    @Test
    void constructingAnEntryWithANullFieldAndNoReasonIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new Entry(null, null, "some basis", null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructingAnEntryWithNoLegalBasisIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new Entry(DeductionRuleClass.VOLUNTARY, DeductionCapGroup.NONE, "  ", null))
            .isInstanceOf(IllegalStateException.class);
    }
}
