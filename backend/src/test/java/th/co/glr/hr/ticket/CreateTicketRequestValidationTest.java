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

/** The API-boundary validation of CreateTicketRequest. TicketController.create takes @Valid @RequestBody, so a
 * violation here is a 400 before TicketService#create is reached. The 2026-09-30 "must state a real channel" rule
 * was relaxed by the owner-approved short create form (2026-11-16): an absent channel is stored UNSPECIFIED. */
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

    // Owner-approved สร้างงานใหม่ drawing (2026-11-16): the short form does not ask for a channel, so an absent one is
    // no longer refused at the boundary. It is stored UNSPECIFIED ("not stated", V144) and stated later on the deal
    // page. These three tests used to pin the opposite ("...IsRejected"); see DealShortCreateIntegrationTest.
    @Test
    void absentEntryChannelPassesValidation() {
        assertThat(violatedFields(withChannel(null))).isEmpty();
    }

    @Test
    void emptyEntryChannelPassesValidation() {
        assertThat(violatedFields(withChannel(""))).isEmpty();
    }

    @Test
    void whitespaceOnlyEntryChannelPassesValidation() {
        assertThat(violatedFields(withChannel("   "))).isEmpty();
    }

    @Test
    void negativeEstimatedValueIsRejected() {
        CreateTicketRequest request = new CreateTicketRequest("Test deal", "NORMAL", "ลูกค้า", null, 77L, null, null,
            null, List.of(), null, null, null, null, new java.math.BigDecimal("-1"), null);
        assertThat(violatedFields(request)).containsExactly("estimatedValue");
    }

    @Test
    void overlongDesignerNameIsRejected() {
        CreateTicketRequest request = new CreateTicketRequest("Test deal", "NORMAL", "ลูกค้า", null, 77L, null, null,
            null, List.of(), null, null, null, "ก".repeat(201), null, null);
        assertThat(violatedFields(request)).containsExactly("designerName");
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
}
