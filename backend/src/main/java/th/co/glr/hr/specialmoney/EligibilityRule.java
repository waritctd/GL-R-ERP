package th.co.glr.hr.specialmoney;

/** Identity of which eligibility gate a {@link SpecialMoneyType} is evaluated against. */
public enum EligibilityRule {
    /** Active employee + passed probation. */
    STANDARD,
    /** Active employee; no probation requirement, gated on tenure days + department instead. */
    PREPROBATION_SALES_SUPPORT
}
