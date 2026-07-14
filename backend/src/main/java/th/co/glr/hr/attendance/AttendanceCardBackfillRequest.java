package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * A batch of device-user rows (User ID + card number) pulled from the SC700, used to backfill
 * hr.employee.badge_card_no so historical card punches resolve to the right employee. The device
 * User ID (Pin) is matched against hr.employee.employee_code.
 */
public record AttendanceCardBackfillRequest(
    @JsonProperty("mappings")
    @NotEmpty
    List<@Valid CardMapping> mappings
) {
    public record CardMapping(
        @JsonProperty("employee_code")
        @NotBlank
        String employeeCode,
        @JsonProperty("card_no")
        String cardNo
    ) {
    }
}
