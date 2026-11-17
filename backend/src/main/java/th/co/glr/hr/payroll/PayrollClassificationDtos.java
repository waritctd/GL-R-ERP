package th.co.glr.hr.payroll;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DTOs for the two per-employee, per-component payroll matrices: the
 * withholding-tax treatment classification and the SSO wage-base inclusion tick. Grouped in one
 * file like {@link PayrollReconciliationDtos}, which this mirrors in shape.
 */
public final class PayrollClassificationDtos {
    private PayrollClassificationDtos() {
    }

    // ---- Tax-treatment classification (hr.payroll_component_tax_treatment) --------------------

    /** One employee's full classification map for a tax year: component -> treatment. */
    public record EmployeeComponentTaxTreatments(
        long employeeId,
        Map<PayrollComponent, PayrollTaxTreatment> byComponent
    ) {}

    public record ComponentTaxTreatmentUpsertRequest(
        @NotNull Long employeeId,
        @NotNull PayrollComponent component,
        // Nullable and meaningful: null explicitly resets the component back to "not yet classified" rather than being
        // coerced to any treatment.
        PayrollTaxTreatment taxTreatment
    ) {}

    /**
     * One employee's full classification row for the matrix screen, WITH the display fields
     * {@link EmployeeComponentTaxTreatments} deliberately omits -- that record is keyed purely by
     * employeeId because it exists only for internal (repository/calculator) plumbing; the
     * screen needs a name and code to render a matrix a human can read.
     *
     * <p>{@code byComponent} is the EFFECTIVE classification
     * {@link PayrollCalculator#calculateClassified} actually applies, not the RAW stored map
     * ({@code hr.payroll_component_tax_treatment} rows only): for an employee with no stored
     * rows every cell would render blank and the badge would read "ยังไม่จัดประเภท N รายการ"
     * while payroll runs fine on synthesized defaults. HR, trusting the badge, would then
     * classify a single cell and strand every other component. It is the same merged/effective
     * map {@code PayrollService#treatmentsFor} resolves for the engine, so the screen and the
     * engine can never disagree about what "currently applies" means.
     */
    public record TaxTreatmentMatrixRow(
        long employeeId,
        String employeeCode,
        String employeeName,
        // EFFECTIVE classification (see class javadoc above): synthesized defaults are merged in for every component
        // with no stored row.
        Map<PayrollComponent, PayrollTaxTreatment> byComponent,
        // Components for which HR (or a migration backfill)
        // has an actual stored row -- i.e. a value NOT synthesized by this snapshot. Includes a
        // present-and-null "deliberately left unclassified" row. The frontend uses this to tell HR
        // apart "you set this" from "the system is defaulting this", which is exactly the distinction
        // the branch's back-loading-risk mitigation depends on (a screen that cannot make this
        // distinction is not that mitigation).
        Set<PayrollComponent> explicitlyClassifiedComponents
    ) {}

    public record TaxTreatmentListResponse(int taxYear, List<TaxTreatmentMatrixRow> items) {}

    // ---- SSO wage-base inclusion (hr.payroll_component_sso_inclusion) --------------------------

    /** One employee's full SSO-inclusion map for a tax year: component -> included. Unlike classification,
     * inclusion is a plain boolean with a real default (seeded by PayrollRepository#seedSsoInclusionDefaults), so
     * there is no "unset" state once seeded. */
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
