package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param markedCount employees newly marked present or refreshed for the date
 * @param clearedCount previously-marked WFH rows removed because they were not resubmitted
 */
record AttendanceMarkPresentResponse(
    @JsonProperty("marked_count") int markedCount,
    @JsonProperty("cleared_count") int clearedCount
) {
}
