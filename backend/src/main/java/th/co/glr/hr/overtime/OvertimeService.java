package th.co.glr.hr.overtime;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.EmployeeDay;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

@Service
public class OvertimeService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");
    private static final int ATTENDANCE_LOOKAROUND_HOURS = 16;
    /** A backdated request has to say why it is backdated, not just "OT". */
    private static final int BACKDATED_REASON_MIN_LENGTH = 20;

    private final OvertimeRepository overtimeRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final AttendanceDailyService attendanceDailyService;

    public OvertimeService(
            OvertimeRepository overtimeRepository,
            AuditService auditService,
            NotificationService notificationService,
            AppProperties appProperties,
            AttendanceDailyService attendanceDailyService) {
        this.overtimeRepository = overtimeRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.appProperties = appProperties;
        this.attendanceDailyService = attendanceDailyService;
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
        Long managerDivisionId = null;
        if (!canViewAll(user)) {
            managerEmployeeId = requireEmployeeId(user);
            managerDivisionId = user.manager() ? user.divisionId() : null;
            if (requestedEmployeeId != null && !canAccessEmployee(user, requestedEmployeeId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
            }
        }

        return overtimeRepository.findRequests(new OvertimeFilter(
            employeeId,
            managerEmployeeId,
            managerDivisionId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus)
        ));
    }

    public List<OvertimeEmployeeOption> employeeOptions(UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        Long managerDivisionId = user.manager() ? user.divisionId() : null;
        return overtimeRepository.findEmployeeOptions(actorEmployeeId, managerDivisionId, canViewAll(user));
    }

    @Transactional
    public OvertimeRequestDto submit(SubmitOvertimeRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        validatePlannedWindow(request);
        validateRetroactiveWindow(request);

        int plannedMinutes = minutesBetween(request.plannedStartAt(), request.plannedEndAt());
        LocalDate payrollMonth = request.workDate().withDayOfMonth(1);
        OvertimeDayType dayType = parseDayType(request.dayType());
        long id = overtimeRepository.create(employeeId, actorEmployeeId, request, plannedMinutes, dayType, payrollMonth);
        OvertimeRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_OVERTIME_REQUEST", "overtime_request", id, null, created);
        notifySubmitted(created);
        return created;
    }

    @Transactional
    public OvertimeRequestDto approve(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        OvertimeRequestDto existing = requireRequest(id);
        OvertimeStatus status = parseStatus(existing.status());
        if (status == OvertimeStatus.SUBMITTED) {
            return managerApprove(id, request, user, actorEmployeeId, existing);
        }
        if (status == OvertimeStatus.MANAGER_APPROVED) {
            return ceoApprove(id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
    }

    private OvertimeRequestDto managerApprove(
            long id,
            ReviewOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireManager(existing.employeeId(), user);
        requirePayrollMonthOpen(existing.workDate());

        OvertimeCalculation calculation = calculate(existing);
        BigDecimal salaryBasis = overtimeRepository.findSalaryBasisAsOf(existing.employeeId(), existing.workDate());
        int updated = overtimeRepository.managerApprove(id, actorEmployeeId, calculation, salaryBasis, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "MANAGER_APPROVE_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyManagerApproved(after);
        return after;
    }

    private OvertimeRequestDto ceoApprove(
            long id,
            ReviewOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireCeo(user);
        requirePayrollMonthOpen(existing.workDate());

        int updated = overtimeRepository.ceoApprove(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_APPROVE_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyCeoApproved(after);
        syncAttendanceDay(after);
        return after;
    }

    /**
     * Re-derives the attendance day so its overtime minutes and badge match the request's new state.
     *
     * <p>Called only where a request enters or leaves {@code APPROVED} — CEO approval and
     * cancellation. Rejection needs no sync: a request can only be rejected from SUBMITTED or
     * MANAGER_APPROVED, neither of which ever contributed minutes, so the stored figure is already
     * correct.
     */
    private void syncAttendanceDay(OvertimeRequestDto request) {
        attendanceDailyService.recalculate(
            new EmployeeDay(request.employeeId(), request.workDate()));
    }

    @Transactional
    public OvertimeRequestDto reject(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        OvertimeRequestDto existing = requireRequest(id);
        OvertimeStatus status = parseStatus(existing.status());
        if (status == OvertimeStatus.SUBMITTED) {
            return managerReject(id, request, user, actorEmployeeId, existing);
        }
        if (status == OvertimeStatus.MANAGER_APPROVED) {
            return ceoReject(id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
    }

    private OvertimeRequestDto managerReject(
            long id,
            ReviewOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireManager(existing.employeeId(), user);
        int updated = overtimeRepository.reject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "REJECT_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyRejected(after);
        return after;
    }

    private OvertimeRequestDto ceoReject(
            long id,
            ReviewOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireCeo(user);
        int updated = overtimeRepository.ceoReject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request has already been reviewed");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_REJECT_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyRejected(after);
        return after;
    }

    @Transactional
    public OvertimeRequestDto cancel(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        OvertimeRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        boolean manager = managesEmployee(existing.employeeId(), user);
        if (!manager && existing.employeeId() != actorEmployeeId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!manager && !"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only submitted overtime requests can be cancelled by employees");
        }
        if (!"SUBMITTED".equals(existing.status())
                && !"MANAGER_APPROVED".equals(existing.status())
                && !"APPROVED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active overtime requests can be cancelled");
        }

        int updated = overtimeRepository.cancel(id, manager ? actorEmployeeId : null, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Overtime request can no longer be cancelled");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CANCEL_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        // Cancelling an already-APPROVED request removes minutes the day had been credited with.
        syncAttendanceDay(after);
        return after;
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
        if (targetEmployeeId != actorEmployeeId && !managesEmployee(targetEmployeeId, user)) {
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

    /**
     * Bounds a retroactive request. Advance notice was removed on CEO instruction — anyone may file
     * for today or for a past date, including for themselves — so the only remaining limits are
     * that the claim must still be payable and that the employee has explained why it is late.
     */
    private void validateRetroactiveWindow(SubmitOvertimeRequest request) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (!request.workDate().isBefore(today)) {
            return;
        }
        int windowDays = Math.max(0, appProperties.getOvertime().getRetroactiveWindowDays());
        if (request.workDate().isBefore(today.minusDays(windowDays))) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "Overtime can only be filed up to " + windowDays + " days after the work date"
            );
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < BACKDATED_REASON_MIN_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "A retroactive overtime request must explain clearly why it is late (at least "
                    + BACKDATED_REASON_MIN_LENGTH + " characters)"
            );
        }
        requirePayrollMonthOpen(request.workDate());
    }

    /**
     * Refuses to touch a work date whose payroll month has already been processed.
     *
     * <p>Payroll derives overtime by {@code payroll_month} and a processed period is inserted once,
     * so a request that lands in a closed month is approved and then never paid — silently. This
     * runs at submit and again at each approval stage, because a request filed before the cut-off
     * can still be approved after it.
     */
    private void requirePayrollMonthOpen(LocalDate workDate) {
        LocalDate payrollMonth = workDate.withDayOfMonth(1);
        if (overtimeRepository.payrollMonthProcessed(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "Payroll for " + payrollMonth.getYear() + "-" + payrollMonth.getMonthValue()
                    + " has already been processed; this overtime can no longer be paid"
            );
        }
    }

    private void requireManager(long employeeId, UserPrincipal user) {
        if (!managesEmployee(employeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the employee's manager can review overtime");
        }
    }

    private void requireCeo(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the CEO can approve manager-approved overtime");
        }
    }

    private boolean canAccessEmployee(UserPrincipal user, long employeeId) {
        return (user.employeeId() != null && user.employeeId() == employeeId) || managesEmployee(employeeId, user);
    }

    /**
     * True when {@code user} manages the given employee — either as the employee's direct
     * reports-to manager, or as a ฝ่าย manager sharing the employee's division (excluding self).
     */
    private boolean managesEmployee(long employeeId, UserPrincipal user) {
        if (user == null || user.employeeId() == null) {
            return false;
        }
        return overtimeRepository.findEmployeeAccess(employeeId)
            .map(access -> {
                boolean directReport = access.managerEmployeeId() != null
                    && access.managerEmployeeId().equals(user.employeeId());
                boolean divisionManager = user.manager()
                    && user.divisionId() != null
                    && user.divisionId().equals(access.divisionId())
                    && employeeId != user.employeeId();
                return directReport || divisionManager;
            })
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

    private void notifySubmitted(OvertimeRequestDto request) {
        String title = "ส่งคำขอ OT แล้ว";
        String message = "คำขอ OT วันที่ " + request.workDate() + " ถูกส่งให้ผู้จัดการตรวจสอบแล้ว";
        notificationService.notify(request.employeeId(), "OVERTIME_SUBMITTED", title, message, "/overtime", true);
        if (request.managerEmployeeId() != null) {
            notificationService.notify(
                request.managerEmployeeId(),
                "OVERTIME_PENDING_MANAGER",
                "มีคำขอ OT รออนุมัติ",
                request.employeeName() + " ส่งคำขอ OT วันที่ " + request.workDate(),
                "/overtime",
                true
            );
        }
    }

    private void notifyManagerApproved(OvertimeRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "OVERTIME_MANAGER_APPROVED",
            "ผู้จัดการอนุมัติคำขอ OT แล้ว",
            "คำขอ OT วันที่ " + request.workDate() + " ผ่านผู้จัดการแล้ว และรอ CEO อนุมัติขั้นสุดท้าย",
            "/overtime",
            true
        );
        for (Long ceoEmployeeId : overtimeRepository.findCeoApproverEmployeeIds()) {
            notificationService.notify(
                ceoEmployeeId,
                "OVERTIME_PENDING_CEO",
                "มีคำขอ OT รอ CEO อนุมัติ",
                request.employeeName() + " มีคำขอ OT วันที่ " + request.workDate() + " ที่ผู้จัดการอนุมัติแล้ว",
                "/overtime",
                true
            );
        }
    }

    private void notifyCeoApproved(OvertimeRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "OVERTIME_APPROVED",
            "CEO อนุมัติคำขอ OT แล้ว",
            "คำขอ OT วันที่ " + request.workDate() + " อนุมัติครบถ้วนแล้ว",
            "/overtime",
            true
        );
        if (request.managerApprovedBy() != null) {
            notificationService.notify(
                request.managerApprovedBy(),
                "OVERTIME_APPROVED",
                "CEO อนุมัติคำขอ OT แล้ว",
                request.employeeName() + " ได้รับการอนุมัติ OT วันที่ " + request.workDate() + " ครบถ้วนแล้ว",
                "/overtime",
                true
            );
        }
    }

    private void notifyRejected(OvertimeRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "OVERTIME_REJECTED",
            "คำขอ OT ถูกปฏิเสธ",
            "คำขอ OT วันที่ " + request.workDate() + " ถูกปฏิเสธ: "
                + (request.reviewerNote() == null ? "กรุณาติดต่อผู้จัดการหรือ HR" : request.reviewerNote()),
            "/overtime",
            true
        );
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
