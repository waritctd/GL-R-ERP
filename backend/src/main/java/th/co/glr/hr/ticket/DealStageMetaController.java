package th.co.glr.hr.ticket;

import java.util.Map;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

/**
 * The deal pipeline's own shape, served to clients instead of being re-declared by each of them.
 *
 * <p><strong>Why this exists.</strong> The frontend carried a hand-maintained copy of this list —
 * the fifteen stages with their display number, phase and write gate — in
 * {@code features/tickets/stageMeta.js}. Nothing checked it against {@link DealStage}, and when
 * V143 split {@link DealStage#QUOTE_OWNER} out of {@link DealStage#QUOTE_DESIGN_SIDE} the copy
 * stayed at fourteen entries: a deal legitimately sitting at the new stage rendered with no
 * number, no phase, no gate and a raw code where its name should be. This endpoint is the single
 * answer, and the copy is deleted rather than corrected.
 *
 * <p><strong>What it deliberately does not serve: display text.</strong> Every Thai label — stage
 * names, phase names, gate names, reason wording — stays with the client, because wording changes
 * should not require a backend deploy. The one exception lives elsewhere: a
 * {@code StageDecisionDto.blockedReason} is server-supplied, because only the service knows why a
 * particular stage is refused. What this endpoint serves is the code set and the structure, so a
 * client can no longer invent either.
 *
 * <p><strong>Scope: the deal stage axis only.</strong> The fulfilment and payment sub-step lists
 * the frontend also keeps are deliberately NOT served here. {@link PaymentTrack} has no single
 * ordered enumeration to serve — {@code PaymentTrack.path(policy)} legitimately returns a
 * different sequence on a deposit-bypass policy — and {@link FulfilmentStatus} publishes an import
 * sequence and a delivery set rather than one ordered whole. Serving an invented single order for
 * either would be a new defect rather than the removal of one, so they stay client-side and are
 * called out as a known gap instead of quietly guessed at.
 *
 * <p><strong>Authenticated, not role-gated.</strong> Unlike the config controllers this is
 * modelled on ({@code FactoryConfigController}, {@code PriceCalcConfigController}), the payload
 * carries no business data at all: it is the same fifteen constants for every caller and reveals
 * nothing about any deal. Narrowing it to the sales roles would only mean a role that can legally
 * open a deal page cannot render its stage. {@code SecurityConfig} is default-deny, so a session
 * is still required.
 */
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
            "cancelReasons", DealCancelReason.ORDER);
    }

    /**
     * One pipeline stage's structure.
     *
     * @param code         the {@link DealStage} constant, and the value stored in
     *                     {@code sales.ticket.sales_stage}
     * @param no           1-based DISPLAY sequence — what a stepper prints
     * @param businessCode the owner's S1–S20 sheet number. Genuinely different from {@code no}
     *                     and served alongside it rather than derived: PROCUREMENT alone covers
     *                     S12–S17, and V143 shifted every display number after S4 without moving
     *                     a single sheet number. See {@code DealStage.BUSINESS_CODE}.
     * @param phase        1–5
     * @param gate         which department owns the manual fallback: sales / account / import
     * @param auto         whether the deal normally reaches this stage from an operational
     *                     transition rather than a rep choosing it
     */
    public record DealStageMetaDto(String code, int no, String businessCode, int phase,
                                   String gate, boolean auto) {}
}
