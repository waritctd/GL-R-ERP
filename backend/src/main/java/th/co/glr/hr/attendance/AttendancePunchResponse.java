package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AttendancePunchResponse(
    @JsonProperty("punch_id")
    Long punchId,
    boolean inserted,
    String status
) {
}
