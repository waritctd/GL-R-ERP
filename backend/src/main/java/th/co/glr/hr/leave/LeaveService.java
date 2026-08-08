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
import java.util.HashMap;
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
    // Leave HR-submit gate (2026-08-03), owner ruling: "HR and บริหาร oversee leave but do not
    // request it for themselves." Same role bucket as VIEW_ALL_ROLES (`ผู้บริหาร` -> hr,
    // `ผู้บริหารระดับสูง` -> ceo -- see frontend/src/app/roles.js), matching the "CEO/HR are
    // queue-side, they never file their own requests" precedent already established for OT and
    // welfare approvals in this codebase. Deliberately SELF-submission only -- see
    // #resolveTargetEmployee's own comment for why HR-for-a-subordinate must keep working.
    private static final Set<String> SELF_SUBMIT_BLOCKED_ROLES = Set.of("hr", "ceo");
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
    // §5.2 leave purpose (V125): the five NAMED purposes plus OTHER, the open-ended catch-all for
    // the announcement's trailing "เป็นต้น" ("etc.") -- see normalizePurposeCode's Javadoc.
    private static final Set<String> KNOWN_PURPOSE_CODES = Set.of(
        "DRIVING_LICENSE_OR_GOVERNMENT", "FAMILY_NECESSITY", "RELIGIOUS_PRACTICE",
        "WEDDING", "FAMILY_FUNERAL", "OTHER");
    private static final String WEDDING_PURPOSE_CODE = "WEDDING";
    // §5.2 wedding leave cap ("ลาเข้าพิธีสมรส จัดงานสมรสของบุตรลาได้ไม่เกิน 3 วัน"). See the check
    // itself in #autoRejectNote for why this is a hardcoded Java constant, not a hr.leave_type
    // column, the same way SICK's certificate requirement and PERSONAL's probation gate are.
    private static final BigDecimal WEDDING_LEAVE_MAX_DAYS = new BigDecimal("3.00");
    // §5.3.4: types the announcement names explicitly ("ลาพักร้อน ลากิจ" -- VACATION, PERSONAL).
    private static final Set<String> RESIGNATION_GATED_LEAVE_TYPES = Set.of("VACATION", "PERSONAL");
    // §5.3.3: the pair the announcement names ("ลากิจและลาพักผ่อน") -- each type maps to the other,
    // so a single lookup tells #contiguousLeaveRuleOutcome which type to search for.
    private static final Map<String, String> CONTIGUOUS_LEAVE_PAIR = Map.of(
        "VACATION", "PERSONAL",
        "PERSONAL", "VACATION"
    );
    // §5.3.2: <i>"ไม่อนุญาตให้ลาพร้อมกันทั้งแผนก ต้องมีพนักงานที่มีอายุงานเกิน 1 ปี มาทำงานอย่างน้อย 1
    // คน"</i> names neither a tenure-free reading nor a department-size floor -- BOTH are owner
    // relaxations layered onto the literal text, not this branch's own interpretation:
    //   1. the "&gt;1 year tenure" qualifier is dropped (owner ruling, see #departmentCoverageRuleOutcome).
    //   2. the gate only APPLIES when the department has at least this many ACTIVE employees
    //      (owner ruling, 2026-08-03, given after CI showed the un-floored gate blocking realistic
    //      2-person departments -- e.g. a warehouse pair where one is already off could never let the
    //      other take SICK leave at all). Departments below this floor are EXEMPT ENTIRELY, not just
    //      down to one person; do not "fix" this back to a 1-person floor or the literal text.
    private static final int MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE = 3;
    // §5.3.3: how far past the new request's own dates to search for a same-employee request of the
    // paired type. 14 days comfortably bounds every seeded schedule's longest realistic run of
    // consecutive non-workdays (WEEKEND_DUTY's is the extreme case at 5) with room to spare -- see
    // #hasInterveningWorkday.
    private static final int CONTIGUOUS_CHECK_WINDOW_DAYS = 14;
    // §5.3.5 VACATION carry-forward (V127): no grant is ever computed for an earned_year before
    // this constant -- see #ensureCarryoverGrant. The governing announcement took effect 1 Oct
    // 2567/2024, but this is deliberately 2025 (the first FULL calendar year it governs), not 2024
    // (2567's own partial first year): computing a carry-out for 2024 would require deciding what
    // "annual quota" means for a year the rule only covered for its last quarter -- an
    // interpretation this migration does not make, per CLAUDE.md's "historical years must not be
    // invented ... a wrong balance is worse than none." No hr.leave_carryover row is ever written
    // for earned_year < 2025, and no employee's 2024 (or earlier) vacation usage ever grants
    // anything into 2025 -- this is a deliberate NO-BACKFILL boundary, not an oversight; state it
    // plainly when reporting this change.
    private static final int CARRY_FORWARD_BASELINE_YEAR = 2025;

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
        )).stream()
            .map(dto -> withCanReviewFlag(dto, user))
            .toList();
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
        // §5.2 leave purpose (V125): validated up front, same as leaveTypeCode -- see
        // normalizePurposeCode's Javadoc for why this can never refuse a request for lacking a
        // matching NAMED category (OTHER is always available).
        String purposeCode = normalizePurposeCode(request.purposeCode());
        boolean requestedAsEmergency = Boolean.TRUE.equals(request.requestedAsEmergency());

        BigDecimal totalDays = computeTotalDays(
            request.startDate(), request.endDate(), request.startTime(), request.endTime(), leaveType, isWorkingDay);
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
        // evaluateDepartmentCoverage = true, always -- a real submission must evaluate the FULL gate
        // chain, exactly as before Phase A0b added the QUICK-preview skip; see AutoRejectResult's
        // Javadoc and #preview's Javadoc for the only caller that ever passes `false`.
        AutoRejectResult autoReject = autoRejectNote(leaveType, employeeId, request.startDate(), request.endDate(),
            hasAttachment, totalDays, purposeCode, requestedAsEmergency, true);
        // Phase A0a (structured rejection outcome): `blocking` carries the machine-readable
        // LeaveRuleCode + params alongside the same Thai sentence that used to be the ONLY thing
        // #autoRejectNote returned -- systemNote/status derive from it exactly as before, since
        // `outcome == null` (approved) and `outcome.messageTh() == null` never happens (see
        // LeaveRuleOutcome#of, which always renders a non-null sentence for a non-null code).
        LeaveRuleOutcome outcome = autoReject.blocking();
        String systemNote = outcome == null ? null : outcome.messageTh();
        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;

        // V118 cross-year quota fix (2026-08-02): extracted (Phase A0b) to #computeQuotaSplit -- see
        // that method's Javadoc, which carries this comment's full original text verbatim. #preview
        // calls the SAME method for its own totalDays/paidDays/unpaidDays/quotaYearSplits response
        // fields, so a previewed figure and the figure #submit would actually persist can never drift
        // apart through two separate implementations of this arithmetic.
        QuotaSplitResult quotaSplit = computeQuotaSplit(
            employeeId, leaveType, request.startDate(), request.endDate(), totalDays, isWorkingDay,
            quotaYear, status == LeaveStatus.APPROVED);
        List<LeaveQuotaYearSplit> quotaYearSplits = quotaSplit.quotaYearSplits();
        BigDecimal paidDays = quotaSplit.paidDays();
        BigDecimal unpaidDays = quotaSplit.unpaidDays();
        BigDecimal remainingBefore = quotaSplit.remainingBefore();
        BigDecimal remainingAfter = quotaSplit.remainingAfter();

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
        // §5.2 emergency-filing exception (V125): a small follow-up UPDATE by id, same shape as
        // #attachFile below -- kept OUT of #create's own parameter list so every pre-existing
        // caller/test of LeaveRepository#create is unaffected by this addition (see
        // LeaveRepository#markEmergencyFiling's Javadoc).
        if (autoReject.emergencyFilingApplied()) {
            leaveRepository.markEmergencyFiling(id);
        }
        // Phase A0a (structured rejection outcome, V131): same small-follow-up-UPDATE-by-id shape as
        // markEmergencyFiling immediately above and #attachFile below -- kept OUT of #create's own
        // parameter list for the identical reason (every pre-existing caller/test of
        // LeaveRepository#create stays unaffected). Only called for an actually-rejected outcome;
        // system_note_code/system_note_params stay NULL for an APPROVED request (create() never sets
        // them to anything but their SQL default), the same as system_note itself.
        if (outcome != null) {
            leaveRepository.recordAutoRejectReason(id, outcome.code(), outcome.params());
        }
        // V118 cross-year quota fix: persist the per-year attribution now that the parent row exists
        // (leave_request_quota_year.leave_request_id is a FK to it). Same transaction as #create
        // above (this method is @Transactional).
        leaveRepository.insertQuotaYearSplits(id, quotaYearSplits);
        if (hasAttachment) {
            // V134 storage-durability fix: leave attachments (e.g. a SICK medical certificate) go
            // straight to the database now -- never to the app container's ephemeral disk. See
            // FileStorageService#storeInDatabase's javadoc for why, and LeaveAttachmentRepository
            // #saveWithContent for how the blob insert and the storage_state flip land in this same
            // @Transactional method.
            FileStorageService.StoredContent storedContent =
                fileStorage.storeInDatabase("leave", id, attachment, LEAVE_ATTACHMENT_MIME_TYPES);
            LeaveAttachmentDto savedAttachment = leaveAttachments.saveWithContent(
                id,
                storedContent.fileName(),
                storedContent.storageKey(),
                storedContent.mimeType(),
                storedContent.fileSize(),
                actorEmployeeId,
                storedContent.content()
            );
            leaveRepository.attachFile(id, savedAttachment.id());
        }
        LeaveRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_LEAVE_REQUEST", "leave_request", id, null, created);
        notifyAfterSubmit(created, status);
        return withCanReviewFlag(created, user);
    }

    /**
     * POST /api/leave/preview (Phase A0b dry-run): runs the IDENTICAL gate chain {@link #submit}
     * runs -- {@link #eligibilityRuleOutcome} (always) and, when dates are supplied, {@link
     * #autoRejectNote} (the SAME method #submit calls, with the SAME arguments) -- against an
     * UNCOMMITTED request, and writes nothing: no {@link LeaveRepository#create}, no attachment
     * storage, no audit record, no notification. Exists because every rule {@link #autoRejectNote}
     * computes was previously reachable only by actually submitting and receiving an AUTO_REJECTED
     * row -- there was no way for the frontend to show a verdict before the employee commits.
     *
     * <p><b>{@code depth} (QUICK vs FULL):</b> QUICK skips {@link #departmentCoverageRuleOutcome} --
     * it fans out over every active department colleague's schedule and leave spans (see that
     * method's Javadoc for the query cost), and the UI is expected to call this endpoint on a
     * debounce as the user types dates, which cannot afford that fan-out on every keystroke. This is
     * NEVER silent: {@link LeavePreviewDto#coverageEvaluated} is {@code false} whenever the check did
     * not run (QUICK depth, or a gate above it in {@link #autoRejectNote} already blocked first, or
     * the pre-existing emergency-filing early return skipped it -- see {@link AutoRejectResult}'s
     * Javadoc), so a caller can never mistake "not evaluated" for "evaluated, and clear". FULL runs
     * everything, exactly as {@link #submit} does -- {@code depth == null} defaults to FULL, the safer
     * reading for a caller that does not specify (a caller who wants the cheap QUICK read must ask
     * for it explicitly).
     *
     * <p><b>Nullable {@code startDate}/{@code endDate}:</b> with no dates chosen yet, the caller still
     * gets the eligibility verdict from the gates that need only the employee and type -- {@link
     * #eligibilityRuleOutcome} (once-per-employment, resignation, hire-date, min-service, probation),
     * evaluated against {@code LocalDate.now(clock)} (the same as-of-today stand-in {@link
     * #balanceFor} already uses). Every date-dependent gate (max-consecutive-days, wedding cap,
     * contiguous VACATION/PERSONAL, SICK certificate window, advance notice, department coverage) is
     * NOT evaluated in this case -- {@link LeavePreviewDto#datesEvaluated} is {@code false} and
     * {@code totalDays}/{@code paidDays}/{@code unpaidDays}/{@code quotaYearSplits} are all {@code
     * null}/empty, so the response can never be misread as "approved" when most of the gate chain
     * never ran. This is the explicit representation the brief for this phase asks for.
     *
     * <p>{@code hasAttachment}/{@code requestedAsEmergency}/{@code purposeCode} are the caller's OWN
     * declarations, exactly as {@link #submit} treats them -- this endpoint cannot verify a file was
     * actually chosen (there is no multipart upload here), so a caller that wants an accurate SICK
     * preview must pass {@code hasAttachment} truthfully.
     *
     * <p>{@code counters} (emergencyFilingsRemaining/noCertificateOccasionsRemaining) are always
     * computed, regardless of {@code depth} or whether dates were supplied -- both are scoped to the
     * CURRENT calendar month (see {@link #previewCounters}), not to the request's own dates, so
     * neither needs them.
     */
    public LeavePreviewDto preview(LeavePreviewRequest request, UserPrincipal user) {
        if (request == null || request.leaveTypeCode() == null || request.leaveTypeCode().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุประเภทการลา");
        }
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        LeaveTypeDto leaveType = requireLeaveType(request.leaveTypeCode());
        String purposeCode = normalizePurposeCode(request.purposeCode());
        boolean requestedAsEmergency = Boolean.TRUE.equals(request.requestedAsEmergency());
        boolean hasAttachment = Boolean.TRUE.equals(request.hasAttachment());
        LeavePreviewDepth depth = request.depth() == null ? LeavePreviewDepth.FULL : request.depth();

        // Independent of dates/depth -- see #previewCounters's Javadoc.
        LeavePreviewCounters counters = previewCounters(leaveType, employeeId);

        LocalDate startDate = request.startDate();
        LocalDate endDate = request.endDate();
        if (startDate == null || endDate == null) {
            // Nullable-dates case: only the categorical eligibility gates can be evaluated -- see
            // this method's own Javadoc for why LocalDate.now(clock) stands in for the request's own
            // (not-yet-chosen) startDate here, and why datesEvaluated/coverageEvaluated must both be
            // explicit false rather than silently defaulting to an "approved" reading.
            LeaveRuleOutcome eligibilityOutcome = eligibilityRuleOutcome(leaveType, employeeId, LocalDate.now(clock));
            return new LeavePreviewDto(eligibilityOutcome, false, false, null, null, null, List.of(), counters);
        }
        validateDateRange(startDate, endDate);

        Predicate<LocalDate> isWorkingDay = leaveRepository.workingDayPredicate(employeeId, startDate, endDate);
        // No sub-day times in LeavePreviewRequest's shape -- always the whole-day path, the same path
        // a legacy no-times submission always took. See #computeTotalDays's Javadoc.
        BigDecimal totalDays = computeTotalDays(startDate, endDate, null, null, leaveType, isWorkingDay);
        boolean evaluateDepartmentCoverage = depth == LeavePreviewDepth.FULL;
        AutoRejectResult autoReject = autoRejectNote(leaveType, employeeId, startDate, endDate,
            hasAttachment, totalDays, purposeCode, requestedAsEmergency, evaluateDepartmentCoverage);
        LeaveRuleOutcome outcome = autoReject.blocking();
        boolean approved = outcome == null;
        int quotaYear = startDate.getYear();
        QuotaSplitResult split = computeQuotaSplit(
            employeeId, leaveType, startDate, endDate, totalDays, isWorkingDay, quotaYear, approved);

        return new LeavePreviewDto(
            outcome, true, autoReject.coverageEvaluated(),
            totalDays, split.paidDays(), split.unpaidDays(), split.quotaYearSplits(), counters);
    }

    /**
     * The counters {@link #autoRejectNote}'s SICK/PERSONAL branches already compute as locals
     * ({@code occasionsUsed}/{@code usedThisMonth}) -- exposed here for {@link #preview} via the SAME
     * two repository methods those branches call ({@link LeaveRepository#countNoCertificateRequestsInMonth}/
     * {@link LeaveRepository#countEmergencyFilings}), without changing how either gate USES them.
     * Always scoped to the CURRENT calendar month ({@code LocalDate.now(clock)}), independent of any
     * dates the caller may have chosen -- so this is computable even for a dateless preview.
     *
     * <p>{@code 0} when the type carries no such allowance at all (a non-SICK type for the
     * no-certificate tolerance, or any type but PERSONAL for the emergency exception) -- the same "no
     * allowance" reading {@link #autoRejectNote} itself uses ({@code tolerance <= 0} rejects
     * outright; {@code emergencyMonthlyAllowance() == null} never grants the exception).
     */
    private LeavePreviewCounters previewCounters(LeaveTypeDto leaveType, long employeeId) {
        LocalDate today = LocalDate.now(clock);
        int tolerance = leaveType.noCertificateMonthlyTolerance();
        int noCertificateUsed = tolerance > 0
            ? leaveRepository.countNoCertificateRequestsInMonth(
                employeeId, leaveType.code(), today.withDayOfMonth(1),
                today.withDayOfMonth(1).plusMonths(1).minusDays(1), ACTIVE_QUOTA_STATUSES)
            : 0;
        int noCertificateOccasionsRemaining = Math.max(0, tolerance - noCertificateUsed);

        Integer emergencyAllowance = leaveType.emergencyMonthlyAllowance();
        int emergencyUsed = emergencyAllowance != null
            ? leaveRepository.countEmergencyFilings(employeeId, leaveType.code(), today, ACTIVE_QUOTA_STATUSES)
            : 0;
        int emergencyFilingsRemaining =
            Math.max(0, (emergencyAllowance == null ? 0 : emergencyAllowance) - emergencyUsed);

        return new LeavePreviewCounters(emergencyFilingsRemaining, noCertificateOccasionsRemaining);
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
        return withCanReviewFlag(after, user);
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
        return withCanReviewFlag(after, user);
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
        return withCanReviewFlag(after, user);
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
        BigDecimal annualQuota = employeeAnnualQuota(type, employeeId, LocalDate.now(clock));
        // §5.3.5 VACATION carry-forward (V127): `year` (not "today") is the right key here -- a
        // carry-in is scoped to a specific usable_year, not to "today's" calendar year, so a
        // balance query for a past or future year must see THAT year's own carry-in.
        BigDecimal carriedIn = carryInDays(employeeId, type, year);
        BigDecimal quota = annualQuota.add(carriedIn);
        BigDecimal remaining = quota.subtract(approved).subtract(pending).max(BigDecimal.ZERO);
        // Phase A0b carry-forward provenance: DERIVED, not queried -- see LeaveBalanceDto's Javadoc
        // for why `year - 1`/31 Dec of `year` is the same relationship hr.leave_carryover's own
        // earned_year/usable_year columns already encode (no migration, no new query). null for a
        // type that does not carry forward at all -- "from which year" is meaningless there, not
        // just zero.
        Integer carriedInFromYear = type.carriesForward() ? year - 1 : null;
        LocalDate carriedInExpiresOn = type.carriesForward() ? LocalDate.of(year, 12, 31) : null;
        return new LeaveBalanceDto(
            type.code(),
            type.nameTh(),
            type.nameEn(),
            annualQuota,
            approved,
            pending,
            remaining,
            type.requiresAttachment(),
            carriedIn,
            carriedInFromYear,
            carriedInExpiresOn
        );
    }

    private BigDecimal remainingDays(long employeeId, LeaveTypeDto leaveType, int quotaYear, LocalDate asOf) {
        BigDecimal used = leaveRepository.sumUsedDays(employeeId, leaveType.code(), quotaYear, ACTIVE_QUOTA_STATUSES);
        BigDecimal quota = employeeAnnualQuota(leaveType, employeeId, asOf)
            .add(carryInDays(employeeId, leaveType, quotaYear));
        return quota.subtract(used).max(BigDecimal.ZERO);
    }

    /**
     * V118 cross-year quota fix (2026-08-02), extracted verbatim in Phase A0b from {@link #submit}
     * (previously inline there -- see git blame for the original comment this Javadoc preserves): a
     * request's days are attributed per calendar year (almost always just one -- two only for a
     * request spanning 31 Dec/1 Jan, e.g. a long MATERNITY request -- see {@link LeaveQuotaYearSplit}).
     * Each year's paidDays/unpaidDays/remaining figures are computed INDEPENDENTLY against that
     * year's own quota and paid_days_cap; see {@link LeaveQuotaYearSplit}'s Javadoc for the deliberate
     * consequence of that design (a boundary-straddling request can receive more total paid days than
     * a same-year one would).
     *
     * <p>{@code approved} stands in for the caller's {@code status == LeaveStatus.APPROVED} check --
     * an unapproved (AUTO_REJECTED) request still gets a full per-year breakdown, just with every
     * paidDaysYear/unpaidDaysYear pinned to ZERO (nothing was actually granted) and
     * remainingAfterYear left equal to remainingBeforeYear (nothing was consumed).
     *
     * <p>Called from both {@link #submit} (persists the result) and {@link #preview} (a dry run --
     * see that method's Javadoc) with identical arguments for identical inputs, so a previewed figure
     * and the figure {@link #submit} would actually persist can never drift apart through two separate
     * copies of this arithmetic.
     */
    private QuotaSplitResult computeQuotaSplit(
            long employeeId, LeaveTypeDto leaveType, LocalDate startDate, LocalDate endDate,
            BigDecimal totalDays, Predicate<LocalDate> isWorkingDay, int quotaYear, boolean approved) {
        Map<Integer, BigDecimal> requestedDaysByYear = LeaveDayMath.totalDaysByYear(
            startDate, endDate, totalDays, leaveType.dayCountBasis(), isWorkingDay);
        List<LeaveQuotaYearSplit> quotaYearSplits = new ArrayList<>();
        BigDecimal paidDays = BigDecimal.ZERO;
        BigDecimal unpaidDays = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> entry : requestedDaysByYear.entrySet()) {
            int year = entry.getKey();
            BigDecimal daysInYear = entry.getValue();
            // §5.2/§5.3 pro-ration (V120): the quota itself may depend on the employee's completed
            // service AS OF this request -- startDate is used uniformly for every year in a
            // cross-year split (VACATION/PERSONAL never span a year boundary in practice: VACATION
            // has no max_consecutive_days cap but is a planned, short-notice-driven leave type in
            // practice, and PERSONAL is hard-capped at 3 consecutive days), matching how the
            // pre-existing min-service-months and PERSONAL-probation gates already evaluate tenure
            // as of the request's start date, not "today".
            BigDecimal remainingBeforeYear = remainingDays(employeeId, leaveType, year, startDate);
            BigDecimal paidDaysYear;
            BigDecimal unpaidDaysYear;
            BigDecimal remainingAfterYear;
            if (approved) {
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
            .orElseGet(() -> remainingDays(employeeId, leaveType, quotaYear, startDate));
        BigDecimal remainingAfter = quotaYearSplits.stream()
            .filter(split -> split.quotaYear() == quotaYear)
            .map(LeaveQuotaYearSplit::quotaRemainingAfter)
            .findFirst()
            .orElse(remainingBefore);
        return new QuotaSplitResult(paidDays, unpaidDays, quotaYearSplits, remainingBefore, remainingAfter);
    }

    private record QuotaSplitResult(
        BigDecimal paidDays,
        BigDecimal unpaidDays,
        List<LeaveQuotaYearSplit> quotaYearSplits,
        BigDecimal remainingBefore,
        BigDecimal remainingAfter) {
    }

    /**
     * §5.3.5 VACATION carry-forward (V127): the carry-in available for {@code leaveType} in
     * {@code usableYear} -- always {@link BigDecimal#ZERO} when {@link
     * LeaveTypeDto#carriesForward()} is FALSE (every type except VACATION; this is the ONLY gate
     * -- see that field's Javadoc for why a column, not a hardcoded type-code check). Delegates to
     * {@link #ensureCarryoverGrant} for {@code usableYear - 1} (the EARNED year whose leftover, if
     * any, is what "carry-in for usableYear" means).
     */
    private BigDecimal carryInDays(long employeeId, LeaveTypeDto leaveType, int usableYear) {
        if (!leaveType.carriesForward()) {
            return BigDecimal.ZERO;
        }
        return ensureCarryoverGrant(employeeId, leaveType, usableYear - 1);
    }

    /**
     * §5.3.5 VACATION carry-forward (V127): returns (computing and memoizing on first need) the
     * grant {@code earnedYear} produces for {@code earnedYear + 1} -- i.e. {@code earnedYear}'s own
     * unused quota, capped so it can never itself have been inflated by a still-live carry-in from
     * {@code earnedYear - 1} (see the non-accumulation note below).
     *
     * <p><b>Availability gates, both fail CLOSED (return ZERO, write nothing):</b>
     * <ul>
     *   <li>{@code earnedYear < CARRY_FORWARD_BASELINE_YEAR}: no grant is ever computed this far
     *       back -- see that constant's Javadoc. Recursion bottoms out here.</li>
     *   <li>{@code earnedYear} has not fully elapsed yet ({@code LocalDate.now(clock).getYear() <=
     *       earnedYear}): the year's final usage is not yet knowable (an employee could still take
     *       more of that year's VACATION days before it ends), so nothing is computed OR persisted
     *       -- a grant written too early would be too generous if more days are used afterward, and
     *       there would be no cleanup job to correct it (see hr.leave_carryover's structural-expiry
     *       design). Known edge case, stated plainly: an employee who files a VACATION request
     *       dated in January of {@code earnedYear + 1} while the clock is still in December of
     *       {@code earnedYear} (VACATION's 3-day advance notice allows this) will see zero carry-in
     *       from a year that, calendar-wise, has already answered the question -- the carry-in
     *       becomes visible once the clock itself crosses into {@code earnedYear + 1}, not before.
     *       Not fixed here; it is the same fail-closed direction as every other gate in this class.
     * </ul>
     *
     * <p><b>Consumption order (the decision with the largest effect on what an employee keeps):
     * carry-IN is spent BEFORE the year's own quota.</b> This is the employee-favouring
     * "use-it-or-lose-it" reading -- the pool that is about to expire unconditionally is drawn down
     * first, preserving as much of the renewable annual quota as possible for the NEXT
     * carry-forward. Consequence, worked: employee has a 6.00 own quota for {@code earnedYear} plus
     * a 2.00 carry-in from {@code earnedYear - 1} (8.00 total available) and takes exactly 6.00
     * days during {@code earnedYear}.
     * <pre>
     * carry-in-first (chosen):  2.00 carry-in exhausted first, then 4.00 of own quota ->
     *                           2.00 of own quota left unused -> grants 2.00 into earnedYear+1.
     * own-quota-first (rejected): 6.00 of own quota exhausted first, carry-in untouched (and lost,
     *                           §5.3.5 forbids re-carrying it) -> 0.00 of own quota left unused ->
     *                           grants 0.00 into earnedYear+1.
     * </pre>
     * The formula below computes this WITHOUT needing to track which specific request drew from
     * which pool: the total unused pool ({@code ownQuota + carriedIn - used}) is, under
     * carry-in-first consumption, exactly how much of OWN quota survives, up to a ceiling of
     * {@code ownQuota} itself (once unused exceeds a full own quota, that means carry-in was barely
     * touched -- but a grant can never exceed a full year's own entitlement regardless, per
     * §5.3.5's non-accumulation clause):
     * <pre>carryOut = min(ownQuota, max(0, ownQuota + carriedIn - used))</pre>
     *
     * <p><b>Pro-rated first year (V120) DOES carry forward.</b> §5.3.5 says "ปีใด" ("whatever
     * year"), with no carve-out for a first, pro-rated year, and V120's own parenthetical extends
     * pro-ration from the identical announcement sentence -- reading one half as carrying an
     * unstated exception the other half does not state is not supported by the text. What it
     * carries is the unused portion of THAT year's own (pro-rated, not flat-6) entitlement:
     * {@code ownQuota} below is {@link #employeeAnnualQuota} evaluated at the EARNED year's own
     * year-end (31 Dec), i.e. the employee's final, fully-accrued entitlement for that year --
     * capturing the full pro-rated figure even though {@link #employeeAnnualQuota} would have
     * returned a SMALLER number earlier in that same year (see that method's "quota GROWS as the
     * year progresses" note). This is an INTERPRETATION, like V120's own, not restated company
     * policy -- flag it as such when reporting this change.
     *
     * <p><b>Non-accumulation (§5.3.5 "...ได้ในปีต่อไปเท่านั้น"):</b> {@code carryOut} is bounded
     * above by {@code ownQuota} (this method's own year), never by {@code ownQuota + carriedIn} --
     * a carry-in that goes unused this year is simply gone, not re-carried. Enforced twice: here in
     * Java (the {@code .min(ownQuota)}) AND structurally in the DB
     * ({@code chk_lc_carried_days_bounded_by_own_quota}, V127).
     */
    private BigDecimal ensureCarryoverGrant(long employeeId, LeaveTypeDto leaveType, int earnedYear) {
        if (earnedYear < CARRY_FORWARD_BASELINE_YEAR) {
            return BigDecimal.ZERO;
        }
        Optional<BigDecimal> existing = leaveRepository.findCarryover(employeeId, leaveType.code(), earnedYear);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (LocalDate.now(clock).getYear() <= earnedYear) {
            return BigDecimal.ZERO;
        }
        BigDecimal carriedIn = ensureCarryoverGrant(employeeId, leaveType, earnedYear - 1);
        BigDecimal ownQuota = employeeAnnualQuota(leaveType, employeeId, LocalDate.of(earnedYear, 12, 31));
        BigDecimal used = leaveRepository.sumUsedDays(employeeId, leaveType.code(), earnedYear, ACTIVE_QUOTA_STATUSES);
        BigDecimal carryOut = ownQuota.add(carriedIn).subtract(used).max(BigDecimal.ZERO).min(ownQuota);
        leaveRepository.insertCarryoverIfAbsent(
            employeeId, leaveType.code(), earnedYear, earnedYear + 1, ownQuota, used, carriedIn, carryOut);
        return carryOut;
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
     * <p>Resolves "passed probation" through the SAME shared function
     * {@code SpecialMoneyPolicyEvaluator#hasPassedProbation} uses for special-money aid eligibility
     * (owner ruling, 2026-08-03): {@code hr.employee.confirm_date}, when present, is authoritative
     * and the employee is eligible the day AFTER it; otherwise falls back to {@code hire_date +
     * probation_days}, where {@code probation_days} falls back to {@link
     * SpecialMoneyPolicyEvaluator#DEFAULT_PROBATION_DAYS} when NULL on the employee row. Calling the
     * shared method (not a duplicated copy of the arithmetic) is what actually prevents the two
     * rules drifting apart.
     *
     * <p>{@code probation_days == 0} means eligible from the hire date itself (no waiting period) --
     * handled by the plain {@code plusDays(0)} arithmetic inside the shared method, not a special
     * case here. A row with NEITHER {@code confirm_date} NOR {@code hire_date} on file fails closed
     * (returns a rejection), the same direction as every other eligibility gate in this class -- it
     * must never silently pass.
     *
     * @return a rejection outcome ({@link LeaveRuleCode#PROBATION_HIRE_DATE_MISSING} or
     *     {@link LeaveRuleCode#PROBATION_NOT_PASSED}), or {@code null} if the employee has passed
     *     probation. (Phase A0a: was a plain English rejection message; the DECISION this method
     *     makes is unchanged, only its return shape is now structured -- see {@link LeaveRuleOutcome}.)
     */
    private LeaveRuleOutcome personalProbationRuleOutcome(LeaveTypeDto leaveType, long employeeId, LocalDate startDate) {
        Optional<LocalDate> hireDate = leaveRepository.findHireDate(employeeId);
        Optional<LocalDate> confirmDate = leaveRepository.findConfirmDate(employeeId);
        if (hireDate.isEmpty() && confirmDate.isEmpty()) {
            return LeaveRuleOutcome.of(LeaveRuleCode.PROBATION_HIRE_DATE_MISSING,
                Map.of("leaveTypeNameTh", leaveType.nameTh()));
        }
        // confirm_date is authoritative when present (see #hasPassedProbation), so probation_days
        // is only ever consulted on the hire_date fallback path -- skip the extra read otherwise,
        // the same care findHireDate/findProbationDays already take elsewhere in this class.
        Integer probationDays =
            confirmDate.isEmpty() ? leaveRepository.findProbationDays(employeeId).orElse(null) : null;
        if (SpecialMoneyPolicyEvaluator.hasPassedProbation(
                hireDate.orElse(null), probationDays, confirmDate.orElse(null), startDate)) {
            return null;
        }
        // Presentational only -- the eligibility DECISION above already came from the shared
        // method; this just recomputes the same "expected eligible on" date for the message,
        // preferring confirm_date (day-after) the same way the decision itself did.
        LocalDate probationEndsOn = confirmDate
            .map(d -> d.plusDays(1))
            .orElseGet(() -> hireDate.get()
                .plusDays(probationDays != null ? probationDays : SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS));
        return LeaveRuleOutcome.of(LeaveRuleCode.PROBATION_NOT_PASSED, Map.of(
            "leaveTypeNameTh", leaveType.nameTh(),
            "probationEndsOn", probationEndsOn.toString()));
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
     * @return a rejection outcome ({@link LeaveRuleCode#SICK_CERTIFICATE_WINDOW},
     *     {@link LeaveRuleCode#SICK_CERTIFICATE_REQUIRED}, or
     *     {@link LeaveRuleCode#SICK_NO_CERT_TOLERANCE_EXHAUSTED}), or {@code null} if the
     *     SICK-specific rule is satisfied (other gates in {@link #autoRejectNote} may still apply).
     *     (Phase A0a: was a plain English rejection message; the DECISION TABLE above is unchanged,
     *     only its return shape is now structured -- see {@link LeaveRuleOutcome}.)
     */
    private LeaveRuleOutcome sickCertificateRuleOutcome(LeaveTypeDto leaveType, long employeeId, LocalDate startDate, boolean hasAttachment) {
        if (hasAttachment) {
            Integer filingWindowDays = leaveType.certificateFilingWindowDays();
            if (filingWindowDays == null) {
                return null;
            }
            LocalDate deadline = LeaveDayMath.addWorkingDays(startDate, filingWindowDays);
            LocalDate today = LocalDate.now(clock);
            if (today.isAfter(deadline)) {
                return LeaveRuleOutcome.of(LeaveRuleCode.SICK_CERTIFICATE_WINDOW, Map.of(
                    "certificateWindowDays", String.valueOf(filingWindowDays),
                    "certificateDeadline", deadline.toString()));
            }
            return null;
        }

        int tolerance = leaveType.noCertificateMonthlyTolerance();
        if (tolerance <= 0) {
            return LeaveRuleOutcome.of(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED, Map.of());
        }
        LocalDate monthStart = startDate.withDayOfMonth(1);
        LocalDate monthEndInclusive = monthStart.plusMonths(1).minusDays(1);
        int occasionsUsed = leaveRepository.countNoCertificateRequestsInMonth(
            employeeId, leaveType.code(), monthStart, monthEndInclusive, ACTIVE_QUOTA_STATUSES);
        if (occasionsUsed >= tolerance) {
            return LeaveRuleOutcome.of(LeaveRuleCode.SICK_NO_CERT_TOLERANCE_EXHAUSTED,
                Map.of("tolerance", String.valueOf(tolerance)));
        }
        return null;
    }

    /**
     * §5.2 emergency-filing exception (V125): the outcome of {@link #autoRejectNote} in the one case
     * that needs to communicate more than a rejection outcome -- whether THIS request was approved
     * specifically because it used the emergency tolerance (so {@link #submit} knows to record
     * {@code emergency_filing = TRUE} via {@link LeaveRepository#markEmergencyFiling}). {@code
     * blocking == null} means approved; {@code emergencyFilingApplied} is only ever {@code true}
     * on an approved outcome (never paired with a non-null {@code blocking}).
     *
     * <p>Phase A0a: {@code blocking} was a plain English rejection {@code String} ({@code
     * rejectionNote}); it is now a structured {@link LeaveRuleOutcome} (code + params + rendered
     * Thai sentence) -- see that record's Javadoc. This is a pure representation change: every call
     * site below still returns for the exact same reason, under the exact same condition, in the
     * exact same order as before.
     *
     * <p>Phase A0b: {@code coverageEvaluated} records whether {@link #departmentCoverageRuleOutcome}
     * actually RAN for this call -- {@code false} for every return that happens before reaching it
     * (every gate above it in {@link #autoRejectNote}, including the pre-existing emergency-filing
     * early-return at line ~1451, which already skipped department coverage entirely before this
     * field existed -- see that call site's own comment) or when the caller passed {@code
     * evaluateDepartmentCoverage = false} (QUICK-depth {@link #preview}); {@code true} only when the
     * method reached and executed {@link #departmentCoverageRuleOutcome}. {@link #submit} always
     * passes {@code evaluateDepartmentCoverage = true}, so this flag is {@code true} for every
     * submission that reaches that point today -- unchanged observable behaviour for submit.
     */
    private record AutoRejectResult(LeaveRuleOutcome blocking, boolean emergencyFilingApplied, boolean coverageEvaluated) {
        private static AutoRejectResult reject(LeaveRuleOutcome outcome) {
            return new AutoRejectResult(outcome, false, false);
        }
    }

    /**
     * §5.3.4 (ประกาศ "วันเวลาทำงาน และการหยุดงาน" eff. 1 ต.ค. 2567): <i>"ไม่อนุญาตให้พนักงานที่ยื่นใบ
     * ลาออกแล้ว ลาพักร้อน ลากิจ ยกเว้นได้รับอนุมัติจากหัวหน้างานว่าได้ทำงานที่ค้างอยู่เรียบร้อยแล้ว
     * ตลอดจนได้สอนงานและถ่ายทอดงานให้แก่พนักงานอื่นเรียบร้อยแล้ว"</i> ("An employee who has already
     * submitted a resignation may not take VACATION or PERSONAL leave, except with the supervisor's
     * approval that outstanding work has been finished and handed over to another employee.")
     *
     * <p><b>{@code hr.resignation} (V1__employee_master_schema.sql), inspected for this rule:</b> one
     * row per employee ({@code employee_id} PRIMARY KEY, 1:1 -- not a history table), with {@code
     * recorded_date} (when the resignation was recorded) and {@code resign_date} (the effective/last
     * working date), both nullable and neither documented, in that migration or anywhere else in this
     * codebase, as distinguishing "submitted" from "confirmed/effective". The announcement's "ยื่นใบ
     * ลาออกแล้ว" ("has ALREADY SUBMITTED [a resignation]") keys off submission, not effectiveness, so
     * this gate reads the mere EXISTENCE of a row as "has submitted a resignation" -- the only column
     * guaranteed non-null is {@code employee_id} itself, and this codebase has no other write path
     * that would insert a row here for any reason other than an actual resignation. {@code
     * resign_date} being in the future/past is deliberately NOT consulted: the normal case this rule
     * targets is an employee working out their notice period, still active, with a {@code
     * resign_date} that has not yet arrived; {@code hr.employee.is_active} is independently derived
     * FALSE once it does (V1's comment on that column), at which point {@link #validateEmployee}
     * already refuses the submission before this method is ever reached.
     *
     * <p><b>KNOWN GAP found while inspecting this table:</b> {@code PayrollService#remainingPayPeriods}
     * (2026-07-29) documents that resignations are not currently recorded in this platform at all --
     * they live in another system, so nothing populates {@code hr.resignation} in production today.
     * This gate is correct but DORMANT until that changes; flagged here so a green test suite is not
     * mistaken for "verified against real resignation data".
     *
     * <p><b>Handover-confirmation escape hatch: DOCUMENTED NON-ENFORCEMENT, not a system override.</b>
     * "ได้รับอนุมัติจากหัวหน้างานว่า...เรียบร้อยแล้ว" is a human judgement this system cannot verify --
     * the same shape of problem as {@link #personalProbationRuleOutcome} and every other categorical
     * gate below that ends its message with "Contact HR if this is an exception" while implementing no
     * system-level bypass (the exception is arranged entirely outside this codebase). This gate follows
     * that exact, already-established convention rather than inventing a new bypass (e.g. a submit-time
     * flag any caller could set) that the announcement gives no way to verify either.
     *
     * @return a rejection outcome ({@link LeaveRuleCode#RESIGNATION_GATE}), or {@code null} if the
     *         type is not resignation-gated or no resignation row exists for this employee. (Phase
     *         A0a: was a plain English rejection message; the DECISION is unchanged, only its return
     *         shape is now structured -- see {@link LeaveRuleOutcome}.)
     */
    private LeaveRuleOutcome resignationRuleOutcome(LeaveTypeDto leaveType, long employeeId) {
        if (!RESIGNATION_GATED_LEAVE_TYPES.contains(leaveType.code())) {
            return null;
        }
        if (!leaveRepository.hasSubmittedResignation(employeeId)) {
            return null;
        }
        return LeaveRuleOutcome.of(LeaveRuleCode.RESIGNATION_GATE, Map.of("leaveTypeNameTh", leaveType.nameTh()));
    }

    /**
     * §5.3.3: <i>"ไม่อนุญาตให้ลากิจและลาพักผ่อนติดต่อกัน"</i> ("PERSONAL and VACATION leave may not be
     * taken back to back"). Symmetric: submitting either type checks against the other.
     *
     * <p><b>DECISION -- "ติดต่อกัน" ("contiguous"/"back to back") across a non-working day:</b> two
     * requests are contiguous when there is no day the employee would actually have been AT WORK
     * between them -- i.e. every calendar day strictly between the earlier request's end and the
     * later request's start is a non-working day under {@link LeaveRepository#workingDayPredicate}
     * (schedule- AND holiday-aware, see {@link #hasInterveningWorkday}). A Friday PERSONAL request
     * immediately followed by a Monday VACATION request (an ordinary Sat/Sun weekend sitting between
     * them) therefore counts as CONTIGUOUS and is rejected: the employee never actually returned to
     * work between the two, so the weekend does not break the run any more than it would for two
     * halves of a single request. This is the reading that gives the rule teeth -- the opposite
     * reading (any calendar gap at all, including a plain weekend, breaks contiguity) would let every
     * VACATION/PERSONAL pair dodge this rule just by landing on either side of a weekend, which is
     * precisely the kind of disguised-extended-break pattern "ติดต่อกัน" exists to catch. A real,
     * worked weekday sitting between the two requests DOES break contiguity -- the employee genuinely
     * came back to work, so the two periods are not one continuous absence.
     *
     * <p>Searches only {@link #CONTIGUOUS_CHECK_WINDOW_DAYS} on either side of the new request's own
     * dates (see {@link LeaveRepository#findActiveRequestsByType}), not the employee's whole history
     * -- nothing further away can possibly be contiguous under any seeded schedule.
     *
     * @return a rejection outcome ({@link LeaveRuleCode#CONTIGUOUS_LEAVE_PAIR}), or {@code null} if
     *         this type is not gated or no contiguous request of the paired type exists. (Phase A0a:
     *         was a plain English rejection message; the DECISION is unchanged, only its return shape
     *         is now structured -- see {@link LeaveRuleOutcome}.)
     */
    private LeaveRuleOutcome contiguousLeaveRuleOutcome(LeaveTypeDto leaveType, long employeeId, LocalDate startDate, LocalDate endDate) {
        String pairedTypeCode = CONTIGUOUS_LEAVE_PAIR.get(leaveType.code());
        if (pairedTypeCode == null) {
            return null;
        }
        List<LeaveRequestSpan> existingRequests = leaveRepository.findActiveRequestsByType(
            employeeId, pairedTypeCode,
            startDate.minusDays(CONTIGUOUS_CHECK_WINDOW_DAYS), endDate.plusDays(CONTIGUOUS_CHECK_WINDOW_DAYS));
        for (LeaveRequestSpan existing : existingRequests) {
            if (isContiguous(employeeId, existing, startDate, endDate)) {
                // Phase A0a: pairedTypeNameTh is looked up ONLY on this (rare) rejection path, not on
                // every submission -- a presentational-only read (the paired type's own Thai name for
                // the message), not a change to the DECISION made just above. Falls back to the raw
                // code if the type row is somehow missing (defensive only; VACATION/PERSONAL are both
                // permanently-seeded rows).
                String pairedTypeNameTh = leaveRepository.findLeaveType(pairedTypeCode)
                    .map(LeaveTypeDto::nameTh)
                    .orElse(pairedTypeCode);
                return LeaveRuleOutcome.of(LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR, Map.of(
                    "leaveTypeNameTh", leaveType.nameTh(),
                    "pairedTypeNameTh", pairedTypeNameTh));
            }
        }
        return null;
    }

    /** See {@link #contiguousLeaveRuleOutcome}'s Javadoc for the across-a-non-working-day decision. */
    private boolean isContiguous(long employeeId, LeaveRequestSpan existing, LocalDate newStart, LocalDate newEnd) {
        LocalDate earlierEnd;
        LocalDate laterStart;
        if (existing.endDate().isBefore(newStart)) {
            earlierEnd = existing.endDate();
            laterStart = newStart;
        } else if (newEnd.isBefore(existing.startDate())) {
            earlierEnd = newEnd;
            laterStart = existing.startDate();
        } else {
            return true; // overlapping/touching ranges -- trivially contiguous.
        }
        return !hasInterveningWorkday(employeeId, earlierEnd.plusDays(1), laterStart.minusDays(1));
    }

    /**
     * True if at least one date in [{@code from}, {@code to}] (inclusive) is a day the employee would
     * actually have worked, per {@link LeaveRepository#workingDayPredicate} (schedule/holiday-aware,
     * see V115/V117/V121 and #470's LeaveDayMath rework). An empty/invalid range ({@code from} after
     * {@code to}, i.e. the two spans are adjacent with no days between them at all) returns {@code
     * false} -- no possible working day, so still contiguous.
     */
    private boolean hasInterveningWorkday(long employeeId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return false;
        }
        Predicate<LocalDate> isWorkingDay = leaveRepository.workingDayPredicate(employeeId, from, to);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (isWorkingDay.test(day)) {
                return true;
            }
        }
        return false;
    }

    /**
     * §5.3.2: <i>"ไม่อนุญาตให้ลาพร้อมกันทั้งแผนก ต้องมีพนักงานที่มีอายุงานเกิน 1 ปี มาทำงานอย่างน้อย 1
     * คน"</i> ("The whole department may not be on leave at the same time; at least one employee with
     * over 1 year of tenure must be at work.")
     *
     * <p><b>OWNER RELAXATION -- implemented here, NOT the literal text above:</b> the "&gt;1 year
     * tenure" qualifier is DROPPED. The rule actually enforced is simply: at least one employee of the
     * department must be at work. Do not reintroduce a tenure check into this method; it was
     * deliberately removed, not missed.
     *
     * <p>Binds at DEPARTMENT level. A PENDING (SUBMITTED) request counts as absent, the same as an
     * APPROVED one -- first-come-first-served, so two colleagues are never both told "yes" for the
     * same day (see {@link LeaveRepository#findActiveLeaveSpans}'s SUBMITTED/APPROVED scope).
     * Departments with fewer than {@value #MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE} ACTIVE employees
     * (requester included) are EXEMPT ENTIRELY -- see {@link #MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE}'s
     * own comment for the owner ruling this floor implements and why. "Active" matches {@link
     * LeaveRepository#findActiveDepartmentColleagues}'s {@code is_active = TRUE} filter -- a nominal
     * headcount that includes long-inactive rows (production has departments on paper far larger than
     * their real active count) would under-count how exposed the department genuinely is.
     *
     * <p>Applies to EVERY leave type, not just VACATION/PERSONAL -- the announcement's text has no
     * type qualifier here (contrast §5.3.3/§5.3.4, which both explicitly name their types), and this
     * reading is an owner-confirmed ruling: SICK included, the department-size floor above the only
     * carve-out. This means even SICK leave -- typically unplanned/urgent -- can be blocked if
     * approving it would leave a &ge;3-person department empty; flagged in the PR body as a known risk.
     *
     * <p><b>Schedule-awareness (V115/V117/V121, via #470's {@link LeaveRepository#workingDayPredicate}):
     * </b> "at work" is evaluated per employee's own resolved schedule, not a hardcoded Mon-Fri
     * assumption -- a warehouse (OPS_6D) colleague genuinely at work on a Saturday counts as coverage
     * even though the requester's own office (OFFICE_5D) schedule has Saturday off. Only days that are
     * a WORKDAY for the REQUESTER'S OWN schedule are checked at all -- a day the requester would not
     * have worked anyway is not a day their leave request removes any coverage from. This check is
     * independent of the CALENDAR_DAYS/WORKING_DAYS quota-counting basis (out of scope for this rule --
     * see CLAUDE.md); it always asks "would this person have physically worked this day", which is a
     * {@code workingDayPredicate} question regardless of how the requester's OWN leave type counts days
     * for quota purposes.
     *
     * @return a rejection outcome ({@link LeaveRuleCode#DEPARTMENT_COVERAGE}) naming the first
     *         uncovered date, or {@code null} if the department is exempt or stays covered on every
     *         day of the request. (Phase A0a: was a plain English rejection message naming the date;
     *         the DECISION is unchanged, only its return shape is now structured -- see
     *         {@link LeaveRuleOutcome}.)
     */
    private LeaveRuleOutcome departmentCoverageRuleOutcome(long employeeId, LocalDate startDate, LocalDate endDate) {
        List<Long> colleagueIds = leaveRepository.findActiveDepartmentColleagues(employeeId);
        // +1 for the requester, who is themselves an active member of the department but is
        // deliberately excluded from findActiveDepartmentColleagues's own result -- see that
        // method's Javadoc.
        int departmentSize = colleagueIds.size() + 1;
        if (departmentSize < MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE) {
            return null;
        }
        Predicate<LocalDate> ownWorkingDay = leaveRepository.workingDayPredicate(employeeId, startDate, endDate);
        // Built ONCE per colleague, outside the day loop below -- workingDayPredicate is a per-
        // employee-per-request call (two queries), not a per-day one; see its javadoc.
        Map<Long, Predicate<LocalDate>> colleagueWorkingDays = new HashMap<>();
        for (Long colleagueId : colleagueIds) {
            colleagueWorkingDays.put(colleagueId, leaveRepository.workingDayPredicate(colleagueId, startDate, endDate));
        }
        List<EmployeeLeaveSpan> colleagueLeave = leaveRepository.findActiveLeaveSpans(colleagueIds, startDate, endDate);

        for (LocalDate cursor = startDate; !cursor.isAfter(endDate); cursor = cursor.plusDays(1)) {
            LocalDate day = cursor;
            if (!ownWorkingDay.test(day)) {
                continue; // not a day the requester would have worked anyway -- no coverage at stake.
            }
            boolean someoneElseAtWork = colleagueIds.stream().anyMatch(colleagueId -> {
                if (!colleagueWorkingDays.get(colleagueId).test(day)) {
                    return false;
                }
                return colleagueLeave.stream().noneMatch(span ->
                    span.employeeId() == colleagueId
                        && !day.isBefore(span.startDate())
                        && !day.isAfter(span.endDate()));
            });
            if (!someoneElseAtWork) {
                return LeaveRuleOutcome.of(LeaveRuleCode.DEPARTMENT_COVERAGE,
                    Map.of("uncoveredDate", day.toString()));
            }
        }
        return null;
    }

    /**
     * Phase A0b dry-run extraction: the subset of {@link #autoRejectNote}'s checks that depend only
     * on the employee and leave type -- plus, for two of them, a single {@code referenceDate} -- not
     * on a request's actual date RANGE. Bundles, in the SAME order they always ran inline in {@link
     * #autoRejectNote}: once-per-employment, §5.3.4 resignation, hire-date-missing-on-a-prorated-type,
     * minimum service months, and PERSONAL probation. Every line of logic below is moved verbatim
     * from {@link #autoRejectNote} (Phase A0b extraction, not a rewrite) -- {@link #autoRejectNote}
     * calls this SAME method, with {@code referenceDate == startDate}, exactly where these five checks
     * used to sit inline, so a submission's decisions, thresholds and evaluation order are byte-for-
     * byte unchanged by this extraction.
     *
     * <p>Exists so {@link #preview} can evaluate these five when the caller has not chosen dates yet
     * (see {@code LeavePreviewRequest}'s nullable startDate/endDate) without a second, drift-prone
     * copy of the same decisions -- the brief this phase implements is explicit that the rules must
     * not be reimplemented, only extracted. For that dateless case, {@link #preview} passes {@code
     * LocalDate.now(clock)} as {@code referenceDate} -- the SAME "as-of-today" stand-in {@link
     * #balanceFor} already uses when a query has no request date of its own (see that method's
     * Javadoc on {@code annualQuota}), not a new convention invented for this method.
     *
     * @return the first blocking outcome among these five, or {@code null} if none applies.
     */
    private LeaveRuleOutcome eligibilityRuleOutcome(LeaveTypeDto leaveType, long employeeId, LocalDate referenceDate) {
        // §5.6 once-per-employment (ORDINATION today). Java-level check; ux_leave_once_per_employment
        // (V116) is the race-proof DB backstop -- see the DuplicateKeyException catch in #submit.
        if (leaveType.oncePerEmployment() && leaveRepository.hasOutstandingOrGrantedRequest(employeeId, leaveType.code())) {
            return LeaveRuleOutcome.of(LeaveRuleCode.ONCE_PER_EMPLOYMENT,
                Map.of("leaveTypeNameTh", leaveType.nameTh()));
        }

        // §5.3.4 post-resignation gate (relational rules, 2026-08). See #resignationRuleOutcome's
        // Javadoc for what hr.resignation actually contains and the handover-escape-hatch decision.
        LeaveRuleOutcome resignationOutcome = resignationRuleOutcome(leaveType, employeeId);
        if (resignationOutcome != null) {
            return resignationOutcome;
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
            return LeaveRuleOutcome.of(LeaveRuleCode.HIRE_DATE_MISSING_PRORATED,
                Map.of("leaveTypeNameTh", leaveType.nameTh()));
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
                return LeaveRuleOutcome.of(LeaveRuleCode.HIRE_DATE_MISSING_MIN_SERVICE,
                    Map.of("leaveTypeNameTh", leaveType.nameTh()));
            }
            long completedMonths = ChronoUnit.MONTHS.between(hireDate.get(), referenceDate);
            if (completedMonths < leaveType.minServiceMonths()) {
                return LeaveRuleOutcome.of(LeaveRuleCode.MIN_SERVICE_MONTHS, Map.of(
                    "leaveTypeNameTh", leaveType.nameTh(),
                    "minServiceMonths", String.valueOf(leaveType.minServiceMonths())));
            }
        }

        // §5.2 PERSONAL "passed probation" gate (review fix, V116): hardcoded to PERSONAL's code,
        // the same way the SICK certificate check below is hardcoded to SICK's code -- neither is a
        // per-type column the way quota/notice/consecutive-days are. See
        // #personalProbationRuleOutcome's Javadoc for the full rationale and decisions.
        if ("PERSONAL".equals(leaveType.code())) {
            LeaveRuleOutcome probationOutcome = personalProbationRuleOutcome(leaveType, employeeId, referenceDate);
            if (probationOutcome != null) {
                return probationOutcome;
            }
        }
        return null;
    }

    /**
     * §5 leave-rules-as-data (V116, extended V120/V124/V125, relational rules 2026-08). Checks run in
     * this order -- categorical eligibility first (once-per-employment, §5.3.4 post-resignation,
     * missing hire_date on a pro-rated type, minimum service, PERSONAL probation, the first-year
     * total-days cap), then request-shape (wedding-leave cap, max consecutive days, §5.3.3 contiguous
     * VACATION/PERSONAL), then document/timing checks (SICK certificate, advance notice x
     * emergency-filing exception), then finally the one gate that is not about the requester's own
     * eligibility at all (§5.3.2 department coverage) -- so the surfaced systemNote is always the most
     * fundamental reason the request cannot be paid, not an incidental one. Only the first violation
     * found is returned; see the individual checks below for what each one means and the decisions
     * behind it.
     *
     * <p>ORDERING NOTE (V125): the wedding-leave cap and the SICK-certificate/notice checks are both
     * PER-TYPE-CODE gates (PERSONAL and SICK respectively) that can never both apply to the same
     * request -- a request is submitted under exactly one leave_type_code, so their relative order
     * has no observable effect on any real submission. They stay in their pre-existing buckets
     * (wedding cap alongside max-consecutive-days as a request-SHAPE limit; SICK-certificate and
     * notice/emergency as document/timing checks, in that order) purely so each gate sits next to the
     * other gates it is conceptually closest to, not because the order between them matters.
     *
     * <p>ORDERING NOTE (relational rules, 2026-08): §5.3.4 (resignation) is CATEGORICAL -- like
     * once-per-employment, it is a "not eligible for this type at all" gate, so it runs immediately
     * after once-per-employment, ahead of every hire-date/tenure check (those still make sense to
     * report even for a resigned employee, but resignation is the more fundamental reason). §5.3.3
     * (contiguous) is REQUEST-SHAPE -- like max-consecutive-days, it is about the shape of this
     * request relative to nearby ones, so it sits directly after max-consecutive-days; it is
     * type-scoped (VACATION/PERSONAL only, via {@code CONTIGUOUS_LEAVE_PAIR}) and mutually exclusive
     * with the wedding cap and SICK-certificate gates by leave_type_code, so its exact position
     * relative to those two has no observable effect, same reasoning as V125's own ordering note
     * above. §5.3.2 (department coverage) is neither categorical, request-shape, nor document/timing
     * -- it is the ONE gate here that depends on OTHER employees' data rather than the requester's
     * own, and it is type-AGNOSTIC (applies to every leave type, SICK included -- owner ruling), so it
     * runs LAST, after every type-specific/requester-specific gate above (including all of V124/V125's)
     * has had a chance to give a more specific reason first.
     */
    private AutoRejectResult autoRejectNote(LeaveTypeDto leaveType, long employeeId, LocalDate startDate, LocalDate endDate,
            boolean hasAttachment, BigDecimal totalDays, String purposeCode, boolean requestedAsEmergency,
            boolean evaluateDepartmentCoverage) {
        // Phase A0b: the categorical eligibility gates (once-per-employment, resignation,
        // hire-date-missing-prorated, min-service-months, PERSONAL probation) are extracted to
        // #eligibilityRuleOutcome -- called here with the SAME arguments (leaveType, employeeId,
        // startDate), in the SAME order, as they ran inline before this extraction. This is a pure
        // relocation, not a behaviour change: see that method's Javadoc for why it exists (LeaveService
        // #preview needs to run exactly these checks against LocalDate.now(clock) when the caller has
        // not chosen dates yet, without a second, drift-prone copy of the same decisions).
        LeaveRuleOutcome eligibilityOutcome = eligibilityRuleOutcome(leaveType, employeeId, startDate);
        if (eligibilityOutcome != null) {
            return AutoRejectResult.reject(eligibilityOutcome);
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
                        return AutoRejectResult.reject(LeaveRuleOutcome.of(LeaveRuleCode.FIRST_YEAR_MAX_DAYS, Map.of(
                            "leaveTypeNameTh", leaveType.nameTh(),
                            "effectiveCap", formatDays(effectiveCap))));
                    }
                }
            }
        }

        // §5.2 wedding leave cap (V125): "ลาเข้าพิธีสมรส จัดงานสมรสของบุตรลาได้ไม่เกิน 3 วัน" -- own
        // marriage or a child's wedding, no more than 3 days. The ONLY per-PURPOSE limit §5.2 states
        // -- do not extend this pattern to the other named purposes, none of which carry a stated
        // cap.
        //
        // DECISION: a per-REQUEST calendar-day span cap (same mechanics as the generic
        // maxConsecutiveDays check just below), not a cumulative per-year total across every
        // WEDDING-purpose request -- a wedding is a discrete one-time occasion, unlike ลากิจ's
        // everyday, repeatable use, and "ไม่เกิน 3 วัน" reads naturally as "this occasion may not
        // exceed 3 days".
        //
        // DESIGN NOTE -- deliberately NOT a hr.leave_type column, unlike every OTHER §5 numeric rule
        // in this method: those vary meaningfully BY TYPE (V116/V120/V124). This rule is scoped to a
        // (type, purpose) PAIR -- PERSONAL + WEDDING specifically -- and no other purpose/type
        // combination carries a stated cap; a leave_type column would either apply to every purpose
        // of PERSONAL (wrong -- it would also cap FAMILY_NECESSITY/FAMILY_FUNERAL/etc.) or need a new
        // type x purpose table built for exactly one number. Hardcoded here instead, the same
        // precedent as the SICK-certificate check and the PERSONAL-probation gate above, both of
        // which are likewise keyed off leaveType.code() in Java rather than a column, for the
        // identical "does not generalize across every type" reason.
        if ("PERSONAL".equals(leaveType.code()) && WEDDING_PURPOSE_CODE.equals(purposeCode)) {
            long weddingSpanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (BigDecimal.valueOf(weddingSpanDays).compareTo(WEDDING_LEAVE_MAX_DAYS) > 0) {
                return AutoRejectResult.reject(LeaveRuleOutcome.of(LeaveRuleCode.WEDDING_MAX_DAYS,
                    Map.of("maxDays", formatDays(WEDDING_LEAVE_MAX_DAYS))));
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
                return AutoRejectResult.reject(LeaveRuleOutcome.of(LeaveRuleCode.MAX_CONSECUTIVE_DAYS, Map.of(
                    "leaveTypeNameTh", leaveType.nameTh(),
                    "maxConsecutiveDays", formatDays(leaveType.maxConsecutiveDays()))));
            }
        }

        // §5.3.3 contiguous VACATION/PERSONAL gate (relational rules, 2026-08) runs before the SICK
        // certificate gate just below. ORDERING NOTE: the two are mutually exclusive by leave type
        // (CONTIGUOUS_LEAVE_PAIR only keys VACATION/PERSONAL; sickCertificateRuleOutcome only fires
        // for SICK), so for any single request at most one of them can ever return non-null -- this
        // placement is a documentation choice (keeps it grouped with the request-shape checks above,
        // consistent with where it lived before V124), not a decision with an observable effect on
        // which message a user sees. See #contiguousLeaveRuleOutcome's Javadoc for the "ติดต่อกัน
        // across a non-working day" decision.
        LeaveRuleOutcome contiguousOutcome = contiguousLeaveRuleOutcome(leaveType, employeeId, startDate, endDate);
        if (contiguousOutcome != null) {
            return AutoRejectResult.reject(contiguousOutcome);
        }

        // §5.1 SICK certificate + filing-window + no-certificate tolerance (V124). See
        // #sickCertificateRuleOutcome's Javadoc for the combined decision table.
        if ("SICK".equals(leaveType.code())) {
            LeaveRuleOutcome sickOutcome = sickCertificateRuleOutcome(leaveType, employeeId, startDate, hasAttachment);
            if (sickOutcome != null) {
                return AutoRejectResult.reject(sickOutcome);
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
        //
        // §5.2 emergency-filing exception (V125), COMBINED notice x emergency decision table:
        //
        //   notice met?  | requestedAsEmergency? | monthly tolerance left? | outcome
        //   -------------|------------------------|--------------------------|--------------------------------
        //   yes           | (irrelevant)           | (irrelevant)             | proceeds normally (unaffected)
        //   no            | no                     | n/a                      | AUTO_REJECTED (unchanged, pre-V125 behaviour)
        //   no            | yes                    | yes (used < allowance)   | APPROVED via the exception; emergencyFilingApplied=true
        //   no            | yes                    | no (used >= allowance)   | AUTO_REJECTED (tolerance-exhausted message, not the generic notice one)
        //
        // "โดยไม่หักเงิน" ("without deduction") is read here as: an emergency-approved request is not
        // WORSE OFF than an on-time one -- it falls through to the SAME quota/paid-cap machinery every
        // other approved request already goes through (#submit), not as an unconditional override of
        // ordinary quota-exceeded unpaid splitting, which is an orthogonal, pre-existing rule. Only
        // types carrying a non-null emergencyMonthlyAllowance (PERSONAL today) get this exception at
        // all -- it composes with advanceNoticeDays generically (data-driven, not hardcoded to
        // PERSONAL's code), unlike the wedding cap above.
        //
        // ISOLATION FROM V124's SICK no-certificate tolerance: this counter
        // (LeaveRepository#countEmergencyFilings) reads hr.leave_request.emergency_filing, a column
        // set ONLY by #markEmergencyFiling for THIS gate; V124's sickCertificateRuleOutcome/
        // countNoCertificateRequestsInMonth instead reads attachment_id IS NULL and is called only
        // for SICK. The two counters share no column and are never invoked for the same
        // leave_type_code in practice (emergencyMonthlyAllowance is non-null only for PERSONAL,
        // noCertificateMonthlyTolerance meaningful only for SICK), so neither can consume the other's
        // monthly allowance -- see LeaveTypeDto's class Javadoc for the same point stated at the data
        // level.
        //
        // UNENFORCEABLE CONDITIONS, stated plainly (not silently assumed): §5.2 also requires the
        // supervisor was told before the shift started, and that the leave form is filed with the
        // supervisor on the first day back at work. Neither is representable in this schema -- there
        // is no "supervisor was notified" event and no "filed on day N of absence" concept anywhere
        // in hr.leave_request. This gate enforces only what it CAN verify (the requester's own
        // declaration and the <=N/month count); the other two conditions are left to the reviewing
        // manager/HR's judgement, same as every other manually-reviewed condition in this class (e.g.
        // MILITARY's voluntary-vs-call-up distinction, V116's migration comment). An emergency-
        // approved request is NOT proof the supervisor was actually notified in time -- do not read it
        // that way.
        int noticeDays = Math.max(0, leaveType.advanceNoticeDays());
        if (noticeDays > 0) {
            LocalDate earliestAllowed = LocalDate.now(clock).plusDays(noticeDays);
            if (startDate.isBefore(earliestAllowed)) {
                if (leaveType.emergencyMonthlyAllowance() != null && requestedAsEmergency) {
                    int usedThisMonth = leaveRepository.countEmergencyFilings(
                        employeeId, leaveType.code(), startDate, ACTIVE_QUOTA_STATUSES);
                    if (usedThisMonth < leaveType.emergencyMonthlyAllowance()) {
                        // PRE-EXISTING quirk, unchanged by Phase A0b: this return happens BEFORE the
                        // department-coverage block below, so an emergency-approved request always
                        // skips that gate entirely, regardless of `evaluateDepartmentCoverage` --
                        // coverageEvaluated is therefore `false` here, accurately reflecting that
                        // #departmentCoverageRuleOutcome never ran for this outcome. This is a
                        // behaviour this extraction inherited, not one it introduces; flagged in the
                        // PR body rather than silently "fixed".
                        return new AutoRejectResult(null, true, false);
                    }
                    return AutoRejectResult.reject(LeaveRuleOutcome.of(LeaveRuleCode.EMERGENCY_TOLERANCE_EXHAUSTED,
                        Map.of("allowance", String.valueOf(leaveType.emergencyMonthlyAllowance()))));
                }
                return AutoRejectResult.reject(LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE,
                    Map.of("noticeDays", String.valueOf(noticeDays))));
            }
        }

        // §5.3.2 department-coverage gate (relational rules, 2026-08). Runs LAST of all -- after
        // V125's wedding-cap/SICK-certificate/notice-and-emergency gates too, not just the pre-V125
        // set: it is the most data-heavy check (reads every other active department member's
        // schedule/leave) and, unlike every gate above (including V125's, which are all still about
        // THIS employee's own eligibility/timing), it is not about the requester at all -- it is the
        // least "fundamental" reason in the ordering this Javadoc describes, and it applies to every
        // leave type (type-agnostic), so it must not sit ahead of a type-specific rejection that would
        // otherwise have fired first. See #departmentCoverageRuleOutcome's Javadoc for the owner's
        // relaxation of the announcement's text, the department-size floor, and the schedule-awareness
        // this depends on.
        //
        // Phase A0b: gated on `evaluateDepartmentCoverage` -- #submit always passes `true` (identical
        // behaviour to before this parameter existed), so this branch is unconditionally reached for
        // every real submission, unchanged. QUICK-depth LeaveService#preview passes `false` instead,
        // to skip this check's fan-out over every active department colleague's schedule/leave spans
        // -- see #preview's Javadoc for why (debounced-as-the-user-types calls cannot afford it). The
        // check's own condition is untouched; only whether it runs at all is now caller-controlled.
        LeaveRuleOutcome departmentCoverageOutcome = evaluateDepartmentCoverage
            ? departmentCoverageRuleOutcome(employeeId, startDate, endDate)
            : null;
        if (departmentCoverageOutcome != null) {
            return new AutoRejectResult(departmentCoverageOutcome, false, true);
        }
        return new AutoRejectResult(null, false, evaluateDepartmentCoverage);
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

    /**
     * Leave HR-submit gate (2026-08-03): fires FIRST, before the subordinate-access check below and
     * before {@code submit}/{@code preview} do any further work (validateEmployee, rule evaluation,
     * DB write) -- this is the very first thing {@code submit} calls after basic request validation.
     * Self-submission ({@code targetEmployeeId == actorEmployeeId}) by an hr/ceo actor is refused
     * outright, regardless of {@code requestedEmployeeId} being explicit or defaulted from the
     * actor's own id.
     *
     * <p>Deliberately role-gated on SELF only, not on role alone: an hr/ceo actor submitting for
     * SOMEONE ELSE still falls through to the unchanged {@code canReviewEmployee} check below (HR's
     * blanket {@code REVIEW_ALL_ROLES} reach, or a manager-of-record relationship for anyone else,
     * ceo included) -- filing a paper form on a report's behalf is a real, still-supported HR
     * workflow; only filing for THEMSELVES is what the owner ruling blocks.
     */
    private long resolveTargetEmployee(Long requestedEmployeeId, UserPrincipal user) {
        long actorEmployeeId = requireEmployeeId(user);
        long targetEmployeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (targetEmployeeId == actorEmployeeId
                && user != null && SELF_SUBMIT_BLOCKED_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                "ฝ่ายบุคคลและผู้บริหารไม่สามารถยื่นคำขอลาให้ตนเองได้ กรุณาให้ผู้อื่นดำเนินการแทน");
        }
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
     * §5.2 leave purpose (V125). §5.2's text lists five illustrative purposes then ends with
     * "เป็นต้น" ("etc.") -- explicitly NON-EXHAUSTIVE. This validates only that a supplied,
     * non-blank code matches one of the five NAMED purposes or {@code OTHER} (the always-available
     * catch-all for anything not on the list) -- it can therefore never refuse a legitimate ลากิจ
     * request for lacking a matching category, only catch a malformed/misspelled code from the
     * caller. Blank/null returns {@code null} (no purpose selected).
     *
     * <p>DECISION: purpose is OPTIONAL, for every leave type including PERSONAL. Every request
     * submitted before V125 has none, and §5.2 never states that choosing a purpose category is a
     * precondition for filing ลากิจ -- only that the wedding sub-rule caps a request IF that is the
     * stated purpose (see {@link #autoRejectNote}'s wedding-cap check). Making this required would
     * retroactively block every existing submit flow that never asked for it; a caller that does not
     * care about the wedding cap (or is filing SICK/VACATION/etc.) can simply omit it.
     */
    private String normalizePurposeCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!KNOWN_PURPOSE_CODES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทเหตุผลการลาไม่ถูกต้อง");
        }
        return normalized;
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
     *
     * <p>Phase A0b: takes {@code startDate}/{@code endDate}/{@code startTime}/{@code endTime}
     * directly rather than a {@code SubmitLeaveRequest} (a pure parameter-extraction refactor, same
     * body, same behaviour for every #submit call site below) so {@link #preview} -- which has no
     * sub-day times in its request shape at all -- can call it too, passing {@code null} for both
     * times to take the same whole-day path a legacy no-times submission always took.
     */
    private BigDecimal computeTotalDays(
            LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
            LeaveTypeDto leaveType, Predicate<LocalDate> isWorkingDay) {
        if (startTime == null) {
            if (leaveType.dayCountBasis() == LeaveDayCountBasis.CALENDAR_DAYS) {
                return BigDecimal.valueOf(LeaveDayMath.countCalendarDays(startDate, endDate));
            }
            return workingDaysBetween(startDate, endDate, isWorkingDay);
        }
        long minutes = Duration.between(startTime, endTime).toMinutes();
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

    /**
     * GET /api/leave/review-summary phase (Phase A0b): stamps {@code canReview} onto {@code dto} for
     * THIS {@code user} -- a capability flag ("this actor could act on this employee's requests"),
     * computed from the SAME decision {@link #approve}/{@link #reject} already gate on ({@link
     * #canReviewAll}(user) OR {@link #isDirectManager}), not a role check: {@code REVIEW_ALL_ROLES}
     * is {@code {hr}} only, but any ฝ่าย manager may review their own direct reports too. Exposed so
     * the frontend stops inferring "can I approve this" from the actor's own role alone, which would
     * under-report for a department manager. It says nothing about whether THIS PARTICULAR request is
     * actionable right now -- {@link #approve}/{@link #reject} still enforce {@code status ==
     * SUBMITTED} server-side regardless of this flag, and callers should too.
     *
     * <p>{@code user.employeeId()} is read directly (not via {@link #requireEmployeeId}) so this can
     * never turn an otherwise-successful {@link #list} call into a 400 for an account with no bound
     * employee record -- such an actor simply can never be a direct manager; {@link #canReviewAll}
     * is still evaluated normally for them.
     *
     * <p>Records are immutable, so this is a full reconstruction with one field changed -- {@code
     * dto}'s own {@code canReview()} (always {@code false}, the placeholder {@link
     * LeaveRepository#mapRequest} writes -- see its comment) is discarded in favour of the value
     * computed here.
     */
    private LeaveRequestDto withCanReviewFlag(LeaveRequestDto dto, UserPrincipal user) {
        boolean canReview = canReviewAll(user)
            || (user.employeeId() != null && isDirectManager(dto.employeeId(), user.employeeId()));
        return new LeaveRequestDto(
            dto.id(), dto.employeeId(), dto.employeeCode(), dto.employeeName(),
            dto.leaveTypeCode(), dto.leaveTypeNameTh(), dto.leaveTypeNameEn(),
            dto.startDate(), dto.endDate(), dto.startTime(), dto.endTime(),
            dto.totalDays(), dto.paidDays(), dto.unpaidDays(), dto.quotaYear(),
            dto.reason(), dto.attachmentId(), dto.attachmentFileName(), dto.status(),
            dto.quotaRemainingBefore(), dto.quotaRemainingAfter(), dto.systemNote(),
            dto.requestedById(), dto.requestedByName(), dto.requestedAt(),
            dto.reviewedById(), dto.reviewedByName(), dto.reviewedAt(), dto.reviewerNote(),
            dto.cancelledAt(), dto.managerEmployeeId(), dto.managerName(),
            dto.createdAt(), dto.updatedAt(),
            dto.contactHouseNo(), dto.contactSubdistrict(), dto.contactDistrict(),
            dto.contactProvince(), dto.contactPhone(), dto.purposeCode(), dto.emergencyFiling(),
            dto.systemNoteCode(), dto.systemNoteParams(),
            canReview,
            dto.pendingApproverRole(), dto.pendingApproverName());
    }

    /**
     * GET /api/leave/review-summary (Phase A0b). Returns TWO independent signals -- see {@link
     * LeaveReviewSummaryDto}'s Javadoc for the full rationale, which this method's design refinement
     * (added after the frontend's phase A1 started consuming it) exists to satisfy:
     *
     * <ul>
     *   <li>{@code isReviewer}: can this actor review ANYONE at all, independent of whether anything
     *       is SUBMITTED right now. {@code true} for HR/CEO ({@link #canReviewAll}) or when {@link
     *       LeaveRepository#hasActiveDirectReports} finds at least one active direct report -- the
     *       SAME {@link #canReviewEmployee}/{@link #isDirectManager} shape {@link #approve}/{@link
     *       #reject}/{@link #withCanReviewFlag} already use, NOT a role check.
     *   <li>{@code pendingCount}: the count of SUBMITTED requests they may act on right now, via
     *       {@link LeaveRepository#countReviewableSubmitted}. Skipped entirely (returned as 0,
     *       without an extra query) when {@code isReviewer} is {@code false} -- {@code
     *       countReviewableSubmitted} would compute 0 for a non-reviewer anyway (it filters on the
     *       identical active-direct-report condition), so this is a pure optimization, not a
     *       different decision.
     * </ul>
     *
     * <p>A manager whose whole team happens to be caught up (zero SUBMITTED rows right now) still
     * gets {@code isReviewer = true} -- the motivating case a single "reviewable count" collapsed
     * into one field would have gotten wrong: it would have been indistinguishable from that actor
     * having no review rights at all, hiding the review queue's tab/entry point for exactly the
     * managers who use it, and making it flicker in and out of existence as requests come and go.
     */
    public LeaveReviewSummaryDto reviewSummary(UserPrincipal user) {
        if (canReviewAll(user)) {
            return new LeaveReviewSummaryDto(true, leaveRepository.countReviewableSubmitted(null, true));
        }
        long actorEmployeeId = requireEmployeeId(user);
        boolean isReviewer = leaveRepository.hasActiveDirectReports(actorEmployeeId);
        int pendingCount = isReviewer ? leaveRepository.countReviewableSubmitted(actorEmployeeId, false) : 0;
        return new LeaveReviewSummaryDto(isReviewer, pendingCount);
    }

    /**
     * GET /api/leave/attachments/{attachmentId} (Phase A0b): resolves an attachment to its storage
     * location, authorizing BEFORE returning it -- mirrors {@code
     * SpecialMoneyService#resolveAttachmentForDownload} (the pattern this phase's brief names to
     * copy). An unknown/nonexistent attachment id returns 404, never 403, so this endpoint cannot be
     * used to probe which ids exist -- the SAME 404-before-authz-check ordering
     * SpecialMoneyService's version uses.
     *
     * <p>AUTHORIZATION CHANGE (stated per CLAUDE.md's rule for sales/CRM-adjacent authz work, though
     * leave is outside that section -- the same discipline applies to any new authz surface): before
     * this endpoint existed, a leave attachment (e.g. a SICK medical certificate) was upload-only --
     * nothing could read it back. The access predicate is {@link #canAccessEmployee} (self or direct
     * manager) OR {@link #canReviewEmployee} (HR/CEO or direct manager) -- the union is: the employee
     * themselves, their own direct manager, or HR/CEO. A same-department peer who is not the manager,
     * an unrelated employee, or a DIFFERENT employee's manager are all refused. This is a NEW
     * authorization decision, not inferred from {@code mockApi.js} -- see the real-Postgres
     * integration test ({@code LeaveAttachmentDownloadAuthzIntegrationTest}) this phase ships
     * alongside it, written wrong-way-round (asserting what each caller CANNOT reach).
     *
     * <p><b>V134 storage-durability fix -- ordering is itself a security property, do not
     * reorder:</b> this method now ALSO reports whether the attachment's bytes are actually
     * available ({@link LeaveAttachmentRepository.AttachmentLocation#storageState()} {@code
     * DATABASE}, or {@code DISK_LEGACY} with a file that still resolves), and throws {@code 410
     * GONE} when they are not -- but that check runs STRICTLY AFTER the 404 (unknown id) and 403
     * (known id, not permitted) checks above. If availability were checked first, a caller could
     * distinguish "this id doesn't exist" (404) from "this id exists but its bytes are gone" (what
     * would otherwise be 410) WITHOUT ever being authorized to know the id exists at all -- turning
     * an availability signal into an existence-probe oracle. An unauthorized caller must always see
     * exactly the same 403 an authorized caller with a perfectly healthy attachment would never see,
     * regardless of whether the underlying row is DATABASE, DISK_LEGACY, or MISSING.
     */
    public LeaveAttachmentRepository.AttachmentLocation resolveAttachmentForDownload(long attachmentId, UserPrincipal user) {
        // 1. Unknown id -> 404, before anything else.
        LeaveAttachmentRepository.AttachmentLocation location = leaveAttachments.findAttachmentLocation(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบเอกสารนี้"));
        LeaveRequestDto owningRequest = leaveRepository.findById(location.leaveRequestId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบเอกสารนี้"));
        // 2. Known id, caller not permitted -> 403.
        long actorEmployeeId = requireEmployeeId(user);
        boolean allowed = canAccessEmployee(actorEmployeeId, owningRequest.employeeId())
            || canReviewEmployee(owningRequest.employeeId(), actorEmployeeId, user);
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        // 3. Known id, permitted, bytes unavailable -> 410 GONE. Only reached once 1 and 2 have
        // already let the caller through, so this can never leak existence to an unauthorized caller.
        if (!bytesAvailable(location)) {
            throw new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล");
        }
        return location;
    }

    /**
     * {@code DATABASE} rows are trusted without a second query here -- {@link
     * LeaveAttachmentRepository#saveWithContent} inserts the blob and flips {@code storage_state}
     * to {@code DATABASE} in the same transaction, so the two can never disagree for a row this
     * query can see at all. {@code DISK_LEGACY} rows are checked against the actual filesystem
     * (they may already be gone -- e.g. every UAT/Render deploy before this migration). Anything
     * else ({@code MISSING}, or a future state this code doesn't know about) is unavailable.
     */
    private boolean bytesAvailable(LeaveAttachmentRepository.AttachmentLocation location) {
        return switch (location.storageState()) {
            case "DATABASE" -> true;
            case "DISK_LEGACY" -> fileStorage.existsOnDisk(location.storagePath());
            default -> false;
        };
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
