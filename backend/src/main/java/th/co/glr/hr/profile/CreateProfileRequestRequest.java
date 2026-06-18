package th.co.glr.hr.profile;

import jakarta.validation.constraints.NotBlank;

public record CreateProfileRequestRequest(
    @NotBlank String fieldKey,
    @NotBlank String fieldLabel,
    String oldValue,
    @NotBlank String newValue
) {
}
