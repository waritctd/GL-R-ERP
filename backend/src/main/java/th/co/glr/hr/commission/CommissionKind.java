package th.co.glr.hr.commission;

public final class CommissionKind {
    public static final String SALE = "SALE";
    public static final String CLAWBACK = "CLAWBACK";
    // Manual commission entries (feat/commission-manual-adjustments, V84): a sales_manager/CEO
    // hand-typed amount, never computed by CommissionCalculator, with no invoice behind it.
    // ADJUSTMENT = a case-by-case correction on a rep's monthly commission (e.g. a takeover-credit
    // split). MANAGER = the manager's own team/MANAGER commission. STOCK_BONUS = the stock-sale
    // bonus. INCENTIVE = the performance incentive. ALL FOUR are hand-typed MANUAL amounts for now
    // (owner decision: manual across the UI until the CEO-confirmed config lands to auto-compute
    // specific ones later). Each requires a reason and manager/CEO approval before it counts toward
    // payroll -- see CommissionService#createManualCommission and #payrollReadySummary.
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String MANAGER = "MANAGER";
    public static final String STOCK_BONUS = "STOCK_BONUS";
    public static final String INCENTIVE = "INCENTIVE";

    private CommissionKind() {
    }
}
