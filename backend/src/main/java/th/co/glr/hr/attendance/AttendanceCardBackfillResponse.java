package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AttendanceCardBackfillResponse(
    // employees whose badge_card_no was set from a device card number
    @JsonProperty("updated")
    int updated,
    // rows with no/zero card number (fingerprint/PIN-only users) — nothing to map
    @JsonProperty("skipped")
    int skipped,
    // card rows whose User ID matched no employee_code
    @JsonProperty("unmatched")
    int unmatched
) {
}
