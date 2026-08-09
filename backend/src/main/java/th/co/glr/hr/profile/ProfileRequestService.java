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
import th.co.glr.hr.notification.NotificationService;

@Service
public class ProfileRequestService {
    private static final Set<String> SUPPORTED_FIELDS = Set.of("phone", "email", "address", "emergency");

    private final ProfileRequestRepository profileRequests;
    private final EmployeeRepository employees;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public ProfileRequestService(ProfileRequestRepository profileRequests, EmployeeRepository employees,
                                 AuditService auditService, NotificationService notificationService) {
        this.profileRequests = profileRequests;
        this.employees = employees;
        this.auditService = auditService;
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
        ProfileRequestDto created = profileRequests.findById(id).map(this::toDto).orElseThrow();
        notifySubmitted(created);
        return created;
    }

    /**
     * D2 (owner ruling): every active HR employee, resolved by {@link
     * EmployeeRepository#findHrEmployeeIds()} -- see that method's Javadoc for why this is now
     * feasible (division {@code source_code}, the same shape {@code
     * th.co.glr.hr.notification.CeoApproverRepository#findEmployeeIds} uses for the CEO role) and
     * confirmed as this company's real HR division code, not guessed.
     *
     * <p>Skips the requester when they themselves resolve to an HR employee id. {@code
     * ProfileRequestController#create} gates on role EXACTLY {@code "employee"} ({@code
     * sessions.requireAnyRole(user, "employee")}) -- S-5 correction: an earlier draft of this
     * Javadoc claimed {@link #create} "has no role gate", which is false; that gate is real, it is
     * just enforced in the controller, not here. Since {@code UserPrincipal#role()} is a single
     * value, an HR employee filing their own request is filing under role {@code "employee"} at that
     * moment, not {@code "hr"} -- so this filter is not defending against a self-review hole (see
     * {@link #update}'s Javadoc: there isn't one). It exists because {@code
     * findHrEmployeeIds()} resolves role from the employee's CURRENT division, independent of what
     * role gated THIS request: an employee who is HR now, but filed this exact request back when
     * they were not, would otherwise get both the requester-facing "submitted" notification AND a
     * reviewer-facing "pending HR review" notification about their own past action -- the same
     * "nobody is notified about their own action" principle every other cancel/reviewed notification
     * in this codebase already follows.
     */
    private List<Long> hrRecipientsExcludingRequester(ProfileRequestDto request) {
        return employees.findHrEmployeeIds().stream()
            .filter(hrEmployeeId -> hrEmployeeId != request.employeeId())
            .toList();
    }

