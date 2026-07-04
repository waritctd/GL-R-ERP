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
    private static final Set<String> CEO_ROLES = Set.of("ceo", "admin");

    private final PriceCalcConfigRepository priceConfigs;
    private final SessionContext sessions;

    public PriceCalcConfigController(PriceCalcConfigRepository priceConfigs, SessionContext sessions) {
        this.priceConfigs = priceConfigs;
        this.sessions     = sessions;
    }

    @GetMapping
    Map<String, List<PriceCalcConfigDto>> list(HttpSession session) {
        sessions.requireUser(session);
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
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO/Admin เท่านั้น");
        }
    }
}
