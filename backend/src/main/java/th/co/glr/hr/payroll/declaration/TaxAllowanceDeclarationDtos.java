package th.co.glr.hr.payroll.declaration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;

/**
 * DTOs for the tax-allowance declaration staging workflow (PR A). See
 * {@code V105__tax_allowance_declaration.sql}'s header comment for the state machine these move
 * through, and {@link TaxAllowanceDeclarationService} for the endpoint-to-method mapping.
 *
 * <p>The declared amount/count fields are deliberately the SAME set as
 * {@code PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest} (minus {@code employeeId} on
 * the self-service request, per the {@code /me} idiom below), so promoting a declaration into
 * {@code hr.employee_tax_allowance} at Apply time is a straight field-for-field copy with no
 * translation step to get wrong.
 */
public final class TaxAllowanceDeclarationDtos {
    private TaxAllowanceDeclarationDtos() {
    }

    /**
     * Full declaration row, returned by every read endpoint. {@code allowances} reuses {@link
     * PayrollTaxAllowanceInput} rather than repeating its 21 fields a second time.
     */
    public record TaxAllowanceDeclarationDto(
        long declarationId,
        long employeeId,
        String employeeCode,
        String employeeName,
        int taxYear,
        int effectiveMonth,
        PayrollTaxAllowanceInput allowances,
        String documentReference,
        TaxAllowanceDeclarationStatus status,
        Long submittedById,
        OffsetDateTime submittedAt,
        boolean onBehalf,
        Long reviewedById,
        OffsetDateTime reviewedAt,
        String reviewerNote,
        // A FLAG, not a status -- see TaxAllowanceDeclarationStatus's javadoc. Both null together
        // ("approved, not yet applied") or all three non-null together ("applied"); never mixed
        // (chk_tad_applied_consistent).
        OffsetDateTime appliedAt,
        Long appliedById,
        Integer appliedEffectiveMonth,
        java.time.LocalDate expiresOn,
        OffsetDateTime expiredAt,
        OffsetDateTime reverifiedAt,
        Long reverifiedById,
        Long supersededById
    ) {}

    /**
     * Self-service submission body. Deliberately carries NO {@code employeeId} anywhere in this
     * record — copy of {@code PayrollService#ownPayslipPdf}'s idiom: the caller's own row is
     * resolved server-side from {@code actor.employeeId()}, never from anything the client sends,
     * so there is nothing here for a forged body to smuggle. An extra {@code employeeId} field in
     * a raw JSON request is simply not bound (Jackson ignores unknown properties by default) and
     * the row lands on the caller regardless — see
     * {@code TaxAllowanceDeclarationScopeIntegrationTest} for the test that pins this.
     */
    public record TaxAllowanceDeclarationSubmitRequest(
        @NotNull Integer taxYear,
        // ล.ย.01 ข้อ 2.2: null means "in force from January", same default upsertTaxAllowances uses.
        Integer effectiveMonth,
        @PositiveOrZero BigDecimal spouseAllowance,
        @PositiveOrZero BigDecimal childAllowance,
        @PositiveOrZero BigDecimal parentCareAllowance,
        @PositiveOrZero BigDecimal disabledCareAllowance,
        @PositiveOrZero BigDecimal maternityAllowance,
        @PositiveOrZero BigDecimal lifeInsuranceAllowance,
        @PositiveOrZero BigDecimal healthInsuranceAllowance,
        @PositiveOrZero BigDecimal parentHealthInsuranceAllowance,
        @PositiveOrZero BigDecimal rmfAllowance,
        @PositiveOrZero BigDecimal ssfAllowance,
        @PositiveOrZero BigDecimal pensionInsuranceAllowance,
        @PositiveOrZero BigDecimal thaiEsgAllowance,
        @PositiveOrZero BigDecimal homeLoanInterestAllowance,
        @PositiveOrZero BigDecimal educationDonation,
        @PositiveOrZero BigDecimal generalDonation,
        @PositiveOrZero BigDecimal politicalDonation,
        @PositiveOrZero Integer childCount,
        @PositiveOrZero Integer childCountDouble,
        @PositiveOrZero Integer disabledCareCount,
        Boolean disabilityCardHolder,
        @PositiveOrZero Integer parentCareCount,
        String documentReference
    ) {}

    /**
     * HR on-behalf create + auto-approve (decision #9), for staff who never log in. The ONE request
     * in this file that legitimately carries an {@code employeeId} — HR is declaring for someone
     * else, so there is no {@code actor.employeeId()} to fall back on.
     */
    public record TaxAllowanceOnBehalfRequest(
        @NotNull Long employeeId,
        @NotNull Integer taxYear,
        Integer effectiveMonth,
        @PositiveOrZero BigDecimal spouseAllowance,
        @PositiveOrZero BigDecimal childAllowance,
        @PositiveOrZero BigDecimal parentCareAllowance,
        @PositiveOrZero BigDecimal disabledCareAllowance,
        @PositiveOrZero BigDecimal maternityAllowance,
        @PositiveOrZero BigDecimal lifeInsuranceAllowance,
        @PositiveOrZero BigDecimal healthInsuranceAllowance,
        @PositiveOrZero BigDecimal parentHealthInsuranceAllowance,
        @PositiveOrZero BigDecimal rmfAllowance,
        @PositiveOrZero BigDecimal ssfAllowance,
        @PositiveOrZero BigDecimal pensionInsuranceAllowance,
        @PositiveOrZero BigDecimal thaiEsgAllowance,
        @PositiveOrZero BigDecimal homeLoanInterestAllowance,
        @PositiveOrZero BigDecimal educationDonation,
        @PositiveOrZero BigDecimal generalDonation,
        @PositiveOrZero BigDecimal politicalDonation,
        @PositiveOrZero Integer childCount,
        @PositiveOrZero Integer childCountDouble,
        @PositiveOrZero Integer disabledCareCount,
        Boolean disabilityCardHolder,
        @PositiveOrZero Integer parentCareCount,
        String documentReference
    ) {}

