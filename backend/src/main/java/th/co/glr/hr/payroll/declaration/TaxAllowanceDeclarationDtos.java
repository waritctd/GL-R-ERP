package th.co.glr.hr.payroll.declaration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;

/** DTOs for the tax-allowance declaration staging workflow (PR A). See V105__tax_allowance_declaration.sql's
 * header comment for the state machine these move through, and TaxAllowanceDeclarationService for the
 * endpoint-to-method mapping. */
public final class TaxAllowanceDeclarationDtos {
    private TaxAllowanceDeclarationDtos() {
    }

    /** Full declaration row, returned by every read endpoint. allowances reuses PayrollTaxAllowanceInput rather
     * than repeating its 21 fields a second time. */
    public record TaxAllowanceDeclarationDto(
        long declarationId,
        long employeeId,
        String employeeCode,
        String employeeName,
        int taxYear,
        /** Always 1 on anything written since the whole-year ruling (2026-08-31) — see
         * TaxAllowanceDeclarationService#WHOLE_YEAR_EFFECTIVE_MONTH. Kept on the read DTO because pre-ruling rows
         * still hold months 2-12 and the register must show them honestly. */
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
        // A FLAG, not a status -- see TaxAllowanceDeclarationStatus's javadoc. Both null together ("approved, not yet
        // applied") or all three non-null together ("applied"); never mixed (chk_tad_applied_consistent).
        OffsetDateTime appliedAt,
        Long appliedById,
        Integer appliedEffectiveMonth,
        java.time.LocalDate expiresOn,
        OffsetDateTime expiredAt,
        OffsetDateTime reverifiedAt,
        Long reverifiedById,
        Long supersededById,
        /** แบบ ล.ย.01 detail (V137). Never null on a read — empty() when the row predates the form. */
        LorYor01Details lorYor01
    ) {}

