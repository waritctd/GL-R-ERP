package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AttendanceDatImportRequest(
    @JsonProperty("site_code")
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9_]{2,20}$")
    String siteCode,

    @JsonProperty("device_code")
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9_]{2,40}$")
    String deviceCode,

    @JsonProperty("file_name")
    @NotBlank
    @Size(max = 260)
    String fileName,

    @NotBlank
    @Size(max = 5_000_000) // ~5 MB cap; a .dat punch export is far smaller. Guards against OOM.
    String content
) {
}
