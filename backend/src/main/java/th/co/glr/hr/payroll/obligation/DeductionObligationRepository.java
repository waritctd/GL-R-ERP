package th.co.glr.hr.payroll.obligation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationUpdateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.RemittanceLedgerRowDto;

/**
 * Persistence for {@code hr.deduction_obligation} + {@code hr.deduction_obligation_remittance}
 * (V106, issue #373). Every mutation is a conditional {@code UPDATE ... WHERE obligation_id = :id
 * AND status = '...'} with a rowcount check in the caller, the same idiom
 * {@code TaxAllowanceDeclarationRepository} uses — there is no {@code @Version} column anywhere in
 * this repo.
 */
@Repository
public class DeductionObligationRepository {
    private static final String SELECT_COLUMNS = """
        o.obligation_id, o.employee_id, e.employee_code,
        COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
        o.kind, o.monthly_instructed_amount, o.instructed_total, o.authority_reference, o.start_date,
        o.status, o.completed_at, o.completion_acknowledged_by_id, o.completion_acknowledged_at,
        o.override_continue_past_total, o.override_by_id, o.override_at, o.override_reason,
        o.notes, o.created_at, o.updated_at
        """;
    private static final String FROM_JOIN = """
        FROM hr.deduction_obligation o
        JOIN hr.employee e ON e.employee_id = o.employee_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeductionObligationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(DeductionObligationCreateRequest request, Long createdById) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("employeeId", request.employeeId())
            .addValue("kind", request.kind().name())
            .addValue("monthlyInstructedAmount", request.monthlyInstructedAmount())
            .addValue("instructedTotal", request.instructedTotal())
            .addValue("authorityReference", request.authorityReference())
            .addValue("startDate", request.startDate())
            .addValue("notes", request.notes())
            .addValue("createdById", createdById);
        jdbc.update("""
            INSERT INTO hr.deduction_obligation
                (employee_id, kind, monthly_instructed_amount, instructed_total, authority_reference,
                 start_date, notes, created_by_id, updated_by_id)
            VALUES
                (:employeeId, :kind, :monthlyInstructedAmount, :instructedTotal, :authorityReference,
                 :startDate, :notes, :createdById, :createdById)
            """, params, keyHolder, new String[] {"obligation_id"});
        return keyHolder.getKey().longValue();
    }

    public Optional<DeductionObligationDto> findById(long obligationId) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE o.obligation_id = :id",
            Map.of("id", obligationId),
            this::mapRow
        ).stream().findFirst();
    }

    /** HR/CEO register, optionally filtered. */
    public List<DeductionObligationDto> findAll(
        Long employeeId, DeductionObligationKind kind, DeductionObligationStatus status
    ) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (employeeId != null) {
            sql.append(" AND o.employee_id = :employeeId");
            params.addValue("employeeId", employeeId);
        }
        if (kind != null) {
            sql.append(" AND o.kind = :kind");
            params.addValue("kind", kind.name());
        }
        if (status != null) {
            sql.append(" AND o.status = :status");
            params.addValue("status", status.name());
        }
        sql.append(" ORDER BY e.employee_code, o.start_date DESC");
        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    /** Employee self-service: every obligation ever recorded for them, most recent first. */
    public List<DeductionObligationDto> findForEmployee(long employeeId) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + FROM_JOIN
                + " WHERE o.employee_id = :employeeId ORDER BY o.start_date DESC",
            Map.of("employeeId", employeeId),
            this::mapRow
        );
    }

    /**
     * The obligation that drives THIS employee's payroll for this kind IN THIS PAYROLL MONTH, if
     * any: the ACTIVE one when there is one (the unique partial index guarantees at most one),
     * otherwise the most recently started COMPLETED one (still relevant — its {@code
     * overrideContinuePastTotal} may keep it driving deductions). A STOPPED obligation never drives
     * payroll again.
     *
     * <p>D2 fix (Opus review): a {@code start_date} filter is REQUIRED, not optional — an obligation
     * recorded ahead of its start (HR entering a future กยศ notification early, or a typo'd year)
     * must not deduct for a month before the authority actually instructed it, and reprocessing a
     * month that predates a since-created obligation must not retroactively back-deduct it either.
     * Before this filter existed, an obligation starting 2027-06 was found as "driving" for payroll
     * month 2026-01 and deducted the full monthly figure.
     *
     * <p>Off-by-one-month fix (Opus re-review): {@code payrollMonth} is always normalised to the
     * 1st of the month, but {@code start_date} is whatever DAY the authority's document is dated
     * (see {@link DeductionObligationDto#startDate()}'s own javadoc — the field is intentionally
     * the document date, not a "first payroll month to deduct" field). Comparing the raw dates
     * (`start_date &lt;= payrollMonth`) made a mid-month start (e.g. 2026-01-15) fail for its own
     * start month — 2026-01-15 &gt; 2026-01-01 — silently skipping January's instalment even though
     * the obligation had already begun. TRUNCATING start_date to its own month-start before the
     * comparison is what makes "started this calendar month" mean "drives this calendar month".
     */
    public Optional<DeductionObligationDto> findDrivingObligation(
        long employeeId, DeductionObligationKind kind, LocalDate payrollMonth
    ) {
        String sql = "SELECT " + SELECT_COLUMNS + FROM_JOIN + """
             WHERE o.employee_id = :employeeId AND o.kind = :kind AND o.status IN ('ACTIVE', 'COMPLETED')
               AND date_trunc('month', o.start_date)::date <= :payrollMonth
             ORDER BY (o.status = 'ACTIVE') DESC, o.start_date DESC
             LIMIT 1
            """;
        return jdbc.query(sql,
            Map.of("employeeId", employeeId, "kind", kind.name(), "payrollMonth", payrollMonth),
            this::mapRow
        ).stream().findFirst();
    }

    /** Never touches status/override — see {@link DeductionObligationUpdateRequest}'s own javadoc. */
    public int update(long obligationId, DeductionObligationUpdateRequest request, long updatedById) {
        return jdbc.update("""
            UPDATE hr.deduction_obligation
               SET monthly_instructed_amount = :monthlyInstructedAmount,
                   instructed_total = :instructedTotal,
                   authority_reference = :authorityReference,
                   notes = :notes,
                   updated_by_id = :updatedById,
                   updated_at = now()
             WHERE obligation_id = :id AND status <> 'STOPPED'
            """,
            new MapSqlParameterSource()
                .addValue("id", obligationId)
                .addValue("monthlyInstructedAmount", request.monthlyInstructedAmount())
                .addValue("instructedTotal", request.instructedTotal())
                .addValue("authorityReference", request.authorityReference())
                .addValue("notes", request.notes())
                .addValue("updatedById", updatedById));
    }

    /** Frozen from here on — a STOPPED obligation never reactivates on its own. */
    public int stop(long obligationId, long updatedById) {
        return jdbc.update("""
            UPDATE hr.deduction_obligation
               SET status = 'STOPPED', updated_by_id = :updatedById, updated_at = now()
             WHERE obligation_id = :id AND status <> 'STOPPED'
            """,
            Map.of("id", obligationId, "updatedById", updatedById));
    }

    public int acknowledgeCompletion(long obligationId, long ackById) {
        return jdbc.update("""
            UPDATE hr.deduction_obligation
               SET completion_acknowledged_by_id = :ackById,
                   completion_acknowledged_at = now(),
                   updated_by_id = :ackById,
                   updated_at = now()
             WHERE obligation_id = :id AND status = 'COMPLETED'
            """,
            Map.of("id", obligationId, "ackById", ackById));
    }

    /** Decision 3's mitigation: only reachable once an obligation is COMPLETED. */
    public int setOverrideContinue(long obligationId, long overrideById, String reason) {
        return jdbc.update("""
            UPDATE hr.deduction_obligation
               SET override_continue_past_total = TRUE,
                   override_by_id = :overrideById,
                   override_at = now(),
                   override_reason = :reason,
                   updated_by_id = :overrideById,
                   updated_at = now()
             WHERE obligation_id = :id AND status = 'COMPLETED'
            """,
            new MapSqlParameterSource()
                .addValue("id", obligationId)
                .addValue("overrideById", overrideById)
                .addValue("reason", reason));
    }

    /** Resumes normal auto-stop behaviour from the next resolve onward. */
    public int clearOverrideContinue(long obligationId, long updatedById) {
        return jdbc.update("""
            UPDATE hr.deduction_obligation
               SET override_continue_past_total = FALSE,
                   override_by_id = NULL,
                   override_at = NULL,
                   override_reason = NULL,
                   updated_by_id = :updatedById,
                   updated_at = now()
             WHERE obligation_id = :id AND status = 'COMPLETED'
            """,
            Map.of("id", obligationId, "updatedById", updatedById));
    }

    /**
     * Applies the completion transition implied by a freshly-recomputed cumulative total.
     * {@code shouldBeCompleted = true} only ever moves ACTIVE -> COMPLETED (a no-op if already
     * COMPLETED or STOPPED); {@code false} only ever reverts COMPLETED -> ACTIVE, clearing the
     * acknowledgement AND override fields (a correction that drops the cumulative total back under
     * the instructed total means the obligation is, factually, no longer complete — see this
     * method's caller, {@code DeductionObligationService#recordRemittance}). A STOPPED obligation is
     * untouched either way: HR ending it deliberately is never silently reopened by a ledger
     * recompute.
     */
    public void applyCompletionTransition(long obligationId, boolean shouldBeCompleted) {
        if (shouldBeCompleted) {
            jdbc.update("""
                UPDATE hr.deduction_obligation
                   SET status = 'COMPLETED', completed_at = COALESCE(completed_at, now())
                 WHERE obligation_id = :id AND status = 'ACTIVE'
                """, Map.of("id", obligationId));
        } else {
            jdbc.update("""
                UPDATE hr.deduction_obligation
                   SET status = 'ACTIVE',
                       completed_at = NULL,
                       completion_acknowledged_by_id = NULL,
                       completion_acknowledged_at = NULL,
                       override_continue_past_total = FALSE,
                       override_by_id = NULL,
                       override_at = NULL,
                       override_reason = NULL
                 WHERE obligation_id = :id AND status = 'COMPLETED'
                """, Map.of("id", obligationId));
        }
    }

    /**
     * Sum of every ledger row for this obligation EXCLUDING {@code payrollMonth} itself — the basis
     * for computing THIS period's own remaining-balance clamp so a re-run of {@code payrollMonth}
     * never sees its own prior remittance as "already paid" (see V106's header on why the ledger is
     * a per-period upsert, not an accumulating counter).
     */
    public BigDecimal sumRemittanceExcludingMonth(long obligationId, LocalDate payrollMonth) {
        BigDecimal sum = jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount), 0)
              FROM hr.deduction_obligation_remittance
             WHERE obligation_id = :id AND payroll_month <> :payrollMonth
            """,
            Map.of("id", obligationId, "payrollMonth", payrollMonth),
            BigDecimal.class);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** Total ever remitted, every period included — what the progress view reports as "paid to date". */
    public BigDecimal sumRemittanceTotal(long obligationId) {
        BigDecimal sum = jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount), 0)
              FROM hr.deduction_obligation_remittance
             WHERE obligation_id = :id
            """,
            Map.of("id", obligationId),
            BigDecimal.class);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public int countRemittedPeriods(long obligationId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.deduction_obligation_remittance WHERE obligation_id = :id
            """,
            Map.of("id", obligationId),
            Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Idempotent per (obligation, payroll_month) — see V106's header. Reprocessing the same month
     * overwrites that period's own row instead of inserting a second one, so the ledger self-
     * corrects on a payroll re-run rather than double-counting.
     */
    public void upsertRemittance(long obligationId, LocalDate payrollMonth, BigDecimal amount, Long payrollPeriodId) {
        jdbc.update("""
            INSERT INTO hr.deduction_obligation_remittance
                (obligation_id, payroll_month, amount, payroll_period_id)
            VALUES (:obligationId, :payrollMonth, :amount, :payrollPeriodId)
            ON CONFLICT (obligation_id, payroll_month)
            DO UPDATE SET amount = EXCLUDED.amount,
                          payroll_period_id = EXCLUDED.payroll_period_id,
                          updated_at = now()
            """,
            new MapSqlParameterSource()
                .addValue("obligationId", obligationId)
                .addValue("payrollMonth", payrollMonth)
                .addValue("amount", amount)
                .addValue("payrollPeriodId", payrollPeriodId));
    }

    /**
     * A zero-amount period is not a remittance (nothing was actually deducted/remitted that month
     * -- an unrelated cap clamped it to zero, or the month predates the obligation's start_date) --
     * removing any row instead of upserting a zero keeps {@link #countRemittedPeriods}/{@link
     * #findLedger} honest as "periods money actually moved", the figure the progress view's "N of M
     * งวด" reports. Idempotent no-op if no row exists for this period. DELETE (not "skip insert") is
     * required so a reprocess that newly zeroes out a previously-nonzero period removes the STALE
     * row rather than leaving it stranded.
     */
    public void deleteRemittance(long obligationId, LocalDate payrollMonth) {
        jdbc.update("""
            DELETE FROM hr.deduction_obligation_remittance
             WHERE obligation_id = :obligationId AND payroll_month = :payrollMonth
            """,
            Map.of("obligationId", obligationId, "payrollMonth", payrollMonth));
    }

    public List<RemittanceLedgerRowDto> findLedger(long obligationId) {
        return jdbc.query("""
            SELECT remittance_id, payroll_month, amount, payroll_period_id
              FROM hr.deduction_obligation_remittance
             WHERE obligation_id = :id
             ORDER BY payroll_month
            """,
            Map.of("id", obligationId),
            (rs, rowNum) -> new RemittanceLedgerRowDto(
                rs.getLong("remittance_id"),
                rs.getObject("payroll_month", LocalDate.class),
                rs.getBigDecimal("amount"),
                nullableLong(rs, "payroll_period_id")
            ));
    }

    private DeductionObligationDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeductionObligationDto(
            rs.getLong("obligation_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            DeductionObligationKind.valueOf(rs.getString("kind")),
            rs.getBigDecimal("monthly_instructed_amount"),
            rs.getBigDecimal("instructed_total"),
            rs.getString("authority_reference"),
            rs.getObject("start_date", LocalDate.class),
            DeductionObligationStatus.valueOf(rs.getString("status")),
            rs.getObject("completed_at", OffsetDateTime.class),
            nullableLong(rs, "completion_acknowledged_by_id"),
            rs.getObject("completion_acknowledged_at", OffsetDateTime.class),
            rs.getBoolean("override_continue_past_total"),
            nullableLong(rs, "override_by_id"),
            rs.getObject("override_at", OffsetDateTime.class),
            rs.getString("override_reason"),
            rs.getString("notes"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
