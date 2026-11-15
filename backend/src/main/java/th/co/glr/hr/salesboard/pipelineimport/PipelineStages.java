package th.co.glr.hr.salesboard.pipelineimport;

import java.util.Map;
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

    /** {@code warning} is non-null when the route forced a different rep code than the file said. */
    record StageChoice(String stage, String warning) {}

    /**
     * The planned stage when it is on the deal's route. Otherwise, first another stage of the SAME rep code that is on
     * the route (S3 on an owner-direct deal → QUOTE_OWNER, exactly as {@code TicketService#firstQuoteStageOnRoute}
     * and V210 choose it — the code the rep wrote is kept, so no warning), else the nearest on-route stage before it
     * in {@link DealStage#ORDER}, with a warning naming both codes. The shared head (LEAD_APPROACH … SPEC_APPROVED) is
     * on every route, so the walk always ends.
     */
    static StageChoice stageFor(String code, String entryChannel) {
        String planned = STAGE_FOR_CODE.get(code);
        if (planned == null) {
            throw new IllegalArgumentException("unknown rep code " + code);
        }
        if (DealRoute.isOnRoute(entryChannel, planned)) {
            return new StageChoice(planned, null);
        }
        for (String stage : DealStage.ORDER) {
            if (code.equals(DealStage.repCodeOf(stage)) && DealRoute.isOnRoute(entryChannel, stage)) {
                return new StageChoice(stage, null);
            }
        }
        for (int i = DealStage.indexOf(planned) - 1; i >= 0; i--) {
            String stage = DealStage.ORDER.get(i);
            if (DealRoute.isOnRoute(entryChannel, stage)) {
                String landed = DealStage.repCodeOf(stage);
                return new StageChoice(stage, code + " ไม่อยู่ในเส้นทางของดีลที่มีแต่" + partyPhrase(entryChannel)
                    + " — นำเข้าเป็น " + landed);
            }
        }
        throw new IllegalStateException("no on-route stage before " + planned);
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
