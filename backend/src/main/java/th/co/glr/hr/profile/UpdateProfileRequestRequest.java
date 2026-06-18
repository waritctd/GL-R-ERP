package th.co.glr.hr.profile;

import jakarta.validation.constraints.Pattern;

public record UpdateProfileRequestRequest(
    @Pattern(regexp = "approved|rejected|pending") String status,
    String reviewerNote
) {
}
