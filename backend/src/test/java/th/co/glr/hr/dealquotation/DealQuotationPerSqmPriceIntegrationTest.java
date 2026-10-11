package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;

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
 *       {@code direct_net_price_sqm}, V201) and served back verbatim, because the conversion cannot
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
class DealQuotationPerSqmPriceIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

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

    /** The editor's row may still hold a per-แผ่น figure from before the rep retyped the price.
     * When a per-ตร.ม. price is given it is the ONLY source; a per-แผ่น one beside it is not
     * consulted, so the two can never disagree on the stored row. */
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

    /** A rep who only has a net price leaves ราคาตั้ง blank; the editor then sends the net as the
     * list price too (it always has, per แผ่น). Both derive to the same figure, which is what makes
     * the document print "Net" in the ส่วนลด column. */
    @Test
    void directNet_whenTheListIsTheNet_bothComeOutEqual() {
        DealQuotationItemDto item = createOne(DIRECT_NET, perSqm("790", "790", null));

        assertThat(item.unitPrice()).isEqualByComparingTo(item.netUnitPrice()).isEqualByComparingTo("531.16");
    }

    // ── what was typed is what comes back ────────────────────────────────────────────────────

    /** On a 10×10 tile (100 แผ่น/ตร.ม.) 1,800 becomes 16.82 a piece — and 16.82 a piece is 1,799.74
     * per ตร.ม. A figure recovered from the per-แผ่น price would hand the rep a number they never
     * typed, so the typed one is stored and served verbatim. */
    @Test
    void theTypedPerSqmPriceIsServedBackVerbatim_notRecoveredFromThePerPieceOne() {
        ItemInput mosaic = item("10x10", "0.01", 100, null, "0", null, "1800", null);
        DealQuotationDto created = quotationService.create(deal(salesActor), request(NET, mosaic), salesActor);

        DealQuotationItemDto reread = quotationService.get(created.id(), salesActor).items().get(0);
        assertThat(reread.unitPrice()).isEqualByComparingTo("16.82");
        assertThat(reread.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(storedPrices(reread.id())).containsEntry("list_price_sqm", bd("1800.00"));
    }

    /** GET → PUT of an untouched document is a no-op on every price. Sent the way the editor
     * sends it — each saved row WITH its id — so this goes through the in-place rewrite. */
    @Test
    void savingADraftBackUnchanged_movesNoPrice() {
        long ticket = deal(salesActor);
        ItemInput typed = perSqm("1799.99", null, "12.5");
        DealQuotationDto created = quotationService.create(ticket, request(NET, typed), salesActor);
        DealQuotationItemDto before = created.items().get(0);

        DealQuotationDto saved = quotationService.update(created.id(),
            request(NET, withId(before.id(), typed)), salesActor);
        DealQuotationItemDto after = saved.items().get(0);

        assertThat(after.id()).as("the same row, rewritten in place").isEqualTo(before.id());
        assertThat(after.listPriceSqm()).isEqualByComparingTo("1799.99");
        assertThat(after.unitPrice()).isEqualByComparingTo(before.unitPrice());
        assertThat(after.netUnitPrice()).isEqualByComparingTo(before.netUnitPrice());
        assertThat(after.lineAmount()).isEqualByComparingTo(before.lineAmount());
        assertThat(saved.subtotalAmount()).isEqualByComparingTo(created.subtotalAmount());
    }

    // ── what must not change ─────────────────────────────────────────────────────────────────

    /** The regression guard for everything that still states its prices per แผ่น — every row
     * saved before V201, the pricing-request path, the e2e suite's payloads, the English form. */
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

    /** A row repriced per แผ่น (the legacy path) must not keep showing a per-ตร.ม. figure that no
     * longer has anything to do with its price. */
    @Test
    void repricingARowPerPiece_clearsTheStoredPerSqmFigure() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            request(NET, perSqm("1800", null, "0")), salesActor);
        assertThat(created.items().get(0).listPriceSqm()).isEqualByComparingTo("1800.00");

        DealQuotationDto saved = quotationService.update(created.id(), request(NET, perPiece("999.00", "0")), salesActor);

        DealQuotationItemDto item = saved.items().get(0);
        assertThat(item.unitPrice()).isEqualByComparingTo("999.00");
        assertThat(item.listPriceSqm()).isNull();
    }

    // ── a save that reuses the row's id ──────────────────────────────────────────────────────
    // The editor sends every row it has already saved WITH its id, and DealQuotationService#update
    // then rewrites that row IN PLACE (DealQuotationRepository#updateItemsInPlace) rather than
    // deleting and re-inserting it. That is a different SQL statement from the INSERT the tests
    // above go through, with a column list of its own — so it is pinned on its own. Found by
    // mutation: with both columns dropped from that UPDATE, everything above stayed green.

    @Test
    void anInPlaceSave_writesAPerSqmPrice_ontoARowThatWasPricedPerPiece() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, request(NET, perPiece("999.00", "0")), salesActor);
        long rowId = created.items().get(0).id();

        DealQuotationDto saved = quotationService.update(created.id(),
            request(NET, withId(rowId, perSqm("1800", null, "20"))), salesActor);

        DealQuotationItemDto item = saved.items().get(0);
        assertThat(item.id()).as("the same row, rewritten in place").isEqualTo(rowId);
        assertThat(item.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(item.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(item.netUnitPrice()).isEqualByComparingTo("968.20");
        assertThat(storedPrices(rowId)).containsEntry("list_price_sqm", bd("1800.00"));
    }

    @Test
    void anInPlaceSave_repricedPerPiece_clearsBothStoredPerSqmFigures() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            request(DIRECT_NET, perSqm("1800", "790", null)), salesActor);
        long rowId = created.items().get(0).id();
        assertThat(storedPrices(rowId)).containsEntry("list_price_sqm", bd("1800.00"))
            .containsEntry("direct_net_price_sqm", bd("790.00"));

        DealQuotationDto saved = quotationService.update(created.id(),
            request(NET, withId(rowId, perPiece("999.00", "0"))), salesActor);

        DealQuotationItemDto item = saved.items().get(0);
        assertThat(item.id()).as("the same row, rewritten in place").isEqualTo(rowId);
        assertThat(item.unitPrice()).isEqualByComparingTo("999.00");
        assertThat(item.listPriceSqm()).isNull();
        assertThat(item.directNetPriceSqm()).isNull();
        assertThat(storedPrices(rowId)).containsEntry("list_price_sqm", null)
            .containsEntry("direct_net_price_sqm", null);
    }

    /** The printed document is untouched by this change: it shows the per-แผ่น list and net prices
     * it always has. The per-ตร.ม. figure the rep typed is an INPUT, and appears nowhere on it. */
    @Test
    void thePrintedDocument_stillShowsPerPiecePrices_andNotThePerSqmFigure() {
        DealQuotationDto created = quotationService.create(deal(salesActor),
            request(NET, perSqm("1800", null, "20")), salesActor);

        List<BigDecimal> numbers = sheetNumbers(created.id());
        assertThat(numbers).as("ราคา").anySatisfy(n -> assertThat(n).isEqualByComparingTo("1210.25"));
        assertThat(numbers).as("คงเหลือ").anySatisfy(n -> assertThat(n).isEqualByComparingTo("968.20"));
        assertThat(numbers).as("เป็นเงิน").anySatisfy(n -> assertThat(n).isEqualByComparingTo("9682.00"));
        assertThat(numbers).as("the typed per-ตร.ม. price is not printed")
            .noneSatisfy(n -> assertThat(n).isEqualByComparingTo("1800"));
    }

    // ── ยอดรวม: the foot of the document ─────────────────────────────────────────────────────
    // Owner, 2026-10-06: "make sure the ยอดรวม in final is correct … calculated correctly". The
    // price the rep keys is per ตร.ม. INCLUDING VAT; the document prices each line per แผ่น BEFORE
    // VAT and adds 7% once, at its foot. So the VAT inside the keyed figure has to be taken out
    // exactly once on the way in and put back exactly once on the way out — and the foot has to
    // come back to what the keyed price says, give or take the satang lost rounding a price to
    // two decimals per แผ่น.
    //
    // 28 แผ่น of the 60×120 throughout: 20 ตร.ม. at 1.39 แผ่น/ตร.ม. is 27.8, so 28 แผ่น = 14 กล่อง.

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

    /** The claim itself, written wrong-way-round: the foot comes back to (keyed price × the area
     * the pieces cover), and NOT to either of the two ways to get VAT wrong — never taken out of
     * the keyed price, so charged twice (≈ 38,797), or taken out and never put back (≈ 33,887). */
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

    /** The same in every price mode, each with the box that mode keys its price in. */
    @Test
    void everyPriceMode_comesBackToTheKeyedPriceAtTheFoot() {
        // ราคาตั้ง 1,800 less 20%: 28 × 1,210.25 × 0.80
        assertFoot(NET, pieces(28, perSqm("1800", null, "20")),
            "27109.60", "1897.67", "29007.27", keyedPriceTimesArea("1440", 28));
        // ราคาพิเศษ 790 (ราคาตั้ง 1,800 only prints): 28 × 531.16
        assertFoot(SPECIAL_SQM, pieces(28, item("60x120", "0.72", 2, null, null, "790", "1800", null)),
            "14872.48", "1041.07", "15913.55", keyedPriceTimesArea("790", 28));
        // ราคาสุทธิ 790: the same money as ราคาพิเศษ 790 — it is the same keyed price
        assertFoot(DIRECT_NET, pieces(28, perSqm("1800", "790", null)),
            "14872.48", "1041.07", "15913.55", keyedPriceTimesArea("790", 28));
    }

    /** More than one line: รวมเป็นเงิน is the sum of the lines, and VAT is taken on THAT (once, on
     * the document), whatever unit each line's price was keyed in. */
    @Test
    void theSubtotalIsTheSumOfTheLines_andVatIsTakenOnce_onTheSubtotal() {
        DealQuotationDto quotation = quotationService.create(deal(salesActor),
            request(NET, pieces(28, perSqm("1800", null, "0")), freight()), salesActor);

        BigDecimal sumOfLines = quotation.items().stream().map(DealQuotationItemDto::lineAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfLines).as("33,887.00 + 2 × 500.00").isEqualByComparingTo("34887.00");
        assertThat(quotation.subtotalAmount()).isEqualByComparingTo(sumOfLines);
        assertThat(quotation.vatAmount()).as("7% of 34,887.00").isEqualByComparingTo("2442.09");
        assertThat(quotation.grandTotal()).isEqualByComparingTo("37329.09");
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────

    /** "รวม VAT" has no meaning on the English form (USD, no VAT). It keeps its own inputs. */
    @Test
    void anEnglishDocument_refusesAVatInclusivePerSqmPrice_andStoresNothing() {
        long ticket = deal(salesActor);
        UpsertDealQuotationRequest english = new UpsertDealQuotationRequest(null, null, null, null, 50,
            "CASH_ON_DELIVERY", 0, 30, null, NET, "EN", "USD",
            List.of(item("60x120", "0.72", 2, "15.00", "0", null, "1800", null))).withRecipientType("OWNER");

        // The row ALSO states a perfectly good per-piece price, so nothing but the per-ตร.ม. figure
        // can be what is refused here.
        assertThat(refusal(() -> quotationService.create(ticket, english, salesActor), HttpStatus.BAD_REQUEST))
            .hasMessageContaining("ใช้ได้กับใบเสนอราคาภาษาไทยเท่านั้น");

        assertThat(quotationCount(ticket)).isZero();
    }

    /** No area per piece, no conversion — and never a silent fallback to reading 1,800 per ตร.ม.
     * as 1,800 a piece. The message is asserted because a save is refused for an incomplete row
     * anyway: only the wording says the PRICE was the reason. */
    @Test
    void withNoAreaPerPiece_aPerSqmPriceIsRefused_notReadAsAPerPiecePrice() {
        long ticket = deal(salesActor);
        ItemInput noArea = item("60x120", null, 2, null, "0", null, "1800", null);
        // The dangerous shape: the same figure sent as a per-piece price too. Were the per-ตร.ม.
        // one quietly dropped, this row would be priced at 1,800 a PIECE.
        ItemInput noAreaWithAPerPiecePriceBeside = item("60x120", null, 2, "1800", "0", null, "1800", null);

        for (ItemInput row : List.of(noArea, noAreaWithAPerPiecePriceBeside)) {
            assertThat(refusal(() -> quotationService.create(ticket, request(NET, row), salesActor),
                HttpStatus.BAD_REQUEST)).hasMessageContaining("ขาด แผ่น/ตร.ม.");
        }
        assertThat(quotationCount(ticket)).isZero();
    }

    /** The live preview skips the completeness check (a half-typed row must still preview), so it
     * is the one path where nothing else would stop the fallback above. It shows no number at all
     * rather than 10 แผ่น × 1,800. */
    @Test
    void thePreview_withNoAreaPerPiece_showsNoNumber_ratherThanThePerSqmPriceTimesThePieces() {
        ItemInput noAreaWithAPerPiecePriceBeside = item("60x120", null, 2, "1800", "0", null, "1800", null);

        assertThat(refusal(() -> quotationService.calculateLine(noAreaWithAPerPiecePriceBeside, "TH", salesActor),
            HttpStatus.BAD_REQUEST)).hasMessageContaining("ขาด แผ่น/ตร.ม.");
    }

    /** A per-ตร.ม. price that is present but not positive is refused AS SUCH — not skipped in
     * favour of a per-piece price sent beside it. */
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

    /** ราคาสุทธิ/ตร.ม. belongs to ราคาสุทธิ mode only — exactly as ราคาพิเศษ is only read in its own
     * mode. Sent under another mode it prices nothing and is not kept. */
    @Test
    void aNetPerSqm_outsideNetPriceMode_pricesNothingAndIsNotStored() {
        DealQuotationItemDto item = createOne(NET, perSqm("1800", "790", "0"));

        assertThat(item.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(item.netUnitPrice()).as("NET: list × (1 − 0%)").isEqualByComparingTo("1210.25");
        assertThat(item.directNetPriceSqm()).isNull();
        assertThat(storedPrices(item.id())).containsEntry("direct_net_price_sqm", null);
    }

    /** สินค้า/บริการอื่น has no area, so it has no per-ตร.ม. price; one sent on it changes nothing. */
    @Test
    void aPlainRow_isNotPricedPerSqm() {
        ItemInput freight = new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, bd("500.00"), BigDecimal.ZERO, null,
            null, null, null, WastageCalculator.LINE_TYPE_PLAIN, "ค่าขนส่ง", bd("2"), "JOB", null, null,
            null, null, null, null, null, null, bd("1800"), bd("790"));

        DealQuotationItemDto item = createOne(NET, freight);

        assertThat(item.unitPrice()).isEqualByComparingTo("500.00");
        assertThat(item.lineAmount()).isEqualByComparingTo("1000.00");
        assertThat(item.listPriceSqm()).isNull();
        assertThat(item.directNetPriceSqm()).isNull();
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

    /** The preview has no quotation to read a price mode from; it infers one from the row. A net
     * typed per ตร.ม. has to say "ราคาสุทธิ" just as a net typed per แผ่น does — otherwise the row
     * previews as NET, off its list price, and shows the rep a number for a price nobody typed. */
    @Test
    void thePreview_readsANetPerSqm_asNetPriceMode() {
        DealQuotationItemDto preview = quotationService.calculateLine(
            perSqm("1800", "790", null), "TH", salesActor);

        assertThat(preview.netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(preview.lineAmount()).isEqualByComparingTo("5311.60");
        assertThat(preview.unitPrice()).isEqualByComparingTo("1210.25");
    }

    // ── copies ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aRevisionAndAReorder_carryTheTypedPerSqmPrices() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            request(DIRECT_NET, perSqm("1800", "790", null)), salesActor);
        quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);

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

    // ── who may read them ────────────────────────────────────────────────────────────────────

    /** Ruling 3 (2026-09-30): {@code account} sees each line's NET price and amount, never the
     * list price. The typed per-ตร.ม. figures are exactly what that ruling withholds — ราคาตั้ง in
     * another unit, and the VAT-inclusive figure the net is derived from.
     *
     * <p>Read through the REAL service as a real {@code account} caller, on both JSON reads it can
     * reach, with the same fixture {@code DealQuotationIntegrationTest
     * #account_readingADealDirectQuotation_getsNetPricesButNoListPriceOrDiscount} uses to put a
     * quotation inside account's scope (a deal at PROCUREMENT, a quotation that went to the
     * customer). Written wrong-way-round: what account must NOT receive. */
    @Test
    void anAccountCaller_isServedNeitherPerSqmPrice_onAnyReadItCanReach() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            request(DIRECT_NET, perSqm("1800", "790", null)), salesActor);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'PROCUREMENT' WHERE ticket_id = :id", Map.of("id", ticket));
        jdbc.update("UPDATE sales.quotation SET doc_status = 'APPROVED' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        UserPrincipal account = actor(
            createEmployee("บัญชี ราคาต่อ ตร.ม.", "acct-persqm@glr.co.th", "ACCT", "ฝ่ายบัญชี", null), "account");

        List<DealQuotationItemDto> servedToAccount = List.of(
            quotationService.get(created.id(), account).items().get(0),
            quotationService.listForTicket(ticket, account).get(0).items().get(0));

        assertThat(servedToAccount).hasSize(2).allSatisfy(item -> {
            assertThat(item.listPriceSqm()).isNull();
            assertThat(item.directNetPriceSqm()).isNull();
            assertThat(item.unitPrice()).isNull();
            assertThat(item.netUnitPrice()).as("the net per แผ่น stays").isEqualByComparingTo("531.16");
            assertThat(item.lineAmount()).isEqualByComparingTo("5311.60");
        });
        // …and it is the VIEW that withholds them, not the row: the owning rep still reads both.
        DealQuotationItemDto servedToSales = quotationService.get(created.id(), salesActor).items().get(0);
        assertThat(servedToSales.listPriceSqm()).isEqualByComparingTo("1800.00");
        assertThat(servedToSales.directNetPriceSqm()).isEqualByComparingTo("790.00");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** The owner's 60×120 (0.72 ตร.ม./แผ่น, 2 แผ่น/กล่อง), priced per ตร.ม. including VAT — the
     * shape the editor sends since this change. */
    private static ItemInput perSqm(String listPriceSqm, String directNetPriceSqm, String discountPct) {
        return item("60x120", "0.72", 2, null, discountPct, null, listPriceSqm, directNetPriceSqm);
    }

    /** The same tile priced per แผ่น — the shape every request had before this change, and the one
     * the English form, the pricing-request path and the e2e suite still send. */
    private static ItemInput perPiece(String unitPrice, String discountPct) {
        return item("60x120", "0.72", 2, unitPrice, discountPct, null, null, null);
    }

    /** A TILE row of 10 แผ่น with no wastage, so 10 is what every amount is multiplied by. Built
     * through the CANONICAL constructor on purpose: the older shapes default the two per-ตร.ม.
     * fields to null, and this class is about what happens when they are not. */
    private static ItemInput item(String sizeText, String sqmPerPiece, Integer piecesPerBox, String unitPrice,
                                  String discountPct, String specialPriceSqm, String listPriceSqm,
                                  String directNetPriceSqm) {
        return new ItemInput(
            null, null, null, "Panaria", "Trilogy", "Ivory", "Lappato", sizeText, bd("10.00"),
            bd(sqmPerPiece), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, piecesPerBox, bd(unitPrice), bd(discountPct), "อิตาลี",
            75, 90, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, bd(specialPriceSqm), null, null, null,
            null, null, null, false, bd(listPriceSqm), bd(directNetPriceSqm));
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

    /** A สินค้า/บริการอื่น line: ค่าขนส่ง, 2 × 500.00. */
    private static ItemInput freight() {
        return new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, bd("500.00"), BigDecimal.ZERO, null,
            null, null, null, WastageCalculator.LINE_TYPE_PLAIN, "ค่าขนส่ง", bd("2"), "JOB", null, null,
            null, null, null, null, null, null, null, null);
    }

    /** What a customer works out from the price they were quoted: that price (per ตร.ม., including
     * VAT) × the area the pieces cover at the 60×120's 1.39 แผ่น/ตร.ม. */
    private static BigDecimal keyedPriceTimesArea(String perSqmIncVat, int pieces) {
        return new BigDecimal(perSqmIncVat).multiply(BigDecimal.valueOf(pieces))
            .divide(new BigDecimal("1.39"), 2, RoundingMode.HALF_UP);
    }

    private void assertFoot(String priceMode, ItemInput row, String subtotal, String vat, String grandTotal,
                            BigDecimal keyed) {
        DealQuotationDto quotation = quotationService.create(deal(salesActor), request(priceMode, row), salesActor);
        assertThat(quotation.subtotalAmount()).as("%s รวมเป็นเงิน", priceMode).isEqualByComparingTo(subtotal);
        assertThat(quotation.vatAmount()).as("%s VAT 7%%", priceMode).isEqualByComparingTo(vat);
        assertThat(quotation.grandTotal()).as("%s ยอดรวมทั้งสิ้น", priceMode).isEqualByComparingTo(grandTotal);
        assertThat(quotation.grandTotal()).as("%s: what the keyed price says", priceMode)
            .isCloseTo(keyed, within(new BigDecimal("0.50")));
    }

    /** {@code row} as the editor sends a row it has already saved: carrying its server id, which is
     * what makes {@code DealQuotationService#update} rewrite it in place. */
    private static ItemInput withId(long id, ItemInput row) {
        return new ItemInput(row.locationLabel(), row.catalogPriceId(), row.productCode(), row.brand(), row.model(),
            row.color(), row.texture(), row.sizeText(), row.thicknessMm(), row.sqmPerPiece(), row.quantityMode(),
            row.areaSqm(), row.piecesInput(), row.wastageMode(), row.wastageValue(), row.piecesPerBox(),
            row.unitPrice(), row.discountPct(), row.originCountry(), row.leadTimeMinDays(), row.leadTimeMaxDays(),
            row.itemNotes(), row.lineType(), row.description(), row.quantity(), row.unit(), row.specialPriceSqm(),
            row.directNetPrice(), row.adjustmentPct(), row.adjustmentDeadline(), row.adjustmentAmount(), id,
            row.sqmPerBox(), row.roundToFullBox(), row.listPriceSqm(), row.directNetPriceSqm());
    }

    private static UpsertDealQuotationRequest request(String priceMode, ItemInput... items) {
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, priceMode, "TH", "THB", List.of(items)).withRecipientType("OWNER");
    }

    private DealQuotationItemDto createOne(String priceMode, ItemInput item) {
        return quotationService.create(deal(salesActor), request(priceMode, item), salesActor).items().get(0);
    }

    private Map<String, Object> storedPrices(long itemId) {
        return jdbc.queryForMap(
            "SELECT list_price_sqm, direct_net_price_sqm FROM sales.quotation_item WHERE quotation_item_id = :id",
            Map.of("id", itemId));
    }

    /** Every numeric cell of the workbook the SERVICE renders for the stored quotation, at 2dp. */
    private List<BigDecimal> sheetNumbers(long quotationId) {
        byte[] xls = quotationService.renderXlsx(quotationId, salesActor);
        List<BigDecimal> numbers = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.NUMERIC) {
                            numbers.add(BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP));
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return numbers;
    }
}
