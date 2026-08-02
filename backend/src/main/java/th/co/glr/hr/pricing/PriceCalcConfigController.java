package th.co.glr.hr.pricing;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/price-calc-configs")
public class PriceCalcConfigController {
    private static final Set<String> CEO_ROLES = Set.of("ceo");

    /**
     * Read gate, issue #388. This configuration <em>is</em> the margin policy — {@code marginPct},
     * {@code importDutyPct}, and every landed-cost component (freight/insurance/inland per sqm).
     * The read previously gated on authentication alone, so any session could pull it and, with
     * {@code /api/catalog/prices} and {@code /api/fx-rates}, reconstruct the whole cost→price model.
     *
     * <p>Scoped to {@code PricingCostingService.RAW_COSTING_ROLES} ({@code import}, {@code ceo}) —
     * the audience already trusted with raw landed cost inside the pricing chain. {@code import}
     * grants nothing it cannot already derive there; {@code sales} is deliberately excluded, since
     * a rep works from the approved selling price and never the cost side. Writes stay CEO-only.
     */
    private static final Set<String> READ_ROLES = Set.of("ceo", "import");

    private final PriceCalcConfigRepository priceConfigs;
    private final SessionContext sessions;

    public PriceCalcConfigController(PriceCalcConfigRepository priceConfigs, SessionContext sessions) {
        this.priceConfigs = priceConfigs;
        this.sessions     = sessions;
    }

    @GetMapping
    Map<String, List<PriceCalcConfigDto>> list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        requireCostingRole(user);
        return Map.of("configs", priceConfigs.findCurrentConfigs());
    }

    @PostMapping
    Map<String, PriceCalcConfigDto> update(
        @Valid @RequestBody UpdatePriceCalcConfigRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);
        PriceCalcConfigDto result = priceConfigs.createNewVersion(
            request.country(),
            request.freightPerSqm(),
            request.insurancePerSqm(),
            request.inlandFactoryToPortPerSqm(),
            request.inlandPortToWarehousePerSqm(),
            request.importDutyPct(),
            request.marginPct(),
            request.effectiveFrom(),
            user.id());
        return Map.of("config", result);
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
