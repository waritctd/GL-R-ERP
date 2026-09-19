package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingClearanceFeeDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;

/**
 * V109's real selling-price formula (the CEO's own spreadsheet), quoted verbatim from that
 * migration's own header comment:
 *
 * <pre>
 * C  = P x E                                          goods cost in THB
 * i  = C x insurance_value_factor x insurance_rate x insurance_buffer     (1.15 x 0.0045 x B1)
 * F  = freight[origin_country, thickness_mm, qty_sqm]  flat THB, per factory shipment
 * T  = duty_pct[product_type]
 * S  = clearance_fee[qty_sqm]                          flat THB, per factory shipment
 * TC = [ (C + i + F) x (1 + T) x cost_buffer ] + S     (B2)
 * UC = TC / Q                                          Q = that factory shipment's total sqm
 * SP = UC x (1 + margin_pct) x selling_buffer, rounded HALF_UP to 2 decimal places
 * </pre>
 *
 * <p><b>Phase 2 CEO-pricing rounding change (owner-approved, 2026-09-19):</b> {@code SP} is no
 * longer rounded UP to the nearest {@code selling_price_round_up_to} — it is rounded HALF_UP to
 * 2 decimal places, full stop. {@code selling_price_round_up_to} ({@code
 * sales.pricing_formula_config.selling_price_round_up_to}) is consequently DEAD — the column
 * stays in the schema and the config DTO (no migration, nothing reads it destructively), but no
 * formula step consumes it any more. This applies to every price this engine computes from now
 * on, legacy PCRs included; an ALREADY-APPROVED {@code pricing_decision}'s stored
 * {@code approved_selling_price_per_requested_unit} is never rewritten — only a not-yet-approved
 * decision recomputes through the new rule. See {@link #sellingPrice} (renamed from the old
 * {@code roundUpSellingPrice}, which took a now-unused {@code roundUpTo} parameter).
 *
 * <p>insurance_buffer / cost_buffer / selling_buffer are deliberate COST BUFFERS, not VAT — the
 * customer quotation separately adds VAT 7%, untouched by this class.
 *
 * <p>This class owns exactly two things: (1) band/rate SELECTION against a config snapshot the
 * caller already fetched (half-open {@code [min, max)} lookups for freight/clearance, exact-match
 * for duty) — each throwing a loud, item/shipment-naming {@link ApiException} on a miss, NEVER
 * returning zero, per this codebase's pricing brief ("a missing band must never silently compute
 * as ฿0"); and (2) the pure arithmetic (insurance, cost buffer, unit cost, selling buffer,
 * round-up) that composes selected rates into a price. It does NOT decide how a pricing request's
 * items group into "factory shipments", nor how a shipment-flat F/S is allocated back across the
 * items that share it — that grouping is {@link LandedCostCalculator}'s job, since only that class
 * has the factory-quote/pricing-request structure the grouping depends on.
 *
 * <p>Deliberately free of DB access at the point of use (the caller fetches the config ONCE via
 * {@link #requireCurrentConfig()} and passes the already-fetched lists into the selection methods)
 * so every band edge and every arithmetic step can be hand-verified in a fast, DB-free unit test —
 * see {@code PricingFormulaEngineTest}. Real-DB coverage of the SEEDED config plus this selection
 * logic together lives in {@code LandedCostCalculatorFormulaIntegrationTest}.
 */
@Component
public class PricingFormulaEngine {
    /** Owner ruling 2026-08-16: {@code product_type} exists nowhere in deal data today (the
     * catalog has {@code collection}, free text, no type column) — every item defaults to this
     * until the CEO overrides it per item via {@code PricingDecisionService#overrideItemProductType}. */
    public static final String DEFAULT_PRODUCT_TYPE = "TILE";

    private final PricingFormulaConfigRepository formulaConfigs;

    public PricingFormulaEngine(PricingFormulaConfigRepository formulaConfigs) {
        this.formulaConfigs = formulaConfigs;
    }

    public PricingFormulaConfigDto requireCurrentConfig() {
        return formulaConfigs.findCurrent()
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่พบสูตรคำนวณราคาขายที่ใช้งานอยู่ (sales.pricing_formula_config) — กรุณาตั้งค่าสูตรคำนวณราคาก่อน"));
    }

