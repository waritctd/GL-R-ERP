package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Selects affected frontend tests, including readers outside the import graph. */
record AttendanceMarkPresentResponse(
    @JsonProperty("marked_count") int markedCount,
    @JsonProperty("cleared_count") int clearedCount
) {
}
