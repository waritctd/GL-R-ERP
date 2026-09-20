package th.co.glr.hr.deposit;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * The STORED remaining invoice's own create/update body (GLA-99 step 2) — the same dialog fields
 * {@code RemainingInvoiceDialog.jsx} already collects for the stateless preview
 * ({@code reference}/{@code depositReference}/{@code issueDate}/note selection), now persisted.
 *
 * <p>{@code quotationId} is meaningful on {@link RemainingInvoiceService#createDraft} only (which
 * accepted-quotation chain to snapshot from — {@code null} picks the newest qualifying one, exactly
 * as the stateless {@code /options}/{@code /file} endpoints already do); {@link
 * RemainingInvoiceService#updateDraft} ignores it and always re-resolves against the DRAFT's own
 * already-chosen {@code customerQuotationId}, so a draft can never be silently re-sourced from a
 * different quotation mid-edit.
 *
 * <p>Every field is optional and PATCH-shaped on update — an absent field leaves the stored value
 * alone (see {@link RemainingInvoiceRepository#updateDraftFields}) — but {@code notes} is
 * whole-value replace, not append, matching how the dialog's own checkbox list always submits its
 * full current selection.
 */
public record RemainingInvoiceDraftRequest(
    Long quotationId,
    @Size(max = 255) String reference,
    @Size(max = 255) String depositReference,
    LocalDate docDate,
    List<String> notes
) {}
