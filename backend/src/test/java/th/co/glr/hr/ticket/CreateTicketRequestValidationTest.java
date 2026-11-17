package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The API-boundary half of "a new deal must state a real ช่องทางดีล" (owner ruling 2026-09-30).
 * TicketController.create takes @Valid @RequestBody CreateTicketRequest, so a violation here is a 400 before
 * TicketService#create is reached. */
class CreateTicketRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private static CreateTicketRequest withChannel(String channel) {
        return new CreateTicketRequest("Test deal", "NORMAL", "ลูกค้า", null, 77L, null, null,
            channel, List.of());
    }

    private static Set<String> violatedFields(CreateTicketRequest request) {
        return VALIDATOR.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(Collectors.toSet());
    }

    @Test
    void absentEntryChannelIsRejected() {
        assertThat(violatedFields(withChannel(null))).containsExactly("entryChannel");
    }

    @Test
    void emptyEntryChannelIsRejected() {
        assertThat(violatedFields(withChannel(""))).containsExactly("entryChannel");
    }

    @Test
    void whitespaceOnlyEntryChannelIsRejected() {
        assertThat(violatedFields(withChannel("   "))).containsExactly("entryChannel");
    }

    @Test
    void eachRealChannelPassesValidation() {
        for (String channel : List.of(EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT,
                EntryChannel.BUYER_DIRECT)) {
            assertThat(violatedFields(withChannel(channel))).as(channel).isEmpty();
        }
    }

    /** The asymmetry is the design, not an oversight: @NotBlank only asks "was a channel sent?", and UNSPECIFIED is
     * a non-blank string, so it passes here. Do not "fix" this by adding a pattern to the DTO without moving that
     * test with it. */
    @Test
    void unspecifiedPassesBeanValidationBecauseTheServiceOwnsThatRefusal() {
        assertThat(violatedFields(withChannel(EntryChannel.UNSPECIFIED))).isEmpty();
    }

    // The short create form's optional fields (2026-11-16; see DealLifecycleDialogsIntegrationTest). A channel is
    // stated in each, so the only violation is the field under test.
    private static CreateTicketRequest shortForm(String designerName, java.math.BigDecimal estimatedValue) {
        return new CreateTicketRequest("Test deal", "NORMAL", "ลูกค้า", null, 77L, null, null,
            EntryChannel.DESIGNER_LED, List.of(), null, null, null, designerName, estimatedValue, null);
    }

    @Test
    void anEstimateMustBePositive() {
        assertThat(violatedFields(shortForm(null, new java.math.BigDecimal("-1")))).containsExactly("estimatedValue");
        assertThat(violatedFields(shortForm(null, java.math.BigDecimal.ZERO))).containsExactly("estimatedValue");
        assertThat(violatedFields(shortForm(null, new java.math.BigDecimal("0.01")))).isEmpty();
    }

    /** The ceiling is the column's: NUMERIC(14,2), the same precision as imported_value (V215). */
    @Test
    void anEstimateMustFitTheColumn() {
        assertThat(violatedFields(shortForm(null, new java.math.BigDecimal("999999999999.99")))).isEmpty();
        assertThat(violatedFields(shortForm(null, new java.math.BigDecimal("1000000000000")))).containsExactly("estimatedValue");
        assertThat(violatedFields(shortForm(null, new java.math.BigDecimal("1.005")))).containsExactly("estimatedValue");
    }

    @Test
    void anOverlongDesignerNameIsRejected() {
        assertThat(violatedFields(shortForm("ก".repeat(201), null))).containsExactly("designerName");
    }
}
