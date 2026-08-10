package th.co.glr.hr.employee;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import th.co.glr.hr.common.PageRequest;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.common.ApiException;

@Repository
public class EmployeeRepository {
    private static final String[][] AVATAR_PALETTE = {
        {"#e0e7ff", "#4338ca"},
        {"#ccfbf1", "#0f766e"},
        {"#fef3c7", "#b45309"},
        {"#ffe4e6", "#be123c"},
        {"#e0f2fe", "#0369a1"},
        {"#dcfce7", "#15803d"}
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final EmployeeReferenceRepository references;
    private final EmployeeCodeGenerator employeeCodes;

    public EmployeeRepository(
            NamedParameterJdbcTemplate jdbc,
            EmployeeReferenceRepository references,
            EmployeeCodeGenerator employeeCodes) {
        this.jdbc = jdbc;
        this.references = references;
        this.employeeCodes = employeeCodes;
    }

    public List<EmployeeDto> findEmployees(EmployeeFilter filter, boolean includeSensitive) {
        return findEmployees(filter, includeSensitive, null);
    }

    public List<EmployeeDto> findEmployees(EmployeeFilter filter, boolean includeSensitive, PageRequest page) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(baseSelect(includeSensitive)).append(" WHERE 1 = 1");
        appendFilters(sql, params, filter);

        sql.append(" ORDER BY e.employee_code");
        if (page != null) {
            sql.append(" LIMIT :limit OFFSET :offset");
            params.addValue("limit", page.size());
            params.addValue("offset", page.offset());
        }

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapEmployeeSummary(rs, includeSensitive));
    }

    public int countEmployees(EmployeeFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM (")
            .append(baseSelect(false)).append(" WHERE 1 = 1");
        appendFilters(sql, params, filter);
        sql.append(") AS filtered_employees");
        Integer total = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return total == null ? 0 : total;
    }

    private void appendFilters(StringBuilder sql, MapSqlParameterSource params, EmployeeFilter filter) {
        if (hasText(filter.search())) {
            sql.append("""
                 AND (
                     e.employee_code ILIKE :search
                     OR CONCAT_WS(' ', e.first_name_th, e.last_name_th) ILIKE :search
                     OR CONCAT_WS(' ', e.first_name_en, e.last_name_en) ILIKE :search
                     OR e.nickname ILIKE :search
                     OR e.email ILIKE :search
                 )
                """);
            params.addValue("search", "%" + filter.search().trim() + "%");
        }
        if (hasText(filter.divisionId())) {
            sql.append(" AND COALESCE(d.source_code, d.division_id::text) = :divisionId");
            params.addValue("divisionId", filter.divisionId());
        }
        if (hasText(filter.departmentTh())) {
            sql.append(" AND dep.name_th = :departmentTh");
            params.addValue("departmentTh", filter.departmentTh());
        }
        if (hasText(filter.statusId())) {
            sql.append(" AND ").append(EmployeeStatus.sqlCaseExpression()).append(" = :statusId");
            params.addValue("statusId", filter.statusId());
        }
        if (filter.active() != null) {
            sql.append(" AND e.is_active = :active");
            params.addValue("active", filter.active());
        }
    }

    public Optional<EmployeeDto> findEmployeeById(long id, boolean includeSensitive) {
        try {
            EmployeeDto employee = jdbc.queryForObject(
                baseSelect(includeSensitive) + " WHERE e.employee_id = :id",
                Map.of("id", id),
                (rs, rowNum) -> mapEmployeeDetail(rs, includeSensitive)
            );
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /**
     * D2 (owner ruling): every active employee whose derived login role is EXACTLY {@code "hr"} per
     * {@link th.co.glr.hr.auth.DivisionAccessPolicy#roleFor}.
     *
     * <p><b>S-3 review finding: the previous {@code d.source_code ILIKE 'HR%'} query did NOT mirror
     * {@code roleFor} and the mismatch was latent, not theoretical.</b> {@code roleFor} is:
     *
     * <pre>
     * public static String roleFor(EmployeeLoginRecord employee) {
     *     String code = divisionCode(employee);
     *     if ("md".equals(code) || isExecutive(employee)) {
     *         return "ceo";
     *     }
     *     if ("hr".equals(code)) {
     *         return "hr";
     *     }
     *     ...
     * }
     * private static String divisionCode(EmployeeLoginRecord employee) {
     *     String source = firstText(employee.divisionCode(), prefix(employee.divisionName()));
     *     return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
     * }
     * </pre>
     *
     * which the SQL below now reproduces exactly, in two respects {@code ILIKE 'HR%'} got wrong:
     *
     * <ol>
     *   <li><b>Exact match, not prefix.</b> {@code roleFor} requires {@code "hr".equals(code)} --
     *       an {@code HRD}-style {@code source_code} (Human Resources <em>Development</em>, say)
     *       would satisfy {@code ILIKE 'HR%'} but does NOT equal {@code "hr"}, so {@code roleFor}
     *       resolves such an employee to {@code "employee"}, never {@code "hr"}. The old query
     *       would have wrongly notified them.</li>
     *   <li><b>The {@code source_code IS NULL} fallback.</b> {@code hr.division.source_code} is
     *       NULLABLE; when it is null or blank, {@code divisionCode()} falls back to the {@code
     *       name_th} prefix before the first {@code '-'}. {@code d.source_code ILIKE 'HR%'} is
     *       false for every NULL row regardless of {@code name_th}, so a division like {@code
     *       name_th = 'HR-บุคคล'} with {@code source_code IS NULL} (a real, observed shape) was
     *       silently excluded even though {@code roleFor} grants its employees the {@code "hr"}
     *       role. The {@code CASE} below reproduces the fallback with {@code split_part(...,
     *       '-', 1)}.</li>
     * </ol>
     *
     * <p><b>Executive precedence, pinned.</b> {@code roleFor} checks {@code isExecutive} BEFORE
     * {@code "hr".equals(code)} -- an employee whose position contains "กรรมการ" resolves to
     * {@code "ceo"} even inside the HR division, and must therefore NOT be returned here. The
     * {@code NOT LIKE '%กรรมการ%'} guard below (whitespace-stripped first, matching {@code
     * DivisionAccessPolicy#contains}) reproduces that.
     *
     * <p>{@code 'HR'} (and the {@code name_th}-prefix {@code 'HR-...'} shape) is confirmed as this
     * company's actual HR division coding by {@code V115__work_schedule_and_holiday_calendar.sql}'s
     * OFFICE_5D seed -- not guessed from the "hr" role name.
     *
     * <p>This is a RECIPIENT-RESOLUTION query only -- it grants no access and enforces no
     * permission. Nothing reads its result to decide who MAY do something; every employee it returns
     * is already independently entitled (via their derived "hr" role) to see whatever this is used
     * to notify them about. Do not repurpose this as an authorization check.
     */
    public List<Long> findHrEmployeeIds() {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE e.is_active = TRUE
               AND regexp_replace(COALESCE(p.name_th, ''), '\\s+', '', 'g') NOT LIKE '%กรรมการ%'
               AND lower(btrim(
                     CASE
                         WHEN d.source_code IS NOT NULL AND btrim(d.source_code) <> '' THEN d.source_code
                         ELSE split_part(COALESCE(d.name_th, ''), '-', 1)
                     END
                   )) = 'hr'
             ORDER BY e.employee_id
            """, Map.of(), (rs, rowNum) -> rs.getLong("employee_id"));
    }

    public Optional<EmployeeDto> findEmployeeSummaryById(long id) {
        try {
            EmployeeDto employee = jdbc.queryForObject(
                baseSelect(false) + " WHERE e.employee_id = :id",
                Map.of("id", id),
                (rs, rowNum) -> mapEmployeeSummary(rs, false)
            );
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Map<Long, EmployeeDto> findEmployeeSummariesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<EmployeeDto> employees = jdbc.query(
            baseSelect(false) + " WHERE e.employee_id IN (:ids)",
            Map.of("ids", ids),
            (rs, rowNum) -> mapEmployeeSummary(rs, false)
        );
        return employees.stream().collect(Collectors.toMap(EmployeeDto::id, employee -> employee));
    }

    public boolean exists(long id) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM hr.employee WHERE employee_id = :id)",
            Map.of("id", id),
            Boolean.class
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean isEmployeeActive(long id) {
        Boolean active = jdbc.queryForObject(
            "SELECT is_active FROM hr.employee WHERE employee_id = :id",
            Map.of("id", id),
            Boolean.class
        );
        return Boolean.TRUE.equals(active);
    }

    public long create(UpsertEmployeeRequest request) {
        if (!hasText(request.nameTh())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุชื่อพนักงาน");
        }

        NameParts thaiName = splitName(request.nameTh());
        NameParts englishName = splitName(request.nameEn());
        Long titleId = references.ensureTitle(defaultText(request.titleTh(), "นาย"));
        Long divisionId = references.ensureDivision(request.divisionId(), defaultText(request.divisionTh(), request.divisionId()));
        Long departmentId = references.ensureDepartment(request.departmentTh(), divisionId);
        Long positionId = references.ensurePosition(defaultText(request.positionTh(), "เจ้าหน้าที่"));
        Long levelId = references.ensureLevel(defaultText(request.level(), "O2"));
        Long locationId = references.ensureLocation(defaultText(request.locationTh(), "สำนักงานใหญ่ กรุงเทพฯ"));
        Long statusId = references.ensureStatus(defaultText(request.statusId(), "ACT"));
        boolean active = EmployeeStatus.active(defaultText(request.statusId(), "ACT"));

        String employeeCode = hasText(request.code()) ? request.code().trim() : employeeCodes.nextEmployeeCode();
        LocalDate hireDate = request.hireDate() == null ? LocalDate.now() : request.hireDate();

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("employeeCode", employeeCode)
            .addValue("badge", hasText(request.badge()) ? request.badge().trim() : "BC-" + System.currentTimeMillis())
            .addValue("titleId", titleId)
            .addValue("firstNameTh", thaiName.first())
            .addValue("lastNameTh", thaiName.last())
            .addValue("firstNameEn", englishName.first())
            .addValue("lastNameEn", englishName.last())
            .addValue("nickName", request.nickName())
            .addValue("gender", genderCode(request.genderTh()))
            .addValue("birthDate", request.birthDate())
            .addValue("nationality", defaultText(request.nationality(), "ไทย"))
            .addValue("maritalStatus", defaultText(request.maritalStatus(), "โสด"))
            .addValue("email", request.email())
            .addValue("phone", request.phone())
            .addValue("divisionId", divisionId)
            .addValue("departmentId", departmentId)
            .addValue("positionId", positionId)
            .addValue("levelId", levelId)
            .addValue("locationId", locationId)
            .addValue("statusId", statusId)
            .addValue("salary", request.salary() == null ? BigDecimal.ZERO : request.salary())
            .addValue("directorRemuneration", request.directorRemuneration() == null ? BigDecimal.ZERO : request.directorRemuneration())
            // Nullable standing override -- pass through null (NO coalesce): null = no standing override,
            // distinct from a 0 override.
            .addValue("withholdingTaxOverride", request.withholdingTaxOverride())
            .addValue("hireDate", hireDate)
            .addValue("confirmDate", request.confirmationDate())
            .addValue("active", active);

        Long id = jdbc.queryForObject("""
            INSERT INTO hr.employee(
                employee_code, badge_card_no, title_id, first_name_th, last_name_th,
                first_name_en, last_name_en, nickname, gender, date_of_birth,
                nationality, marital_status, email, phone, division_id, department_id,
                position_id, level_id, location_id, status_id, pay_type, current_salary,
                director_remuneration, withholding_tax_override, hire_date, confirm_date, is_active
            )
            VALUES (
                :employeeCode, :badge, :titleId, :firstNameTh, :lastNameTh,
                :firstNameEn, :lastNameEn, :nickName, :gender, :birthDate,
                :nationality, :maritalStatus, :email, :phone, :divisionId, :departmentId,
                :positionId, :levelId, :locationId, :statusId, 'M', :salary,
                :directorRemuneration, :withholdingTaxOverride, :hireDate, :confirmDate, :active
            )
            RETURNING employee_id
            """, params, Long.class);

        long employeeId = id == null ? 0 : id;
        upsertCurrentAddress(employeeId, request.address(), request.phone());
        upsertEmergencyContact(employeeId, request.emergencyName(), request.emergencyRelationship(), request.emergencyPhone());
        insertCurrentAssignment(employeeId, divisionId, departmentId, positionId, levelId, locationId, statusId, hireDate);
        insertInitialSalary(employeeId, hireDate, request.salary());
        return employeeId;
    }

    public void update(long id, UpsertEmployeeRequest request) {
        if (!exists(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน");
        }

        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);

        if (request.nameTh() != null) {
            NameParts parts = splitName(request.nameTh());
            sets.add("first_name_th = :firstNameTh");
            sets.add("last_name_th = :lastNameTh");
            params.addValue("firstNameTh", parts.first()).addValue("lastNameTh", parts.last());
        }
        if (request.nameEn() != null) {
            NameParts parts = splitName(request.nameEn());
            sets.add("first_name_en = :firstNameEn");
            sets.add("last_name_en = :lastNameEn");
            params.addValue("firstNameEn", parts.first()).addValue("lastNameEn", parts.last());
        }
        addSet(sets, params, "nickname", "nickName", request.nickName());
        addSet(sets, params, "email", "email", request.email());
        addSet(sets, params, "phone", "phone", request.phone());
        addSet(sets, params, "date_of_birth", "birthDate", request.birthDate());
        addSet(sets, params, "nationality", "nationality", request.nationality());
        addSet(sets, params, "marital_status", "maritalStatus", request.maritalStatus());
        addSet(sets, params, "current_salary", "salary", request.salary());
        addSet(sets, params, "director_remuneration", "directorRemuneration", request.directorRemuneration());
        // Standing withholding override -- FULL-REPLACE, unlike every other field on this method (and
        // unlike director_remuneration/current_salary, which are NOT NULL columns cleared via 0). This
        // column is nullable and NULL is itself a meaningful state ("compute automatically"), so an
        // addSet-style skip-when-null would make the override settable but never clearable back to
        // NULL. Always emit the SET and bind the value even when null -- safe only because the employee
        // edit form (EmployeeFormModal) always submits this field as part of a full-object payload
        // (null when the input is blank), and it is the only caller of this update path.
        sets.add("withholding_tax_override = :withholdingTaxOverride");
        params.addValue("withholdingTaxOverride", request.withholdingTaxOverride());
        addSet(sets, params, "hire_date", "hireDate", request.hireDate());
        addSet(sets, params, "confirm_date", "confirmDate", request.confirmationDate());

        if (request.genderTh() != null) {
            sets.add("gender = :gender");
            params.addValue("gender", genderCode(request.genderTh()));
        }
        if (request.titleTh() != null) {
            sets.add("title_id = :titleId");
            params.addValue("titleId", references.ensureTitle(defaultText(request.titleTh(), "นาย")));
        }
        boolean assignmentChanged = false;
        Long divisionId = null;
        if (request.divisionId() != null || request.divisionTh() != null) {
            divisionId = references.ensureDivision(request.divisionId(), defaultText(request.divisionTh(), request.divisionId()));
            sets.add("division_id = :divisionId");
            params.addValue("divisionId", divisionId);
            assignmentChanged = true;
        }
        if (request.departmentTh() != null) {
            Long departmentDivisionId = divisionId == null ? references.currentDivisionId(id) : divisionId;
            sets.add("department_id = :departmentId");
            params.addValue("departmentId", references.ensureDepartment(request.departmentTh(), departmentDivisionId));
            assignmentChanged = true;
        }
        if (request.positionTh() != null) {
            sets.add("position_id = :positionId");
            params.addValue("positionId", references.ensurePosition(request.positionTh()));
            assignmentChanged = true;
        }
        if (request.level() != null) {
            sets.add("level_id = :levelId");
            params.addValue("levelId", references.ensureLevel(request.level()));
            assignmentChanged = true;
        }
        if (request.locationTh() != null) {
            sets.add("location_id = :locationId");
            params.addValue("locationId", references.ensureLocation(request.locationTh()));
            assignmentChanged = true;
        }
        if (request.statusId() != null) {
            sets.add("status_id = :statusId");
            sets.add("is_active = :active");
            params.addValue("statusId", references.ensureStatus(request.statusId()));
            params.addValue("active", EmployeeStatus.active(request.statusId()));
            assignmentChanged = true;
        }

        if (!sets.isEmpty()) {
            sets.add("updated_at = now()");
            jdbc.update("UPDATE hr.employee SET " + String.join(", ", sets) + " WHERE employee_id = :id", params);
        }

        if (assignmentChanged) {
            syncCurrentAssignment(id);
        }

        if (request.address() != null || request.phone() != null) {
            upsertCurrentAddress(id, request.address(), request.phone());
        }
        if (request.emergencyName() != null || request.emergencyRelationship() != null || request.emergencyPhone() != null) {
            upsertEmergencyContact(id, request.emergencyName(), request.emergencyRelationship(), request.emergencyPhone());
        }
    }

    public void updatePhone(long employeeId, String phone) {
        jdbc.update("UPDATE hr.employee SET phone = :phone, updated_at = now() WHERE employee_id = :id",
            Map.of("id", employeeId, "phone", phone));
    }

    public void updateEmail(long employeeId, String email) {
        jdbc.update("UPDATE hr.employee SET email = :email, updated_at = now() WHERE employee_id = :id",
            Map.of("id", employeeId, "email", email));
    }

    public void updateAddressLine(long employeeId, String line1) {
        upsertCurrentAddress(employeeId, line1, null);
    }

    public void updateEmergencyContact(long employeeId, String name, String phone) {
        upsertEmergencyContact(employeeId, name, null, phone);
    }

    private EmployeeDto mapEmployeeSummary(ResultSet rs, boolean includeSensitive) throws SQLException {
        long id = rs.getLong("employee_id");
        LocalDate birthDate = rs.getObject("date_of_birth", LocalDate.class);
        String nameTh = fullName(rs.getString("first_name_th"), rs.getString("last_name_th"));
        String nameEn = fullName(rs.getString("first_name_en"), rs.getString("last_name_en"));
        String statusId = rs.getString("status_id");
        String[][] palette = AVATAR_PALETTE;
        String[] colors = palette[Math.floorMod(Long.hashCode(id), palette.length)];

        return new EmployeeDto(
            id,
            rs.getString("employee_code"),
            rs.getString("badge_card_no"),
            nameTh,
            nameEn,
            rs.getString("nickname"),
            initials(nameEn, nameTh),
            colors[0],
            colors[1],
            rs.getString("title_th"),
            genderLabel(rs.getString("gender")),
            birthDate,
            birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears(),
            rs.getString("nationality"),
            rs.getString("marital_status"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("division_code"),
            rs.getString("division_th"),
            rs.getString("division_en"),
            rs.getString("department_th"),
            rs.getString("position_th"),
            rs.getString("position_en"),
            rs.getString("level_code"),
            rs.getString("location_th"),
            statusId,
            defaultText(rs.getString("status_th"), EmployeeStatus.name(statusId)),
            EmployeeStatus.tone(statusId),
            rs.getBoolean("is_active"),
            payTypeLabel(rs.getString("pay_type")),
            rs.getBigDecimal("current_salary"),
            rs.getBigDecimal("director_remuneration"),
            // Nullable standing override -- raw read preserves SQL NULL (no override) vs a 0 override.
            rs.getBigDecimal("withholding_tax_override"),
            rs.getObject("hire_date", LocalDate.class),
            rs.getObject("confirm_date", LocalDate.class),
            rs.getString("reports_to"),
            rs.getString("bank_name"),
            rs.getString("account_no"),
            new AddressDto(defaultText(rs.getString("address_line1"), ""), defaultText(rs.getString("district"), ""),
                defaultText(rs.getString("province"), ""), defaultText(rs.getString("postal_code"), "")),
            new EmergencyContactDto(defaultText(rs.getString("emergency_name"), NO_VALUE),
                defaultText(rs.getString("emergency_relationship"), NO_VALUE), defaultText(rs.getString("emergency_phone"), NO_VALUE)),
            List.of(),
            List.of(),
            includeSensitive
                ? new SensitiveDto(rs.getString("national_id"), rs.getString("tax_id"), rs.getString("social_security_no"),
                    rs.getString("ss_hospital"), rs.getString("provident_fund_no"))
                : SensitiveDto.empty(),
            0
        );
    }

    private EmployeeDto mapEmployeeDetail(ResultSet rs, boolean includeSensitive) throws SQLException {
        EmployeeDto snapshot = mapEmployeeSummary(rs, includeSensitive);
        List<AssignmentDto> assignments = loadAssignments(snapshot.id());
        if (assignments.isEmpty()) {
            assignments = List.of(new AssignmentDto(snapshot.hireDate(), null, snapshot.positionTh(), snapshot.divisionTh(), snapshot.departmentTh(), true));
        }

        return new EmployeeDto(
            snapshot.id(), snapshot.code(), snapshot.badge(), snapshot.nameTh(), snapshot.nameEn(), snapshot.nickName(),
            snapshot.initials(), snapshot.avatarBg(), snapshot.avatarFg(), snapshot.titleTh(), snapshot.genderTh(),
            snapshot.birthDate(), snapshot.age(), snapshot.nationality(), snapshot.maritalStatus(), snapshot.email(),
            snapshot.phone(), snapshot.divisionId(), snapshot.divisionTh(), snapshot.divisionEn(), snapshot.departmentTh(),
            snapshot.positionTh(), snapshot.positionEn(), snapshot.level(), snapshot.locationTh(), snapshot.statusId(),
            snapshot.statusTh(), snapshot.statusTone(), snapshot.active(), snapshot.payType(), snapshot.salary(),
            snapshot.directorRemuneration(), snapshot.withholdingTaxOverride(),
            snapshot.hireDate(), snapshot.confirmationDate(), snapshot.reportsTo(), snapshot.bank(), snapshot.bankAccount(),
            snapshot.currentAddress(), snapshot.emergencyContact(), assignments, loadSalaryHistory(snapshot.id()), snapshot.sensitive(), 0
        );
    }

    private String baseSelect(boolean includeSensitive) {
        String sensitiveColumns = includeSensitive
            ? """
                pii.national_id,
                pii.tax_id,
                pii.social_security_no,
                pii.ss_hospital,
                pii.provident_fund_no,
                """
            : """
                NULL::varchar AS national_id,
                NULL::varchar AS tax_id,
                NULL::varchar AS social_security_no,
                NULL::varchar AS ss_hospital,
                NULL::varchar AS provident_fund_no,
                """;
        String sensitiveJoin = includeSensitive
            ? " LEFT JOIN hr_restricted.employee_pii pii ON pii.employee_id = e.employee_id"
            : "";

        return """
            SELECT e.employee_id,
                   e.employee_code,
                   e.badge_card_no,
                   t.name_th AS title_th,
                   e.first_name_th,
                   e.last_name_th,
                   e.first_name_en,
                   e.last_name_en,
                   e.nickname,
                   e.gender,
                   e.date_of_birth,
                   e.nationality,
                   e.marital_status,
                   e.email,
                   e.phone,
                   COALESCE(d.source_code, d.division_id::text) AS division_code,
                   d.name_th AS division_th,
                   d.name_en AS division_en,
                   dep.name_th AS department_th,
                   p.name_th AS position_th,
                   p.name_en AS position_en,
                   COALESCE(lvl.source_code, lvl.name_th, e.job_grade) AS level_code,
                   loc.name_th AS location_th,
                   %s AS status_id,
                   s.name_th AS status_th,
                   e.is_active,
                   e.pay_type,
                   e.current_salary,
                   e.director_remuneration,
                   e.withholding_tax_override,
                   e.hire_date,
                   e.confirm_date,
                   NULLIF(TRIM(CONCAT_WS(' ', m.first_name_th, m.last_name_th)) || COALESCE(' · ' || mp.name_th, ''), '') AS reports_to,
                   b.name_th AS bank_name,
                   ba.account_no,
                   NULLIF(TRIM(CONCAT_WS(' ', addr.house_no, addr.building, addr.soi, addr.road)), '') AS address_line1,
                   addr.district,
                   addr.province,
                   addr.postal_code,
                   em.full_name AS emergency_name,
                   em.relationship AS emergency_relationship,
                   em.phone AS emergency_phone,
                   %s
                   e.created_at
              FROM hr.employee e
              LEFT JOIN hr.title t ON t.title_id = e.title_id
              LEFT JOIN hr.division d ON d.division_id = e.division_id
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
              LEFT JOIN hr.employee_level lvl ON lvl.level_id = e.level_id
              LEFT JOIN hr.work_location loc ON loc.location_id = e.location_id
              LEFT JOIN hr.employment_status s ON s.status_id = e.status_id
              LEFT JOIN hr.employee m ON m.employee_id = e.reports_to_employee_id
              LEFT JOIN hr.position mp ON mp.position_id = m.position_id
              LEFT JOIN hr.employee_bank_account ba ON ba.employee_id = e.employee_id
              LEFT JOIN hr.bank b ON b.bank_id = ba.bank_id
              LEFT JOIN hr.employee_address addr ON addr.employee_id = e.employee_id AND addr.address_type = 'CURRENT'
              LEFT JOIN hr.employee_emergency_contact em ON em.employee_id = e.employee_id
              %s
            """.formatted(EmployeeStatus.sqlCaseExpression(), sensitiveColumns, sensitiveJoin);
    }

    private List<AssignmentDto> loadAssignments(long employeeId) {
        return jdbc.query("""
            SELECT a.effective_from,
                   a.effective_to,
                   p.name_th AS title,
                   d.name_th AS division,
                   dep.name_th AS department,
                   a.is_current
              FROM hr.employee_assignment a
              LEFT JOIN hr.position p ON p.position_id = a.position_id
              LEFT JOIN hr.division d ON d.division_id = a.division_id
              LEFT JOIN hr.department dep ON dep.department_id = a.department_id
             WHERE a.employee_id = :employeeId
             ORDER BY a.effective_from DESC NULLS LAST
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new AssignmentDto(
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("title"),
            rs.getString("division"),
            rs.getString("department"),
            rs.getBoolean("is_current")
        ));
    }

    private List<SalaryHistoryDto> loadSalaryHistory(long employeeId) {
        return jdbc.query("""
            SELECT effective_date, old_amount, new_amount, note
              FROM hr.salary_history
             WHERE employee_id = :employeeId
             ORDER BY effective_date DESC NULLS LAST, salary_id DESC
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new SalaryHistoryDto(
            rs.getObject("effective_date", LocalDate.class),
            rs.getBigDecimal("old_amount"),
            rs.getBigDecimal("new_amount"),
            rs.getString("note")
        ));
    }

    private void insertCurrentAssignment(long employeeId, Long divisionId, Long departmentId, Long positionId, Long levelId, Long locationId, Long statusId, LocalDate from) {
        jdbc.update("""
            INSERT INTO hr.employee_assignment(employee_id, division_id, department_id, position_id, level_id, location_id, status_id, effective_from, is_current)
            VALUES (:employeeId, :divisionId, :departmentId, :positionId, :levelId, :locationId, :statusId, :from, TRUE)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("divisionId", divisionId)
            .addValue("departmentId", departmentId)
            .addValue("positionId", positionId)
            .addValue("levelId", levelId)
            .addValue("locationId", locationId)
            .addValue("statusId", statusId)
            .addValue("from", from));
    }

    /**
     * Keeps the assignment timeline consistent with the employee's current org snapshot.
     * When the snapshot moved, the open assignment is closed and a fresh current row is opened
     * so {@code is_current} always reflects the live division/department/position/level/location/status.
     */
    private void syncCurrentAssignment(long employeeId) {
        Map<String, Object> snapshot = jdbc.queryForMap("""
            SELECT division_id, department_id, position_id, level_id, location_id, status_id
              FROM hr.employee
             WHERE employee_id = :id
            """, Map.of("id", employeeId));

        List<Map<String, Object>> current = jdbc.queryForList("""
            SELECT division_id, department_id, position_id, level_id, location_id, status_id
              FROM hr.employee_assignment
             WHERE employee_id = :id AND is_current
             ORDER BY effective_from DESC NULLS LAST, assignment_id DESC
             LIMIT 1
            """, Map.of("id", employeeId));

        if (!current.isEmpty() && sameAssignment(snapshot, current.getFirst())) {
            return;
        }

        LocalDate today = LocalDate.now();
        jdbc.update("""
            UPDATE hr.employee_assignment
               SET is_current = FALSE,
                   effective_to = COALESCE(effective_to, :today)
             WHERE employee_id = :id AND is_current
            """, new MapSqlParameterSource().addValue("id", employeeId).addValue("today", today));

        insertCurrentAssignment(
            employeeId,
            toLong(snapshot.get("division_id")),
            toLong(snapshot.get("department_id")),
            toLong(snapshot.get("position_id")),
            toLong(snapshot.get("level_id")),
            toLong(snapshot.get("location_id")),
            toLong(snapshot.get("status_id")),
            today);
    }

    private static boolean sameAssignment(Map<String, Object> snapshot, Map<String, Object> assignment) {
        for (String column : new String[] {"division_id", "department_id", "position_id", "level_id", "location_id", "status_id"}) {
            if (!Objects.equals(toLong(snapshot.get(column)), toLong(assignment.get(column)))) {
                return false;
            }
        }
        return true;
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private void insertInitialSalary(long employeeId, LocalDate date, BigDecimal salary) {
        if (salary == null) {
            return;
        }
        jdbc.update("""
            INSERT INTO hr.salary_history(employee_id, effective_date, old_amount, new_amount, note)
            VALUES (:employeeId, :date, 0, :salary, 'เริ่มงาน')
            """, Map.of("employeeId", employeeId, "date", date, "salary", salary));
    }

    // ---- ล.ย.01 header PREFILL (owner decision #4, the read half) -------------------------------

    /**
     * What the ล.ย.01 header block is seeded with, straight out of the employee master.
     *
     * <p>Deliberately a flat carrier rather than {@link EmployeeDto}: that record is a 45-component
     * screen model whose {@code AddressDto} holds only four of the thirteen slots this form prints
     * ({@code line1} is a CONCAT of house_no/building/soi/road and cannot be taken apart again), and
     * whose {@code sensitive} block carries national ID, social-security number, bank details and
     * salary — none of which belong anywhere near this path. Selecting exactly the sixteen values
     * the form asks for is what keeps the blast radius equal to the feature.
     */
    public record LorYor01HeaderSource(
        String taxId, String firstNameTh, String lastNameTh, String maritalStatus,
        String building, String roomNo, String floor, String village, String houseNo, String moo,
        String soi, String junction, String road, String subDistrict, String district,
        String province, String postalCode
    ) {}

    /**
     * The identity block for ONE employee's own ล.ย.01, so they do not retype a 13-digit tax ID and
     * a thirteen-part address on every filing.
     *
     * <p>⚠️ <b>This is the only self-service read of {@code hr_restricted.employee_pii} in the
     * codebase.</b> Everywhere else that schema is reached, the caller is HR/CEO —
     * {@code EmployeeService#get} joins it only when {@code canSeeSensitiveEmployeeFields}, and
     * strips the whole {@code sensitive} block via {@code withoutSensitiveSelfServiceFields()} when
     * an employee looks at their own row. So an employee cannot read their own tax ID through
     * {@code GET /api/employees/{id}} today, and this method is a deliberate, narrow exception to
     * that: the data subject reading the single restricted field they are required to write onto
     * their own tax form. It selects {@code tax_id} and nothing else out of {@code employee_pii} —
     * national ID, social-security number, ss_hospital and provident_fund_no stay unreachable.
     *
     * <p><b>Scoping is the caller's job and the caller has exactly one option.</b> The only caller is
     * {@code TaxAllowanceDeclarationService#getOwn}, which passes {@code actor.employeeId()} — there
     * is no employeeId anywhere in that endpoint's request. The {@code WHERE} clause below is what
     * turns that into an enforced filter, and
     * {@code TaxAllowanceDeclarationScopeIntegrationTest#employeeCannotObtainAnotherEmployeesTaxIdThroughTheHeaderPrefill}
     * is what proves it against real Postgres rather than a mock.
     *
     * <p>Everything except {@code tax_id} here (names, สถานภาพ, the address) is already readable by
     * the employee about themselves through {@code GET /api/employees/{id}} — {@code
     * withoutSensitiveSelfServiceFields()} keeps {@code currentAddress} and {@code maritalStatus}.
     * Only the tax ID is a new access path.
     *
     * <p>The address row is {@code address_type = 'CURRENT'}, matching what the write-back half
     * ({@link #upsertCurrentAddressFromDeclaration}) targets — read and write must agree or an
     * approved correction would not come back on the next filing.
     *
     * @return empty when the employee row does not exist; a record with null fields when it exists
     *         but has no PII row and/or no CURRENT address (both are common — {@code employee_pii}
     *         is populated only where PII was actually captured).
     */
    public Optional<LorYor01HeaderSource> findLorYor01HeaderSource(long employeeId) {
        List<LorYor01HeaderSource> rows = jdbc.query("""
            SELECT pii.tax_id,
                   e.first_name_th,
                   e.last_name_th,
                   e.marital_status,
                   addr.building, addr.room_no, addr.floor, addr.village, addr.house_no, addr.moo,
                   addr.soi, addr.junction, addr.road, addr.subdistrict, addr.district,
                   addr.province, addr.postal_code
              FROM hr.employee e
              LEFT JOIN hr_restricted.employee_pii pii ON pii.employee_id = e.employee_id
              LEFT JOIN hr.employee_address addr
                     ON addr.employee_id = e.employee_id AND addr.address_type = 'CURRENT'
             WHERE e.employee_id = :employeeId
            """,
            new MapSqlParameterSource("employeeId", employeeId),
            (rs, rowNum) -> new LorYor01HeaderSource(
                rs.getString("tax_id"), rs.getString("first_name_th"), rs.getString("last_name_th"),
                rs.getString("marital_status"),
                rs.getString("building"), rs.getString("room_no"), rs.getString("floor"),
                rs.getString("village"), rs.getString("house_no"), rs.getString("moo"),
                rs.getString("soi"), rs.getString("junction"), rs.getString("road"),
                rs.getString("subdistrict"), rs.getString("district"), rs.getString("province"),
                rs.getString("postal_code")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ---- ล.ย.01 master write-back (owner decision #4, 2026-08-08) ------------------------------
    //
    // Called ONLY from TaxAllowanceDeclarationService#approve, i.e. after HR has confirmed a
    // declaration the employee signed. See that method for why approve and not apply.
    //
    // Every one of these preserves-on-null via COALESCE, matching #upsertCurrentAddress below: a
    // blank box on the form means "not answered", never "erase what HR already has". The cost is
    // that a genuine deletion (an employee who moved out of a named building) cannot be expressed
    // through this path and has to be made in the employee editor -- deliberate, because silently
    // destroying master data on a partially-filled form is the worse failure.

    /**
     * ที่อยู่ from an approved ล.ย.01 into the employee master, all 13 slots.
     *
     * <p>Targets {@code address_type = 'CURRENT'} because that is the row every reader already
     * joins to -- {@code PayrollRepository#findExportRows} (the statutory KBank/PND1/SSO files),
     * {@code EmployeeRepository#findById}, {@code LeaveRepository}. Writing 'REGISTERED' instead
     * would leave the correction invisible to every one of them.
     *
     * <p>Widths are guaranteed by V138: building/soi/road were VARCHAR(120) against the
     * declaration's VARCHAR(200) until then.
     */
    public void upsertCurrentAddressFromDeclaration(
        long employeeId, String houseNo, String building, String roomNo, String floor, String village,
        String moo, String soi, String junction, String road, String subDistrict, String district,
        String province, String postalCode) {
        jdbc.update("""
            INSERT INTO hr.employee_address(
                employee_id, address_type, house_no, building, room_no, floor, village, moo, soi,
                junction, road, subdistrict, district, province, postal_code)
            VALUES (:employeeId, 'CURRENT', :houseNo, :building, :roomNo, :floor, :village, :moo,
                :soi, :junction, :road, :subDistrict, :district, :province, :postalCode)
            ON CONFLICT (employee_id, address_type) DO UPDATE SET
                house_no    = COALESCE(EXCLUDED.house_no,    hr.employee_address.house_no),
                building    = COALESCE(EXCLUDED.building,    hr.employee_address.building),
                room_no     = COALESCE(EXCLUDED.room_no,     hr.employee_address.room_no),
                floor       = COALESCE(EXCLUDED.floor,       hr.employee_address.floor),
                village     = COALESCE(EXCLUDED.village,     hr.employee_address.village),
                moo         = COALESCE(EXCLUDED.moo,         hr.employee_address.moo),
                soi         = COALESCE(EXCLUDED.soi,         hr.employee_address.soi),
                junction    = COALESCE(EXCLUDED.junction,    hr.employee_address.junction),
                road        = COALESCE(EXCLUDED.road,        hr.employee_address.road),
                subdistrict = COALESCE(EXCLUDED.subdistrict, hr.employee_address.subdistrict),
                district    = COALESCE(EXCLUDED.district,    hr.employee_address.district),
                province    = COALESCE(EXCLUDED.province,    hr.employee_address.province),
                postal_code = COALESCE(EXCLUDED.postal_code, hr.employee_address.postal_code)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("houseNo", houseNo).addValue("building", building).addValue("roomNo", roomNo)
            .addValue("floor", floor).addValue("village", village).addValue("moo", moo)
            .addValue("soi", soi).addValue("junction", junction).addValue("road", road)
            .addValue("subDistrict", subDistrict).addValue("district", district)
            .addValue("province", province).addValue("postalCode", postalCode));
    }

    /**
     * เลขประจำตัวผู้เสียภาษีอากร from an approved ล.ย.01.
     *
     * <p>⚠️ <b>This is the FIRST production write to {@code hr_restricted}.</b> Every other access
     * to that schema in this codebase is a {@code LEFT JOIN} read
     * ({@code PayrollRepository#findExportRows}, {@code EmployeeRepository#findById}). V1 sketched a
     * separate {@code hr_app} role with no grant there, but those GRANT lines are COMMENTED OUT and
     * no such role exists -- Flyway creates and alters {@code hr_restricted} tables using the same
     * {@code spring.datasource} credentials the app runs under, so the app's role owns the schema.
     *
     * <p>An UPSERT, not an UPDATE: {@code employee_pii} has no row at all for many employees (it is
     * populated only where PII was actually captured), so an UPDATE would silently affect zero rows
     * and the tax ID would vanish with no error.
     */
    public void upsertTaxIdFromDeclaration(long employeeId, String taxId) {
        if (taxId == null) {
            return;
        }
        jdbc.update("""
            INSERT INTO hr_restricted.employee_pii(employee_id, tax_id)
            VALUES (:employeeId, :taxId)
            ON CONFLICT (employee_id) DO UPDATE
                SET tax_id = COALESCE(EXCLUDED.tax_id, hr_restricted.employee_pii.tax_id)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("taxId", taxId));
    }

    /** สถานภาพ from an approved ล.ย.01. No-ops on null — see the mapping in the calling service. */
    public void updateMaritalStatusFromDeclaration(long employeeId, String maritalStatus) {
        if (maritalStatus == null) {
            return;
        }
        jdbc.update("UPDATE hr.employee SET marital_status = :status WHERE employee_id = :employeeId",
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("status", maritalStatus));
    }

    private void upsertCurrentAddress(long employeeId, String line1, String phone) {
        if (line1 == null && phone == null) {
            return;
        }
        jdbc.update("""
            INSERT INTO hr.employee_address(employee_id, address_type, house_no, phone)
            VALUES (:employeeId, 'CURRENT', :line1, :phone)
            ON CONFLICT (employee_id, address_type) DO UPDATE
                SET house_no = COALESCE(EXCLUDED.house_no, hr.employee_address.house_no),
                    phone = COALESCE(EXCLUDED.phone, hr.employee_address.phone)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("line1", line1)
            .addValue("phone", phone));
    }

    private void upsertEmergencyContact(long employeeId, String name, String relationship, String phone) {
        if (name == null && relationship == null && phone == null) {
            return;
        }
        jdbc.update("""
            INSERT INTO hr.employee_emergency_contact(employee_id, full_name, relationship, phone)
            VALUES (:employeeId, :name, :relationship, :phone)
            ON CONFLICT (employee_id) DO UPDATE
                SET full_name = COALESCE(EXCLUDED.full_name, hr.employee_emergency_contact.full_name),
                    relationship = COALESCE(EXCLUDED.relationship, hr.employee_emergency_contact.relationship),
                    phone = COALESCE(EXCLUDED.phone, hr.employee_emergency_contact.phone),
                    updated_at = now()
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("name", name)
            .addValue("relationship", relationship)
            .addValue("phone", phone));
    }

    private static void addSet(List<String> sets, MapSqlParameterSource params, String column, String param, Object value) {
        if (value != null) {
            sets.add(column + " = :" + param);
            params.addValue(param, value);
        }
    }

    private static String genderCode(String label) {
        if (!hasText(label)) {
            return "U";
        }
        if (label.contains("หญิง") || label.equalsIgnoreCase("F")) {
            return "F";
        }
        if (label.contains("ชาย") || label.equalsIgnoreCase("M")) {
            return "M";
        }
        return "U";
    }

    private static String genderLabel(String code) {
        return switch (defaultText(code, "U")) {
            case "M" -> "ชาย";
            case "F" -> "หญิง";
            default -> "ไม่ระบุ";
        };
    }

    private static String payTypeLabel(String code) {
        return "D".equals(code) ? "รายวัน" : "รายเดือน";
    }

    /**
     * The display placeholder this class substitutes for a value it does not have. Emitted for an
     * employee with no English name ({@link #fullName}) and for missing emergency-contact fields.
     *
     * <p>Named because it also has to be RECOGNISED, not only produced -- see {@link #initials},
     * which used to consume its own sibling's output as if it were a name.
     */
    private static final String NO_VALUE = "-";

    private static String fullName(String first, String last) {
        return defaultText((defaultText(first, "") + " " + defaultText(last, "")).trim(), NO_VALUE);
    }

    /**
     * Two letters for an avatar: from the English name when there is one, otherwise from the Thai.
     *
     * <p><b>The English branch has to reject {@link #NO_VALUE}.</b> Its caller passes
     * {@code fullName(firstNameEn, lastNameEn)}, and that method returns the literal {@code "-"}
     * when both halves are blank. {@code hasText("-")} is TRUE -- it is a non-blank string -- so
     * the branch ran, took the first character, and returned {@code "-"}, leaving the Thai fallback
     * directly beneath it unreachable for exactly the employees who need it.
     *
     * <p>Nobody in the UAT seed has an English name, so every avatar in the app -- topbar, employee
     * list, profile header, approval queues -- rendered a dash for every user, on every page.
     */
    private static String initials(String englishName, String thaiName) {
        String fromEnglish = hasText(englishName) && !NO_VALUE.equals(englishName.trim())
            ? leadingLetters(englishName)
            : "";
        if (!fromEnglish.isEmpty()) {
            return fromEnglish;
        }
        String fromThai = leadingLetters(thaiName);
        return fromThai.isEmpty() ? "GL" : fromThai;
    }

    /** First letter of each of the first two words, upper-cased. Empty when there are no words. */
    private static String leadingLetters(String name) {
        if (!hasText(name)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isBlank()) {
                builder.append(Character.toUpperCase(part.charAt(0)));
            }
            if (builder.length() == 2) {
                break;
            }
        }
        return builder.toString();
    }

    private static NameParts splitName(String fullName) {
        if (!hasText(fullName)) {
            return new NameParts(null, null);
        }
        String[] parts = fullName.trim().split("\\s+", 2);
        return parts.length == 1 ? new NameParts(parts[0], "") : new NameParts(parts[0], parts[1]);
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record NameParts(String first, String last) {
    }
}
