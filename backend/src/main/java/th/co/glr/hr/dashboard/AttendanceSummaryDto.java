package th.co.glr.hr.dashboard;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttendanceSummaryDto(
    String scope,
    Long todayPresent,
    Long lateToday,
    Long missingCheckout,
    Long punchCountToday,
    Long monthlyAttendanceDays,
    String todayStatus,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    OffsetDateTime firstIn,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    OffsetDateTime lastOut,
    Integer lateMinutesToday
) {
    public static AttendanceSummaryDto empty(String scope) {
        return new AttendanceSummaryDto(scope, 0L, 0L, 0L, 0L, 0L, null, null, null, null);
    }
}
