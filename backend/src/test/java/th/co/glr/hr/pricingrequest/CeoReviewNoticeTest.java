package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
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

    private static PricingCostingWriteItem cost(long itemId, String landedPerUnit, String sqmPerUnit,
                                                String uncostableReason) {
        return new PricingCostingWriteItem(itemId, 1L, 1L, 1, null, "F", null, null, null, null, null, null,
            null, null, null, null, sqmPerUnit == null ? null : new BigDecimal(sqmPerUnit), null, null, null,
            null, null, 1L, 1, null, null, null, null, null, null, null,
            landedPerUnit == null ? null : new BigDecimal(landedPerUnit), null, null, null, null, uncostableReason, null);
    }
}
