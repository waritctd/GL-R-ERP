package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything {@link RemainingInvoicePdfRenderer} prints: the STORED document, nothing recomputed. */
public record RemainingInvoiceRenderModel(
    LocalDate docDate,
    String docNumber,
    String customerName,
    String customerBranch,
    String customerTaxId,
    String customerAddress,
    String projectName,
    String reference,
    String depositReference,
    List<RemainingInvoiceItemDto> items,
    BigDecimal depositDeduction,
    BigDecimal netAmount,
    BigDecimal vatAmount,
    BigDecimal grandTotal,
    String preparerName
) {}
