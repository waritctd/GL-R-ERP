package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * Returned once when an HR user rotates a device's agent token. The plaintext {@code agentToken} is
 * shown only here — the server keeps just its hash — so it must be copied into the device agent now.
 */
public record RotateAgentTokenResponse(
    @JsonProperty("device_code") String deviceCode,
    @JsonProperty("agent_token") String agentToken,
    @JsonProperty("rotated_at") OffsetDateTime rotatedAt
) {
}
