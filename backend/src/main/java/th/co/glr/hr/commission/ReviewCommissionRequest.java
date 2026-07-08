package th.co.glr.hr.commission;

import jakarta.validation.constraints.Size;

public record ReviewCommissionRequest(
    @Size(max = 2000) String reviewerNote
) {
}
