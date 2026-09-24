package th.co.glr.hr.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BillingNoteCancelRequest(
    @NotBlank @Size(max = 2000) String reason
) {}
