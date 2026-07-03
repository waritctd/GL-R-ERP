package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A registered scanner/location the HR team can attribute an imported .dat file to. */
public record AttendanceDeviceDto(
    @JsonProperty("device_code")
    String deviceCode,
    @JsonProperty("device_name")
    String deviceName,
    @JsonProperty("site_code")
    String siteCode,
    @JsonProperty("site_name")
    String siteName
) {
}
