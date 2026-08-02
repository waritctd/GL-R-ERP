package th.co.glr.hr.pricing;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Collections;
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

/**
 * Deal-create modal's "ราคาตั้ง (ประมาณการ)" display multiplier (V112). The catalog/supplier
 * price is a FACTORY cost; the owner does not want that raw number shown to a rep as
 * "ราคาตั้ง" — it must be multiplied by this CEO-configurable figure first so the on-screen
 * number approximates a real selling price.
 *
 * <p><b>Read is open to any authenticated user, deliberately</b> — the same reasoning as the
 * 2026-08-02 FX-rate widening ({@link FxRateController}): every rep who can pick a catalog item
 * needs this multiplier to render the estimate, and the multiplier alone (a single bare number,
 * no country/cost breakdown) reveals nothing about landed cost or margin.
 *
 * <p><b>This is NOT {@code sales.price_calc_config.margin_pct}</b>, the real margin policy that
 * issue #388 deliberately locked {@code sales} out of reading — this table carries none of that
 * policy's fields (no country, no landed-cost components, no effective-dating) and must never be
 * conflated with it. Write stays CEO-only.
 */
@RestController
@RequestMapping("/api/deal-estimate-markup")
public class DealEstimateMarkupController {
    private static final Set<String> CEO_ROLES = Set.of("ceo");

    private final DealEstimateMarkupRepository markup;
    private final SessionContext sessions;

    public DealEstimateMarkupController(DealEstimateMarkupRepository markup, SessionContext sessions) {
        this.markup   = markup;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, DealEstimateMarkupDto> get(HttpSession session) {
        sessions.requireUser(session);
        // Collections.singletonMap (not Map.of, which throws on a null value) so a missing seed
        // row degrades to { dealEstimateMarkup: null } instead of a 500 — the deal-create modal's
        // estimateReady already treats a null multiplier as "not ready" and hides the section.
        return Collections.singletonMap("dealEstimateMarkup", markup.findCurrent().orElse(null));
    }

    @PutMapping
    Map<String, DealEstimateMarkupDto> update(
        @Valid @RequestBody UpdateDealEstimateMarkupRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);
        DealEstimateMarkupDto result = markup.update(request.multiplier(), user.id());
        return Map.of("dealEstimateMarkup", result);
    }

    private void requireCeoRole(UserPrincipal user) {
        if (!CEO_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้น");
        }
    }
}
