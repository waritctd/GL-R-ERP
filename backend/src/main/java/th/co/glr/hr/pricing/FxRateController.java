package th.co.glr.hr.pricing;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api/fx-rates")
public class FxRateController {
    private static final Set<String> CEO_ROLES = Set.of("ceo");

    private final FxRateRepository fxRates;
    private final SessionContext sessions;

    public FxRateController(FxRateRepository fxRates, SessionContext sessions) {
        this.fxRates  = fxRates;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, List<FxRateDto>> list(HttpSession session) {
        sessions.requireUser(session);
        return Map.of("fxRates", fxRates.findAll());
    }

    @PutMapping("/{currency}")
    Map<String, FxRateDto> upsert(
        @PathVariable String currency,
        @Valid @RequestBody UpsertFxRateRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);
        FxRateDto result = fxRates.upsert(
            currency.toUpperCase(),
            request.rateToThb(),
            request.effectiveDate(),
            user.id());
        return Map.of("fxRate", result);
    }

    private void requireCeoRole(UserPrincipal user) {
        if (!CEO_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้น");
        }
    }
}