    /**
     * The role gate lives in {@code ProfileRequestController#update} ({@code
     * sessions.requireAnyRole(user, "hr")}), not here, and has no self-exclusion -- but S-5
     * correction: this does NOT mean an HR employee can file their own profile-change request and
     * then approve it in one sitting. {@code ProfileRequestController#create} gates on role EXACTLY
     * {@code "employee"} and {@code #update} gates on role EXACTLY {@code "hr"}; {@code
     * UserPrincipal#role()} is a single fixed value for the lifetime of a session (set once at
     * login, see {@code AuthService#toPrincipal}), so the SAME session can never satisfy both gates
     * -- self-review by one actor in one sitting is not reachable. (An earlier draft of this Javadoc
     * claimed it was "possible"; it is not, and the tests below that construct that state do so only
     * by calling this service directly, bypassing both controller gates -- see their own Javadoc.)
     *
     * <p>{@link #notifyReviewed} still does not double-notify a requester/reviewer who happen to be
     * the same employee id (BLOCKING 2), and {@link #hrRecipientsExcludingRequester} still skips
     * them on submit -- not because self-review in one sitting is reachable, but because {@code
     * findHrEmployeeIds()} is resolved live and can diverge from a session's role SNAPSHOT (see that
     * method's own Javadoc): an employee who is HR now, reviewing a request they themselves filed
     * back when their session (and role) was still {@code "employee"}, is a genuinely reachable
     * same-employee case, just not a same-sitting privilege bypass.
     */
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
        String action = "approved".equals(request.status())
            ? "APPROVE_PROFILE_REQUEST"
            : "REJECT_PROFILE_REQUEST";
        auditService.record(reviewer, action, "profile_request", id, existing, reviewed);
        ProfileRequestDto reviewedDto = toDto(reviewed);
        notifyReviewed(reviewedDto);
        return reviewedDto;
    }

    /**
     * Notification coverage gap D: profile-change requests notified nobody at all. Notifies the
     * REQUESTER, plus -- D2 (owner ruling, built on this branch) -- every HR employee, resolved by
     * {@link #hrRecipientsExcludingRequester}. HR is the sole reviewer ({@code
     * ProfileRequestController#update} gates on {@code sessions.requireAnyRole(user, "hr")}); the
     * HR-facing notification links to {@code /requests} (the review queue -- {@code
     * ProfileRequestsPage}), NOT {@code /profile} (which renders the viewer's OWN requests -- S4, a
     * pre-existing bug in this same gap: an HR reviewer following the old link landed on their
     * personal request history, not the queue). HR still ALSO sees every pending request via {@link
     * #list}'s hr branch regardless -- this notification is a heads-up, not the only way to find it.
     *
     * <p>S5: {@code oldValue} can be {@code null} (a field -- address, emergency contact -- that was
     * never set before) and rendering it verbatim produced a literal {@code จาก "null"}; routed
     * through {@link #displayValue} instead.
     */
    private void notifySubmitted(ProfileRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "PROFILE_REQUEST_SUBMITTED",
            "ส่งคำขอแก้ไขข้อมูลส่วนตัวแล้ว",
            "คำขอแก้ไข " + request.fieldLabel() + " จาก \"" + displayValue(request.oldValue()) + "\" เป็น \""
                + displayValue(request.newValue()) + "\" ถูกส่งให้ฝ่ายบุคคลพิจารณาแล้ว"
                + "\nอยู่ระหว่างรอฝ่ายบุคคลพิจารณา ระบบจะแจ้งผลให้ทราบ",
            "/profile",
            true
        );
        String requesterName = request.employee() == null ? "พนักงาน" : request.employee().nameTh();
        for (Long hrEmployeeId : hrRecipientsExcludingRequester(request)) {
            notificationService.notify(
                hrEmployeeId,
                "PROFILE_REQUEST_PENDING_HR",
                "มีคำขอแก้ไขข้อมูลส่วนตัวรอพิจารณา",
                requesterName + " ขอแก้ไข " + request.fieldLabel() + " เป็น \"" + displayValue(request.newValue()) + "\""
                    + "\nกรุณาพิจารณาอนุมัติหรือปฏิเสธในระบบ",
                "/requests",
                true
            );
        }
    }

    /**
     * Requester-side notification on the decision only.
     *
     * <p>Review finding (BLOCKING 2): this used to ALSO notify {@code reviewer.employeeId()} -- the
     * person who just clicked approve/reject, i.e. the ACTOR, not "the other side". No other
     * notification in this codebase tells an actor about their own action (see {@code
     * OvertimeService#notifyRejected}'s Javadoc, and the cancel-notification fixes elsewhere in this
     * branch); this was the one place that did. Dropped the reviewer-notify call entirely -- when
     * requester and reviewer are the same employee id (see {@link #update}'s Javadoc for when that is
     * and is not reachable -- S-5 correction: it is NOT a same-sitting self-review, only the live
     * {@code findHrEmployeeIds()}-vs-session-role-snapshot divergence described there), exactly one
     * notification (this one) now results, not two.
     */
    private void notifyReviewed(ProfileRequestDto request) {
        boolean approved = "approved".equals(request.status());
        String employeeMessage = approved
            ? "คำขอแก้ไข " + request.fieldLabel() + " เป็น \"" + displayValue(request.newValue()) + "\" ได้รับการอนุมัติแล้ว"
            : "คำขอแก้ไข " + request.fieldLabel() + " เป็น \"" + displayValue(request.newValue()) + "\" ถูกปฏิเสธ";
        notificationService.notify(
            request.employeeId(),
            approved ? "PROFILE_REQUEST_APPROVED" : "PROFILE_REQUEST_REJECTED",
            approved ? "คำขอแก้ไขข้อมูลส่วนตัวได้รับการอนุมัติ" : "คำขอแก้ไขข้อมูลส่วนตัวถูกปฏิเสธ",
            employeeMessage,
            "/profile",
            true
        );
    }

    /** S5: {@code null}/blank renders as an explicit Thai placeholder, never the literal "null". */
    private String displayValue(String value) {
        return value == null || value.isBlank() ? "(ไม่มีข้อมูล)" : value;
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
