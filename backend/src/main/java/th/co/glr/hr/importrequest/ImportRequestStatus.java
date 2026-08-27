package th.co.glr.hr.importrequest;

import java.util.Set;

/**
 * Lifecycle of a ใบขอซื้อ (F-SM-001), mirroring {@code sales.deposit_notice}'s own
 * DRAFT/ISSUED/SUPERSEDED rather than inventing a fourth vocabulary for the same idea.
 *
 * <p><strong>WIP — nothing reads this yet.</strong> It belongs to the STORED import-request
 * aggregate, which has no service and no controller. The shipped feature
 * ({@link ImportRequestService}) is stateless and has no lifecycle at all.
 *
 * <p>There is deliberately no {@code CANCELLED}. A draft that should not exist is DELETED — it has
 * no number and no audit weight, so a tombstone would be litter. An ISSUED form cannot be cancelled
 * at all: it is a controlled document that has left the building, and the only correction is a new
 * version that SUPERSEDES it. {@code chk_import_request_status} (V154) accepts exactly these three,
 * so adding a constant here without a migration would produce a value the column rejects.
 */
public final class ImportRequestStatus {
    /** No number, no issue date, freely editable, deletable. */
    public static final String DRAFT = "DRAFT";
    /** Numbered and dated. Body and line items frozen; only the import-owned footer fields move. */
    public static final String ISSUED = "ISSUED";
    /** Replaced by a later version, which is named in {@code superseded_by_id}. */
    public static final String SUPERSEDED = "SUPERSEDED";

    public static final Set<String> VALUES = Set.of(DRAFT, ISSUED, SUPERSEDED);

    /**
     * Not superseded — i.e. this row is still part of the live picture for its (deal, brand).
     *
     * <p><strong>Deliberately NOT a uniqueness predicate.</strong> V154 enforces at most one ISSUED
     * <em>and</em> at most one DRAFT per (deal, brand) with TWO partial indexes, precisely so a
     * correction can be prepared as a DRAFT while the previous version is still ISSUED. An earlier
     * draft of that migration used a single index over "not superseded", which forbade exactly that
     * pair and made revisions impossible; do not reintroduce the idea that "live" means "at most
     * one".
     */
    public static boolean isLive(String status) {
        return DRAFT.equals(status) || ISSUED.equals(status);
    }

    private ImportRequestStatus() {}
}
