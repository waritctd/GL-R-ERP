package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything {@link DepositNoticePdfRenderer} prints: the STORED document, nothing recomputed. */
public record DepositNoticeRenderModel(
    LocalDate issueDate,
    String docNumber,
    String customerName,
    String customerBranch,
    String customerTaxId,
    String customerAddress,
    String projectName,
    String reference,
    List<DepositNoticeItemDto> items,
    BigDecimal subtotal,
    BigDecimal depositPercent,
    BigDecimal depositAmount,
    BigDecimal vatAmount,
    BigDecimal totalPayable,
    String preparerName
) {}
