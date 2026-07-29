package th.co.glr.hr.payroll;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * DTOs for the two per-employee, per-component payroll matrices introduced V95 (owner decisions
 * 2026-07-29, docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md): the
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
