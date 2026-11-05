package th.co.glr.hr.pricing;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
     * Read gate: the owner-ruled audience is {@code ceo}, {@code import}, {@code sales} and {@code
     * sales_manager}. NOT opened to every authenticated session: the owner's words were "should
     * only be to sale", and this set is the whole of the rulings.
     *
     * <p>{@code sales} reads it because a rep who picks a catalog item needs the FX rate to convert
     * its price to THB, and every catalog row is EUR or USD — there are no THB rows at all — so
     * gating the rate to the costing audience would block the one role it exists for. {@code
     * sales_manager} reads it because the deal page converts SM's foreign-currency prices to THB.
     * The widening is justified by what an FX rate DISCLOSES, not by any one feature that asks for
     * it: a rate only converts a number already visible to this caller. Re-narrowing it is an
     * owner decision, not a tidy-up.
     *
     * <p>{@code /api/catalog/prices} is open to any authenticated user, and an FX rate on its own
     * reveals nothing about landed cost or margin. It does NOT relax {@link PriceCalcConfigController}, whose {@code marginPct},
     * {@code importDutyPct} and landed-cost components stay gated to {@code RAW_COSTING_ROLES}
     * exactly as before; this rate alone cannot reconstruct that policy. Writes stay CEO-only.
     *
     * <p>If a test pinning this set fails, someone has re-narrowed or re-widened the gate: reopen
     * the ruling with the owner rather than editing the test to match the code.
     */
    private static final Set<String> READ_ROLES = Set.of("ceo", "import", "sales", "sales_manager");

    private final FxRateRepository fxRates;
    private final SessionContext sessions;
    private final BotFxFetchService botFx;

    public FxRateController(FxRateRepository fxRates, SessionContext sessions, BotFxFetchService botFx) {
        this.fxRates  = fxRates;
        this.sessions = sessions;
        this.botFx    = botFx;
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

    /**
     * Trigger the BOT FX fetch on demand instead of waiting for the 18:00 schedule. CEO-only, the
     * same gate as {@link #upsert} — this WRITES rates (via {@code BotFxFetchService.fetchNow}).
     * Returns what changed so the console can report "อัปเดต N/5 สกุล"; a missing token surfaces as
     * the service's own 503, not a silent no-op.
     */
    @PostMapping("/fetch-now")
    Map<String, BotFxFetchService.FxFetchResult> fetchNow(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        requireCeoRole(user);
        return Map.of("result", botFx.fetchNow());
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
