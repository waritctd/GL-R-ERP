package th.co.glr.hr.payroll.obligation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallDtos.PayrollDeductionShortfallDto;

/**
 * Persistence for {@code hr.payroll_deduction_shortfall} (V107, issue #376) -- the generalised
 * requested/actual/shortfall triple. See that migration's header and {@link
 * DeductionObligationService#recordGarnishmentShortfalls} for what writes it and why the ONLY
 * deduction kind that populates it today is {@link PayrollDeductionKind#LEGAL_EXECUTION_GARNISHMENT}.
 */
@Repository
public class PayrollDeductionShortfallRepository {
    private static final String SELECT_COLUMNS = """
        s.shortfall_id, s.employee_id, e.employee_code,
        COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
        s.payroll_month, s.deduction_kind, s.requested_amount, s.actual_amount, s.shortfall_amount,
        s.shortfall_reason, s.payroll_period_id, s.created_at, s.updated_at
        """;
    private static final String FROM_JOIN = """
        FROM hr.payroll_deduction_shortfall s
        JOIN hr.employee e ON e.employee_id = s.employee_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public PayrollDeductionShortfallRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent per (employee, payroll_month, deduction_kind) -- a payroll re-run overwrites that
     * period's own row, exactly mirroring {@code DeductionObligationRepository#upsertRemittance}.
     */
    public void upsert(
        long employeeId, LocalDate payrollMonth, PayrollDeductionKind kind,
        BigDecimal requestedAmount, BigDecimal actualAmount, BigDecimal shortfallAmount,
        String shortfallReason, Long payrollPeriodId
    ) {
        jdbc.update("""
            INSERT INTO hr.payroll_deduction_shortfall
                (employee_id, payroll_month, deduction_kind, requested_amount, actual_amount,
                 shortfall_amount, shortfall_reason, payroll_period_id)
            VALUES
                (:employeeId, :payrollMonth, :kind, :requestedAmount, :actualAmount,
                 :shortfallAmount, :shortfallReason, :payrollPeriodId)
            ON CONFLICT (employee_id, payroll_month, deduction_kind)
            DO UPDATE SET requested_amount = EXCLUDED.requested_amount,
                          actual_amount = EXCLUDED.actual_amount,
                          shortfall_amount = EXCLUDED.shortfall_amount,
                          shortfall_reason = EXCLUDED.shortfall_reason,
                          payroll_period_id = EXCLUDED.payroll_period_id,
                          updated_at = now()
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("payrollMonth", payrollMonth)
                .addValue("kind", kind.name())
                .addValue("requestedAmount", requestedAmount)
                .addValue("actualAmount", actualAmount)
                .addValue("shortfallAmount", shortfallAmount)
                .addValue("shortfallReason", shortfallReason)
                .addValue("payrollPeriodId", payrollPeriodId));
    }

    /**
     * A period that no longer has a shortfall (a correction lowered the requested figure, or raised
     * the statutory cap headroom) must not leave a stale row behind -- same DELETE-not-skip
     * discipline as {@code DeductionObligationRepository#deleteRemittance}.
     */
    public void delete(long employeeId, LocalDate payrollMonth, PayrollDeductionKind kind) {
        jdbc.update("""
            DELETE FROM hr.payroll_deduction_shortfall
             WHERE employee_id = :employeeId AND payroll_month = :payrollMonth AND deduction_kind = :kind
            """,
            Map.of("employeeId", employeeId, "payrollMonth", payrollMonth, "kind", kind.name()));
    }

    public List<PayrollDeductionShortfallDto> findAll(Long employeeId, PayrollDeductionKind kind) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (employeeId != null) {
            sql.append(" AND s.employee_id = :employeeId");
            params.addValue("employeeId", employeeId);
        }
        if (kind != null) {
            sql.append(" AND s.deduction_kind = :kind");
            params.addValue("kind", kind.name());
        }
        sql.append(" ORDER BY s.payroll_month DESC, e.employee_code");
        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    private PayrollDeductionShortfallDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() must be read IMMEDIATELY after the getLong it reports on -- any intervening
        // column access (even a getString for an unrelated column) overwrites it, which is why
        // periodId/periodIdWasNull are captured together, before anything else is read from rs.
        long periodId = rs.getLong("payroll_period_id");
        boolean periodIdWasNull = rs.wasNull();
        return new PayrollDeductionShortfallDto(
            rs.getLong("shortfall_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getObject("payroll_month", LocalDate.class),
            PayrollDeductionKind.valueOf(rs.getString("deduction_kind")),
            rs.getBigDecimal("requested_amount"),
            rs.getBigDecimal("actual_amount"),
            rs.getBigDecimal("shortfall_amount"),
            rs.getString("shortfall_reason"),
            periodIdWasNull ? null : periodId,
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class)
        );
    }
}
