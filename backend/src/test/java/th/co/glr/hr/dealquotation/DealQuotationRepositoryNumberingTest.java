package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit unit test for {@link DealQuotationRepository#baseNumber} and
 * {@link DealQuotationRepository#revisionNumber} — NO Spring context, NO Postgres. Both methods
 * are pure static functions with no DB dependency, so this is the only LOCALLY-RUNNABLE evidence
 * for the numbering-format change (owner feedback 2026-09-11: "มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" /
 * "ใบแรกเป็น QT-2026-0014-1") in an environment with neither {@code TEST_DB_URL} nor Docker —
 * {@link DealQuotationIntegrationTest}'s real-DB coverage of the same two methods cannot execute
 * there at all.
 *
 * <p>Both methods are package-private on {@link DealQuotationRepository}, so this test lives in
 * the same package deliberately (same idiom {@code DealQuotationIntegrationTest} already uses to
 * reach them, just without the {@code AbstractPostgresIntegrationTest} base class it needs for
 * everything else it covers).
 */
class DealQuotationRepositoryNumberingTest {

    // ── The owner requirement itself ────────────────────────────────────────────────────────

    @Test
    void revisionNumber_revision1_carriesTheSuffix_theActualOwnerRequirement() {
        // Owner feedback 2026-09-11, verbatim: "ใบแรกเป็น QT-2026-0014-1" -- revision 1 must print
        // WITH the suffix, not bare. This is the one assertion the old
        // "revisionNo <= 1 ? baseNumber : ..." code directly violated.
        assertThat(DealQuotationRepository.revisionNumber("QT-2026-0014", 1)).isEqualTo("QT-2026-0014-1");
    }

    @Test
    void revisionNumber_revision2_unchangedFromBefore() {
        assertThat(DealQuotationRepository.revisionNumber("QT-2026-0014", 2)).isEqualTo("QT-2026-0014-2");
    }

    // ── New-style round trip ────────────────────────────────────────────────────────────────

    @Test
    void baseNumber_stripsANewStyleRevision1Suffix() {
        assertThat(DealQuotationRepository.baseNumber("QT-2026-0014-1", 1)).isEqualTo("QT-2026-0014");
    }

    // ── Legacy (pre-2026-09-11) bare rows ───────────────────────────────────────────────────

    @Test
    void baseNumber_aLegacyBareNumberAtRevision1_isANoOp() {
        // A quotation issued BEFORE this change has NO "-1" suffix at revisionNo 1 -- and that
        // number is never rewritten (no migration touches sales.quotation.number). baseNumber must
        // return it completely unchanged rather than stripping a suffix that was never there.
        assertThat(DealQuotationRepository.baseNumber("QT-2026-0014", 1)).isEqualTo("QT-2026-0014");
    }

    @Test
    void fullLegacyPath_revisingABareRow_producesExactlyWhatTheOldCodeProduced_noCollisionNoRewrite() {
        String bareLegacyNumber = "QT-2026-0014";
        String base = DealQuotationRepository.baseNumber(bareLegacyNumber, 1);
        // The parent's own number is never touched -- this is the "no rewrite" half.
        assertThat(base).isEqualTo(bareLegacyNumber);
        // "{bareNumber}-2" -- identical to what the OLD (pre-this-change) code already produced
        // for a revision of a bare-numbered parent, so a legacy row's revision chain is
        // unaffected by this change and cannot collide with anything.
        assertThat(DealQuotationRepository.revisionNumber(base, 2)).isEqualTo("QT-2026-0014-2");
    }

    // ── Round-trip property ─────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_baseNumberOfARevisionNumber_recoversTheOriginalBase_acrossRevisions1Through5() {
        String base = "QT-2026-0014";
        for (int revisionNo = 1; revisionNo <= 5; revisionNo++) {
            String revisioned = DealQuotationRepository.revisionNumber(base, revisionNo);
            assertThat(DealQuotationRepository.baseNumber(revisioned, revisionNo))
                .as("round trip failed for revisionNo=%d (revisioned number was '%s')", revisionNo, revisioned)
                .isEqualTo(base);
        }
    }

    // ── The hand-checked edge case: a base's own "-<4 digits>" tail must never be mistaken for
    //    a "-1" (or any other single/double-digit) revision suffix. ─────────────────────────────

    @Test
    void baseNumber_aBaseEndingInDigitsThatLookLikeASuffix_isNotCorrupted() {
        // "QT-2026-0001" ends in "-0001", NOT "-1" -- endsWith("-1") is false because the
        // character immediately before the final "1" is "0", not "-". A sloppier strip (e.g.
        // stripping any trailing "-" + digits, or checking containment instead of a true
        // endsWith on the exact "-{revisionNo}" token) would corrupt this into "QT-2026-000",
        // silently truncating a real sequence number. Nothing else in this codebase pins this,
        // so a future change to the base's zero-padding width (currently 4 digits, #M10) could
        // reintroduce exactly this bug without any other test noticing.
        assertThat(DealQuotationRepository.baseNumber("QT-2026-0001", 1)).isEqualTo("QT-2026-0001");
    }

    @Test
    void baseNumber_aBaseEndingInDigitsThatLookLikeASuffix_revisionNo2Variant() {
        // Same hazard, one digit over: "QT-2026-0012" ends in "2" but NOT in the exact "-2"
        // token (the preceding character is "1", not "-"), so a revision-2 strip must also leave
        // it untouched.
        assertThat(DealQuotationRepository.baseNumber("QT-2026-0012", 2)).isEqualTo("QT-2026-0012");
    }
}
