package th.co.glr.hr.commission;

import jakarta.validation.constraints.NotBlank;

public record CreateClawbackRequest(
    @NotBlank String reason
) {}
