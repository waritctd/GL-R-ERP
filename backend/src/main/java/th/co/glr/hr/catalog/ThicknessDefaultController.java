package th.co.glr.hr.catalog;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/** CEO entry point for catalogue thickness defaults (V152). Write gate mirrors PricingFormulaConfigController:
 * {ceo} only. Read is CEO-only too — unlike the formula config, which {import} may read, this list exposes no
 * price and is purely an administrative to-do, so there is no reason to widen it. */
@RestController
@RequestMapping("/api/catalog/thickness-defaults")
public class ThicknessDefaultController {

    private static final Set<String> CEO_ROLES = Set.of("ceo");

    private final ThicknessDefaultRepository thicknessDefaults;
    private final SessionContext sessions;

    public ThicknessDefaultController(ThicknessDefaultRepository thicknessDefaults, SessionContext sessions) {
        this.thicknessDefaults = thicknessDefaults;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, Object> list(HttpSession session) {
        requireCeoRole(sessions.requireUser(session));
        List<ThicknessDefaultDtos.ThicknessGapDto> gaps = thicknessDefaults.listGaps();
        return Map.of(
            "gaps", gaps,
            "rowsStillMissingThickness", thicknessDefaults.countRowsStillMissingThickness());
    }

    @PutMapping
    Map<String, Object> save(
        @Valid @RequestBody ThicknessDefaultRequests.SaveThicknessDefaultsRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);
        int touched = thicknessDefaults.saveAll(request.entries(), user.id());
        // Return the refreshed gap list so the editor re-renders from server truth rather than patching its own local
        // state -- the remaining count is the number the CEO is working down.
        return Map.of(
            "saved", touched,
            "gaps", thicknessDefaults.listGaps(),
            "rowsStillMissingThickness", thicknessDefaults.countRowsStillMissingThickness());
    }

    private void requireCeoRole(UserPrincipal user) {
        if (!CEO_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้น");
        }
    }
}
