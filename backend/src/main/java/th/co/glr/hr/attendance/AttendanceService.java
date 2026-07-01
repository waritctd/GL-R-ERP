package th.co.glr.hr.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

@Service
public class AttendanceService {
    private static final ZoneId DEFAULT_WORK_DATE_ZONE = ZoneId.of("Asia/Bangkok");
    // HR and executives (ceo) see all attendance company-wide; admin for support access.
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo", "admin");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
        String siteCode = request.siteCode().trim().toUpperCase();
        String deviceCode = request.deviceCode().trim().toUpperCase();
        authenticateDevice(siteCode, deviceCode, agentToken);
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
        Long divisionId = null;
        if (!VIEW_ALL_ROLES.contains(user.role())) {
            if (user.employeeId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
            }
            if (user.manager() && user.divisionId() != null) {
                // ฝ่าย managers see their whole division; a requested employeeId narrows within it
                // (an out-of-division employeeId simply matches nothing, so no data leaks).
                divisionId = user.divisionId();
            } else {
                if (requestedEmployeeId != null && !requestedEmployeeId.equals(user.employeeId())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
                }
                employeeId = user.employeeId();
            }
        }

        return attendanceRepository.findPunches(
            new AttendancePunchFilter(employeeId, divisionId, effectiveFrom, effectiveTo, limit));
    }

    /**
     * Issues (or rotates) the agent token for one device. Returns the plaintext token once; only its
     * SHA-256 hash is stored. HR-only mutation — authorization is enforced at the controller.
     */
    @Transactional
    public RotateAgentTokenResponse rotateDeviceToken(String rawDeviceCode) {
        if (rawDeviceCode == null || rawDeviceCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Device code is required");
        }
        String deviceCode = rawDeviceCode.trim().toUpperCase();
        String token = generateAgentToken();
        OffsetDateTime rotatedAt = OffsetDateTime.now();
        int updated = attendanceRepository.updateAgentTokenHash(deviceCode, sha256Hex(token), rotatedAt);
        if (updated != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attendance device is not registered");
        }
        return new RotateAgentTokenResponse(deviceCode, token, rotatedAt);
    }

    /**
     * Authenticates an inbound punch against the presenting device's own token, scoping the accepted
     * site/device to that credential. Devices not yet provisioned with a per-device token fall back to
     * the legacy shared token during rollout; unset that shared token to enforce per-device-only.
     */
    private void authenticateDevice(String siteCode, String deviceCode, String agentToken) {
        AttendanceDeviceCredential device = attendanceRepository.findDeviceCredential(deviceCode).orElse(null);
        if (device == null || !device.active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unknown or inactive attendance device");
        }
        if (!device.siteCode().equals(siteCode)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Attendance device does not belong to requested site");
        }
        if (device.tokenHash() != null) {
            if (agentToken == null || agentToken.isBlank()
                || !constantTimeEquals(device.tokenHash(), sha256Hex(agentToken.trim()))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid attendance agent token");
            }
            return;
        }
        // Transitional fallback: no per-device token issued yet — accept the legacy shared token.
        String shared = properties.getAttendance().getAgentToken();
        if (shared == null || shared.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance device has no agent token; rotate one to enable punches");
        }
        if (agentToken == null || agentToken.isBlank() || !constantTimeEquals(shared, agentToken.trim())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid attendance agent token");
        }
    }

    private String generateAgentToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