    /** Self-service submission body. Deliberately carries NO employeeId anywhere in this record — copy of
     * PayrollService#ownPayslipPdf's idiom: the caller's own row is resolved server-side from actor.employeeId(),
     * never from anything the client sends, so there is nothing here for a forged body to smuggle. */
    /** The parts of แบบ ล.ย.01 that are not allowance amounts: the header block, ข้อ 1-2 สถานภาพ, the per-parent
     * ticks in ข้อ 4/ข้อ 6, and the two free-text slots. Carried as ONE nested component on the submit requests
     * rather than ~20 more flat ones. */
    public record LorYor01Details(
        // header — ส่วนหัว
        String taxpayerId,
        String firstNameTh,
        String lastNameTh,
        LorYor01AddressPayload address,
        // ข้อ 1 / ข้อ 2
        String maritalState,
        String spousalStatus,
        Boolean spouseHasIncome,
        // ข้อ 3
        @PositiveOrZero Integer childrenTotal,
        @PositiveOrZero BigDecimal childExtraAllowance,
        // ข้อ 4
        Boolean ownFatherSupported,
        Boolean ownMotherSupported,
        Boolean spouseFatherSupported,
        Boolean spouseMotherSupported,
        @PositiveOrZero BigDecimal spouseParentCareAllowance,
        // ข้อ 6
        Boolean ownFatherHealthInsured,
        Boolean ownMotherHealthInsured,
        Boolean spouseFatherHealthInsured,
        Boolean spouseMotherHealthInsured,
        // ข้อ 9
        @PositiveOrZero BigDecimal providentFundAllowance,
        // ข้อ 10 / ข้อ 15 free text
        String rmfSellerName,
        String otherDonationNote
    ) {
        public static LorYor01Details empty() {
            return new LorYor01Details(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** ที่อยู่ as ล.ย.01 asks for it — 13 slots, of which hr.employee_address can hold 8 before V137. */
    public record LorYor01AddressPayload(
        String building, String roomNo, String floor, String village,
        String houseNo, String moo, String soi, String junction, String road,
        String subDistrict, String district, String province, String postalCode
    ) {}

    public record TaxAllowanceDeclarationSubmitRequest(
        @NotNull Integer taxYear,
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
        String documentReference,
        LorYor01Details lorYor01
    ) {}

    /** HR on-behalf create + auto-approve (decision #9), for staff who never log in. The ONE request in this file
     * that legitimately carries an employeeId — HR is declaring for someone else, so there is no
     * actor.employeeId() to fall back on. */
    public record TaxAllowanceOnBehalfRequest(
        @NotNull Long employeeId,
        @NotNull Integer taxYear,
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
        String documentReference,
        LorYor01Details lorYor01
    ) {}

    /** Reject requires a reason (decision #6 / {@code chk_tad_rejected_has_reason}); approve does not. */
    public record TaxAllowanceReviewRequest(String reviewerNote) {}

    /** What the ล.ย.01 header block is SEEDED with when the employee opens a fillable form — owner decision #4's
     * read half, the counterpart to the write-back approve performs. It rides on the /me envelope and NEVER on
     * TaxAllowanceDeclarationDto. */
    public record LorYor01HeaderPrefill(
        String taxpayerId,
        String firstNameTh,
        String lastNameTh,
        String maritalState,
        LorYor01AddressPayload address
    ) {
        public static LorYor01HeaderPrefill empty() {
            return new LorYor01HeaderPrefill(null, null, null, null,
                new LorYor01AddressPayload(null, null, null, null, null, null, null, null, null,
                    null, null, null, null));
        }
    }

    /** headerPrefill is never null — LorYor01HeaderPrefill#empty() when the employee has no master data to offer,
     * so the client never has to branch on its absence. */
    public record MyTaxAllowanceDeclarationsResponse(
        int taxYear,
        List<TaxAllowanceDeclarationDto> items,
        LorYor01HeaderPrefill headerPrefill
    ) {}

    public record TaxAllowanceDeclarationRegisterResponse(List<TaxAllowanceDeclarationDto> items) {}

    // ---- Caps metadata (GET /api/payroll/tax-allowances/caps) ---------------------------------

    public enum TaxAllowanceCapKind {
        FLAT, PER_HEAD, PERCENT_OF_INCOME, SHARED_GROUP
    }

    /** One catalogue row. See TaxAllowanceCapCatalog for how every field is populated and
     * TaxAllowanceCapCatalogTest for how each is proven to correspond to PayrollCalculator's actual literals by
     * driving the real calculator. */
    public record TaxAllowanceCapEntry(
        // Matches PayrollTaxAllowanceInput's field naming (e.g. "spouse", "child", "rmf"), plus "child_double" and
        // "personal" which have no allowance field of their own.
        String category,
        TaxAllowanceCapKind kind,
        // null, or "life_health" / "retirement" / "donation" — categories that share one ceiling.
        String groupId,
        // FLAT: the flat cap. PER_HEAD: the per-head amount. PERCENT_OF_INCOME with an absolute ceiling
        // (thai_esg/rmf/ssf/pension): that ceiling. PERCENT_OF_INCOME with none (donation, a moving
        // 10%-of-a-dynamic-base target): null.
        BigDecimal ownCap,
        // The shared ceiling this category's group is ALSO capped by, applied after summing every group member's
        // own-capped amount. Null when not in a shared group.
        BigDecimal groupCap,
        // PER_HEAD only: a hard ceiling on count x ownCap (parent_care = 120,000 = 4 heads). Null when the law states
        // no such total (child, child_double, disabled_care).
        BigDecimal maxTotal,
        // The %-of-income rate, when kind = PERCENT_OF_INCOME; else null.
        BigDecimal incomeRate,
        // Applied to the declared amount before capping (education_donation = 2, everything else 1).
        BigDecimal multiplier,
        // false only for "personal" — granted automatically, never declared (decision #1).
        boolean declarable
    ) {}

    public record TaxAllowanceCapsResponse(int taxYear, List<TaxAllowanceCapEntry> caps) {}

    // Evidence attachments -------- Reuses the generic hr.file_attachment table V33 created rather than a
    // dedicated table, since a declaration already has the single surrogate BIGINT id file_attachment.owner_id
    // needs.

    /** Exposes attachment tombstones for the audit trail; deleted evidence remains non-downloadable. */
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
        String deleteReason,
        // V135: which of TAX_ALLOWANCE_GROUPS' five section keys this evidence belongs to. Null for every pre-V135 row
        // -- the frontend shows those as "general/uncategorized", never drops them.
        String sectionKey
    ) {}

    /** Resolved (attachment metadata, file path, storage state) triple — the download gate has already run by the
     * time this exists. storageState is one of DATABASE (bytes in hr.file_attachment_blob), DISK_LEGACY (bytes may
     * still be at filePath on disk), or MISSING — V134 storage-durability fix. */
    public record TaxAllowanceAttachmentDownload(TaxAllowanceAttachmentDto attachment, String filePath,
                                                  String storageState) {}

    /** Optional tombstone reason for {@code DELETE /tax-allowance-attachments/{id}}. */
    public record TaxAllowanceTombstoneRequest(String reason) {}
}
