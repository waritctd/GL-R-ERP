package th.co.glr.hr.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProfileRequestRequest(
    @NotBlank @Pattern(regexp = "phone|email|address|emergency") @Size(max = 40) String fieldKey,
    @NotBlank @Size(max = 120) String fieldLabel,
    @Size(max = 2000) String oldValue,
    @NotBlank @Size(max = 2000) String newValue
) {
}
