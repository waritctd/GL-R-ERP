package th.co.glr.hr.importrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Write shapes for the STORED ใบขอซื้อ.
 *
 * <p>Accepted by {@link ImportRequestController}'s PLURAL ({@code /import-requests}) routes — see
 * that class's own Javadoc for the singular/plural split (REVIEW ROUND 2 nit: this used to say "WIP
 * — no controller accepts these yet", true when {@code V154__import_request_document.sql} first
 * designed these shapes but stale since the controller wiring landed).
 */
public final class ImportRequestRequests {
    private ImportRequestRequests() {}

    /**
     * Mirrors every other step's {@code clientRequestId}-idempotent action request shape, plus
     * (V184, owner decision 09-18) the country a caller supplies for any factory {@code
     * createDrafts}'s resolution cascade would otherwise need to auto-create with no country at
     * all. Optional/empty when the deal's lines all resolve to EXISTING factories — the common
     * case, which needs no new-factory country at all.
     */
    public record CreateImportRequestsRequest(
        String clientRequestId,
        @Valid List<NewFactoryCountryInput> newFactoryCountries
    ) {}

    /**
     * One factory NAME's caller-supplied country, for {@code createDrafts}'s auto-create path
     * (owner decision 2, revised 09-18: "Sales should be forced to select country ... if there is
     * unknown make it อื่นๆ and let the input"). Matched against an unresolved line's typed factory
     * name by the SAME {@code ImportRequestService#normalizeFactoryName} the resolution cascade
     * itself uses — a caller MUST supply {@code factoryName} exactly (or near-exactly — normalized)
     * as it appears on the deal line(s) it is meant to cover.
     *
     * @param countryCode  ISO 3166-1 alpha-2, validated against {@code price_catalog.country} —
     *                     {@code 'ZZ'} (อื่นๆ) is a real, always-valid option here.
     * @param countryOther required (non-blank, ≤100 chars) when {@code countryCode} is {@code 'ZZ'},
     *                     forbidden otherwise — the same pairing {@code
     *                     price_catalog.factories.country_other}'s CHECK constraints enforce (V184).
     */
    public record NewFactoryCountryInput(
        @NotBlank String factoryName,
        @NotBlank String countryCode,
        @Size(max = 100) String countryOther
    ) {}

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
        @Valid List<ImportRequestItemInput> items,
        /**
         * Body field (owner decision 09-18 #2): moves only while DRAFT, same as {@code projectName}
         * etc — the owning rep/CEO edit it here. Sent absent leaves it alone; sent present, {@link
         * #leadTimeMaxDays} MUST be present too (both-or-neither — {@code
         * ImportRequestService#requireValidLeadTimePair}), matching {@code
         * chk_import_request_lead_time}'s own pairing. Post-issue editing by import/CEO goes through
         * {@link AdvanceImportStepRequest}'s sibling, {@code POST .../lead-time}
         * ({@link SetLeadTimeRequest}), not this record.
         */
        @Min(1) @Max(365) Integer leadTimeMinDays,
        @Min(1) @Max(365) Integer leadTimeMaxDays,
        /**
         * REVIEW ROUND 2, S-F: "กำหนดวันที่ต้องการของ" on THIS FORM — a BODY field like {@code
         * projectName} (moves only while DRAFT, owning rep/CEO only), separate from {@link
         * SetRequiredByNoteRequest}, which sets the DEAL-level value new drafts snapshot FROM. Sent
         * absent leaves it alone; sent blank/{@code null}-equivalent is not distinguishable from
         * absent under this record's own PATCH semantics, so clearing it to genuinely empty is not
         * supported here — matching every other {@code @Size String} field on this record. If this
         * form's own value is still blank at {@link
         * th.co.glr.hr.importrequest.ImportRequestService#issue}, it is re-snapshotted from the
         * deal's current value at that time (see that method).
         */
        @Size(max = 200) String requiredByNote
    ) {}

    /**
     * @param note    the printed sub-row — free text, prefilled at creation from {@link #color}/
     *                {@link #texture} (owner decision 09-18 #3 §A) but independently editable
     *                thereafter; a business-typed note is never overwritten by re-deriving it — see
     *                {@code ImportRequestLinePrefill#mergeNote}.
     * @param color   structured colour, prefilled from the deal line — carried separately from
     *                {@link #note} so the order-email draft can show a real Colour/Surface/Size
     *                detail line rather than re-parsing formatted Thai text back out of a free-text
     *                field. Optional; PR-B is expected to surface it read-only.
     * @param texture structured surface/texture — see {@link #color}.
     * @param brand   (REVIEW ROUND 2, S-A) the deal line's product brand, prefilled from {@code
     *                ticket_item.brand} — feeds the order-email draft's per-line header
     *                ("<Brand> <Model>  (Code: X)"). Separate from {@code
     *                sales.import_request.brand}, the FORM-level display snapshot.
     * @param model   the deal line's RAW hand-typed model text, deliberately separate from {@link
     *                #code} — see {@code ImportRequestQueryRepository.LineFactoryCandidate#model}'s
     *                own Javadoc for why the two are frequently different values.
     */
    public record ImportRequestItemInput(
        Long ticketItemId,
        @NotBlank @Size(max = 255) String code,
        @Size(max = 80) String size,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
        @Size(max = 30) String unit,
        @Size(max = 255) String note,
        @Size(max = 255) String color,
        @Size(max = 255) String texture,
        @Size(max = 255) String brand,
        @Size(max = 255) String model
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

    /**
     * Advances an ISSUED row's per-factory progress by one step (V184, GLA-100/S12-S17).
     *
     * @param targetStep one of {@link ImportRequestStep#ORDER}. Forward-only from the row's current
     *                   step (skips allowed — the owner accepted "forward skips allowed, never
     *                   backwards; correct via revision" as a default), enforced by the service, not
     *                   this record.
     * @param eventDate  backdatable — import may log a step after the fact. Null defaults to today
     *                   (Bangkok).
     * @param note       free text, optional.
     */
    public record AdvanceImportStepRequest(
        @NotBlank String targetStep,
        LocalDate eventDate,
        @Size(max = 500) String note
    ) {}

    /**
     * Sets an ISSUED row's lead time (owner decision 09-18 #2) — import/CEO only (the DRAFT-stage
     * edit for the owning rep/CEO goes through {@link UpdateImportRequestRequest} instead, exactly
     * like the other body fields). Both fields REQUIRED, unlike the PATCH-optional pair on {@link
     * UpdateImportRequestRequest}: this route's one job is setting them, so there is no "absent
     * means leave alone" case to support. Cross-field ({@code min <= max}) is checked by the
     * service, not by a bean-validation annotation.
     */
    public record SetLeadTimeRequest(
        @NotNull @Min(1) @Max(365) Integer leadTimeMinDays,
        @NotNull @Min(1) @Max(365) Integer leadTimeMaxDays
    ) {}

    /**
     * Edits the drafted order-email (owner decision 09-18 #3 §B) — PATCH semantics like {@link
     * UpdateImportRequestRequest}: an absent field is left alone. Refused once the draft has been
     * marked sent — see {@code ImportRequestService#updateEmailDraft}.
     */
    public record UpdateEmailDraftRequest(
        @Size(max = 255) String emailTo,
        @Size(max = 255) String emailSubject,
        @Size(max = 20000) String emailBody
    ) {}
}
