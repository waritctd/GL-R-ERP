package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

/** The deal pipeline's own shape, served to clients instead of being re-declared by each of them. The one
 * exception lives elsewhere: a StageDecisionDto.blockedReason is server-supplied, because only the service
 * knows why a particular stage is refused. */
@RestController
@RequestMapping("/api/meta")
public class DealStageMetaController {

    private final SessionContext sessions;

    public DealStageMetaController(SessionContext sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/deal-stages")
    Map<String, Object> dealStages(HttpSession session) {
        sessions.requireUser(session);
        return Map.of(
            "stages", DealStage.ORDER.stream()
                .map(code -> new DealStageMetaDto(code, DealStage.displayNoOf(code),
                    DealStage.businessCodeOf(code), DealStage.phaseOf(code),
                    DealStage.gateOf(code), DealStage.isAutoAdvanced(code)))
                .toList(),
            "phases", DealStage.ORDER.stream().map(DealStage::phaseOf).distinct().sorted().toList(),
            "lostReasons", DealLostReason.ORDER,
            "cancelReasons", DealCancelReason.ORDER,
            "routes", routes());
    }

    /** Every EntryChannel's route, as ordered stage CODES — DealRoute#path verbatim, never a table re-declared
     * here. Codes only, like the rest of this endpoint: the route names and stage wording stay with the client. */
    private static Map<String, List<String>> routes() {
        return Map.of(
            EntryChannel.DESIGNER_LED, DealRoute.path(EntryChannel.DESIGNER_LED),
            EntryChannel.OWNER_DIRECT, DealRoute.path(EntryChannel.OWNER_DIRECT),
            EntryChannel.BUYER_DIRECT, DealRoute.path(EntryChannel.BUYER_DIRECT),
            EntryChannel.UNSPECIFIED, DealRoute.path(EntryChannel.UNSPECIFIED));
    }

    /** One pipeline stage's structure. */
    public record DealStageMetaDto(String code, int no, String businessCode, int phase,
                                   String gate, boolean auto) {}
}
