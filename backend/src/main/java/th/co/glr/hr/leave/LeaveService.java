package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@Service
public class LeaveService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo", "admin");
    private static final Set<String> REVIEW_ALL_ROLES = Set.of("hr", "admin");
    private static final Set<LeaveStatus> ACTIVE_QUOTA_STATUSES = Set.of(LeaveStatus.SUBMITTED, LeaveStatus.APPROVED);

    private final LeaveRepository leaveRepository;

    public LeaveService(LeaveRepository leaveRepository) {
        this.leaveRepository = leaveRepository;
    }

    public List<LeaveRequestDto> list(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            String requestedStatus) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
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
        int year = requestedYear == null ? LocalDate.now(BUSINESS_ZONE).getYear() : requestedYear;
        return leaveRepository.findLeaveTypes().stream()
            .map(type -> balanceFor(employeeId, year, type))
            .toList();
    }

    @Transactional
    public LeaveRequestDto submit(SubmitLeaveRequest request, UserPrincipal user) {
        long actorEmployeeId = requireEmployeeId(user);
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        LeaveTypeDto leaveType = requireLeaveType(request.leaveTypeCode());
        validateDateRange(request.startDate(), request.endDate());

        BigDecimal totalDays = workingDaysBetween(request.startDate(), request.endDate());
        int quotaYear = request.startDate().getYear();
        BigDecimal remainingBefore = remainingDays(employeeId, leaveType, quotaYear);
        boolean quotaAvailable = remainingBefore.compareTo(totalDays) >= 0;
        LeaveStatus status = quotaAvailable ? LeaveStatus.SUBMITTED : LeaveStatus.AUTO_REJECTED;
        BigDecimal remainingAfter = quotaAvailable ? remainingBefore.subtract(totalDays) : remainingBefore;
        String systemNote = quotaAvailable ? null : insufficientQuotaNote(remainingBefore, totalDays);

        long id = leaveRepository.create(
            employeeId,
            actorEmployeeId,
            request,
            totalDays,
            quotaYear,
            status,
            remainingBefore,
            remainingAfter.max(BigDecimal.ZERO),
            systemNote
        );
        return requireRequest(id);
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
        return requireRequest(id);
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
        return requireRequest(id);
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
        return requireRequest(id);
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
        int days = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                days += 1;
            }
            cursor = cursor.plusDays(1);
        }
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

    private String insufficientQuotaNote(BigDecimal remainingBefore, BigDecimal requestedDays) {
        return "Remaining quota is " + remainingBefore.stripTrailingZeros().toPlainString()
            + " day(s), which is not enough for this " + requestedDays.stripTrailingZeros().toPlainString()
            + " day request. Contact HR to adjust quota or follow the unpaid leave process.";
    }
}
