package th.co.glr.hr.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeAuthRepository {
    private static final String LOGIN_SELECT = """
        SELECT e.employee_id,
               e.employee_code,
               COALESCE(substring(e.email from '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+'), btrim(e.email)) AS email,
               e.is_active,
               e.created_at::date AS created_at,
               e.password_hash,
               e.must_change_password,
               e.can_create_quotation,
               d.division_id,
               d.source_code AS division_code,
               d.name_th AS division_name,
               p.name_th AS position_name,
               COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email) AS display_name
          FROM hr.employee e
          LEFT JOIN hr.division d ON d.division_id = e.division_id
          LEFT JOIN hr.position p ON p.position_id = e.position_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeAuthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeLoginRecord> findByEmail(String email) {
        try {
            EmployeeLoginRecord employee = jdbc.queryForObject(LOGIN_SELECT + """
                 WHERE e.email IS NOT NULL
                   AND btrim(e.email) <> ''
                   AND (
                       LOWER(btrim(e.email)) = LOWER(:email)
                       OR LOWER(substring(e.email from '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+')) = LOWER(:email)
                   )
                 ORDER BY e.is_active DESC, e.employee_id
                 LIMIT 1
                """, Map.of("email", email), (rs, rowNum) -> mapEmployee(rs));
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /** Every active employee, for the view-as candidate list. */
    public java.util.List<EmployeeLoginRecord> findAllActive() {
        return jdbc.query(LOGIN_SELECT + " WHERE e.is_active ORDER BY display_name, e.employee_id",
            Map.of(), (rs, rowNum) -> mapEmployee(rs));
    }

    public Optional<EmployeeLoginRecord> findByEmployeeId(long employeeId) {
        try {
            EmployeeLoginRecord employee = jdbc.queryForObject(
                LOGIN_SELECT + " WHERE e.employee_id = :id",
                Map.of("id", employeeId),
                (rs, rowNum) -> mapEmployee(rs));
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /** The login records for these employees in ONE query, keyed by id; an unknown id is simply absent. */
    public Map<Long, EmployeeLoginRecord> findByEmployeeIds(java.util.Collection<Long> employeeIds) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, EmployeeLoginRecord> byId = new LinkedHashMap<>();
        jdbc.query(LOGIN_SELECT + " WHERE e.employee_id IN (:ids)", Map.of("ids", employeeIds),
            (rs, rowNum) -> mapEmployee(rs)).forEach(record -> byId.put(record.employeeId(), record));
        return byId;
    }

    /** Display identity only (AuthResponse.operator): a developer who runs the portal. Grants no permission. */
    public boolean isOperator(long employeeId) {
        Boolean operator = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.employee
                 WHERE employee_id = :id AND is_active AND is_operator
            )
            """, Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(operator);
    }

    /** Whether this employee holds the admin capability, for the UI hint on AuthResponse. That one is the security
     * gate and must not depend on the auth module; this one only decides whether a nav item renders. */
    public boolean isAdmin(long employeeId) {
        Boolean admin = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.employee
                 WHERE employee_id = :id AND is_active AND is_admin
            )
            """, Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(admin);
    }

    /**
     * Whether this employee holds the per-employee "can create quotations" capability (lets
     * someone outside ฝ่ายขาย create deal quotations; {@code DivisionAccessPolicy#roleFor} also
     * gives an employee/qc/warehouse holder the {@code sales} role).
     *
     * <p>Same shape and same discipline as {@link #isAdmin}: a live read against
     * {@code hr.employee.can_create_quotation}, restricted to active rows, decided fresh on every
     * call rather than trusted from the session principal (which is captured at login and would
     * otherwise go stale the moment a grant is added or revoked).
     * This IS a security gate: {@code th.co.glr.hr.auth.DealEntryAccess} and
     * {@code th.co.glr.hr.dealquotation.DealQuotationService} both call it live at decision time
     * (deal / customer / project creation and every quotation write). {@link AuthResponse} also
     * carries the result as a UI hint, exactly as {@code admin} does, but the hint is never trusted.
     */
    public boolean canCreateQuotation(long employeeId) {
        Boolean granted = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.employee
                 WHERE employee_id = :id AND is_active AND can_create_quotation
            )
            """, Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(granted);
    }

    /** Live read of {@code hr.employee.can_manage_all_sales_documents} (active rows) for an actor in the {@code sales} or
     * {@code sales_manager} role: the holder acts as the OWNER of every deal's sales documents. The grant gives nothing to
     * any other role (Ploy 2026-11-19), and that rule lives only here. Same discipline as {@link #canCreateQuotation}:
     * never cached on the principal. */
    public boolean canManageAllSalesDocuments(UserPrincipal actor) {
        return canManageAllSalesDocuments(jdbc, actor);
    }

    /** The one SQL copy; repositories that already hold a jdbc template (TicketRepository) call this instead of copying it. */
    public static boolean canManageAllSalesDocuments(NamedParameterJdbcTemplate jdbc, UserPrincipal actor) {
        if (!"sales".equals(actor.role()) && !"sales_manager".equals(actor.role())) {
            return false;
        }
        Boolean granted = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.employee
                 WHERE employee_id = :id AND is_active AND can_manage_all_sales_documents
            )
            """, Map.of("id", actor.id()), Boolean.class);
        return Boolean.TRUE.equals(granted);
    }

    /**
     * Whether this employee holds the {@code hr.employee.can_issue_billing_note} grant — the per-employee capability that lets a caller in
     * ANY role create/update/issue/revise/cancel/mark-received a ใบวางบิล (billing note) without
     * being handed the {@code account} role wholesale. Same shape and same discipline as {@link
     * #canCreateQuotation}/{@link #isAdmin}: a live read against {@code hr.employee}, restricted to
     * active rows, decided fresh on every call rather than trusted from the session principal.
     *
     * <p>Holders include plain {@code employee} rows (QC&amp;ISO) and sales roles, none holding
     * {@code account}, so the grant must genuinely work for a plain {@code employee} row, not
     * merely widen an existing sales-shaped gate. {@link th.co.glr.hr.billing.BillingNoteService} is the caller; see its own
     * Javadoc for the full write/read gate this grant participates in.
     */
    public boolean canIssueBillingNote(long employeeId) {
        Boolean granted = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.employee
                 WHERE employee_id = :id AND is_active AND can_issue_billing_note
            )
            """, Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(granted);
    }

    /** The per-person invoice-issuer grant ({@code hr.employee.can_issue_tax_invoice}, any role): a live read of an active
     * row, never cached on the session. */
    public boolean canIssueTaxInvoice(long employeeId) {
        return canIssueTaxInvoice(jdbc, employeeId);
    }

    /** The import role (division PCIM, as {@code DivisionAccessPolicy#roleFor}) stays price-free: the grant gives it nothing,
     * so it is never counted as an issuer. Needs {@code d} = hr.division. */
    public static final String NOT_IMPORT_DIVISION =
        "LOWER(TRIM(COALESCE(NULLIF(TRIM(d.source_code), ''), split_part(COALESCE(d.name_th, ''), '-', 1)))) <> 'pcim'";

    /** The one SQL copy, for repositories that already hold a jdbc template (TicketRepository). */
    public static boolean canIssueTaxInvoice(NamedParameterJdbcTemplate jdbc, long employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM hr.employee e LEFT JOIN hr.division d ON d.division_id = e.division_id
                            WHERE e.employee_id = :id AND e.is_active AND e.can_issue_tax_invoice AND """
                + " " + NOT_IMPORT_DIVISION + ")", Map.of("id", employeeId), Boolean.class));
    }

    /** The per-person read-everything grant ({@code hr.employee.can_read_all_sales}): a live read of an active row, never
     * cached on the session. Role handling (import excluded) lives in {@link OversightReader}. */
    public boolean canReadAllSales(long employeeId) {
        return canReadAllSales(jdbc, employeeId);
    }

    /** The one SQL copy, for repositories that already hold a jdbc template (TicketRepository). */
    public static boolean canReadAllSales(NamedParameterJdbcTemplate jdbc, long employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM hr.employee WHERE employee_id = :id AND is_active AND can_read_all_sales)",
            Map.of("id", employeeId), Boolean.class));
    }

    /** The showroom team's grant ({@code hr.employee.can_see_showroom_orders}): reads every SHOWROOM no-project order. A
     * live read of an active row, like the other per-person grants. */
    public boolean canSeeShowroomOrders(long employeeId) {
        return canSeeShowroomOrders(jdbc, employeeId);
    }

    public static boolean canSeeShowroomOrders(NamedParameterJdbcTemplate jdbc, long employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM hr.employee WHERE employee_id = :id AND is_active AND can_see_showroom_orders)",
            Map.of("id", employeeId), Boolean.class));
    }

    /** Stores a user-chosen password and clears the forced-change flag. */
    public void updatePassword(long employeeId, String passwordHash) {
        jdbc.update("""
            UPDATE hr.employee
               SET password_hash = :hash,
                   must_change_password = FALSE,
                   updated_at = now()
             WHERE employee_id = :id
            """, Map.of("id", employeeId, "hash", passwordHash));
    }

    /** Admin reset: overwrites the password with a freshly issued temporary hash and forces a change on next login.
     * Unlike #backfillTemporaryPassword there is NO IS NULL guard — an HR reset is an intentional overwrite of
     * whatever password the row currently has. */
    public void setTemporaryPassword(long employeeId, String passwordHash) {
        jdbc.update("""
            UPDATE hr.employee
               SET password_hash = :hash,
                   must_change_password = TRUE,
                   updated_at = now()
             WHERE employee_id = :id
            """, Map.of("id", employeeId, "hash", passwordHash));
    }

    /** Seeds a temporary password hash for a row that has none, leaving must_change_password = TRUE. The IS NULL
     * guard makes it idempotent and race-safe so it never overwrites a password a user has already set. */
    public void backfillTemporaryPassword(long employeeId, String passwordHash) {
        jdbc.update("""
            UPDATE hr.employee
               SET password_hash = :hash
             WHERE employee_id = :id
               AND password_hash IS NULL
            """, Map.of("id", employeeId, "hash", passwordHash));
    }

    /** employee_id -> employee_code for active rows that still lack a password hash. */
    public Map<Long, String> findActiveCodesNeedingPassword() {
        return jdbc.query("""
            SELECT employee_id, employee_code
              FROM hr.employee
             WHERE password_hash IS NULL
               AND is_active = TRUE
               AND employee_code IS NOT NULL
               AND btrim(employee_code) <> ''
            """, new MapSqlParameterSource(), rs -> {
            Map<Long, String> codes = new LinkedHashMap<>();
            while (rs.next()) {
                codes.put(rs.getLong("employee_id"), rs.getString("employee_code"));
            }
            return codes;
        });
    }

    private EmployeeLoginRecord mapEmployee(ResultSet rs) throws SQLException {
        return new EmployeeLoginRecord(
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getBoolean("is_active"),
            nullableLong(rs, "division_id"),
            rs.getString("division_code"),
            rs.getString("division_name"),
            rs.getString("position_name"),
            rs.getObject("created_at", LocalDate.class),
            rs.getString("password_hash"),
            rs.getBoolean("must_change_password"),
            rs.getBoolean("can_create_quotation")
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
