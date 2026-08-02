package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.specialmoney.SpecialMoneyPolicyEvaluator;

@Service
public class LeaveService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> LEAVE_ATTACHMENT_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/png"
    );
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");
    private static final Set<String> REVIEW_ALL_ROLES = Set.of("hr");
    private static final Set<LeaveStatus> ACTIVE_QUOTA_STATUSES = Set.of(LeaveStatus.SUBMITTED, LeaveStatus.APPROVED);
    // Sub-day leave (2026-07-25): day-fraction = clock-hours(start,end) / 8, no lunch subtraction
    // (decided rule -- see docs/agent-handoffs), rounded HALF_UP to 2dp, capped at 1.00 whole day.
    // Times must fall within the standard workday, matching the paper form's printed hours.
    private static final BigDecimal STANDARD_WORKDAY_MINUTES = BigDecimal.valueOf(8 * 60);
    private static final LocalTime WORKDAY_START = LocalTime.of(8, 30);
    private static final LocalTime WORKDAY_END = LocalTime.of(17, 30);
    private static final BigDecimal FULL_DAY = new BigDecimal("1.00");

    private final LeaveRepository leaveRepository;
    private final LeaveAttachmentRepository leaveAttachments;
    private final FileStorageService fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    // §5 leave-rules-as-data (V116): advance notice used to be a single global
    // app.leave.advance-notice-days property (AppProperties.Leave), which was wrong for every type
    // except roughly VACATION's neighbourhood. That dependency is removed cleanly here in favour of
    // LeaveTypeDto#advanceNoticeDays -- there is no longer any global fallback to preserve, since
    // hr.leave_type.advance_notice_days is NOT NULL DEFAULT 0 and every row (including the ones this
    // migration adds) has an explicit value.
    @Autowired
    public LeaveService(LeaveRepository leaveRepository,
                        LeaveAttachmentRepository leaveAttachments,
                        FileStorageService fileStorage,
                        AuditService auditService,
                        NotificationService notificationService) {
        this(leaveRepository, leaveAttachments, fileStorage, auditService, notificationService,
            Clock.system(BUSINESS_ZONE));
    }

    LeaveService(LeaveRepository leaveRepository,
                 LeaveAttachmentRepository leaveAttachments,
                 FileStorageService fileStorage,
                 AuditService auditService,
                 NotificationService notificationService,
                 Clock clock) {
        this.leaveRepository = leaveRepository;
        this.leaveAttachments = leaveAttachments;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public List<LeaveRequestDto> list(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            String requestedStatus) {
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveTo = toDate == null ? today.plusMonths(1) : toDate;
        LocalDate effectiveFrom = fromDate == null ? today.withDayOfMonth(1) : fromDate;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น");
        }

        Long employeeId = requestedEmployeeId;
        Long managerEmployeeId = null;
        if (!canViewAll(user)) {
            managerEmployeeId = requireEmployeeId(user);
            if (requestedEmployeeId != null && !canAccessEmployee(managerEmployeeId, requestedEmployeeId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }

        return leaveRepository.findRequests(new LeaveFilter(
            employeeId,
            managerEmployeeId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus)
        ));
    }

    public List<LeaveEmployeeOption> employeeOptions(UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        return leaveRepository.findEmployeeOptions(actorEmployeeId, canViewAll(user));
    }

    public List<LeaveTypeDto> leaveTypes() {
        return leaveRepository.findLeaveTypes();
    }

    public List<LeaveBalanceDto> balances(UserPrincipal user, Long requestedEmployeeId, Integer requestedYear) {
        long actorEmployeeId = requireEmployeeId(user);
        long employeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (!canViewAll(user) && !canAccessEmployee(actorEmployeeId, employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        validateEmployee(employeeId);
        int year = requestedYear == null ? LocalDate.now(clock).getYear() : requestedYear;
        return leaveRepository.findLeaveTypes().stream()
            .map(type -> balanceFor(employeeId, year, type))
            .toList();
    }

    /**
     * Paper-form (ใบลาหยุด F-HR-020) autofill for the contact-during-leave block, plus read-only
     * position/department/division -- same access predicate as {@link #balances}: own record, HR/CEO,
     * or the employee's direct manager.
     */
    public LeaveContactDefaultsDto contactDefaults(UserPrincipal user, Long requestedEmployeeId) {
        long actorEmployeeId = requireEmployeeId(user);
        long employeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (!canViewAll(user) && !canAccessEmployee(actorEmployeeId, employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        validateEmployee(employeeId);
        return leaveRepository.findContactDefaults(employeeId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน"));
    }

    @Transactional
    public LeaveRequestDto submit(SubmitLeaveRequest request, UserPrincipal user) {
        return submit(request, null, user);
    }

    @Transactional
    public LeaveRequestDto submit(SubmitLeaveRequest request, MultipartFile attachment, UserPrincipal user) {
        validateSubmitRequest(request);
        long actorEmployeeId = requireEmployeeId(user);
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        LeaveTypeDto leaveType = requireLeaveType(request.leaveTypeCode());
        validateDateRange(request.startDate(), request.endDate());
        validateSubDayTimes(request);

        BigDecimal totalDays = computeTotalDays(request);
        int quotaYear = request.startDate().getYear();
        BigDecimal remainingBefore = remainingDays(employeeId, leaveType, quotaYear);
        boolean hasAttachment = attachment != null && !attachment.isEmpty();
        // Leave -> payroll unpaid-day deduction (2026-07-23); §5 leave-rules-as-data (V116) added the
        // once-per-employment/min-service/max-consecutive-days/per-type-notice gates. The gate no
        // longer auto-rejects purely for exceeding quota. It approves and splits the requested days
        // into paidDays (bounded by both the remaining statutory quota AND, if the type has one, the
        // remaining paid-days-cap allowance -- see boundByPaidCap) and unpaidDays (no-work-no-pay,
        // deducted downstream in payroll at base/30 per unpaid WORKING day -- see
        // PayrollCalculator#unpaidLeaveDeduction). See autoRejectNote for the full list of remaining
        // auto-reject reasons. See docs/agent-handoffs for the HR/legal sign-off caveat the
        // quota-based split still needs before it drives a real payroll run.
        String systemNote = autoRejectNote(leaveType, employeeId, request.startDate(), request.endDate(), hasAttachment);
        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;
        BigDecimal paidDays;
        BigDecimal unpaidDays;
        BigDecimal remainingAfter;
        if (status == LeaveStatus.APPROVED) {
            // paidDays consumes from the request's earliest working days first (chronological order):
            // that is the only ordering an aggregate paid/unpaid split can represent, and it matches
            // the natural reading of "day N onward went unpaid". See LeaveDayMath.
            BigDecimal quotaBoundedPaidDays = remainingBefore.min(totalDays).max(BigDecimal.ZERO);
            // §5.4 MATERNITY-shaped rule (V116): paid_days_cap bounds how many of THOSE days are paid,
            // independently of the quota -- a 98-day MATERNITY request (98-day quota, 45-day cap)
            // splits 45 paid / 53 unpaid even though the full 98 days fit inside the quota itself.
            paidDays = boundByPaidCap(employeeId, leaveType, quotaYear, quotaBoundedPaidDays);
            unpaidDays = totalDays.subtract(paidDays);
            // review fix (V116): remainingAfter tracks QUOTA consumption, not money paid -- it must be
            // derived from quotaBoundedPaidDays (== remainingDays()'s own min(remaining, totalDays)
            // formula), NOT from the paid-cap-narrowed `paidDays`. Using `paidDays` here understated
            // quota consumption for any capped type: a 98-day MATERNITY request (98-day quota, 45-day
            // cap) would have stored quota_remaining_after = 98-45 = 53, while the very next
            // remainingDays() call (which sums total_days, not paid_days) reports 0 -- a stored value
            // that lies about how much quota is left, and that a UI could genuinely surface to the
            // employee.
            remainingAfter = remainingBefore.subtract(quotaBoundedPaidDays).max(BigDecimal.ZERO);
        } else {
            paidDays = BigDecimal.ZERO;
            unpaidDays = BigDecimal.ZERO;
            remainingAfter = remainingBefore;
        }

        ResolvedContact contact = resolveContact(employeeId, request);
        long id;
        try {
            id = leaveRepository.create(
                employeeId,
                actorEmployeeId,
                request,
                totalDays,
                paidDays,
                unpaidDays,
                quotaYear,
                status,
                remainingBefore,
                remainingAfter,
                systemNote,
                contact.houseNo(),
                contact.subdistrict(),
                contact.district(),
                contact.province(),
                contact.phone()
            );
        } catch (DuplicateKeyException e) {
            // §5.6 once-per-employment (V116) race backstop: ux_leave_once_per_employment catches a
            // concurrent second submission that slipped past the Java-level check in autoRejectNote
            // above (both requests read "no existing claim" before either had committed). The
            // Java-level check is what produces the normal AUTO_REJECTED-with-systemNote UX; this is
            // only the last-resort guard when two submissions genuinely race.
            //
            // NOTE: this maps ANY DuplicateKeyException thrown by leaveRepository.create to the
            // once-per-employment message -- safe today, since ux_leave_once_per_employment is the
            // only unique constraint hr.leave_request has. If a future migration adds another unique
            // index on this table, this catch will need to inspect the constraint name (or a similar
            // discriminator) before it can keep assuming every DuplicateKeyException here means this.
            throw new ApiException(HttpStatus.CONFLICT, "การลาประเภทนี้ใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน และมีคำขอที่ใช้สิทธิ์นี้ไปแล้ว");
        }
        if (hasAttachment) {
            FileStorageService.StoredFile storedFile = fileStorage.store("leave", id, attachment, LEAVE_ATTACHMENT_MIME_TYPES);
            LeaveAttachmentDto savedAttachment = leaveAttachments.save(
                id,
                storedFile.fileName(),
                storedFile.filePath(),
                storedFile.mimeType(),
                storedFile.fileSize(),
                actorEmployeeId
            );
            leaveRepository.attachFile(id, savedAttachment.id());
        }
        LeaveRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_LEAVE_REQUEST", "leave_request", id, null, created);
        notifyAfterSubmit(created, status);
        return created;
    }

    @Transactional
    public LeaveRequestDto approve(long id, ReviewLeaveRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        LeaveRequestDto existing = requireRequest(id);
        requireReviewer(existing.employeeId(), actorEmployeeId, user);
        requireStatus(existing, LeaveStatus.SUBMITTED);
        int updated = leaveRepository.approve(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอลานี้ได้รับการพิจารณาไปแล้ว");
        }
        LeaveRequestDto after = requireRequest(id);
        auditService.record(user, "APPROVE_LEAVE_REQUEST", "leave_request", id, existing, after);
        notificationService.notify(
            after.employeeId(),
            "LEAVE_APPROVED",
            "คำขอลาได้รับการอนุมัติ",
            "คำขอลา " + after.leaveTypeNameTh() + " วันที่ " + after.startDate() + " ถึง " + after.endDate()
                + " ได้รับการอนุมัติแล้ว เหลือโควตา " + formatDays(after.quotaRemainingAfter()) + " วัน",
            "/leave",
            true);
        return after;
    }

    @Transactional
    public LeaveRequestDto reject(long id, ReviewLeaveRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        LeaveRequestDto existing = requireRequest(id);
        requireReviewer(existing.employeeId(), actorEmployeeId, user);
        requireStatus(existing, LeaveStatus.SUBMITTED);
        int updated = leaveRepository.reject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอลานี้ได้รับการพิจารณาไปแล้ว");
        }
        LeaveRequestDto after = requireRequest(id);
        auditService.record(user, "REJECT_LEAVE_REQUEST", "leave_request", id, existing, after);
        notificationService.notify(
            after.employeeId(),
            "LEAVE_REJECTED",
            "คำขอลาถูกปฏิเสธ",
            "คำขอลา " + after.leaveTypeNameTh() + " วันที่ " + after.startDate() + " ถึง " + after.endDate()
                + " ถูกปฏิเสธ: " + (after.reviewerNote() == null ? "กรุณาติดต่อ HR" : after.reviewerNote()),
            "/leave",
            true);
        return after;
    }

    @Transactional
    public LeaveRequestDto cancel(long id, ReviewLeaveRequest request, UserPrincipal user) {
        LeaveRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        boolean reviewer = canReviewEmployee(existing.employeeId(), actorEmployeeId, user);
        if (!reviewer && existing.employeeId() != actorEmployeeId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!reviewer && !"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "พนักงานยกเลิกได้เฉพาะคำขอลาที่ยังไม่ได้รับการพิจารณาเท่านั้น");
        }
        if (!"SUBMITTED".equals(existing.status()) && !"APPROVED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ยกเลิกได้เฉพาะคำขอลาที่ยังอยู่ระหว่างพิจารณาเท่านั้น");
        }

        int updated = leaveRepository.cancel(id, reviewer ? actorEmployeeId : null, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอลานี้ไม่สามารถยกเลิกได้แล้ว");
        }
        // Cancel-after-close reversal: uses `existing` (the pre-cancel snapshot), not the freshly
        // cancelled row -- it still carries the paidDays/unpaidDays that were actually granted.
        recordPayrollCorrectionIfNeeded(existing);
        LeaveRequestDto after = requireRequest(id);
        auditService.record(user, "CANCEL_LEAVE_REQUEST", "leave_request", id, existing, after);
        return after;
    }

    /**
     * Cancel-after-close reversal (2026-07-23; AUTO-REFUND added 2026-07-23 same day, owner
     * decision -- the original record-and-surface-only v1 was not enough). Cancelling a leave
     * request is allowed unconditionally regardless of whether it overlaps an already-PROCESSED
     * payroll month (nothing above this method blocks that). Once a leave's month has been
     * processed, though, its unpaid-day deduction already landed in the employee's net pay for a
     * closed period -- undoing that in place is out of scope here. Instead, this records an
     * auditable "credit owed" row per affected processed month in {@code
     * hr.leave_payroll_correction}.
     *
     * <p>This method only ever WRITES a pending correction (never resolves one) -- resolution is
     * entirely {@code PayrollService}'s concern, on the read side: {@code
     * PayrollService#suggestedInputs} surfaces the unresolved total as an early heads-up (unscoped,
     * independent of any specific run), while {@code PayrollService#preview}/{@code #process}
     * auto-apply it as a real pre-tax credit the NEXT time payroll runs for this employee, and
     * {@code #process} marks the consumed correction(s) resolved (sets {@code resolved_at} /
     * {@code resolved_payroll_period_id}) in the same transaction. See {@code
     * LeaveRepository#findRefundableUnpaidDaysByEmployee}/{@code #resolvePendingCorrections} and
     * {@code PayrollCalculator}'s {@code leaveRefundDays}/{@code leaveDeductionRefund} handling for
     * the full mechanism.
     */
    private void recordPayrollCorrectionIfNeeded(LeaveRequestDto cancelled) {
        if (!"APPROVED".equals(cancelled.status())) {
            return;
        }
        BigDecimal unpaidDays = cancelled.unpaidDays();
        if (unpaidDays == null || unpaidDays.signum() <= 0) {
            return;
        }
        BigDecimal paidDays = cancelled.paidDays() == null ? BigDecimal.ZERO : cancelled.paidDays();
        BigDecimal totalDays = cancelled.totalDays() == null ? BigDecimal.ZERO : cancelled.totalDays();
        Map<LocalDate, BigDecimal> unpaidByMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            cancelled.startDate(), cancelled.endDate(), paidDays, totalDays);
        if (unpaidByMonth.isEmpty()) {
            return;
        }
        Set<LocalDate> processedMonths = leaveRepository.findProcessedPayrollMonths(unpaidByMonth.keySet());
        for (LocalDate month : processedMonths) {
            BigDecimal days = unpaidByMonth.get(month);
            if (days != null && days.signum() > 0) {
                leaveRepository.recordPayrollCorrection(cancelled.id(), cancelled.employeeId(), month, days);
            }
        }
    }

    private LeaveBalanceDto balanceFor(long employeeId, int year, LeaveTypeDto type) {
        BigDecimal approved = leaveRepository.sumUsedDays(employeeId, type.code(), year, Set.of(LeaveStatus.APPROVED));
        BigDecimal pending = leaveRepository.sumUsedDays(employeeId, type.code(), year, Set.of(LeaveStatus.SUBMITTED));
        BigDecimal remaining = type.annualQuotaDays().subtract(approved).subtract(pending).max(BigDecimal.ZERO);
        return new LeaveBalanceDto(
            type.code(),
            type.nameTh(),
            type.nameEn(),
            type.annualQuotaDays(),
            approved,
            pending,
            remaining,
            type.requiresAttachment()
        );
    }

    private BigDecimal remainingDays(long employeeId, LeaveTypeDto leaveType, int quotaYear) {
        BigDecimal used = leaveRepository.sumUsedDays(employeeId, leaveType.code(), quotaYear, ACTIVE_QUOTA_STATUSES);
        return leaveType.annualQuotaDays().subtract(used).max(BigDecimal.ZERO);
    }

    /**
     * §5.4-shaped paid-days-cap gate (V116): bounds an already quota-bounded candidate paidDays
     * figure by what remains of the type's OWN paid allowance this quota year (independent of the
     * quota itself). {@code paidDaysCap == null} means "no separate cap" (today's behaviour for
     * SICK/VACATION/PERSONAL) -- every quota-bounded day is paid, unchanged.
     */
    private BigDecimal boundByPaidCap(long employeeId, LeaveTypeDto leaveType, int quotaYear, BigDecimal candidatePaidDays) {
        if (leaveType.paidDaysCap() == null) {
            return candidatePaidDays;
        }
        BigDecimal paidUsed = leaveRepository.sumPaidDays(employeeId, leaveType.code(), quotaYear, ACTIVE_QUOTA_STATUSES);
        BigDecimal remainingPaidAllowance = leaveType.paidDaysCap().subtract(paidUsed).max(BigDecimal.ZERO);
        return candidatePaidDays.min(remainingPaidAllowance);
    }

    /**
     * §5.2 PERSONAL "passed probation" gate (review fix, V116). Extracted to its own method so the
     * decision is unit-testable in isolation from the rest of {@link #autoRejectNote}'s branches --
     * see {@code LeaveServiceTest} for the Mockito-level reject/allow/NULL-hire_date/
     * NULL-probation_days/probation_days=0 coverage, and {@code LeaveTypeRuleIntegrationTest} for
     * the same cases proven through the real repository (the NULL-column SQL mapping is exactly
     * what Mockito cannot verify).
     *
     * <p>Resolves "passed probation" the SAME way
     * {@code SpecialMoneyPolicyEvaluator#evaluateStandardProbationEligibility} already does for
     * special-money aid: {@code hire_date + probation_days}, where {@code probation_days} falls
     * back to {@link SpecialMoneyPolicyEvaluator#DEFAULT_PROBATION_DAYS} when NULL on the employee
     * row. Referencing that constant directly (not a duplicated literal) is what actually prevents
     * the two rules drifting apart.
     *
     * <p>{@code probation_days == 0} means eligible from the hire date itself (no waiting period) --
     * handled by the plain {@code plusDays(0)} arithmetic below, not a special case. A NULL
     * hire_date fails closed (returns a rejection), the same direction as every other eligibility
     * gate in this class -- it must never silently pass.
     *
     * <p>DECISION, unlike {@code SpecialMoneyPolicyEvaluator}: this does NOT consult
     * {@code hr.employee.confirm_date}. The correction this implements was scoped to
     * {@code hire_date + probation_days}; whether an explicit {@code confirm_date} should also
     * override PERSONAL eligibility is a separate policy question, left open rather than silently
     * assumed.
     *
     * @return a rejection message, or {@code null} if the employee has passed probation.
     */
    private String personalProbationRejectionNote(long employeeId, LocalDate startDate) {
        Optional<LocalDate> hireDate = leaveRepository.findHireDate(employeeId);
        if (hireDate.isEmpty()) {
            return "Your hire date is not on file, so probation status cannot be verified. "
                + "Contact HR to record it before PERSONAL leave can be used.";
        }
        int probationDays = leaveRepository.findProbationDays(employeeId)
            .orElse(SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS);
        LocalDate probationEndsOn = hireDate.get().plusDays(probationDays);
        if (probationEndsOn.isAfter(startDate)) {
            return "PERSONAL leave requires having passed probation (expected " + probationEndsOn
                + "). Contact HR if this is an exception.";
        }
        return null;
    }

    /**
     * §5 leave-rules-as-data (V116). Checks run in this order -- categorical eligibility first
     * (once-per-employment, minimum service), then request-shape (max consecutive days), then
     * document/timing checks (SICK certificate, advance notice) -- so the surfaced systemNote is
     * always the most fundamental reason the request cannot be paid, not an incidental one. Only the
     * first violation found is returned; see the individual checks below for what each one means and
     * the decisions behind it.
     */
    private String autoRejectNote(LeaveTypeDto leaveType, long employeeId, LocalDate startDate, LocalDate endDate, boolean hasAttachment) {
        // §5.6 once-per-employment (ORDINATION today). Java-level check; ux_leave_once_per_employment
        // (V116) is the race-proof DB backstop -- see the DuplicateKeyException catch in #submit.
        if (leaveType.oncePerEmployment() && leaveRepository.hasOutstandingOrGrantedRequest(employeeId, leaveType.code())) {
            return "This leave type may be used only once during your employment, and a claim for it already exists.";
        }

        // §5.3 minimum SERVICE DURATION (months since hr.employee.hire_date). This is genuinely
        // different from PERSONAL's "passed probation" gate just below -- VACATION/ORDINATION state
        // an N-year/month tenure requirement, not a probation-length one -- which is why PERSONAL's
        // min_service_months is 0 (seeded, V116) and does not reach this branch at all; see that
        // migration's PERSONAL comment. DECISION: a NULL hire_date does NOT silently pass -- eligibility
        // cannot be verified, so the request is rejected with an actionable message, the same
        // fail-closed direction as every other eligibility gate in this method.
        if (leaveType.minServiceMonths() > 0) {
            Optional<LocalDate> hireDate = leaveRepository.findHireDate(employeeId);
            if (hireDate.isEmpty()) {
                return "Your hire date is not on file, so eligibility for " + leaveType.nameEn()
                    + " cannot be verified. Contact HR to record it before this leave type can be used.";
            }
            long completedMonths = ChronoUnit.MONTHS.between(hireDate.get(), startDate);
            if (completedMonths < leaveType.minServiceMonths()) {
                return leaveType.nameEn() + " requires at least " + leaveType.minServiceMonths()
                    + " month(s) of completed service. Contact HR if this is an exception.";
            }
        }

        // §5.2 PERSONAL "passed probation" gate (review fix, V116): hardcoded to PERSONAL's code,
        // the same way the SICK certificate check below is hardcoded to SICK's code -- neither is a
        // per-type column the way quota/notice/consecutive-days are. See
        // #personalProbationRejectionNote's Javadoc for the full rationale and decisions.
        if ("PERSONAL".equals(leaveType.code())) {
            String note = personalProbationRejectionNote(employeeId, startDate);
            if (note != null) {
                return note;
            }
        }

        // §5.2 "not more than 3 consecutive days" (PERSONAL) and any other type given a
        // max_consecutive_days cap. DECISION: "consecutive" is counted in CALENDAR days (end - start +
        // 1 inclusive), not working days -- LeaveDayMath's Mon-Fri-only counting is explicitly out of
        // scope for this branch (see CLAUDE.md), and a calendar-day span is the natural reading of "3
        // days in a row" for a short request that, by definition, rarely crosses a weekend anyway. A
        // sub-day request is always single-day (start_date = end_date, enforced by
        // validateSubDayTimes), so this never conflicts with the sub-day feature.
        if (leaveType.maxConsecutiveDays() != null) {
            long spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (BigDecimal.valueOf(spanDays).compareTo(leaveType.maxConsecutiveDays()) > 0) {
                return leaveType.nameEn() + " may not exceed " + formatDays(leaveType.maxConsecutiveDays())
                    + " consecutive day(s) per request. Contact HR if this is an exception.";
            }
        }

        if ("SICK".equals(leaveType.code()) && !hasAttachment) {
            return "Sick leave requires a medical certificate attachment. Attach the certificate or contact HR for help.";
        }

        // §5 advance notice, now per-type (hr.leave_type.advance_notice_days) instead of the removed
        // global app.leave.advance-notice-days property. DECISION: counted in CALENDAR days, not
        // working days -- same reasoning and same out-of-LeaveDayMath-scope boundary as the
        // consecutive-days check above; this is an unchanged behaviour carried over from the original
        // global-property version, which also compared plain calendar dates. A type with
        // advanceNoticeDays == 0 (SICK, MATERNITY, MILITARY, LEAVE_WITHOUT_PAY as seeded) skips this
        // check entirely, matching the old code's unconditional SICK exemption.
        int noticeDays = Math.max(0, leaveType.advanceNoticeDays());
        if (noticeDays > 0) {
            LocalDate earliestAllowed = LocalDate.now(clock).plusDays(noticeDays);
            if (startDate.isBefore(earliestAllowed)) {
                return "Leave requests must be submitted at least " + noticeDays
                    + " day(s) before the start date. Contact your manager or HR for urgent leave.";
            }
        }
        return null;
    }

    private void notifyAfterSubmit(LeaveRequestDto request, LeaveStatus status) {
        if (status == LeaveStatus.APPROVED) {
            boolean hasUnpaidDays = request.unpaidDays() != null && request.unpaidDays().signum() > 0;
            // review fix (V116): the unpaid portion no longer only comes from exceeding the quota --
            // paid_days_cap (e.g. MATERNITY: 98-day quota, 45-day paid cap) can produce unpaid days on
            // a request that never touched the quota limit at all. "เนื่องจากเกินโควตา" ("because it
            // exceeded quota") would be a false claim in that case, so the wording no longer names a
            // specific cause.
            String unpaidSuffix = hasUnpaidDays
                ? " (รวมวันลาไม่รับค่าจ้าง " + formatDays(request.unpaidDays()) + " วัน ตามเงื่อนไขของประเภทการลานี้)"
                : "";
            notificationService.notify(
                request.employeeId(),
                "LEAVE_AUTO_APPROVED",
                "คำขอลาได้รับการอนุมัติอัตโนมัติ",
                "คำขอลา " + request.leaveTypeNameTh() + " วันที่ " + request.startDate() + " ถึง "
                    + request.endDate() + " ได้รับการอนุมัติแล้ว เหลือโควตา "
                    + formatDays(request.quotaRemainingAfter()) + " วัน" + unpaidSuffix,
                "/leave",
                true);
            if (request.managerEmployeeId() != null) {
                notificationService.notify(
                    request.managerEmployeeId(),
                    "LEAVE_AUTO_APPROVED",
                    "ลูกทีมมีวันลาที่อนุมัติอัตโนมัติ",
                    request.employeeName() + " ลา " + request.leaveTypeNameTh() + " วันที่ "
                        + request.startDate() + " ถึง " + request.endDate(),
                    "/leave",
                    false);
            }
            return;
        }
        notificationService.notify(
            request.employeeId(),
            "LEAVE_AUTO_REJECTED",
            "คำขอลาไม่ผ่านเงื่อนไข",
            request.systemNote() == null ? "คำขอลาไม่ผ่านเงื่อนไข กรุณาติดต่อ HR" : request.systemNote(),
            "/leave",
            true);
    }

    private long resolveTargetEmployee(Long requestedEmployeeId, UserPrincipal user) {
        long actorEmployeeId = requireEmployeeId(user);
        long targetEmployeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (targetEmployeeId != actorEmployeeId
                && !canReviewEmployee(targetEmployeeId, actorEmployeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "พนักงานสามารถขอลาให้ตนเองหรือผู้ใต้บังคับบัญชาที่มีสิทธิ์เท่านั้น");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!leaveRepository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบข้อมูลพนักงาน");
        }
    }

    private void validateSubmitRequest(SubmitLeaveRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุคำขอลา");
        }
        if (request.startDate() == null || request.endDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่ลา");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลการลา");
        }
    }

    private LeaveTypeDto requireLeaveType(String value) {
        String code = value == null ? "" : value.trim().toUpperCase();
        return leaveRepository.findLeaveType(code)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ประเภทการลาไม่ถูกต้อง"));
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดการลาต้องไม่มาก่อนวันที่เริ่มต้น");
        }
        if (startDate.getYear() != endDate.getYear()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "คำขอลาต้องไม่คร่อมปีโควตา");
        }
    }

    private BigDecimal workingDaysBetween(LocalDate startDate, LocalDate endDate) {
        int days = LeaveDayMath.countWorkingDays(startDate, endDate);
        if (days <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน");
        }
        return BigDecimal.valueOf(days);
    }

    /**
     * Sub-day leave (2026-07-25): no times -> the existing whole-day weekday count. Times set ->
     * clock-hours(start,end) / 8 (STANDARD_WORKDAY_MINUTES), no lunch subtraction (decided rule),
     * rounded HALF_UP to 2dp, capped at 1.00 (a sub-day request can never exceed one whole day).
     * FULL_DAY (not BigDecimal.ONE) keeps the cap at scale 2, matching the NUMERIC(5,2) convention
     * every other day figure in this codebase uses.
     */
    private BigDecimal computeTotalDays(SubmitLeaveRequest request) {
        if (request.startTime() == null) {
            return workingDaysBetween(request.startDate(), request.endDate());
        }
        long minutes = Duration.between(request.startTime(), request.endTime()).toMinutes();
        BigDecimal fraction = BigDecimal.valueOf(minutes)
            .divide(STANDARD_WORKDAY_MINUTES, 2, RoundingMode.HALF_UP);
        return fraction.min(FULL_DAY);
    }

    /**
     * Sub-day leave (2026-07-25): startTime/endTime are optional, but if either is set both must be,
     * the request must be single-day on a WORKING day, endTime must be after startTime, and both must
     * fall within the standard workday (08:30-17:30) -- mirrors V90's chk_leave_time_* checks, giving
     * a clearer 400 before the DB constraint would ever fire. The weekday check matters: without it a
     * Saturday/Sunday half-day would be accepted (and could produce a payroll deduction for a
     * non-working day) while the identical whole-day request is rejected by workingDaysBetween.
     */
    private void validateSubDayTimes(SubmitLeaveRequest request) {
        LocalTime startTime = request.startTime();
        LocalTime endTime = request.endTime();
        if (startTime == null && endTime == null) {
            return;
        }
        if (startTime == null || endTime == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "การลาแบบระบุช่วงเวลาต้องระบุเวลาเริ่มต้นและเวลาสิ้นสุด");
        }
        if (!request.startDate().equals(request.endDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "การลาแบบระบุช่วงเวลาต้องเริ่มต้นและสิ้นสุดในวันเดียวกัน");
        }
        if (LeaveDayMath.countWorkingDays(request.startDate(), request.startDate()) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน");
        }
        if (!endTime.isAfter(startTime)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาสิ้นสุดการลาต้องอยู่หลังเวลาเริ่มต้น");
        }
        if (startTime.isBefore(WORKDAY_START) || startTime.isAfter(WORKDAY_END)
                || endTime.isBefore(WORKDAY_START) || endTime.isAfter(WORKDAY_END)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาลาต้องอยู่ในช่วงเวลาทำงาน (08:30-17:30)");
        }
    }

    /**
     * Paper-form (ใบลาหยุด F-HR-020) contact-during-leave autofill/override: per field, use what the
     * requester submitted if non-blank, else fall back to the employee's current address/phone. Missing
     * defaults (e.g. no address on file) simply leave the field null.
     */
    private ResolvedContact resolveContact(long employeeId, SubmitLeaveRequest request) {
        LeaveContactDefaultsDto defaults = leaveRepository.findContactDefaults(employeeId).orElse(null);
        return new ResolvedContact(
            pickContactValue(request.contactHouseNo(), defaults == null ? null : defaults.contactHouseNo()),
            pickContactValue(request.contactSubdistrict(), defaults == null ? null : defaults.contactSubdistrict()),
            pickContactValue(request.contactDistrict(), defaults == null ? null : defaults.contactDistrict()),
            pickContactValue(request.contactProvince(), defaults == null ? null : defaults.contactProvince()),
            pickContactValue(request.contactPhone(), defaults == null ? null : defaults.contactPhone())
        );
    }

    private String pickContactValue(String requestedValue, String defaultValue) {
        return requestedValue != null && !requestedValue.isBlank() ? requestedValue.trim() : defaultValue;
    }

    private record ResolvedContact(String houseNo, String subdistrict, String district, String province, String phone) {
    }

    private void requireReviewer(long employeeId, long actorEmployeeId, UserPrincipal user) {
        if (!canReviewEmployee(employeeId, actorEmployeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะฝ่ายบุคคลหรือหัวหน้างานโดยตรงของพนักงานเท่านั้นที่สามารถพิจารณาคำขอลาได้");
        }
    }

    private boolean canReviewEmployee(long employeeId, long actorEmployeeId, UserPrincipal user) {
        return canReviewAll(user) || isDirectManager(employeeId, actorEmployeeId);
    }

    private boolean canAccessEmployee(long actorEmployeeId, long employeeId) {
        return actorEmployeeId == employeeId || isDirectManager(employeeId, actorEmployeeId);
    }

    private boolean isDirectManager(long employeeId, long actorEmployeeId) {
        return leaveRepository.findEmployeeAccess(employeeId)
            .map(access -> access.active()
                && access.managerEmployeeId() != null
                && access.managerEmployeeId() == actorEmployeeId)
            .orElse(false);
    }

    private LeaveRequestDto requireRequest(long id) {
        return leaveRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอลานี้"));
    }

    private void requireStatus(LeaveRequestDto request, LeaveStatus status) {
        if (!status.name().equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอลานี้ได้รับการพิจารณาไปแล้ว");
        }
    }

    private boolean canViewAll(UserPrincipal user) {
        return user != null && VIEW_ALL_ROLES.contains(user.role());
    }

    private boolean canReviewAll(UserPrincipal user) {
        return user != null && REVIEW_ALL_ROLES.contains(user.role());
    }

    private Long requireEmployeeId(UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        return user.employeeId();
    }

    private LeaveStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LeaveStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "สถานะการลาไม่ถูกต้อง");
        }
    }

    private String note(ReviewLeaveRequest request) {
        return request == null || request.reviewerNote() == null || request.reviewerNote().isBlank()
            ? null
            : request.reviewerNote().trim();
    }

    private String formatDays(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
