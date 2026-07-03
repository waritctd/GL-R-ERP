package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record AttendancePunchDto(
    @JsonProperty("punch_id")
    long punchId,
    @JsonProperty("employee_id")
    Long employeeId,
    @JsonProperty("employee_code")
    String employeeCode,
    @JsonProperty("employee_name")
    String employeeName,
    @JsonProperty("nick_name")
    String nickName,
    @JsonProperty("position_th")
    String positionTh,
    @JsonProperty("badge_card_no")
    String badgeCardNo,
    @JsonProperty("badge_code")
    String badgeCode,
    @JsonProperty("punch_time")
    OffsetDateTime punchTime,
    @JsonProperty("work_date")
    LocalDate workDate,
    @JsonProperty("site_code")
    String siteCode,
    @JsonProperty("device_code")
    String deviceCode,
    @JsonProperty("device_name")
    String deviceName,
    @JsonProperty("device_status")
    short deviceStatus,
    @JsonProperty("punch_state")
    short punchState,
    @JsonProperty("work_code")
    String workCode,
    @JsonProperty("reserved_value")
    String reservedValue,
    @JsonProperty("punch_source")
    String punchSource,
    @JsonProperty("ingest_method")
    String ingestMethod,
    @JsonProperty("created_at")
    OffsetDateTime createdAt
) {
}
