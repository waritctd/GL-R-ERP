package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * PUT /api/tickets/{id}/tracking body. Full-replace semantics (like {@code waiveDeposit}/
 * {@code setEntryChannel}), not a merge — a field left {@code null} clears that field (for
 * {@code winProbability}, null means "fall back to the stage default", which is the intended way
 * to clear a rep's override).
 */
public record TrackingUpdateRequest(
    @Min(0) @Max(100) Integer winProbability,
    @Size(max = 200) String designerName,
    @Size(max = 200) String ownerName,
    @Size(max = 200) String buyerName,
    LocalDate nextFollowUpAt
) {}
