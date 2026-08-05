package th.co.glr.hr.overtime;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
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
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.notification.NotificationService;

@Service
public class OvertimeService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");
    private static final int ATTENDANCE_LOOKAROUND_HOURS = 16;
    /** A backdated request has to say why it is backdated, not just "OT". */
    private static final int BACKDATED_REASON_MIN_LENGTH = 20;

    private final OvertimeRepository overtimeRepository;
    private final ManagerApproverRepository managerApproverRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final AttendanceDailyService attendanceDailyService;

    public OvertimeService(
            OvertimeRepository overtimeRepository,
            ManagerApproverRepository managerApproverRepository,
            AuditService auditService,
            NotificationService notificationService,
            AppProperties appProperties,
            AttendanceDailyService attendanceDailyService) {
        this.overtimeRepository = overtimeRepository;
        this.managerApproverRepository = managerApproverRepository;
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น");
        }

        Long employeeId = requestedEmployeeId;
        Long managerEmployeeId = null;
        Long managerDivisionId = null;
        if (!canViewAll(user)) {
            managerEmployeeId = requireEmployeeId(user);
            managerDivisionId = user.manager() ? user.divisionId() : null;
            if (requestedEmployeeId != null && !canAccessEmployee(user, requestedEmployeeId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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
        // Opus review finding F2: this used to live at the BOTTOM of validateRetroactiveWindow,
        // which returns early for a work date that is today or later -- so a closed payroll month
        // was only refused for BACKDATED submissions. Hoisted here so it runs for every submit,
        // matching what this guard's own javadoc has always claimed. Harmless for the current
        // seed-covered case (Jan-Jun 2026 is entirely in the past, so the old placement did fire),
        // but the gap was real: record coverage for a current month and same-day OT sailed past
        // submit, only to be refused a stage later at manager approval -- surfacing the error to
        // the wrong person, after the request had already entered the queue.
        requirePayrollMonthOpen(request.workDate());

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
            // No manager stage exists for this employee (manager-less ฝ่าย, or the requester is a
            // ผู้จัดการ) -- the CEO is the only approver, and approving takes the request all the
            // way to APPROVED rather than parking it in a MANAGER_APPROVED nobody would clear.
            if (!hasManagerStage(existing.employeeId())) {
                return ceoDirectApprove(id, request, user, actorEmployeeId, existing);
            }
            return managerApprove(id, request, user, actorEmployeeId, existing);
        }
        if (status == OvertimeStatus.MANAGER_APPROVED) {
            return ceoApprove(id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
    }

    /**
     * Whether a manager stage exists for this employee at all. Read inside the approving
     * transaction, so it reflects the org chart as at the moment of the decision.
     *
     * <p>Not folded into the {@code UPDATE ... WHERE} guard: reassigning a division's ผู้จัดการ
     * between this read and the write would, at worst, let the CEO approve in one step something
     * they were entitled to approve in two a moment earlier. The CEO outranks the manager stage, so
     * the race costs a review step, never an unauthorized approval.
     */
    private boolean hasManagerStage(long employeeId) {
        return managerApproverRepository.hasManagerApprover(employeeId);
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "MANAGER_APPROVE_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyManagerApproved(after);
        return after;
    }

    /**
     * SUBMITTED straight to APPROVED, for a request with no manager stage.
     *
     * <p>Does the manager step's work as well as the CEO's: the attendance-derived calculation and
     * the salary basis are what {@link #managerApprove} writes in the two-step flow, and payroll
     * reads them off an APPROVED row whichever route produced it. Skipping them here would approve
     * an overtime request worth zero minutes at a zero salary basis.
     */
    private OvertimeRequestDto ceoDirectApprove(
            long id,
            ReviewOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireCeoForManagerlessRequest(user);
        requirePayrollMonthOpen(existing.workDate());

        OvertimeCalculation calculation = calculate(existing);
        BigDecimal salaryBasis = overtimeRepository.findSalaryBasisAsOf(existing.employeeId(), existing.workDate());
        int updated = overtimeRepository.ceoDirectApprove(id, actorEmployeeId, calculation, salaryBasis, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_DIRECT_APPROVE_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyCeoApproved(after);
        syncAttendanceDay(after);
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
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
            // Symmetric with approve(): whoever is the only possible reviewer must be able to
            // refuse as well as accept, or a manager-less request can only ever be approved.
            if (!hasManagerStage(existing.employeeId())) {
                return ceoDirectReject(id, request, user, actorEmployeeId, existing);
            }
            return managerReject(id, request, user, actorEmployeeId, existing);
        }
        if (status == OvertimeStatus.MANAGER_APPROVED) {
            return ceoReject(id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
    }

    private OvertimeRequestDto ceoDirectReject(
            long id,
            ReviewOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireCeoForManagerlessRequest(user);
        // reject() is already guarded on status = 'SUBMITTED' and writes no approver columns, so it
        // is the correct statement for this path; only the audit action distinguishes the route.
        int updated = overtimeRepository.reject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_DIRECT_REJECT_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyRejected(after, actorEmployeeId);
        return after;
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "REJECT_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyRejected(after, actorEmployeeId);
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_REJECT_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyRejected(after, actorEmployeeId);
        return after;
    }

    @Transactional
    public OvertimeRequestDto cancel(long id, ReviewOvertimeRequest request, UserPrincipal user) {
        OvertimeRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        boolean manager = managesEmployee(existing.employeeId(), user);
        if (!manager && existing.employeeId() != actorEmployeeId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!manager && !"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "พนักงานยกเลิกได้เฉพาะคำขอทำงานล่วงเวลาที่ยังไม่ได้รับการพิจารณาเท่านั้น");
        }
        if (!"SUBMITTED".equals(existing.status())
                && !"MANAGER_APPROVED".equals(existing.status())
                && !"APPROVED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ยกเลิกได้เฉพาะคำขอทำงานล่วงเวลาที่ยังอยู่ระหว่างพิจารณาเท่านั้น");
        }

        int updated = overtimeRepository.cancel(id, manager ? actorEmployeeId : null, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ไม่สามารถยกเลิกได้แล้ว");
        }
        OvertimeRequestDto after = requireRequest(id);
        auditService.record(user, "CANCEL_OVERTIME_REQUEST", "overtime_request", id, existing, after);
        notifyCancelled(existing, after, actorEmployeeId, user.name());
        // Cancelling an already-APPROVED request removes minutes the day had been credited with.
        syncAttendanceDay(after);
        return after;
    }

    /**
     * Notification coverage gap B: cancelling a request used to notify nobody, so a withdrawn item
     * sat in a reviewer's queue forever. Notifies the requester unconditionally (worded for either
     * cancel path -- {@code after.reviewedById()} is non-null exactly when a reviewer/manager
     * cancelled it, the same {@code manager ? actorEmployeeId : null} shape {@link #cancel} itself
     * just wrote), plus whoever the request was actually pending with, resolved from the SAME source
     * {@link #notifySubmitted}/{@link #notifyManagerApproved} use for each stage -- see {@link
     * #notifySubmitted}'s Javadoc for why this must be {@code ManagerApproverRepository}/{@code
     * findCeoApproverEmployeeIds}, never {@code reports_to_employee_id}.
     *
     * <p>{@code before.status()} (the PRE-cancel status) decides which stage was pending:
     * {@code SUBMITTED} -> the manager(s) if a manager stage exists, else the CEO(s) (mirrors {@link
     * #notifySubmitted}'s own goesToCeo branch); {@code MANAGER_APPROVED} -> the CEO(s) only, the
     * manager already acted. {@code APPROVED} has no PENDING stage left, but cancelling it reverses
     * payroll-relevant state ({@link #syncAttendanceDay} strips the credited minutes back out) -- D1
     * (owner ruling): the manager and CEO who actually approved it are told, resolved from what the
     * row itself records approved it -- {@code managerApprovedBy} and, since S-7 below, {@code
     * ceoApprovedBy} (the single CEO who actually approved this row), never a stage that never saw
     * the request (a manager-less request's {@code managerApprovedBy} is always {@code null}, so
     * that branch is simply skipped).
     *
     * <p><b>S-2/S-7 (review, second pass):</b> the {@code APPROVED} branch used to notify {@code
     * before.managerApprovedBy()} and then separately loop the WHOLE CEO approver set ({@code
     * findCeoApproverEmployeeIds()} -- every active employee in division {@code MD%}/{@code MN%}),
     * with no de-duplication between the two. A กรรมการผู้จัดการ (position contains ผู้จัดการ,
     * division {@code MD}) is a manager AND a member of that broadcast CEO set, so approving as the
     * manager stage and then being looped again as "a CEO" produced two notification rows and two
     * emails for one cancellation. S-7 replaces the broadcast with the single approving CEO, read
     * from {@code before.ceoApprovedBy()} (populated by both {@code OvertimeRepository#ceoApprove}
     * and {@code #ceoDirectApprove} for every row that ever reaches {@code APPROVED} -- see those
     * methods) -- the owner's D1 ruling was "notify the approvers", i.e. the specific people who
     * approved this row, not everyone who currently COULD. That alone removes most of the
     * duplication (the manager and the specific approving CEO are, in the ordinary case, different
     * people), and the {@code LinkedHashSet} below removes what is left: the rare case where the
     * SAME employee id shows up as both {@code managerApprovedBy} and {@code ceoApprovedBy} (e.g. a
     * division reassignment between the two approvals) is still only notified once.
     *
     * <p>Review finding (BLOCKING 1): every approver-facing branch below used to hardcode {@code
     * before.employeeName()} as if the employee themselves always did the cancelling, and never
     * excluded the actor from the notified set. Cancel is reachable by a REVIEWER too (a ฝ่าย
     * manager, for SUBMITTED/MANAGER_APPROVED/APPROVED alike) -- and {@code
     * findManagerApproverEmployeeIds(before.employeeId())} resolves to that SAME manager, since
     * {@code managesEmployee}/{@code PEER_IS_MANAGER_APPROVER} key off the identical ผู้จัดการ match.
     * A manager cancelling their own division's SUBMITTED request was therefore emailed about the
     * cancellation they themselves had just performed, under a message that also named them as if
     * they were the employee. Fixed by threading {@code actorEmployeeId}/{@code actorName} (the
     * caller of {@link #cancel}) through: every message below is worded from the actual actor, and
     * every approver loop skips {@code actorEmployeeId} -- nobody is ever notified about their own
     * action.
     */
    private void notifyCancelled(
            OvertimeRequestDto before, OvertimeRequestDto after, long actorEmployeeId, String actorName) {
        String actorLabel = actorName == null || actorName.isBlank() ? "ผู้จัดการหรือ HR" : actorName;
        boolean cancelledByReviewer = after.reviewedById() != null;
        notificationService.notify(
            after.employeeId(),
            "OVERTIME_CANCELLED",
            "คำขอ OT ถูกยกเลิก",
            cancelledByReviewer
                ? "คำขอ OT วันที่ " + after.workDate() + " ถูกยกเลิกโดย "
                    + (after.reviewedByName() == null ? "ผู้จัดการหรือ HR" : after.reviewedByName())
                : "คำขอ OT วันที่ " + after.workDate() + " ถูกยกเลิกเรียบร้อยแล้ว",
            "/overtime",
            true
        );
        if ("SUBMITTED".equals(before.status())) {
            List<Long> managerApprovers =
                managerApproverRepository.findManagerApproverEmployeeIds(before.employeeId());
            if (managerApprovers.isEmpty()) {
                for (Long ceoEmployeeId : overtimeRepository.findCeoApproverEmployeeIds()) {
                    if (ceoEmployeeId == actorEmployeeId) {
                        continue;
                    }
                    notificationService.notify(
                        ceoEmployeeId,
                        "OVERTIME_CANCELLED",
                        "คำขอ OT ที่รอ CEO อนุมัติถูกยกเลิก",
                        actorLabel + " ยกเลิกคำขอ OT วันที่ " + before.workDate(),
                        "/overtime",
                        true
                    );
                }
            } else {
                for (Long managerEmployeeId : managerApprovers) {
                    if (managerEmployeeId == actorEmployeeId) {
                        continue;
                    }
                    notificationService.notify(
                        managerEmployeeId,
                        "OVERTIME_CANCELLED",
                        "คำขอ OT ที่รออนุมัติถูกยกเลิก",
                        actorLabel + " ยกเลิกคำขอ OT วันที่ " + before.workDate(),
                        "/overtime",
                        true
                    );
                }
            }
        } else if ("MANAGER_APPROVED".equals(before.status())) {
            for (Long ceoEmployeeId : overtimeRepository.findCeoApproverEmployeeIds()) {
                if (ceoEmployeeId == actorEmployeeId) {
                    continue;
                }
                notificationService.notify(
                    ceoEmployeeId,
                    "OVERTIME_CANCELLED",
                    "คำขอ OT ที่รอ CEO อนุมัติถูกยกเลิก",
                    actorLabel + " ยกเลิกคำขอ OT วันที่ " + before.workDate() + " ที่ผู้จัดการอนุมัติแล้ว",
                    "/overtime",
                    true
                );
            }
        } else if ("APPROVED".equals(before.status())) {
            // S-2/S-7: both possible approvers (manager, and the single CEO who actually approved
            // this row -- see the class-level Javadoc above) collected into a LinkedHashSet so the
            // rare case where they resolve to the SAME employee id is notified exactly once, not
            // twice. LinkedHashSet preserves insertion order (manager first) purely for determinism;
            // nothing downstream depends on iteration order.
            Set<Long> approvedByRecipients = new LinkedHashSet<>();
            if (before.managerApprovedBy() != null) {
                approvedByRecipients.add(before.managerApprovedBy());
            }
            if (before.ceoApprovedBy() != null) {
                approvedByRecipients.add(before.ceoApprovedBy());
            }
            approvedByRecipients.remove(actorEmployeeId);
            for (Long recipientId : approvedByRecipients) {
                notificationService.notify(
                    recipientId,
                    "OVERTIME_CANCELLED",
                    "คำขอ OT ที่อนุมัติแล้วถูกยกเลิก",
                    actorLabel + " ยกเลิกคำขอ OT วันที่ " + before.workDate() + " ที่อนุมัติครบถ้วนแล้ว",
                    "/overtime",
                    true
                );
            }
        }
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
            throw new ApiException(HttpStatus.FORBIDDEN, "พนักงานสามารถขอทำงานล่วงเวลาให้ตนเองเท่านั้น");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!overtimeRepository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบข้อมูลพนักงาน");
        }
    }

    private void validatePlannedWindow(SubmitOvertimeRequest request) {
        int plannedMinutes = minutesBetween(request.plannedStartAt(), request.plannedEndAt());
        if (plannedMinutes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาสิ้นสุดการทำงานล่วงเวลาต้องอยู่หลังเวลาเริ่มต้น");
        }
        LocalDate startWorkDate = request.plannedStartAt().atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        if (!startWorkDate.equals(request.workDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ทำงานต้องตรงกับวันที่เริ่มต้นตามแผน");
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
                "ยื่นคำขอทำงานล่วงเวลาย้อนหลังได้ไม่เกิน " + windowDays + " วันหลังวันที่ทำงาน"
            );
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < BACKDATED_REASON_MIN_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "คำขอทำงานล่วงเวลาย้อนหลังต้องระบุเหตุผลที่ยื่นล่าช้าอย่างชัดเจน (อย่างน้อย "
                    + BACKDATED_REASON_MIN_LENGTH + " ตัวอักษร)"
            );
        }
    }

    /**
     * Refuses to touch a work date whose payroll month is closed, for either of two distinct
     * reasons -- an HR reader needs to tell them apart, so each gets its own Thai message:
     *
     * <ol>
     *   <li><b>Already processed in this system.</b> Payroll derives overtime by {@code
     *       payroll_month} and a processed period is inserted once, so a request that lands in a
     *       processed month is approved and then never paid -- silently.
     *   <li><b>Seed-covered.</b> The month was paid outside the ERP and is already reflected in
     *       {@code hr.payroll_year_to_date_seed} (PND1 filed) -- see V114. It is never {@code
     *       PROCESSED} here (a DB trigger refuses that, to avoid double-counting year-to-date
     *       withholding), so checking {@link OvertimeRepository#payrollMonthProcessed(LocalDate)}
     *       alone would report such a month as open.
     * </ol>
     *
     * <p>This runs at submit and again at each approval stage, because a request filed before a
     * month closes -- by either route -- can still be approved after it does.
     */
    private void requirePayrollMonthOpen(LocalDate workDate) {
        LocalDate payrollMonth = workDate.withDayOfMonth(1);
        if (overtimeRepository.payrollMonthProcessed(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "งวดเงินเดือน " + payrollMonth.getYear() + "-" + payrollMonth.getMonthValue()
                    + " ได้ประมวลผลไปแล้ว จึงไม่สามารถจ่ายค่าล่วงเวลานี้ได้อีก"
            );
        }
        if (overtimeRepository.payrollMonthSeedCovered(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "งวดเงินเดือน " + payrollMonth.getYear() + "-" + payrollMonth.getMonthValue()
                    + " ถูกจ่ายนอกระบบไปแล้วและได้ยื่นแบบภาษีแล้ว จึงไม่สามารถยื่นหรืออนุมัติค่าล่วงเวลาในงวดนี้ได้"
            );
        }
    }

    private void requireManager(long employeeId, UserPrincipal user) {
        if (!managesEmployee(employeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะหัวหน้างานของพนักงานเท่านั้นที่สามารถพิจารณาคำขอทำงานล่วงเวลาได้");
        }
    }

    private void requireCeo(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้นที่สามารถอนุมัติคำขอทำงานล่วงเวลาที่หัวหน้างานอนุมัติแล้วได้");
        }
    }

    /**
     * Same role gate as {@link #requireCeo}, different message: on this path there is no manager
     * stage to have passed, so telling the caller the request is "waiting for a manager" would send
     * them looking for an approver who does not exist.
     */
    private void requireCeoForManagerlessRequest(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "คำขอนี้ไม่มีขั้นอนุมัติของหัวหน้างาน (ฝ่ายนี้ไม่มีผู้จัดการ หรือผู้ยื่นเป็นผู้จัดการเอง)"
                    + " จึงต้องให้ CEO พิจารณาเท่านั้น");
        }
    }

    private boolean canAccessEmployee(UserPrincipal user, long employeeId) {
        return (user.employeeId() != null && user.employeeId() == employeeId) || managesEmployee(employeeId, user);
    }

    /**
     * True when {@code user} manages the given employee: a ฝ่าย manager sharing the employee's
     * division, excluding self.
     *
     * <p>{@code reports_to_employee_id} deliberately does NOT grant approval rights. It used to,
     * and was removed on the owner's instruction so that overtime matches
     * {@code AttendanceService.resolveScope}, which has always been division-only. The self
     * exclusion is what sends a ผู้จัดการ's own request straight to the CEO.
     *
     * <p>Must stay in lockstep with {@code ManagerApproverRepository}: that class answers "does any
     * such user exist" in SQL, and the two are pinned to each other by
     * {@code ManagerApproverInvariantIntegrationTest}.
     */
    private boolean managesEmployee(long employeeId, UserPrincipal user) {
        if (user == null || user.employeeId() == null) {
            return false;
        }
        return overtimeRepository.findEmployeeAccess(employeeId)
            .map(access -> user.manager()
                && user.divisionId() != null
                && user.divisionId().equals(access.divisionId())
                && employeeId != user.employeeId())
            .orElse(false);
    }

    private OvertimeRequestDto requireRequest(long id) {
        return overtimeRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอทำงานล่วงเวลานี้"));
    }

    private void requireStatus(OvertimeRequestDto request, OvertimeStatus status) {
        if (!status.name().equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอทำงานล่วงเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
    }

    private boolean canViewAll(UserPrincipal user) {
        return user != null && VIEW_ALL_ROLES.contains(user.role());
    }

    /**
     * Notifies whoever can actually act on the new request — the employee's ฝ่าย manager(s), or the
     * CEO when there is no manager stage.
     *
     * <p>This used to notify {@code reports_to_employee_id}. That became wrong the moment approval
     * went division-only: it put the request in front of someone who cannot clear it, while the
     * person who can never heard about it. Reading the approver list from the same place the routing
     * decision comes from is what keeps the two in step.
     */
    private void notifySubmitted(OvertimeRequestDto request) {
        List<Long> managerApprovers =
            managerApproverRepository.findManagerApproverEmployeeIds(request.employeeId());
        boolean goesToCeo = managerApprovers.isEmpty();

        String title = "ส่งคำขอ OT แล้ว";
        String message = "คำขอ OT วันที่ " + request.workDate()
            + (goesToCeo ? " ถูกส่งให้ CEO พิจารณาแล้ว (ไม่มีขั้นอนุมัติของหัวหน้างาน)" : " ถูกส่งให้ผู้จัดการตรวจสอบแล้ว");
        notificationService.notify(request.employeeId(), "OVERTIME_SUBMITTED", title, message, "/overtime", true);

        if (goesToCeo) {
            for (Long ceoEmployeeId : overtimeRepository.findCeoApproverEmployeeIds()) {
                notificationService.notify(
                    ceoEmployeeId,
                    "OVERTIME_PENDING_CEO",
                    "มีคำขอ OT รอ CEO อนุมัติ",
                    request.employeeName() + " ส่งคำขอ OT วันที่ " + request.workDate()
                        + " ซึ่งไม่มีขั้นอนุมัติของหัวหน้างาน",
                    "/overtime",
                    true
                );
            }
            return;
        }
        for (Long managerEmployeeId : managerApprovers) {
            notificationService.notify(
                managerEmployeeId,
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

    /**
     * Notification coverage gap C: a rejection used to tell only the requester, leaving the manager
     * who had already approved the request sitting on a stale mental queue entry when the CEO later
     * closed it out.
     *
     * <p>{@code managerApprovedBy()} is set ONLY by {@link #managerApprove} and is never cleared by
     * {@code reject}/{@code ceoReject} (see {@code OvertimeRepository}) -- so it reliably tells apart
     * the three ways a request reaches REJECTED: {@link #managerReject} (the manager themselves is
     * rejecting -- they need no notification about their own action) and {@link #ceoDirectReject}
     * (manager-less request, no manager stage ever existed) BOTH leave it {@code null}; only {@link
     * #ceoReject} (rejecting a MANAGER_APPROVED request) leaves it set, which is exactly the one case
     * where a manager approved something the CEO then overturned and needs to be told.
     *
     * <p><b>S-6 (review, second pass):</b> this was the one branch-stated-rule ("nobody is notified
     * about their own action") place that had no actor self-skip. Reachable: a กรรมการผู้จัดการ
     * (division {@code MD}) has {@code manager() == true} AND role {@code ceo} -- for an
     * MD-division non-manager's OT they can {@link #managerApprove} it and then, as CEO, {@link
     * #ceoReject} the SAME request; {@code managerApprovedBy} then equals the rejecter, who was
     * emailed "CEO ปฏิเสธคำขอ OT ที่ผู้จัดการอนุมัติแล้ว" about their own rejection. {@code
     * actorEmployeeId} is threaded through the same way {@link #notifyCancelled} already does, and
     * both possible recipients (the employee themselves, and {@code managerApprovedBy}) are skipped
     * when they are the actor -- the employee-self case is reachable too, symmetrically: a
     * manager-less CEO can submit their OWN OT (self-submission is allowed everywhere) and then
     * {@link #ceoDirectReject} it.
     */
    private void notifyRejected(OvertimeRequestDto request, long actorEmployeeId) {
        if (request.employeeId() != actorEmployeeId) {
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
        if (request.managerApprovedBy() != null && request.managerApprovedBy() != actorEmployeeId) {
            notificationService.notify(
                request.managerApprovedBy(),
                "OVERTIME_REJECTED",
                "CEO ปฏิเสธคำขอ OT ที่ผู้จัดการอนุมัติแล้ว",
                request.employeeName() + " มีคำขอ OT วันที่ " + request.workDate()
                    + " ที่ผู้จัดการอนุมัติแล้ว แต่ถูก CEO ปฏิเสธ: "
                    + (request.reviewerNote() == null ? "กรุณาติดต่อ HR" : request.reviewerNote()),
                "/overtime",
                true
            );
        }
    }

    private Long requireEmployeeId(UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "สถานะคำขอทำงานล่วงเวลาไม่ถูกต้อง");
        }
    }

    private OvertimeDayType parseDayType(String value) {
        if (value == null || value.isBlank()) {
            return OvertimeDayType.WORKDAY;
        }
        try {
            return OvertimeDayType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทวันทำงานล่วงเวลาไม่ถูกต้อง");
        }
    }

    private int minutesBetween(OffsetDateTime start, OffsetDateTime end) {
        long minutes = Duration.between(start.toInstant(), end.toInstant()).toMinutes();
        if (minutes > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ช่วงเวลาทำงานล่วงเวลานานเกินกำหนด");
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
