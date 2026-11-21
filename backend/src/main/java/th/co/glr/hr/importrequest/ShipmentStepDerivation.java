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

    /**
     * An IR's step from its lots: the slowest round's step, CONTACTED while any quantity is on no round, and RECEIVED
     * only when every line's full quantity sits on RECEIVED rounds (which falls out of "slowest" once nothing is
     * unplaced). The date is that step's date on the slowest round; while quantity waits it keeps the IR's own
     * CONTACTED stamp (or its first issue date).
     */
    public static Derived derive(IrLock ir, List<DerivedInput> rows) {
        Map<Long, BigDecimal> placed = new LinkedHashMap<>();
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        DerivedInput slowest = null;
        for (DerivedInput r : rows) {
            wanted.put(r.itemId(), r.itemQty());
            placed.merge(r.itemId(), r.lotQty() == null ? BigDecimal.ZERO : r.lotQty(), BigDecimal::add);
            if (r.roundStep() != null && (slowest == null
                || ImportRequestStep.indexOf(r.roundStep()) < ImportRequestStep.indexOf(slowest.roundStep()))) {
                slowest = r;
            }
        }
        boolean unplaced = slowest == null || wanted.entrySet().stream()
            .anyMatch(e -> placed.get(e.getKey()).compareTo(e.getValue()) < 0);
        if (unplaced) {
            LocalDate at = ImportRequestStep.CONTACTED.equals(ir.importStep()) && ir.importStepAt() != null
                ? ir.importStepAt() : ir.firstIssuedDate();
            return ImportRequestStep.CONTACTED.equals(ir.importStep())
                ? new Derived(ImportRequestStep.CONTACTED, at, ir.importStepById(), ir.importStepByName(), ir.importStepNote())
                : new Derived(ImportRequestStep.CONTACTED, at, null, null, null);
        }
        return new Derived(slowest.roundStep(), slowest.roundStepOn() != null ? slowest.roundStepOn() : ir.importStepAt(),
            slowest.stepById(), slowest.stepByName(), slowest.stepNote());
    }
}
