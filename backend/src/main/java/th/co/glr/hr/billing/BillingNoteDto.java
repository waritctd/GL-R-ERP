package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The shape {@link BillingNoteRenderer} actually renders — converted from a stored {@link
 * BillingNoteDocumentDto} by {@link BillingNoteService#toRenderable}, mirroring how {@code
 * RemainingInvoiceDto} sits beside {@code RemainingInvoiceDocumentDto} for the same reason: the
 * renderer never needs to know it is rendering a stored snapshot rather than something built
 * on the fly. */
public record BillingNoteDto(
    String docNumber,
    LocalDate billDate,
    String customerName,
    String customerBranch,
    String customerAddress,
    String customerTaxId,
    String note,
    BigDecimal totalAmount,
    List<BillingNoteLineRenderDto> lines
) {}
