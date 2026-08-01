package th.co.glr.hr.pricing;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.ClearanceFeeRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.CreatePricingFormulaConfigRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.DutyRateRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.FreightRateRequest;

/**
 * BRANCH 1 of the sales pricing-formula redesign: config storage + CEO editing UI only. This
 * controller reads/writes {@code sales.pricing_formula_config} and its three child tables; it does
 * NOT calculate a selling price (that is later branches, on top of this config).
 *
 * <p>This is a NEW endpoint, not a change to any existing one -- it does not touch
 * {@code /api/price-calc-configs} (PriceCalcConfigController) or any of its authorization. The read
 * gate mirrors that endpoint's existing precedent (issue #388): {@code ceo}/{@code import} may read
 * this cost-model config, everyone else is denied, since it is itself the margin policy (freight,
 * duty, clearance amounts, and the margin/buffer constants). Writes stay CEO-only.
 */
@RestController
@RequestMapping("/api/pricing-formula-config")
public class PricingFormulaConfigController {
    private static final Set<String> CEO_ROLES = Set.of("ceo");
    private static final Set<String> READ_ROLES = Set.of("ceo", "import");

    private final PricingFormulaConfigRepository formulaConfigs;
    private final SessionContext sessions;

    public PricingFormulaConfigController(PricingFormulaConfigRepository formulaConfigs, SessionContext sessions) {
        this.formulaConfigs = formulaConfigs;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, PricingFormulaConfigDto> get(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        requireCostingRole(user);
        PricingFormulaConfigDto dto = formulaConfigs.findCurrent()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบสูตรคำนวณราคาขาย"));
        return Map.of("formulaConfig", dto);
    }

    @PostMapping
    Map<String, PricingFormulaConfigDto> update(
        @Valid @RequestBody CreatePricingFormulaConfigRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);
        validate(request);
        PricingFormulaConfigDto result = formulaConfigs.createNewVersion(request, user.id());
        return Map.of("formulaConfig", result);
    }

