package th.co.glr.hr.specialmoney;

/**
 * Identity of the amount-capping rule a {@link SpecialMoneyType} uses. This does not carry the
 * numbers (those live in {@code hr.special_money_policy} / {@link PolicyAmounts}) -- it is a tag
 * {@link SpecialMoneyPolicyEvaluator} switches on so that adding a new {@link SpecialMoneyType}
 * forces a deliberate choice of cap shape rather than silently falling through.
 */
public enum CapRule {
    /** Flat amount, e.g. the 5,000 THB life-event aid types. */
    FIXED_AID,
    /** Capped against a rolling calendar-year total (MEDICAL). */
    MEDICAL_ANNUAL,
    /** Tailored flat cap or per-piece self-buy caps, whichever mode is chosen. */
    UNIFORM_ANNUAL,
    /** Piece-count cap only; no per-piece rate shape specified in this slice. */
    UNIFORM_NEW_STAFF,
    /** Fixed kit line items (t-shirt/trouser/shoes/belt), each with its own qty cap. */
    UNIFORM_PREPROBATION_KIT,
    /** Rate x days, rate selected by role/destination. */
    PER_DIEM_RATE,
    /** No cap; evidence-gated, amount is whatever was approved. */
    DISCRETIONARY
}
