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
import th.co.glr.hr.notification.NotificationService;

@Service
public class ProfileRequestService {
    private static final Set<String> SUPPORTED_FIELDS = Set.of("phone", "email", "address", "emergency");

    // Byte-identical to the PROFILE_REQUEST_APPROVED/PROFILE_REQUEST_REJECTED entries that used to
    // live in NotificationRepository#TICKET_EVENT_TITLES. That map is read only by the sales-style
    // notifyEmployeeAt/notifyByRoleInternal transport (NotificationRepository#ticketEventTitle) --
    // now that update() below calls NotificationService#notify directly, which takes its subject as
    // an explicit parameter rather than resolving one from a type code, the title has to live here
    // instead. See this class's constructor Javadoc for why the transport changed.
    private static final String APPROVED_TITLE = "อนุมัติคำขอแก้ไขข้อมูลของคุณแล้ว";
    private static final String REJECTED_TITLE = "คำขอแก้ไขข้อมูลของคุณไม่ได้รับอนุมัติ";

    private final ProfileRequestRepository profileRequests;
    private final EmployeeRepository employees;
    private final AuditService auditService;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;

    /**
     * {@code notifications} ({@link NotificationRepository}) is still used directly for {@link
     * #create}'s HR fan-out ({@code notifyHrOfProfileRequest} -&gt; {@code notifyByRoleInternal}) --
     * that path is unaffected by this constructor's new argument and stays exactly as #860 shipped
     * it.
     *
     * <p>{@code notificationService} is new (regression fix, 2026-08-31): {@link #update} used to
     * notify the requesting employee through {@code notifications.notifyEmployeeOfProfileRequest},
     * which -- like every other {@code NotificationRepository#notifyEmployee*} method -- ultimately
     * routes through {@code SalesNotificationMailRouter#routeResolved}. That router deliberately
     * redirects anyone in an {@code AC}- or {@code PCIM}-coded division to a SHARED departmental
     * mailbox ({@code account@glr.co.th} / {@code import@glr.co.th}) instead of their own address --
     * correct for a sales/deal notification, but wrong for a personal HR one: verified against
     * production, employee 72 (ฝ่ายบัญชี, {@code source_code = 'AC'}) would have her "your email was
     * updated" notice land in the shared accounting inbox instead of reaching her, and the message
     * itself names her new personal email address. {@link NotificationService#notify} is the plain
     * per-employee transport every other HR domain (leave, overtime, welfare, attendance-correction)
     * already uses -- it resolves the employee's own address with no shared-box branch, so it is the
     * right transport for a request that is fundamentally about one person's own data.
     */
    public ProfileRequestService(ProfileRequestRepository profileRequests, EmployeeRepository employees,
                                 AuditService auditService, NotificationRepository notifications,
                                 NotificationService notificationService) {
        this.profileRequests = profileRequests;
        this.employees = employees;
        this.auditService = auditService;
        this.notifications = notifications;
        this.notificationService = notificationService;
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

        // Tell the requesting employee what happened to their own request, addressed to THEM --
        // never to a shared departmental mailbox. notificationService.notify(...) is the plain
        // per-employee transport (see this class's constructor Javadoc for why it replaced
        // notifications.notifyEmployeeOfProfileRequest here). The link stays "/profile" and the
        // type codes stay PROFILE_REQUEST_APPROVED/PROFILE_REQUEST_REJECTED, unchanged from #860 --
        // rows with those values already exist in production and the frontend renders them.
        notificationService.notify(reviewed.employeeId(),
            approved ? "PROFILE_REQUEST_APPROVED" : "PROFILE_REQUEST_REJECTED",
            approved ? APPROVED_TITLE : REJECTED_TITLE,
            approved ? approvedMessage(reviewed) : rejectedMessage(reviewed, request.reviewerNote()),
            "/profile",
            true);

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
