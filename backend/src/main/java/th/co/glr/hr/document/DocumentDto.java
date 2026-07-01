package th.co.glr.hr.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record DocumentDto(
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
    boolean           hasPdf,
    boolean           hasXlsx,
    String            issuedByName,
    String            preparerName,
    OffsetDateTime    createdAt,
    OffsetDateTime    updatedAt,
    List<DocumentItemDto> items
) {}
