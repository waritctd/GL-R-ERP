package th.co.glr.hr.deposit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RevisionRequest(
    @NotNull  RevisionScope scope,
    @NotBlank String        reason
) {}
