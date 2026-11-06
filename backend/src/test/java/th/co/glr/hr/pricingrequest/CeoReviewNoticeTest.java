package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.LandedCostCalculator.CalculationResult;
import th.co.glr.hr.pricingcosting.PricingCostingRepository.PricingCostingWriteItem;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;

/** Pure text contract of the CEO "ready to set the price" notice (no DB). */
class CeoReviewNoticeTest {

    private static final String HEADLINE = "ฝ่ายนำเข้า (สมชาย) ได้ราคาโรงงานครบทุกรายการแล้ว — รอ CEO ตั้งราคา";
    private static final String HEAD = HEADLINE + "\nคำขอราคา PR-0001 · ดีล T-0001 · ลูกค้า ACME\n\n";
    private static final String TODO = "\n\nสิ่งที่ต้องทำต่อ: เปิดคำขอราคา ใส่กำไร % แล้วบันทึกราคาขาย";

    @Test
    void threeCostedImportLines_listEachCostAndTheTotal() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "Tile A", "60x60", new BigDecimal("100.00"), new BigDecimal("100"), "ตร.ม.", null),
            item(2, "SCG", null, "  ", new BigDecimal("12.5"), new BigDecimal("12.5"), "ตร.ม.", null),
            withDescription(item(3, null, null, null, null, new BigDecimal("1250"), "กล่อง", null), "กระเบื้องพิเศษ"));
        CalculationResult costs = new CalculationResult(List.of(
            cost(1, "360.00", "0.36", null),
            cost(2, "90.50", "0.5", null),
            cost(3, "1234.5", null, null)), new BigDecimal("20000.5"));

        assertThat(CeoReviewNotice.message(HEADLINE, summary(), items, costs)).isEqualTo(HEAD
            + "รายการ (3):\n"
            + "• SCG Tile A 60x60 — 100 ตร.ม. · ต้นทุน 1,000.00 บาท/ตร.ม.\n"
            + "• SCG — 12.5 ตร.ม. · ต้นทุน 181.00 บาท/ตร.ม.\n"
            + "• กระเบื้องพิเศษ — 1,250 กล่อง · ต้นทุน 1,234.50 บาท/กล่อง\n"
            + "ต้นทุนรวม 20,000.50 บาท" + TODO);
    }

    @Test
    void twelveLines_showTenThenCountTheRest() {
        List<PricingRequestItemDto> items = new ArrayList<>();
        List<PricingCostingWriteItem> costed = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            items.add(item(i, "B", "M" + i, "60x60", new BigDecimal("10"), new BigDecimal("10"), "ตร.ม.", null));
            costed.add(cost(i, "36.00", "0.36", null));
        }
        String msg = CeoReviewNotice.message(HEADLINE, summary(), items,
            new CalculationResult(costed, new BigDecimal("1200")));

        assertThat(msg).contains("รายการ (12):\n");
        assertThat(msg.lines().filter(l -> l.startsWith("• "))).hasSize(10);
        assertThat(msg).contains("• B M10 60x60 — 10 ตร.ม. · ต้นทุน 100.00 บาท/ตร.ม.\nและอีก 2 รายการ\nต้นทุนรวม 1,200.00 บาท");
        assertThat(msg).doesNotContain("M11").doesNotContain("M12");
    }

    @Test
    void oneUncostableLine_isDashed_andThereIsNoTotalLine() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "A", "60x60", new BigDecimal("10"), new BigDecimal("10"), "ตร.ม.", null),
            item(2, "SCG", "B", "60x60", new BigDecimal("10"), new BigDecimal("10"), "ตร.ม.", null));
        CalculationResult costs = new CalculationResult(List.of(
            cost(1, "36.00", "0.36", null),
            cost(2, null, null, "ไม่พบอัตราค่าขนส่ง")), new BigDecimal("360"));

        assertThat(CeoReviewNotice.message(HEADLINE, summary(), items, costs)).isEqualTo(HEAD
            + "รายการ (2):\n"
            + "• SCG A 60x60 — 10 ตร.ม. · ต้นทุน 100.00 บาท/ตร.ม.\n"
            + "• SCG B 60x60 — 10 ตร.ม. · ต้นทุน -" + TODO);
    }

    @Test
    void nullCosts_everyImportLineIsDashed_noTotal() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "A", "60x60", new BigDecimal("10"), new BigDecimal("10"), "ตร.ม.", null));

        assertThat(CeoReviewNotice.message(HEADLINE, summary(), items, null)).isEqualTo(HEAD
            + "รายการ (1):\n"
            + "• SCG A 60x60 — 10 ตร.ม. · ต้นทุน -" + TODO);
    }

    @Test
    void mixedStockAndImport_stockLinesNameTheirSource_andTotalIsLabelledImportOnly() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "Imp", "60x60", new BigDecimal("10"), new BigDecimal("10"), "ตร.ม.", null),
            item(2, "SCG", "Th", "60x60", new BigDecimal("5"), new BigDecimal("5"), "ตร.ม.", "IN_THAILAND"),
            item(3, "SCG", "Tr", "60x60", new BigDecimal("6"), new BigDecimal("6"), "ตร.ม.", "IN_TRANSIT"));
        CalculationResult costs = new CalculationResult(List.of(cost(1, "36.00", "0.36", null)), new BigDecimal("360"));

        assertThat(CeoReviewNotice.message(HEADLINE, summary(), items, costs)).isEqualTo(HEAD
            + "รายการ (3):\n"
            + "• SCG Imp 60x60 — 10 ตร.ม. · ต้นทุน 100.00 บาท/ตร.ม.\n"
            + "• SCG Th 60x60 — 5 ตร.ม. · สต็อกในไทย\n"
            + "• SCG Tr 60x60 — 6 ตร.ม. · สินค้าระหว่างขนส่ง\n"
            + "ต้นทุนรวมสินค้านำเข้า 360.00 บาท" + TODO);
    }

    @Test
    void stockOnly_noCosts_noTotalLine() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "Th", "60x60", new BigDecimal("5"), new BigDecimal("5"), "ตร.ม.", "IN_THAILAND"));

        assertThat(CeoReviewNotice.message(HEADLINE, summary(), items, null)).isEqualTo(HEAD
            + "รายการ (1):\n"
            + "• SCG Th 60x60 — 5 ตร.ม. · สต็อกในไทย" + TODO);
    }

    @Test
    void quantityInPieces_whenNoSqm_usesRequestedUnit() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "A", "60x60", null, new BigDecimal("1250.00"), "แผ่น", null));

        assertThat(CeoReviewNotice.message(HEADLINE, summary(), items, null))
            .contains("• SCG A 60x60 — 1,250 แผ่น · ต้นทุน -");
    }

    @Test
    void nonSqmUnitCost_isPerRequestedUnit_andFallsBackToหน่วย() {
        List<PricingRequestItemDto> items = List.of(
            item(1, "SCG", "A", "60x60", null, new BigDecimal("4"), "แผ่น", null),
            item(2, "SCG", "B", "60x60", null, new BigDecimal("4"), null, null));
        CalculationResult costs = new CalculationResult(List.of(
            cost(1, "123.456", null, null), cost(2, "10", "0", null)), new BigDecimal("133.456"));

        String msg = CeoReviewNotice.message(HEADLINE, summary(), items, costs);

        assertThat(msg).contains("• SCG A 60x60 — 4 แผ่น · ต้นทุน 123.46 บาท/แผ่น");
        assertThat(msg).contains("• SCG B 60x60 — 4 · ต้นทุน 10.00 บาท/หน่วย");
    }

    @Test
    void costsOrNull_returnsTheCalculatorsResult() {
        CalculationResult result = new CalculationResult(List.of(), BigDecimal.TEN);
        LandedCostCalculator calc = new LandedCostCalculator(null, null, null, null, null, null) {
            @Override
            public CalculationResult calculate(PricingRequestSummaryDto s) {
                return result;
            }
        };

        assertThat(CeoReviewNotice.costsOrNull(calc, summary())).isSameAs(result);
    }

    @Test
    void costsOrNull_swallowsACalculatorFailure() {
        LandedCostCalculator calc = new LandedCostCalculator(null, null, null, null, null, null) {
            @Override
            public CalculationResult calculate(PricingRequestSummaryDto s) {
                throw new IllegalStateException("no freight rate");
            }
        };

        assertThat(CeoReviewNotice.costsOrNull(calc, summary())).isNull();
    }

    // ── fixtures ──

    private static PricingRequestSummaryDto summary() {
        return new PricingRequestSummaryDto(1L, "PR-0001", 1L, "T-0001", "โครงการ", "ACME", 1L, "DESIGNER", null,
            "Designer", "READY_FOR_CEO_REVIEW", 1L, "เซล", null, null, null, null, "THB", null, 1, 1, null,
            null, null, null, null, null, null);
    }

    private static PricingRequestItemDto item(long id, String brand, String model, String size, BigDecimal sqm,
                                              BigDecimal qty, String unit, String stockSource) {
        return new PricingRequestItemDto(id, 1L, null, null, null, brand, model, null, null, null, size, null,
            qty, sqm, unit, null, "CONFIRMED", (LocalDate) null, null, null, (int) id, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, true, null, null, null, null, stockSource, (LocalDate) null);
    }

    private static PricingRequestItemDto withDescription(PricingRequestItemDto i, String description) {
        return new PricingRequestItemDto(i.id(), i.pricingRequestId(), null, null, null, null, null, description,
            null, null, null, null, i.requestedQty(), i.requestedQtySqm(), i.requestedUnit(), null, "CONFIRMED",
            (LocalDate) null, null, null, i.sortOrder(), null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, true,
            null, null, null, null, i.stockSource(), (LocalDate) null);
    }

    private static PricingCostingWriteItem cost(long itemId, String landedPerUnit, String sqmPerUnit,
                                                String uncostableReason) {
        return new PricingCostingWriteItem(itemId, 1L, 1L, 1, null, "F", null, null, null, null, null, null,
            null, null, null, null, sqmPerUnit == null ? null : new BigDecimal(sqmPerUnit), null, null, null,
            null, null, 1L, 1, null, null, null, null, null, null, null,
            landedPerUnit == null ? null : new BigDecimal(landedPerUnit), null, null, null, null, uncostableReason);
    }
}
