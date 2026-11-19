package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;

/**
 * Sales feedback #1 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) — "แก้เป็น ตร.ม. รวม VAT ทุกแบบ" —
 * through the REAL {@link DealQuotationService} against real Postgres.
 *
 * <p>Owner ruling of the same day: every tile price box on a Thai quotation is typed per ตร.ม.
 * INCLUDING VAT — ราคาตั้ง in all three price modes, and ราคาสุทธิ too — and the system works out
 * the per-แผ่น figure the document has always printed. Concretely:
 *
 * <ul>
 *   <li>{@code ItemInput.listPriceSqm} → the server derives {@code unitPrice} (ราคาตั้ง/แผ่น);</li>
 *   <li>{@code ItemInput.directNetPriceSqm} (ราคาสุทธิ mode) → the server derives the net per แผ่น;</li>
 *   <li>both by the rule ราคาพิเศษ already uses — see {@code WastageCalculatorPerSqmPriceTest};</li>
 *   <li>what was typed is STORED ({@code sales.quotation_item.list_price_sqm} /
 *       {@code direct_net_price_sqm}, V192.2) and served back verbatim, because the conversion cannot
 *       be undone.</li>
 * </ul>
 *
 * <p>What must NOT change, and is pinned here as such: a request that still states its prices per
 * แผ่น (the English document, every row saved before this, the pricing-request path, the e2e
 * suite's own payloads) is priced exactly as it always was, and the printed document still shows
 * per-แผ่น prices.
 *
 * <p>The tile throughout is the owner's own 60×120 (0.72 ตร.ม./แผ่น → 1.39 แผ่น/ตร.ม.), so the
 * expected figures are hers: 1,800 → 1,210.25 (QN6900648) and 790 → 531.16 (QN6900981-1).
 */
class DealQuotationPerSqmPriceIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    private static final String NET = WastageCalculator.PRICE_MODE_NET;
    private static final String SPECIAL_SQM = WastageCalculator.PRICE_MODE_SPECIAL_SQM;
    private static final String DIRECT_NET = WastageCalculator.PRICE_MODE_DIRECT_NET;

    // ── ราคาตั้ง − ส่วนลด % ─────────────────────────────────────────────────────────────────────

    @Test
    void net_aListPricePerSqm_isConvertedToPerPiece_andEverythingElseFollowsFromThat() {
        DealQuotationItemDto item = createOne(NET, perSqm("1800", null, "20"));

        assertThat(item.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(item.unitPrice()).as("ราคาตั้ง/แผ่น, ก่อน VAT").isEqualByComparingTo("1210.25");
        assertThat(item.discountPct()).isEqualByComparingTo("20");
        assertThat(item.netUnitPrice()).as("1,210.25 × 0.80").isEqualByComparingTo("968.20");
        assertThat(item.lineAmount()).as("1,210.25 × 10 แผ่น × 0.80").isEqualByComparingTo("9682.00");
        assertThat(item.directNetPriceSqm()).isNull();
    }

    /** The editor's row may still hold a per-แผ่น figure from before the rep retyped the price. When a per-ตร.ม.
     * price is given it is the ONLY source; a per-แผ่น one beside it is not consulted, so the two can never
     * disagree on the stored row. */
    @Test
    void net_aPerPiecePriceSentAlongsideThePerSqmOne_isIgnored() {
        DealQuotationItemDto item = createOne(NET, item("60x120", "0.72", 2, "1.00", "0", null, "1800", null));

        assertThat(item.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(item.lineAmount()).isEqualByComparingTo("12102.50");
    }

    // ── ราคาพิเศษ บาท/ตร.ม. ──────────────────────────────────────────────────────────────────

    @Test
    void specialSqm_theListPriceIsNowPerSqmToo_andTheSpecialPriceIsUnchanged() {
        DealQuotationItemDto item = createOne(SPECIAL_SQM,
            item("60x120", "0.72", 2, null, null, "790", "1800", null));

        assertThat(item.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(item.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(item.specialPriceSqm()).isEqualByComparingTo("790");
        assertThat(item.netUnitPrice()).as("her QN6900981-1").isEqualByComparingTo("531.16");
        assertThat(item.lineAmount()).isEqualByComparingTo("5311.60");
    }

    // ── ราคาสุทธิ ───────────────────────────────────────────────────────────────────────────────

    @Test
    void directNet_theNetAndTheListAreBothPerSqm() {
        DealQuotationItemDto item = createOne(DIRECT_NET, perSqm("1800", "790", null));

        assertThat(item.directNetPriceSqm()).isEqualByComparingTo("790.00");
        assertThat(item.netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(item.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(item.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(item.lineAmount()).isEqualByComparingTo("5311.60");
    }

    // ── what was typed is what comes back ────────────────────────────────────────────────────

    /** On a 10×10 tile (100 แผ่น/ตร.ม.) 1,800 becomes 16.82 a piece — and 16.82 a piece is 1,799.74 per ตร.ม. A
     * figure recovered from the per-แผ่น price would hand the rep a number they never typed, so the typed one is
     * stored and served verbatim. */
    @Test
    void theTypedPerSqmPriceIsServedBackVerbatim_notRecoveredFromThePerPieceOne() {
        ItemInput mosaic = item("10x10", "0.01", 100, null, "0", null, "1800", null);
        DealQuotationDto created = quotationService.create(deal(salesActor), request(NET, mosaic), salesActor);

        DealQuotationItemDto reread = quotationService.get(created.id(), salesActor).items().get(0);
        assertThat(reread.unitPrice()).isEqualByComparingTo("16.82");
        assertThat(reread.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(storedPrices(reread.id())).containsEntry("list_price_sqm", bd("1800.00"));
    }

    // ── what must not change ─────────────────────────────────────────────────────────────────

    /** The regression guard for everything that still states its prices per แผ่น — every row
     * saved before V192.2, the pricing-request path, the e2e suite's payloads, the English form. */
    @Test
    void aRequestThatStatesItsPricePerPiece_isPricedExactlyAsBefore() {
        DealQuotationItemDto item = createOne(NET, perPiece("1748.13", "15"));

        assertThat(item.unitPrice()).isEqualByComparingTo("1748.13");
        assertThat(item.netUnitPrice()).isEqualByComparingTo("1485.91");
        assertThat(item.lineAmount()).isEqualByComparingTo("14859.11");
        assertThat(item.listPriceSqm()).isNull();
        assertThat(item.directNetPriceSqm()).isNull();
        assertThat(storedPrices(item.id())).containsEntry("list_price_sqm", null)
            .containsEntry("direct_net_price_sqm", null);
    }

    // a save that reuses the row's id ────────────────────────────────────────────────────── The editor sends
    // every row it has already saved WITH its id, and DealQuotationService#update then rewrites that row IN PLACE
    // (DealQuotationRepository#updateItemsInPlace) rather than deleting and re-inserting it.

    // ยอดรวม: the foot of the document ───────────────────────────────────────────────────── Owner, 2026-10-06:
    // "make sure the ยอดรวม in final is correct … calculated correctly". The price the rep keys is per ตร.ม.

    @Test
    void theFootOfTheDocument_addsUp_forAPriceKeyedPerSqmIncludingVat() {
        DealQuotationDto quotation = quotationService.create(deal(salesActor),
            request(NET, pieces(28, perSqm("1800", null, "0"))), salesActor);

        assertThat(quotation.items().get(0).unitPrice()).as("1,800 ÷ 1.07 ÷ 1.39").isEqualByComparingTo("1210.25");
        assertThat(quotation.items().get(0).lineAmount()).as("28 แผ่น × 1,210.25").isEqualByComparingTo("33887.00");
        assertThat(quotation.subtotalAmount()).as("รวมเป็นเงิน").isEqualByComparingTo("33887.00");
        assertThat(quotation.vatAmount()).as("ภาษีมูลค่าเพิ่ม 7% of 33,887.00").isEqualByComparingTo("2372.09");
        assertThat(quotation.grandTotal()).as("ยอดรวมทั้งสิ้น").isEqualByComparingTo("36259.09");
        // …and what a re-read of the saved document says, not only what create() returned.
        DealQuotationDto reread = quotationService.get(quotation.id(), salesActor);
        assertThat(reread.grandTotal()).isEqualByComparingTo("36259.09");
    }

    /** The claim itself, written wrong-way-round: the foot comes back to (keyed price × the area the pieces cover),
     * and NOT to either of the two ways to get VAT wrong — never taken out of the keyed price, so charged twice (≈
     * 38,797), or taken out and never put back (≈ 33,887). */
    @Test
    void theGrandTotal_carriesTheVatExactlyOnce_notTwiceAndNotNever() {
        BigDecimal grandTotal = quotationService.create(deal(salesActor),
            request(NET, pieces(28, perSqm("1800", null, "0"))), salesActor).grandTotal();
        BigDecimal keyed = keyedPriceTimesArea("1800", 28); // 1,800 × 28 ÷ 1.39 = 36,258.99

        assertThat(grandTotal).as("what the keyed price says").isCloseTo(keyed, within(new BigDecimal("0.50")));
        assertThat(grandTotal).as("VAT charged twice")
            .isNotCloseTo(keyed.multiply(new BigDecimal("1.07")), within(new BigDecimal("100")));
        assertThat(grandTotal).as("VAT never put back")
            .isNotCloseTo(keyed.divide(new BigDecimal("1.07"), 2, RoundingMode.HALF_UP), within(new BigDecimal("100")));
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────

    /** No area per piece, no conversion — and never a silent fallback to reading 1,800 per ตร.ม. as 1,800 a piece.
     * The message is asserted because a save is refused for an incomplete row anyway: only the wording says the
     * PRICE was the reason. */
    @Test
    void withNoAreaPerPiece_aPerSqmPriceIsRefused_notReadAsAPerPiecePrice() {
        long ticket = deal(salesActor);
        ItemInput noArea = item("60x120", null, 2, null, "0", null, "1800", null);
        // The dangerous shape: the same figure sent as a per-piece price too. Were the per-ตร.ม. one quietly dropped,
        // this row would be priced at 1,800 a PIECE.
        ItemInput noAreaWithAPerPiecePriceBeside = item("60x120", null, 2, "1800", "0", null, "1800", null);

        for (ItemInput row : List.of(noArea, noAreaWithAPerPiecePriceBeside)) {
            assertThat(refusal(() -> quotationService.create(ticket, request(NET, row), salesActor),
                HttpStatus.BAD_REQUEST)).hasMessageContaining("ขาด แผ่น/ตร.ม.");
        }
        assertThat(quotationCount(ticket)).isZero();
    }

    /** A per-ตร.ม. price that is present but not positive is refused AS SUCH — not skipped in favour of a per-piece
     * price sent beside it. */
    @Test
    void aZeroOrNegativePerSqmPrice_isRefused() {
        long ticket = deal(salesActor);
        for (String price : new String[] {"0", "-1800"}) {
            assertThat(refusal(() -> quotationService.create(ticket,
                request(NET, perSqm(price, null, "0")), salesActor), HttpStatus.BAD_REQUEST))
                .as("ราคาตั้ง %s", price).hasMessageContaining("ราคาตั้งต่อ ตร.ม. ต้องมากกว่าศูนย์");
            assertThat(refusal(() -> quotationService.create(ticket,
                request(NET, item("60x120", "0.72", 2, "1210.25", "0", null, price, null)), salesActor),
                HttpStatus.BAD_REQUEST))
                .as("ราคาตั้ง %s beside a valid per-piece price", price)
                .hasMessageContaining("ราคาตั้งต่อ ตร.ม. ต้องมากกว่าศูนย์");
            assertThat(refusal(() -> quotationService.create(ticket,
                request(DIRECT_NET, perSqm("1800", price, null)), salesActor), HttpStatus.BAD_REQUEST))
                .as("ราคาสุทธิ %s", price).hasMessageContaining("ราคาสุทธิต่อ ตร.ม. ต้องมากกว่าศูนย์");
        }
        assertThat(quotationCount(ticket)).isZero();
    }

    // ── the live preview ─────────────────────────────────────────────────────────────────────

    /** calculate-line is what shows the rep the per-แผ่น figure while they type the per-ตร.ม. one. */
    @Test
    void thePreview_returnsThePerPieceFiguresForAPerSqmPrice() {
        DealQuotationItemDto preview = quotationService.calculateLine(
            perSqm("1800", null, "20"), "TH", salesActor);

        assertThat(preview.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(preview.netUnitPrice()).isEqualByComparingTo("968.20");
        assertThat(preview.lineAmount()).isEqualByComparingTo("9682.00");
        assertThat(preview.listPriceSqm()).isEqualByComparingTo("1800");
    }

    // ── copies ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aRevisionAndAReorder_carryTheTypedPerSqmPrices() {
        long ticket = deal(salesActor);
        DealQuotationDto approved = approved(ticket, request(DIRECT_NET, perSqm("1800", "790", null)), salesActor);

        for (DealQuotationDto copy : List.of(
                quotationService.createReorder(approved.id(), salesActor),
                quotationService.createRevision(approved.id(), salesActor))) {
            DealQuotationItemDto item = copy.items().get(0);
            assertThat(item.listPriceSqm()).as(copy.number()).isEqualByComparingTo("1800.00");
            assertThat(item.directNetPriceSqm()).as(copy.number()).isEqualByComparingTo("790.00");
            assertThat(item.unitPrice()).isEqualByComparingTo("1210.25");
            assertThat(item.netUnitPrice()).isEqualByComparingTo("531.16");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** The owner's 60×120 (0.72 ตร.ม./แผ่น, 2 แผ่น/กล่อง), priced per ตร.ม. including VAT — the shape the editor
     * sends since this change. */
    private static ItemInput perSqm(String listPriceSqm, String directNetPriceSqm, String discountPct) {
        return item("60x120", "0.72", 2, null, discountPct, null, listPriceSqm, directNetPriceSqm);
    }

    /** {@code row} with its quantity changed to {@code pieces} แผ่น (the fixtures default to 10). */
    private static ItemInput pieces(int pieces, ItemInput row) {
        return new ItemInput(row.locationLabel(), row.catalogPriceId(), row.productCode(), row.brand(), row.model(),
            row.color(), row.texture(), row.sizeText(), row.thicknessMm(), row.sqmPerPiece(), row.quantityMode(),
            row.areaSqm(), pieces, row.wastageMode(), row.wastageValue(), row.piecesPerBox(),
            row.unitPrice(), row.discountPct(), row.originCountry(), row.leadTimeMinDays(), row.leadTimeMaxDays(),
            row.itemNotes(), row.lineType(), row.description(), row.quantity(), row.unit(), row.specialPriceSqm(),
            row.directNetPrice(), row.adjustmentPct(), row.adjustmentDeadline(), row.adjustmentAmount(), row.id(),
            row.sqmPerBox(), row.roundToFullBox(), row.listPriceSqm(), row.directNetPriceSqm());
    }

    /** What a customer works out from the price they were quoted: that price (per ตร.ม., including VAT) × the area
     * the pieces cover at the 60×120's 1.39 แผ่น/ตร.ม. */
    private static BigDecimal keyedPriceTimesArea(String perSqmIncVat, int pieces) {
        return new BigDecimal(perSqmIncVat).multiply(BigDecimal.valueOf(pieces))
            .divide(new BigDecimal("1.39"), 2, RoundingMode.HALF_UP);
    }

    private DealQuotationItemDto createOne(String priceMode, ItemInput item) {
        return quotationService.create(deal(salesActor), request(priceMode, item), salesActor).items().get(0);
    }

    private Map<String, Object> storedPrices(long itemId) {
        return jdbc.queryForMap(
            "SELECT list_price_sqm, direct_net_price_sqm FROM sales.quotation_item WHERE quotation_item_id = :id",
            Map.of("id", itemId));
    }

}
