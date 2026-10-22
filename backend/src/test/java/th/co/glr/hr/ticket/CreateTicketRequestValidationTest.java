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

/**
 * The API-boundary half of "a new deal must state a real ช่องทางดีล" (owner ruling 2026-09-30).
 *
 * <p>{@code TicketController.create} takes {@code @Valid @RequestBody CreateTicketRequest}, so a
 * violation here is a 400 before {@link TicketService#create} is reached. The service deliberately
 * still tolerates a null channel (see {@code TicketServiceTest#create_stillToleratesNullEntryChannelAtServiceLevel}),
 * which is why the absent/blank rule lives on the DTO and not in the service.
 *
 * <p>There is no {@code TicketController} MVC test in this repo to extend; this follows
 * {@code LoginRequestNormalizationTest}'s pattern of running the real {@code Validator} over the
 * real record, which is exactly what {@code @Valid} does at request time.
 */
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

    /**
     * The asymmetry is the design, not an oversight: {@code @NotBlank} only asks "was a channel
     * sent?", and {@code UNSPECIFIED} is a non-blank string, so it passes here. Refusing it is the
     * SERVICE's job ({@code TicketServiceTest#create_rejectsUnspecifiedEntryChannel}) because the
     * value check belongs next to {@link EntryChannel}. Do not "fix" this by adding a pattern to the
     * DTO without moving that test with it.
     */
    @Test
    void unspecifiedPassesBeanValidationBecauseTheServiceOwnsThatRefusal() {
        assertThat(violatedFields(withChannel(EntryChannel.UNSPECIFIED))).isEmpty();
    }
}
