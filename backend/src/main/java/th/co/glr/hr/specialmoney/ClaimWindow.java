package th.co.glr.hr.specialmoney;

/**
 * Identity of the timing rule that bounds when a {@link SpecialMoneyType} may be claimed relative
 * to the underlying event or receipt.
 */
public enum ClaimWindow {
    /**
     * No deadline. This is the correct value for the AID_* types: the welfare document sets no
     * claim window for เงินช่วยเหลือ, only evidence requirements.
     *
     * <p>A {@code THREE_MONTHS_FROM_EVENT} constant used to exist and was applied to all AID_*
     * types. It was invented, not sourced from the document, and it refused genuine claims; removed
     * on the owner's ruling, 2026-08-03.
     */
    NONE,
    /** MEDICAL: must submit within one month of the receipt date (§2.3 — the document's only claim deadline). */
    ONE_MONTH_FROM_RECEIPT
}
