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
    String preparerName,
    // the chosen preparer's own signature image (hr.employee_signature), or null when none is on file
    byte[] preparerSignature,
    // the ออกในนาม line printed under เรียน (feedback #74); null prints nothing
    String payerLine
) {
    /** A document with no signature image. */
    public DepositNoticeRenderModel(LocalDate issueDate, String docNumber, String customerName, String customerBranch,
                                    String customerTaxId, String customerAddress, String projectName, String reference,
                                    List<DepositNoticeItemDto> items, BigDecimal subtotal, BigDecimal depositPercent,
                                    BigDecimal depositAmount, BigDecimal vatAmount, BigDecimal totalPayable,
                                    String preparerName) {
        this(issueDate, docNumber, customerName, customerBranch, customerTaxId, customerAddress, projectName,
            reference, items, subtotal, depositPercent, depositAmount, vatAmount, totalPayable, preparerName, null, null);
    }
}
