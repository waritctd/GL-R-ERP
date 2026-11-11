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
            throw new FreightBandNotFoundException(
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

    // ── Container rule (CEO ruling 2026-11-06) ───────────────────────────────────────────────
    // The formula sheet's ค่าขนส่ง / ค่าชิปปิ้ง tables stop at the 801–1,000 ตร.ม. column, i.e. ONE
    // container, so a big shipment is charged per container, not once:
    //   * 20' containers only. Capacity by thickness: under 17 mm 800 ตร.ม.; 17 mm and up 450 ตร.ม.
    //   * Thicknesses of one factory shipment fill containers TOGETHER: container space
    //     E = Σ sqm_i / capacity(t_i); full = floor(E); r = E − full.
    //   * The leftover r is charged as one more full container when it is nearly full:
    //     thick-only group (every line 17 mm+): r × 450 > 250; otherwise (thin-only or mixed): r × 800 > 600.
    //   * Full containers pay the full-container rate (thin: the 801+ band, else the band holding 800;
    //     thick: the band holding 450); a mixed group pays the HIGHEST full rate among its thicknesses.
    //   * A leftover pays its own band per thickness present (looked up at r × that thickness's capacity,
    //     min 1 ตร.ม.); the HIGHEST is charged.
    // e.g. 3,910 @8 mm = 4.8875 → 5 containers; 3,250 @8 mm = 4 full + 50 ตร.ม.; 700 @19 mm = 1 full + 250.

    /** A 20' container holds this many ตร.ม. of tile under 17 mm thick (CEO ruling 2026-11-06). */
    public static final BigDecimal CONTAINER_SQM = new BigDecimal("800");
    /** ...and this many from 17 mm up. */
    static final BigDecimal THICK_CONTAINER_SQM = new BigDecimal("450");
    static final BigDecimal THICK_FROM_MM = new BigDecimal("17");
    /** A thin / mixed leftover above this (75% of 800) is charged as a full container. */
    static final BigDecimal NEARLY_FULL_REMAINDER_SQM = new BigDecimal("600");
    /** A thick-only leftover above this (of 450) is charged as a full container. */
    static final BigDecimal THICK_NEARLY_FULL_REMAINDER_SQM = new BigDecimal("250");
    /** The quantity whose band is the sheet's full-container column (801–1,000 ตร.ม.). */
    static final BigDecimal FULL_CONTAINER_LOOKUP_SQM = new BigDecimal("801");

    /** One (thickness, sqm) line of a shipment / freight group. */
    public record ContainerLine(BigDecimal thicknessMm, BigDecimal qtySqm) {}

    /** How a group splits into containers: {@code fullContainers} at the full rate, plus a leftover
     * {@code remainderSqm} (r × 450 for a thick-only group, else r × 800; zero when none). */
    public record ContainerSplit(int fullContainers, BigDecimal remainderSqm) {}

    /** A container-rule charge: the total THB amount and the split it was charged on. */
    public record ContainerCharge(BigDecimal amountThb, int fullContainers, BigDecimal remainderSqm,
                                  BigDecimal roundUpAboveSqm) {}

    /** Capacity of a 20' container for this thickness: 450 ตร.ม. from 17 mm up, else 800. */
    public static BigDecimal containerCapacitySqm(BigDecimal thicknessMm) {
        return isThick(thicknessMm) ? THICK_CONTAINER_SQM : CONTAINER_SQM;
    }

    private static boolean isThick(BigDecimal thicknessMm) {
        return thicknessMm.compareTo(THICK_FROM_MM) >= 0;
    }

    /** Container space E = Σ sqm_i / capacity(t_i). */
    public static BigDecimal containerUnits(List<ContainerLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (ContainerLine line : lines) {
            total = total.add(line.qtySqm().divide(containerCapacitySqm(line.thicknessMm()), 12, RoundingMode.HALF_UP));
        }
        return total;
    }

    private static boolean thickOnly(List<ContainerLine> lines) {
        return lines.stream().allMatch(l -> isThick(l.thicknessMm()));
    }

    /** Full containers and the leftover fraction r of a container (zero when rounded up). */
    private record Fill(int full, BigDecimal r, boolean thickOnly) {
        BigDecimal roundUpAboveSqm() {
            return thickOnly ? THICK_NEARLY_FULL_REMAINDER_SQM : NEARLY_FULL_REMAINDER_SQM;
        }
    }

    private static Fill fill(List<ContainerLine> lines) {
        // 9 dp so that thirds that sum to 0.999999999999 still count as one whole container
        BigDecimal e = containerUnits(lines).setScale(9, RoundingMode.HALF_UP);
        int full = e.setScale(0, RoundingMode.DOWN).intValueExact();
        BigDecimal r = e.subtract(BigDecimal.valueOf(full));
        boolean thick = thickOnly(lines);
        BigDecimal cap = thick ? THICK_CONTAINER_SQM : CONTAINER_SQM;
        BigDecimal limit = thick ? THICK_NEARLY_FULL_REMAINDER_SQM : NEARLY_FULL_REMAINDER_SQM;
        // 6 dp, like remainderSqm: 1.555556 × 450 must read as exactly 250, not 250.0000002
        if (r.multiply(cap).setScale(6, RoundingMode.HALF_UP).compareTo(limit) > 0) {
            return new Fill(full + 1, BigDecimal.ZERO, thick);
        }
        return new Fill(full, r, thick);
    }

    private static BigDecimal remainderSqm(Fill f) {
        return f.r().multiply(f.thickOnly() ? THICK_CONTAINER_SQM : CONTAINER_SQM).setScale(6, RoundingMode.HALF_UP);
    }

    public static ContainerSplit containerSplit(List<ContainerLine> lines) {
        Fill f = fill(lines);
        return new ContainerSplit(f.full(), remainderSqm(f));
    }

    /** F for a freight group (one factory shipment × origin country) under the container rule. */
    public ContainerCharge containerFreight(List<PricingFreightRateDto> rates, String originCountryCode,
                                            List<ContainerLine> lines, String itemLabel) {
        Fill f = fill(lines);
        BigDecimal amount = BigDecimal.ZERO;
        if (f.full() > 0) {
            BigDecimal highest = null;
            for (ContainerLine line : lines) {
                BigDecimal rate = fullFreightRate(rates, originCountryCode, line.thicknessMm(), thicknessLabel(itemLabel, line));
                highest = highest == null ? rate : highest.max(rate);
            }
            amount = amount.add(highest.multiply(BigDecimal.valueOf(f.full())));
        }
        if (f.r().signum() > 0) {
            BigDecimal highest = null;
            for (ContainerLine line : lines) {
                BigDecimal qty = atLeastOne(f.r().multiply(containerCapacitySqm(line.thicknessMm())));
                BigDecimal rate = selectFreightRate(rates, originCountryCode, line.thicknessMm(), qty, thicknessLabel(itemLabel, line)).amountThb();
                highest = highest == null ? rate : highest.max(rate);
            }
            amount = amount.add(highest);
        }
        return new ContainerCharge(money4(amount), f.full(), remainderSqm(f), f.roundUpAboveSqm());
    }

    private static String thicknessLabel(String label, ContainerLine line) {
        return label + " หนา " + line.thicknessMm() + " มม.";
    }

    // Thin: the sheet's full-container column (801+), or — when the table stops below it (e.g. 12–16 mm)
    // — the band holding 800. Thick: the band holding 450 (the 17–21 table has no 801+ band).
    private BigDecimal fullFreightRate(List<PricingFreightRateDto> rates, String originCountryCode,
                                       BigDecimal thicknessMm, String itemLabel) {
        BigDecimal cap = containerCapacitySqm(thicknessMm);
        if (isThick(thicknessMm)) {
            return selectFreightRate(rates, originCountryCode, thicknessMm, cap, itemLabel).amountThb();
        }
        PricingFreightRateDto full = findFreightRate(rates, originCountryCode, thicknessMm,
            FULL_CONTAINER_LOOKUP_SQM, itemLabel);
        if (full == null) {
            full = selectFreightRate(rates, originCountryCode, thicknessMm, cap, itemLabel);
        }
        return full.amountThb();
    }

    /** S for a whole shipment under the container rule (clearance has no thickness table). */
    public ContainerCharge containerClearance(List<PricingClearanceFeeDto> fees, List<ContainerLine> lines,
                                              String shipmentLabel) {
        Fill f = fill(lines);
        BigDecimal remainder = remainderSqm(f);
        BigDecimal amount = BigDecimal.ZERO;
        if (f.full() > 0) {
            PricingClearanceFeeDto full = findClearanceFee(fees, FULL_CONTAINER_LOOKUP_SQM, shipmentLabel);
            if (full == null) {
                full = selectClearanceFee(fees, CONTAINER_SQM, shipmentLabel);
            }
            amount = amount.add(full.amountThb().multiply(BigDecimal.valueOf(f.full())));
        }
        if (f.r().signum() > 0) {
            amount = amount.add(selectClearanceFee(fees, atLeastOne(remainder), shipmentLabel).amountThb());
        }
        return new ContainerCharge(money4(amount), f.full(), remainder, f.roundUpAboveSqm());
    }

    // The bands start at 1 ตร.ม.; a sliver of a leftover (e.g. 0.5) is priced as the first band.
    private static BigDecimal atLeastOne(BigDecimal qtySqm) {
        return qtySqm.max(BigDecimal.ONE);
    }

    private PricingFreightRateDto findFreightRate(List<PricingFreightRateDto> rates, String originCountryCode,
                                                  BigDecimal thicknessMm, BigDecimal qtySqm, String itemLabel) {
        try {
            return selectFreightRate(rates, originCountryCode, thicknessMm, qtySqm, itemLabel);
        } catch (ApiException missing) {
            return null;
        }
    }

    private PricingClearanceFeeDto findClearanceFee(List<PricingClearanceFeeDto> fees, BigDecimal qtySqm,
                                                    String shipmentLabel) {
        try {
            return selectClearanceFee(fees, qtySqm, shipmentLabel);
        } catch (ApiException missing) {
            return null;
        }
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