    /**
     * Half-open {@code [min, max)} band lookup — min inclusive, max exclusive, NULL max = +infinity
     * (V109's own BAND CONVENTION note). Throws — never returns a zero-amount fallback — on a miss
     * OR on more than one match (the config's own write-time validation,
     * {@code PricingFormulaConfigController#validate}, already rejects overlapping bands, so more
     * than one match means the config was somehow corrupted; treated as a hard error, not silently
     * resolved by picking one). {@code itemLabel} names the item/shipment in the message so
     * Import/CEO can see exactly what needs fixing.
     *
     * <p>{@code originCountryCode} is matched EXACTLY (V151, "one canonical country, so the
     * freight lookup can join"): both this parameter and {@link PricingFreightRateDto#originCountryCode()}
     * are ISO 3166-1 alpha-2 codes FK-constrained to {@code price_catalog.country}, so an exact
     * {@code equals} is correct and sufficient — there is deliberately no fuzzy/case-insensitive
     * matching here any more. V151's whole point was that free-text-vs-code was the bug (every
     * freight lookup returned nothing, for all nine factories); a fuzzy matcher bridging the two
     * canonical codes back together would just be a second, redundant source of truth for the same
     * fact, and the exact defect class this repo keeps hitting.
     */
    public PricingFreightRateDto selectFreightRate(List<PricingFreightRateDto> rates, String originCountryCode,
                                                    BigDecimal thicknessMm, BigDecimal qtySqm, String itemLabel) {
        PricingFreightRateDto match = null;
        for (PricingFreightRateDto rate : rates) {
            if (!rate.originCountryCode().equals(originCountryCode)) {
                continue;
            }
            if (thicknessMm.compareTo(rate.thicknessMinMm()) < 0) {
                continue;
            }
            if (rate.thicknessMaxMm() != null && thicknessMm.compareTo(rate.thicknessMaxMm()) >= 0) {
                continue;
            }
            if (qtySqm.compareTo(rate.qtyMinSqm()) < 0) {
                continue;
            }
            if (rate.qtyMaxSqm() != null && qtySqm.compareTo(rate.qtyMaxSqm()) >= 0) {
                continue;
            }
            if (match != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "สูตรคำนวณราคามีอัตราค่าขนส่งที่ซ้อนทับกันสำหรับ " + itemLabel + " (ต้นทาง=" + originCountryCode
                        + " ความหนา=" + thicknessMm + " มม. จำนวน=" + qtySqm + " ตร.ม.) — กรุณาตรวจสอบสูตรคำนวณราคา");
            }
            match = rate;
        }
        if (match == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่พบอัตราค่าขนส่งสำหรับ " + itemLabel + " (ต้นทาง=" + originCountryCode
                    + " ความหนา=" + thicknessMm + " มม. จำนวน=" + qtySqm + " ตร.ม.) — กรุณาตั้งค่าสูตรคำนวณราคาให้ครอบคลุม");
        }
        return match;
    }

    /** Same half-open {@code [min, max)} convention as {@link #selectFreightRate}, keyed on
     * {@code qty_sqm} only (clearance fee does not vary by country or thickness — V109's own
     * schema has no such columns on {@code sales.pricing_clearance_fee}). */
    public PricingClearanceFeeDto selectClearanceFee(List<PricingClearanceFeeDto> fees, BigDecimal qtySqm,
                                                       String shipmentLabel) {
        PricingClearanceFeeDto match = null;
        for (PricingClearanceFeeDto fee : fees) {
            if (qtySqm.compareTo(fee.qtyMinSqm()) < 0) {
                continue;
            }
            if (fee.qtyMaxSqm() != null && qtySqm.compareTo(fee.qtyMaxSqm()) >= 0) {
                continue;
            }
            if (match != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "สูตรคำนวณราคามีค่าธรรมเนียมพิธีการที่ซ้อนทับกันสำหรับ " + shipmentLabel
                        + " (จำนวน=" + qtySqm + " ตร.ม.) — กรุณาตรวจสอบสูตรคำนวณราคา");
            }
            match = fee;
        }
        if (match == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่พบค่าธรรมเนียมพิธีการสำหรับ " + shipmentLabel + " (จำนวน=" + qtySqm
                    + " ตร.ม.) — กรุณาตั้งค่าสูตรคำนวณราคาให้ครอบคลุม");
        }
        return match;
    }

    /** Exact-match lookup (duty is not banded — one rate per {@code product_type} code). */
    public PricingDutyRateDto selectDutyRate(List<PricingDutyRateDto> rates, String productType, String itemLabel) {
        for (PricingDutyRateDto rate : rates) {
            if (rate.productType().equals(productType)) {
                return rate;
            }
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
            "ไม่พบอัตราอากรขาเข้าสำหรับประเภทสินค้า '" + productType + "' (" + itemLabel
                + ") — กรุณาตั้งค่าสูตรคำนวณราคาให้ครอบคลุม หรือเลือกประเภทสินค้าอื่น");
    }

    // ── Pure arithmetic — every THB amount is money4'd (HALF_UP to 4dp) at the point it becomes
    // a "line" in the formula, matching this codebase's existing convention for every other money
    // figure in this pipeline (LandedCostCalculator's pre-V109 goodsCost/freight/insurance/cif/
    // landedPerUnit were rounded the same way, independently, at each step). ─────────────────────

    public static BigDecimal money4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    /** i = C x insurance_value_factor x insurance_rate x insurance_buffer. */
    public BigDecimal insurance(BigDecimal goodsCostThb, PricingFormulaConfigDto config) {
        return money4(goodsCostThb
            .multiply(config.insuranceValueFactor())
            .multiply(config.insuranceRate())
            .multiply(config.insuranceBuffer()));
    }

    /** The incremental duty AMOUNT, decomposing {@code X * (1+T) = X + X*T} so
     * {@code import_duty_thb} keeps meaning "the duty line item in THB" the same way the pre-V109
     * engine's own {@code duty} column did — never a bare percentage. */
    public BigDecimal dutyAmount(BigDecimal cifThb, BigDecimal dutyPct) {
        return money4(cifThb.multiply(dutyPct));
    }

    /** TC = [(C + i + F) x (1 + T) x cost_buffer] + S, i.e.
     * {@code money4(money4(cif + dutyAmount) * costBuffer) + clearanceFee}, composed from
     * already-money4'd components exactly the way the pre-V109 engine composed its own
     * already-rounded intermediate lines (goodsCost, freight, insurance, cif, landedPerUnit each
     * independently {@code money4}'d) — so every intermediate is independently checkable in a
     * hand-computed test, not just the final total. */
    public BigDecimal totalLandedCost(BigDecimal cifThb, BigDecimal dutyAmountThb, BigDecimal costBuffer,
                                      BigDecimal clearanceFeeThb) {
        BigDecimal beforeClearance = money4(cifThb.add(dutyAmountThb).multiply(costBuffer));
        return money4(beforeClearance.add(clearanceFeeThb));
    }

    /** UC = TC / Q — kept at 8dp intermediate precision, matching every other physical-unit
     * conversion ratio already in this pipeline ({@code LandedCostCalculator#resolveSqmPerPiece}
     * and friends divide at scale 8 HALF_UP too), since UC is not itself a "line" in the
     * decomposition, only an intermediate on the way to a per-piece figure. */
    public BigDecimal unitCostPerSqm(BigDecimal totalLandedCostThb, BigDecimal totalSqm) {
        return totalLandedCostThb.divide(totalSqm, 8, RoundingMode.HALF_UP);
    }

    /**
     * SP = UC x (1 + margin_pct) x selling_buffer, rounded HALF_UP to 2dp.
     *
     * <p><b>Owner-approved rounding change (2026-09-19), superseding the old "RoundUp to nearest
     * {@code selling_price_round_up_to}" rule</b> (${@code roundUpSellingPrice}, removed — it took
     * a {@code roundUpTo} argument this method no longer has any use for). The old rule's own
     * worked example, ฿191.96 -&gt; ฿200 (nearest ฿10), no longer holds: ฿191.96 now prints as
     * ฿191.96. 2dp HALF_UP, not 4dp {@link #money4}, because this is the CUSTOMER-FACING selling
     * price — {@code sales.pricing_decision_item.proposed/approved_selling_price_per_requested_unit}
     * are NUMERIC(18,4) so a 4dp value would still fit the column, but the printed/quoted figure
     * (and every sibling Phase-2 price column, {@code list_unit_price} etc., V187) is 2dp money,
     * matching {@code sales.quotation_item.unit_price} (NUMERIC(14,2), V49) throughout the
     * direct-deal quotation this whole pricing chain eventually feeds.
     */
    public BigDecimal sellingPrice(BigDecimal costPerUnitThb, BigDecimal marginPct, BigDecimal sellingBuffer) {
        BigDecimal raw = costPerUnitThb.multiply(BigDecimal.ONE.add(marginPct)).multiply(sellingBuffer);
        return raw.setScale(2, RoundingMode.HALF_UP);
    }
}
