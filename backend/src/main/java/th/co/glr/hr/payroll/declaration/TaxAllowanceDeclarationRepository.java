package th.co.glr.hr.payroll.declaration;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01AddressPayload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01Details;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;

/**
 * Persistence for {@code hr.tax_allowance_declaration} (V105). Deliberately writes NO SQL against
 * {@code hr.employee_tax_allowance} — the parent table is only ever touched by
 * {@code PayrollRepository#upsertTaxAllowances}/{@code #markTaxAllowanceVerified}/
 * {@code #setTaxAllowanceVerificationDeadline}, called from
 * {@link TaxAllowanceDeclarationService#apply}. That separation is the whole point of this table:
 * see {@code V105}'s header comment.
 *
 * <p>Every mutation here is a conditional {@code UPDATE ... WHERE declaration_id = :id AND status =
 * '...'} with a rowcount check in the caller, never a bare row load followed by a save — there is no
 * {@code @Version} anywhere in this repo (copy of the profile-request concurrency idiom).
 */
@Repository
public class TaxAllowanceDeclarationRepository {
    private static final String SELECT_COLUMNS = """
        d.declaration_id, d.employee_id, e.employee_code,
        COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
        d.tax_year, d.effective_month,
        d.spouse_allowance, d.child_allowance, d.parent_care_allowance, d.disabled_care_allowance,
        d.maternity_allowance, d.life_insurance_allowance, d.health_insurance_allowance,
        d.parent_health_insurance_allowance, d.rmf_allowance, d.ssf_allowance,
        d.pension_insurance_allowance, d.thai_esg_allowance, d.home_loan_interest_allowance,
        d.education_donation, d.general_donation, d.political_donation,
        d.child_count, d.child_count_double, d.disabled_care_count, d.disability_card_holder,
        d.parent_care_count, d.document_reference,
        d.status, d.submitted_by_id, d.submitted_at, d.on_behalf,
        d.reviewed_by_id, d.reviewed_at, d.reviewer_note,
        d.applied_at, d.applied_by_id, d.applied_effective_month,
        d.expires_on, d.expired_at, d.reverified_at, d.reverified_by_id, d.superseded_by_id,
        -- แบบ ล.ย.01 (V137): header block, ข้อ 1-2 สถานภาพ, the ข้อ 4/6 per-parent ticks,
        -- the ข้อ 3 second tier, ข้อ 9, and the two free-text slots.
        d.taxpayer_id, d.first_name_th AS declared_first_name, d.last_name_th AS declared_last_name,
        d.addr_building, d.addr_room_no, d.addr_floor, d.addr_village, d.addr_house_no, d.addr_moo,
        d.addr_soi, d.addr_junction, d.addr_road, d.addr_sub_district, d.addr_district,
        d.addr_province, d.addr_postal_code,
        d.marital_state, d.spousal_status, d.spouse_has_income,
        d.children_total, d.child_extra_allowance,
        d.own_father_supported, d.own_mother_supported,
        d.spouse_father_supported, d.spouse_mother_supported, d.spouse_parent_care_allowance,
        d.own_father_health_insured, d.own_mother_health_insured,
        d.spouse_father_health_insured, d.spouse_mother_health_insured,
        d.provident_fund_allowance, d.rmf_seller_name, d.other_donation_note
        """;
    private static final String FROM_JOIN = """
        FROM hr.tax_allowance_declaration d
        JOIN hr.employee e ON e.employee_id = d.employee_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final FileAttachmentBlobRepository blobs;

    @Autowired
    public TaxAllowanceDeclarationRepository(NamedParameterJdbcTemplate jdbc, FileAttachmentBlobRepository blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /** Legacy/test convenience overload -- see LeaveAttachmentRepository's own dual-constructor javadoc for why. */
    public TaxAllowanceDeclarationRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new FileAttachmentBlobRepository(jdbc));
    }

    public Optional<TaxAllowanceDeclarationDto> findById(long declarationId) {
        List<TaxAllowanceDeclarationDto> rows = jdbc.query(
            "SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE d.declaration_id = :id",
            Map.of("id", declarationId),
            this::mapRow);
        return rows.stream().findFirst();
    }

    /** Every declaration for one employee/tax-year, latest submission first — "current" is items.get(0). */
    public List<TaxAllowanceDeclarationDto> findForEmployee(long employeeId, int taxYear) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + FROM_JOIN
                + " WHERE d.employee_id = :employeeId AND d.tax_year = :taxYear"
                + " ORDER BY d.submitted_at DESC",
            Map.of("employeeId", employeeId, "taxYear", taxYear),
            this::mapRow);
    }

    /** HR/CEO register: every declaration, optionally filtered by tax year and/or status. */
    public List<TaxAllowanceDeclarationDto> findRegister(Integer taxYear, TaxAllowanceDeclarationStatus status) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (taxYear != null) {
            sql.append(" AND d.tax_year = :taxYear");
            params.addValue("taxYear", taxYear);
        }
        if (status != null) {
            sql.append(" AND d.status = :status");
            params.addValue("status", status.name());
        }
        sql.append(" ORDER BY e.employee_code, d.submitted_at DESC");
        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    /** True if this employee already has a PENDING declaration for this tax year (the partial unique index). */
    public boolean existsPending(long employeeId, int taxYear) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.tax_allowance_declaration
             WHERE employee_id = :employeeId AND tax_year = :taxYear AND status = 'PENDING'
            """,
            Map.of("employeeId", employeeId, "taxYear", taxYear), Integer.class);
        return count != null && count > 0;
    }

    /** Inserts a new PENDING declaration and returns its generated id. */
    public long insert(
        long employeeId, int taxYear, int effectiveMonth, PayrollTaxAllowanceInput allowances,
        String documentReference, long submittedById, boolean onBehalf, LorYor01Details form
    ) {
        LorYor01Details f = form == null ? LorYor01Details.empty() : form;
        LorYor01AddressPayload addr = f.address() == null
            ? new LorYor01AddressPayload(null, null, null, null, null, null, null, null, null,
                null, null, null, null)
            : f.address();
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.tax_allowance_declaration (
                employee_id, tax_year, effective_month,
                spouse_allowance, child_allowance, parent_care_allowance, disabled_care_allowance,
                maternity_allowance, life_insurance_allowance, health_insurance_allowance,
                parent_health_insurance_allowance, rmf_allowance, ssf_allowance,
                pension_insurance_allowance, thai_esg_allowance, home_loan_interest_allowance,
                education_donation, general_donation, political_donation,
                child_count, child_count_double, disabled_care_count, disability_card_holder,
                parent_care_count, document_reference,
                status, submitted_by_id, submitted_at, on_behalf,
                taxpayer_id, first_name_th, last_name_th,
                addr_building, addr_room_no, addr_floor, addr_village, addr_house_no, addr_moo,
                addr_soi, addr_junction, addr_road, addr_sub_district, addr_district,
                addr_province, addr_postal_code,
                marital_state, spousal_status, spouse_has_income,
                children_total, child_extra_allowance,
                own_father_supported, own_mother_supported,
                spouse_father_supported, spouse_mother_supported, spouse_parent_care_allowance,
                own_father_health_insured, own_mother_health_insured,
                spouse_father_health_insured, spouse_mother_health_insured,
                provident_fund_allowance, rmf_seller_name, other_donation_note
            ) VALUES (
                :employeeId, :taxYear, :effectiveMonth,
                :spouseAllowance, :childAllowance, :parentCareAllowance, :disabledCareAllowance,
                :maternityAllowance, :lifeInsuranceAllowance, :healthInsuranceAllowance,
                :parentHealthInsuranceAllowance, :rmfAllowance, :ssfAllowance,
                :pensionInsuranceAllowance, :thaiEsgAllowance, :homeLoanInterestAllowance,
                :educationDonation, :generalDonation, :politicalDonation,
                :childCount, :childCountDouble, :disabledCareCount, :disabilityCardHolder,
                :parentCareCount, :documentReference,
                'PENDING', :submittedById, now(), :onBehalf,
                :taxpayerId, :firstNameTh, :lastNameTh,
                :addrBuilding, :addrRoomNo, :addrFloor, :addrVillage, :addrHouseNo, :addrMoo,
                :addrSoi, :addrJunction, :addrRoad, :addrSubDistrict, :addrDistrict,
                :addrProvince, :addrPostalCode,
                :maritalState, :spousalStatus, :spouseHasIncome,
                :childrenTotal, :childExtraAllowance,
                :ownFatherSupported, :ownMotherSupported,
                :spouseFatherSupported, :spouseMotherSupported, :spouseParentCareAllowance,
                :ownFatherHealthInsured, :ownMotherHealthInsured,
                :spouseFatherHealthInsured, :spouseMotherHealthInsured,
                :providentFundAllowance, :rmfSellerName, :otherDonationNote
            )
            RETURNING declaration_id
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("taxYear", taxYear)
                .addValue("effectiveMonth", effectiveMonth)
                .addValue("spouseAllowance", money(allowances.spouseAllowance()))
                .addValue("childAllowance", money(allowances.childAllowance()))
                .addValue("parentCareAllowance", money(allowances.parentCareAllowance()))
                .addValue("disabledCareAllowance", money(allowances.disabledCareAllowance()))
                .addValue("maternityAllowance", money(allowances.maternityAllowance()))
                .addValue("lifeInsuranceAllowance", money(allowances.lifeInsuranceAllowance()))
                .addValue("healthInsuranceAllowance", money(allowances.healthInsuranceAllowance()))
                .addValue("parentHealthInsuranceAllowance", money(allowances.parentHealthInsuranceAllowance()))
                .addValue("rmfAllowance", money(allowances.rmfAllowance()))
                .addValue("ssfAllowance", money(allowances.ssfAllowance()))
                .addValue("pensionInsuranceAllowance", money(allowances.pensionInsuranceAllowance()))
                .addValue("thaiEsgAllowance", money(allowances.thaiEsgAllowance()))
                .addValue("homeLoanInterestAllowance", money(allowances.homeLoanInterestAllowance()))
                .addValue("educationDonation", money(allowances.educationDonation()))
                .addValue("generalDonation", money(allowances.generalDonation()))
                .addValue("politicalDonation", money(allowances.politicalDonation()))
                .addValue("childCount", allowances.childCount())
                .addValue("childCountDouble", allowances.childCountDouble())
                .addValue("disabledCareCount", allowances.disabledCareCount())
                .addValue("disabilityCardHolder", allowances.disabilityCardHolder())
                .addValue("parentCareCount", allowances.parentCareCount() == null ? 0 : allowances.parentCareCount())
                .addValue("documentReference", documentReference)
                .addValue("submittedById", submittedById)
                .addValue("onBehalf", onBehalf)
                .addValue("taxpayerId", f.taxpayerId())
                .addValue("firstNameTh", f.firstNameTh())
                .addValue("lastNameTh", f.lastNameTh())
                .addValue("addrBuilding", addr.building())
                .addValue("addrRoomNo", addr.roomNo())
                .addValue("addrFloor", addr.floor())
                .addValue("addrVillage", addr.village())
                .addValue("addrHouseNo", addr.houseNo())
                .addValue("addrMoo", addr.moo())
                .addValue("addrSoi", addr.soi())
                .addValue("addrJunction", addr.junction())
                .addValue("addrRoad", addr.road())
                .addValue("addrSubDistrict", addr.subDistrict())
                .addValue("addrDistrict", addr.district())
                .addValue("addrProvince", addr.province())
                .addValue("addrPostalCode", addr.postalCode())
                .addValue("maritalState", f.maritalState())
                .addValue("spousalStatus", f.spousalStatus())
                .addValue("spouseHasIncome", f.spouseHasIncome())
                .addValue("childrenTotal", f.childrenTotal())
                .addValue("childExtraAllowance", money(f.childExtraAllowance()))
                .addValue("ownFatherSupported", flag(f.ownFatherSupported()))
                .addValue("ownMotherSupported", flag(f.ownMotherSupported()))
                .addValue("spouseFatherSupported", flag(f.spouseFatherSupported()))
                .addValue("spouseMotherSupported", flag(f.spouseMotherSupported()))
                .addValue("spouseParentCareAllowance", money(f.spouseParentCareAllowance()))
                .addValue("ownFatherHealthInsured", flag(f.ownFatherHealthInsured()))
                .addValue("ownMotherHealthInsured", flag(f.ownMotherHealthInsured()))
                .addValue("spouseFatherHealthInsured", flag(f.spouseFatherHealthInsured()))
                .addValue("spouseMotherHealthInsured", flag(f.spouseMotherHealthInsured()))
                .addValue("providentFundAllowance", money(f.providentFundAllowance()))
                .addValue("rmfSellerName", f.rmfSellerName())
                .addValue("otherDonationNote", f.otherDonationNote()),
            Long.class);
        return id;
    }

    /** Withdraw own PENDING declaration. Caller checks employee ownership BEFORE calling (404, not 403, on a foreign row). */
    public int withdrawPending(long declarationId, long employeeId) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'WITHDRAWN'
             WHERE declaration_id = :id AND employee_id = :employeeId AND status = 'PENDING'
            """,
            Map.of("id", declarationId, "employeeId", employeeId));
    }

    /**
     * Administrative withdraw of ANY pending declaration for an employee/tax-year, used only by
     * {@code createOnBehalf} to clear the way before inserting an HR-authored row — otherwise an
     * employee-submitted PENDING row still sitting in the queue would collide with
     * {@code uq_tad_one_pending_per_employee_year} and the insert would fail with a raw constraint
     * violation instead of a clean outcome.
     */
    public int withdrawAnyPending(long employeeId, int taxYear) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'WITHDRAWN'
             WHERE employee_id = :employeeId AND tax_year = :taxYear AND status = 'PENDING'
            """,
            Map.of("employeeId", employeeId, "taxYear", taxYear));
    }

    /**
     * Marks every OTHER APPROVED declaration for this employee/tax-year as SUPERSEDED by the given
     * (newly-approved) id. MUST run before the conditional approve UPDATE below, in the same
     * transaction — {@code uq_tad_one_approved_per_employee_year} is not deferrable.
     */
    public int supersedeApproved(long employeeId, int taxYear, long supersededById) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'SUPERSEDED', superseded_by_id = :supersededById
             WHERE employee_id = :employeeId AND tax_year = :taxYear AND status = 'APPROVED'
               AND declaration_id <> :supersededById
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("taxYear", taxYear)
                .addValue("supersededById", supersededById));
    }

    /** Conditional PENDING -> APPROVED. Returns 0 if the row was not PENDING (already decided, or missing). */
    public int approve(long declarationId, long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'APPROVED', reviewed_by_id = :reviewedById, reviewed_at = now(),
                   reviewer_note = :reviewerNote
             WHERE declaration_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("reviewedById", reviewedById)
                .addValue("reviewerNote", reviewerNote));
    }

    /** Conditional PENDING -> REJECTED. {@code reviewerNote} must be non-blank (checked by the service, enforced by chk_tad_rejected_has_reason). */
    public int reject(long declarationId, long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'REJECTED', reviewed_by_id = :reviewedById, reviewed_at = now(),
                   reviewer_note = :reviewerNote
             WHERE declaration_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("reviewedById", reviewedById)
                .addValue("reviewerNote", reviewerNote));
    }

    /**
     * Conditional flag-the-declaration-applied step. Deliberately runs BEFORE the
     * {@code hr.employee_tax_allowance} promotion in {@link TaxAllowanceDeclarationService#apply}:
     * the {@code applied_at IS NULL} guard in the WHERE clause is what makes a concurrent double-apply
     * 409 on the SECOND caller before it ever reaches the allowance table, with no {@code @Version}
     * needed.
     *
     * <p>{@code expiresOn} is also set here (2026-08-01, the yearly-expiry PR) — the SAME value
     * {@code TaxAllowanceDeclarationService#apply} passes to the parent table's {@code
     * setTaxAllowanceVerificationDeadline}, so the two never drift apart. This is what {@code
     * idx_tad_expiry_sweep} (V105) exists for: {@link #findExpirySweepCandidates} reads this column
     * directly rather than re-deriving a deadline from the parent table.
     */
    public int markApplied(long declarationId, long appliedById, int appliedEffectiveMonth, LocalDate expiresOn) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET applied_at = now(), applied_by_id = :appliedById,
                   applied_effective_month = :appliedEffectiveMonth,
                   expires_on = :expiresOn
             WHERE declaration_id = :id AND status = 'APPROVED' AND applied_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("appliedById", appliedById)
                .addValue("appliedEffectiveMonth", appliedEffectiveMonth)
                .addValue("expiresOn", expiresOn));
    }

    // ---- Yearly expiry (2026-08-01) -----------------------------------------------------------
    //
    // See TaxAllowanceDeclarationService#expireOverdueVerifications for the full sweep and
    // TaxAllowanceExpiryWorker for the @Scheduled trigger. THE trap (plan doc): a dated row on the
    // PARENT table (hr.employee_tax_allowance) must have EVERY row for the employee/tax-year
    // expired at once, via PayrollRepository#expireTaxAllowanceVerification -- never just the row
    // this declaration happens to reference -- or #findTaxAllowancesByEmployee's DISTINCT ON
    // ... ORDER BY effective_month DESC silently falls back to an older still-VERIFIED row.

    /** One row eligible for the expiry sweep: applied, still APPROVED, past its expires_on date. */
    public record ExpirySweepCandidate(long declarationId, long employeeId, int taxYear) {}

    public List<ExpirySweepCandidate> findExpirySweepCandidates(LocalDate asOf) {
        return jdbc.query("""
            SELECT declaration_id, employee_id, tax_year
              FROM hr.tax_allowance_declaration
             WHERE status = 'APPROVED' AND applied_at IS NOT NULL
               AND expires_on IS NOT NULL AND expires_on < :asOf
            """,
            Map.of("asOf", asOf),
            (rs, rowNum) -> new ExpirySweepCandidate(
                rs.getLong("declaration_id"), rs.getLong("employee_id"), rs.getInt("tax_year")));
    }

    /**
     * Conditional APPROVED -> EXPIRED. Guarded by the same {@code status = 'APPROVED' AND
     * applied_at IS NOT NULL} shape {@link #findExpirySweepCandidates} selects on, so a second sweep
     * run over the same row matches zero rows instead of re-stamping {@code expired_at} — the
     * idempotency the sweep needs with no extra bookkeeping.
     */
    public int expireApplied(long declarationId) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'EXPIRED', expired_at = now()
             WHERE declaration_id = :id AND status = 'APPROVED' AND applied_at IS NOT NULL
            """,
            Map.of("id", declarationId));
    }

    /**
     * Conditional EXPIRED -> APPROVED (the reverify mirror). {@code expired_at} is cleared back to
     * NULL in the SAME statement -- required by {@code chk_tad_expired_consistent}, which demands
     * {@code (status = 'EXPIRED') = (expired_at IS NOT NULL)} on every row, including this one the
     * moment it stops being EXPIRED.
     */
    public int reverify(long declarationId, long reverifiedById, LocalDate newExpiresOn) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'APPROVED', reverified_at = now(), reverified_by_id = :reverifiedById,
                   expired_at = NULL, expires_on = :newExpiresOn
             WHERE declaration_id = :id AND status = 'EXPIRED'
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("reverifiedById", reverifiedById)
                .addValue("newExpiresOn", newExpiresOn));
    }

    // ---- Evidence attachments (hr.file_attachment, domain='tax_allowance_declaration') --------
    //
    // The download gate itself lives ENTIRELY in the service
    // (TaxAllowanceDeclarationService#requireOwnerOrHr, re-resolved from the parent declaration on
    // every call) -- this repository only ever does the plain CRUD against hr.file_attachment,
    // exactly like FactoryQuoteRepository's own attachment methods it is modelled on.

    private static final String ATTACHMENT_DOMAIN = "tax_allowance_declaration";

    /**
     * V134 storage-durability fix: {@code filePath} here is the bare correlation key {@code
     * FileStorageService#storeInDatabase} computed, and {@code content} is inserted into {@code
     * hr.file_attachment_blob} in the SAME transaction as the {@code hr.file_attachment} insert
     * below ({@code TaxAllowanceDeclarationService#uploadAttachment} is {@code @Transactional}),
     * flipping {@code storage_state} straight to {@code DATABASE}.
     */
    public TaxAllowanceAttachmentDto saveAttachmentWithContent(
        long declarationId, String fileName, String filePath, String mimeType, Long fileSize, long uploadedBy,
        String sectionKey, byte[] content
    ) {
        TaxAllowanceAttachmentDto saved =
            saveAttachment(declarationId, fileName, filePath, mimeType, fileSize, uploadedBy, sectionKey);
        blobs.saveContent(saved.attachmentId(), content);
        return saved;
    }

    public TaxAllowanceAttachmentDto saveAttachment(
        long declarationId, String fileName, String filePath, String mimeType, Long fileSize, long uploadedBy,
        String sectionKey
    ) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by, section_key)
            VALUES
                (:domain, :declarationId, :fileName, :filePath, :mimeType, :fileSize, :uploadedBy, :sectionKey)
            """,
            new MapSqlParameterSource()
                .addValue("domain", ATTACHMENT_DOMAIN)
                .addValue("declarationId", declarationId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("uploadedBy", uploadedBy)
                .addValue("sectionKey", sectionKey),
            key, new String[]{"attachment_id"});
        return findAttachment(key.getKey().longValue()).orElseThrow();
    }

    public Optional<TaxAllowanceAttachmentDto> findAttachment(long attachmentId) {
        List<TaxAllowanceAttachmentDto> rows = jdbc.query("""
            SELECT attachment_id, owner_id AS declaration_id, file_name, mime_type, file_size,
                   uploaded_by, uploaded_at, deleted_at, deleted_by, delete_reason, section_key
              FROM hr.file_attachment
             WHERE attachment_id = :attachmentId AND domain = :domain
            """,
            Map.of("attachmentId", attachmentId, "domain", ATTACHMENT_DOMAIN),
            this::mapAttachment);
        return rows.stream().findFirst();
    }

    /** Every evidence file ever uploaded for a declaration, including tombstoned ones (audit trail). */
    public List<TaxAllowanceAttachmentDto> findAttachments(long declarationId) {
        return jdbc.query("""
            SELECT attachment_id, owner_id AS declaration_id, file_name, mime_type, file_size,
                   uploaded_by, uploaded_at, deleted_at, deleted_by, delete_reason, section_key
              FROM hr.file_attachment
             WHERE domain = :domain AND owner_id = :declarationId
             ORDER BY uploaded_at DESC, attachment_id DESC
            """,
            Map.of("domain", ATTACHMENT_DOMAIN, "declarationId", declarationId),
            this::mapAttachment);
    }

    /** V134: {@code (filePath, storageState)} pair for the download path's availability check. */
    public Optional<AttachmentFileLocation> findAttachmentFileLocation(long attachmentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT file_path, storage_state
                  FROM hr.file_attachment
                 WHERE attachment_id = :attachmentId AND domain = :domain
                """,
                Map.of("attachmentId", attachmentId, "domain", ATTACHMENT_DOMAIN),
                (rs, rowNum) -> new AttachmentFileLocation(rs.getString("file_path"), rs.getString("storage_state"))));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record AttachmentFileLocation(String filePath, String storageState) {
    }

    /**
     * Audited tombstone -- keeps the row and the file on disk, only records who deleted it, when,
     * and why. Guarded by {@code deleted_at IS NULL} so a second call cannot overwrite an existing
     * tombstone's {@code deleted_by}/{@code delete_reason}.
     */
    public int tombstoneAttachment(long attachmentId, long deletedBy, String reason) {
        return jdbc.update("""
            UPDATE hr.file_attachment
               SET deleted_at = now(), deleted_by = :deletedBy, delete_reason = :reason
             WHERE attachment_id = :attachmentId AND domain = :domain AND deleted_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("attachmentId", attachmentId)
                .addValue("deletedBy", deletedBy)
                .addValue("reason", reason)
                .addValue("domain", ATTACHMENT_DOMAIN));
    }

    private TaxAllowanceAttachmentDto mapAttachment(ResultSet rs, int rowNum) throws SQLException {
        return new TaxAllowanceAttachmentDto(
            rs.getLong("attachment_id"),
            rs.getLong("declaration_id"),
            rs.getString("file_name"),
            rs.getString("mime_type"),
            (Long) rs.getObject("file_size"),
            nullableLong(rs, "uploaded_by"),
            rs.getObject("uploaded_at", OffsetDateTime.class),
            rs.getObject("deleted_at", OffsetDateTime.class),
            nullableLong(rs, "deleted_by"),
            rs.getString("delete_reason"),
            rs.getString("section_key"));
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** A null tick is FALSE in the database: the ข้อ 4/6 boxes are NOT NULL DEFAULT FALSE (V137). */
    private static boolean flag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private TaxAllowanceDeclarationDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        PayrollTaxAllowanceInput allowances = new PayrollTaxAllowanceInput(
            rs.getBigDecimal("spouse_allowance"),
            rs.getBigDecimal("child_allowance"),
            rs.getBigDecimal("parent_care_allowance"),
            rs.getBigDecimal("disabled_care_allowance"),
            rs.getBigDecimal("maternity_allowance"),
            rs.getBigDecimal("life_insurance_allowance"),
            rs.getBigDecimal("health_insurance_allowance"),
            rs.getBigDecimal("parent_health_insurance_allowance"),
            rs.getBigDecimal("rmf_allowance"),
            rs.getBigDecimal("ssf_allowance"),
            rs.getBigDecimal("pension_insurance_allowance"),
            rs.getBigDecimal("thai_esg_allowance"),
            rs.getBigDecimal("home_loan_interest_allowance"),
            rs.getBigDecimal("education_donation"),
            rs.getBigDecimal("general_donation"),
            rs.getBigDecimal("political_donation"),
            rs.getInt("child_count"),
            rs.getInt("child_count_double"),
            rs.getInt("disabled_care_count"),
            rs.getBoolean("disability_card_holder"),
            rs.getInt("parent_care_count")
        );
        LorYor01Details lorYor01 = new LorYor01Details(
            rs.getString("taxpayer_id"),
            rs.getString("declared_first_name"),
            rs.getString("declared_last_name"),
            new LorYor01AddressPayload(
                rs.getString("addr_building"), rs.getString("addr_room_no"),
                rs.getString("addr_floor"), rs.getString("addr_village"),
                rs.getString("addr_house_no"), rs.getString("addr_moo"),
                rs.getString("addr_soi"), rs.getString("addr_junction"),
                rs.getString("addr_road"), rs.getString("addr_sub_district"),
                rs.getString("addr_district"), rs.getString("addr_province"),
                rs.getString("addr_postal_code")),
            rs.getString("marital_state"),
            rs.getString("spousal_status"),
            // getObject, not getBoolean: these three are genuinely nullable and getBoolean would
            // turn "not answered" into a confident false on the form.
            (Boolean) rs.getObject("spouse_has_income"),
            (Integer) rs.getObject("children_total"),
            rs.getBigDecimal("child_extra_allowance"),
            rs.getBoolean("own_father_supported"),
            rs.getBoolean("own_mother_supported"),
            rs.getBoolean("spouse_father_supported"),
            rs.getBoolean("spouse_mother_supported"),
            rs.getBigDecimal("spouse_parent_care_allowance"),
            rs.getBoolean("own_father_health_insured"),
            rs.getBoolean("own_mother_health_insured"),
            rs.getBoolean("spouse_father_health_insured"),
            rs.getBoolean("spouse_mother_health_insured"),
            rs.getBigDecimal("provident_fund_allowance"),
            rs.getString("rmf_seller_name"),
            rs.getString("other_donation_note"));
        return new TaxAllowanceDeclarationDto(
            rs.getLong("declaration_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getInt("tax_year"),
            rs.getInt("effective_month"),
            allowances,
            rs.getString("document_reference"),
            TaxAllowanceDeclarationStatus.valueOf(rs.getString("status")),
            nullableLong(rs, "submitted_by_id"),
            rs.getObject("submitted_at", OffsetDateTime.class),
            rs.getBoolean("on_behalf"),
            nullableLong(rs, "reviewed_by_id"),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("applied_at", OffsetDateTime.class),
            nullableLong(rs, "applied_by_id"),
            (Integer) rs.getObject("applied_effective_month"),
            rs.getObject("expires_on", LocalDate.class),
            rs.getObject("expired_at", OffsetDateTime.class),
            rs.getObject("reverified_at", OffsetDateTime.class),
            nullableLong(rs, "reverified_by_id"),
            nullableLong(rs, "superseded_by_id"),
            lorYor01
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
