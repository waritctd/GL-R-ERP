package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read shapes for the STORED ใบขอซื้อ.
 *
 * <p>Served by {@link ImportRequestController}'s PLURAL routes (REVIEW ROUND 2 nit: this used to
 * say "Import/CEO only" and "WIP — nothing serves these yet", both stale — the read gate is now
 * {@code ImportRequestService#requireRead} (CEO/import/sales_manager unrestricted, sales scoped to
 * the deal's own owner), a materially wider and ownership-aware set than the old preview-only
 * {@code IR_ROLES} pair this Javadoc used to point at).
 *
 * <p>Carries no price of any kind, matching {@code sales.import_request_item}'s own decision: the
 * printed form has no price column, and a supplier cost here would leak what
 * {@code FactoryQuoteService}/{@code PricingDecisionService} keep to import and the CEO.
 */
public final class ImportRequestDtos {
    private ImportRequestDtos() {}

    public record ImportRequestDto(
        long id,
        long ticketId,
        String ticketCode,
        /**
         * V184: the grouping key was brand; it is now {@link #factoryId}. Retained as a DISPLAY
         * snapshot only — the distinct brand(s) of this factory's lines, joined for the header/
         * filename — and nullable, since a factory can carry lines spanning more than one brand and
         * this column cannot state them all as a single value the way it could when it WAS the key.
         */
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
        /**
         * The date this (deal, factory)'s WHOLE revision chain was first issued — carried forward,
         * never overwritten, across a revision's own {@link #issueDate} (REVIEW ROUND 1, S7). Null
         * while DRAFT. {@link #expectedArrivalFrom}/{@link #expectedArrivalTo} are derived from
         * THIS field, not {@link #issueDate} — see {@link ImportRequestService#withPageCount}.
         */
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
        // Per-factory progress (V184, GLA-100/S12-S17). Null while status=DRAFT; set to CONTACTED on
        // first issue and carried forward across a revision — see ImportRequestService#issue.
        String importStep,
        LocalDate importStepAt,
        Long importStepById,
        String importStepByName,
        String importStepNote,
        // Lead time / expected arrival (V184, owner decision 09-18 #2). Autofilled from the
        // factory's country (LeadTimeDefaults) on draft creation when a default exists, editable
        // thereafter, carried forward on revision, and required before ISSUE — see
        // ImportRequestService#issue.
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        /**
         * DERIVED, not stored: {@code issueDate + leadTimeMinDays} days. Null while DRAFT
         * (issueDate is null) or while either lead-time bound is unset — see {@link
         * ImportRequestService#withPageCount} for where this is computed.
         */
        LocalDate expectedArrivalFrom,
        /** DERIVED: {@code issueDate + leadTimeMaxDays} days. See {@link #expectedArrivalFrom}. */
        LocalDate expectedArrivalTo,
        // Order-email draft (owner decision 09-18 #3 §B). A DRAFT ONLY — nothing in this codebase
        // sends it; see ImportRequestService#buildEmailDraft / #markEmailSent.
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
        /**
         * REVIEW ROUND 3, item 3: a SNAPSHOT of the issuing user's email at the moment of issue —
         * see {@code sales.import_request.issued_by_email}'s own comment (V184) for why this is
         * carried separately from {@link #issuedByName} rather than resolved fresh on demand.
         */
        String issuedByEmail,
        Long supersededById,
        Instant createdAt,
        Instant updatedAt,
        Instant issuedAt,
        /**
         * Sheets this form prints on, from {@code ImportRequestRenderer.pagesRequired}. Served so a
         * client can warn before issuing that a form will run to a second sheet, rather than
         * discovering it at download.
         */
        int pageCount,
        List<ImportRequestItemDto> items
    ) {}

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
        /** REVIEW ROUND 2, S-A: the line's brand, separate from {@link #code} — see {@code
         * ImportRequestItemInput}'s own Javadoc. Feeds the order-email draft's per-line header. */
        String brand,
        /** The line's raw model text, separate from {@link #code} — see {@code
         * ImportRequestItemInput}'s own Javadoc. */
        String model
    ) {}
}
