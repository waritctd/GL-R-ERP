package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

public record SpecialMoneyRequestDto(
    long id,
    long employeeId,
    String employeeCode,
    String employeeName,
    String requestType,
    LocalDate eventDate,
    LocalDate eventEndDate,
    LocalDate receiptDate,
    BigDecimal quantity,
    BigDecimal requestedAmount,
    BigDecimal approvedAmount,
    String payrollBucket,
    int policyVersion,
    String reason,
    Map<String, String> detail,
    String status,
    LocalDate payrollMonth,
    String capOverrideReason,
    Long requestedById,
    String requestedByName,
    OffsetDateTime requestedAt,
    Long managerApprovedBy,
    String managerApprovedByName,
    OffsetDateTime managerApprovedAt,
    Long ceoApprovedBy,
    String ceoApprovedByName,
    OffsetDateTime ceoApprovedAt,
    Long reviewedById,
    String reviewedByName,
    OffsetDateTime reviewedAt,
    String reviewerNote,
    OffsetDateTime cancelledAt,
    /** Reports-to, for display only: welfare has no manager stage, so this grants nothing. */
    Long managerEmployeeId,
    String managerName,
    /**
     * How many pieces of evidence are attached. Projected so a reviewer sees the document trail
     * before opening the request — and so the UI can warn before an approval the server will refuse
     * for an evidence-required type.
     */
    int attachmentCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {
}
