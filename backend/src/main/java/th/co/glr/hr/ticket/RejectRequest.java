package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(@NotBlank String reason) {}