    /** Reject requires a reason (decision #6 / {@code chk_tad_rejected_has_reason}); approve does not. */
    public record TaxAllowanceReviewRequest(String reviewerNote) {}

    /**
     * Apply body. {@code effectiveMonth} is an optional override of the declaration's own {@code
     * effectiveMonth} — null means "use what was declared". Either way the resolved month is
     * checked against {@code findPeriodByMonth}: an already-{@code PROCESSED} month refuses with
     * 409, since re-running it would change a figure already filed on ภ.ง.ด.1.
     */
    public record TaxAllowanceApplyRequest(Integer effectiveMonth) {}

    public record MyTaxAllowanceDeclarationsResponse(int taxYear, List<TaxAllowanceDeclarationDto> items) {}

    public record TaxAllowanceDeclarationRegisterResponse(List<TaxAllowanceDeclarationDto> items) {}

    // ---- Caps metadata (GET /api/payroll/tax-allowances/caps) ---------------------------------

    public enum TaxAllowanceCapKind {
        FLAT, PER_HEAD, PERCENT_OF_INCOME, SHARED_GROUP
    }

    /**
     * One catalogue row. See {@link TaxAllowanceCapCatalog} for how every field is populated and
     * {@code TaxAllowanceCapCatalogTest} for how each is proven to correspond to
     * {@code PayrollCalculator}'s actual literals by driving the real calculator.
     */
    public record TaxAllowanceCapEntry(
        // Matches PayrollTaxAllowanceInput's field naming (e.g. "spouse", "child", "rmf"), plus
        // "child_double" and "personal" which have no allowance field of their own.
        String category,
        TaxAllowanceCapKind kind,
        // null, or "life_health" / "retirement" / "donation" — categories that share one ceiling.
        String groupId,
        // FLAT: the flat cap. PER_HEAD: the per-head amount. PERCENT_OF_INCOME with an absolute
        // ceiling (thai_esg/rmf/ssf/pension): that ceiling. PERCENT_OF_INCOME with none (donation,
        // a moving 10%-of-a-dynamic-base target): null.
        BigDecimal ownCap,
        // The shared ceiling this category's group is ALSO capped by, applied after summing every
        // group member's own-capped amount. Null when not in a shared group.
        BigDecimal groupCap,
        // PER_HEAD only: a hard ceiling on count x ownCap (parent_care = 120,000 = 4 heads). Null
        // when the law states no such total (child, child_double, disabled_care).
        BigDecimal maxTotal,
        // The %-of-income rate, when kind = PERCENT_OF_INCOME; else null.
        BigDecimal incomeRate,
        // Applied to the declared amount before capping (education_donation = 2, everything else 1).
        BigDecimal multiplier,
        // false only for "personal" — granted automatically, never declared (decision #1).
        boolean declarable
    ) {}

    public record TaxAllowanceCapsResponse(int taxYear, List<TaxAllowanceCapEntry> caps) {}

    // ---- Evidence attachments (hr.file_attachment, domain='tax_allowance_declaration') --------
    //
    // Reuses the generic hr.file_attachment table V33 created (see that migration's own
    // idx_file_attachment_domain_owner index, and V105's header comment noting it was left ready
    // for exactly this) rather than a dedicated table, since a declaration already has the single
    // surrogate BIGINT id (declaration_id) file_attachment.owner_id needs.

    /**
     * One evidence file. {@code deletedAt} is surfaced (not hidden) so HR's attachment list stays
     * an honest audit trail of what was ever uploaded — mirrors {@code
     * FactoryQuoteAttachmentDto}'s own tombstone visibility. A tombstoned file is never
     * downloadable (see {@code TaxAllowanceDeclarationService#getAttachmentForDownload}) even
     * though its row (and the file on disk) survive.
     */
    public record TaxAllowanceAttachmentDto(
        long attachmentId,
        long declarationId,
        String fileName,
        String mimeType,
        Long fileSize,
        Long uploadedBy,
        OffsetDateTime uploadedAt,
        OffsetDateTime deletedAt,
        Long deletedBy,
        String deleteReason
    ) {}

    /**
     * Resolved (attachment metadata, file path, storage state) triple — the download gate has
     * already run by the time this exists. {@code storageState} is one of {@code DATABASE} (bytes
     * in {@code hr.file_attachment_blob}), {@code DISK_LEGACY} (bytes may still be at {@code
     * filePath} on disk), or {@code MISSING} — V134 storage-durability fix. {@code
     * TaxAllowanceDeclarationService#getAttachmentForDownload} has ALREADY confirmed the bytes are
     * available before returning this (410 otherwise), so a caller holding one of these never needs
     * to re-check {@code storageState} for availability — only to pick the right byte source.
     */
    public record TaxAllowanceAttachmentDownload(TaxAllowanceAttachmentDto attachment, String filePath,
                                                  String storageState) {}

    /** Optional tombstone reason for {@code DELETE /tax-allowance-attachments/{id}}. */
    public record TaxAllowanceTombstoneRequest(String reason) {}
}
