package th.co.glr.hr.profile;

import java.util.List;
import java.util.Map;
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
    private final ProfileRequestRepository profileRequests;
    private final EmployeeRepository employees;
    private final AuditService audit;

    public ProfileRequestService(ProfileRequestRepository profileRequests, EmployeeRepository employees, AuditService audit) {
        this.profileRequests = profileRequests;
        this.employees = employees;
        this.audit = audit;
    }

    public List<ProfileRequestDto> list(UserPrincipal user) {
        List<ProfileRequestRecord> records = user.role().equals("hr") || user.role().equals("admin")
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
        profileRequests.updateStatus(id, request.status(), reviewer.id(), request.reviewerNote());
        if ("approved".equals(request.status()) && "pending".equals(existing.status())) {
            applyApprovedRequest(existing);
        }
        ProfileRequestRecord updated = profileRequests.findById(id).orElseThrow();
        String action = "approved".equals(request.status()) ? "profile_request.approve" : "profile_request.reject";
        audit.record(reviewer.id(), action, "profile_request", id, existing, updated);
        return toDto(updated);
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
