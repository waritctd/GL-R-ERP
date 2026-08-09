package th.co.glr.hr.overtime;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.EmployeeDay;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;

@Service
public class OvertimeService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");
    private static final int ATTENDANCE_LOOKAROUND_HOURS = 16;
    /** A backdated request has to say why it is backdated, not just "OT". */
    private static final int BACKDATED_REASON_MIN_LENGTH = 20;
    /**
     * Prefix marking a {@code calculation_note} as the day-type-unverified flag {@link
     * #resolveDayTypeSubmitNote} wrote at submit time, rather than an ordinary approval-time
     * calculation note. Used by {@link #preserveDayTypeClaimFlag} to decide whether an
     * approval-time note must be appended to, rather than allowed to overwrite, whatever is
     * already stored.
     *
     * <p>Kept short deliberately: {@code calculation_note} renders untruncated in a narrow
     * {@code <small>} table cell ({@code OvertimePanel.jsx}'s "เหตุผล" column), so the full note
     * (this prefix plus {@link #resolveDayTypeSubmitNote}'s message) targets roughly 80 characters
     * total rather than reproducing the work date, which the same table row already shows in its
     * own column.
     */
    private static final String DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX = "[รอตรวจสอบ] ";
    /**
     * Prefix marking a {@code calculation_note} as the claim-vs-suggestion DISAGREEMENT flag {@link
     * #resolveDayTypeSubmitNote} writes at submit time when the employee's claim does not match
     * {@link #suggestDayType} -- a distinct condition from {@link
     * #DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX} (that one is about the CALENDAR being unloaded; this
     * one is about the CLAIM disagreeing with an already-resolvable suggestion). Both are
     * recognised by {@link #isDayTypeFlagNote}, which {@link #preserveDayTypeClaimFlag} uses to
     * decide whether an approval-time note must be appended to, rather than allowed to overwrite,
     * whatever is already stored -- see that method's Javadoc.
     */
    private static final String DAY_TYPE_CLAIM_DISAGREEMENT_NOTE_PREFIX = "[ไม่ตรงกับที่ระบบแนะนำ] ";

    private final OvertimeRepository overtimeRepository;
    private final ManagerApproverRepository managerApproverRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final AttendanceDailyService attendanceDailyService;
    private final HolidayCalendar holidayCalendar;
    private final WorkScheduleResolver scheduleResolver;
    private final CeoApproverRepository ceoApprovers;

    public OvertimeService(
            OvertimeRepository overtimeRepository,
            ManagerApproverRepository managerApproverRepository,
            AuditService auditService,
            NotificationService notificationService,
            AppProperties appProperties,
            AttendanceDailyService attendanceDailyService,
            HolidayCalendar holidayCalendar,
            WorkScheduleResolver scheduleResolver,
            CeoApproverRepository ceoApprovers) {
        this.overtimeRepository = overtimeRepository;
        this.managerApproverRepository = managerApproverRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.appProperties = appProperties;
        this.attendanceDailyService = attendanceDailyService;
        this.holidayCalendar = holidayCalendar;
        this.scheduleResolver = scheduleResolver;
        this.ceoApprovers = ceoApprovers;
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

        List<OvertimeRequestDto> requests = overtimeRepository.findRequests(new OvertimeFilter(
            employeeId,
            managerEmployeeId,
            managerDivisionId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus)
        ));
        // feat/ot-nonworkday-rate-suggestion: batch-loaded, not a per-row lookup -- see
        // attachSuggestions' Javadoc. Mirrors AttendanceDailyService#list's identical
        // division/department/holiday batching for the same reason: a per-row
        // OvertimeRepository#findDivisionId call here would be an N+1 on a screen that can list a
        // whole division's OT history.
        return attachSuggestions(requests, effectiveFrom, effectiveTo);
    }

    /**
     * Attaches {@link OvertimeRequestDto#suggestedDayType} to every row in ONE pass, batch-loading
     * division/department (via {@link OvertimeRepository#findDivisionIdsByEmployee}/{@link
     * OvertimeRepository#findDepartmentIdsByEmployee}) and the range's holidays ONCE rather than
     * once per row -- exactly the pattern {@code AttendanceDailyService#list} already uses for the
     * identical division/department/holiday triad.
     *
     * <p>{@code holidays} is bounded to exactly {@code [fromDate, toDate]}: every row {@link
     * OvertimeRepository#findRequests} can return already has {@code work_date BETWEEN :fromDate
     * AND :toDate} (see {@link OvertimeFilter}), so a wider read would only waste the query.
     */
    private List<OvertimeRequestDto> attachSuggestions(
            List<OvertimeRequestDto> requests, LocalDate fromDate, LocalDate toDate) {
        if (requests.isEmpty()) {
            return requests;
        }
        Map<Long, Long> divisionByEmployee = overtimeRepository.findDivisionIdsByEmployee();
        Map<Long, Long> departmentByEmployee = overtimeRepository.findDepartmentIdsByEmployee();
        Set<LocalDate> holidays = holidayCalendar.holidaysBetween(fromDate, toDate);
        return requests.stream()
            .map(request -> withSuggestedDayType(request, suggestDayType(
                request.employeeId(),
                request.workDate(),
                holidays.contains(request.workDate()),
                divisionByEmployee.get(request.employeeId()),
                departmentByEmployee.get(request.employeeId()))))
            .toList();
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
        // SECURITY: request.dayType() is unauthenticated client input and is deliberately never
        // used to set pay -- it used to be (see git history / the P0 this fixed), which let a
        // caller self-declare HOLIDAY (3.00x) on an ordinary Tuesday and get paid double what the
        // work was worth, with nothing in the approval UI to contradict the lie. day_type/
        // pay_rate_multiplier at submit are always DERIVED -- from hr.holiday (V115) OR the
        // employee's resolved WorkSchedule non-workday, via suggestDayType -- never DECLARED by the
        // caller. THIS STILL HOLDS after feat/ot-nonworkday-rate-suggestion: what changed is that an
        // AUTHORIZED APPROVER may later override the suggestion at approval time (see
        // ApproveOvertimeRequest.dayType, honoured only inside managerApprove/ceoDirectApprove,
        // from an actor who already passed this request's approve gate) -- request.dayType() here
        // is the submitter's own field, and it is a REQUEST, not a pay input, at every stage. It is
        // still validated -- see resolveDayTypeSubmitNote -- but only to flag a disagreement with
        // the suggestion (or a derivation the calendar cannot yet corroborate) for the approver to
        // see; either way it never feeds dayType/pay_rate_multiplier directly, and a submitter can
        // never move their own money by changing it.
        OvertimeDayType suggestedDayType = suggestDayType(employeeId, request.workDate());
        String submitTimeNote = resolveDayTypeSubmitNote(request.dayType(), suggestedDayType, request.workDate());
        long id = overtimeRepository.create(
            employeeId, actorEmployeeId, request, plannedMinutes, suggestedDayType, payrollMonth, submitTimeNote);
        OvertimeRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_OVERTIME_REQUEST", "overtime_request", id, null, created);
        notifySubmitted(created);
        return created;
    }

    @Transactional
    public OvertimeRequestDto approve(long id, ApproveOvertimeRequest request, UserPrincipal user) {
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
            // Freeze point does not move: ceoApprove() below never reads request.dayType() -- see
            // its own comment and ApproveOvertimeRequest's Javadoc. The manager (or, on the
            // manager-less route, the CEO acting at the FIRST stage above) already made that call.
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
            ApproveOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireManager(existing.employeeId(), user);
        requirePayrollMonthOpen(existing.workDate());

        // The approver's DECISION -- see ApproveOvertimeRequest's Javadoc. Parsed (and, for a
        // malformed non-blank value, rejected with 400) BEFORE requireManager's authorization could
        // ever be bypassed by a caller who is not this request's manager -- requireManager already
        // ran above, so this line only executes for an actor already authorized to approve.
        OvertimeDayType approverDayType = parseOvertimeDayType(request == null ? null : request.dayType());
        OvertimeCalculation calculation = calculate(existing, approverDayType);
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
            ApproveOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireCeoForManagerlessRequest(user);
        requirePayrollMonthOpen(existing.workDate());

        // See managerApprove's identical comment -- same authorized-decision treatment for the
        // manager-less route, which is this class's one-step equivalent of that same stage.
        OvertimeDayType approverDayType = parseOvertimeDayType(request == null ? null : request.dayType());
        OvertimeCalculation calculation = calculate(existing, approverDayType);
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
            ApproveOvertimeRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            OvertimeRequestDto existing) {
        requireCeo(user);
        requirePayrollMonthOpen(existing.workDate());

        // request.dayType() is DELIBERATELY never read here. The freeze point does not move
        // (feat/ot-nonworkday-rate-suggestion): day_type/pay_rate_multiplier were already computed
        // and frozen at managerApprove, on the two-stage route this method is the second half of --
        // this final CEO sign-off inherits that decision, same as payable_minutes/salary_basis
        // already do (see OvertimeRepository#ceoApprove's SQL, which does not write those columns
        // at all). Letting the CEO override a second time here would need a second decision point;
        // that is out of scope for this fix -- see ApproveOvertimeRequest's Javadoc and this fix's
        // PR body.
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
     * CeoApproverRepository}, never {@code reports_to_employee_id}.
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
     * before.managerApprovedBy()} and then separately loop the WHOLE CEO approver set (at the time,
     * {@code findCeoApproverEmployeeIds()} -- every active employee in division {@code MD%}/{@code
     * MN%}; that lookup is now {@code CeoApproverRepository#findEmployeeIds()}, narrowed to the
     * กรรมการผู้จัดการ alone -- NARROWER than the {@code ceo} role, see {@code CeoApproverRule}),
     * with no de-duplication between
     * the two. A กรรมการผู้จัดการ (position contains ผู้จัดการ,
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
                for (Long ceoEmployeeId : ceoApprovers.findEmployeeIds()) {
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
            for (Long ceoEmployeeId : ceoApprovers.findEmployeeIds()) {
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

    /**
     * The money calculation, computed once at whichever approval stage first leaves {@code
     * SUBMITTED} ({@link #managerApprove} / {@link #ceoDirectApprove}) and frozen from there —
     * {@link #ceoApprove} never calls this again, matching how {@code salary_basis} is resolved
     * once and not re-priced later (see {@code OvertimeRepository#findSalaryBasisAsOf}'s Javadoc
     * and {@code PayrollRepository#findApprovedOvertimePayByEmployee}'s comment on the same rule).
     *
     * <p>{@code dayType} is what actually becomes pay (feat/ot-nonworkday-rate-suggestion): {@code
     * approverDayType}, when the approver supplied one, WINS outright; otherwise this falls back to
     * {@link #suggestDayType}, re-derived HERE at approval time rather than trusted from whatever
     * was stored at submit — the calendar/schedule can be corrected between submission and
     * approval, and this is the point money is finalized, so the fallback must reflect their
     * current state, not a possibly-stale one from days or weeks earlier. Either way, this is the
     * ONLY place the two possible sources (approver decision, system suggestion) are reconciled
     * into one value — do not duplicate this fallback logic at either call site.
     *
     * @param approverDayType the approver's parsed {@link ApproveOvertimeRequest#dayType}, or
     *     {@code null} to use the suggestion. Callers must have already authorized the actor to
     *     approve THIS request before calling this method — see {@link #managerApprove}/{@link
     *     #ceoDirectApprove}, the only two callers.
     */
    OvertimeCalculation calculate(OvertimeRequestDto request, OvertimeDayType approverDayType) {
        OvertimeDayType dayType = approverDayType != null
            ? approverDayType
            : suggestDayType(request.employeeId(), request.workDate());
        OffsetDateTime windowStart = request.plannedStartAt().minusHours(ATTENDANCE_LOOKAROUND_HOURS);
        OffsetDateTime windowEnd = request.plannedEndAt().plusHours(ATTENDANCE_LOOKAROUND_HOURS);
        return overtimeRepository.findAttendanceBounds(request.employeeId(), windowStart, windowEnd)
            .map(bounds -> calculate(request, bounds, dayType))
            .orElseGet(() -> new OvertimeCalculation(
                null,
                null,
                0,
                0,
                preserveDayTypeClaimFlag(request, "No attendance punches were found around the approved overtime window."),
                dayType
            ));
    }

    private OvertimeCalculation calculate(OvertimeRequestDto request, OvertimeAttendanceBounds bounds, OvertimeDayType dayType) {
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
                preserveDayTypeClaimFlag(request, "Attendance punches were found, but they do not overlap the approved overtime window."),
                dayType
            );
        }
        return new OvertimeCalculation(
            actualStart,
            actualEnd,
            actualMinutes,
            actualMinutes,
            preserveDayTypeClaimFlag(request, "Calculated from the overlap between approved overtime time and first/last attendance punch. No rounding applied."),
            dayType
        );
    }

    /**
     * The system's SUGGESTION for {@code dayType} -- one of THREE distinct values this fix
     * (feat/ot-nonworkday-rate-suggestion) introduces (see this fix's PR body for the full model):
     * the system's suggestion (this method, never a pay input by itself), the employee's REQUEST
     * ({@code SubmitOvertimeRequest.dayType}, pre-filled from this but freely editable, never a pay
     * input either), and the approver's DECISION ({@link ApproveOvertimeRequest#dayType}, the ONLY
     * one of the three that may become {@code pay_rate_multiplier}).
     *
     * <p>A date suggests HOLIDAY (3.00x) when EITHER of two independent things is true, mirroring
     * the exact predicate {@code AttendanceDailyService#toDto} and {@code
     * LeaveDayMath#workingDayPredicate} already use for the same two collaborators -- this is not a
     * third, competing implementation of that rule:
     *
     * <ol>
     *   <li>{@link HolidayCalendar#isHoliday} says {@code workDate} is a recorded {@code hr.holiday}
     *       row (public/company holiday) -- checked FIRST, short-circuiting the rest: a holiday
     *       always wins regardless of schedule, so the division/department lookups below are
     *       skipped entirely on a day they could not change the answer;
     *   <li>otherwise, the employee's resolved {@link WorkSchedule} (via {@link #scheduleResolver}
     *       -- {@code @Primary} {@code TieredWorkScheduleResolver} in production) says {@code
     *       workDate} is NOT one of their working days -- their ordinary weekly day off.
     * </ol>
     *
     * <p>Resolution is EMPLOYEE &gt; DEPARTMENT &gt; DIVISION &gt; company default (see {@code
     * TieredWorkScheduleResolver}'s own Javadoc) -- this tiering is load-bearing, not a
     * simplification this method could skip: an employee-scope override on a department that would
     * otherwise resolve differently (จำเนียร, employee-scope OPS_6D filed under a five-day
     * department) must come out right, and it is what keeps an OPS_6D division's Saturday a WORKDAY
     * (1.50x) rather than a naive day-of-week check's HOLIDAY -- Saturday is an ordinary working day
     * for คลังสินค้า/แม่บ้าน under the six-day schedule.
     *
     * <p>A work date with no {@code hr.holiday} row AND a scheduled working day resolves to WORKDAY
     * (1.50x), not HOLIDAY (3.00x) -- an ordinary working day simply has no calendar row, so absence
     * is the overwhelmingly common, correct case, not a data gap to fail loudly over. See {@link
     * #resolveDayTypeSubmitNote} for the narrower case where that absence is still worth flagging
     * for a human to check before approving.
     *
     * <p><b>Never trusted as a pay input by itself.</b> This feeds the initial {@code
     * day_type}/{@code pay_rate_multiplier} written at {@link #submit}, and is the DEFAULT {@link
     * #calculate} falls back to at {@link #managerApprove}/{@link #ceoDirectApprove} -- but the
     * approver may override it with an explicit {@link ApproveOvertimeRequest#dayType}, and THAT
     * choice, not this method's answer, is what actually freezes into pay. This is the policy
     * change this fix makes: the schedule/holiday rows in the DB may be wrong, so nothing pays 3x
     * without a human confirming it at approval.
     */
    private OvertimeDayType suggestDayType(long employeeId, LocalDate workDate) {
        boolean holiday = holidayCalendar.isHoliday(workDate);
        Long divisionId = holiday ? null : overtimeRepository.findDivisionId(employeeId);
        Long departmentId = holiday ? null : overtimeRepository.findDepartmentId(employeeId);
        return suggestDayType(employeeId, workDate, holiday, divisionId, departmentId);
    }

    /**
     * Bulk-load-friendly overload of {@link #suggestDayType(long, LocalDate)} -- {@code holiday}
     * and the division/department ids are supplied by the caller (see {@code attachSuggestions})
     * rather than looked up per call, so a list of N requests costs 2 batch queries total instead
     * of up to 2N.
     */
    private OvertimeDayType suggestDayType(
            long employeeId, LocalDate workDate, boolean holiday, Long divisionId, Long departmentId) {
        if (holiday) {
            return OvertimeDayType.HOLIDAY;
        }
        WorkSchedule schedule = scheduleResolver.resolve(employeeId, divisionId, departmentId, workDate);
        return schedule.isWorkday(workDate) ? OvertimeDayType.WORKDAY : OvertimeDayType.HOLIDAY;
    }

    /**
     * Resolves the {@code calculation_note} to write at submit time for {@code
     * SubmitOvertimeRequest.dayType} — a REQUEST only; it must never become a pay input, {@link
     * #suggestDayType} (or the approver's later override) alone decides money. Two independent
     * things can produce a note here, and they are deliberately not conflated -- either, both, or
     * neither may fire:
     *
     * <ol>
     *   <li><b>The suggestion itself is unverified.</b> {@code suggested} is WORKDAY -- i.e.
     *       resolved purely on "not a recorded holiday, and the employee's schedule says this is an
     *       ordinary working day" -- AND the calendar has ZERO rows for the work date's year at all
     *       (see {@link HolidayCalendar#hasHolidaysForYear}'s Javadoc). That is worth flagging: an
     *       unrecorded public holiday could still be hiding on this date, and nobody has loaded the
     *       year to rule it out. <b>Narrowed</b> (feat/ot-nonworkday-rate-suggestion) from "fires
     *       whenever the calendar has zero rows for the year, unconditionally" to "fires only when
     *       the suggestion is WORKDAY": a suggestion of HOLIDAY is never unverified in this sense --
     *       it is either a recorded {@code hr.holiday} row (unambiguous) or the employee's own
     *       resolved non-workday (a weekend, or a six-day schedule's Sunday), and a schedule-derived
     *       non-workday does not become less certain just because nobody has also loaded the
     *       public-holiday calendar for the year.
     *   <li><b>The claim disagrees with the suggestion.</b> The employee's {@code dayType} field is
     *       a REQUEST the submit form pre-fills from the suggestion but leaves freely editable
     *       (owner ruling, 2026-08-08) — they may submit a value that disagrees with it, in EITHER
     *       direction, and the request is accepted either way. <b>This replaces</b> the previous
     *       behaviour of refusing an over-claim outright (400) when the calendar could actively
     *       disprove it — see this fix's PR body for why that refusal was removed. The disagreement
     *       is flagged here instead, for the approver to see before they decide.
     * </ol>
     *
     * <pre>
     * suggestion | claim         | note
     * ---------- | ------------- | -----------------------------------------------------------
     * WORKDAY    | year unloaded | "[รอตรวจสอบ] ..." -- calendar-unverified flag, regardless of claim
     * HOLIDAY    | (any)         | no calendar-unverified flag -- schedule/holiday already certain
     * (any)      | disagrees     | "[ไม่ตรงกับที่ระบบแนะนำ] ..." appended -- accepted, never a 400
     * (any)      | matches/blank | no disagreement flag
     * </pre>
     *
     * <p>An unrecognised non-blank claim string is still refused outright (400) by {@link
     * #parseOvertimeDayType}, called first, unconditionally -- that is a 400 about SYNTAX (the
     * value is not a recognised {@link OvertimeDayType} name), not about the claim being wrong, and
     * this fix does not relax it.
     */
    private String resolveDayTypeSubmitNote(String claim, OvertimeDayType suggested, LocalDate workDate) {
        OvertimeDayType claimedDayType = parseOvertimeDayType(claim);
        StringBuilder note = new StringBuilder();
        if (suggested == OvertimeDayType.WORKDAY && !holidayCalendar.hasHolidaysForYear(workDate.getYear())) {
            // Short and scannable on purpose -- see DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX's
            // Javadoc. Deliberately says nothing about what was claimed: the work date itself is
            // also omitted, since the same table row already shows it in a separate column.
            note.append(DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX)
                .append("ปฏิทินวันหยุดปี ").append(workDate.getYear())
                .append(" ยังไม่ได้โหลด อัตรา OT อาจไม่ถูกต้อง โปรดตรวจสอบ");
        }
        if (claimedDayType != null && claimedDayType != suggested) {
            if (note.length() > 0) {
                note.append(' ');
            }
            note.append(DAY_TYPE_CLAIM_DISAGREEMENT_NOTE_PREFIX)
                .append("พนักงานระบุ ").append(dayTypeLabel(claimedDayType))
                .append(" แต่ระบบแนะนำ ").append(dayTypeLabel(suggested))
                .append(" โปรดตรวจสอบก่อนอนุมัติ");
        }
        return note.length() == 0 ? null : note.toString();
    }

    private String dayTypeLabel(OvertimeDayType dayType) {
        return dayType == OvertimeDayType.HOLIDAY ? "วันหยุด (3x)" : "วันทำงานปกติ (1.5x)";
    }

    /**
     * Parses {@code value} as an {@link OvertimeDayType}, or {@code null} for "nothing supplied"
     * (blank/{@code null} input) — same shape as {@link #parseStatus}. Throws 400 for anything
     * non-blank that isn't a recognised {@link OvertimeDayType} name.
     *
     * <p>Pure syntax parsing — it grants no semantic authority by itself. Two call sites share it
     * with very different trust, and the trust boundary lives at the CALL SITE, not here:
     *
     * <ul>
     *   <li>{@link #resolveDayTypeSubmitNote} uses it on {@code SubmitOvertimeRequest.dayType}, an
     *       unauthenticated REQUEST that must never reach {@code pay_rate_multiplier};
     *   <li>{@link #managerApprove}/{@link #ceoDirectApprove} use it on {@code
     *       ApproveOvertimeRequest.dayType}, from an actor who has already passed THIS request's own
     *       approve authorization, where the parsed result IS trusted as the pay input.
     * </ul>
     *
     * Do not add a shortcut here that lets one caller's input reach the other's effect.
     */
    private OvertimeDayType parseOvertimeDayType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OvertimeDayType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทวันทำงานล่วงเวลาไม่ถูกต้อง");
        }
    }

    /**
     * Appends -- never replaces -- a day-type flag {@link #resolveDayTypeSubmitNote} wrote into
     * {@code calculation_note} at submit time (either the calendar-unverified flag or the
     * claim-disagreement flag, see {@link #isDayTypeFlagNote}). {@link
     * OvertimeRepository#managerApprove} / {@link OvertimeRepository#ceoDirectApprove} overwrite
     * {@code calculation_note} wholesale with whatever this method's caller ({@link #calculate})
     * returns, so if a flag is present in {@code request.calculationNote()} it must be folded into
     * the new approval-time text here, or approval silently erases the flag the approver was
     * supposed to review.
     */
    private String preserveDayTypeClaimFlag(OvertimeRequestDto request, String approvalCalculationNote) {
        String submitTimeNote = request.calculationNote();
        if (isDayTypeFlagNote(submitTimeNote)) {
            return submitTimeNote + " " + approvalCalculationNote;
        }
        return approvalCalculationNote;
    }

    /**
     * True when {@code note} is a submit-time day-type flag this class wrote -- either the
     * calendar-unverified flag ({@link #DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX}) or the
     * claim-disagreement flag ({@link #DAY_TYPE_CLAIM_DISAGREEMENT_NOTE_PREFIX}). {@link
     * #resolveDayTypeSubmitNote} always puts one of these two prefixes FIRST when it produces any
     * note at all (even when both conditions fire, the unverified prefix leads), so checking the
     * start of the string is sufficient to recognise either one.
     */
    private boolean isDayTypeFlagNote(String note) {
        return note != null
            && (note.startsWith(DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX)
                || note.startsWith(DAY_TYPE_CLAIM_DISAGREEMENT_NOTE_PREFIX));
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
        // A2 (OT UAT defect #3): the redesigned submit form offers only two date pickers (วันที่ทำ
        // OT / วันที่สิ้นสุด), which together can express at most a next-day window -- so the API
        // must refuse anything longer, or a caller bypassing the form could still submit a
        // multi-day window the UI can never produce. Checked in BUSINESS_ZONE, matching how
        // startWorkDate is derived just above.
        LocalDate endWorkDate = request.plannedEndAt().atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        if (endWorkDate.isAfter(request.workDate().plusDays(1))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาสิ้นสุดการทำงานล่วงเวลาต้องอยู่ในวันที่ทำงานหรือวันถัดไปเท่านั้น");
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
            // A3 (OT UAT defect #4): state the outcome positively -- who must review this -- rather
            // than naming the missing stage. Mirrored verbatim in mockApi.js.
            throw new ApiException(HttpStatus.FORBIDDEN, "คำขอนี้ต้องให้ CEO พิจารณาเท่านั้น");
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

    /**
     * Single choke point for reading one request back out of the repository -- every mutation
     * method (submit/approve/reject/cancel) routes its "before"/"after" DTOs through here, which is
     * what makes it the right place to attach {@link OvertimeRequestDto#suggestedDayType} (see
     * {@link #withSuggestedDayType}): every DTO this class hands to a controller or an audit record
     * carries a freshly-computed suggestion, never a stale one.
     */
    private OvertimeRequestDto requireRequest(long id) {
        OvertimeRequestDto request = overtimeRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอทำงานล่วงเวลานี้"));
        return withSuggestedDayType(request, suggestDayType(request.employeeId(), request.workDate()));
    }

    /**
     * Reconstructs {@code request} with {@link OvertimeRequestDto#suggestedDayType} attached.
     * {@link OvertimeRepository#mapRequest} cannot compute the suggestion itself -- it is a plain
     * SQL row mapper with no {@link WorkScheduleResolver} -- so this class attaches it to every DTO
     * leaving here instead (see {@link #requireRequest}, {@link #attachSuggestions}).
     *
     * <p>A plain field-by-field copy: {@link OvertimeRequestDto} is a record with no generated
     * "with"-style mutator. Keep this in sync if a field is ever added to that record -- the
     * compiler will refuse to build this method as soon as the constructor arity disagrees, which
     * is the intended safety net for an otherwise easy field to drop by accident.
     */
    private OvertimeRequestDto withSuggestedDayType(OvertimeRequestDto request, OvertimeDayType suggested) {
        return new OvertimeRequestDto(
            request.id(),
            request.employeeId(),
            request.employeeCode(),
            request.employeeName(),
            request.workDate(),
            request.plannedStartAt(),
            request.plannedEndAt(),
            request.plannedMinutes(),
            request.dayType(),
            request.payRateMultiplier(),
            request.reason(),
            request.status(),
            request.actualStartAt(),
            request.actualEndAt(),
            request.actualMinutes(),
            request.payableMinutes(),
            request.calculationNote(),
            request.payrollMonth(),
            request.requestedById(),
            request.requestedByName(),
            request.requestedAt(),
            request.managerApprovedBy(),
            request.managerApprovedByName(),
            request.managerApprovedAt(),
            request.ceoApprovedBy(),
            request.ceoApprovedByName(),
            request.ceoApprovedAt(),
            request.reviewedById(),
            request.reviewedByName(),
            request.reviewedAt(),
            request.reviewerNote(),
            request.cancelledAt(),
            request.managerEmployeeId(),
            request.managerName(),
            request.hasManagerApprover(),
            request.createdAt(),
            request.updatedAt(),
            request.pendingApproverRole(),
            request.pendingApproverName(),
            suggested.name()
        );
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

        // A3 (OT UAT defect #4): neither message names the missing manager stage anymore -- the
        // employee/CEO need to know who holds the request, not that a stage is absent. The title
        // on the CEO notification below already says "รอ CEO อนุมัติ", so the body needs nothing
        // extra to make that point.
        String title = "ส่งคำขอ OT แล้ว";
        String message = "คำขอ OT วันที่ " + request.workDate()
            + (goesToCeo ? " ถูกส่งให้ CEO พิจารณาแล้ว" : " ถูกส่งให้ผู้จัดการตรวจสอบแล้ว");
        notificationService.notify(request.employeeId(), "OVERTIME_SUBMITTED", title, message, "/overtime", true);

        if (goesToCeo) {
            for (Long ceoEmployeeId : ceoApprovers.findEmployeeIds()) {
                notificationService.notify(
                    ceoEmployeeId,
                    "OVERTIME_PENDING_CEO",
                    "มีคำขอ OT รอ CEO อนุมัติ",
                    request.employeeName() + " ส่งคำขอ OT วันที่ " + request.workDate(),
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
        for (Long ceoEmployeeId : ceoApprovers.findEmployeeIds()) {
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

    private String note(ApproveOvertimeRequest request) {
        return request == null || request.reviewerNote() == null || request.reviewerNote().isBlank()
            ? null
            : request.reviewerNote().trim();
    }
}
