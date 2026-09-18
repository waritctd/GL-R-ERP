package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice, prints as "ใบแจ้งยอด") — stateless: built fresh on
 * every download from the ticket's own data (no stored row, no migration; see
 * DepositNoticeService#getRemainingInvoiceXlsx/getRemainingInvoiceOptions).
 *
 * <p>{@code reference} (H9, อ้างอิงใบเสนอราคา/ใบสั่งซื้อ) and {@code depositReference} (the "หัก
 * มัดจำ" row's label suffix) are deliberately SEPARATE fields, not one shared value as the
 * previous renderer assumed — a customer PO number typed into H9 has nothing to do with which
 * receipt/deposit-notice number the deduction row should cite, and conflating them made the
 * deduction row wrongly print the quotation reference.
 */
public record RemainingInvoiceDto(
    String     docNumber,
    LocalDate  issueDate,
    String     reference,
    String     depositReference,
    String     customerName,
    String     customerBranch,
    String     customerAddress,
    String     customerTaxId,
    String     projectName,
    BigDecimal depositAmount,
    List<String> notes,
    List<RemainingInvoiceItemDto> items
) {}
