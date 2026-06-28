package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

public record AttendancePunchRequest(
    @JsonProperty("site_code")
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9_]{2,20}$")
    String siteCode,

    @JsonProperty("device_code")
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9_]{2,40}$")
    String deviceCode,

    @JsonProperty("badge_code")
    @NotBlank
    String badgeCode,

    @JsonProperty("punch_time")
    @NotNull
    OffsetDateTime punchTime,

    @JsonProperty("work_date")
    LocalDate workDate,

    @JsonProperty("device_status")
    @Min(0)
    @Max(255)
    Short deviceStatus,

    @JsonProperty("punch_state")
    @Min(0)
    @Max(255)
    Short punchState,

    @JsonProperty("work_code")
    String workCode,

    @JsonProperty("reserved_value")
    String reservedValue,

    @JsonProperty("punch_source")
    @Pattern(regexp = "^(BIOMETRIC|WEB_CHECKIN|MANUAL)$")
    String punchSource,

    @JsonProperty("ingest_method")
    @Pattern(regexp = "^(LIVE_CAPTURE|CATCHUP_PULL|USB_DAT_IMPORT|WEB_PORTAL|MANUAL_ENTRY)$")
    String ingestMethod,

    @JsonProperty("raw_payload")
    Map<String, Object> rawPayload
) {
}
