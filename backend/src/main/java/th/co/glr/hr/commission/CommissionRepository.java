package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CommissionRepository {
    private static final String RECORD_SELECT = """
        SELECT cr.commission_id, cr.source_ticket_id, cr.sales_rep_id,
               NULLIF(TRIM(CONCAT_WS(' ', sr.first_name_th, sr.last_name_th)), '') AS sales_rep_name,
               cr.submitted_by_id, cr.kind, cr.status, cr.payroll_month,
               cr.actual_received, cr.commissionable_base, cr.weight_multiplier,
               cr.approved_by_id, cr.approved_at, cr.cancellation_of_id, cr.cancellation_reason,
               cr.manager_approved_by,
               NULLIF(TRIM(CONCAT_WS(' ', manager_approver.first_name_th, manager_approver.last_name_th)), '') AS manager_approved_by_name,
               cr.manager_approved_at,
               cr.ceo_approved_by,
               NULLIF(TRIM(CONCAT_WS(' ', ceo_approver.first_name_th, ceo_approver.last_name_th)), '') AS ceo_approved_by_name,
               cr.ceo_approved_at,
               cr.rejected_by_id,
               NULLIF(TRIM(CONCAT_WS(' ', rejected_by.first_name_th, rejected_by.last_name_th)), '') AS rejected_by_name,
               cr.rejected_at,
               cr.rejection_reason,
               cr.created_at AS record_created_at, cr.updated_at AS record_updated_at,
               cr.deal_payable_amount_snapshot, cr.deal_amount_mismatch,
               cr.manual_amount, cr.manual_reason,
               inv.invoice_id, inv.invoice_number, inv.invoice_date, inv.gross_amount,
               inv.bank_fees, inv.suspense_vat, inv.transport_fee, inv.cut_fee, inv.shortfall,
               inv.withholding_tax, inv.overpayment,
               inv.invoice_attachment_id, fa.file_name AS invoice_attachment_file_name,
               inv.created_at AS invoice_created_at, inv.updated_at AS invoice_updated_at
          FROM sales.commission_record cr
          -- LEFT JOIN (not JOIN): manual entries (kind ADJUSTMENT/MANAGER, V84) have invoice_id
          -- NULL -- an inner join here would silently exclude every manual commission row from
          -- findRecords/findById/findApprovedRecordsByMonth. mapRecord() below returns a null
          -- InvoiceDetails for these rows.
          LEFT JOIN sales.invoice_details inv ON inv.invoice_id = cr.invoice_id
          JOIN hr.employee sr ON sr.employee_id = cr.sales_rep_id
          LEFT JOIN hr.employee manager_approver ON manager_approver.employee_id = cr.manager_approved_by
          LEFT JOIN hr.employee ceo_approver ON ceo_approver.employee_id = cr.ceo_approved_by
          LEFT JOIN hr.employee rejected_by ON rejected_by.employee_id = cr.rejected_by_id
          LEFT JOIN hr.file_attachment fa ON fa.attachment_id = inv.invoice_attachment_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public CommissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CommissionRecord> findRecords(Long salesRepId, LocalDate payrollMonth) {
        return jdbc.query(
            RECORD_SELECT + """
             WHERE (:salesRepId::bigint IS NULL OR cr.sales_rep_id = :salesRepId)
               AND (:payrollMonth::date IS NULL OR cr.payroll_month = :payrollMonth)
             ORDER BY cr.payroll_month DESC, cr.created_at DESC, cr.commission_id DESC
            """,
            new MapSqlParameterSource()
                .addValue("salesRepId", salesRepId)
                .addValue("payrollMonth", payrollMonth),
            (rs, rowNum) -> mapRecord(rs));
    }

    public List<CommissionRecord> findApprovedRecordsByMonth(LocalDate payrollMonth) {
        return jdbc.query(
            RECORD_SELECT + """
             WHERE cr.payroll_month = :payrollMonth
               AND cr.status = 'APPROVED'
             ORDER BY sr.first_name_th, sr.last_name_th, cr.created_at
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> mapRecord(rs));
    }

    public Optional<CommissionRecord> findById(long id) {
        try {
            CommissionRecord record = jdbc.queryForObject(
                RECORD_SELECT + " WHERE cr.commission_id = :id",
                Map.of("id", id),
                (rs, rowNum) -> mapRecord(rs));
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<TierConfig> findTiers() {
        return jdbc.query("""
            SELECT tier_number, lower_bound, upper_bound, rate_percent, is_high_roller
              FROM sales.tier_config
             ORDER BY tier_number
            """,
            Map.of(),
            (rs, rowNum) -> new TierConfig(
                rs.getInt("tier_number"),
                rs.getBigDecimal("lower_bound"),
                rs.getBigDecimal("upper_bound"),
                rs.getBigDecimal("rate_percent"),
                rs.getBoolean("is_high_roller")
            ));
    }

    /**
     * Commission redesign calc-refine (2026-07-22): the exact, un-divided sum of {@code
     * actual_received &times; weight_multiplier} across every active (non-VOID, non-REJECTED)
     * commission record for the rep/month. This is real money multiplied by a small integer, so
     * the sum is exact -- no precision is lost here. The caller ({@link
     * CommissionCalculator#monthlyTierBase}) divides by VAT exactly once, at full precision,
     * instead of this repository summing each receipt's already-2dp-rounded {@code
     * commissionable_base} column (the old {@code sumActiveMonthlyBase}, which let per-receipt
     * rounding accumulate across many small receipts).
     */
    public BigDecimal sumActiveWeightedActualReceived(long salesRepId, LocalDate payrollMonth) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(actual_received * weight_multiplier), 0)
              FROM sales.commission_record
             WHERE sales_rep_id = :salesRepId
               AND payroll_month = :payrollMonth
               AND status NOT IN ('VOID', 'REJECTED')
            """,
            new MapSqlParameterSource()
                .addValue("salesRepId", salesRepId)
                .addValue("payrollMonth", payrollMonth),
            BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Commission redesign calc-refine: the UNWEIGHTED sum of {@code actual_received} -- real cash
     * actually received, with no 2x/3x tier-base weighting applied. Kept deliberately separate
     * from {@link #sumActiveWeightedActualReceived}: "real cash received" and "weighted tier
     * base" are two different numbers once any receipt is weighted, and a future team-commission
     * slice needs the unweighted one. Not currently wired into any response DTO — available here
     * for that slice and for tests that need to assert the two totals diverge.
     */
    public BigDecimal sumActiveActualReceived(long salesRepId, LocalDate payrollMonth) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(actual_received), 0)
              FROM sales.commission_record
             WHERE sales_rep_id = :salesRepId
               AND payroll_month = :payrollMonth
               AND status NOT IN ('VOID', 'REJECTED')
            """,
            new MapSqlParameterSource()
                .addValue("salesRepId", salesRepId)
                .addValue("payrollMonth", payrollMonth),
            BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public boolean hasActiveClawbackFor(long commissionId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1
                  FROM sales.commission_record
                 WHERE cancellation_of_id = :commissionId
                   AND kind = 'CLAWBACK'
                   AND status <> 'VOID'
            )
            """,
            Map.of("commissionId", commissionId),
            Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public List<Long> findSalesManagerApproverEmployeeIds() {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE e.is_active = TRUE
               AND d.source_code ILIKE 'SA%'
               AND p.name_th LIKE '%ผู้จัดการ%'
             ORDER BY e.employee_id
            """, Map.of(), (rs, rowNum) -> rs.getLong("employee_id"));
    }

    public List<Long> findCeoApproverEmployeeIds() {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE e.is_active = TRUE
               AND (d.source_code ILIKE 'MD%' OR d.source_code ILIKE 'MN%')
             ORDER BY e.employee_id
            """, Map.of(), (rs, rowNum) -> rs.getLong("employee_id"));
    }

    public long createInvoice(SubmitCommissionRequest request) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.invoice_details
                (invoice_number, invoice_date, gross_amount, bank_fees, suspense_vat,
                 transport_fee, cut_fee, shortfall, withholding_tax, overpayment)
            VALUES
                (:invoiceNumber, :invoiceDate, :grossAmount, :bankFees, :suspenseVat,
                 :transportFee, :cutFee, :shortfall, :withholdingTax, :overpayment)
            """,
            amountParams()
                .addValue("invoiceNumber", request.invoiceNumber().trim())
                .addValue("invoiceDate", request.invoiceDate())
                .addValue("grossAmount", money(request.grossAmount()))
                .addValue("bankFees", money(request.bankFees()))
                .addValue("suspenseVat", money(request.suspenseVat()))
                .addValue("transportFee", money(request.transportFee()))
                .addValue("cutFee", money(request.cutFee()))
                .addValue("shortfall", money(request.shortfall()))
                .addValue("withholdingTax", money(request.withholdingTax()))
                .addValue("overpayment", money(request.overpayment())),
            keyHolder,
            new String[]{"invoice_id"});
        return keyHolder.getKey().longValue();
    }

    public void attachInvoiceFile(long invoiceId, long attachmentId) {
        jdbc.update("""
            UPDATE sales.invoice_details
               SET invoice_attachment_id = :attachmentId,
                   updated_at = now()
             WHERE invoice_id = :invoiceId
            """, Map.of("invoiceId", invoiceId, "attachmentId", attachmentId));
    }

    public long createCommissionRecord(
        long invoiceId,
        Long sourceTicketId,
        long salesRepId,
        long submittedById,
        LocalDate payrollMonth,
        InvoiceCalculation calculation
    ) {
        return createCommissionRecord(invoiceId, sourceTicketId, salesRepId, submittedById,
            payrollMonth, calculation, null, false);
    }

    /**
     * @param dealPayableAmountSnapshot Step 9 cross-check snapshot of the linked deal's payable
     *                                  amount at submission time, or {@code null} when
     *                                  {@code sourceTicketId} is {@code null} (unlinked commission).
     * @param dealAmountMismatch        Step 9 flag — {@code true} when grossAmount diverged from the
     *                                  snapshot by more than the gate's threshold. Never blocks
     *                                  submission; surfaced for reviewers at approval time.
     */
    public long createCommissionRecord(
        long invoiceId,
        Long sourceTicketId,
        long salesRepId,
        long submittedById,
        LocalDate payrollMonth,
        InvoiceCalculation calculation,
        BigDecimal dealPayableAmountSnapshot,
        boolean dealAmountMismatch
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base,
                 deal_payable_amount_snapshot, deal_amount_mismatch)
            VALUES
                (:invoiceId, :sourceTicketId, :salesRepId, :submittedById, 'SALE', 'SUBMITTED',
                 :payrollMonth, :actualReceived, :commissionableBase,
                 :dealPayableAmountSnapshot, :dealAmountMismatch)
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("sourceTicketId", sourceTicketId)
                .addValue("salesRepId", salesRepId)
                .addValue("submittedById", submittedById)
                .addValue("payrollMonth", payrollMonth)
                .addValue("actualReceived", calculation.actualReceived())
                .addValue("commissionableBase", calculation.commissionableBase())
                .addValue("dealPayableAmountSnapshot", dealPayableAmountSnapshot)
                .addValue("dealAmountMismatch", dealAmountMismatch),
            keyHolder,
            new String[]{"commission_id"});
        return keyHolder.getKey().longValue();
    }

    /**
     * Commission redesign calc-refine: the clawback copies the original's {@code
     * weight_multiplier} (not the column default of 1). Without this, a clawback of a 2x-weighted
     * sale would only reverse 1x of the original's contribution to the monthly TIER BASE,
     * silently leaving half of the original's weighted credit in place after the "cancellation".
     */
    public long createClawback(CommissionRecord original, long submittedById, LocalDate payrollMonth, String reason) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base, weight_multiplier,
                 approved_by_id, approved_at, cancellation_of_id, cancellation_reason)
            VALUES
                (:invoiceId, :sourceTicketId, :salesRepId, :submittedById, 'CLAWBACK', 'APPROVED',
                 :payrollMonth, :actualReceived, :commissionableBase, :weightMultiplier,
                 :submittedById, now(), :cancellationOfId, :reason)
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", original.invoiceDetails().id())
                .addValue("sourceTicketId", original.sourceTicketId())
                .addValue("salesRepId", original.salesRepId())
                .addValue("submittedById", submittedById)
                .addValue("payrollMonth", payrollMonth)
                .addValue("actualReceived", original.actualReceived().abs().negate())
                .addValue("commissionableBase", original.commissionableBase().abs().negate())
                .addValue("weightMultiplier", original.weightMultiplier())
                .addValue("cancellationOfId", original.id())
                .addValue("reason", reason),
            keyHolder,
            new String[]{"commission_id"});
        return keyHolder.getKey().longValue();
    }

    /**
     * Manual commission entries (feat/commission-manual-adjustments, V84): a sales_manager/CEO
     * hand-typed amount for kind ADJUSTMENT / MANAGER / STOCK_BONUS / INCENTIVE -- never computed by {@link
     * CommissionCalculator}, no {@code invoice_id} (nullable since V84), no {@code
     * source_ticket_id}. {@code actual_received}/{@code commissionable_base} are stored as ZERO,
     * deliberately: those are the tier-calc columns, and manual entries must never bleed into
     * {@link #sumActiveWeightedActualReceived} or the monthly tier base -- {@code manual_amount}
     * is the sole source of truth, added on top of the tier commission only in {@link
     * CommissionService#payrollReadySummary}.
     *
     * <p>Created by a sales_manager lands {@code MANAGER_APPROVED} (still needs CEO sign-off,
     * reusing the existing {@code approve()}/{@code ceoApprove()} chain verbatim -- no separate
     * approval path is added). Created by the CEO lands {@code APPROVED} directly, mirroring how
     * {@link #managerApprove} and {@link #ceoApprove} each stamp their own approval columns.
     */
    public long createManualCommission(
        String kind,
        long salesRepId,
        long submittedById,
        LocalDate payrollMonth,
        BigDecimal manualAmount,
        String manualReason,
        boolean ceoCreated
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        String sql = ceoCreated
            ? """
                INSERT INTO sales.commission_record
                    (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                     payroll_month, actual_received, commissionable_base, manual_amount, manual_reason,
                     ceo_approved_by, ceo_approved_at, approved_by_id, approved_at)
                VALUES
                    (NULL, NULL, :salesRepId, :submittedById, :kind, 'APPROVED',
                     :payrollMonth, 0, 0, :manualAmount, :manualReason,
                     :submittedById, now(), :submittedById, now())
                """
            : """
                INSERT INTO sales.commission_record
                    (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                     payroll_month, actual_received, commissionable_base, manual_amount, manual_reason,
                     manager_approved_by, manager_approved_at, approved_by_id, approved_at)
                VALUES
                    (NULL, NULL, :salesRepId, :submittedById, :kind, 'MANAGER_APPROVED',
                     :payrollMonth, 0, 0, :manualAmount, :manualReason,
                     :submittedById, now(), :submittedById, now())
                """;
        jdbc.update(sql,
            new MapSqlParameterSource()
                .addValue("salesRepId", salesRepId)
                .addValue("submittedById", submittedById)
                .addValue("kind", kind)
                .addValue("payrollMonth", payrollMonth)
                .addValue("manualAmount", manualAmount)
                .addValue("manualReason", manualReason),
            keyHolder,
            new String[]{"commission_id"});
        return keyHolder.getKey().longValue();
    }

    /**
     * Slice A2: the sales-manager review step can now rewrite any invoice input, not just the
     * three original deduction fields — {@code grossAmount}/{@code bankFees}/{@code suspenseVat}
     * joined {@code transportFee}/{@code cutFee}/{@code shortfall}/{@code withholdingTax}/
     * {@code overpayment}. Every value here is already the resolved "new value or keep existing"
     * result (see {@code CommissionService#valueOrExisting}); this method just persists it.
     */
    public void updateDeductions(long invoiceId, BigDecimal grossAmount, BigDecimal bankFees, BigDecimal suspenseVat,
                                  BigDecimal transportFee, BigDecimal cutFee, BigDecimal shortfall,
                                  BigDecimal withholdingTax, BigDecimal overpayment) {
        jdbc.update("""
            UPDATE sales.invoice_details
               SET gross_amount = :grossAmount,
                   bank_fees = :bankFees,
                   suspense_vat = :suspenseVat,
                   transport_fee = :transportFee,
                   cut_fee = :cutFee,
                   shortfall = :shortfall,
                   withholding_tax = :withholdingTax,
                   overpayment = :overpayment,
                   updated_at = now()
             WHERE invoice_id = :invoiceId
            """,
            amountParams()
                .addValue("invoiceId", invoiceId)
                .addValue("grossAmount", money(grossAmount))
                .addValue("bankFees", money(bankFees))
                .addValue("suspenseVat", money(suspenseVat))
                .addValue("transportFee", money(transportFee))
                .addValue("cutFee", money(cutFee))
                .addValue("shortfall", money(shortfall))
                .addValue("withholdingTax", money(withholdingTax))
                .addValue("overpayment", money(overpayment)));
    }

    /**
     * Commission redesign calc-refine: sets a commission record's tier-base weight multiplier
     * (1/2/3). Separate statement from {@link #updateDeductions} because the multiplier lives on
     * {@code sales.commission_record}, not {@code sales.invoice_details}. Only reachable through
     * {@link CommissionService#updateDeductions} (sales-manager/CEO review path) -- sales has no
     * access to that endpoint at all, so no separate role check is needed here.
     */
    public void updateWeightMultiplier(long commissionId, int weightMultiplier) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET weight_multiplier = :weightMultiplier,
                   updated_at = now()
             WHERE commission_id = :commissionId
            """,
            Map.of("commissionId", commissionId, "weightMultiplier", weightMultiplier));
    }

    /**
     * Slice A2 duplicate guard for the accountant auto-create trigger ({@code
     * CommissionService#createFromDeal}): a deal that already has a live (non-VOID, non-REJECTED)
     * SALE commission must not get a second one, regardless of the invoice number used the second
     * time (the invoice_number UNIQUE constraint alone would not catch a genuine second invoice
     * number for the same deal).
     */
    public boolean hasActiveCommissionForTicket(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM sales.commission_record
                 WHERE source_ticket_id = :ticketId
                   AND kind = 'SALE'
                   AND status NOT IN ('VOID', 'REJECTED')
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public void updateCommissionAmountsForInvoice(long invoiceId, InvoiceCalculation calculation) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET actual_received = CASE WHEN kind = 'CLAWBACK' THEN :actualReceived * -1 ELSE :actualReceived END,
                   commissionable_base = CASE WHEN kind = 'CLAWBACK' THEN :commissionableBase * -1 ELSE :commissionableBase END,
                   updated_at = now()
             WHERE invoice_id = :invoiceId
               AND status NOT IN ('VOID', 'REJECTED')
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("actualReceived", calculation.actualReceived().abs())
                .addValue("commissionableBase", calculation.commissionableBase().abs()));
    }

    public void managerApprove(long commissionId, long actorId) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET status = 'MANAGER_APPROVED',
                   manager_approved_by = :actorId,
                   manager_approved_at = now(),
                   approved_by_id = :actorId,
                   approved_at = now(),
                   updated_at = now()
             WHERE commission_id = :commissionId
               AND status = 'SUBMITTED'
            """,
            Map.of("commissionId", commissionId, "actorId", actorId));
    }

    public void ceoApprove(long commissionId, long actorId) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET status = 'APPROVED',
                   ceo_approved_by = :actorId,
                   ceo_approved_at = now(),
                   approved_by_id = :actorId,
                   approved_at = now(),
                   updated_at = now()
             WHERE commission_id = :commissionId
               AND status = 'MANAGER_APPROVED'
            """,
            Map.of("commissionId", commissionId, "actorId", actorId));
    }

    public void managerReject(long commissionId, long actorId, String reason) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET status = 'REJECTED',
                   rejected_by_id = :actorId,
                   rejected_at = now(),
                   rejection_reason = :reason,
                   approved_by_id = :actorId,
                   approved_at = now(),
                   updated_at = now()
             WHERE commission_id = :commissionId
               AND status = 'SUBMITTED'
            """,
            new MapSqlParameterSource()
                .addValue("commissionId", commissionId)
                .addValue("actorId", actorId)
                .addValue("reason", clean(reason)));
    }

    public void ceoReject(long commissionId, long actorId, String reason) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET status = 'REJECTED',
                   rejected_by_id = :actorId,
                   rejected_at = now(),
                   rejection_reason = :reason,
                   approved_by_id = :actorId,
                   approved_at = now(),
                   updated_at = now()
             WHERE commission_id = :commissionId
               AND status = 'MANAGER_APPROVED'
            """,
            new MapSqlParameterSource()
                .addValue("commissionId", commissionId)
                .addValue("actorId", actorId)
                .addValue("reason", clean(reason)));
    }

    private MapSqlParameterSource amountParams() {
        return new MapSqlParameterSource();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private CommissionRecord mapRecord(ResultSet rs) throws SQLException {
        InvoiceDetails invoice = mapInvoiceDetails(rs);
        long sourceTicketRaw = rs.getLong("source_ticket_id");
        Long sourceTicketId = rs.wasNull() ? null : sourceTicketRaw;
        long approvedByRaw = rs.getLong("approved_by_id");
        Long approvedById = rs.wasNull() ? null : approvedByRaw;
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        Timestamp managerApprovedAt = rs.getTimestamp("manager_approved_at");
        Timestamp ceoApprovedAt = rs.getTimestamp("ceo_approved_at");
        Timestamp rejectedAt = rs.getTimestamp("rejected_at");
        long cancellationOfRaw = rs.getLong("cancellation_of_id");
        Long cancellationOfId = rs.wasNull() ? null : cancellationOfRaw;
        return new CommissionRecord(
            rs.getLong("commission_id"),
            invoice,
            sourceTicketId,
            rs.getLong("sales_rep_id"),
            rs.getString("sales_rep_name"),
            rs.getLong("submitted_by_id"),
            rs.getString("kind"),
            rs.getString("status"),
            rs.getObject("payroll_month", LocalDate.class),
            rs.getBigDecimal("actual_received"),
            rs.getBigDecimal("commissionable_base"),
            rs.getInt("weight_multiplier"),
            approvedById,
            approvedAt == null ? null : approvedAt.toInstant(),
            nullableLong(rs, "manager_approved_by"),
            rs.getString("manager_approved_by_name"),
            managerApprovedAt == null ? null : managerApprovedAt.toInstant(),
            nullableLong(rs, "ceo_approved_by"),
            rs.getString("ceo_approved_by_name"),
            ceoApprovedAt == null ? null : ceoApprovedAt.toInstant(),
            nullableLong(rs, "rejected_by_id"),
            rs.getString("rejected_by_name"),
            rejectedAt == null ? null : rejectedAt.toInstant(),
            rs.getString("rejection_reason"),
            cancellationOfId,
            rs.getString("cancellation_reason"),
            rs.getTimestamp("record_created_at").toInstant(),
            rs.getTimestamp("record_updated_at").toInstant(),
            rs.getBigDecimal("deal_payable_amount_snapshot"),
            rs.getBoolean("deal_amount_mismatch"),
            rs.getBigDecimal("manual_amount"),
            rs.getString("manual_reason")
        );
    }

    /**
     * Manual entries (kind ADJUSTMENT/MANAGER, V84) have no invoice -- the LEFT JOIN in {@code
     * RECORD_SELECT} yields NULLs across every {@code inv.*} column for those rows. Returns {@code
     * null} in that case rather than an {@link InvoiceDetails} full of nulls (which would NPE on
     * {@code invoice_created_at.toInstant()} regardless).
     */
    private InvoiceDetails mapInvoiceDetails(ResultSet rs) throws SQLException {
        long invoiceIdRaw = rs.getLong("invoice_id");
        if (rs.wasNull()) {
            return null;
        }
        return new InvoiceDetails(
            invoiceIdRaw,
            rs.getString("invoice_number"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getBigDecimal("gross_amount"),
            rs.getBigDecimal("bank_fees"),
            rs.getBigDecimal("suspense_vat"),
            rs.getBigDecimal("transport_fee"),
            rs.getBigDecimal("cut_fee"),
            rs.getBigDecimal("shortfall"),
            rs.getBigDecimal("withholding_tax"),
            rs.getBigDecimal("overpayment"),
            nullableLong(rs, "invoice_attachment_id"),
            rs.getString("invoice_attachment_file_name"),
            rs.getTimestamp("invoice_created_at").toInstant(),
            rs.getTimestamp("invoice_updated_at").toInstant()
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
