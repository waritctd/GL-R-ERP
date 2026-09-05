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

/**
 * Fast, DB-free unit tests for {@link FxResolver} — mirrors {@code PricingFormulaEngineTest}'s
 * convention (no Spring context, no Postgres: {@link FxRateRepository} is mocked, since {@link
 * FxResolver#resolve} takes it as an explicit argument and never constructs one itself).
 *
 * <p>Issue P0: before this class's fix, {@code resolve} refused ANY non-THB rate unless {@code
 * source} was exactly {@code "BOT"} with a non-null {@code fetchedAt} — a rule that was
 * unreachable in production (see {@link FxResolver}'s class Javadoc), so the CEO's own manual FX
 * entry was guaranteed to be refused. These tests pin the fix: a MANUAL rate is now accepted, the
 * staleness guard still applies to BOTH sources, and every refusal names a fix the CEO can act on.
 * Zero tests referenced {@link FxResolver} or the old BOT message before this file — confirmed by
 * grep across {@code backend/src/test/java} before writing it.
 */
class FxResolverTest {
    private static final String CURRENCY = "EUR";

    private final FxRateRepository fxRates = mock(FxRateRepository.class);

    // ── THB shortcut — unaffected by the P0 change, sanity only ─────────────────────────────

    @Test
    void thb_alwaysResolvesToRateOne_evenWithNoRowOnFile() {
        when(fxRates.findByCurrency("THB")).thenReturn(Optional.empty());
        FxRateDto resolved = FxResolver.resolve(fxRates, "THB");
        assertThat(resolved.rateToThb()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void blankOrNullCurrency_isTreatedAsThb() {
        when(fxRates.findByCurrency("THB")).thenReturn(Optional.empty());
        assertThat(FxResolver.resolve(fxRates, null).currency()).isEqualTo("THB");
        assertThat(FxResolver.resolve(fxRates, "  ").currency()).isEqualTo("THB");
    }

    // ── P0: a MANUAL rate is now accepted (was unconditionally refused before the fix) ──────

    @Test
    void manualNonThbRate_recentEffectiveDate_isNowAccepted() {
        FxRateDto manual = fxRate(CURRENCY, "MANUAL", LocalDate.now(), null);
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(manual));

        FxRateDto resolved = FxResolver.resolve(fxRates, CURRENCY);

        assertThat(resolved.source()).isEqualTo("MANUAL");
        assertThat(resolved.rateToThb()).isEqualByComparingTo(manual.rateToThb());
    }

    /** The direction that must NOT regress: a BOT-sourced rate was accepted before this fix and
     * must stay accepted after it — only the BOT-ONLY requirement was removed, not BOT itself. */
    @Test
    void botNonThbRate_recentEffectiveDate_isStillAccepted() {
        FxRateDto bot = fxRate(CURRENCY, "BOT", LocalDate.now(), Instant.now());
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(bot));

        FxRateDto resolved = FxResolver.resolve(fxRates, CURRENCY);

        assertThat(resolved.source()).isEqualTo("BOT");
    }

    /** Currency matching must be case-insensitive on the way in — the controller/mock both
     * normalise to upper case, but a caller passing lower case must still resolve. */
    @Test
    void currencyLookup_isCaseInsensitiveOnTheWayIn() {
        FxRateDto manual = fxRate(CURRENCY, "MANUAL", LocalDate.now(), null);
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(manual));

        assertThat(FxResolver.resolve(fxRates, "eur").source()).isEqualTo("MANUAL");
    }

    // ── The staleness guard: UNCHANGED, and applies to BOTH sources — the mutation-check target ──

    /**
     * CLAUDE.md's testing rule: assert both sides (MANUAL and BOT) on ONE fixture (same currency,
     * same stale date), rather than two independent tests that could each be individually broken
     * without the other catching it. This is one of the two tests this task's mutation-check
     * targets: breaking the staleness guard must turn THIS test red (both sub-assertions) and
     * nothing else.
     */
    @Test
    void staleRate_refusedForBothManualAndBotSources_sameFixtureExceptSource() {
        LocalDate staleDate = LocalDate.now().minusDays(8);

        // F6 fix: derive the expected substring from FxResolver.MAX_RATE_AGE_DAYS rather than
        // hardcoding "7", so this assertion and the production message cannot silently drift
        // apart the way they used to (the constant changing used to leave a stale "7" behind).
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

    /** The band edge: exactly 7 days old is NOT yet stale (the guard is "more than 7 days", i.e.
     * {@code isBefore(now - 7)}, not {@code isBefore(now - 6)}) — pins the boundary precisely so a
     * future off-by-one does not silently tighten or loosen the window. */
    @Test
    void rateExactlySevenDaysOld_isStillAccepted_boundaryIsMoreThanSevenNotSevenOrMore() {
        FxRateDto exactlyAWeekOld = fxRate(CURRENCY, "MANUAL", LocalDate.now().minusDays(7), null);
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(exactlyAWeekOld));

        assertThat(FxResolver.resolve(fxRates, CURRENCY).source()).isEqualTo("MANUAL");
    }

    @Test
    void nullEffectiveDate_refusedWithItsOwnMessage_neverAttemptingToPrintANullDate() {
        FxRateDto noDate = fxRate(CURRENCY, "MANUAL", null, null);
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.of(noDate));

        assertThatThrownBy(() -> FxResolver.resolve(fxRates, CURRENCY))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage())
                    .as("must not read 'null' as a printed date, and must not share the stale "
                        + "wording (which names an actual date)")
                    .doesNotContain("null")
                    .contains(CURRENCY)
                    .contains("ตั้งค่า CEO", "อัตราแลกเปลี่ยน");
            });
    }

    // ── No row at all ────────────────────────────────────────────────────────────────────────

    @Test
    void missingRateRow_refusedWithActionableMessage_namingTheCeoSettingsPath() {
        when(fxRates.findByCurrency(CURRENCY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> FxResolver.resolve(fxRates, CURRENCY))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage())
                    .as("must name the fix the CEO can actually perform, not the old BOT wording")
                    .contains(CURRENCY)
                    .contains("ยังไม่มีอัตราแลกเปลี่ยน")
                    .contains("ตั้งค่า CEO", "อัตราแลกเปลี่ยน")
                    .doesNotContain("BOT");
            });
    }

    private FxRateDto fxRate(String currency, String source, LocalDate effectiveDate, Instant fetchedAt) {
        return new FxRateDto(1L, currency, new BigDecimal("38.5000"), effectiveDate, Instant.now(), source, fetchedAt);
    }
}
