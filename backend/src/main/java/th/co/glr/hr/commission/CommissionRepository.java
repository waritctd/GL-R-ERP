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
               cr.actual_received, cr.commissionable_base,
               cr.approved_by_id, cr.approved_at, cr.cancellation_of_id, cr.cancellation_reason,
               cr.created_at AS record_created_at, cr.updated_at AS record_updated_at,
               inv.invoice_id, inv.invoice_number, inv.invoice_date, inv.gross_amount,
               inv.bank_fees, inv.suspense_vat, inv.transport_fee, inv.cut_fee, inv.shortfall,
               inv.created_at AS invoice_created_at, inv.updated_at AS invoice_updated_at
          FROM sales.commission_record cr
          JOIN sales.invoice_details inv ON inv.invoice_id = cr.invoice_id
          JOIN hr.employee sr ON sr.employee_id = cr.sales_rep_id
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

    public BigDecimal sumActiveMonthlyBase(long salesRepId, LocalDate payrollMonth) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(commissionable_base), 0)
              FROM sales.commission_record
             WHERE sales_rep_id = :salesRepId
               AND payroll_month = :payrollMonth
               AND status <> 'VOID'
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

    public long createInvoice(SubmitCommissionRequest request) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.invoice_details
                (invoice_number, invoice_date, gross_amount, bank_fees, suspense_vat,
                 transport_fee, cut_fee, shortfall)
            VALUES
                (:invoiceNumber, :invoiceDate, :grossAmount, :bankFees, :suspenseVat,
                 :transportFee, :cutFee, :shortfall)
            """,
            amountParams()
                .addValue("invoiceNumber", request.invoiceNumber().trim())
                .addValue("invoiceDate", request.invoiceDate())
                .addValue("grossAmount", money(request.grossAmount()))
                .addValue("bankFees", money(request.bankFees()))
                .addValue("suspenseVat", money(request.suspenseVat()))
                .addValue("transportFee", money(request.transportFee()))
                .addValue("cutFee", money(request.cutFee()))
                .addValue("shortfall", money(request.shortfall())),
            keyHolder,
            new String[]{"invoice_id"});
        return keyHolder.getKey().longValue();
    }

    public long createCommissionRecord(
        long invoiceId,
        Long sourceTicketId,
        long salesRepId,
        long submittedById,
        LocalDate payrollMonth,
        InvoiceCalculation calculation
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base)
            VALUES
                (:invoiceId, :sourceTicketId, :salesRepId, :submittedById, 'SALE', 'SUBMITTED',
                 :payrollMonth, :actualReceived, :commissionableBase)
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("sourceTicketId", sourceTicketId)
                .addValue("salesRepId", salesRepId)
                .addValue("submittedById", submittedById)
                .addValue("payrollMonth", payrollMonth)
                .addValue("actualReceived", calculation.actualReceived())
                .addValue("commissionableBase", calculation.commissionableBase()),
            keyHolder,
            new String[]{"commission_id"});
        return keyHolder.getKey().longValue();
    }

    public long createClawback(CommissionRecord original, long submittedById, LocalDate payrollMonth, String reason) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base, approved_by_id, approved_at,
                 cancellation_of_id, cancellation_reason)
            VALUES
                (:invoiceId, :sourceTicketId, :salesRepId, :submittedById, 'CLAWBACK', 'APPROVED',
                 :payrollMonth, :actualReceived, :commissionableBase, :submittedById, now(),
                 :cancellationOfId, :reason)
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", original.invoiceDetails().id())
                .addValue("sourceTicketId", original.sourceTicketId())
                .addValue("salesRepId", original.salesRepId())
                .addValue("submittedById", submittedById)
                .addValue("payrollMonth", payrollMonth)
                .addValue("actualReceived", original.actualReceived().abs().negate())
                .addValue("commissionableBase", original.commissionableBase().abs().negate())
                .addValue("cancellationOfId", original.id())
                .addValue("reason", reason),
            keyHolder,
            new String[]{"commission_id"});
        return keyHolder.getKey().longValue();
    }

    public void updateDeductions(long invoiceId, BigDecimal transportFee, BigDecimal cutFee, BigDecimal shortfall) {
        jdbc.update("""
            UPDATE sales.invoice_details
               SET transport_fee = :transportFee,
                   cut_fee = :cutFee,
                   shortfall = :shortfall,
                   updated_at = now()
             WHERE invoice_id = :invoiceId
            """,
            amountParams()
                .addValue("invoiceId", invoiceId)
                .addValue("transportFee", money(transportFee))
                .addValue("cutFee", money(cutFee))
                .addValue("shortfall", money(shortfall)));
    }

    public void updateCommissionAmountsForInvoice(long invoiceId, InvoiceCalculation calculation) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET actual_received = CASE WHEN kind = 'CLAWBACK' THEN :actualReceived * -1 ELSE :actualReceived END,
                   commissionable_base = CASE WHEN kind = 'CLAWBACK' THEN :commissionableBase * -1 ELSE :commissionableBase END,
                   updated_at = now()
             WHERE invoice_id = :invoiceId
               AND status <> 'VOID'
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("actualReceived", calculation.actualReceived().abs())
                .addValue("commissionableBase", calculation.commissionableBase().abs()));
    }

    public void approve(long commissionId, long actorId) {
        jdbc.update("""
            UPDATE sales.commission_record
               SET status = 'APPROVED',
                   approved_by_id = :actorId,
                   approved_at = now(),
                   updated_at = now()
             WHERE commission_id = :commissionId
            """,
            Map.of("commissionId", commissionId, "actorId", actorId));
    }

    private MapSqlParameterSource amountParams() {
        return new MapSqlParameterSource();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private CommissionRecord mapRecord(ResultSet rs) throws SQLException {
        InvoiceDetails invoice = new InvoiceDetails(
            rs.getLong("invoice_id"),
            rs.getString("invoice_number"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getBigDecimal("gross_amount"),
            rs.getBigDecimal("bank_fees"),
            rs.getBigDecimal("suspense_vat"),
            rs.getBigDecimal("transport_fee"),
            rs.getBigDecimal("cut_fee"),
            rs.getBigDecimal("shortfall"),
            rs.getTimestamp("invoice_created_at").toInstant(),
            rs.getTimestamp("invoice_updated_at").toInstant()
        );
        long sourceTicketRaw = rs.getLong("source_ticket_id");
        Long sourceTicketId = rs.wasNull() ? null : sourceTicketRaw;
        long approvedByRaw = rs.getLong("approved_by_id");
        Long approvedById = rs.wasNull() ? null : approvedByRaw;
        Timestamp approvedAt = rs.getTimestamp("approved_at");
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
            approvedById,
            approvedAt == null ? null : approvedAt.toInstant(),
            cancellationOfId,
            rs.getString("cancellation_reason"),
            rs.getTimestamp("record_created_at").toInstant(),
            rs.getTimestamp("record_updated_at").toInstant()
        );
    }
}
