package th.co.glr.hr.attendance;

import java.util.Map;
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

    private record DeviceRecord(long deviceId, String siteCode) {
    }
}
