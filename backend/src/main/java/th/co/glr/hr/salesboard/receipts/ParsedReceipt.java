package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One data row of an Express receipts file, after header-alias mapping (see {@link ReceiptFileParser}).
 * {@code line} is the 1-based row number in the file, for error messages. {@code customerName} and {@code reference}
 * may be null; the other fields are always present (rows missing them are reported as invalid, not parsed).
 */
public record ParsedReceipt(int line, String docNo, String docType, LocalDate docDate, String customerName, BigDecimal amount,
                            String reference) {}
