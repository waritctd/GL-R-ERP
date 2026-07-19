package th.co.glr.hr.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDailyFilter;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.AttendanceEmployeeOption;
import th.co.glr.hr.attendance.daily.EmployeeDay;
import th.co.glr.hr.attendance.daily.UnmappedBadge;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

@Service
public class AttendanceService {
    private static final ZoneId DEFAULT_WORK_DATE_ZONE = ZoneId.of("Asia/Bangkok");
    // HR and executives (ceo) see all attendance company-wide.
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AttendanceRepository attendanceRepository;
    private final AttendanceDatParser datParser;
    private final AppProperties properties;
    private final AttendanceDailyService dailyService;

    public AttendanceService(
            AttendanceRepository attendanceRepository,
            AttendanceDatParser datParser,
            AppProperties properties,
            AttendanceDailyService dailyService) {
        this.attendanceRepository = attendanceRepository;
        this.datParser = datParser;
        this.properties = properties;
        this.dailyService = dailyService;
    }

    /** The day view, scoped to what the caller may see. */
    public List<AttendanceDailyDto> listDaily(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            Long requestedDivisionId) {
        LocalDate today = LocalDate.now(DEFAULT_WORK_DATE_ZONE);
        LocalDate effectiveTo = toDate == null ? today : toDate;
        LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfMonth(1) : fromDate;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "toDate must be on or after fromDate");
        }
        // employees × days: an unbounded range is a genuine latency/memory hazard company-wide.
        if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) >= AttendanceDailyService.MAX_RANGE_DAYS) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "Date range must be shorter than " + AttendanceDailyService.MAX_RANGE_DAYS + " days");
        }

        AttendanceScope scope = resolveScope(user, requestedEmployeeId, requestedDivisionId);
        return dailyService.list(new AttendanceDailyFilter(
            scope.employeeId(), scope.divisionId(), effectiveFrom, effectiveTo));
    }

    /**
     * Badges that scanned but match no employee. HR/CEO only — this is a data-repair queue, not
     * something an employee can act on.
     */
    public List<UnmappedBadge> listUnmappedBadges(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = LocalDate.now(DEFAULT_WORK_DATE_ZONE);
        LocalDate effectiveTo = toDate == null ? today : toDate;
        LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfMonth(1) : fromDate;
        return dailyService.listUnmappedBadges(effectiveFrom, effectiveTo);
    }

    /**
     * Employees the caller may filter by. Mirrors the overtime picker's self/direct-report/division
     * scope — {@code useHrData}'s employee list is HR-only, so a ฝ่าย manager would otherwise get a
     * one-entry picker that cannot pick any of their team.
     */
    public List<AttendanceEmployeeOption> listEmployeeOptions(UserPrincipal user) {
        boolean includeAll = canViewAll(user);
        Long managerDivisionId = user.manager() ? user.divisionId() : null;
        return dailyService.listEmployeeOptions(user.employeeId(), managerDivisionId, includeAll);
    }

    /** Re-derives daily rows for a range. HR/CEO only; also the historical backfill entry point. */
    public int recalculateDaily(LocalDate fromDate, LocalDate toDate, Long employeeId) {
        if (fromDate == null || toDate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fromDate and toDate are required");
        }
        if (toDate.isBefore(fromDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "toDate must be on or after fromDate");
        }
        return dailyService.recalculateRange(fromDate, toDate, employeeId);
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
        // Keep the day view current as scans arrive. Only the affected day is re-derived, and an
        // unmapped badge yields no pair, so this is a no-op until the badge is mapped.
        dailyService.recalculateForPunches(List.of(punchId));
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

        int insertedCount = attendanceRepository.batchInsertPunches(parseResult.punches());
        int skippedCount = parseResult.punches().size() - insertedCount;
        attendanceRepository.insertImportErrors(importId, parseResult.errors());
        attendanceRepository.updateImportCounts(
            importId,
            parseResult.rowCount(),
            insertedCount,
            skippedCount,
            parseResult.errors().size()
        );

        // Roll up once for the whole imported span, collapsing to distinct employee-days. Doing this
        // per punch instead would turn a 10,000-row import into 10,000 recalculations of a few
        // hundred days.
        recalculateImportedSpan(parseResult);

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

        AttendanceScope scope = resolveScope(user, requestedEmployeeId);

        return attendanceRepository.findPunches(new AttendancePunchFilter(
            scope.employeeId(), scope.divisionId(), effectiveFrom, effectiveTo, limit));
    }

    /**
     * Resolves which employees' attendance {@code user} may read.
     *
     * <p>The single source of truth for attendance authorization — punch list, day view, unmapped
     * badges and the employee picker all route through here. Keep it that way: a second copy of
     * these rules would drift, and a drift here leaks other people's attendance rather than
     * throwing.
     *
     * <ul>
     *   <li>hr/ceo — company-wide, and may request any employee.
     *   <li>ฝ่าย manager — their whole division. A requested employeeId narrows <em>within</em> the
     *       division because both predicates AND, so an out-of-division id matches nothing instead
     *       of leaking.
     *   <li>everyone else — forced to their own employee id; asking for someone else is a 403.
     * </ul>
     */
    AttendanceScope resolveScope(UserPrincipal user, Long requestedEmployeeId) {
        return resolveScope(user, requestedEmployeeId, null);
    }

    /**
     * As {@link #resolveScope(UserPrincipal, Long)}, plus an optional ฝ่าย narrowing.
     *
     * <p>{@code requestedDivisionId} is honoured <strong>only</strong> for hr/ceo, who can already
     * see every division — for them it is a convenience filter, not a grant. A ฝ่าย manager's
     * division is always taken from their own principal and the requested value is ignored
     * outright, so the parameter can never widen anyone's access.
     */
    AttendanceScope resolveScope(UserPrincipal user, Long requestedEmployeeId, Long requestedDivisionId) {
        if (VIEW_ALL_ROLES.contains(user.role())) {
            if (requestedEmployeeId == null && requestedDivisionId == null) {
                return AttendanceScope.all();
            }
            return new AttendanceScope(requestedEmployeeId, requestedDivisionId);
        }
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
        }
        if (user.manager() && user.divisionId() != null) {
            return AttendanceScope.division(requestedEmployeeId, user.divisionId());
        }
        if (requestedEmployeeId != null && !requestedEmployeeId.equals(user.employeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return AttendanceScope.self(user.employeeId());
    }

    /** True when the caller may see company-wide data (and so the unmapped-badge queue). */
    boolean canViewAll(UserPrincipal user) {
        return VIEW_ALL_ROLES.contains(user.role());
    }

    /**
     * Rolls up every employee-day covered by an import, in one pass.
     *
     * <p>Scoped to the span the file actually covers rather than the whole table. The set of
     * employee-days is resolved from the database rather than from the parsed rows because the .dat
     * carries badge codes, not employee ids — a badge only becomes a person after resolution.
     */
    private void recalculateImportedSpan(DatParseResult parseResult) {
        LocalDate from = null;
        LocalDate to = null;
        for (NormalizedAttendancePunch punch : parseResult.punches()) {
            LocalDate workDate = punch.workDate();
            if (from == null || workDate.isBefore(from)) {
                from = workDate;
            }
            if (to == null || workDate.isAfter(to)) {
                to = workDate;
            }
        }
        if (from != null) {
            dailyService.recalculateRange(from, to, null);
        }
    }

    /** Active scanners/locations available as an import source. */
    public List<AttendanceDeviceDto> listDevices() {
        return attendanceRepository.findActiveDevices();
    }

    /**
     * Backfills hr.employee.badge_card_no from a device-user export so historical card punches
     * resolve. Each row's device User ID (Pin) is matched to employee_code; rows without a card
     * number (fingerprint/PIN-only users) are skipped, and cards whose Pin matches no employee are
     * reported as unmatched. Once badge_card_no is set, the punch-history query resolves those rows.
     */
    @Transactional
    public AttendanceCardBackfillResponse backfillCardNumbers(AttendanceCardBackfillRequest request) {
        int updated = 0;
        int skipped = 0;
        int unmatched = 0;
        for (AttendanceCardBackfillRequest.CardMapping mapping : request.mappings()) {
            String employeeCode = mapping.employeeCode() == null ? "" : mapping.employeeCode().trim();
            String cardNo = mapping.cardNo() == null ? "" : mapping.cardNo().trim();
            if (employeeCode.isBlank() || cardNo.isBlank() || cardNo.equals("0")) {
                skipped++;
                continue;
            }
            if (attendanceRepository.updateEmployeeBadgeByCode(employeeCode, cardNo) > 0) {
                updated++;
            } else {
                unmatched++;
            }
        }
        if (updated > 0) {
            // Punches that were unmapped now resolve to a person. Without this their days would
            // stay permanently blank in the day view — the badge would be "fixed" while the
            // attendance it unlocked never appeared.
            dailyService.recalculateAllHistory();
        }
        return new AttendanceCardBackfillResponse(updated, skipped, unmatched);
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
