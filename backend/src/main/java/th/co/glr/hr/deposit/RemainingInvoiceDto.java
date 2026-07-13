package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RemainingInvoiceDto(
    String     docNumber,
    LocalDate  issueDate,
    String     reference,
    String     customerName,
    String     customerAddress,
    String     customerTaxId,
    String     projectName,
    BigDecimal depositAmount,
    List<RemainingInvoiceItemDto> items
) {}
