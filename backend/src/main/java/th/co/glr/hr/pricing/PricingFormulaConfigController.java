package th.co.glr.hr.pricing;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingClearanceFeeDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.ClearanceFeeRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.CreatePricingFormulaConfigRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.DutyRateRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.FreightRateRequest;

/** BRANCH 1 of the sales pricing-formula redesign: config storage + CEO editing UI only. Writes stay CEO-only.
 * This controller reads/writes sales.pricing_formula_config and its three child tables; it does NOT calculate
 * a selling price (that is later branches, on top of this config). */
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
        return Map.of("formulaConfig", requireCurrentConfig());
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

    // Freight-row add / remove (issue #436). These two endpoints are strictly additive: they do not change the
    // whole-config POST, its request shape, or its validation. Write gate is unchanged in shape: {ceo} only, same
    // requireCeoRole() as the POST above.

    /** Adds one freight row and stores the result as a new config version. A row that would overlap an existing one
     * is a 400, never a silent save. */
    @PostMapping("/freight-rates")
    Map<String, PricingFormulaConfigDto> addFreightRate(
        @Valid @RequestBody FreightRateRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);

        PricingFormulaConfigDto current = requireCurrentConfig();
        List<FreightRateRequest> freightRates = toFreightRequests(current.freightRates());
        freightRates.add(request);

        CreatePricingFormulaConfigRequest full = withFreightRates(current, freightRates);
        validate(full);
        return Map.of("formulaConfig", formulaConfigs.createNewVersion(full, user.id()));
    }

    /** Removes one freight row (identified by its id in the CURRENT version) and stores the result as a new config
     * version. The id must belong to the current version: passing a historical version's row id is a 404, not a
     * silent no-op. Old versions are audit records and stay untouched. */
    @DeleteMapping("/freight-rates/{freightRateId}")
    Map<String, PricingFormulaConfigDto> deleteFreightRate(
        @PathVariable("freightRateId") long freightRateId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);

        PricingFormulaConfigDto current = requireCurrentConfig();
        PricingFreightRateDto target = current.freightRates().stream()
            .filter(rate -> rate.freightRateId() == freightRateId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบค่าขนส่งที่ต้องการลบในสูตรปัจจุบัน"));

        // The request record forbids an empty freight list (@NotEmpty), and a config with no freight matrix at all can
        // price nothing. Enforce it here too, since this path builds the request programmatically and so never passes
        // through bean validation.
        if (current.freightRates().size() == 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีค่าขนส่งอย่างน้อย 1 รายการ");
        }
        validateRemovalLeavesNoInteriorGap(current.freightRates(), target);

        List<FreightRateRequest> freightRates = toFreightRequests(current.freightRates().stream()
            .filter(rate -> rate.freightRateId() != freightRateId)
            .toList());

        CreatePricingFormulaConfigRequest full = withFreightRates(current, freightRates);
        validate(full);
        return Map.of("formulaConfig", formulaConfigs.createNewVersion(full, user.id()));
    }

    /** "Deleting a row must not open a gap that makes a real order unpriceable" (#436). Every deletion removes
     * coverage, so the rule cannot be "no order may become unpriceable" -- that would forbid deleting anything. */
    private void validateRemovalLeavesNoInteriorGap(List<PricingFreightRateDto> all, PricingFreightRateDto target) {
        List<PricingFreightRateDto> sameThicknessBand = all.stream()
            .filter(rate -> rate.originCountryCode().equals(target.originCountryCode()))
            .filter(rate -> rate.thicknessMinMm().compareTo(target.thicknessMinMm()) == 0)
            .filter(rate -> rate.thicknessMaxMm().compareTo(target.thicknessMaxMm()) == 0)
            .sorted(Comparator.comparing(PricingFreightRateDto::qtyMinSqm))
            .toList();

        if (sameThicknessBand.size() > 1) {
            // Compare by id, not by value: two rows can share a qtyMinSqm only if they overlap, which validate() already
            // rejects, but an id comparison cannot be fooled either way.
            long lowestId = sameThicknessBand.get(0).freightRateId();
            long highestId = sameThicknessBand.get(sameThicknessBand.size() - 1).freightRateId();
            if (target.freightRateId() != lowestId && target.freightRateId() != highestId) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ลบไม่ได้: จะทำให้ช่วงจำนวน (ตร.ม.) ขาดตอนตรงกลาง — " + target.originCountryName()
                        + " หนา " + target.thicknessMinMm() + "-" + target.thicknessMaxMm() + " มม. ช่วง "
                        + target.qtyMinSqm() + "-" + (target.qtyMaxSqm() == null ? "ไม่จำกัด" : target.qtyMaxSqm())
                        + " ตร.ม. ลบได้เฉพาะช่วงบนสุดหรือล่างสุด");
            }
            return;
        }

        // The row is the last one in its thickness band, so removing it empties that band. Apply the same edge-only
        // rule one level up, across the country's thickness ladder.
        TreeSet<BigDecimal> thicknessMins = new TreeSet<>();
        for (PricingFreightRateDto rate : all) {
            if (rate.originCountryCode().equals(target.originCountryCode())) {
                thicknessMins.add(rate.thicknessMinMm());
            }
        }
        if (thicknessMins.size() > 1
            && thicknessMins.first().compareTo(target.thicknessMinMm()) != 0
            && thicknessMins.last().compareTo(target.thicknessMinMm()) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ลบไม่ได้: จะทำให้ช่วงความหนาขาดตอนตรงกลาง — " + target.originCountryName()
                    + " หนา " + target.thicknessMinMm() + "-" + target.thicknessMaxMm()
                    + " มม. ลบได้เฉพาะช่วงความหนาบนสุดหรือล่างสุด");
        }
    }

    /** Current config or 404 -- the add/remove endpoints amend a version, they cannot create one. */
    private PricingFormulaConfigDto requireCurrentConfig() {
        return formulaConfigs.findCurrent()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบสูตรคำนวณราคาขาย"));
    }

    private List<FreightRateRequest> toFreightRequests(List<PricingFreightRateDto> rates) {
        List<FreightRateRequest> requests = new ArrayList<>();
        for (PricingFreightRateDto rate : rates) {
            requests.add(new FreightRateRequest(rate.originCountryCode(), rate.thicknessMinMm(),
                rate.thicknessMaxMm(), rate.qtyMinSqm(), rate.qtyMaxSqm(), rate.amountThb()));
        }
        return requests;
    }

    /** Rebuilds the whole-config request from the current version, swapping in a new freight list. */
    private CreatePricingFormulaConfigRequest withFreightRates(
        PricingFormulaConfigDto current,
        List<FreightRateRequest> freightRates
    ) {
        List<DutyRateRequest> dutyRates = new ArrayList<>();
        for (PricingDutyRateDto duty : current.dutyRates()) {
            dutyRates.add(new DutyRateRequest(duty.productType(), duty.productLabel(), duty.dutyPct()));
        }
        List<ClearanceFeeRequest> clearanceFees = new ArrayList<>();
        for (PricingClearanceFeeDto clearance : current.clearanceFees()) {
            clearanceFees.add(new ClearanceFeeRequest(clearance.qtyMinSqm(), clearance.qtyMaxSqm(), clearance.amountThb()));
        }
        return new CreatePricingFormulaConfigRequest(
            current.insuranceValueFactor(), current.insuranceRate(), current.insuranceBuffer(),
            current.costBuffer(), current.sellingBuffer(), current.defaultMarginPct(),
            current.sellingPriceRoundUpTo(), current.effectiveFrom(),
            freightRates, dutyRates, clearanceFees);
    }

    /** Bean validation (@NotNull/@DecimalMin/etc. on the request record) already covers per-field bounds. This
     * covers the cross-field/cross-row checks a single-field annotation can't express: band ordering and overlap. */
    private void validate(CreatePricingFormulaConfigRequest request) {
        // Bands are half-open [min, max) -- see V109's BAND CONVENTION note.
        for (FreightRateRequest freight : request.freightRates()) {
            if (freight.thicknessMinMm().compareTo(freight.thicknessMaxMm()) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ช่วงความหนาไม่ถูกต้อง: " + freight.originCountryCode() + " " + freight.thicknessMinMm() + "-" + freight.thicknessMaxMm());
            }
            if (freight.qtyMaxSqm() != null && freight.qtyMinSqm().compareTo(freight.qtyMaxSqm()) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ช่วงจำนวน (ตร.ม.) ไม่ถูกต้อง: " + freight.originCountryCode() + " " + freight.thicknessMinMm() + "-" + freight.thicknessMaxMm());
            }
        }
        // Overlap check within the same origin_country: two freight rows conflict when BOTH their thickness ranges AND
        // their quantity ranges overlap -- that is the real ambiguity condition for the freight lookup, regardless of
        // whether the two rows happen to share the exact same thickness band.
        Map<String, List<FreightRateRequest>> byCountry = new java.util.LinkedHashMap<>();
        for (FreightRateRequest freight : request.freightRates()) {
            byCountry.computeIfAbsent(freight.originCountryCode(), k -> new ArrayList<>()).add(freight);
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

    /** Bands are HALF-OPEN [min, max) -- min inclusive, max exclusive, NULL max = +infinity (see V109's BAND
     * CONVENTION note). They must NOT be flagged, since 101 belongs to the second band only. Two half-open bands
     * overlap iff a.min < b.max AND b.min < a.max. */
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
