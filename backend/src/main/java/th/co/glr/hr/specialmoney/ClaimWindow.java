package th.co.glr.hr.specialmoney;

/**
 * Identity of the timing rule that bounds when a {@link SpecialMoneyType} may be claimed relative
 * to the underlying event or receipt.
 */
public enum ClaimWindow {
    NONE,
    /** MEDICAL: must submit within one month of the receipt date. */
    ONE_MONTH_FROM_RECEIPT,
    /**
     * All AID_* types: must submit within three months of the event date.
     *
     * <p><b>NOT in the source welfare-policy document</b> -- this window is an assumption pending
     * confirmation with the CEO/HR before it is relied on to reject a real claim.
     */
    THREE_MONTHS_FROM_EVENT
}
