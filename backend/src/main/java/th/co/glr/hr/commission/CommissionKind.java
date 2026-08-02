package th.co.glr.hr.commission;

public final class CommissionKind {
    public static final String SALE = "SALE";
    public static final String CLAWBACK = "CLAWBACK";
    // Manual commission entries (feat/commission-manual-adjustments, V84): a sales_manager/CEO
    // hand-typed amount, never computed by CommissionCalculator, with no invoice behind it.
    // ADJUSTMENT = a case-by-case correction on a rep's monthly commission (e.g. a takeover-credit
    // split). MANAGER = the manager's own team/MANAGER commission. STOCK_BONUS = the stock-sale
    // bonus. INCENTIVE = the performance incentive.
    //
    // Issue #405 (2026-08-01): INCENTIVE and STOCK_BONUS are now AUTO-COMPUTED by default --
    // CommissionService#computeRepPayrollCommissions runs CommissionCalculator#monthlyIncentive /
    // #stockSaleBonus against the config in sales.commission_incentive_tier /
    // sales.stock_bonus_config (V108) for every payroll month from 2026-08-01 onward. The manual
    // kind literals stay live as an OVERRIDE path, not a leftover: a manager can still hand-type
    // one (e.g. a pre-2026-08-01 correction, or a one-off exception), and when a rep-month already
    // carries an approved manual INCENTIVE/STOCK_BONUS entry the auto-computed limb for that rep is
    // suppressed for that month (manual wins) -- see the double-count guard in
    // #computeRepPayrollCommissions. ADJUSTMENT and MANAGER stay entirely hand-typed; nothing
    // auto-computes them.
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String MANAGER = "MANAGER";
    public static final String STOCK_BONUS = "STOCK_BONUS";
    public static final String INCENTIVE = "INCENTIVE";

    private CommissionKind() {
    }
}
