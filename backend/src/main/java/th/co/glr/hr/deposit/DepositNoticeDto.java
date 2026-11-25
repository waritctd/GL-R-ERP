package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record DepositNoticeDto(
    long              id,
    long              ticketId,
    String            docType,
    int               version,
    String            docNumber,
    LocalDate         issueDate,
    String            status,
    String            customerName,
    String            customerTaxId,
    String            customerAddress,
    String            projectName,
    String            reference,
    String            currency,
    BigDecimal        depositPercent,
    BigDecimal        subtotal,
    BigDecimal        depositAmount,
    BigDecimal        vatPercent,
    BigDecimal        vatAmount,
    BigDecimal        totalPayable,
    List<String>      notes,
    // One-render gap (issue #752): DepositNoticeService.issue defers the PDF/XLSX render to an afterCommit
    // callback (renderAfterCommit, PR #721), so the DTO returned by issue() itself still reports these false —
    // built before the render runs — even though the document is otherwise fully ISSUED.
    boolean           hasPdf,
    boolean           hasXlsx,
    String            issuedByName,
    String            preparerName,
    Long              preparerId,
    OffsetDateTime    createdAt,
    OffsetDateTime    updatedAt,
    List<DepositNoticeItemDto> items,
    // true when customerName/TaxId/Address are the quotation party's (read-only in the editor), false for stored values
    boolean           customerFromQuotation,
    // เรียน picked from the customer list instead of the quotation's customer (feedback #74); null = the quotation's
    Long              addresseeCustomerId
) {
    public IssueNames names() {
        return new IssueNames(addresseeCustomerId);
    }
}
