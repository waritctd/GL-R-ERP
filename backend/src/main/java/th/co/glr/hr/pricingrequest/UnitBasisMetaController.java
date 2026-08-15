package th.co.glr.hr.pricingrequest;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

/**
 * The unit-basis vocabulary, served to clients instead of being re-declared by each of them.
 *
 * <p><strong>Why this exists.</strong> Modelled directly on {@code DealStageMetaController}: the
 * frontend carried a hand-maintained copy of {@link UnitBasis}'s four codes with their Thai
 * display units ({@code pricingRequestMeta.js}'s {@code UNIT_BASIS_OPTIONS}), used by
 * {@code PricingRequestCreateModal}'s unit picker and by {@code unitBasisLabel} throughout the
 * pricing-request UI. That mirror is not retired here — it still backs those existing call
 * sites — but the factory-quote response form (owner ruling: Import must be able to set the unit
 * a factory actually quoted in, not just the one Sales originally requested) gets its select built
 * from this endpoint instead of growing a second hand-typed copy of the same four values.
 *
 * <p><strong>What it deliberately does not serve: the other pricing-request option lists.</strong>
 * {@code RECIPIENT_OPTIONS} and {@code QUANTITY_TYPE_OPTIONS} (also in {@code pricingRequestMeta.js})
 * have no backend enum behind them the way {@link UnitBasis} does, and nothing asked for them here.
 * Scope is the unit-basis vocabulary only.
 *
 * <p><strong>Authenticated, not role-gated.</strong> Same reasoning as
 * {@code DealStageMetaController}: the payload carries no business data at all — the same four
 * constants for every caller, revealing nothing about any deal, pricing request or quote.
 * Narrowing it to import/ceo would only mean a role that can legally open this page cannot render
 * its own unit select. {@code SecurityConfig} is default-deny, so a session is still required.
 */
@RestController
@RequestMapping("/api/meta")
public class UnitBasisMetaController {

    /**
     * Thai display units. Confirmed against {@code pricingRequestMeta.js}'s existing
     * {@code UNIT_BASIS_OPTIONS} (แผ่น / ตร.ม. / กล่อง / เมตร) rather than invented here, so this
     * endpoint's wording matches what every other unit-basis picker in the app already shows.
     */
    private static final Map<String, String> LABELS_TH = new LinkedHashMap<>();
    static {
        LABELS_TH.put(UnitBasis.PER_PIECE, "แผ่น");
        LABELS_TH.put(UnitBasis.PER_SQM, "ตร.ม.");
        LABELS_TH.put(UnitBasis.PER_BOX, "กล่อง");
        LABELS_TH.put(UnitBasis.PER_LINEAR_M, "เมตร");
    }

    private final SessionContext sessions;

    public UnitBasisMetaController(SessionContext sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/unit-bases")
    Map<String, Object> unitBases(HttpSession session) {
        sessions.requireUser(session);
        return Map.of("unitBases", LABELS_TH.entrySet().stream()
            .map(e -> new UnitBasisMetaDto(e.getKey(), e.getValue()))
            .toList());
    }

    /**
     * One unit basis's code and Thai display label.
     *
     * @param code  one of {@link UnitBasis}'s four canonical values
     * @param label the Thai text a picker shows for it
     */
    public record UnitBasisMetaDto(String code, String label) {}
}
