package th.co.glr.hr.attendance;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import th.co.glr.hr.common.ApiException;

@Repository
public class AttendanceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AttendanceRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
        StringBuilder sql = new StringBuilder("""
            SELECT p.punch_id,
                   p.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
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
              LEFT JOIN hr.employee e ON e.employee_id = p.employee_id
              LEFT JOIN hr.attendance_device d ON d.device_id = p.device_id
             WHERE p.work_date BETWEEN :fromDate AND :toDate
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", filter.fromDate())
            .addValue("toDate", filter.toDate())
            .addValue("limit", filter.limit());

        if (filter.employeeId() != null) {
            sql.append(" AND p.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }

        sql.append(" ORDER BY p.punch_time DESC, p.punch_id DESC LIMIT :limit");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new AttendancePunchDto(
            rs.getLong("punch_id"),
            nullableLong(rs, "employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
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
        return jdbc.query("""
            SELECT employee_id
              FROM hr.employee
             WHERE badge_card_no = :badgeCode
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
        } catch (RuntimeException exception) {
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
