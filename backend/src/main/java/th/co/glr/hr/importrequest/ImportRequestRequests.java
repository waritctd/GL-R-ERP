package th.co.glr.hr.importrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Write shapes for the STORED ใบขอซื้อ.
 *
 * <p><strong>WIP — no controller accepts these yet.</strong> See
 * {@code V154__import_request_document.sql}'s closing list.
 */
public final class ImportRequestRequests {
    private ImportRequestRequests() {}

    /** Mirrors every other step's {@code clientRequestId}-idempotent action request shape. */
    public record CreateImportRequestsRequest(String clientRequestId) {}

    /**
     * Edits a draft, or the import-owned footer of an issued form.
     *
     * <p>Every field is nullable and every nullable field is <strong>ignored when absent</strong>
     * rather than written as null — a PATCH, not a PUT. That matters because two different callers
     * touch this: the draft editor sends the body fields, and the footer editor sends only
     * {@code vesselEtaNote}/checked/approved. A PUT shape would have the second silently blank the
     * first's work.
     *
     * <p>{@link #items} is the whole line list or nothing: sending it replaces every line, sending it
     * absent leaves them alone. Partial line edits are deliberately not supported — the printed form
     * is a numbered sequence, so a caller that could add one line without restating the rest would
     * have to be trusted to renumber, and it would not be.
     */
    public record UpdateImportRequestRequest(
        @Size(max = 200) String projectName,
        @Size(max = 200) String customerName,
        @Size(max = 200) String requestedByName,
        @Size(max = 200) String vesselEtaNote,
        @Size(max = 200) String checkedByName,
        LocalDate checkedDate,
        @Size(max = 200) String approvedByName,
        LocalDate approvedDate,
        @Valid List<ImportRequestItemInput> items
    ) {}

    public record ImportRequestItemInput(
        Long ticketItemId,
        @NotBlank @Size(max = 255) String code,
        @Size(max = 80) String size,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
        @Size(max = 30) String unit,
        @Size(max = 255) String note
    ) {}

    /**
     * Issues a draft: mints {@code IR<yy><nnn>} and freezes the body.
     *
     * <p>{@link #docNumber} is the owner-requested OVERRIDE ("the ir number should be able to be
     * overriden too"). When supplied it is used verbatim and the sequence is NOT advanced — so a
     * number typed ahead of the counter cannot later be minted a second time by accident, because
     * {@code ux_import_request_doc_number} refuses the duplicate outright. When absent the next
     * sequence value is minted normally.
     */
    public record IssueImportRequestRequest(
        @Size(max = 30) String docNumber
    ) {}

    /**
     * Sales-owned: "กำหนดวันที่ต้องการของ" on the DEAL, snapshotted onto each IR at issue.
     *
     * <p>Free text, not a date — the owner's own IR69068 reads "Within 21/5/26", which no date column
     * could hold without changing what the customer was told. Nullable so it can be cleared.
     */
    public record SetRequiredByNoteRequest(
        @Size(max = 200) String requiredByNote
    ) {}
}
