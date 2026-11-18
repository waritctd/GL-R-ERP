package th.co.glr.hr.deposit;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** The STORED remaining invoice's own create/update body (GLA-99 step 2) — the same dialog fields
 * RemainingInvoiceDialog.jsx already collects for the stateless preview
 * (reference/depositReference/issueDate/note selection), now persisted. */
public record RemainingInvoiceDraftRequest(
    Long quotationId,
    @Size(max = 255) String reference,
    @Size(max = 255) String depositReference,
    LocalDate docDate,
    List<String> notes,
    // ผู้จัดทำ: the deal's owner, keyer or a sales manager; null keeps the stored preparer.
    Long preparerId
) {
    public RemainingInvoiceDraftRequest(Long quotationId, String reference, String depositReference,
                                        LocalDate docDate, List<String> notes) {
        this(quotationId, reference, depositReference, docDate, notes, null);
    }
}
