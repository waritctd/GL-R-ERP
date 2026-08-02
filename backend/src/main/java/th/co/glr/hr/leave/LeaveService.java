package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    // §5.2/§5.3 pro-ration (V120, defect 1 fix): a first-year employee's quota is scaled by
    // completed months of service out of a full 12 -- see #employeeAnnualQuota.
    private static final int FULL_SERVICE_MONTHS = 12;
    private static final BigDecimal HALF_DAY_INCREMENT = new BigDecimal("0.5");

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
        // Schedule/holiday-aware working-day counting (2026-08-03): built ONCE per submission and
        // shared by every LeaveDayMath call below (validateSubDayTimes, computeTotalDays,
        // totalDaysByYear) -- see LeaveRepository#workingDayPredicate's javadoc for the fixed query
        // cost (two queries, not one per day) and LeaveDayMath's javadoc for the six-day-department/
        // holiday defects this closes.
        Predicate<LocalDate> isWorkingDay =
            leaveRepository.workingDayPredicate(employeeId, request.startDate(), request.endDate());
        validateSubDayTimes(request, leaveType.dayCountBasis(), isWorkingDay);

        BigDecimal totalDays = computeTotalDays(request, leaveType, isWorkingDay);
        int quotaYear = request.startDate().getYear();
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
        String systemNote = autoRejectNote(leaveType, employeeId, request.startDate(), request.endDate(), hasAttachment, totalDays);
        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;

        // V118 cross-year quota fix (2026-08-02): a request's days are attributed per calendar year
        // (almost always just one -- two only for a request spanning 31 Dec/1 Jan, e.g. a long
        // MATERNITY request -- see LeaveQuotaYearSplit). Each year's paidDays/unpaidDays/remaining
        // figures are computed INDEPENDENTLY against that year's own quota and paid_days_cap; see
        // LeaveQuotaYearSplit's Javadoc for the deliberate consequence of that design (a boundary-
        // straddling request can receive more total paid days than a same-year one would).
        Map<Integer, BigDecimal> requestedDaysByYear = LeaveDayMath.totalDaysByYear(
            request.startDate(), request.endDate(), totalDays, leaveType.dayCountBasis(), isWorkingDay);
        List<LeaveQuotaYearSplit> quotaYearSplits = new ArrayList<>();
        BigDecimal paidDays = BigDecimal.ZERO;
        BigDecimal unpaidDays = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> entry : requestedDaysByYear.entrySet()) {
            int year = entry.getKey();
            BigDecimal daysInYear = entry.getValue();
            // §5.2/§5.3 pro-ration (V120): the quota itself may depend on the employee's completed
            // service AS OF this request -- request.startDate() is used uniformly for every year in
            // a cross-year split (VACATION/PERSONAL never span a year boundary in practice: VACATION
            // has no max_consecutive_days cap but is a planned, short-notice-driven leave type in
            // practice, and PERSONAL is hard-capped at 3 consecutive days), matching how the
            // pre-existing min-service-months and PERSONAL-probation gates already evaluate tenure
            // as of the request's start date, not "today".
            BigDecimal remainingBeforeYear = remainingDays(employeeId, leaveType, year, request.startDate());
            BigDecimal paidDaysYear;
            BigDecimal unpaidDaysYear;
            BigDecimal remainingAfterYear;
            if (status == LeaveStatus.APPROVED) {
                // paidDaysYear consumes from THIS YEAR's earliest working days first (chronological
                // order, reset per year): the only ordering an aggregate paid/unpaid split can
                // represent, matching the natural reading of "day N of this year onward went unpaid".
                // See LeaveDayMath.
                BigDecimal quotaBoundedPaidDaysYear = remainingBeforeYear.min(daysInYear).max(BigDecimal.ZERO);
                // §5.4 MATERNITY-shaped rule (V116): paid_days_cap bounds how many of THOSE days are
                // paid, independently of the quota -- e.g. a 44-day slice of a MATERNITY request
                // still gets bounded by that same year's 45-day paid_days_cap.
                paidDaysYear = boundByPaidCap(employeeId, leaveType, year, quotaBoundedPaidDaysYear);
                unpaidDaysYear = daysInYear.subtract(paidDaysYear);
                // review fix (V116), preserved per-year: remainingAfterYear tracks QUOTA consumption,
                // not money paid -- derived from quotaBoundedPaidDaysYear, NOT from the
                // paid-cap-narrowed `paidDaysYear`. See the original review-fix comment (git blame)
                // for the full "98-45=53 lies about quota left" rationale, which applies identically
                // per year here.
                remainingAfterYear = remainingBeforeYear.subtract(quotaBoundedPaidDaysYear).max(BigDecimal.ZERO);
            } else {
                paidDaysYear = BigDecimal.ZERO;
                unpaidDaysYear = BigDecimal.ZERO;
                remainingAfterYear = remainingBeforeYear;
            }
            paidDays = paidDays.add(paidDaysYear);
            unpaidDays = unpaidDays.add(unpaidDaysYear);
            quotaYearSplits.add(new LeaveQuotaYearSplit(
                year, daysInYear, paidDaysYear, unpaidDaysYear, remainingBeforeYear, remainingAfterYear));
        }
        // hr.leave_request.quota_remaining_before/after (the PARENT columns) reflect ONLY the
        // request's nominal start year (quotaYear) -- see that table's V118 column comments. In the
        // rare case the start year itself received zero requested days (e.g. a request starting on
        // the last weekend of a year with no working days left in it before the boundary), fall back
        // to that year's own remainingDays() so the parent still carries a real, correct-for-that-year
        // figure rather than an arbitrary other year's.
        BigDecimal remainingBefore = quotaYearSplits.stream()
            .filter(split -> split.quotaYear() == quotaYear)
            .map(LeaveQuotaYearSplit::quotaRemainingBefore)
            .findFirst()
            .orElseGet(() -> remainingDays(employeeId, leaveType, quotaYear, request.startDate()));
        BigDecimal remainingAfter = quotaYearSplits.stream()
            .filter(split -> split.quotaYear() == quotaYear)
            .map(LeaveQuotaYearSplit::quotaRemainingAfter)
            .findFirst()
            .orElse(remainingBefore);

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
        // V118 cross-year quota fix: persist the per-year attribution now that the parent row exists
        // (leave_request_quota_year.leave_request_id is a FK to it). Same transaction as #create
        // above (this method is @Transactional).
        leaveRepository.insertQuotaYearSplits(id, quotaYearSplits);
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
        // V118 cross-year quota fix: reads the request's per-year attribution and composes the
        // per-month unpaid breakdown via LeaveDayMath#unpaidWorkingDaysByMonthAcrossYears, NOT the
        // parent's aggregate paidDays/totalDays -- see that method's Javadoc for why the aggregate
        // figures can no longer be fed into a single whole-request chronological-rank threshold once
        // paid-day consumption resets per calendar year (a request can be "paid, then unpaid" in an
        // earlier year and "paid" again from the start of a later one; the old aggregate-only call
        // would have misattributed which calendar days -- and so which months -- are unpaid in that
        // case). For every pre-V118 (single-year) request this produces the identical result as
        // before.
        //
        // §5.4 MATERNITY calendar-day counting (V119): the basis used to REVERSE a cancellation must
        // be the SAME basis used to GRANT it, or the recomputed unpaid-by-month figures would not
        // match what was actually deducted -- see LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth's
        // Javadoc for the identical decision on the forward (deduction) path. Falls back to
        // WORKING_DAYS if the leave type has since been deactivated/removed (defensive only; every
        // type a request could have been submitted under still exists in practice).
        LeaveDayCountBasis basis = leaveRepository.findLeaveType(cancelled.leaveTypeCode())
            .map(LeaveTypeDto::dayCountBasis)
            .orElse(LeaveDayCountBasis.WORKING_DAYS);
        List<LeaveQuotaYearSplit> quotaYearSplits = leaveRepository.findQuotaYearSplits(cancelled.id());
        // Schedule/holiday-aware working-day counting (2026-08-03): same reasoning as #submit --
        // the reversal must use the SAME per-employee working-day predicate that granted the
        // request, or the recomputed unpaid-by-month figures would not match what was actually
        // deducted (mirrors the basis decision in the comment above, one paragraph up).
        Predicate<LocalDate> isWorkingDay = leaveRepository.workingDayPredicate(
            cancelled.employeeId(), cancelled.startDate(), cancelled.endDate());
        Map<LocalDate, BigDecimal> unpaidByMonth = LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(
            cancelled.startDate(), cancelled.endDate(), quotaYearSplits, basis, isWorkingDay);
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
        // §5.2/§5.3 pro-ration (V120): a balance check reflects the employee's quota AS OF TODAY
        // (LocalDate.now(clock)), not the requested `year` -- the same "as of the reference date"
        // meaning #employeeAnnualQuota uses for a live submission (request.startDate()), just with
        // "today" standing in since a balance query has no request date of its own.
        BigDecimal quota = employeeAnnualQuota(type, employeeId, LocalDate.now(clock));
        BigDecimal remaining = quota.subtract(approved).subtract(pending).max(BigDecimal.ZERO);
        return new LeaveBalanceDto(
            type.code(),
            type.nameTh(),
            type.nameEn(),
            quota,
            approved,
            pending,
            remaining,
            type.requiresAttachment()
        );
    }

    private BigDecimal remainingDays(long employeeId, LeaveTypeDto leaveType, int quotaYear, LocalDate asOf) {
        BigDecimal used = leaveRepository.sumUsedDays(employeeId, leaveType.code(), quotaYear, ACTIVE_QUOTA_STATUSES);
        BigDecimal quota = employeeAnnualQuota(leaveType, employeeId, asOf);
        return quota.subtract(used).max(BigDecimal.ZERO);
    }

    /**
     * §5.2/§5.3 pro-ration for an employee's first year of service (V120, fix for defect 1). V116
     * seeded VACATION.min_service_months=12 as an outright eligibility floor -- refusing ALL vacation
     * leave to anyone under a year of service -- when the announcement's actual text grants a
     * pro-rated entitlement instead: "...(กรณีอายุงานไม่ถึงหนึ่งปีให้ลาได้เป็นสัดส่วนตามอายุงาน)"
     * ("in case service duration is under one year, leave is granted in proportion to service
     * duration"), stated identically for PERSONAL in §5.2. Applies only when {@link
     * LeaveTypeDto#proratedFirstYear()} is TRUE (VACATION, PERSONAL -- see V120's migration comment);
     * every other type returns its flat {@link LeaveTypeDto#annualQuotaDays()} unchanged.
     *
     * <p><b>INTERPRETATION, not stated company policy (pending owner confirmation -- flag this when
     * reporting the change, do not describe it as settled).</b> The announcement gives no formula,
     * only "เป็นสัดส่วนตามอายุงาน" ("in proportion to service duration"). This method reads that as:
     *
     * <pre>quota × (completed months of service, capped at 12, ÷ 12)</pre>
     *
     * rounded to the NEAREST 0.5 DAY (HALF_UP) -- 0.5 is the natural increment since sub-day leave
     * (V90) already makes the schema and UI support a half day; there is no reason to round to a
     * whole day and reintroduce the exact "denies more than it should / grants more than it should"
     * tension this fix exists to remove.
     *
     * <p><b>"Completed months of service" is measured as {@code hire_date} to {@code asOf}, capped at
     * {@value #FULL_SERVICE_MONTHS}, DELIBERATELY NOT bounded to the calendar quota year.</b> An
     * earlier draft of this method bounded the month-count to the calendar year being quoted (i.e.
     * {@code max(hireDate, 1 Jan of quotaYear)} through {@code min(asOf, 31 Dec of quotaYear)}), which
     * is wrong in both directions: it wrongly PRO-RATES a 10-year veteran's mid-year request (their
     * service "within the year" only reaches 12 months at the 31 Dec mark, understating a request
     * filed in July), and it wrongly grants a January-hired employee the FULL quota on day one (by 31
     * Dec they will have covered 12 calendar-year months, even though they have not yet completed a
     * single day of real tenure at the time of the request). Total tenure capped at 12, independent of
     * which calendar year's bucket the quota is being computed for, is the only reading that gets both
     * of those cases right and matches "อายุงานไม่ถึงหนึ่งปี" ("service duration under one year") at
     * face value: it is about how long the employee has actually worked, not where "today" falls
     * inside a calendar year.
     *
     * <p>Consequence, stated plainly: because {@code asOf} is the request's own start date (see the
     * call sites), a first-year employee's quota GROWS as the calendar year progresses -- two
     * VACATION requests filed months apart by the same not-yet-1-year employee can see a different
     * quota figure, larger for the later one. This is the natural reading of an accruing, tenure-based
     * entitlement (the employee has genuinely earned more by the later date), not a bug; flagged here
     * so it is not "corrected" into a single fixed-at-submission-year number without knowing this was
     * intentional.
     *
     * <p>A missing {@code hire_date} returns {@link BigDecimal#ZERO} rather than throwing -- the
     * submission path never reaches this method with a missing hire date (see the dedicated
     * proratedFirstYear/hire-date gate in {@link #autoRejectNote}, which fails the request closed
     * first); {@link #balanceFor}, which has no equivalent gate, simply reports a zero quota for a
     * balance display rather than crashing.
     */
    private BigDecimal employeeAnnualQuota(LeaveTypeDto leaveType, long employeeId, LocalDate asOf) {
        if (!leaveType.proratedFirstYear()) {
            return leaveType.annualQuotaDays();
        }
        Optional<LocalDate> hireDate = leaveRepository.findHireDate(employeeId);
        if (hireDate.isEmpty() || !hireDate.get().isBefore(asOf)) {
            return BigDecimal.ZERO;
        }
        long completedMonths = Math.min(FULL_SERVICE_MONTHS, ChronoUnit.MONTHS.between(hireDate.get(), asOf));
        if (completedMonths >= FULL_SERVICE_MONTHS) {
            return leaveType.annualQuotaDays();
        }
        BigDecimal fraction = BigDecimal.valueOf(completedMonths)
            .divide(BigDecimal.valueOf(FULL_SERVICE_MONTHS), 6, RoundingMode.HALF_UP);
        return roundToNearestHalfDay(leaveType.annualQuotaDays().multiply(fraction));
    }

    /** Rounds to the nearest 0.5, at NUMERIC(5,2) scale -- see {@link #employeeAnnualQuota}'s Javadoc. */
    private BigDecimal roundToNearestHalfDay(BigDecimal value) {
        return value.divide(HALF_DAY_INCREMENT, 0, RoundingMode.HALF_UP)
            .multiply(HALF_DAY_INCREMENT)
            .setScale(2, RoundingMode.HALF_UP);
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
     * §5.1 SICK certificate + filing-window + no-certificate tolerance (V124). Replaces the old
     * "SICK requires an attachment, full stop" rule with the combined reading of the announcement's
     * single sentence:
     *
     * <pre>
     * (ต้องมีใบรับรองแพทย์ และยื่นใบลาภายใน 3 วันทำการ) ได้ไม่เกิน 30 วันต่อปี โดยได้รับค่าจ้างตามปกติ
     * หากลาป่วยเกิน 30 วันจะไม่มีสิทธิได้รับค่าจ้าง สำหรับกรณีป่วยเพียงเล็กน้อยไม่ได้ไปพบแพทย์ บริษัท
     * อนุโลมให้ลาได้ไม่เกินเดือนละ 3 ครั้ง และต้องแจ้งให้หัวหน้างานทราบก่อนเวลาเริ่มงานในวันนั้น
     * </pre>
     *
     * <p><b>COMBINED DECISION TABLE</b> (certificate presence x filing-on-time x monthly tolerance):
     *
     * <pre>
     * | has cert | filed within window | occasions used this month | outcome                     |
     * |----------|----------------------|----------------------------|------------------------------|
     * | yes      | yes                  | n/a                        | ALLOW                        |
     * | yes      | no                   | n/a                        | REJECT -- late certificate   |
     * | no       | n/a                  | < tolerance (1st-3rd)      | ALLOW -- minor-illness exception |
     * | no       | n/a                  | >= tolerance (4th+)        | REJECT -- needs a certificate |
     * </pre>
     *
     * <p><b>The interaction the task exists to get right:</b> the filing-window clause is
     * grammatically part of the CERTIFICATE sentence, not the tolerance sentence -- so a
     * certificate-less request is judged ONLY against the monthly occasion count, NEVER against the
     * 3-working-day window. A certificate-less request filed long after its own start date is still
     * ALLOWED if the employee has not used all of this month's occasions -- the tolerance path is a
     * genuine substitute for the WHOLE certificate-plus-deadline requirement, not merely a substitute
     * for possessing the physical document while remaining bound by its deadline.
     * (INTERPRETATION, pending owner confirmation -- same caveat as every other §5 reading in this
     * class.)
     *
     * <p>A LATE-FILED certificate does NOT fall back to the no-certificate tolerance path even when
     * occasions remain this month -- the announcement's exception is explicitly for "ป่วยเพียงเล็กน้อย
     * ไม่ได้ไปพบแพทย์" (minor illness, no doctor visit). An employee who DID see a doctor and holds a
     * real certificate is not that case merely because they filed late, and letting a late
     * certificate "downgrade" into the tolerance bucket would let genuine, proven illness bypass the
     * filing deadline the announcement states for exactly that case.
     *
     * <p><b>Filing-window reference date:</b> the announcement names no explicit reference event.
     * This reads "ยื่นใบลาภายใน 3 วันทำการ" (file the leave form within 3 working days) as 3 working
     * days from the leave's own {@code startDate} -- {@code hr.leave_request} has no separate "date
     * of illness" field (see V124's migration comment), so start_date is the only available proxy.
     * "Now" is {@link #clock}'s {@code LocalDate.now(clock)} AT SUBMISSION, the same reference point
     * the advance-notice check two branches below already uses -- not {@code hr.leave_request
     * .requested_at}, which is not yet known until after {@link #submit}'s {@code INSERT}.
     *
     * <p><b>Working-day counting caveat:</b> {@link LeaveDayMath#addWorkingDays} counts Mon-Fri only,
     * with no holiday calendar -- a known, PRE-EXISTING, separately-owned defect (see CLAUDE.md and
     * {@link LeaveDayMath}'s class Javadoc). NOT fixed here. Unlike {@code advanceNoticeDays}
     * elsewhere in this file (deliberately CALENDAR-day, to sidestep this exact dependency), the
     * filing window is genuinely working-day per the announcement's own "วันทำการ" wording and
     * cannot avoid it.
     *
     * <p><b>Occasion counting:</b> an "occasion" (ครั้ง) is a REQUEST, not a day -- matches the
     * announcement's own "ครั้ง" wording (as opposed to "วัน" used everywhere else in §5.1). A
     * request's occasion month is its {@code startDate}'s calendar month, the same start_date-only
     * precedent {@code quotaYear} already uses (see {@link #submit}). Only certificate-less ({@code
     * attachment_id IS NULL}) SICK requests in {@link #ACTIVE_QUOTA_STATUSES} count -- a CANCELLED
     * occasion releases its slot (the employee never actually took uncertified leave that month),
     * matching the once-per-employment guard's identical cancel-releases-the-claim precedent (V116).
     * The count is computed fresh from {@code hr.leave_request} on every call (see {@link
     * LeaveRepository#countNoCertificateRequestsInMonth}) -- a rolling allowance, never a stored
     * counter that could drift.
     *
     * @return a rejection message, or {@code null} if the SICK-specific rule is satisfied (other
     *     gates in {@link #autoRejectNote} may still apply).
     */
    private String sickCertificateNote(LeaveTypeDto leaveType, long employeeId, LocalDate startDate, boolean hasAttachment) {
        if (hasAttachment) {
            Integer filingWindowDays = leaveType.certificateFilingWindowDays();
            if (filingWindowDays == null) {
                return null;
            }
            LocalDate deadline = LeaveDayMath.addWorkingDays(startDate, filingWindowDays);
            LocalDate today = LocalDate.now(clock);
            if (today.isAfter(deadline)) {
                return "Medical certificate must be filed within " + filingWindowDays
                    + " working day(s) of the leave start date (by " + deadline
                    + "). Contact HR if this is an exception.";
            }
            return null;
        }

        int tolerance = leaveType.noCertificateMonthlyTolerance();
        if (tolerance <= 0) {
            return "Sick leave requires a medical certificate attachment. Attach the certificate or contact HR for help.";
        }
        LocalDate monthStart = startDate.withDayOfMonth(1);
        LocalDate monthEndInclusive = monthStart.plusMonths(1).minusDays(1);
        int occasionsUsed = leaveRepository.countNoCertificateRequestsInMonth(
            employeeId, leaveType.code(), monthStart, monthEndInclusive, ACTIVE_QUOTA_STATUSES);
        if (occasionsUsed >= tolerance) {
            return "Sick leave without a medical certificate is limited to " + tolerance
                + " occasion(s) per calendar month; that allowance has already been used this month. "
                + "Attach a certificate or contact HR for help.";
        }
        return null;
    }

    /**
     * §5 leave-rules-as-data (V116, extended V120). Checks run in this order -- categorical
     * eligibility first (once-per-employment, missing hire_date on a pro-rated type, minimum
     * service, PERSONAL probation, the first-year total-days cap), then request-shape (max
     * consecutive days), then document/timing checks (SICK certificate, advance notice) -- so the
     * surfaced systemNote is always the most fundamental reason the request cannot be paid, not an
     * incidental one. Only the first violation found is returned; see the individual checks below
     * for what each one means and the decisions behind it.
     */
    private String autoRejectNote(LeaveTypeDto leaveType, long employeeId, LocalDate startDate, LocalDate endDate, boolean hasAttachment, BigDecimal totalDays) {
        // §5.6 once-per-employment (ORDINATION today). Java-level check; ux_leave_once_per_employment
        // (V116) is the race-proof DB backstop -- see the DuplicateKeyException catch in #submit.
        if (leaveType.oncePerEmployment() && leaveRepository.hasOutstandingOrGrantedRequest(employeeId, leaveType.code())) {
            return "This leave type may be used only once during your employment, and a claim for it already exists.";
        }

        // §5.2/§5.3 pro-ration (V120): #employeeAnnualQuota cannot compute a quota without hire_date
        // for a prorated_first_year type (VACATION, PERSONAL) -- fails CLOSED here, the same
        // direction as every other eligibility gate in this method, rather than letting
        // #employeeAnnualQuota silently return ZERO and produce a confusing all-unpaid approval with
        // no explanation. Runs before minServiceMonths below (VACATION's own min_service_months is
        // now 0 post-V120, so that check would never catch this) and before PERSONAL's probation
        // gate (which has its own, narrower NULL-hire_date check further down -- unreachable in
        // practice once this fires first, kept as a defensive fallback, not because it needs to run).
        if (leaveType.proratedFirstYear() && leaveRepository.findHireDate(employeeId).isEmpty()) {
            return "Your hire date is not on file, so eligibility for " + leaveType.nameEn()
                + " cannot be verified. Contact HR to record it before this leave type can be used.";
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

        // §5.2 first-year total-days cap (V120, defect 3). SEPARATE rule from pro-ration and from
        // the probation gate above -- do not collapse the three. The 2567 text moved the "not more
        // than 3 days" limit INSIDE the under-one-year parenthesis and dropped "ติดต่อกัน"
        // ("consecutive"): "(กรณีอายุงานไม่ถึงหนึ่งปี ให้ลาเป็นสัดส่วนตามอายุงาน และไม่อนุญาตให้ลากิจ
        // เกิน 3 วัน)" -- a TOTAL annual ceiling for under-1-year employees, not a per-request
        // consecutive-day rule for everyone (the old 2561-era max_consecutive_days=3 blanket rule
        // this replaces; see V120's migration comment for why max_consecutive_days is now NULL on
        // PERSONAL instead). Owner-ruled reading (2026-08-02), not this branch's own interpretation.
        //
        // Expressed as data (leaveType.firstYearMaxDays(), NULL = no such cap) rather than hardcoded
        // to PERSONAL's code, matching every other §5 rule on this table -- even though PERSONAL is
        // the only row that carries a non-NULL value today.
        //
        // Effective cap = min(prorated annual quota, firstYearMaxDays) -- EXPLICITLY the smaller of
        // the two constraints the announcement states together for a first-year employee, not
        // firstYearMaxDays alone: a 2-month employee's prorated quota (~1.17 days) is already below
        // 3 and binds on its own; a 9-month employee's prorated quota (5.25 days) would exceed 3
        // without this gate, so the flat 3-day ceiling is what actually binds for them instead. Once
        // completedMonths reaches FULL_SERVICE_MONTHS this gate does not apply AT ALL (not "applies
        // with an unlimited cap") -- an employee past one year is governed by the ordinary
        // quota/paid-cap machinery alone, the same as VACATION.
        //
        // Enforced as an outright REJECTION -- unlike ordinary quota exceedance elsewhere in this
        // method, which approves-and-splits into paid/unpaid -- because "ไม่อนุญาต" ("not permitted")
        // is categorically stronger than a pay-eligibility limit; this is the same reading V116 gave
        // the now-removed consecutive-day cap. Compares against usedThisYear + totalDays (cumulative
        // across every request this quota year, not just this single request's length), matching "3
        // days ... in total".
        if (leaveType.firstYearMaxDays() != null) {
            Optional<LocalDate> hireDate = leaveRepository.findHireDate(employeeId);
            // Presence already established by the proratedFirstYear gate above for every type this
            // branch can reach today (firstYearMaxDays is only ever seeded alongside
            // proratedFirstYear -- see V120's migration comment); re-checked rather than assumed,
            // since nothing in the Java model itself enforces that the two columns travel together.
            if (hireDate.isPresent()) {
                long completedMonths = Math.min(FULL_SERVICE_MONTHS, ChronoUnit.MONTHS.between(hireDate.get(), startDate));
                if (completedMonths < FULL_SERVICE_MONTHS) {
                    BigDecimal proratedQuota = employeeAnnualQuota(leaveType, employeeId, startDate);
                    BigDecimal effectiveCap = proratedQuota.min(leaveType.firstYearMaxDays());
                    BigDecimal usedThisYear = leaveRepository.sumUsedDays(
                        employeeId, leaveType.code(), startDate.getYear(), ACTIVE_QUOTA_STATUSES);
                    if (usedThisYear.add(totalDays).compareTo(effectiveCap) > 0) {
                        return leaveType.nameEn() + " is limited to " + formatDays(effectiveCap)
                            + " day(s) total per year during your first " + FULL_SERVICE_MONTHS
                            + " months of service. Contact HR if this is an exception.";
                    }
                }
            }
        }

        // §5.2 "not more than 3 consecutive days" (PERSONAL, pre-2567/pre-V120 wording -- superseded,
        // see the first-year total-days cap above) and any other type given a
        // max_consecutive_days cap. DECISION: "consecutive" is counted in CALENDAR days (end - start +
        // 1 inclusive), not working days -- a calendar-day span is the natural reading of "3 days in
        // a row" for a short request that, by definition, rarely crosses a weekend anyway. A sub-day
        // request is always single-day (start_date = end_date, enforced by validateSubDayTimes), so
        // this never conflicts with the sub-day feature.
        //
        // RECONFIRMED, not merely carried over (2026-08-03): LeaveDayMath is now schedule/holiday-
        // aware (see its javadoc), so switching this to true working days is technically possible in
        // this branch -- but was deliberately NOT done here. This check is a request-SHAPE limit ("do
        // not take more than N days in one go"), not a pay-eligibility count, and it is a MAXIMUM:
        // switching to a working-day count would, if anything, LOOSEN it (a calendar span that
        // crosses a weekend/holiday would newly fit under the same numeric cap, since those days
        // would stop counting toward the limit) -- the opposite direction of the advance-notice
        // decision below, but still an unrequested behaviour change with no business ask driving it.
        // Left on calendar days; flagged as a separate, explicit follow-up decision if the business
        // actually wants it.
        if (leaveType.maxConsecutiveDays() != null) {
            long spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (BigDecimal.valueOf(spanDays).compareTo(leaveType.maxConsecutiveDays()) > 0) {
                return leaveType.nameEn() + " may not exceed " + formatDays(leaveType.maxConsecutiveDays())
                    + " consecutive day(s) per request. Contact HR if this is an exception.";
            }
        }

        if ("SICK".equals(leaveType.code())) {
            String note = sickCertificateNote(leaveType, employeeId, startDate, hasAttachment);
            if (note != null) {
                return note;
            }
        }

        // §5 advance notice, now per-type (hr.leave_type.advance_notice_days) instead of the removed
        // global app.leave.advance-notice-days property. DECISION: counted in CALENDAR days, not
        // working days; this is an unchanged behaviour carried over from the original global-property
        // version, which also compared plain calendar dates. A type with advanceNoticeDays == 0
        // (SICK, MATERNITY, MILITARY, LEAVE_WITHOUT_PAY as seeded) skips this check entirely, matching
        // the old code's unconditional SICK exemption.
        //
        // RECONFIRMED, not merely carried over (2026-08-03): V116 called this calendar-day counting a
        // deliberate STOPGAP specifically because working-day counting was not available yet ("§5.2 =
        // 1 working day, §5.3 = 3 working days" in the source announcement). It is available now --
        // LeaveDayMath is schedule/holiday-aware (see its javadoc) -- but this branch deliberately does
        // NOT convert this check, for the opposite reason from the consecutive-days check above: this
        // is a MINIMUM lead-time requirement, so switching to a true working-day count would TIGHTEN
        // it whenever the gap between filing and the start date crosses a weekend/holiday (those days
        // would stop counting toward the required notice, so a request that clears today's calendar-
        // day check could newly be rejected). That is a real, employee-facing behaviour change with
        // its own blast radius -- it belongs in its own reviewed change with its own tests, not folded
        // silently into a branch whose stated purpose is the payroll deduction/day-count fix. Left on
        // calendar days here; flagged as a separate, explicit follow-up decision.
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

    /**
     * V118 cross-year quota fix (2026-08-02): dropped the "start/end must fall in the same calendar
     * year" rejection this method used to carry -- quota is now attributed per calendar year (see
     * LeaveQuotaYearSplit), so a request spanning 31 Dec/1 Jan is no longer categorically unfileable.
     * A 98-day MATERNITY request starting after roughly mid-September used to 400 here every time;
     * that was the defect this migration fixes. Only the (unrelated) end-before-start ordering check
     * remains.
     */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดการลาต้องไม่มาก่อนวันที่เริ่มต้น");
        }
    }

    private BigDecimal workingDaysBetween(LocalDate startDate, LocalDate endDate, Predicate<LocalDate> isWorkingDay) {
        int days = LeaveDayMath.countWorkingDays(startDate, endDate, isWorkingDay);
        if (days <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน");
        }
        return BigDecimal.valueOf(days);
    }

    /**
     * Sub-day leave (2026-07-25): no times -&gt; the existing whole-day day count, per the leave
     * type's {@link LeaveDayCountBasis} (V119: CALENDAR_DAYS for MATERNITY, WORKING_DAYS for every
     * other type -- unchanged). Times set -&gt; clock-hours(start,end) / 8 (STANDARD_WORKDAY_MINUTES),
     * no lunch subtraction (decided rule), rounded HALF_UP to 2dp, capped at 1.00 (a sub-day request
     * can never exceed one whole day). FULL_DAY (not BigDecimal.ONE) keeps the cap at scale 2,
     * matching the NUMERIC(5,2) convention every other day figure in this codebase uses. The sub-day
     * branch is basis-independent by construction: sub-day leave is always single-day (start_date ==
     * end_date, enforced by {@link #validateSubDayTimes}), so "which days count" never arises -- the
     * one date in question always counts, under either basis.
     *
     * <p>{@code isWorkingDay} (2026-08-03): the schedule/holiday-aware predicate built once in
     * {@link #submit}, only read by the WORKING_DAYS branch below (via {@link #workingDaysBetween})
     * -- the CALENDAR_DAYS (MATERNITY) branch never touches it, unchanged from before this predicate
     * existed.
     */
    private BigDecimal computeTotalDays(
            SubmitLeaveRequest request, LeaveTypeDto leaveType, Predicate<LocalDate> isWorkingDay) {
        if (request.startTime() == null) {
            if (leaveType.dayCountBasis() == LeaveDayCountBasis.CALENDAR_DAYS) {
                return BigDecimal.valueOf(LeaveDayMath.countCalendarDays(request.startDate(), request.endDate()));
            }
            return workingDaysBetween(request.startDate(), request.endDate(), isWorkingDay);
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
     *
     * <p>Schedule/holiday-aware working-day counting (2026-08-03): the "must be a working day" check
     * now uses the schedule/holiday-aware {@code isWorkingDay} predicate (a warehouse employee's
     * Saturday sub-day request is now valid), and applies ONLY to {@code WORKING_DAYS}-basis types
     * -- gated on {@code basis} so a CALENDAR_DAYS (MATERNITY) sub-day request is never rejected for
     * landing on a non-working day, matching how CALENDAR_DAYS never filters days anywhere else in
     * this class. Skipping this gate for CALENDAR_DAYS is a deliberate decision, not an oversight:
     * without it, a sub-day MATERNITY request landing on a public holiday would go from "allowed"
     * (today, holiday-blind) to "rejected" (this branch's holiday awareness applied everywhere) --
     * exactly the "double-handling" MATERNITY must not receive, per LeaveDayCountBasis's javadoc.
     */
    private void validateSubDayTimes(
            SubmitLeaveRequest request, LeaveDayCountBasis basis, Predicate<LocalDate> isWorkingDay) {
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
        if (basis == LeaveDayCountBasis.WORKING_DAYS
                && LeaveDayMath.countWorkingDays(request.startDate(), request.startDate(), isWorkingDay) == 0) {
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
