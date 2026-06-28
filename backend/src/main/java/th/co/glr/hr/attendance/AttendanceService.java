package th.co.glr.hr.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

@Service
public class AttendanceService {
    private static final ZoneId DEFAULT_WORK_DATE_ZONE = ZoneId.of("Asia/Bangkok");

    private final AttendanceRepository attendanceRepository;
    private final AttendanceDatParser datParser;
    private final AppProperties properties;

    public AttendanceService(
            AttendanceRepository attendanceRepository,
            AttendanceDatParser datParser,
            AppProperties properties) {
        this.attendanceRepository = attendanceRepository;
        this.datParser = datParser;
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

    @Transactional
    public AttendanceImportResponse importDatFile(AttendanceDatImportRequest request, UserPrincipal user) {
        String siteCode = request.siteCode().trim().toUpperCase();
        String deviceCode = request.deviceCode().trim().toUpperCase();
        String fileHash = sha256Hex(request.content());
        AttendanceImportResponse duplicate = attendanceRepository.findImportByHash(fileHash).orElse(null);
        if (duplicate != null) {
            return duplicate;
        }

        DatParseResult parseResult = datParser.parse(request);
        long importId = attendanceRepository.createImportFile(
            siteCode,
            deviceCode,
            request.fileName().trim(),
            fileHash,
            request.content().getBytes(StandardCharsets.UTF_8).length,
            user.employeeId()
        );

        int insertedCount = 0;
        int skippedCount = 0;
        for (NormalizedAttendancePunch punch : parseResult.punches()) {
            Long punchId = attendanceRepository.upsertPunch(punch);
            if (punchId == null) {
                skippedCount++;
            } else {
                insertedCount++;
            }
        }
        attendanceRepository.insertImportErrors(importId, parseResult.errors());
        attendanceRepository.updateImportCounts(
            importId,
            parseResult.rowCount(),
            insertedCount,
            skippedCount,
            parseResult.errors().size()
        );

        return new AttendanceImportResponse(
            importId,
            "imported",
            parseResult.rowCount(),
            insertedCount,
            skippedCount,
            parseResult.errors().size()
        );
    }

    public List<AttendancePunchDto> listPunches(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            Integer requestedLimit) {
        LocalDate today = LocalDate.now(DEFAULT_WORK_DATE_ZONE);
        LocalDate effectiveTo = toDate == null ? today : toDate;
        LocalDate effectiveFrom = fromDate == null ? effectiveTo.minusDays(30) : fromDate;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "toDate must be on or after fromDate");
        }
        int limit = requestedLimit == null ? 500 : Math.max(1, Math.min(requestedLimit, 2_000));

        Long employeeId = requestedEmployeeId;
        if (!"hr".equals(user.role())) {
            if (user.employeeId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
            }
            if (requestedEmployeeId != null && !requestedEmployeeId.equals(user.employeeId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            employeeId = user.employeeId();
        }

        return attendanceRepository.findPunches(new AttendancePunchFilter(employeeId, effectiveFrom, effectiveTo, limit));
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

    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
