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
     * An IR's step from its lots: the slowest placed round's step. RECEIVED needs every line's full quantity on RECEIVED
     * rounds; while quantity is unplaced the step stops short of it (at most AWAITING_CUSTOMS), because the remainder is
     * still on its way, not back at the start. With no lots at all the IR keeps its own step (never RECEIVED by this path).
     * A fresh IR (CONTACTED) stays CONTACTED with its own stamp. The date, operator and note are the chosen round's.
     */
    public static Derived derive(IrLock ir, List<DerivedInput> rows) {
        Map<Long, BigDecimal> placed = new LinkedHashMap<>();
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        DerivedInput slowest = null;
        for (DerivedInput r : rows) {
            wanted.put(r.itemId(), r.itemQty());
            placed.merge(r.itemId(), r.lotQty() == null ? BigDecimal.ZERO : r.lotQty(), BigDecimal::add);
            if (r.roundStep() != null && (slowest == null || slower(r, slowest))) {
                slowest = r;
            }
        }
        boolean unplaced = slowest == null || wanted.entrySet().stream()
            .anyMatch(e -> placed.get(e.getKey()).compareTo(e.getValue()) < 0);
        String step = slowest == null ? ir.importStep() : slowest.roundStep();
        if (unplaced && (step == null || ImportRequestStep.indexOf(step) > ImportRequestStep.indexOf(ImportRequestStep.AWAITING_CUSTOMS))) {
            step = step == null ? ImportRequestStep.CONTACTED : ImportRequestStep.AWAITING_CUSTOMS;
        }
        if (ImportRequestStep.CONTACTED.equals(step)) {
            LocalDate at = ir.importStepAt() != null && ImportRequestStep.CONTACTED.equals(ir.importStep()) ? ir.importStepAt() : ir.firstIssuedDate();
            return ImportRequestStep.CONTACTED.equals(ir.importStep())
                ? new Derived(step, at, ir.importStepById(), ir.importStepByName(), ir.importStepNote())
                : new Derived(step, at, null, null, null);
        }
        if (slowest == null) {
            return new Derived(step, ir.importStepAt(), ir.importStepById(), ir.importStepByName(), ir.importStepNote());
        }
        LocalDate at = slowest.roundStepOn() != null ? slowest.roundStepOn() : ir.importStepAt();
        // the IR's own date is never before it was first issued (the round's dates stay as they are)
        if (at != null && ir.firstIssuedDate() != null && at.isBefore(ir.firstIssuedDate())) {
            at = ir.firstIssuedDate();
        }
        return new Derived(step, at, slowest.stepById(), slowest.stepByName(), slowest.stepNote());
    }
}
