package th.co.glr.hr.overtime;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@Service
public class OvertimeService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo", "admin");
    private static final Set<String> ADMIN_OVERRIDE_ROLES = Set.of("admin");
    private static final int ATTENDANCE_LOOKAROUND_HOURS = 16;

    private final OvertimeRepository overtimeRepository;

    public OvertimeService(OvertimeRepository overtimeRepository) {
        this.overtimeRepository = overtimeRepository;
    }

    public List<OvertimeRequestDto> list(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            String requestedStatus) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate effectiveTo = toDate == null ? today : toDate;
        LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfMonth(1) : fromDate;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "toDate must be on or after fromDate");
        }

        Long employeeId = requestedEmployeeId;
        Long managerEmployeeId = null;
        if (!canViewAll(user)) {
            managerEmployeeId = requireEmployeeId(user);
            if (requestedEmployeeId != null && !canAccessEmployee(managerEmployeeId, requestedEmployeeId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
            }
        }

        return overtimeRepository.findRequests(new OvertimeFilter(
            employeeId,
            managerEmployeeId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus)
        ));
    }

    public List<OvertimeEmployeeOption> employeeOptions(UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        return overtimeRepository.findEmployeeOptions(actorEmployeeId, canViewAll(user));
    }

    @Transactional
    public OvertimeRequestDto submit(SubmitOvertimeRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        validatePlannedWindow(request);
        validateRetroactiveSubmission(request, employeeId, actorEmployeeId, user);

        int plannedMinutes = minutesBetween(request.plannedStartAt(), request.plannedEndAt());
        LocalDate payrollMonth = request.workDate().withDayOfMonth(1);
        OvertimeDayType dayType = parseDayType(request.dayType());
        long id = overtimeRepository.create(employeeId, actorEmployeeId, request, plannedMinutes, dayType, payrollMonth);
        return requireRequest(id);
    }

    @Transactional
    public OvertimeRequestDto approve(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        OvertimeRequestDto existing = requireRequest(id);
        requireDirectManager(existing.employeeId(), actorEmployeeId, user);
        requireStatus(existing, OvertimeStatus.SUBMITTED);

        OvertimeCalculation calculation = calculate(existing);
        int updated = overtimeRepository.approve(id, actorEmployeeId, calculation, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
        return requireRequest(id);
    }

    @Transactional
    public OvertimeRequestDto reject(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        OvertimeRequestDto existing = requireRequest(id);
        requireDirectManager(existing.employeeId(), actorEmployeeId, user);
        requireStatus(existing, OvertimeStatus.SUBMITTED);
        int updated = overtimeRepository.reject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
        return requireRequest(id);
    }

    @Transactional
    public OvertimeRequestDto cancel(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        OvertimeRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        boolean manager = isDirectManager(existing.employeeId(), actorEmployeeId) || canAdminOverride(user);
        if (!manager && existing.employeeId() != actorEmployeeId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!manager && !"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only submitted overtime requests can be cancelled by employees");
        }
        if (!"SUBMITTED".equals(existing.status()) && !"APPROVED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active overtime requests can be cancelled");
        }

        int updated = overtimeRepository.cancel(id, manager ? actorEmployeeId : null, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request can no longer be cancelled");
        }
        return requireRequest(id);
    }

    OvertimeCalculation calculate(OvertimeRequestDto request) {
        OffsetDateTime windowStart = request.plannedStartAt().minusHours(ATTENDANCE_LOOKAROUND_HOURS);
        OffsetDateTime windowEnd = request.plannedEndAt().plusHours(ATTENDANCE_LOOKAROUND_HOURS);
        return overtimeRepository.findAttendanceBounds(request.employeeId(), windowStart, windowEnd)
            .map(bounds -> calculate(request, bounds))
            .orElseGet(() -> new OvertimeCalculation(
                null,
                null,
                0,
                0,
                "No attendance punches were found around the approved overtime window."
            ));
    }

    private OvertimeCalculation calculate(OvertimeRequestDto request, OvertimeAttendanceBounds bounds) {
        OffsetDateTime actualStart = laterOf(request.plannedStartAt(), bounds.firstPunchAt());
        OffsetDateTime actualEnd = earlierOf(request.plannedEndAt(), bounds.lastPunchAt());
        int actualMinutes = actualEnd.toInstant().isAfter(actualStart.toInstant())
            ? minutesBetween(actualStart, actualEnd)
            : 0;
        if (actualMinutes == 0) {
            return new OvertimeCalculation(
                null,
                null,
                0,
                0,
                "Attendance punches were found, but they do not overlap the approved overtime window."
            );
        }
        return new OvertimeCalculation(
            actualStart,
            actualEnd,
            actualMinutes,
            actualMinutes,
            "Calculated from the overlap between approved overtime time and first/last attendance punch. No rounding applied."
        );
    }

    private long resolveTargetEmployee(Long requestedEmployeeId, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long targetEmployeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (targetEmployeeId != actorEmployeeId && !isDirectManager(targetEmployeeId, actorEmployeeId) && !canAdminOverride(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Employees can only request their own overtime");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!overtimeRepository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee not found");
        }
    }

    private void validatePlannedWindow(SubmitOvertimeRequest request) {
        int plannedMinutes = minutesBetween(request.plannedStartAt(), request.plannedEndAt());
        if (plannedMinutes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Overtime end time must be after start time");
        }
        LocalDate startWorkDate = request.plannedStartAt().atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        if (!startWorkDate.equals(request.workDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "workDate must match the planned start date");
        }
    }

    private void validateRetroactiveSubmission(
            SubmitOvertimeRequest request,
            long employeeId,
            long actorEmployeeId,
            UserPrincipal user) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (!request.workDate().isBefore(today)) {
            return;
        }
        if (employeeId == actorEmployeeId && !canAdminOverride(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Retroactive overtime must be submitted by the employee's direct manager");
        }
        if (!isDirectManager(employeeId, actorEmployeeId) && !canAdminOverride(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the employee's direct manager can submit retroactive overtime");
        }
    }

    private void requireDirectManager(long employeeId, long actorEmployeeId, UserPrincipal user) {
        if (!isDirectManager(employeeId, actorEmployeeId) && !canAdminOverride(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the employee's direct manager can review overtime");
        }
    }

    private boolean canAccessEmployee(long actorEmployeeId, long employeeId) {
        return actorEmployeeId == employeeId || isDirectManager(employeeId, actorEmployeeId);
    }

    private boolean isDirectManager(long employeeId, long actorEmployeeId) {
        return overtimeRepository.findEmployeeAccess(employeeId)
            .map(access -> access.managerEmployeeId() != null && access.managerEmployeeId() == actorEmployeeId)
            .orElse(false);
    }

    private OvertimeRequestDto requireRequest(long id) {
        return overtimeRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Overtime request not found"));
    }

    private void requireStatus(OvertimeRequestDto request, OvertimeStatus status) {
        if (!status.name().equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
    }

    private boolean canViewAll(UserPrincipal user) {
        return user != null && VIEW_ALL_ROLES.contains(user.role());
    }

    private boolean canAdminOverride(UserPrincipal user) {
        return user != null && ADMIN_OVERRIDE_ROLES.contains(user.role());
    }

    private Long requireEmployeeId(UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
        }
        return user.employeeId();
    }

    private OvertimeStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OvertimeStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid overtime status");
        }
    }

    private OvertimeDayType parseDayType(String value) {
        if (value == null || value.isBlank()) {
            return OvertimeDayType.WORKDAY;
        }
        try {
            return OvertimeDayType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid overtime day type");
        }
    }

    private int minutesBetween(OffsetDateTime start, OffsetDateTime end) {
        long minutes = Duration.between(start.toInstant(), end.toInstant()).toMinutes();
        if (minutes > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Overtime window is too long");
        }
        return (int) minutes;
    }

    private OffsetDateTime laterOf(OffsetDateTime first, OffsetDateTime second) {
        return first.toInstant().isAfter(second.toInstant()) ? first : second;
    }

    private OffsetDateTime earlierOf(OffsetDateTime first, OffsetDateTime second) {
        return first.toInstant().isBefore(second.toInstant()) ? first : second;
    }

    private String note(ReviewOvertimeRequest request) {
        return request == null || request.reviewerNote() == null || request.reviewerNote().isBlank()
            ? null
            : request.reviewerNote().trim();
    }
}
