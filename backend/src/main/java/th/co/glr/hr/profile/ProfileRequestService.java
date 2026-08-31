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
import th.co.glr.hr.notification.NotificationRepository;

@Service
public class ProfileRequestService {
    private static final Set<String> SUPPORTED_FIELDS = Set.of("phone", "email", "address", "emergency");

    private final ProfileRequestRepository profileRequests;
    private final EmployeeRepository employees;
    private final AuditService auditService;
    private final NotificationRepository notifications;

    public ProfileRequestService(ProfileRequestRepository profileRequests, EmployeeRepository employees,
                                 AuditService auditService, NotificationRepository notifications) {
        this.profileRequests = profileRequests;
        this.employees = employees;
        this.auditService = auditService;
        this.notifications = notifications;
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        if (!SUPPORTED_FIELDS.contains(request.fieldKey())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับฟิลด์ข้อมูลส่วนตัวนี้");
        }
        long id = profileRequests.create(user.employeeId(), request, user);
        ProfileRequestRecord created = profileRequests.findById(id).orElseThrow();
        // Fetched once and threaded into both the notification copy and the returned DTO below,
        // rather than letting toDto(record) re-query EmployeeRepository a second time for the
        // same employee.
        EmployeeDto employee = employees.findEmployeeSummaryById(created.employeeId()).orElse(null);
        notifications.notifyHrOfProfileRequest("PROFILE_REQUEST_SUBMITTED", submittedMessage(employee, created));
        ProfileRequestDto dto = toDto(created, employee);
        // The review side (APPROVE_/REJECT_PROFILE_REQUEST) was already audited; the submission was
        // not, so the trail recorded who decided a request but never who raised it. Placed after
        // main's notification block rather than replacing it: both sides of this merge are wanted,
        // and reusing `employee` keeps that side's single-fetch optimisation intact.
        auditService.record(user, "SUBMIT_PROFILE_REQUEST", "profile_request", id, null, dto);
        return dto;
    }

    @Transactional
    public ProfileRequestDto update(long id, UpdateProfileRequestRequest request, UserPrincipal reviewer) {
        if (request.status() == null || request.status().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุสถานะ");
        }
        ProfileRequestRecord existing = profileRequests.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอแก้ไขข้อมูลส่วนตัวนี้"));
        if (!"pending".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอแก้ไขข้อมูลส่วนตัวนี้ได้รับการพิจารณาไปแล้ว");
        }

        int updated = profileRequests.updatePendingStatus(id, request.status(), reviewer, request.reviewerNote());
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอแก้ไขข้อมูลส่วนตัวนี้ได้รับการพิจารณาไปแล้ว");
        }
        if ("approved".equals(request.status()) && "pending".equals(existing.status())) {
            applyApprovedRequest(existing);
        }
        ProfileRequestRecord reviewed = profileRequests.findById(id).orElseThrow();
        boolean approved = "approved".equals(request.status());
        String action = approved ? "APPROVE_PROFILE_REQUEST" : "REJECT_PROFILE_REQUEST";
        auditService.record(reviewer, action, "profile_request", id, existing, reviewed);

        // Tell the requesting employee what happened to their own request -- ProfileRequestService
        // emitted zero notifications before this change, so this and the notifyHrOfProfileRequest
        // call in create() above are both new.
        notifications.notifyEmployeeOfProfileRequest(reviewed.employeeId(),
            approved ? "PROFILE_REQUEST_APPROVED" : "PROFILE_REQUEST_REJECTED",
            approved ? approvedMessage(reviewed) : rejectedMessage(reviewed, request.reviewerNote()));

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
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับฟิลด์ข้อมูลส่วนตัวนี้");
        }
    }

    // --- notification copy (2026-08-31) --------------------------------------------------------
    //
    // Never interpolate fieldKey (a machine code like "email"/"emergency") into any of these three
    // strings -- always fieldLabel(), which is already Thai ("อีเมล", "เบอร์โทรศัพท์",
    // "ที่อยู่ปัจจุบัน", "ผู้ติดต่อฉุกเฉิน"). A previous round shipped a raw machine code
    // (TRAVEL_PER_DIEM) into a subject line at real people -- see
    // NotificationRepository#notifyEmployeeAt's comment for the same lesson, and
    // ProfileRequestNotificationIntegrationTest#everyNotificationMessageCarriesTheThaiLabelNeverTheRawFieldKey
    // for the regression guard.

    /** To HR, when an employee files a new request. */
    private String submittedMessage(EmployeeDto employee, ProfileRequestRecord record) {
        // employee is only ever null if the employee row backing a just-created request vanished
        // between the insert and this read -- the FK on hr.profile_change_request.employee_id
        // makes that a can't-happen in practice. Fall back to the name snapshotted on the request
        // itself rather than risk an NPE over an edge case this defensively.
        String who = employee == null
            ? record.requestedBy()
            : titleAndName(employee) + " (" + employee.code() + ")";
        String fromClause = isBlank(record.oldValue()) ? "" : " จาก " + record.oldValue();
        return who + " ขอแก้ไข" + record.fieldLabel() + fromClause + " เป็น " + record.newValue();
    }

    /** To the employee, when HR approves their request. */
    private String approvedMessage(ProfileRequestRecord record) {
        return "อัปเดต" + record.fieldLabel() + "ในทะเบียนพนักงานของคุณเป็น " + record.newValue() + " เรียบร้อยแล้ว";
    }

    /** To the employee, when HR rejects their request. */
    private String rejectedMessage(ProfileRequestRecord record, String reviewerNote) {
        String reasonClause = isBlank(reviewerNote) ? "" : " เหตุผล: " + reviewerNote;
        return "คำขอแก้ไข" + record.fieldLabel() + "ของคุณไม่ได้รับอนุมัติ" + reasonClause;
    }

    /**
     * A Thai title glues directly onto the first name with no space in between ("นาย" +
     * "ภาคภูมิ" -&gt; "นายภาคภูมิ"); {@code nameTh()} already carries exactly one space between
     * first and last name ({@code EmployeeRepository#fullName}). {@code titleTh()} is nullable on
     * some imported rows, so a missing title degrades to the bare name rather than a leading space.
     */
    private static String titleAndName(EmployeeDto employee) {
        String title = employee.titleTh();
        return (title == null ? "" : title) + employee.nameTh();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
