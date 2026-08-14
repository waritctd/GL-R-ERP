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
    // One-render gap (issue #752): DepositNoticeService.issue defers the PDF/XLSX render to an
    // afterCommit callback (renderAfterCommit, PR #721), so the DTO returned by issue() itself
    // still reports these false — built before the render runs — even though the document is
    // otherwise fully ISSUED. Only a SUBSEQUENT read of the same document reports both true.
    boolean           hasPdf,
    boolean           hasXlsx,
    String            issuedByName,
    String            preparerName,
    OffsetDateTime    createdAt,
    OffsetDateTime    updatedAt,
    List<DepositNoticeItemDto> items
) {}
