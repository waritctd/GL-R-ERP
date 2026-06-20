package th.co.glr.hr.profile;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequestRequest(
    @Pattern(regexp = "approved|rejected") String status,
    @Size(max = 2000) String reviewerNote
) {
}
