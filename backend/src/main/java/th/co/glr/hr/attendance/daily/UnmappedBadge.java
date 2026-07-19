package th.co.glr.hr.attendance.daily;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * A badge that scanned but resolves to no employee, aggregated per badge.
 *
 * <p>These punches can never appear in the day view — {@code attendance_daily.employee_id} is NOT
 * NULL — so without surfacing them here someone's attendance would go missing silently.
 */
public record UnmappedBadge(
    @JsonProperty("badge_code") String badgeCode,
    @JsonProperty("punch_count") int punchCount,
    @JsonProperty("first_seen") OffsetDateTime firstSeen,
    @JsonProperty("last_seen") OffsetDateTime lastSeen,
    @JsonProperty("site_code") String siteCode
) {
}
