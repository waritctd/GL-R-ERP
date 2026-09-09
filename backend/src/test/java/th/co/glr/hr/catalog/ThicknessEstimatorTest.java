package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.catalog.ThicknessEstimator.Suggestion;

/**
 * SPEC-PREFILL.md ladder A: {@link ThicknessEstimator} is a pure-function class (no database, no
 * Spring context), so every case here is a plain object test — no {@code AbstractPostgresIntegrationTest}
 * needed. {@link CatalogRepositoryThicknessEstimationIntegrationTest} covers the two REPOSITORY
 * methods that feed this class's inputs from real Postgres; this class covers the DECISION alone.
 *
 * <p>Every assertion below was checked to actually go RED when the mechanism it covers is broken,
 * and GREEN again once reverted — a test that cannot fail is not evidence (CLAUDE.md). Confirmed
 * by hand: flipping {@code snapUp}'s {@code <=} to {@code <} (only {@link
 * #aRawValueAlreadyOnTheLadderIsNotBumpedToTheNextRung} moves); emptying {@code
 * SUPPRESSED_FACTORIES} does NOT by itself move {@link
 * #equipeYieldsNoSuggestionEvenWithOtherwisePlausibleInputs} — Equipe is also simply absent from
 * {@code FACTORY_DENSITY}, so the "unknown factory" branch already refuses it — but emptying that
 * set WHILE ALSO temporarily adding an Equipe entry to the density map (simulating the failure mode
 * the suppression set exists to survive: someone adding a future measurement without re-reading
 * this class's own warning) does move exactly that one test, proving the suppression check is real
 * defence-in-depth and not merely documentation.
 */
class ThicknessEstimatorTest {
    private final ThicknessEstimator estimator = new ThicknessEstimator();

    // ── fromBoxWeight: the suppression the spec calls out by name ──────────────────────────────

    /**
     * Equipe's measured density (8 kg/m³, ~290× error — see {@link ThicknessEstimator}'s own
     * Javadoc) means NO input should ever produce a suggestion for it, not even numbers that would
     * look perfectly reasonable for any other factory. 22.5kg over 1.44m² is a real, plausible box.
     */
    @Test
    void equipeYieldsNoSuggestionEvenWithOtherwisePlausibleInputs() {
        assertThat(estimator.fromBoxWeight("Equipe", new BigDecimal("22.5"), new BigDecimal("1.44")))
            .isEmpty();
    }

    // ── fromBoxWeight: round-UP, never to-nearest ───────────────────────────────────────────────

