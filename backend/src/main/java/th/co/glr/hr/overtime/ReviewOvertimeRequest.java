package th.co.glr.hr.overtime;

import jakarta.validation.constraints.Size;

public record ReviewOvertimeRequest(
    @Size(max = 2000) String reviewerNote
) {
}
