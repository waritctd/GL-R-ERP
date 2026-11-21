package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.DerivedInput;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;

/** The rule that turns an IR's lots into its own import_step, shared by the round service and the revision path. */
public final class ShipmentStepDerivation {
    private ShipmentStepDerivation() {}

    public record Derived(String step, LocalDate at, Long byId, String byName, String note) {}

    /** The slower step wins; on a tie the later step date, then the higher round id, so the row order never decides. */
    private static boolean slower(DerivedInput r, DerivedInput cur) {
        int byStep = Integer.compare(ImportRequestStep.indexOf(cur.roundStep()), ImportRequestStep.indexOf(r.roundStep()));
        if (byStep != 0) {
            return byStep > 0;
        }
        int byDate = java.util.Comparator.nullsFirst(java.util.Comparator.<LocalDate>naturalOrder())
            .compare(cur.roundStepOn(), r.roundStepOn());
        if (byDate != 0) {
            return byDate < 0;
        }
        return r.roundId() != null && (cur.roundId() == null || r.roundId() > cur.roundId());
    }

    /**
     * An IR's step from its lots: the slowest placed round's step, but a CONTACTED round is only planned, so while any lot
     * sits on a round past CONTACTED the slowest is chosen among those (planning the rest on a new round never rewinds the
     * IR; if every lot is on CONTACTED rounds the IR is CONTACTED). RECEIVED needs every line's full quantity on RECEIVED
     * rounds: with unplaced quantity or a lot on a round not yet received it stops short (at most AWAITING_CUSTOMS). With no
     * lots at all the IR keeps its own step (never RECEIVED by this path). A fresh IR (CONTACTED) stays CONTACTED with its
     * own stamp. The date, operator and note are the chosen round's.
     */
    public static Derived derive(IrLock ir, List<DerivedInput> rows) {
        Map<Long, BigDecimal> placed = new LinkedHashMap<>();
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        DerivedInput slowest = null;
        DerivedInput slowestPast = null; // among rounds past CONTACTED
        boolean allReceived = true;
        for (DerivedInput r : rows) {
            wanted.put(r.itemId(), r.itemQty());
            placed.merge(r.itemId(), r.lotQty() == null ? BigDecimal.ZERO : r.lotQty(), BigDecimal::add);
            if (r.roundStep() != null) {
                allReceived &= ImportRequestStep.RECEIVED.equals(r.roundStep());
                if (slowest == null || slower(r, slowest)) {
                    slowest = r;
                }
                if (ImportRequestStep.indexOf(r.roundStep()) > ImportRequestStep.indexOf(ImportRequestStep.CONTACTED)
                    && (slowestPast == null || slower(r, slowestPast))) {
                    slowestPast = r;
                }
            }
        }
        boolean unplaced = slowest == null || wanted.entrySet().stream()
            .anyMatch(e -> placed.get(e.getKey()).compareTo(e.getValue()) < 0);
        DerivedInput chosen = slowestPast != null ? slowestPast : slowest;
        String step = chosen == null ? ir.importStep() : chosen.roundStep();
        if ((unplaced || !allReceived) && (step == null
            || ImportRequestStep.indexOf(step) > ImportRequestStep.indexOf(ImportRequestStep.AWAITING_CUSTOMS))) {
            step = step == null ? ImportRequestStep.CONTACTED : ImportRequestStep.AWAITING_CUSTOMS;
        }
        if (ImportRequestStep.CONTACTED.equals(step)) {
            LocalDate at = ir.importStepAt() != null && ImportRequestStep.CONTACTED.equals(ir.importStep()) ? ir.importStepAt() : ir.firstIssuedDate();
            return ImportRequestStep.CONTACTED.equals(ir.importStep())
                ? new Derived(step, at, ir.importStepById(), ir.importStepByName(), ir.importStepNote())
                : new Derived(step, at, null, null, null);
        }
        if (chosen == null) {
            return new Derived(step, ir.importStepAt(), ir.importStepById(), ir.importStepByName(), ir.importStepNote());
        }
        LocalDate at = chosen.roundStepOn() != null ? chosen.roundStepOn() : ir.importStepAt();
        // the IR's own date is never before it was first issued (the round's dates stay as they are)
        if (at != null && ir.firstIssuedDate() != null && at.isBefore(ir.firstIssuedDate())) {
            at = ir.firstIssuedDate();
        }
        return new Derived(step, at, chosen.stepById(), chosen.stepByName(), chosen.stepNote());
    }
}
