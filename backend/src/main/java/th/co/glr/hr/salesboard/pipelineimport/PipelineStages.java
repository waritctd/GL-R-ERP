package th.co.glr.hr.salesboard.pipelineimport;

import java.util.Map;
import java.util.function.UnaryOperator;
import th.co.glr.hr.ticket.DealRoute;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;

/**
 * Rep code (S1–S9) → pipeline stage for an imported deal, and the deal's entry channel. Plan 2026-11-15 Task 4.1
 * mapping; spec §7.1 for the code groups.
 */
final class PipelineStages {

    /** The stage a code lands on when the deal's route allows it. */
    static final Map<String, String> STAGE_FOR_CODE = Map.of(
        "S1", DealStage.PRESENTATION,
        "S2", DealStage.SPEC_APPROVED,
        "S3", DealStage.QUOTE_DESIGN_SIDE,
        "S5", DealStage.AWAITING_BUYER,
        "S6", DealStage.NEGOTIATION,
        "S7", DealStage.DEPOSIT_RECEIVED,
        "S8", DealStage.DELIVERY_SCHEDULING,
        "S9", DealStage.DELIVERED);

    /** {@code warning} is non-null when the route or a missing fact forced a different rep code than the file said. */
    record StageChoice(String stage, String warning) {}

    /** S7–S9 claim money received or goods moved. Those facts live in real documents (deposit notice, IR, delivery
     * record) the import cannot create, so such rows are never imported — see PipelineImportService. */
    static boolean needsDocument(String code) {
        return "S7".equals(code) || "S8".equals(code) || "S9".equals(code);
    }

    private static final java.util.Set<String> QUOTE_STAGES =
        java.util.Set.of(DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER, DealStage.QUOTE_BUYER);

    /**
     * Where a row's code lands, in two steps:
     * <ol>
     *   <li><b>Route.</b> The planned stage when it is on the deal's route. Otherwise another stage of the SAME rep
     *   code that is on the route (S3 on an owner-direct deal → QUOTE_OWNER, as {@code
     *   TicketService#firstQuoteStageOnRoute} and V210 choose it), else the nearest on-route stage before it.</li>
     *   <li><b>Facts.</b> {@code factRefusal} is the app's own stage-fact gate ({@code
     *   TicketService#stageFactRefusal}, null = the fact holds) evaluated for the deal being created. When it refuses,
     *   the deal lands on the highest EARLIER on-route stage whose facts hold, never on a stage it has no fact for:
     *   S3 with no quotation in the app → S2 ("S3 ในชีท → นำเข้าเป็น S2 เพราะยังไม่มีใบเสนอราคาในระบบ").</li>
     * </ol>
     * LEAD_APPROACH … SPEC_APPROVED are on every route and fact-free, so both walks always end.
     */
    static StageChoice stageFor(String code, String entryChannel, UnaryOperator<String> factRefusal) {
        String planned = STAGE_FOR_CODE.get(code);
        if (planned == null) {
            throw new IllegalArgumentException("unknown rep code " + code);
        }
        String candidate = null;
        String warning = null;
        if (DealRoute.isOnRoute(entryChannel, planned)) {
            candidate = planned;
        } else {
            for (String stage : DealStage.ORDER) {
                if (code.equals(DealStage.repCodeOf(stage)) && DealRoute.isOnRoute(entryChannel, stage)) {
                    candidate = stage;
                    break;
                }
            }
            if (candidate == null) {
                candidate = onRouteBelow(planned, entryChannel, s -> null);
                warning = code + " ไม่อยู่ในเส้นทางของดีลที่มีแต่" + partyPhrase(entryChannel) + " — นำเข้าเป็น "
                    + DealStage.repCodeOf(candidate);
            }
        }
        if (factRefusal.apply(candidate) != null) {
            String landed = onRouteBelow(candidate, entryChannel, factRefusal);
            String reason = QUOTE_STAGES.contains(candidate) ? "ยังไม่มีใบเสนอราคาในระบบ" : "ยังไม่มีเอกสารยืนยันในระบบ";
            return new StageChoice(landed, code + " ในชีท → นำเข้าเป็น " + DealStage.repCodeOf(landed) + " เพราะ" + reason);
        }
        return new StageChoice(candidate, warning);
    }

    /** The highest stage strictly below {@code stage} that is on the route and whose facts hold. */
    private static String onRouteBelow(String stage, String entryChannel, UnaryOperator<String> factRefusal) {
        for (int i = DealStage.indexOf(stage) - 1; i >= 0; i--) {
            String below = DealStage.ORDER.get(i);
            if (DealRoute.isOnRoute(entryChannel, below) && factRefusal.apply(below) == null) {
                return below;
            }
        }
        throw new IllegalStateException("no on-route stage with its facts below " + stage);
    }

    /**
     * The template has no channel column, so the deal's route is read from the parties the row names: a designer →
     * DESIGNER_LED (the full route); else a project owner → OWNER_DIRECT; else a buyer → BUYER_DIRECT. Null when the row
     * names none — the row is then skipped (a deal needs a customer).
     */
    static String channelFor(String designer, String owner, String buyer) {
        if (PipelineFields.text(designer) != null) {
            return EntryChannel.DESIGNER_LED;
        }
        if (PipelineFields.text(owner) != null) {
            return EntryChannel.OWNER_DIRECT;
        }
        if (PipelineFields.text(buyer) != null) {
            return EntryChannel.BUYER_DIRECT;
        }
        return null;
    }

    private static String partyPhrase(String entryChannel) {
        return EntryChannel.BUYER_DIRECT.equals(entryChannel) ? "ผู้ซื้อ" : "เจ้าของโครงการ";
    }

    private PipelineStages() {}
}
