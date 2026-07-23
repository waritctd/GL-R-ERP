package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

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

    private final LeaveRepository leaveRepository;
    private final LeaveAttachmentRepository leaveAttachments;
    private final FileStorageService fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final Clock clock;

    @Autowired
    public LeaveService(LeaveRepository leaveRepository,
                        LeaveAttachmentRepository leaveAttachments,
                        FileStorageService fileStorage,
                        AuditService auditService,
                        NotificationService notificationService,
                        AppProperties appProperties) {
        this(leaveRepository, leaveAttachments, fileStorage, auditService, notificationService, appProperties,
            Clock.system(BUSINESS_ZONE));
    }

    LeaveService(LeaveRepository leaveRepository,
                 LeaveAttachmentRepository leaveAttachments,
                 FileStorageService fileStorage,
                 AuditService auditService,
                 NotificationService notificationService,
                 AppProperties appProperties,
                 Clock clock) {
        this.leaveRepository = leaveRepository;
        this.leaveAttachments = leaveAttachments;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.appProperties = appProperties;
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
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        validateEmployee(employeeId);
        int year = requestedYear == null ? LocalDate.now(clock).getYear() : requestedYear;
        return leaveRepository.findLeaveTypes().stream()
            .map(type -> balanceFor(employeeId, year, type))
            .toList();
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

        BigDecimal totalDays = workingDaysBetween(request.startDate(), request.endDate());
        int quotaYear = request.startDate().getYear();
        BigDecimal remainingBefore = remainingDays(employeeId, leaveType, quotaYear);
        boolean hasAttachment = attachment != null && !attachment.isEmpty();
        // Leave -> payroll unpaid-day deduction (2026-07-23): the gate no longer auto-rejects purely
        // for exceeding quota. It approves and splits the requested days into paidDays (covered by
        // remaining statutory quota) and unpaidDays (no-work-no-pay, deducted downstream in payroll at
        // base/30 per unpaid WORKING day -- see PayrollCalculator#unpaidLeaveDeduction). The only
        // remaining auto-reject reasons are non-quota ones: a SICK request missing its required
        // attachment, and insufficient advance notice. See docs/agent-handoffs for the HR/legal
        // sign-off caveat this rule still needs before it drives a real payroll run.
        String systemNote = autoRejectNote(leaveType, request.startDate(), hasAttachment);
        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;
        BigDecimal paidDays;
        BigDecimal unpaidDays;
        BigDecimal remainingAfter;
        if (status == LeaveStatus.APPROVED) {
            // paidDays consumes from the request's earliest working days first (chronological order):
            // that is the only ordering an aggregate paid/unpaid split can represent, and it matches
            // the natural reading of "day N onward went unpaid". See LeaveDayMath.
            paidDays = remainingBefore.min(totalDays).max(BigDecimal.ZERO);
            unpaidDays = totalDays.subtract(paidDays);
            remainingAfter = remainingBefore.subtract(paidDays).max(BigDecimal.ZERO);
        } else {
            paidDays = BigDecimal.ZERO;
            unpaidDays = BigDecimal.ZERO;
            remainingAfter = remainingBefore;
        }

        long id = leaveRepository.create(
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
            systemNote
        );
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
            throw new ApiException(HttpStatus.CONFLICT, "Leave request has already been reviewed");
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
            throw new ApiException(HttpStatus.CONFLICT, "Leave request has already been reviewed");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!reviewer && !"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only submitted leave requests can be cancelled by employees");
        }
        if (!"SUBMITTED".equals(existing.status()) && !"APPROVED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active leave requests can be cancelled");
        }

        int updated = leaveRepository.cancel(id, reviewer ? actorEmployeeId : null, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave request can no longer be cancelled");
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
        int paidDays = cancelled.paidDays() == null ? 0 : cancelled.paidDays().setScale(0, RoundingMode.DOWN).intValue();
        Map<LocalDate, Integer> unpaidByMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            cancelled.startDate(), cancelled.endDate(), paidDays);
        if (unpaidByMonth.isEmpty()) {
            return;
        }
        Set<LocalDate> processedMonths = leaveRepository.findProcessedPayrollMonths(unpaidByMonth.keySet());
        for (LocalDate month : processedMonths) {
            Integer days = unpaidByMonth.get(month);
            if (days != null && days > 0) {
                leaveRepository.recordPayrollCorrection(cancelled.id(), cancelled.employeeId(), month, BigDecimal.valueOf(days));
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

    private String autoRejectNote(LeaveTypeDto leaveType, LocalDate startDate, boolean hasAttachment) {
        if ("SICK".equals(leaveType.code()) && !hasAttachment) {
            return "Sick leave requires a medical certificate attachment. Attach the certificate or contact HR for help.";
        }
        int noticeDays = Math.max(0, appProperties.getLeave().getAdvanceNoticeDays());
        LocalDate earliestAllowed = LocalDate.now(clock).plusDays(noticeDays);
        if (!"SICK".equals(leaveType.code()) && startDate.isBefore(earliestAllowed)) {
            return "Leave requests must be submitted at least " + noticeDays
                + " day(s) before the start date. Contact your manager or HR for urgent leave.";
        }
        return null;
    }

    private void notifyAfterSubmit(LeaveRequestDto request, LeaveStatus status) {
        if (status == LeaveStatus.APPROVED) {
            boolean hasUnpaidDays = request.unpaidDays() != null && request.unpaidDays().signum() > 0;
            String unpaidSuffix = hasUnpaidDays
                ? " (รวมวันลาไม่รับค่าจ้าง " + formatDays(request.unpaidDays()) + " วัน เนื่องจากเกินโควตา)"
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
                    true);
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
            throw new ApiException(HttpStatus.FORBIDDEN, "Employees can only request leave for themselves or eligible reports");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!leaveRepository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee not found");
        }
    }

    private void validateSubmitRequest(SubmitLeaveRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Leave request is required");
        }
        if (request.startDate() == null || request.endDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Leave dates are required");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Leave reason is required");
        }
    }

    private LeaveTypeDto requireLeaveType(String value) {
        String code = value == null ? "" : value.trim().toUpperCase();
        return leaveRepository.findLeaveType(code)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid leave type"));
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Leave end date must be on or after start date");
        }
        if (startDate.getYear() != endDate.getYear()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Leave requests cannot span quota years");
        }
    }

    private BigDecimal workingDaysBetween(LocalDate startDate, LocalDate endDate) {
        int days = LeaveDayMath.countWorkingDays(startDate, endDate);
        if (days <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Leave range must include at least one weekday");
        }
        return BigDecimal.valueOf(days);
    }

    private void requireReviewer(long employeeId, long actorEmployeeId, UserPrincipal user) {
        if (!canReviewEmployee(employeeId, actorEmployeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only HR or the employee's direct manager can review leave");
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Leave request not found"));
    }

    private void requireStatus(LeaveRequestDto request, LeaveStatus status) {
        if (!status.name().equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave request has already been reviewed");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid leave status");
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
