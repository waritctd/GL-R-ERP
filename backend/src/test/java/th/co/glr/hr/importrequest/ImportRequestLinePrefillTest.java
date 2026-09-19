package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Plain unit tests (no DB) for the F-SM-001 line-prefill fallback chain (owner decision 09-18 #3
 * §A). {@link ImportRequestQueryRepository#factoryResolutionCandidates} and {@code
 * ImportRequestService#resolveFactoryGroups} are exercised end-to-end, against real Postgres, by
 * {@code ImportRequestFactoryResolutionIntegrationTest}'s prefill tests — these pin the pure
 * cascade logic itself in isolation, which is what makes the mutation-check in this branch's PR
 * description possible without a database in the loop.
 */
class ImportRequestLinePrefillTest {

    // ── firstNonBlank (the code cascade AND the color/texture cascade share this) ────────────────

    @Test
    void firstNonBlank_prefersTheFirstNonBlankCandidate() {
        assertThat(ImportRequestLinePrefill.firstNonBlank("catalog", "pri", "model"))
            .isEqualTo("catalog");
        assertThat(ImportRequestLinePrefill.firstNonBlank(null, "pri", "model")).isEqualTo("pri");
        assertThat(ImportRequestLinePrefill.firstNonBlank(null, null, "model")).isEqualTo("model");
        assertThat(ImportRequestLinePrefill.firstNonBlank(null, null, null)).isNull();
    }

    @Test
    void firstNonBlank_treatsBlankAndWhitespaceOnlyAsAbsent() {
        assertThat(ImportRequestLinePrefill.firstNonBlank("", "pri", "model")).isEqualTo("pri");
        assertThat(ImportRequestLinePrefill.firstNonBlank("   ", "pri", "model")).isEqualTo("pri");
    }

    @Test
    void firstNonBlank_stripsTheWinningCandidate() {
        assertThat(ImportRequestLinePrefill.firstNonBlank("  CAT-001  ", "pri", "model"))
            .isEqualTo("CAT-001");
    }

    // ── buildColorSurfaceNote ──────────────────────────────────────────────────────────────────

    @Test
    void buildColorSurfaceNote_joinsBothWhenBothAreKnown() {
        assertThat(ImportRequestLinePrefill.buildColorSurfaceNote("Grigio", "Matte"))
            .isEqualTo("สี Grigio · ผิว Matte");
    }

    @Test
    void buildColorSurfaceNote_colourAloneHasNoDanglingSeparator() {
        assertThat(ImportRequestLinePrefill.buildColorSurfaceNote("Grigio", null))
            .isEqualTo("สี Grigio");
        assertThat(ImportRequestLinePrefill.buildColorSurfaceNote("Grigio", "   "))
            .isEqualTo("สี Grigio");
    }

    @Test
    void buildColorSurfaceNote_surfaceAloneHasNoDanglingSeparator() {
        assertThat(ImportRequestLinePrefill.buildColorSurfaceNote(null, "Matte"))
            .isEqualTo("ผิว Matte");
    }

    @Test
    void buildColorSurfaceNote_isNullWhenNeitherIsKnown_ratherThanABlankString() {
        assertThat(ImportRequestLinePrefill.buildColorSurfaceNote(null, null)).isNull();
        assertThat(ImportRequestLinePrefill.buildColorSurfaceNote("", "   ")).isNull();
    }

    // ── mergeNote — never overwrite a note the business already wrote ────────────────────────────

    @Test
    void mergeNote_fillsInTheDerivedNote_whenNoneExistsYet() {
        assertThat(ImportRequestLinePrefill.mergeNote(null, "สี Grigio · ผิว Matte"))
            .isEqualTo("สี Grigio · ผิว Matte");
        assertThat(ImportRequestLinePrefill.mergeNote("", "สี Grigio")).isEqualTo("สี Grigio");
    }

    @Test
    void mergeNote_keepsAnExistingNoteUntouched_ratherThanOverwritingOrAppending() {
        assertThat(ImportRequestLinePrefill.mergeNote("สั่งตามPO", "สี Grigio · ผิว Matte"))
            .as("a business-typed note must survive re-derivation exactly as written")
            .isEqualTo("สั่งตามPO");
    }

    @Test
    void mergeNote_isNullWhenBothAreAbsent() {
        assertThat(ImportRequestLinePrefill.mergeNote(null, null)).isNull();
    }
}
