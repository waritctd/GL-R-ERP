package th.co.glr.hr.payroll.declaration;

/** Mirrors the CHECK (status IN (...)) on hr.tax_allowance_declaration (V105) exactly. See that migration's
 * header comment for the full state machine. */
public enum TaxAllowanceDeclarationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED,
    EXPIRED,
    WITHDRAWN
}
