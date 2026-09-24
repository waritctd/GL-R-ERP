package th.co.glr.hr.billing;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S6 (Opus review, GLA-99 step 3 round 2) — {@link BillingNoteDraftRequest#lines} carried NO
 * {@code @Valid}, so every nested constraint on {@link BillingNoteLineSelection} (the {@code
 * @Size(max = 30)} on {@code manualDocNumber}, the {@code @DecimalMin} on {@code manualAmount},
 * etc.) was dead at the bean-validation layer — a 31+ char manual doc number sailed straight past
 * {@code @Valid @RequestBody} at the controller and only failed later, at the DB's own {@code
 * VARCHAR(30)} column, where (before the OTHER half of S6, see {@code
 * BillingNoteServiceIntegrationTest#s6_...}) it was misreported as a double-billing conflict. Runs
 * the SAME {@code jakarta.validation} validator {@code @Valid @RequestBody} applies at the
 * controller, without a Spring context — same pattern as {@code
 * dealquotation/DealQuotationRequestsValidationTest}.
 */
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
        // Sanity check that the top-level (non-nested) constraints still work — @Valid on `lines`
        // is additive, not a replacement for the request's own field-level annotations.
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null,
            "X".repeat(2001), List.of());
        assertThat(VALIDATOR.validate(req)).isNotEmpty();
    }
}
