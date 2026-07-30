package th.co.glr.hr.payroll;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DTOs for the two per-employee, per-component payroll matrices introduced V95 (owner decisions
 * 2026-07-29, docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md): the
 * withholding-tax treatment classification and the SSO wage-base inclusion tick. Grouped in one
 * file like {@link PayrollReconciliationDtos}, which this mirrors in shape.
 */
public final class PayrollClassificationDtos {
    private PayrollClassificationDtos() {
    }

    // ---- Tax-treatment classification (hr.payroll_component_tax_treatment) --------------------

    /**
     * One employee's full classification map for a tax year: component -> treatment. A component
     * absent from the map, or present with a {@code null} value, means "not yet classified" --
     * these are distinct storage states (absent = no row at all; present+null = a row exists but
     * HR has not set a treatment) and callers must not conflate them with a default.
     */
    public record EmployeeComponentTaxTreatments(
        long employeeId,
        Map<PayrollComponent, PayrollTaxTreatment> byComponent
    ) {}

    public record ComponentTaxTreatmentUpsertRequest(
        @NotNull Long employeeId,
        @NotNull PayrollComponent component,
        // Nullable and meaningful: null explicitly resets the component back to "not yet
        // classified" rather than being coerced to any treatment.
        PayrollTaxTreatment taxTreatment
    ) {}

    /**
     * P0 fix (Opus review, 2026-07-30): the HTTP surface + screen the reachability test demanded.
     * One employee's full classification row for the matrix screen, WITH the display fields
     * {@link EmployeeComponentTaxTreatments} deliberately omits -- that record is keyed purely by
     * employeeId because it existed only for internal (repository/calculator) plumbing before this
     * fix; the screen needs a name and code to render a matrix a human can read.
     *
     * <p>F2 fix (fifth Opus review, 2026-07-30): {@code byComponent} used to be the RAW stored map
     * ({@code hr.payroll_component_tax_treatment} rows only), not the EFFECTIVE classification
     * {@link PayrollCalculator#calculateClassified} actually applies -- on any employee the V96/V100
     * backfills never reached, every cell rendered blank and the badge read "ยังไม่จัดประเภท N รายการ"
     * while payroll ran fine on synthesized defaults. HR, trusting the badge, would then classify a
     * single cell and (see the now-fixed {@code PayrollService#treatmentsFor}) strand every other
     * component. {@code byComponent} is now the same merged/effective map {@code treatmentsFor}
     * resolves for the engine, so the screen and the engine can never disagree about what "currently
     * applies" means.
     */
    public record TaxTreatmentMatrixRow(
        long employeeId,
        String employeeCode,
        String employeeName,
        // EFFECTIVE classification (see class javadoc above): synthesized defaults are merged in for
        // every component with no stored row. A present-and-null entry (visible via
        // explicitlyClassifiedComponents below) still means "HR deliberately left this unclassified"
        // and still blocks a non-zero amount -- it is never overwritten by a default. SALARY is
        // deliberately never a key here: it is locked to REGULAR_REPROJECT without ever needing a
        // stored row (see PayrollClassifiedCalculationDtos#treatmentOf), so the matrix screen must
        // render it as a fixed label, never an editable cell.
        Map<PayrollComponent, PayrollTaxTreatment> byComponent,
        // F2 fix (fifth Opus review, 2026-07-30): components for which HR (or a migration backfill)
        // has an actual stored row -- i.e. a value NOT synthesized by this snapshot. Includes a
        // present-and-null "deliberately left unclassified" row. The frontend uses this to tell HR
        // apart "you set this" from "the system is defaulting this", which is exactly the distinction
        // the branch's back-loading-risk mitigation depends on (a screen that cannot make this
        // distinction is not that mitigation).
        Set<PayrollComponent> explicitlyClassifiedComponents
    ) {}

    public record TaxTreatmentListResponse(int taxYear, List<TaxTreatmentMatrixRow> items) {}

    // ---- SSO wage-base inclusion (hr.payroll_component_sso_inclusion) --------------------------

    /**
     * One employee's full SSO-inclusion map for a tax year: component -> included. Unlike
     * classification, inclusion is a plain boolean with a real default (seeded by {@code
     * PayrollRepository#seedSsoInclusionDefaults}), so there is no "unset" state once seeded.
     */
    public record EmployeeComponentSsoInclusion(
        long employeeId,
        Map<PayrollComponent, Boolean> byComponent
    ) {}

    public record ComponentSsoInclusionUpsertRequest(
        @NotNull Long employeeId,
        @NotNull PayrollComponent component,
        @NotNull Boolean included
    ) {}
}
