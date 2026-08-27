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
import th.co.glr.hr.approval.PendingApproverSql;
import th.co.glr.hr.common.ApiException;

/**
 * Modelled closely on {@code th.co.glr.hr.overtime.OvertimeRepository} -- same idioms
 * (text-block SQL, {@link MapSqlParameterSource}, a shared {@code baseSelect()}, a private row
 * mapper). Deliberately does not import anything from the {@code overtime} package, so the two
 * modules' rules can diverge without dragging each other along.
 *
 * <p>They now HAVE diverged, in the direction that matters: overtime scopes reads to a ผู้จัดการ's
 * ฝ่าย, welfare scopes them to the employee alone. {@link #findRequests} and
 * {@link #findEmployeeOptions} therefore no longer take a division at all -- see
 * {@link SpecialMoneyFilter}. Do not "restore the symmetry" with overtime here.
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

    // findEmployeeAccess (employee -> division / reports-to) was deleted on 2026-08-10 along with
    // SpecialMoneyService.managesEmployee, its only caller. It answered "does this ผู้จัดการ manage
    // this employee?" -- exactly the question welfare must no longer ask, since hr/ceo are the only
    // roles that may look at another employee's claim. Reinstating it is how the division-wide read
    // comes back; overtime and leave keep their own copies for their own (still division-scoped)
    // rules.

    public Optional<EmployeeEligibilitySnapshot> findEligibility(long employeeId, LocalDate today) {
        return jdbc.query("""
            SELECT e.employee_id,
                   e.hire_date,
                   e.confirm_date,
                   e.probation_days,
                   d.source_code AS department_source_code,
                   p.source_code AS position_source_code,
                   e.is_active
              FROM hr.employee e
              LEFT JOIN hr.department d ON d.department_id = e.department_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE e.employee_id = :employeeId
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new EmployeeEligibilitySnapshot(
                rs.getLong("employee_id"),
                rs.getObject("hire_date", LocalDate.class),
                rs.getObject("confirm_date", LocalDate.class),
                nullableInt(rs, "probation_days"),
                rs.getString("department_source_code"),
                rs.getString("position_source_code"),
                rs.getBoolean("is_active"),
                today
            ))
            .stream()
            .findFirst();
    }

    /**
     * {@code year} is deliberately never {@code event_date}'s year -- see {@code
     * SpecialMoneyService#usageYear}'s Javadoc for why that field cannot key an annual cap (it is
     * employee-supplied and unbounded: {@link SubmitSpecialMoneyHttpRequest} marks it {@code
     * @NotNull} only, V66 has no future-date check, and {@code evaluateMedical} does not even read
     * it). The two queries below key on two DIFFERENT columns instead, because they run over
     * different status sets and only one of those columns is populated for both:
     *
     * <ul>
     *   <li><b>{@code approvedAmountThisYear}</b> -- {@code status = 'APPROVED'} only, so {@code
     *       payroll_month} is always non-null ({@code chk_smr_approved_complete}) and is the exact
     *       column that decides which calendar year's payroll actually pays this row -- V128's
     *       {@code welfare_pay} is summed the same way. It is assigned by the server at approval
     *       time ({@code SpecialMoneyService#ceoApproveFrom}) from {@code LocalDate.now(...)}, never
     *       from client input.
     *   <li><b>{@code activeCountThisYear}</b> -- also spans {@code SUBMITTED} /
     *       {@code MANAGER_APPROVED}, which have no {@code payroll_month} yet (NULL until
     *       approval), so it keys on {@code requested_at} instead: a server-stamped timestamp
     *       ({@code DEFAULT now()}; {@link #create} never writes it explicitly and no method in
     *       this class ever updates it afterwards) present on every row from the moment it is
     *       created. Bangkok-zoned to match {@code SpecialMoneyService.BUSINESS_ZONE} -- this
     *       connection has no session-level timezone configured, so a bare {@code EXTRACT(YEAR FROM
     *       requested_at)} would extract whatever the server/session default (commonly UTC) says,
     *       which can disagree with Bangkok's calendar date by up to 7 hours a day.
     * </ul>
     *
     * <p>Both replacements are deliberately columns the employee never supplies on the request
     * body, so neither can be walked back to "employee picks the year" the way {@code event_date}
     * could.
     */
    public UsageSnapshot findUsage(long employeeId, int year) {
        Map<SpecialMoneyType, BigDecimal> approvedAmountThisYear = new EnumMap<>(SpecialMoneyType.class);
        jdbc.query("""
            SELECT request_type, SUM(approved_amount) AS total_approved
              FROM hr.special_money_request
             WHERE employee_id = :employeeId
               AND status = 'APPROVED'
               AND EXTRACT(YEAR FROM payroll_month) = :year
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

        // Counts include in-flight rows on purpose -- see UsageSnapshot's javadoc. Counting only
        // APPROVED would let an employee file the same once-per-year claim twice before either was
        // decided, and both would then be approvable.
        Map<SpecialMoneyType, Integer> activeCountLifetime = new EnumMap<>(SpecialMoneyType.class);
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
                    activeCountLifetime.put(type, rs.getInt("lifetime_count"));
                }
                return null;
            });

        Map<SpecialMoneyType, Integer> activeCountThisYear = new EnumMap<>(SpecialMoneyType.class);
        jdbc.query("""
            SELECT request_type, COUNT(*) AS year_count
              FROM hr.special_money_request
             WHERE employee_id = :employeeId
               AND status IN ('SUBMITTED', 'MANAGER_APPROVED', 'APPROVED')
               AND EXTRACT(YEAR FROM (requested_at AT TIME ZONE 'Asia/Bangkok')) = :year
             GROUP BY request_type
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("year", year),
            (rs, rowNum) -> {
                SpecialMoneyType type = parseType(rs.getString("request_type"));
                if (type != null) {
                    activeCountThisYear.put(type, rs.getInt("year_count"));
                }
                return null;
            });

        return new UsageSnapshot(approvedAmountThisYear, activeCountLifetime, activeCountThisYear);
    }

    // ---------------------------------------------------------------------
    // Evidence attachments (hr.special_money_request_attachment, V66)
    // ---------------------------------------------------------------------

    public long addAttachment(
            long requestId, Long uploadedById, String fileName, String storagePath, String mimeType, Long sizeBytes) {
        return jdbc.queryForObject("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes, uploaded_by_id)
            VALUES (:requestId, :fileName, :storagePath, :mimeType, :sizeBytes, :uploadedById)
            RETURNING attachment_id
            """, new MapSqlParameterSource()
            .addValue("requestId", requestId)
            .addValue("fileName", fileName)
            .addValue("storagePath", storagePath)
            .addValue("mimeType", mimeType)
            .addValue("sizeBytes", sizeBytes)
            .addValue("uploadedById", uploadedById), Long.class);
    }

    public List<SpecialMoneyAttachmentDto> findAttachments(long requestId) {
        return jdbc.query("""
            SELECT a.attachment_id, a.special_money_request_id, a.file_name, a.mime_type,
                   a.size_bytes, a.uploaded_by_id,
                   concat_ws(' ', u.first_name_th, u.last_name_th) AS uploaded_by_name,
                   a.uploaded_at
              FROM hr.special_money_request_attachment a
              LEFT JOIN hr.employee u ON u.employee_id = a.uploaded_by_id
             WHERE a.special_money_request_id = :requestId
             ORDER BY a.attachment_id
            """, Map.of("requestId", requestId), (rs, rowNum) -> new SpecialMoneyAttachmentDto(
                rs.getLong("attachment_id"),
                rs.getLong("special_money_request_id"),
                rs.getString("file_name"),
                rs.getString("mime_type"),
                nullableLong(rs, "size_bytes"),
                nullableLong(rs, "uploaded_by_id"),
                blankToNull(rs.getString("uploaded_by_name")),
                rs.getObject("uploaded_at", OffsetDateTime.class)));
    }

    /** Storage path + owning request for a download, so the controller can authorize before serving. */
    public Optional<AttachmentLocation> findAttachmentLocation(long attachmentId) {
        return jdbc.query("""
            SELECT special_money_request_id, file_name, storage_path, mime_type
              FROM hr.special_money_request_attachment
             WHERE attachment_id = :attachmentId
            """, Map.of("attachmentId", attachmentId), (rs, rowNum) -> new AttachmentLocation(
                rs.getLong("special_money_request_id"),
                rs.getString("file_name"),
                rs.getString("storage_path"),
                rs.getString("mime_type")))
            .stream()
            .findFirst();
    }

    public int countAttachments(long requestId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.special_money_request_attachment WHERE special_money_request_id = :requestId",
            Map.of("requestId", requestId), Integer.class);
        return count == null ? 0 : count;
    }

    public record AttachmentLocation(long requestId, String fileName, String storagePath, String mimeType) {
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
        if (filter.ownEmployeeId() != null) {
            // Own rows ONLY -- no division disjunct, no reports_to disjunct. Welfare is
            // confidential to each employee (SpecialMoneyService's class Javadoc): hr/ceo read
            // across it by leaving ownEmployeeId null, and everyone else, ผู้จัดการ included, is
            // pinned to themselves. This clause used to read
            // `(s.employee_id = :me OR e.division_id = :myDivision)`, which is the bug.
            sql.append(" AND s.employee_id = :ownEmployeeId");
            params.addValue("ownEmployeeId", filter.ownEmployeeId());
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

    /**
     * The live approval route: SUBMITTED straight to APPROVED. Welfare has no manager stage, so
     * {@code manager_approved_by} / {@code manager_approved_at} stay NULL on every request approved
     * from here — stamping the CEO into those columns would forge a review stage that never
     * happened.
     *
     * <p>{@link #ceoApprove} is the same statement guarded on {@code MANAGER_APPROVED}, kept only to
     * clear rows written before the manager stage was removed.
     */
    public int ceoDirectApprove(
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
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("approvedAmount", approvedAmount)
            .addValue("payrollMonth", payrollMonth)
            .addValue("capOverrideReason", capOverrideReason)
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

    /**
     * The submit form's employee picker. <b>Not</b> {@code OvertimeRepository.findEmployeeOptions}'s
     * shape any more: overtime offers a ผู้จัดการ their whole ฝ่าย because overtime really can be
     * filed and approved for a team, whereas welfare is confidential per employee.
     *
     * <p>So there is no {@code managerDivisionId} parameter: {@code includeAll} (hr/ceo) returns
     * every active employee as a LIST FILTER roster, and everyone else gets exactly themselves.
     * That keeps the picker in step with {@link SpecialMoneyService#resolveTargetEmployee}, which
     * refuses any target but the caller — offering a name here that submit would then 403 turns a
     * picker choice into an error.
     */
    public List<SpecialMoneyEmployeeOption> findEmployeeOptions(Long actorEmployeeId, boolean includeAll) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   dep.name_th AS department_name
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
             WHERE e.is_active = TRUE
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("actorEmployeeId", actorEmployeeId);
        if (!includeAll) {
            sql.append(" AND e.employee_id = :actorEmployeeId");
        }
        sql.append(" ORDER BY e.employee_code");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            long employeeId = rs.getLong("employee_id");
            return new SpecialMoneyEmployeeOption(
                employeeId,
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                rs.getString("department_name"),
                actorEmployeeId != null && employeeId == actorEmployeeId,
                // directReport is now ALWAYS false -- nobody may file for anybody. Kept on the DTO
                // (rather than removed) so the wire shape and mockApi's mirror stay put; see
                // SpecialMoneyEmployeeOption.
                false
            );
        });
    }

    public Set<String> findExcludedProvinces() {
        List<String> provinces = jdbc.query("""
            SELECT province_name_th FROM hr.special_money_excluded_province
            """, Map.of(), (rs, rowNum) -> rs.getString("province_name_th"));
        return new HashSet<>(provinces);
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
                   (SELECT COUNT(*) FROM hr.special_money_request_attachment a
                     WHERE a.special_money_request_id = s.special_money_request_id) AS attachment_count,
                   s.created_at,
                   s.updated_at,
                   """
            // feat/pending-approver-info: read-only "who this is waiting on" -- welfare is
            // CEO-only, single-stage (see SpecialMoneyService's class Javadoc), so the single
            // generic lookup below is the only candidate this domain ever needs.
            + PendingApproverSql.SINGLE_ACTIVE_CEO_NAME_SQL + " AS ceo_single_name"
            + """

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
            rs.getInt("attachment_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            resolvePendingApproverRole(rs),
            resolvePendingApproverName(rs)
        );
    }

    /**
     * feat/pending-approver-info: welfare is CEO-only, single-stage (see {@code
     * SpecialMoneyService}'s class Javadoc -- {@code MANAGER_APPROVED} survives only for legacy
     * rows and {@code ceoApproveFrom} handles both the same way), so both pending statuses resolve
     * to "ceo". Any other status (APPROVED/REJECTED/CANCELLED) has nobody left to wait on.
     */
    String resolvePendingApproverRole(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        return "SUBMITTED".equals(status) || "MANAGER_APPROVED".equals(status) ? "ceo" : null;
    }

    /**
     * feat/pending-approver-info: {@code null} whenever more than one active ceo-role employee
     * exists -- see {@link PendingApproverSql#SINGLE_ACTIVE_CEO_NAME_SQL}'s Javadoc. Deliberate
     * ambiguity handling, not a bug -- see this feature's PR body.
     */
    String resolvePendingApproverName(ResultSet rs) throws SQLException {
        return "ceo".equals(resolvePendingApproverRole(rs)) ? blankToNull(rs.getString("ceo_single_name")) : null;
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "รายละเอียดคำขอไม่ถูกต้อง");
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
