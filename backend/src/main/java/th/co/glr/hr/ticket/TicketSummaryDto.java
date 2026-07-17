package th.co.glr.hr.ticket;

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
    boolean overdue
) {
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
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }
}
