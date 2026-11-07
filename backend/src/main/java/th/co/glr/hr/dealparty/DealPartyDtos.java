package th.co.glr.hr.dealparty;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Deal party (V204, docs/decisions/deal-party-model.md): one customer taking part in a deal in one
 * role. Each ผู้รับเหมา bidding on the project is a {@code BUYER} party with its own quotation chain
 * and its own outcome; the deal is counted once in the pipeline however many bid.
 */
public final class DealPartyDtos {
    private DealPartyDtos() {}

    public static final String ROLE_DESIGNER = "DESIGNER";
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_BUYER = "BUYER";
    public static final Set<String> ROLES = Set.of(ROLE_DESIGNER, ROLE_OWNER, ROLE_BUYER);

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_QUOTED = "QUOTED";
    public static final String STATUS_NEGOTIATING = "NEGOTIATING";
    public static final String STATUS_AWARDED = "AWARDED";
    public static final String STATUS_AWARDED_SPLIT = "AWARDED_SPLIT";
    public static final String STATUS_NOT_AWARDED = "NOT_AWARDED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    /** The statuses a party may still be moved to by hand (PATCH); outcomes go through {@code bid-outcome}. */
    public static final Set<String> PATCHABLE_STATUSES = Set.of(STATUS_QUOTED, STATUS_NEGOTIATING, STATUS_WITHDRAWN);
    /** A party still in the running for the outcome. */
    public static final Set<String> IN_RUNNING = Set.of(STATUS_ACTIVE, STATUS_QUOTED, STATUS_NEGOTIATING);

    public static final Set<String> NOT_AWARDED_REASONS = Set.of(
        "OTHER_BIDDER", "PRICE", "LEAD_TIME", "PAYMENT_TERMS", "RELATIONSHIP", "PRODUCT_FIT");
    public static final String OTHERS_NOT_AWARDED = "NOT_AWARDED";
    public static final String OTHERS_KEEP_WAITING = "KEEP_WAITING";

    /** One party as the API serves it, with its latest live quotation (if any) read alongside. */
    public record DealPartyDto(
        long id,
        long ticketId,
        long customerId,
        String customerName,
        Long contactId,
        String contactName,
        String role,
        String status,
        String notAwardedReason,
        boolean headline,
        Long splitTicketId,
        String splitTicketCode,
        Long latestQuotationId,
        String latestQuotationNumber,
        String latestQuotationStatus,
        /** The latest live quotation's grand total (subtotal + VAT by its document language), like the register shows. */
        BigDecimal latestQuotationAmount,
        Long addedById,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record PartiesResponse(List<DealPartyDto> parties) {}

    public record PartyResponse(DealPartyDto party) {}

    public record AddPartyRequest(@NotNull Long customerId, @NotNull String role, Long contactId) {}

    /** Pre-outcome edits only: a status among {@link #PATCHABLE_STATUSES}, a contact of the party's customer, or
     * {@code headline = true} to make this party's quotation the deal's headline amount. Null fields are untouched. */
    public record PatchPartyRequest(String status, Long contactId, Boolean headline) {}

    /**
     * "บันทึกผลประมูล": one or more winners. {@code primaryPartyId} (one of the winners, default the first)
     * becomes the deal's bill-to; every other winner is forked to a child deal. The contractors not named become
     * {@code NOT_AWARDED} with {@code notAwardedReason} (default {@code OTHER_BIDDER}), or stay as they are when
     * {@code othersOutcome} is {@code KEEP_WAITING}.
     */
    public record BidOutcomeRequest(
        @NotEmpty List<Long> awardedPartyIds,
        Long primaryPartyId,
        String othersOutcome,
        String notAwardedReason
    ) {}

    public record BidOutcomeResult(long primaryTicketId, List<Long> childTicketIds, TicketSummaryDto ticket) {}

    public record BidOutcomeResponse(BidOutcomeResult result) {}

    /** A deal the quotation editor may attach a new ผู้รับเหมา to: the actor's open, pre-outcome deal on this project. */
    public record OpenDealDto(
        long id, String code, String projectName, String salesStage, String customerName,
        int buyerCount, List<OpenDealPartyDto> parties
    ) {}

    public record OpenDealPartyDto(long id, String customerName, String role, String status) {}

    public record OpenDealsResponse(List<OpenDealDto> deals) {}
}
