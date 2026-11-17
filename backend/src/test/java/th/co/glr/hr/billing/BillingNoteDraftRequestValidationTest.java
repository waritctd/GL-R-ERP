package th.co.glr.hr.billing;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies nested billing-note lines are bean-validated before invalid values reach database constraints. */
class BillingNoteDraftRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @Test
    void validRequest_hasNoViolations() {
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), "ครบกำหนด 30 วัน", "note",
            List.of(new BillingNoteLineSelection("MANUAL", null, "INV-1", LocalDate.now(), null,
                new BigDecimal("10.00"), null)));
        assertThat(VALIDATOR.validate(req)).isEmpty();
    }

    @Test
    void manualDocNumberOverMaxLength_isRejected_nowThatLinesIsValid() {
        String tooLong = "X".repeat(31);
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
            List.of(new BillingNoteLineSelection("MANUAL", null, tooLong, LocalDate.now(), null,
                new BigDecimal("10.00"), null)));
        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }

    @Test
    void manualAmountNotPositive_isRejected_nowThatLinesIsValid() {
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
            List.of(new BillingNoteLineSelection("MANUAL", null, "INV-1", LocalDate.now(), null,
                BigDecimal.ZERO, null)));
        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }

    @Test
    void noteOverMaxLength_isStillRejected_onTheRequestItselfNotJustLines() {
        // Sanity check that the top-level (non-nested) constraints still work — @Valid on `lines` is additive, not a
        // replacement for the request's own field-level annotations.
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null,
            "X".repeat(2001), List.of());
        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }
}
