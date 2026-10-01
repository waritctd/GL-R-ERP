package th.co.glr.hr.pricingrequest;

import java.time.Instant;
import java.util.List;

/** CR-1 (GLA-167): read models for the lead-time change flow. Old values are the snapshot taken at request time. */
public final class LeadTimeChangeDtos {
    private LeadTimeChangeDtos() {}

    public record LeadTimeChangeLineDto(
        long pricingRequestItemId,
        Integer oldMinDays,
        Integer oldMaxDays,
        int newMinDays,
        int newMaxDays
    ) {}

    public record LeadTimeChangeDto(
        long id,
        long factoryQuoteId,
        long pricingRequestId,
        String status,
        String reason,
        long requestedBy,
        Instant requestedAt,
        Long decidedBy,
        Instant decidedAt,
        String decisionReason,
        List<LeadTimeChangeLineDto> lines,
        // optimistic concurrency: import edits bump it; approve/reject must quote the version they saw
        int version
    ) {}
}
