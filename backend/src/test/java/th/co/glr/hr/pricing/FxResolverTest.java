package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/** Fast, DB-free unit tests for FxResolver — mirrors PricingFormulaEngineTest's convention (no Spring context,
 * no Postgres: FxRateRepository is mocked, since FxResolver#resolve takes it as an explicit argument and never
 * constructs one itself). */
class FxResolverTest {
    private static final String CURRENCY = "EUR";

    private final FxRateRepository fxRates = mock(FxRateRepository.class);

    /** CLAUDE.md's testing rule: assert both sides (MANUAL and BOT) on ONE fixture (same currency, same stale
     * date), rather than two independent tests that could each be individually broken without the other catching
     * it. */
    @Test
    void staleRate_refusedForBothManualAndBotSources_sameFixtureExceptSource() {
        LocalDate staleDate = LocalDate.now().minusDays(8);

        // derive the expected substring from FxResolver.MAX_RATE_AGE_DAYS rather than hardcoding "7", so this
        // assertion and the production message cannot silently drift apart the way they used to (the constant changing
        // used to leave a stale "7" behind).
        String staleWording = "เก่าเกิน " + FxResolver.MAX_RATE_AGE_DAYS + " วัน";

        FxRateDto staleManual = fxRate(CURRENCY, "MANUAL", staleDate, null);
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(staleManual));
        assertThatThrownBy(() -> FxResolver.resolve(fxRates, CURRENCY))
            .as("a MANUAL rate 8 days old must still be refused — the staleness guard, not the "
                + "BOT-source guard, is what protects the money")
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains(CURRENCY).contains(staleWording)
                    .contains(staleDate.toString())
                    .contains("ตั้งค่า CEO", "อัตราแลกเปลี่ยน");
            });

        FxRateDto staleBot = fxRate(CURRENCY, "BOT", staleDate, Instant.now());
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(staleBot));
        assertThatThrownBy(() -> FxResolver.resolve(fxRates, CURRENCY))
            .as("a BOT rate 8 days old must ALSO still be refused — staleness is source-agnostic")
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains(CURRENCY).contains(staleWording)
                    .contains(staleDate.toString());
            });
    }

    /** The band edge: exactly 7 days old is NOT yet stale (the guard is "more than 7 days", i.e. isBefore(now - 7),
     * not isBefore(now - 6)) — pins the boundary precisely so a future off-by-one does not silently tighten or
     * loosen the window. */
    @Test
    void rateExactlySevenDaysOld_isStillAccepted_boundaryIsMoreThanSevenNotSevenOrMore() {
        FxRateDto exactlyAWeekOld = fxRate(CURRENCY, "MANUAL", LocalDate.now().minusDays(7), null);
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(exactlyAWeekOld));

        assertThat(FxResolver.resolve(fxRates, CURRENCY).source()).isEqualTo("MANUAL");
    }

    private FxRateDto fxRate(String currency, String source, LocalDate effectiveDate, Instant fetchedAt) {
        return new FxRateDto(1L, currency, new BigDecimal("38.5000"), effectiveDate, Instant.now(), source, fetchedAt);
    }
}
