package th.co.glr.hr.specialmoney;

/** Identity of the timing rule that bounds when a SpecialMoneyType may be claimed relative to the underlying
 * event or receipt. */
public enum ClaimWindow {
    /** Aid claims have evidence requirements but no claim deadline. */
    NONE,
    /** MEDICAL: must submit within one month of the receipt date (§2.3 — the document's only claim deadline). */
    ONE_MONTH_FROM_RECEIPT
}
