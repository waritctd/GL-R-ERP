package th.co.glr.hr.pricingrequest;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

/** The unit-basis vocabulary, served to clients instead of being re-declared by each of them. Scope is the
 * unit-basis vocabulary only. Narrowing it to import/ceo would only mean a role that can legally open this
 * page cannot render its own unit select. */
@RestController
@RequestMapping("/api/meta")
public class UnitBasisMetaController {

    /** Thai display units. Confirmed against pricingRequestMeta.js's existing UNIT_BASIS_OPTIONS (แผ่น / ตร.ม. /
     * กล่อง / เมตร) rather than invented here, so this endpoint's wording matches what every other unit-basis
     * picker in the app already shows. */
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

    /** One unit basis's code and Thai display label. */
    public record UnitBasisMetaDto(String code, String label) {}
}
