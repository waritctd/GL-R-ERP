package th.co.glr.hr.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

@Service
public class AttendanceService {
    private static final ZoneId DEFAULT_WORK_DATE_ZONE = ZoneId.of("Asia/Bangkok");

    private final AttendanceRepository attendanceRepository;
    private final AppProperties properties;

    public AttendanceService(AttendanceRepository attendanceRepository, AppProperties properties) {
        this.attendanceRepository = attendanceRepository;
        this.properties = properties;
    }

    @Transactional
    public AttendancePunchResponse receivePunch(AttendancePunchRequest request, String agentToken) {
        requireAgentToken(agentToken);
        NormalizedAttendancePunch punch = normalize(request);
        Long punchId = attendanceRepository.upsertPunch(punch);
        if (punchId == null) {
            return new AttendancePunchResponse(null, false, "duplicate");
        }
        return new AttendancePunchResponse(punchId, true, "inserted");
    }

    private void requireAgentToken(String agentToken) {
        String expected = properties.getAttendance().getAgentToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Attendance agent token is not configured");
        }
        if (agentToken == null || agentToken.isBlank() || !constantTimeEquals(expected, agentToken.trim())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid attendance agent token");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    private NormalizedAttendancePunch normalize(AttendancePunchRequest request) {
        LocalDate workDate = request.workDate() == null
            ? request.punchTime().atZoneSameInstant(DEFAULT_WORK_DATE_ZONE).toLocalDate()
            : request.workDate();
        return new NormalizedAttendancePunch(
            request.siteCode().trim().toUpperCase(),
            request.deviceCode().trim().toUpperCase(),
            request.badgeCode().trim(),
            request.punchTime(),
            workDate,
            request.deviceStatus() == null ? (short) 1 : request.deviceStatus(),
            request.punchState() == null ? (short) 0 : request.punchState(),
            blankDefault(request.workCode(), "0"),
            blankDefault(request.reservedValue(), "0"),
            blankDefault(request.punchSource(), "BIOMETRIC"),
            blankDefault(request.ingestMethod(), "LIVE_CAPTURE"),
            request.rawPayload() == null ? java.util.Map.of() : request.rawPayload()
        );
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
