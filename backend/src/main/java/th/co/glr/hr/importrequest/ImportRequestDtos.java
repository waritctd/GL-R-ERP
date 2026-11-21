package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read shapes for the STORED ใบขอซื้อ. Carries no price of any kind, matching sales.import_request_item's own
 * decision: the printed form has no price column, and a supplier cost here would leak what
 * FactoryQuoteService/PricingDecisionService keep to import and the CEO. */
public final class ImportRequestDtos {
    private ImportRequestDtos() {}

    public record ImportRequestDto(
        long id,
        /** NULL for an order with no project (สั่งของไม่มีโครงการ): every deal-scoped reader must cope. */
        Long ticketId,
        String ticketCode,
        /** V184: the grouping key was brand; it is now #factoryId. */
        String brand,
        /** The grouping key as of V184. References {@code price_catalog.factories}. */
        Long factoryId,
        /** Display snapshot of {@link #factoryId}'s name at the time this row was created. */
        String factoryName,
        int version,
        String status,
        /** {@code IR<yy><nnn>}, or null while still a draft. */
        String docNumber,
        LocalDate issueDate,
        /** The date this (deal, factory)'s WHOLE revision chain was first issued — carried forward, never overwritten,
         * across a revision's own #issueDate (REVIEW ROUND 1, S7). */
        LocalDate firstIssuedDate,
        // Snapshots frozen at issue.
        String customerName,
        String projectName,
        String requestedByName,
        String requiredByNote,
        LocalDate depositReceivedDate,
        // Import-owned, all optional, editable before and after issue.
        String vesselEtaNote,
        String checkedByName,
        LocalDate checkedDate,
        String approvedByName,
        LocalDate approvedDate,
        // Per-factory progress (V184, GLA-100/S12-S17). Null while status=DRAFT; set to CONTACTED on first issue and
        // carried forward across a revision — see ImportRequestService#issue.
        String importStep,
        LocalDate importStepAt,
        Long importStepById,
        String importStepByName,
        String importStepNote,
        // Lead time / expected arrival (V184, owner decision 09-18 #2). Autofilled from the factory's country
        // (LeadTimeDefaults) on draft creation when a default exists, editable thereafter, carried forward on
        // revision, and required before ISSUE — see ImportRequestService#issue.
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        /** DERIVED, not stored: issueDate + leadTimeMinDays days. Null while DRAFT (issueDate is null) or while either
         * lead-time bound is unset — see ImportRequestService#withPageCount for where this is computed. */
        LocalDate expectedArrivalFrom,
        /** DERIVED: {@code issueDate + leadTimeMaxDays} days. See {@link #expectedArrivalFrom}. */
        LocalDate expectedArrivalTo,
        // Order-email draft (owner decision 09-18 #3 §B). A DRAFT ONLY — nothing in this codebase sends it; see
        // ImportRequestService#buildEmailDraft / #markEmailSent.
        String emailTo,
        String emailSubject,
        String emailBody,
        java.time.Instant emailSentAt,
        Long emailSentById,
        String emailSentByName,
        // Audit.
        Long createdById,
        String createdByName,
        Long issuedById,
        String issuedByName,
        /** REVIEW ROUND 3, item 3: a SNAPSHOT of the issuing user's email at the moment of issue — see
         * sales.import_request.issued_by_email's own comment (V184) for why this is carried separately from
         * #issuedByName rather than resolved fresh on demand. */
        String issuedByEmail,
        Long supersededById,
        Instant createdAt,
        Instant updatedAt,
        Instant issuedAt,
        /** Sheets this form prints on, from ImportRequestRenderer.pagesRequired. Served so a client can warn before
         * issuing that a form will run to a second sheet, rather than discovering it at download. */
        int pageCount,
        List<ImportRequestItemDto> items
    ) {}

    /**
     * GET /tickets/{id}/import-requests/coverage.
     *
     * <p>{@code splitPreview} (slice 6c, an additive sales API contract change): the dry run of {@link
     * ImportRequestService#createDrafts}'s per-factory split, for the "ยืนยันออกใบขอซื้อ" confirm. Present only for a
     * caller who may press that button — the CEO and the deal's owning (or keying) sales rep, createDrafts' own
     * gate; {@code null} for import and sales_manager, who read this route only for the covered flag.
     */
    public record ImportRequestCoverageResponse(boolean hasUncoveredFactories, List<String> uncoveredFactoryNames,
                                                ImportRequestSplitPreview splitPreview) {}

    /**
     * What {@code createDrafts} would issue right now, computed by the same code and saving nothing: one entry per
     * factory that would get a NEW ใบขอซื้อ, in the order it would create them, and the deal lines it could not place
     * (no factory anywhere — createDrafts refuses those with a 409 naming the line).
     */
    public record ImportRequestSplitPreview(List<SplitPreviewFactory> factories,
                                            List<SplitPreviewLine> linesWithoutFactory) {}

    /**
     * One factory's would-be ใบขอซื้อ. No money of any kind (ImportRequestDtosNoPricingFieldsTest).
     *
     * @param newFactory      the name matches no factory yet: createDrafts will ask for its country, then create it
     * @param itemCount       how many deal lines it gets (each line is one item on the form)
     * @param leadTimeSource  {@link #LEAD_TIME_FROM_QUOTATION}, {@link #LEAD_TIME_COUNTRY_DEFAULT}, or {@code null}
     *                        when neither applies yet (createDrafts will ask for it, or for the new factory's country)
     */
    public record SplitPreviewFactory(String factoryName, boolean newFactory, int itemCount,
                                      Integer leadTimeMinDays, Integer leadTimeMaxDays, String leadTimeSource) {
        public static final String LEAD_TIME_FROM_QUOTATION = "QUOTATION";
        public static final String LEAD_TIME_COUNTRY_DEFAULT = "COUNTRY_DEFAULT";
    }

    /** A deal line the split cannot place: neither a factory id nor a factory name anywhere in its cascade. */
    public record SplitPreviewLine(long ticketItemId, String code, String size) {}

    public record ImportRequestItemDto(
        long id,
        long importRequestId,
        /** The deal line this was snapshotted from; null once that line has been deleted. */
        Long ticketItemId,
        int seq,
        String code,
        String size,
        BigDecimal qty,
        String unit,
        /** The printed sub-row (owner decision 09-18 #3 §A) — see {@code ImportRequestItemInput}. */
        String note,
        /** Structured colour, separate from {@link #note} — see {@code ImportRequestItemInput}. */
        String color,
        /** Structured surface/texture, separate from {@link #note}. */
        String texture,
        /** REVIEW ROUND 2, S-A: the line's brand, separate from #code — see ImportRequestItemInput's own Javadoc. Feeds
         * the order-email draft's per-line header. */
        String brand,
        /** The line's raw model text, separate from #code — see ImportRequestItemInput's own Javadoc. */
        String model
    ) {}
}
