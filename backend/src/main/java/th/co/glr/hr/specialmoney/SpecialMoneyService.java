package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

/**
 * Modelled closely on {@code th.co.glr.hr.overtime.OvertimeService}: same manager/CEO gate shapes,
 * same dispatch-on-status approve()/reject(), same notification fan-out. See that class for the
 * pattern this mirrors.
 */
@Service
public class SpecialMoneyService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");

    private final SpecialMoneyRepository repository;
    private final SpecialMoneyPolicyEvaluator evaluator;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;

    public SpecialMoneyService(
            SpecialMoneyRepository repository,
            SpecialMoneyPolicyEvaluator evaluator,
            AuditService auditService,
            NotificationService notificationService,
            AppProperties appProperties) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.appProperties = appProperties;
    }

    public List<SpecialMoneyRequestDto> list(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            String requestedStatus,
            String requestType) {
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

        return repository.findRequests(new SpecialMoneyFilter(
            employeeId,
            managerEmployeeId,
            managerDivisionId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus),
            requestType
        ));
    }

    public List<SpecialMoneyEmployeeOption> employeeOptions(UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        Long managerDivisionId = user.manager() ? user.divisionId() : null;
        return repository.findEmployeeOptions(actorEmployeeId, managerDivisionId, canViewAll(user));
    }

    public SpecialMoneyUsageDto usage(long employeeId, int year, UserPrincipal user) {
        if (!canAccessEmployee(user, employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        UsageSnapshot snapshot = repository.findUsage(employeeId, year);
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        snapshot.approvedAmountThisYearByType().forEach((type, amount) -> amounts.put(type.name(), amount));
        Map<String, Integer> counts = new LinkedHashMap<>();
        snapshot.approvedCountLifetimeByType().forEach((type, count) -> counts.put(type.name(), count));
        return new SpecialMoneyUsageDto(employeeId, year, amounts, counts);
    }

    @Transactional
    public SpecialMoneyRequestDto submit(String requestTypeRaw, SubmitSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        SpecialMoneyType type = parseType(requestTypeRaw);

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        EmployeeEligibilitySnapshot eligibility = repository.findEligibility(employeeId, today)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Employee not found"));
        UsageSnapshot usage = repository.findUsage(employeeId, usageYear(request, today));
        PolicyAmounts amounts = repository.findPolicyAmounts(type.name(), today);
        Set<String> excludedProvinces = repository.findExcludedProvinces();

        PolicyDecision decision = evaluator.evaluate(type, request, eligibility, usage, amounts, excludedProvinces);
        if (!decision.violations().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, String.join("; ", decision.violations()));
        }

        long id = repository.create(employeeId, actorEmployeeId, request, type, decision);
        SpecialMoneyRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_SPECIAL_MONEY_REQUEST", "special_money_request", id, null, created);
        notifySubmitted(created);
        return created;
    }

    @Transactional
    public SpecialMoneyRequestDto approve(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);
        SpecialMoneyStatus status = parseStatus(existing.status());
        if (status == SpecialMoneyStatus.SUBMITTED) {
            return managerApprove(id, request, user, actorEmployeeId, existing);
        }
        if (status == SpecialMoneyStatus.MANAGER_APPROVED) {
            return ceoApprove(id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "Special money request has already been reviewed");
    }

    private SpecialMoneyRequestDto managerApprove(
            long id,
            ReviewSpecialMoneyRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            SpecialMoneyRequestDto existing) {
        requireManager(existing.employeeId(), user);

        int updated = repository.managerApprove(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Special money request has already been reviewed");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "MANAGER_APPROVE_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        notifyManagerApproved(after);
        return after;
    }

    private SpecialMoneyRequestDto ceoApprove(
            long id,
            ReviewSpecialMoneyRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            SpecialMoneyRequestDto existing) {
        requireCeo(user);

        BigDecimal approvedAmount = request != null && request.approvedAmount() != null
            ? request.approvedAmount()
            : existing.requestedAmount();
        String capOverrideReason = request == null ? null : blankToNull(request.capOverrideReason());

        SpecialMoneyType type = parseType(existing.requestType());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        EmployeeEligibilitySnapshot eligibility = repository.findEligibility(existing.employeeId(), today)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        UsageSnapshot usage = repository.findUsage(existing.employeeId(), existing.eventDate().getYear());
        PolicyAmounts amounts = repository.findPolicyAmounts(type.name(), today);
        Set<String> excludedProvinces = repository.findExcludedProvinces();

        // Re-run the evaluator with the CEO's chosen amount substituted in, purely to learn what the
        // policy cap allows for this type/employee -- we deliberately do not gate on the recheck's
        // eligibility violations (e.g. once-per-lifetime) here, since this same MANAGER_APPROVED
        // request is itself counted in "usage" and would otherwise trip its own guard.
        SubmitSpecialMoneyRequest recheckRequest = new SubmitSpecialMoneyRequest(
            existing.employeeId(),
            existing.eventDate(),
            existing.eventEndDate(),
            existing.receiptDate(),
            existing.quantity(),
            approvedAmount,
            existing.reason(),
            existing.detail());
        PolicyDecision recheck = evaluator.evaluate(type, recheckRequest, eligibility, usage, amounts, excludedProvinces);

        boolean exceedsCap = approvedAmount.compareTo(recheck.eligibleAmount()) > 0;
        if (exceedsCap && capOverrideReason == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "capOverrideReason is required when the approved amount exceeds the policy cap");
        }

        // The 25th-of-month payroll cutoff. Rolling forward past an already-PROCESSED month is
        // deliberate: payroll writes a processed period once, so a request landing in a closed month
        // would be approved and then never paid.
        int cutoffDay = appProperties.getSpecialMoney().getPayrollCutoffDay();
        LocalDate approvedOn = LocalDate.now(BUSINESS_ZONE);
        LocalDate payrollMonth = approvedOn.getDayOfMonth() <= cutoffDay
            ? approvedOn.withDayOfMonth(1)
            : approvedOn.plusMonths(1).withDayOfMonth(1);
        while (repository.payrollMonthProcessed(payrollMonth)) {
            payrollMonth = payrollMonth.plusMonths(1);
        }

        int updated = repository.ceoApprove(id, actorEmployeeId, approvedAmount, payrollMonth, capOverrideReason, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Special money request has already been reviewed");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_APPROVE_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        notifyCeoApproved(after);
        return after;
    }

    @Transactional
    public SpecialMoneyRequestDto reject(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);
        SpecialMoneyStatus status = parseStatus(existing.status());
        if (status == SpecialMoneyStatus.SUBMITTED) {
            return managerReject(id, request, user, actorEmployeeId, existing);
        }
        if (status == SpecialMoneyStatus.MANAGER_APPROVED) {
            return ceoReject(id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "Special money request has already been reviewed");
    }

    private SpecialMoneyRequestDto managerReject(
            long id,
            ReviewSpecialMoneyRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            SpecialMoneyRequestDto existing) {
        requireManager(existing.employeeId(), user);
        int updated = repository.reject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Special money request has already been reviewed");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "REJECT_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        notifyRejected(after);
        return after;
    }

    private SpecialMoneyRequestDto ceoReject(
            long id,
            ReviewSpecialMoneyRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            SpecialMoneyRequestDto existing) {
        requireCeo(user);
        int updated = repository.ceoReject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Special money request has already been reviewed");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_REJECT_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        notifyRejected(after);
        return after;
    }

    @Transactional
    public SpecialMoneyRequestDto cancel(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        SpecialMoneyRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        boolean isEmployee = existing.employeeId() == actorEmployeeId;
        boolean isRequester = existing.requestedById() != null && existing.requestedById() == actorEmployeeId;
        if (!isEmployee && !isRequester) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only submitted special money requests can be cancelled");
        }

        int updated = repository.cancel(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Special money request can no longer be cancelled");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "CANCEL_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        return after;
    }

    // ---------------------------------------------------------------------
    // Gates
    // ---------------------------------------------------------------------

    private long resolveTargetEmployee(Long requestedEmployeeId, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long targetEmployeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (targetEmployeeId != actorEmployeeId && !managesEmployee(targetEmployeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Employees can only submit their own special-money requests");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!repository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee not found");
        }
    }

    private void requireManager(long employeeId, UserPrincipal user) {
        if (!managesEmployee(employeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the employee's manager can review special-money requests");
        }
    }

    private void requireCeo(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the CEO can approve manager-approved special-money requests");
        }
    }

    private boolean canAccessEmployee(UserPrincipal user, long employeeId) {
        return canViewAll(user)
            || (user.employeeId() != null && user.employeeId() == employeeId)
            || managesEmployee(employeeId, user);
    }

    /**
     * True when {@code user} manages the given employee -- either as the employee's direct
     * reports-to manager, or as a ฝ่าย manager sharing the employee's division (excluding self).
     * Mirrors {@code OvertimeService.managesEmployee}. HR is not special-cased here: it gets no
     * manager-shaped access to submit or review on someone else's behalf.
     */
    private boolean managesEmployee(long employeeId, UserPrincipal user) {
        if (user == null || user.employeeId() == null) {
            return false;
        }
        return repository.findEmployeeAccess(employeeId)
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

    private boolean canViewAll(UserPrincipal user) {
        return user != null && VIEW_ALL_ROLES.contains(user.role());
    }

    // ---------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------

    private void notifySubmitted(SpecialMoneyRequestDto request) {
        String title = "ส่งคำขอเงินสวัสดิการแล้ว";
        String message = "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกส่งให้ผู้จัดการตรวจสอบแล้ว";
        notificationService.notify(request.employeeId(), "SPECIAL_MONEY_SUBMITTED", title, message, "/employee-requests", true);
        if (request.managerEmployeeId() != null) {
            notificationService.notify(
                request.managerEmployeeId(),
                "SPECIAL_MONEY_PENDING_MANAGER",
                "มีคำขอเงินสวัสดิการรออนุมัติ",
                request.employeeName() + " ส่งคำขอ " + request.requestType() + " วันที่ " + request.eventDate(),
                "/employee-requests",
                true
            );
        }
    }

    private void notifyManagerApproved(SpecialMoneyRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_MANAGER_APPROVED",
            "ผู้จัดการอนุมัติคำขอเงินสวัสดิการแล้ว",
            "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ผ่านผู้จัดการแล้ว และรอ CEO อนุมัติขั้นสุดท้าย",
            "/employee-requests",
            true
        );
        for (Long ceoEmployeeId : repository.findCeoApproverEmployeeIds()) {
            notificationService.notify(
                ceoEmployeeId,
                "SPECIAL_MONEY_PENDING_CEO",
                "มีคำขอเงินสวัสดิการรอ CEO อนุมัติ",
                request.employeeName() + " มีคำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ที่ผู้จัดการอนุมัติแล้ว",
                "/employee-requests",
                true
            );
        }
    }

    private void notifyCeoApproved(SpecialMoneyRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_APPROVED",
            "CEO อนุมัติคำขอเงินสวัสดิการแล้ว",
            "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " อนุมัติครบถ้วนแล้ว",
            "/employee-requests",
            true
        );
        if (request.managerApprovedBy() != null) {
            notificationService.notify(
                request.managerApprovedBy(),
                "SPECIAL_MONEY_APPROVED",
                "CEO อนุมัติคำขอเงินสวัสดิการแล้ว",
                request.employeeName() + " ได้รับการอนุมัติคำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ครบถ้วนแล้ว",
                "/employee-requests",
                true
            );
        }
    }

    private void notifyRejected(SpecialMoneyRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_REJECTED",
            "คำขอเงินสวัสดิการถูกปฏิเสธ",
            "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกปฏิเสธ: "
                + (request.reviewerNote() == null ? "กรุณาติดต่อผู้จัดการหรือ HR" : request.reviewerNote()),
            "/employee-requests",
            true
        );
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private int usageYear(SubmitSpecialMoneyRequest request, LocalDate today) {
        return request.eventDate() != null ? request.eventDate().getYear() : today.getYear();
    }

    private SpecialMoneyRequestDto requireRequest(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Special money request not found"));
    }

    private Long requireEmployeeId(UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
        }
        return user.employeeId();
    }

    private SpecialMoneyStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpecialMoneyStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid special money status");
        }
    }

    private SpecialMoneyType parseType(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "requestType is required");
        }
        try {
            return SpecialMoneyType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid special money request type");
        }
    }

    private String note(ReviewSpecialMoneyRequest request) {
        return request == null || request.reviewerNote() == null || request.reviewerNote().isBlank()
            ? null
            : request.reviewerNote().trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
