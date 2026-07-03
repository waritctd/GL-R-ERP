package th.co.glr.hr.attendance;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import th.co.glr.hr.common.ApiException;

@Repository
public class AttendanceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AttendanceRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Credentials for authenticating an inbound punch — returns empty when the device_code is unknown. */
    public Optional<AttendanceDeviceCredential> findDeviceCredential(String deviceCode) {
        return jdbc.query("""
            SELECT site_code, is_active, agent_token_hash
              FROM hr.attendance_device
             WHERE device_code = :deviceCode
            """, Map.of("deviceCode", deviceCode),
            (rs, rowNum) -> new AttendanceDeviceCredential(
                rs.getString("site_code"),
                rs.getBoolean("is_active"),
                rs.getString("agent_token_hash")))
            .stream()
            .findFirst();
    }

    /** Stores a rotated per-device token hash; returns rows updated (0 = unknown device_code). */
    public int updateAgentTokenHash(String deviceCode, String tokenHash, java.time.OffsetDateTime rotatedAt) {
        return jdbc.update("""
            UPDATE hr.attendance_device
               SET agent_token_hash = :hash,
                   agent_token_rotated_at = :rotatedAt,
                   updated_at = now()
             WHERE device_code = :deviceCode
            """, new MapSqlParameterSource()
                .addValue("deviceCode", deviceCode)
                .addValue("hash", tokenHash)
                .addValue("rotatedAt", rotatedAt));
    }

    public Long upsertPunch(NormalizedAttendancePunch punch) {
        DeviceRecord device = findDevice(punch.deviceCode());
        return upsertPunch(punch, device);
    }

    private Long upsertPunch(NormalizedAttendancePunch punch, DeviceRecord device) {
        if (!device.siteCode().equals(punch.siteCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attendance device does not belong to requested site");
        }
        Long employeeId = findEmployeeIdByBadge(punch.badgeCode());
        String rawPayloadJson = toJson(punch.rawPayload());

        return jdbc.query("""
            INSERT INTO hr.attendance_punch (
                device_id, site_code, employee_id, badge_code, punch_time, work_date,
                device_status, punch_state, work_code, reserved_value,
                punch_source, ingest_method, raw_payload
            )
            VALUES (
                :deviceId, :siteCode, :employeeId, :badgeCode, :punchTime, :workDate,
                :deviceStatus, :punchState, :workCode, :reservedValue,
                :punchSource, :ingestMethod, CAST(:rawPayload AS jsonb)
            )
            ON CONFLICT (device_id, badge_code, punch_time) WHERE device_id IS NOT NULL
            DO NOTHING
            RETURNING punch_id
            """, new MapSqlParameterSource()
            .addValue("deviceId", device.deviceId())
            .addValue("siteCode", punch.siteCode())
            .addValue("employeeId", employeeId)
            .addValue("badgeCode", punch.badgeCode())
            .addValue("punchTime", punch.punchTime())
            .addValue("workDate", punch.workDate())
            .addValue("deviceStatus", punch.deviceStatus())
            .addValue("punchState", punch.punchState())
            .addValue("workCode", punch.workCode())
            .addValue("reservedValue", punch.reservedValue())
            .addValue("punchSource", punch.punchSource())
            .addValue("ingestMethod", punch.ingestMethod())
            .addValue("rawPayload", rawPayloadJson),
            (rs, rowNum) -> rs.getLong("punch_id"))
            .stream()
            .findFirst()
            .orElse(null);
    }

    // Chunk size for the bulk import: keeps each multi-row INSERT well under
    // PostgreSQL's 65535-parameter limit (13 params/row) and the statement a
    // reasonable size.
    private static final int IMPORT_INSERT_CHUNK = 500;

    /**
     * Bulk-inserts a whole .dat import's punches. Resolves the device once and
     * every employee in a single query, then inserts in chunked multi-row
     * statements instead of one round trip per punch — the per-row path is far
     * too slow for tens of thousands of rows over a high-latency DB link.
     *
     * @return the number of newly inserted rows (conflicts / already-present
     *         punches are skipped via ON CONFLICT DO NOTHING).
     */
    public int batchInsertPunches(List<NormalizedAttendancePunch> punches) {
        if (punches.isEmpty()) {
            return 0;
        }
        DeviceRecord device = findDevice(punches.get(0).deviceCode());
        for (NormalizedAttendancePunch punch : punches) {
            if (!device.siteCode().equals(punch.siteCode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Attendance device does not belong to requested site");
            }
        }
        Map<String, Long> employeeByBadge = findEmployeeIdsByBadges(
            punches.stream().map(NormalizedAttendancePunch::badgeCode).collect(Collectors.toSet()));

        int inserted = 0;
        for (int start = 0; start < punches.size(); start += IMPORT_INSERT_CHUNK) {
            List<NormalizedAttendancePunch> chunk =
                punches.subList(start, Math.min(start + IMPORT_INSERT_CHUNK, punches.size()));
            inserted += insertPunchChunk(chunk, device, employeeByBadge);
        }
        return inserted;
    }

    private int insertPunchChunk(
            List<NormalizedAttendancePunch> chunk,
            DeviceRecord device,
            Map<String, Long> employeeByBadge) {
        StringBuilder sql = new StringBuilder(
            "INSERT INTO hr.attendance_punch ("
            + "device_id, site_code, employee_id, badge_code, punch_time, work_date, "
            + "device_status, punch_state, work_code, reserved_value, "
            + "punch_source, ingest_method, raw_payload) VALUES ");
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("deviceId", device.deviceId());
        for (int i = 0; i < chunk.size(); i++) {
            NormalizedAttendancePunch punch = chunk.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:deviceId, :siteCode").append(i)
               .append(", :employeeId").append(i)
               .append(", :badgeCode").append(i)
               .append(", :punchTime").append(i)
               .append(", :workDate").append(i)
               .append(", :deviceStatus").append(i)
               .append(", :punchState").append(i)
               .append(", :workCode").append(i)
               .append(", :reservedValue").append(i)
               .append(", :punchSource").append(i)
               .append(", :ingestMethod").append(i)
               .append(", CAST(:rawPayload").append(i).append(" AS jsonb))");
            params.addValue("siteCode" + i, punch.siteCode());
            params.addValue("employeeId" + i, employeeByBadge.get(punch.badgeCode()));
            params.addValue("badgeCode" + i, punch.badgeCode());
            params.addValue("punchTime" + i, punch.punchTime());
            params.addValue("workDate" + i, punch.workDate());
            params.addValue("deviceStatus" + i, punch.deviceStatus());
            params.addValue("punchState" + i, punch.punchState());
            params.addValue("workCode" + i, punch.workCode());
            params.addValue("reservedValue" + i, punch.reservedValue());
            params.addValue("punchSource" + i, punch.punchSource());
            params.addValue("ingestMethod" + i, punch.ingestMethod());
            params.addValue("rawPayload" + i, toJson(punch.rawPayload()));
        }
        sql.append(" ON CONFLICT (device_id, badge_code, punch_time) WHERE device_id IS NOT NULL")
           .append(" DO NOTHING RETURNING punch_id");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong("punch_id")).size();
    }

    private Map<String, Long> findEmployeeIdsByBadges(Set<String> badges) {
        if (badges.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> byBadge = new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (badge_card_no) badge_card_no, employee_id
              FROM hr.employee
             WHERE badge_card_no IN (:badges)
             ORDER BY badge_card_no, is_active DESC, employee_id
            """, Map.of("badges", badges),
            rs -> { byBadge.put(rs.getString("badge_card_no"), rs.getLong("employee_id")); });
        return byBadge;
    }

    public Optional<AttendanceImportResponse> findImportByHash(String fileHash) {
        return jdbc.query("""
            SELECT import_id, row_count, inserted_punch_count, skipped_punch_count, error_count
              FROM hr.attendance_import_file
             WHERE file_hash = :fileHash
            """, Map.of("fileHash", fileHash), (rs, rowNum) -> new AttendanceImportResponse(
                rs.getLong("import_id"),
                "duplicate_file",
                rs.getInt("row_count"),
                rs.getInt("inserted_punch_count"),
                rs.getInt("skipped_punch_count"),
                rs.getInt("error_count")
            ))
            .stream()
            .findFirst();
    }

    public long createImportFile(
            String siteCode,
            String deviceCode,
            String sourceFileName,
            String fileHash,
            long fileSizeBytes,
            Long importedByEmployeeId) {
        DeviceRecord device = findDevice(deviceCode);
        if (!device.siteCode().equals(siteCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attendance device does not belong to requested site");
        }
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.attendance_import_file (
                site_code, device_id, source_file_name, file_hash, file_size_bytes, imported_by_employee_id
            )
            VALUES (
                :siteCode, :deviceId, :sourceFileName, :fileHash, :fileSizeBytes, :importedByEmployeeId
            )
            RETURNING import_id
            """, new MapSqlParameterSource()
            .addValue("siteCode", siteCode)
            .addValue("deviceId", device.deviceId())
            .addValue("sourceFileName", sourceFileName)
            .addValue("fileHash", fileHash)
            .addValue("fileSizeBytes", fileSizeBytes)
            .addValue("importedByEmployeeId", importedByEmployeeId), Long.class);
        return id == null ? 0 : id;
    }

    public void updateImportCounts(long importId, int rowCount, int insertedCount, int skippedCount, int errorCount) {
        jdbc.update("""
            UPDATE hr.attendance_import_file
               SET row_count = :rowCount,
                   inserted_punch_count = :insertedCount,
                   skipped_punch_count = :skippedCount,
                   error_count = :errorCount
             WHERE import_id = :importId
            """, new MapSqlParameterSource()
            .addValue("importId", importId)
            .addValue("rowCount", rowCount)
            .addValue("insertedCount", insertedCount)
            .addValue("skippedCount", skippedCount)
            .addValue("errorCount", errorCount));
    }

    public void insertImportErrors(long importId, List<AttendanceImportErrorRecord> errors) {
        if (errors.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = errors.stream()
            .map(error -> new MapSqlParameterSource()
                .addValue("importId", importId)
                .addValue("lineNo", error.lineNo())
                .addValue("rawLine", error.rawLine())
                .addValue("errorCode", error.errorCode())
                .addValue("errorMessage", error.errorMessage()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
            INSERT INTO hr.attendance_import_error (
                import_id, line_no, raw_line, error_code, error_message
            )
            VALUES (
                :importId, :lineNo, :rawLine, :errorCode, :errorMessage
            )
            """, batch);
    }

    public List<AttendancePunchDto> findPunches(AttendancePunchFilter filter) {
        // Resolve the employee by the stored employee_id when present, otherwise fall back to matching
        // the raw badge_code against either the card number OR the employee_code. Card readers report the
        // card number while fingerprint/PIN punches report the employee_code, so a single-column match
        // leaves half the punches unmapped — COALESCE here keeps historical rows resolving too.
        StringBuilder sql = new StringBuilder("""
            SELECT p.punch_id,
                   COALESCE(p.employee_id, e.employee_id) AS employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   e.nickname AS nick_name,
                   pos.name_th AS position_th,
                   e.badge_card_no,
                   p.badge_code,
                   p.punch_time,
                   p.work_date,
                   p.site_code,
                   d.device_code,
                   d.device_name,
                   p.device_status,
                   p.punch_state,
                   p.work_code,
                   p.reserved_value,
                   p.punch_source,
                   p.ingest_method,
                   p.created_at
              FROM hr.attendance_punch p
              LEFT JOIN hr.employee e ON e.employee_id = COALESCE(
                       p.employee_id,
                       (SELECT em.employee_id
                          FROM hr.employee em
                         WHERE em.badge_card_no = p.badge_code
                            OR em.employee_code = p.badge_code
                         ORDER BY em.is_active DESC, em.employee_id
                         LIMIT 1))
              LEFT JOIN hr.position pos ON pos.position_id = e.position_id
              LEFT JOIN hr.attendance_device d ON d.device_id = p.device_id
             WHERE p.work_date BETWEEN :fromDate AND :toDate
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", filter.fromDate())
            .addValue("toDate", filter.toDate())
            .addValue("limit", filter.limit());

        if (filter.employeeId() != null) {
            sql.append(" AND COALESCE(p.employee_id, e.employee_id) = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }
        if (filter.divisionId() != null) {
            sql.append(" AND e.division_id = :divisionId");
            params.addValue("divisionId", filter.divisionId());
        }

        sql.append(" ORDER BY p.punch_time DESC, p.punch_id DESC LIMIT :limit");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new AttendancePunchDto(
            rs.getLong("punch_id"),
            nullableLong(rs, "employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("nick_name"),
            rs.getString("position_th"),
            rs.getString("badge_card_no"),
            rs.getString("badge_code"),
            rs.getObject("punch_time", java.time.OffsetDateTime.class),
            rs.getObject("work_date", java.time.LocalDate.class),
            rs.getString("site_code"),
            rs.getString("device_code"),
            rs.getString("device_name"),
            rs.getShort("device_status"),
            rs.getShort("punch_state"),
            rs.getString("work_code"),
            rs.getString("reserved_value"),
            rs.getString("punch_source"),
            rs.getString("ingest_method"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
        ));
    }

    /**
     * Points an employee's badge_card_no at the card number the device holds for them, matched by
     * employee_code = device User ID (Pin). Returns rows updated (0 = no employee with that code).
     */
    public int updateEmployeeBadgeByCode(String employeeCode, String cardNo) {
        return jdbc.update("""
            UPDATE hr.employee
               SET badge_card_no = :cardNo,
                   updated_at = now()
             WHERE employee_code = :employeeCode
            """, new MapSqlParameterSource()
            .addValue("employeeCode", employeeCode)
            .addValue("cardNo", cardNo));
    }

    /** Active scanners with their site, for the import picker. Ordered site then device for a stable list. */
    public List<AttendanceDeviceDto> findActiveDevices() {
        return jdbc.query("""
            SELECT d.device_code,
                   d.device_name,
                   d.site_code,
                   s.name AS site_name
              FROM hr.attendance_device d
              JOIN hr.attendance_site s ON s.site_code = d.site_code
             WHERE d.is_active = TRUE
               AND s.is_active = TRUE
             ORDER BY s.name, d.device_name
            """, (rs, rowNum) -> new AttendanceDeviceDto(
                rs.getString("device_code"),
                rs.getString("device_name"),
                rs.getString("site_code"),
                rs.getString("site_name")));
    }

    private DeviceRecord findDevice(String deviceCode) {
        try {
            return jdbc.queryForObject("""
                SELECT device_id, site_code
                  FROM hr.attendance_device
                 WHERE device_code = :deviceCode
                   AND is_active = TRUE
                """, Map.of("deviceCode", deviceCode),
                (rs, rowNum) -> new DeviceRecord(rs.getLong("device_id"), rs.getString("site_code")));
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attendance device is not registered");
        }
    }

    private Long findEmployeeIdByBadge(String badgeCode) {
        // The device reports the card number for card punches and the employee_code for fingerprint/PIN
        // punches; match either so both resolve to the right employee.
        return jdbc.query("""
            SELECT employee_id
              FROM hr.employee
             WHERE badge_card_no = :badgeCode
                OR employee_code = :badgeCode
             ORDER BY is_active DESC, employee_id
             LIMIT 1
            """, Map.of("badgeCode", badgeCode), (rs, rowNum) -> rs.getLong("employee_id"))
            .stream()
            .findFirst()
            .orElse(null);
    }

    private String toJson(Map<String, Object> rawPayload) {
        try {
            return objectMapper.writeValueAsString(rawPayload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid attendance raw payload");
        }
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record DeviceRecord(long deviceId, String siteCode) {
    }
}
