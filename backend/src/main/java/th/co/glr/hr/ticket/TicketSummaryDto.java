package th.co.glr.hr.ticket;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TicketSummaryDto(
    long id,
    String code,
    String type,
    String title,
    String status,
    String priority,
    long createdById,
    String createdByName,
    Long assignedToId,
    String assignedToName,
    String customerName,
    Long customerId,
    Long projectId,
    String projectName,
    Long contactId,
    String contactName,
    String note,
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt,
    int itemCount,
    boolean hasEdits,
    String paymentStatus,
    String fulfillmentStatus,
    // Deal pipeline (V50): one ticket = one deal running the 14-stage journey.
    String salesStage,
    String lostReason,
    Instant lostAt,
    Instant stageUpdatedAt,
    // Deal lifecycle and policy fields (V51): lifecycle gates mutations, policy
    // fields drive tender/deposit/entry-channel workflow choices.
    String lifecycle,
    String tenderRequirement,
    String depositPolicy,
    String depositPolicyReason,
    String entryChannel,
    LocalDate billingDate,
    LocalDate dueDate,
    Integer creditTermDays,
    LocalDate lastFollowUpAt,
    LocalDate nextFollowUpAt,
    String paymentStage,
    BigDecimal amountPayable,
    BigDecimal amountPaid,
    BigDecimal amountOutstanding,
    boolean overdue,
    /** ฝ่ายบัญชี's close confirmation; non-null means the deal is awaiting CEO verification. */
    Instant closeConfirmedAt,
    String closeConfirmedByName,
    /** An INVOICE attachment is on file — a prerequisite for confirming the close. */
    boolean invoiceOnFile,
    /** Why the opportunity went away (V57). Distinct from lostReason. */
    String cancelReason,
    Instant cancelledAt,
    // Deal tracking fields (V83, Slice B1 "kill the weekly report" — handoff 103).
    /** Rep-set override, or null to use {@link WinProbabilityDefaults#defaultFor(String)}. */
    Integer winProbabilityOverride,
    String designerName,
    String ownerName,
    String buyerName,
    /**
     * Computed, not stored: true when this ACTIVE deal has no {@code sales.deal_activity} row
     * logged in the last 7 days (or none ever). See {@code TicketRepository#isStale}.
     */
    boolean stale,
    /**
     * A live {@code SALE} commission already exists for this deal — the same question {@code
     * CommissionService.createFromDeal}'s duplicate guard asks ({@code
     * CommissionRepository.hasActiveCommissionForTicket}), served here so the accountant's
     * worklist ({@code accountActions.js#nextAccountAction}) stops guessing whether a {@code
     * CLOSED_PAID} deal still needs its commission recorded and permanently over-counting its
     * backlog (issue #736).
     *
     * <p>Deliberate disclosure decision: this exposes only the EXISTENCE of a commission to any
     * ticket reader, never an amount — amounts stay behind {@code CommissionController's}
     * role check, which the {@code account} role has no route through ({@code
     * canListCommissionRecords = ['sales','sales_manager','ceo']}). {@link #invoiceOnFile} is
     * the existing precedent for a boolean derived from a related table on this DTO.
     */
    boolean commissionRecorded
) {
    /**
     * Override wins when set, else the {@link DealStage}-derived default. Never a blocker.
     *
     * <p><b>{@code @JsonProperty} is what makes this reach the client, and it is the point.</b>
     * Jackson serializes a record's COMPONENTS; an extra method is invisible to it unless
     * annotated. So this number was computed here and never sent, and the frontend re-derived it
     * from a hand-copied table (`features/tickets/dealTrackingMeta.js`) to render the win-weighted
     * pipeline forecast — the last surviving instance of the pattern #712 (commission tiers) and
     * #713 (stage rules) removed, and #714 was a live example of it going wrong: V143 inserted
     * QUOTE_OWNER, the copy was not updated, and every S5 deal quietly contributed 0% to a
     * money-shaped forecast. See issue #738.
     *
     * <p>{@code READ_ONLY} because there is no matching record component to deserialize into:
     * this record is serialize-only on the wire, and the access mode says so rather than relying
     * on nobody ever pointing Jackson at it in the other direction.
     */
    @JsonProperty(value = "effectiveWinProbability", access = JsonProperty.Access.READ_ONLY)
    public int effectiveWinProbability() {
        return WinProbabilityDefaults.effective(winProbabilityOverride, salesStage);
    }

    public TicketSummaryDto(
        long id, String code, String type, String title, String status, String priority,
        long createdById, String createdByName, Long assignedToId, String assignedToName,
        String customerName, Long customerId, Long projectId, String projectName,
        Long contactId, String contactName, String note,
        Instant createdAt, Instant updatedAt, Instant closedAt, int itemCount, boolean hasEdits,
        String paymentStatus, String fulfillmentStatus,
        String salesStage, String lostReason, Instant lostAt, Instant stageUpdatedAt,
        String lifecycle, String tenderRequirement, String depositPolicy, String depositPolicyReason,
        String entryChannel
    ) {
        this(id, code, type, title, status, priority, createdById, createdByName, assignedToId,
            assignedToName, customerName, customerId, projectId, projectName, contactId, contactName,
            note, createdAt, updatedAt, closedAt, itemCount, hasEdits, paymentStatus, fulfillmentStatus,
            salesStage, lostReason, lostAt, stageUpdatedAt, lifecycle, tenderRequirement, depositPolicy,
            depositPolicyReason, entryChannel, null, null, null, null, null, PaymentStage.NOT_REQUIRED,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null, null, false, null, null,
            null, null, null, null, false, false);
    }

    /**
     * Copy with only {@code customerName}/{@code projectName} overridden — every other field
     * unchanged. Used to render a document (e.g. a Step 4 customer quotation) against a FROZEN
     * customer/project header snapshot instead of the ticket's current live values, the same
     * substitution {@code TicketService.loadQuotationContext}'s own private
     * {@code withCustomerAndProject} already performs for the legacy quotation flow — exposed
     * here as a record method so other packages (e.g.
     * {@code th.co.glr.hr.customerquotation}) can reuse the exact same pattern without a second,
     * hand-copied field list to keep in sync as this record grows.
     */
    public TicketSummaryDto withCustomerAndProject(String frozenCustomerName, String frozenProjectName) {
        return new TicketSummaryDto(id, code, type, title, status, priority, createdById, createdByName,
            assignedToId, assignedToName, frozenCustomerName, customerId, projectId, frozenProjectName,
            contactId, contactName, note, createdAt, updatedAt, closedAt, itemCount, hasEdits,
            paymentStatus, fulfillmentStatus, salesStage, lostReason, lostAt, stageUpdatedAt, lifecycle,
            tenderRequirement, depositPolicy, depositPolicyReason, entryChannel, billingDate, dueDate,
            creditTermDays, lastFollowUpAt, nextFollowUpAt, paymentStage, amountPayable, amountPaid,
            amountOutstanding, overdue, closeConfirmedAt, closeConfirmedByName, invoiceOnFile,
            cancelReason, cancelledAt, winProbabilityOverride, designerName, ownerName, buyerName, stale,
            commissionRecorded);
    }
}
