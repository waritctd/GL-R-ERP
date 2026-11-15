package th.co.glr.hr.salesboard.receipts;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two owner switches for the Express receipts upload (review H4).
 * <ul>
 *   <li>{@code app.receipts.express-upload.enabled} (default FALSE) — the whole upload is off until the owner has
 *       checked a real Express export against the documented template (none has been seen yet, spec §11). Off: upload
 *       is 409 and the UI hides the upload card and page.</li>
 *   <li>{@code app.receipts.cash-doc-types} (default {@code RE}) — the document types that are CASH. An IV (invoice) or
 *       any other type is not money received; such rows are counted as "ไม่ใช่ใบเสร็จรับเงิน" and written nowhere.</li>
 * </ul>
 */
public record ReceiptImportSettings(boolean uploadEnabled, Set<String> cashDocTypes) {
    public static final Set<String> DEFAULT_CASH_DOC_TYPES = Set.of("RE");

    public ReceiptImportSettings {
        cashDocTypes = cashDocTypes == null || cashDocTypes.isEmpty()
            ? DEFAULT_CASH_DOC_TYPES
            : cashDocTypes.stream().map(t -> t.trim().toUpperCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isCash(String docType) {
        return docType != null && cashDocTypes.contains(docType.trim().toUpperCase(Locale.ROOT));
    }

    @Configuration
    static class Config {
        @Bean
        ReceiptImportSettings receiptImportSettings(
            @Value("${app.receipts.express-upload.enabled:false}") boolean enabled,
            @Value("${app.receipts.cash-doc-types:RE}") List<String> cashDocTypes) {
            return new ReceiptImportSettings(enabled, Set.copyOf(cashDocTypes));
        }
    }
}
