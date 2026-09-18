package th.co.glr.hr.importprogress;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public final class ImportProgressRequests {
    private ImportProgressRequests() {}

    /**
     * Advance one factory's import step. {@code targetStep} must be one of {@link ImportStep}'s
     * values and strictly forward of the current step — see {@code
     * ImportProgressService#advanceStep}.
     */
    public record AdvanceImportStepRequest(@NotBlank String targetStep) {}

    /** Set/clear the optional ETA and note for one factory (no step change). */
    public record UpdateFactoryImportRequest(LocalDate eta, String note) {}

    /** Which factory to generate the order-email template for (name from the tracker row). */
    public record OrderEmailTemplateRequest(@NotBlank String factoryName) {}
}
