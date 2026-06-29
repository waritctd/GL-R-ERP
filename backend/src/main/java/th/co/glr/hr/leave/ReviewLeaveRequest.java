package th.co.glr.hr.leave;

import jakarta.validation.constraints.Size;

public record ReviewLeaveRequest(
    @Size(max = 2000) String reviewerNote
) {
}
