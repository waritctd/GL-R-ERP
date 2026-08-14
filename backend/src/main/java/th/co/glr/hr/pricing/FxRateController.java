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

    /**
     * Read gate, owner ruling 2026-08-02 — widened from issue #388's {@code ceo}/{@code import}
     * gate by exactly one role, to {@code sales}. NOT opened to every authenticated session: the
     * owner's words were "should only be to sale", and this set is the whole of that ruling.
     *
     * <p>The trigger was the deal-create modal's "ราคาตั้ง (ประมาณการ)" estimate (V112): a rep who
     * picks a catalog item needs the FX rate to convert its price to THB, and every catalog row is
     * EUR or USD — there are no THB rows at all — so gating the rate to the costing audience
     * blocked the feature for the one role it exists for. {@code sales} is sufficient and
     * necessary: {@code ROLE_PERMISSIONS.canCreateTickets} is {@code ['sales']} and
     * {@code TicketService.create} gates on {@code Set.of("sales")}, so no other role can open that
     * modal — {@code sales_manager} and {@code ceo} browse the pipeline but cannot create a deal.
     *
     * <p><b>THE TRIGGER IS GONE; THE RULING IS NOT.</b> That estimate was removed from the frontend
     * by PR #682 after UAT (reps read catalog-price-times-markup as a selling price), and issue
     * #748 then deleted its whole backend surface — {@code DealEstimateMarkupController}, its
     * repository, DTOs and the V112 table (dropped by V145). This javadoc used to {@code @link}
     * that controller as the precedent for the read widening below, so the reasoning is restated
     * here in full rather than left pointing at a deleted class: the widening was justified by what
     * an FX rate DISCLOSES, not by the feature that happened to ask for it. A rate only converts a
     * number already visible to this caller, so it survives its trigger. Re-narrowing it is a
     * separate owner decision, not a tidy-up that follows from #748.
     *
     * <p>The reasoning is deliberately narrower than #388's: an FX rate only converts a number that
     * is ALREADY visible to this caller — {@code /api/catalog/prices} is open to any authenticated
     * user per the 2026-08-01 ruling that closed #388 — and on its own reveals nothing about landed
     * cost or margin. It does NOT relax {@link PriceCalcConfigController}, whose {@code marginPct},
     * {@code importDutyPct} and landed-cost components stay gated to {@code RAW_COSTING_ROLES}
     * exactly as #388 decided; this rate alone cannot reconstruct that policy. Writes stay CEO-only.
     *
     * <p>If a test pinning this set fails, someone has re-narrowed or re-widened the gate: reopen
     * the ruling with the owner rather than editing the test to match the code.
     */
    private static final Set<String> READ_ROLES = Set.of("ceo", "import", "sales");

    private final FxRateRepository fxRates;
    private final SessionContext sessions;

    public FxRateController(FxRateRepository fxRates, SessionContext sessions) {
        this.fxRates  = fxRates;
        this.sessions = sessions;
    }

    /** Read is gated to {@link #READ_ROLES} — see that field for the ruling and its scope. */
    @GetMapping
    Map<String, List<FxRateDto>> list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        requireReadRole(user);
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

    private void requireReadRole(UserPrincipal user) {
        if (!READ_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO ฝ่ายจัดซื้อต่างประเทศ และฝ่ายขายเท่านั้น");
        }
    }
}
