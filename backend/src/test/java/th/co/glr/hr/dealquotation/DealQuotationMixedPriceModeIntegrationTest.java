package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * Mixed price modes (V192.5), through the REAL {@link DealQuotationService} against real Postgres.
 *
 * <p>Sales feedback #14 (แจ้งปัญหาการใช้งาน เสนอราคา): "อยากให้เลือกการคิดราคาแต่ละข้อไม่เหมือนกันได้ เช่น
 * บางข้อราคาพิเศษต่อตรม บางข้อลด% ตามสเต็ป". It was declined at first ("one quotation = one discount
 * format"); the owner reversed that on 2026-10-06 and asked for a clear choice between ONE mode
 * for the whole document and a MIX.
 *
 * <p>The model: the document's {@code priceMode} gains a fourth value, {@code MIXED}; on such a
 * document every TILE row states its own mode ({@code NET} / {@code SPECIAL_SQM} /
 * {@code DIRECT_NET}), stored in {@code sales.quotation_item.price_mode}. Nothing else about a row
 * changes — and that is the property this class leans on hardest:
 *
 * <blockquote><b>A row of a MIXED document is priced, stored and printed exactly as the same row
 * is on a single-mode document of that mode.</b></blockquote>
 *
 * So the expected figures are not re-derived here; they are whatever the single-mode engine — the
 * one {@code DealQuotationPerSqmPriceIntegrationTest} and the owner's own documents already pin —
 * says for that row. And the reverse, which protects every quotation that exists today: on a
 * single-mode document a row's own mode is ignored and nothing is stored on the row.
 *
 * <p>Scope pinned here as rules, not accidents: Thai documents only; never a quotation that came
 * from a pricing request (its mode is the CEO's — that one is
 * {@code PricingRequestQuotationIntegrationTest}'s to prove, where the fixture lives).
 *
 * <p>The tile throughout is the owner's 60×120 (0.72 ตร.ม./แผ่น → 1.39 แผ่น/ตร.ม.), 10 แผ่น, priced
 * per ตร.ม. including VAT as the editor sends it since V192.2: 1,800 → 1,210.25 a piece; 790 → 531.16.
 */
class DealQuotationMixedPriceModeIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    private static final String NET = WastageCalculator.PRICE_MODE_NET;
    private static final String SPECIAL_SQM = WastageCalculator.PRICE_MODE_SPECIAL_SQM;
    private static final String DIRECT_NET = WastageCalculator.PRICE_MODE_DIRECT_NET;
    private static final String MIXED = WastageCalculator.PRICE_MODE_MIXED;

    // ── each row in its own mode ────────────────────────────────────────────────────────────

    @Test
    void aMixedDocument_pricesEachRowInItsOwnMode() {
        DealQuotationDto quotation = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), specialRow(), directNetRow()), salesActor);

        assertThat(quotation.priceMode()).isEqualTo(MIXED);
        DealQuotationItemDto net = quotation.items().get(0);
        assertThat(net.priceMode()).isEqualTo(NET);
        assertThat(net.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(net.discountPct()).isEqualByComparingTo("20");
        assertThat(net.netUnitPrice()).as("1,210.25 × 0.80").isEqualByComparingTo("968.20");
        assertThat(net.lineAmount()).isEqualByComparingTo("9682.00");

        DealQuotationItemDto special = quotation.items().get(1);
        assertThat(special.priceMode()).isEqualTo(SPECIAL_SQM);
        assertThat(special.specialPriceSqm()).isEqualByComparingTo("790");
        assertThat(special.unitPrice()).isEqualByComparingTo("1210.25");
        assertThat(special.netUnitPrice()).as("her QN6900981-1").isEqualByComparingTo("531.16");
        assertThat(special.lineAmount()).isEqualByComparingTo("5311.60");
        assertThat(special.discountPct()).as("ราคาพิเศษ carries no percent").isNull();

        DealQuotationItemDto direct = quotation.items().get(2);
        assertThat(direct.priceMode()).isEqualTo(DIRECT_NET);
        assertThat(direct.directNetPriceSqm()).isEqualByComparingTo("790.00");
        assertThat(direct.netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(direct.lineAmount()).isEqualByComparingTo("5311.60");
    }

    /** THE property (see the class comment). For each mode the same row is saved twice — alone on a single-mode
     * document, and inside a MIXED one beside rows of the other two modes — and everything the engine computes or
     * prints for it must come out identical. */
    @Test
    void eachRowOfAMixedDocument_comesOutExactlyAsOnASingleModeDocumentOfThatMode() {
        DealQuotationDto mixed = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), specialRow(), directNetRow()), salesActor);
        Map<String, ItemInput> alone = Map.of(NET, netRow("20"), SPECIAL_SQM, specialRow(), DIRECT_NET, directNetRow());

        for (DealQuotationItemDto inMix : mixed.items()) {
            String mode = inMix.priceMode();
            DealQuotationItemDto single = quotationService
                .create(deal(salesActor), request(mode, alone.get(mode).withPriceMode(null)), salesActor)
                .items().get(0);

            assertThat(inMix.unitPrice()).as("%s ราคา", mode).isEqualByComparingTo(single.unitPrice());
            assertThat(inMix.netUnitPrice()).as("%s คงเหลือ", mode).isEqualByComparingTo(single.netUnitPrice());
            assertThat(inMix.lineAmount()).as("%s เป็นเงิน", mode).isEqualByComparingTo(single.lineAmount());
            assertThat(inMix.discountPct()).as("%s ส่วนลด %%", mode).isEqualTo(single.discountPct());
            assertThat(inMix.specialPriceSqm()).as("%s ราคาพิเศษ", mode).isEqualTo(single.specialPriceSqm());
            assertThat(inMix.listPriceSqm()).as("%s ราคาตั้ง/ตร.ม.", mode).isEqualTo(single.listPriceSqm());
            assertThat(inMix.directNetPriceSqm()).as("%s ราคาสุทธิ/ตร.ม.", mode).isEqualTo(single.directNetPriceSqm());
            assertThat(inMix.calculationLine()).as("%s calculation line", mode).isEqualTo(single.calculationLine());
            assertThat(inMix.specialPriceLine()).as("%s ราคาพิเศษ sub-line", mode).isEqualTo(single.specialPriceLine());
            assertThat(inMix.quantity()).isEqualByComparingTo(single.quantity());
            assertThat(inMix.unit()).isEqualTo(single.unit());
        }
    }

    /** "make sure the ยอดรวม in final is correct" applies here as much as anywhere: รวมเป็นเงิน is the sum of the
     * lines whatever mode each was priced in, and VAT is taken once, on that. */
    @Test
    void theFootOfAMixedDocument_isTheSumOfItsLines_withVatTakenOnce() {
        DealQuotationDto quotation = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), specialRow(), directNetRow(), freight()), salesActor);

        BigDecimal sumOfLines = quotation.items().stream().map(DealQuotationItemDto::lineAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfLines).as("9,682.00 + 5,311.60 + 5,311.60 + 2 × 500.00").isEqualByComparingTo("21305.20");
        assertThat(quotation.subtotalAmount()).isEqualByComparingTo("21305.20");
        assertThat(quotation.vatAmount()).as("7% of 21,305.20").isEqualByComparingTo("1491.36");
        assertThat(quotation.grandTotal()).isEqualByComparingTo("22796.56");
        // A สินค้า/บริการอื่น line has no price mode on any document, mixed or not.
        assertThat(quotation.items().get(3).priceMode()).isNull();
    }

    // ── what is stored ──────────────────────────────────────────────────────────────────────

    @Test
    void theDocumentAndEachRow_storeTheirModes_andAReadServesThemBack() {
        DealQuotationDto created = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), specialRow(), directNetRow(), freight()), salesActor);

        assertThat(storedDocumentMode(created.id())).isEqualTo(MIXED);
        assertThat(storedRowModes(created.id())).containsExactly(NET, SPECIAL_SQM, DIRECT_NET, null);
        DealQuotationDto reread = quotationService.get(created.id(), salesActor);
        assertThat(reread.priceMode()).isEqualTo(MIXED);
        assertThat(reread.items()).extracting(DealQuotationItemDto::priceMode)
            .containsExactly(NET, SPECIAL_SQM, DIRECT_NET, null);
        // …and the figures survive the round trip through the row mapper, which prices nothing itself but decides each
        // row's printed lines from its mode.
        assertThat(reread.items().get(1).netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(reread.items().get(1).specialPriceLine()).isEqualTo(created.items().get(1).specialPriceLine())
            .isNotBlank();
        assertThat(reread.items().get(0).specialPriceLine()).as("a NET row prints no ราคาพิเศษ line").isNull();
    }

    // ── refusals ────────────────────────────────────────────────────────────────────────────

    /** No silent default: on a MIXED document a tile row that does not say how it is priced is refused, naming the
     * row — never quietly priced as NET off whatever fields it carries. */
    @Test
    void aMixedDocument_withATileRowThatStatesNoMode_isRefused() {
        long ticket = deal(salesActor);
        ApiException refused = refusal(() -> quotationService.create(ticket,
            request(MIXED, netRow("20"), specialRow().withPriceMode(null)), salesActor), HttpStatus.BAD_REQUEST);

        assertThat(refused.getMessage()).contains("รายการที่ 2").contains("วิธีคิดราคา");
        assertThat(quotationCount(ticket)).isZero();
    }

    /** The English form prices per ตร.ม. with the quantity itself in square metres — a different document shape per
     * row. Mixing is a Thai-document feature (what sales asked for). */
    @Test
    void aMixedDocument_inEnglish_isRefused() {
        long ticket = deal(salesActor);
        UpsertDealQuotationRequest english = new UpsertDealQuotationRequest(null, null, null, null, 50,
            "CASH_ON_DELIVERY", 0, 30, null, MIXED, "EN", "USD",
            List.of(perPiece("100.00", "0").withPriceMode(NET))).withRecipientType("OWNER");

        ApiException refused = refusal(() -> quotationService.create(ticket, english, salesActor),
            HttpStatus.BAD_REQUEST);
        assertThat(refused.getMessage()).contains("ภาษาไทย");
        assertThat(quotationCount(ticket)).isZero();
    }

    // ── editing ─────────────────────────────────────────────────────────────────────────────

    /** The editor saves a draft's rows WITH their ids, which takes the in-place UPDATE path — a different statement
     * from the INSERT. Changing one row's mode must reprice THAT row, leave the others alone, and rewrite the
     * stored mode. */
    @Test
    void changingOneRowsMode_onASavedDraft_repricesThatRowOnly() {
        DealQuotationDto created = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), netRow("10")), salesActor);
        long first = created.items().get(0).id();
        long second = created.items().get(1).id();

        DealQuotationDto updated = quotationService.update(created.id(),
            request(MIXED, withId(first, netRow("20")), withId(second, specialRow())), salesActor);

        assertThat(updated.items()).extracting(DealQuotationItemDto::id).containsExactly(first, second);
        assertThat(updated.items().get(0).netUnitPrice()).as("untouched").isEqualByComparingTo("968.20");
        assertThat(updated.items().get(1).priceMode()).isEqualTo(SPECIAL_SQM);
        assertThat(updated.items().get(1).netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(updated.items().get(1).discountPct()).isNull();
        assertThat(storedRowModes(created.id())).containsExactly(NET, SPECIAL_SQM);
    }

    /** Back to one mode for the whole document: every row is priced in it, and the per-row modes are CLEARED — a
     * leftover would otherwise sit unseen until the document was switched to MIXED again, and then decide a price. */
    @Test
    void switchingAMixedDocumentToOneMode_pricesEveryRowInIt_andClearsTheRowModes() {
        DealQuotationDto created = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), specialRow()), salesActor);
        long first = created.items().get(0).id();
        long second = created.items().get(1).id();

        DealQuotationDto updated = quotationService.update(created.id(),
            request(NET, withId(first, netRow("20")), withId(second, netRow("10"))), salesActor);

        assertThat(updated.priceMode()).isEqualTo(NET);
        assertThat(updated.items()).extracting(DealQuotationItemDto::priceMode).containsOnlyNulls();
        assertThat(updated.items().get(1).netUnitPrice()).as("1,210.25 × 0.90").isEqualByComparingTo("1089.23");
        assertThat(storedDocumentMode(created.id())).isEqualTo(NET);
        assertThat(storedRowModes(created.id())).containsExactly(null, null);
    }

    // ── copies ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aRevisionAndAReorder_carryTheMixAndEveryRowsMode() {
        long ticket = deal(salesActor);
        DealQuotationDto approved = approved(ticket,
            request(MIXED, netRow("20"), specialRow(), directNetRow(), freight()), salesActor);

        for (DealQuotationDto copy : List.of(
                quotationService.createReorder(approved.id(), salesActor),
                quotationService.createRevision(approved.id(), salesActor))) {
            assertThat(copy.priceMode()).as(copy.number()).isEqualTo(MIXED);
            assertThat(copy.items()).as(copy.number()).extracting(DealQuotationItemDto::priceMode)
                .containsExactly(NET, SPECIAL_SQM, DIRECT_NET, null);
            assertThat(copy.items()).extracting(DealQuotationItemDto::netUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("968.20"), new BigDecimal("531.16"), new BigDecimal("531.16"),
                    new BigDecimal("500.00"));
            assertThat(storedRowModes(copy.id())).containsExactly(NET, SPECIAL_SQM, DIRECT_NET, null);
        }
    }

    /** The whole life of the document: a mixed draft submits and approves like any other. Submit re-checks the
     * STORED rows for completeness, which is its own path through the mode. */
    @Test
    void aMixedDocument_submitsAndApproves_likeAnyOther() {
        DealQuotationDto created = quotationService.create(deal(salesActor),
            request(MIXED, netRow("20"), specialRow(), directNetRow()), salesActor);

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);

        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(approved.priceMode()).isEqualTo(MIXED);
        assertThat(approved.grandTotal()).isEqualByComparingTo(created.grandTotal());
    }

    // ── the live preview ────────────────────────────────────────────────────────────────────

    // ── what the customer's document prints ─────────────────────────────────────────────────

    // ── "has special pricing" — decided row by row ──────────────────────────────────────────

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** ราคาตั้ง 1,800 บาท/ตร.ม. รวม VAT, less {@code discountPct}% — stating NET as its own mode. */
    private static ItemInput netRow(String discountPct) {
        return tile(null, discountPct, null, "1800", null).withPriceMode(NET);
    }

    /** ราคาพิเศษ 790 บาท/ตร.ม. รวม VAT (ราคาตั้ง 1,800 only prints) — stating SPECIAL_SQM. */
    private static ItemInput specialRow() {
        return tile(null, null, "790", "1800", null).withPriceMode(SPECIAL_SQM);
    }

    /** ราคาสุทธิ 790 บาท/ตร.ม. รวม VAT against a ราคาตั้ง of 1,800 — stating DIRECT_NET. */
    private static ItemInput directNetRow() {
        return tile(null, null, null, "1800", "790").withPriceMode(DIRECT_NET);
    }

    /** The owner's 60×120 (0.72 ตร.ม./แผ่น, 2 แผ่น/กล่อง), which every tile row in this class is. */
    private static ItemInput tile(String unitPrice, String discountPct, String specialPriceSqm,
                                  String listPriceSqm, String directNetPriceSqm) {
        return item("60x120", "0.72", 2, unitPrice, discountPct, specialPriceSqm, listPriceSqm, directNetPriceSqm);
    }

    private String storedDocumentMode(long quotationId) {
        return jdbc.queryForObject("SELECT price_mode FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), String.class);
    }

    private List<String> storedRowModes(long quotationId) {
        return jdbc.queryForList(
            "SELECT price_mode FROM sales.quotation_item WHERE quotation_id = :id ORDER BY seq",
            Map.of("id", quotationId), String.class);
    }
}
