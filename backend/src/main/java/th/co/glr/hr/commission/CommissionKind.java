package th.co.glr.hr.commission;

public final class CommissionKind {
    public static final String SALE = "SALE";
    public static final String CLAWBACK = "CLAWBACK";
    // Manual commission entries (feat/commission-manual-adjustments, V84): a sales_manager/CEO hand-typed amount,
    // never computed by CommissionCalculator, with no invoice behind it. ADJUSTMENT = a case-by-case correction on
    // a rep's monthly commission (e.g. a takeover-credit split).
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String MANAGER = "MANAGER";
    public static final String STOCK_BONUS = "STOCK_BONUS";
    public static final String INCENTIVE = "INCENTIVE";

    private CommissionKind() {
    }
}