    /**
     * Bean validation (@NotNull/@DecimalMin/etc. on the request record) already covers per-field
     * bounds. This covers the cross-field/cross-row checks a single-field annotation can't express:
     * band ordering and overlap. Overlapping bands make the lookup this config feeds ambiguous
     * (branches 3-5 would have to silently pick one), so they are rejected here as a 400 rather
     * than allowed to reach storage.
     */
    private void validate(CreatePricingFormulaConfigRequest request) {
        // Bands are half-open [min, max) -- see V109's BAND CONVENTION note. min == max would be
        // an empty band that can never match anything, so it is rejected here too, not just
        // min > max (mirrors the DB CHECK constraints, but as a clean 400 instead of a raw SQL
        // constraint violation surfacing at insert time).
        for (FreightRateRequest freight : request.freightRates()) {
            if (freight.thicknessMinMm().compareTo(freight.thicknessMaxMm()) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ช่วงความหนาไม่ถูกต้อง: " + freight.originCountry() + " " + freight.thicknessMinMm() + "-" + freight.thicknessMaxMm());
            }
            if (freight.qtyMaxSqm() != null && freight.qtyMinSqm().compareTo(freight.qtyMaxSqm()) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ช่วงจำนวน (ตร.ม.) ไม่ถูกต้อง: " + freight.originCountry() + " " + freight.thicknessMinMm() + "-" + freight.thicknessMaxMm());
            }
        }
        // Overlap check within the same origin_country: two freight rows conflict when BOTH their
        // thickness ranges AND their quantity ranges overlap -- that is the real ambiguity
        // condition for the freight lookup, regardless of whether the two rows happen to share the
        // exact same thickness band. Grouping by (country, thickness) and only comparing quantity
        // bands within an identical thickness band (the old approach) missed the case of two
        // DIFFERENT, overlapping thickness bands for the same country (e.g. Italy [3,8) and Italy
        // [5,10)) -- those never landed in the same group and so were never compared. A single
        // pairwise pass across all of a country's rows catches both: two rows with the identical
        // thickness band always have thicknessOverlap == true (a band overlaps itself), so the
        // same-thickness case is still covered.
        Map<String, List<FreightRateRequest>> byCountry = new java.util.LinkedHashMap<>();
        for (FreightRateRequest freight : request.freightRates()) {
            byCountry.computeIfAbsent(freight.originCountry(), k -> new ArrayList<>()).add(freight);
        }
        for (Map.Entry<String, List<FreightRateRequest>> entry : byCountry.entrySet()) {
            List<FreightRateRequest> group = entry.getValue();
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    FreightRateRequest a = group.get(i);
                    FreightRateRequest b = group.get(j);
                    boolean thicknessOverlap = bandsOverlap(a.thicknessMinMm(), a.thicknessMaxMm(), b.thicknessMinMm(), b.thicknessMaxMm());
                    boolean qtyOverlap = bandsOverlap(a.qtyMinSqm(), a.qtyMaxSqm(), b.qtyMinSqm(), b.qtyMaxSqm());
                    if (thicknessOverlap && qtyOverlap) {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                            "ช่วงความหนาและช่วงจำนวน (ตร.ม.) ซ้อนทับกัน: " + entry.getKey()
                                + " หนา " + a.thicknessMinMm() + "-" + a.thicknessMaxMm() + " มม. (" + a.qtyMinSqm() + "-" + (a.qtyMaxSqm() == null ? "ไม่จำกัด" : a.qtyMaxSqm()) + " ตร.ม.)"
                                + " กับ " + b.thicknessMinMm() + "-" + b.thicknessMaxMm() + " มม. (" + b.qtyMinSqm() + "-" + (b.qtyMaxSqm() == null ? "ไม่จำกัด" : b.qtyMaxSqm()) + " ตร.ม.)");
                    }
                }
            }
        }

        Set<String> productTypes = new HashSet<>();
        for (DutyRateRequest duty : request.dutyRates()) {
            if (!productTypes.add(duty.productType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทสินค้าซ้ำ: " + duty.productType());
            }
        }

        for (ClearanceFeeRequest clearance : request.clearanceFees()) {
            if (clearance.qtyMaxSqm() != null && clearance.qtyMinSqm().compareTo(clearance.qtyMaxSqm()) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ช่วงจำนวน (ตร.ม.) ของค่าธรรมเนียมพิธีการไม่ถูกต้อง: " + clearance.qtyMinSqm() + "-" + clearance.qtyMaxSqm());
            }
        }
        List<ClearanceFeeRequest> clearanceFees = request.clearanceFees();
        for (int i = 0; i < clearanceFees.size(); i++) {
            for (int j = i + 1; j < clearanceFees.size(); j++) {
                if (bandsOverlap(clearanceFees.get(i).qtyMinSqm(), clearanceFees.get(i).qtyMaxSqm(),
                                  clearanceFees.get(j).qtyMinSqm(), clearanceFees.get(j).qtyMaxSqm())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ช่วงจำนวน (ตร.ม.) ของค่าธรรมเนียมพิธีการซ้อนทับกัน");
                }
            }
        }
    }

    /**
     * Bands are HALF-OPEN [min, max) -- min inclusive, max exclusive, NULL max = +infinity (see
     * V109's BAND CONVENTION note). Two half-open bands overlap iff a.min < b.max AND b.min <
     * a.max. This is deliberately a strict {@code <}, not {@code <=}: under the old closed-interval
     * test, contiguous bands like [1,101) and [101,451) would have been reported as overlapping
     * (1 <= 451 and 101 <= 101 -- both true), which is wrong once max is exclusive. They must NOT
     * be flagged, since 101 belongs to the second band only.
     */
    private boolean bandsOverlap(BigDecimal aMin, BigDecimal aMax, BigDecimal bMin, BigDecimal bMax) {
        boolean aMinLtBMax = bMax == null || aMin.compareTo(bMax) < 0;
        boolean bMinLtAMax = aMax == null || bMin.compareTo(aMax) < 0;
        return aMinLtBMax && bMinLtAMax;
    }

    private void requireCeoRole(UserPrincipal user) {
        if (!CEO_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้น");
        }
    }

    private void requireCostingRole(UserPrincipal user) {
        if (!READ_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO และฝ่ายจัดซื้อต่างประเทศเท่านั้น");
        }
    }
}
