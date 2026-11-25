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
    Long preparerId,
    // เรียน choice (feedback #74), as DepositNoticeDraftRequest's; both null keeps what is stored (create: the default,
    // the เรียน chosen on the deal's notice for this quotation, else the quotation's customer, with the choice on top)
    Long addresseeCustomerId,
    Boolean addresseeReset
) {
    IssueNames.Choice nameChoice() {
        return new IssueNames.Choice(addresseeCustomerId, addresseeReset);
    }

    public RemainingInvoiceDraftRequest(Long quotationId, String reference, String depositReference,
                                        LocalDate docDate, List<String> notes) {
        this(quotationId, reference, depositReference, docDate, notes, null, null, null);
    }

    public RemainingInvoiceDraftRequest(Long quotationId, String reference, String depositReference,
                                        LocalDate docDate, List<String> notes, Long preparerId) {
        this(quotationId, reference, depositReference, docDate, notes, preparerId, null, null);
    }
}
