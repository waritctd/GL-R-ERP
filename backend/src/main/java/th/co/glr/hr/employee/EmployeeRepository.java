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
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee name is required");
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
            throw new ApiException(HttpStatus.NOT_FOUND, "Employee not found");
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
        // Standing withholding override. Mirrors director_remuneration/current_salary: addSet only emits
        // a SET when the value is non-null, so a null request field leaves the stored value untouched
        // (same "null = don't change" semantics the rest of update() uses).
        addSet(sets, params, "withholding_tax_override", "withholdingTaxOverride", request.withholdingTaxOverride());
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
            new EmergencyContactDto(defaultText(rs.getString("emergency_name"), "-"),
                defaultText(rs.getString("emergency_relationship"), "-"), defaultText(rs.getString("emergency_phone"), "-")),
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

    private static String fullName(String first, String last) {
        return defaultText((defaultText(first, "") + " " + defaultText(last, "")).trim(), "-");
    }

    private static String initials(String englishName, String thaiName) {
        if (hasText(englishName)) {
            String[] parts = englishName.trim().split("\\s+");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (!part.isBlank()) {
                    builder.append(Character.toUpperCase(part.charAt(0)));
                }
                if (builder.length() == 2) {
                    break;
                }
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }
        String fallback = defaultText(thaiName, "GL").replaceAll("\\s+", "");
        return fallback.length() <= 2 ? fallback : fallback.substring(0, 2);
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
