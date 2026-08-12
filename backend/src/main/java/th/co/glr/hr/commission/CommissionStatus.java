package th.co.glr.hr.commission;

public final class CommissionStatus {
    public static final String SUBMITTED = "SUBMITTED";
    public static final String MANAGER_APPROVED = "MANAGER_APPROVED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /**
     * Unreachable by design — nothing in the application ever writes it, and that is correct.
     *
     * <p>No INSERT or status UPDATE in {@link CommissionRepository} produces {@code VOID}, there is
     * no endpoint that voids a record, and {@code git log -S"status = 'VOID'"} over this package
     * returns no commits: it was born unreachable rather than regressing into it. The only writes
     * in the tree are fixtures that bypass the service entirely.
     *
     * <p>It stays unwritten because retiring a commission is already covered three ways, each
     * correct for its situation, and a fourth would be the one with the worst failure mode:
     * <ul>
     *   <li>not yet approved &rarr; {@code REJECTED};</li>
     *   <li>approved, payroll month still open &rarr; {@code createClawback} (which requires
     *       {@code SALE} + {@code APPROVED}, and guards on {@code
     *       requireCommissionPayrollMonthOpen});</li>
     *   <li>approved, payroll month closed &rarr; a signed {@code ADJUSTMENT} in the next month.</li>
     * </ul>
     * That last row is the reason not to add a void: {@code findApprovedRecordsByMonth} filters
     * {@code status = 'APPROVED'} and is the sole input to {@code payrollCommissionTotalsByEmployee},
     * so voiding an approved record would silently restate an already-filed payroll month —
     * precisely what the open-month guard exists to prevent.
     *
     * <p><b>Do not delete this constant.</b> The literal is live in the V35 CHECK constraint, in
     * six backend read filters ({@code NOT IN ('VOID','REJECTED')} and friends), in the
     * {@code updateDeductions} guard, and in the frontend. Removing the Java constant without those
     * is silent divergence, not cleanup.
     *
     * <p>Known reachable-but-benign case, deliberately left alone: cancelling a fully-paid deal does
     * not touch its commissions ({@code TicketService.cancel} references none), so an approved
     * commission survives the cancellation. Clawback or an adjustment is the intended remedy.
     */
    public static final String VOID = "VOID";

    private CommissionStatus() {
    }
}
