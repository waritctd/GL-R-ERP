package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AttendanceImportResponse(
    @JsonProperty("import_id")
    Long importId,
    String status,
    @JsonProperty("row_count")
    int rowCount,
    @JsonProperty("inserted_punch_count")
    int insertedPunchCount,
    @JsonProperty("skipped_punch_count")
    int skippedPunchCount,
    @JsonProperty("error_count")
    int errorCount
) {
}
