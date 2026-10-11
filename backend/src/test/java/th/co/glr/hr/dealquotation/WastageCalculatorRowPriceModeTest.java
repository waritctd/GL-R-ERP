package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;

/**
 * Mixed price modes (V204) — sales feedback #14 (แจ้งปัญหาการใช้งาน เสนอราคา) and the owner's ruling of
 * 2026-10-06: "อยากให้เลือกการคิดราคาแต่ละข้อไม่เหมือนกันได้ เช่น บางข้อราคาพิเศษต่อตรม บางข้อลด% ตามสเต็ป".
 *
 * <p>The whole feature rests on one small rule, pinned here on its own: <b>which mode is a given
 * tile row priced and printed in</b> ({@link WastageCalculator#rowPriceMode}). Everything that
 * used to ask "what mode is this DOCUMENT in" — the write path, the read path, the ส่วนลด cell,
 * the special-pricing gate — now asks this instead, so that a single-mode document behaves
 * exactly as it always did and a MIXED one is nothing more than rows each behaving that way.
 *
 * <p>Also here: what the request is allowed to SAY (bean validation), which is the first thing a
 * wrong payload meets. What the service then does with it is
 * {@code DealQuotationMixedPriceModeIntegrationTest}'s.
 */
class WastageCalculatorRowPriceModeTest {
    private static final String NET = WastageCalculator.PRICE_MODE_NET;
    private static final String SPECIAL_SQM = WastageCalculator.PRICE_MODE_SPECIAL_SQM;
    private static final String DIRECT_NET = WastageCalculator.PRICE_MODE_DIRECT_NET;
    private static final String MIXED = WastageCalculator.PRICE_MODE_MIXED;

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    // ── the rule ────────────────────────────────────────────────────────────────────────────

    @Test
    void onAMixedDocument_aRowIsPricedInItsOwnMode() {
        for (String rowMode : List.of(NET, SPECIAL_SQM, DIRECT_NET)) {
            assertThat(WastageCalculator.rowPriceMode(MIXED, rowMode)).isEqualTo(rowMode);
        }
    }

    /** The wrong-way-round half, and the one that protects every document that exists today: on a
     * single-mode document the header governs, and nothing left on a row can reprice it. */
    @Test
    void onASingleModeDocument_theRowsOwnModeIsNotConsulted() {
        for (String documentMode : List.of(NET, SPECIAL_SQM, DIRECT_NET)) {
            for (String rowMode : new String[] {null, "", NET, SPECIAL_SQM, DIRECT_NET}) {
                assertThat(WastageCalculator.rowPriceMode(documentMode, rowMode))
                    .as("document %s, row %s", documentMode, rowMode)
                    .isEqualTo(documentMode);
            }
        }
    }

    /** MIXED is never the answer: a row is always priced in exactly one of the three. A row of a
     * MIXED document with no mode of its own cannot be written (the service refuses it), but a
     * READ must not fail on one — it reads as NET, the mode a row with no choice made has always
     * been in. */
    @Test
    void aRowIsNeverPricedInMixed_evenWhenItStatesNoModeOfItsOwn() {
        assertThat(WastageCalculator.rowPriceMode(MIXED, null)).isEqualTo(NET);
        assertThat(WastageCalculator.rowPriceMode(MIXED, "")).isEqualTo(NET);
        assertThat(WastageCalculator.rowPriceMode(MIXED, "  ")).isEqualTo(NET);
    }

    // ── what a request may say ──────────────────────────────────────────────────────────────

    @Test
    void theDocumentsMode_mayBeMixed_orOneOfTheThree_andNothingElse() {
        for (String mode : new String[] {null, NET, SPECIAL_SQM, DIRECT_NET, MIXED}) {
            assertThat(VALIDATOR.validate(request(mode))).as("priceMode %s", mode).isEmpty();
        }
        for (String mode : new String[] {"mixed", "MIX", "PER_ITEM", ""}) {
            assertThat(VALIDATOR.validate(request(mode))).as("priceMode '%s'", mode).isNotEmpty();
        }
    }

    /** A ROW may state one of the three, or nothing. Never MIXED — "this row is priced in several
     * ways" means nothing — and never a code the engine does not know. */
    @Test
    void aRowsMode_mayBeOneOfTheThree_orAbsent_andNeverMixed() {
        for (String mode : new String[] {null, NET, SPECIAL_SQM, DIRECT_NET}) {
            assertThat(VALIDATOR.validate(row().withPriceMode(mode))).as("row priceMode %s", mode).isEmpty();
        }
        for (String mode : new String[] {MIXED, "net", "SPECIAL", ""}) {
            assertThat(VALIDATOR.validate(row().withPriceMode(mode))).as("row priceMode '%s'", mode).isNotEmpty();
        }
    }

    /** …and the row-level bound is reached through the document, which is how a real request
     * arrives ({@code @Valid} cascades into {@code items}). */
    @Test
    void aBadRowMode_failsTheWholeRequest() {
        UpsertDealQuotationRequest request = new UpsertDealQuotationRequest(null, null, null, null, 30,
            "CREDIT", 30, 30, null, MIXED, "TH", "THB", List.of(row().withPriceMode("MIXED")));
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
        // …and it is that row, not something else about the fixture: the same request with the row
        // stating a real mode is clean.
        assertThat(VALIDATOR.validate(new UpsertDealQuotationRequest(null, null, null, null, 30,
            "CREDIT", 30, 30, null, MIXED, "TH", "THB", List.of(row().withPriceMode(NET))))).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static UpsertDealQuotationRequest request(String priceMode) {
        return new UpsertDealQuotationRequest(null, null, null, null, 30, "CREDIT", 30, 30, null,
            priceMode, "TH", "THB", List.of(row()));
    }

    private static ItemInput row() {
        return new ItemInput(
            null, null, null, "Panaria", "Trilogy", "Ivory", "Lappato", "60x120", new java.math.BigDecimal("10.00"),
            new java.math.BigDecimal("0.72"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, new java.math.BigDecimal("100.00"),
            java.math.BigDecimal.ZERO, "อิตาลี", 75, 90, null, WastageCalculator.LINE_TYPE_TILE, null, null, null,
            null, null, null, null, null, null, null, false);
    }
}
