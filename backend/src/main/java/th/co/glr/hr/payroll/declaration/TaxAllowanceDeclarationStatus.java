package th.co.glr.hr.payroll.declaration;

/**
 * Mirrors the {@code CHECK (status IN (...))} on {@code hr.tax_allowance_declaration} (V105)
 * exactly. See that migration's header comment for the full state machine.
 *
 * <p>{@code applied_at} is deliberately NOT a status here — it is a flag column on the same row.
 * {@link #APPROVED} covers both "approved, not yet applied" and "approved and applied"; the
 * distinction is {@code appliedAt == null} on {@link TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto}.
 */
public enum TaxAllowanceDeclarationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED,
    EXPIRED,
    WITHDRAWN
}
