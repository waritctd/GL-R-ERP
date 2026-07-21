package th.co.glr.hr.specialmoney;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.common.ApiException;

/**
 * Modelled closely on {@code th.co.glr.hr.overtime.OvertimeRepository} -- same idioms
 * (text-block SQL, {@link MapSqlParameterSource}, a shared {@code baseSelect()}, a private row
 * mapper). Deliberately does not import anything from the {@code overtime} package; see {@link
 * SpecialMoneyEmployeeAccess}.
 */
@Repository
public class SpecialMoneyRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SpecialMoneyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean employeeExists(long employeeId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.employee
                 WHERE employee_id = :employeeId
                   AND is_active = TRUE
            )
            """, Map.of("employeeId", employeeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<SpecialMoneyEmployeeAccess> findEmployeeAccess(long employeeId) {
        return jdbc.query("""
            SELECT employee_id, reports_to_employee_id, division_id, is_active
              FROM hr.employee
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new SpecialMoneyEmployeeAccess(
                rs.getLong("employee_id"),
                nullableLong(rs, "reports_to_employee_id"),
                nullableLong(rs, "division_id"),
                rs.getBoolean("is_active")
            ))
            .stream()
            .findFirst();
    }

    public Optional<EmployeeEligibilitySnapshot> findEligibility(long employeeId, LocalDate today) {
        return jdbc.query("""
            SELECT e.employee_id,
                   e.hire_date,
                   e.confirm_date,
                   e.probation_days,
                   d.source_code AS department_source_code,
                   e.is_active
              FROM hr.employee e
              LEFT JOIN hr.department d ON d.department_id = e.department_id
             WHERE e.employee_id = :employeeId
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new EmployeeEligibilitySnapshot(
                rs.getLong("employee_id"),
                rs.getObject("hire_date", LocalDate.class),
                rs.getObject("confirm_date", LocalDate.class),
                nullableInt(rs, "probation_days"),
                rs.getString("department_source_code"),
                rs.getBoolean("is_active"),
                today
            ))
            .stream()
            .findFirst();
    }

    public UsageSnapshot findUsage(long employeeId, int year) {
        Map<SpecialMoneyType, BigDecimal> approvedAmountThisYear = new EnumMap<>(SpecialMoneyType.class);
        jdbc.query("""
            SELECT request_type, SUM(approved_amount) AS total_approved
              FROM hr.special_money_request
             WHERE employee_id = :employeeId
               AND status = 'APPROVED'
               AND EXTRACT(YEAR FROM event_date) = :year
             GROUP BY request_type
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("year", year),
            (rs, rowNum) -> {
                SpecialMoneyType type = parseType(rs.getString("request_type"));
                if (type != null) {
                    approvedAmountThisYear.put(type, rs.getBigDecimal("total_approved"));
                }
                return null;
            });

        Map<SpecialMoneyType, Integer> approvedCountLifetime = new EnumMap<>(SpecialMoneyType.class);
        jdbc.query("""
            SELECT request_type, COUNT(*) AS lifetime_count
              FROM hr.special_money_request
             WHERE employee_id = :employeeId
               AND status IN ('SUBMITTED', 'MANAGER_APPROVED', 'APPROVED')
             GROUP BY request_type
            """, Map.of("employeeId", employeeId),
            (rs, rowNum) -> {
                SpecialMoneyType type = parseType(rs.getString("request_type"));
                if (type != null) {
                    approvedCountLifetime.put(type, rs.getInt("lifetime_count"));
                }
                return null;
            });

        return new UsageSnapshot(approvedAmountThisYear, approvedCountLifetime);
    }

    public PolicyAmounts findPolicyAmounts(String requestType, LocalDate asOf) {
        Map<String, BigDecimal> amountsByKey = new HashMap<>();
        Map<String, String> textByKey = new HashMap<>();
        int[] version = {1};
        jdbc.query("""
            SELECT policy_key, amount, text_value, version
              FROM hr.special_money_policy
             WHERE request_type = :requestType
               AND effective_from <= :asOf
               AND (effective_to IS NULL OR effective_to >= :asOf)
            """, new MapSqlParameterSource()
            .addValue("requestType", requestType)
            .addValue("asOf", asOf),
            (rs, rowNum) -> {
                String key = rs.getString("policy_key");
                BigDecimal amount = rs.getBigDecimal("amount");
                String text = rs.getString("text_value");
                if (amount != null) {
                    amountsByKey.put(key, amount);
                }
                if (text != null) {
                    textByKey.put(key, text);
                }
                int rowVersion = rs.getInt("version");
                if (rowVersion > version[0]) {
                    version[0] = rowVersion;
                }
                return null;
            });
        return new PolicyAmounts(amountsByKey, textByKey, version[0]);
    }

    public long create(
            long employeeId,
            Long requestedById,
            SubmitSpecialMoneyRequest request,
            SpecialMoneyType type,
            PolicyDecision decision) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.special_money_request (
                employee_id, request_type, event_date, event_end_date, receipt_date,
                quantity, requested_amount, payroll_bucket, policy_version, reason, detail,
                status, requested_by_id
            )
            VALUES (
                :employeeId, :requestType, :eventDate, :eventEndDate, :receiptDate,
                :quantity, :requestedAmount, :payrollBucket, :policyVersion, :reason, CAST(:detail AS jsonb),
                'SUBMITTED', :requestedById
            )
            RETURNING special_money_request_id
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("requestType", type.name())
            .addValue("eventDate", request.eventDate())
            .addValue("eventEndDate", request.eventEndDate())
            .addValue("receiptDate", request.receiptDate())
            .addValue("quantity", request.quantity())
            .addValue("requestedAmount", request.requestedAmount())
            .addValue("payrollBucket", decision.bucket().name())
            .addValue("policyVersion", decision.policyVersion())
            .addValue("reason", request.reason().trim())
            .addValue("detail", toJson(request.detail()))
            .addValue("requestedById", requestedById), Long.class);
        return id == null ? 0 : id;
    }

    public Optional<SpecialMoneyRequestDto> findById(long id) {
        return jdbc.query(baseSelect() + " WHERE s.special_money_request_id = :id",
            Map.of("id", id),
            this::mapRequest)
            .stream()
            .findFirst();
    }

    public List<SpecialMoneyRequestDto> findRequests(SpecialMoneyFilter filter) {
        StringBuilder sql = new StringBuilder(baseSelect()).append("""
             WHERE s.event_date BETWEEN :fromDate AND :toDate
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", filter.fromDate())
            .addValue("toDate", filter.toDate());

        if (filter.employeeId() != null) {
            sql.append(" AND s.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }
        if (filter.managerEmployeeId() != null) {
            StringBuilder scope = new StringBuilder(
                " AND (s.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId");
            params.addValue("managerEmployeeId", filter.managerEmployeeId());
            if (filter.managerDivisionId() != null) {
                scope.append(" OR e.division_id = :managerDivisionId");
                params.addValue("managerDivisionId", filter.managerDivisionId());
            }
            scope.append(")");
            sql.append(scope);
        }
        if (filter.status() != null) {
            sql.append(" AND s.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.requestType() != null) {
            sql.append(" AND s.request_type = :requestType");
            params.addValue("requestType", filter.requestType());
        }

        sql.append(" ORDER BY s.event_date DESC, s.requested_at DESC, s.special_money_request_id DESC");
        return jdbc.query(sql.toString(), params, this::mapRequest);
    }

    public int managerApprove(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'MANAGER_APPROVED',
                   manager_approved_by = :reviewedById,
                   manager_approved_at = now(),
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE special_money_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int ceoApprove(
            long id,
            Long reviewedById,
            BigDecimal approvedAmount,
            LocalDate payrollMonth,
            String capOverrideReason,
            String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'APPROVED',
                   approved_amount = :approvedAmount,
                   payroll_month = :payrollMonth,
                   cap_override_reason = :capOverrideReason,
                   ceo_approved_by = :reviewedById,
                   ceo_approved_at = now(),
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = COALESCE(CAST(:reviewerNote AS text), reviewer_note),
                   updated_at = now()
             WHERE special_money_request_id = :id
               AND status = 'MANAGER_APPROVED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("approvedAmount", approvedAmount)
            .addValue("payrollMonth", payrollMonth)
            .addValue("capOverrideReason", cleanNote(capOverrideReason))
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int reject(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'REJECTED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE special_money_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int ceoReject(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'REJECTED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE special_money_request_id = :id
               AND status = 'MANAGER_APPROVED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int cancel(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'CANCELLED',
                   reviewed_by_id = COALESCE(CAST(:reviewedById AS bigint), reviewed_by_id),
                   reviewed_at = CASE WHEN CAST(:reviewedById AS bigint) IS NULL THEN reviewed_at ELSE now() END,
                   reviewer_note = COALESCE(CAST(:reviewerNote AS text), reviewer_note),
                   cancelled_at = now(),
                   updated_at = now()
             WHERE special_money_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    /** Mirrors {@code OvertimeRepository.findEmployeeOptions}. */
    public List<SpecialMoneyEmployeeOption> findEmployeeOptions(
            Long managerEmployeeId, Long managerDivisionId, boolean includeAll) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   dep.name_th AS department_name,
                   e.reports_to_employee_id,
                   e.division_id
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
             WHERE e.is_active = TRUE
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("managerEmployeeId", managerEmployeeId)
            .addValue("managerDivisionId", managerDivisionId);
        if (!includeAll) {
            sql.append(" AND (e.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId");
            if (managerDivisionId != null) {
                sql.append(" OR e.division_id = :managerDivisionId");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY e.employee_code");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            long employeeId = rs.getLong("employee_id");
            Long reportsTo = nullableLong(rs, "reports_to_employee_id");
            Long divisionId = nullableLong(rs, "division_id");
            boolean self = managerEmployeeId != null && employeeId == managerEmployeeId;
            boolean directReport = (managerEmployeeId != null && managerEmployeeId.equals(reportsTo))
                || (managerDivisionId != null && managerDivisionId.equals(divisionId) && !self);
            return new SpecialMoneyEmployeeOption(
                employeeId,
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                rs.getString("department_name"),
                self,
                directReport
            );
        });
    }

    public Set<String> findExcludedProvinces() {
        List<String> provinces = jdbc.query("""
            SELECT province_name_th FROM hr.special_money_excluded_province
            """, Map.of(), (rs, rowNum) -> rs.getString("province_name_th"));
        return new HashSet<>(provinces);
    }

    /** Mirrors {@code OvertimeRepository.findCeoApproverEmployeeIds} -- same MD/MN division convention. */
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

    public boolean payrollMonthProcessed(LocalDate payrollMonth) {
        Boolean processed = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.payroll_period
                 WHERE payroll_month = :payrollMonth
                   AND status = 'PROCESSED'
            )
            """, Map.of("payrollMonth", payrollMonth), Boolean.class);
        return Boolean.TRUE.equals(processed);
    }

    private String baseSelect() {
        return """
            SELECT s.special_money_request_id,
                   s.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   s.request_type,
                   s.event_date,
                   s.event_end_date,
                   s.receipt_date,
                   s.quantity,
                   s.requested_amount,
                   s.approved_amount,
                   s.payroll_bucket,
                   s.policy_version,
                   s.reason,
                   s.detail,
                   s.status,
                   s.payroll_month,
                   s.cap_override_reason,
                   s.requested_by_id,
                   concat_ws(' ', requested_by.first_name_th, requested_by.last_name_th) AS requested_by_name,
                   s.requested_at,
                   s.manager_approved_by,
                   concat_ws(' ', manager_approver.first_name_th, manager_approver.last_name_th) AS manager_approved_by_name,
                   s.manager_approved_at,
                   s.ceo_approved_by,
                   concat_ws(' ', ceo_approver.first_name_th, ceo_approver.last_name_th) AS ceo_approved_by_name,
                   s.ceo_approved_at,
                   s.reviewed_by_id,
                   concat_ws(' ', reviewed_by.first_name_th, reviewed_by.last_name_th) AS reviewed_by_name,
                   s.reviewed_at,
                   s.reviewer_note,
                   s.cancelled_at,
                   e.reports_to_employee_id,
                   concat_ws(' ', manager.first_name_th, manager.last_name_th) AS manager_name,
                   s.created_at,
                   s.updated_at
              FROM hr.special_money_request s
              JOIN hr.employee e ON e.employee_id = s.employee_id
              LEFT JOIN hr.employee requested_by ON requested_by.employee_id = s.requested_by_id
              LEFT JOIN hr.employee manager_approver ON manager_approver.employee_id = s.manager_approved_by
              LEFT JOIN hr.employee ceo_approver ON ceo_approver.employee_id = s.ceo_approved_by
              LEFT JOIN hr.employee reviewed_by ON reviewed_by.employee_id = s.reviewed_by_id
              LEFT JOIN hr.employee manager ON manager.employee_id = e.reports_to_employee_id
            """;
    }

    private SpecialMoneyRequestDto mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new SpecialMoneyRequestDto(
            rs.getLong("special_money_request_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("request_type"),
            rs.getObject("event_date", LocalDate.class),
            rs.getObject("event_end_date", LocalDate.class),
            rs.getObject("receipt_date", LocalDate.class),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("requested_amount"),
            rs.getBigDecimal("approved_amount"),
            rs.getString("payroll_bucket"),
            rs.getInt("policy_version"),
            rs.getString("reason"),
            fromJson(rs.getString("detail")),
            rs.getString("status"),
            rs.getObject("payroll_month", LocalDate.class),
            rs.getString("cap_override_reason"),
            nullableLong(rs, "requested_by_id"),
            blankToNull(rs.getString("requested_by_name")),
            rs.getObject("requested_at", OffsetDateTime.class),
            nullableLong(rs, "manager_approved_by"),
            blankToNull(rs.getString("manager_approved_by_name")),
            rs.getObject("manager_approved_at", OffsetDateTime.class),
            nullableLong(rs, "ceo_approved_by"),
            blankToNull(rs.getString("ceo_approved_by_name")),
            rs.getObject("ceo_approved_at", OffsetDateTime.class),
            nullableLong(rs, "reviewed_by_id"),
            blankToNull(rs.getString("reviewed_by_name")),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("cancelled_at", OffsetDateTime.class),
            nullableLong(rs, "reports_to_employee_id"),
            blankToNull(rs.getString("manager_name")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private SpecialMoneyType parseType(String value) {
        try {
            return SpecialMoneyType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private String toJson(Map<String, String> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid request detail");
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String cleanNote(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
