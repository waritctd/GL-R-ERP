package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LoginRequest}'s constructor contract, and — the part that actually matters — pins it
 * <b>against the same {@code @Email} validator {@code AuthController} applies</b>.
 *
 * <p>The bug this guards was an ordering bug, not a matching bug. {@code AuthController#login} is
 * {@code @Valid}, so bean validation runs on the constructed record, whereas {@code
 * AuthService#login} only called {@code .trim()} after that point. A pasted or phone-autocorrected
 * address carrying one stray space therefore failed {@code @Email} and returned 400 without the
 * lookup ever running. {@link #theEmailValidatorRejectsTheUntrimmedFormItWouldHaveSeenBefore} is the
 * case that shows the trim is load-bearing rather than cosmetic: it runs the real validator over the
 * raw string and asserts it is refused, so if someone removes the constructor's trim the neighbouring
 * test goes red for a demonstrable reason.
 */
class LoginRequestNormalizationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @Test
    void lowercasesTheEmailSoEveryCasingCanonicalisesToOneForm() {
        assertThat(new LoginRequest("Suneesllim.1977@gmail.com", "pw", null).email())
            .isEqualTo("suneesllim.1977@gmail.com");
        assertThat(new LoginRequest("SUNEESLLIM.1977@GMAIL.COM", "pw", null).email())
            .isEqualTo("suneesllim.1977@gmail.com");
        assertThat(new LoginRequest("SuNeEsLlIm.1977@GmAiL.cOm", "pw", null).email())
            .isEqualTo("suneesllim.1977@gmail.com");
    }

    @Test
    void trimsWhitespaceThatWouldOtherwiseFailValidationBeforeTheLookupRuns() {
        assertThat(new LoginRequest("  somchai@glr.co.th  ", "pw", null).email())
            .isEqualTo("somchai@glr.co.th");
        assertThat(new LoginRequest("\tsomchai@glr.co.th\n", "pw", null).email())
            .isEqualTo("somchai@glr.co.th");
    }

    /**
     * The counterfactual. Without the constructor's trim this is the exact value {@code @Email}
     * would have been handed, so this test states — rather than assumes — why the trim has to happen
     * inside the constructor and not in the service below it.
     */
    @Test
    void theEmailValidatorRejectsTheUntrimmedFormItWouldHaveSeenBefore() {
        assertThat(VALIDATOR.validate(new UnnormalizedLogin(" somchai@glr.co.th"))).isNotEmpty();
        assertThat(VALIDATOR.validate(new UnnormalizedLogin("somchai@glr.co.th "))).isNotEmpty();
        // ...and accepts it once trimmed, in any casing — so the fix is sufficient, not just necessary.
        assertThat(VALIDATOR.validate(new UnnormalizedLogin("somchai@glr.co.th"))).isEmpty();
        assertThat(VALIDATOR.validate(new UnnormalizedLogin("Somchai@GLR.co.th"))).isEmpty();
    }

    @Test
    void appliesTheSameValidatorToTheRealRecordAndPasses() {
        // The whole point, end to end: the untrimmed input above is refused, and the record built
        // from that same input is accepted.
        assertThat(VALIDATOR.validate(new LoginRequest(" Somchai@GLR.co.th ", "pw", null))).isEmpty();
    }

    @Test
    void leavesThePasswordExactlyAsTyped() {
        // Spaces are legitimate password characters. Trimming them alongside the email would lock
        // out anyone whose password has one — a worse failure than the one being fixed.
        assertThat(new LoginRequest("a@b.co", "  spaced pw  ", null).password())
            .isEqualTo("  spaced pw  ");
    }

    @Test
    void toleratesANullEmailRatherThanThrowing() {
        // AuthService builds `new LoginRequest(null, null, null)` for a null body before it rejects
        // the request, so the constructor must survive nulls.
        assertThat(new LoginRequest(null, null, null).email()).isNull();
    }

    /** Mirrors {@link LoginRequest}'s field constraints with no normalisation, for the counterfactual. */
    private record UnnormalizedLogin(
        @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 254) String email) {
    }
}
