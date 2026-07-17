package th.co.glr.hr.profile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeDto;
import th.co.glr.hr.employee.EmployeeRepository;

@Service
public class ProfileRequestService {
    private static final Set<String> SUPPORTED_FIELDS = Set.of("phone", "email", "address", "emergency");

    private final ProfileRequestRepository profileRequests;
    private final EmployeeRepository employees;
    private final AuditService auditService;

    public ProfileRequestService(ProfileRequestRepository profileRequests, EmployeeRepository employees,
                                 AuditService auditService) {
        this.profileRequests = profileRequests;
        this.employees = employees;
        this.auditService = auditService;
    }

    public List<ProfileRequestDto> list(UserPrincipal user) {
        List<ProfileRequestRecord> records = user.role().equals("hr")
            ? profileRequests.findAll()
            : user.employeeId() == null ? List.of() : profileRequests.findByEmployee(user.employeeId());
        Map<Long, EmployeeDto> employeesById = employees.findEmployeeSummariesByIds(records.stream()
            .map(ProfileRequestRecord::employeeId)
            .distinct()
            .toList());
        return records.stream()
            .map(record -> toDto(record, employeesById.get(record.employeeId())))
            .toList();
    }

    @Transactional
    public ProfileRequestDto create(CreateProfileRequestRequest request, UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "User is not linked to an employee");
        }
        if (!SUPPORTED_FIELDS.contains(request.fieldKey())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported profile field");
        }
        long id = profileRequests.create(user.employeeId(), request, user);
        return profileRequests.findById(id).map(this::toDto).orElseThrow();
    }

    @Transactional
    public ProfileRequestDto update(long id, UpdateProfileRequestRequest request, UserPrincipal reviewer) {
        if (request.status() == null || request.status().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Status is required");
        }
        ProfileRequestRecord existing = profileRequests.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Profile request not found"));
        if (!"pending".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Profile request has already been reviewed");
        }

        int updated = profileRequests.updatePendingStatus(id, request.status(), reviewer, request.reviewerNote());
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Profile request has already been reviewed");
        }
        if ("approved".equals(request.status()) && "pending".equals(existing.status())) {
            applyApprovedRequest(existing);
        }
        ProfileRequestRecord reviewed = profileRequests.findById(id).orElseThrow();
        String action = "approved".equals(request.status())
            ? "APPROVE_PROFILE_REQUEST"
            : "REJECT_PROFILE_REQUEST";
        auditService.record(reviewer, action, "profile_request", id, existing, reviewed);
        return toDto(reviewed);
    }

    private void applyApprovedRequest(ProfileRequestRecord request) {
        switch (request.fieldKey()) {
            case "phone" -> employees.updatePhone(request.employeeId(), request.newValue());
            case "email" -> employees.updateEmail(request.employeeId(), request.newValue());
            case "address" -> employees.updateAddressLine(request.employeeId(), request.newValue());
            case "emergency" -> {
                String[] parts = request.newValue().split("·", 2);
                String name = parts[0].trim();
                String phone = parts.length > 1 ? parts[1].trim() : null;
                employees.updateEmergencyContact(request.employeeId(), name, phone);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported profile field");
        }
    }

    private ProfileRequestDto toDto(ProfileRequestRecord record) {
        return toDto(record, employees.findEmployeeSummaryById(record.employeeId()).orElse(null));
    }

    private ProfileRequestDto toDto(ProfileRequestRecord record, EmployeeDto employee) {
        return new ProfileRequestDto(
            record.id(),
            record.employeeId(),
            record.fieldKey(),
            record.fieldLabel(),
            record.oldValue(),
            record.newValue(),
            record.requestedBy(),
            record.requestedAt(),
            record.status(),
            record.reviewedAt(),
            employee
        );
    }
}