    /**
     * Padana density 2333 kg/m³: 18.8973kg / 1m² implies a raw 8.1mm. Nearest-snap would round DOWN
     * to 8.0 (0.1mm away vs 0.4mm to 8.5); round-UP must land on 8.5 — the direction SPEC-PREFILL.md
     * requires because nominal thickness is systematically ≥ the weight-implied figure.
     */
    @Test
    void rawEightPointOneSnapsUpToEightPointFive_notDownToEight() {
        Optional<Suggestion> suggestion =
            estimator.fromBoxWeight("Padana", new BigDecimal("18.8973"), new BigDecimal("1"));
        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().thicknessMm()).isEqualByComparingTo("8.5");
    }

    /** A raw value that already lands exactly on a ladder rung stays there — round-UP is a ceiling, not a "bump to the next rung" operation. */
    @Test
    void aRawValueAlreadyOnTheLadderIsNotBumpedToTheNextRung() {
        // 18.664 / 1 / 2333 * 1000 = 8.000 exactly.
        Optional<Suggestion> suggestion =
            estimator.fromBoxWeight("Padana", new BigDecimal("18.664"), new BigDecimal("1"));
        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().thicknessMm()).isEqualByComparingTo("8");
    }

    /** Nothing to snap UP to above the ladder's own top (20mm) — must refuse rather than return an un-snapped, off-ladder number. */
    @Test
    void aRawValueAboveTheLaddersTopRungYieldsNoSuggestion() {
        // 100 / 1 / 2333 * 1000 ≈ 42.9mm — above every rung.
        assertThat(estimator.fromBoxWeight("Padana", new BigDecimal("100"), new BigDecimal("1")))
            .isEmpty();
    }

    // ── fromBoxWeight: confidence tiers ─────────────────────────────────────────────────────────

    @Test
    void padanaCarriesHighConfidenceAndCitesItsMeasuredAccuracyInTheBasisText() {
        Suggestion suggestion =
            estimator.fromBoxWeight("Padana", new BigDecimal("18.8973"), new BigDecimal("1")).orElseThrow();
        assertThat(suggestion.confidence()).isEqualTo("HIGH");
        assertThat(suggestion.basis()).contains("Padana").contains("98%");
    }

    /**
     * Vives has ZERO validation rows (SPEC-PREFILL.md) — its density is a pooled TRANSFER, not a
     * measurement — so it must still produce a suggestion (unlike Equipe) but labelled UNVALIDATED,
     * and the Thai text itself must say so, not only the machine-readable field.
     */
    @Test
    void vivesIsUnvalidatedNotSuppressed() {
        Suggestion suggestion =
            estimator.fromBoxWeight("Vives", new BigDecimal("5"), new BigDecimal("1")).orElseThrow();
        assertThat(suggestion.confidence()).isEqualTo("UNVALIDATED");
        assertThat(suggestion.basis()).contains("Vives");
    }

    /** A factory absent from the table entirely (never measured, never even attempted) is suppressed, exactly like Equipe — SPEC-PREFILL.md: "prefer suppress; do not silently extend the table." */
    @Test
    void aFactoryAbsentFromTheTableIsSuppressed() {
        assertThat(estimator.fromBoxWeight("Bode", new BigDecimal("18.8973"), new BigDecimal("1")))
            .isEmpty();
    }

    // ── fromBoxWeight: input guards ──────────────────────────────────────────────────────────────

    @Test
    void aNullFactoryNameYieldsNoSuggestion() {
        assertThat(estimator.fromBoxWeight(null, new BigDecimal("18.8973"), new BigDecimal("1"))).isEmpty();
    }

    @Test
    void aZeroOrMissingBoxWeightYieldsNoSuggestion() {
        assertThat(estimator.fromBoxWeight("Padana", null, new BigDecimal("1"))).isEmpty();
        assertThat(estimator.fromBoxWeight("Padana", BigDecimal.ZERO, new BigDecimal("1"))).isEmpty();
        assertThat(estimator.fromBoxWeight("Padana", new BigDecimal("-1"), new BigDecimal("1"))).isEmpty();
    }

    @Test
    void aZeroOrMissingBoxAreaYieldsNoSuggestion() {
        assertThat(estimator.fromBoxWeight("Padana", new BigDecimal("18.8973"), null)).isEmpty();
        assertThat(estimator.fromBoxWeight("Padana", new BigDecimal("18.8973"), BigDecimal.ZERO)).isEmpty();
    }

    // ── fromSiblings ─────────────────────────────────────────────────────────────────────────────

    /** The narrow case where a sibling suggestion is safe: every sibling that states a thickness states the SAME one. */
    @Test
    void agreeingSiblingsSuggestTheSharedValueAtHighConfidence() {
        Suggestion suggestion = estimator.fromSiblings(
            List.of(new BigDecimal("9"), new BigDecimal("9"), new BigDecimal("9"))).orElseThrow();
        assertThat(suggestion.thicknessMm()).isEqualByComparingTo("9");
        assertThat(suggestion.confidence()).isEqualTo("HIGH");
        assertThat(suggestion.basis()).contains("3");
    }

    /**
     * 129 of 220 collections span more than one freight band (SPEC-PREFILL.md) — this is the case
     * that makes "the collection's thickness" unsafe in general, so ANY disagreement must fall
     * through to silence rather than guess a majority or an average.
     */
    @Test
    void disagreeingSiblingsYieldNoSuggestion() {
        assertThat(estimator.fromSiblings(List.of(new BigDecimal("9"), new BigDecimal("10")))).isEmpty();
    }

    /** Different SCALE, same numeric value (8 vs 8.0 — two factories' import formats can write either) must still read as agreement, not disagreement. */
    @Test
    void siblingsAtDifferentScalesButTheSameValueStillAgree() {
        Suggestion suggestion =
            estimator.fromSiblings(List.of(new BigDecimal("8"), new BigDecimal("8.0"))).orElseThrow();
        assertThat(suggestion.thicknessMm()).isEqualByComparingTo("8");
    }

    @Test
    void noSiblingsYieldsNoSuggestion() {
        assertThat(estimator.fromSiblings(List.of())).isEmpty();
        assertThat(estimator.fromSiblings(null)).isEmpty();
    }
}
