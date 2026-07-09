package th.co.glr.hr.profile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;

class ProfileRequestServiceTest {
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ProfileRequestService service = new ProfileRequestService(
        profileRequests, employees, auditService, notificationService);
    private final UserPrincipal reviewer = new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 10L, true, LocalDate.now(), false, null, false);

    @Test
    void createNotifiesHrRole() {
        UserPrincipal employee = new UserPrincipal(8L, "employee@glr.co.th", "Employee", "employee", 22L, true, LocalDate.now(), false, null, false);
        ProfileRequestRecord created = requestWithStatus("pending");
        when(profileRequests.create(22L, new CreateProfileRequestRequest("email", "อีเมล", "old@glr.co.th", "new@glr.co.th"), employee))
            .thenReturn(101L);
        when(profileRequests.findById(101L)).thenReturn(Optional.of(created));
        when(employees.findEmployeeSummaryById(created.employeeId())).thenReturn(Optional.empty());

        service.create(new CreateProfileRequestRequest("email", "อีเมล", "old@glr.co.th", "new@glr.co.th"), employee);

        verify(notificationService).notifyByRole(
            "hr",
            "PROFILE_REQUEST_SUBMITTED",
            "มีคำขอแก้ไขข้อมูลพนักงาน",
            "พนักงาน Employee ยื่นคำขอแก้ไขข้อมูล (อีเมล) รอการตรวจสอบ",
            "/requests",
            true);
    }

    @Test
    void rejectsAlreadyReviewedRequestBeforeChangingStatus() {
        ProfileRequestRecord existing = requestWithStatus("approved");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(101L, new UpdateProfileRequestRequest("rejected", null), reviewer))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(profileRequests, never()).updatePendingStatus(101L, "rejected", reviewer, null);
        verify(employees, never()).updateEmail(existing.employeeId(), existing.newValue());
        verify(notificationService, never()).notify(
            existing.employeeId(),
            "PROFILE_REQUEST_REJECTED",
            "คำขอแก้ไขข้อมูลไม่ได้รับการอนุมัติ",
            "คำขอแก้ไขข้อมูล (อีเมล) ของคุณไม่ได้รับการอนุมัติ: กรุณาติดต่อ HR",
            "/my-requests",
            true);
    }

    @Test
    void rejectsStalePendingReviewWhenConditionalUpdateMisses() {
        ProfileRequestRecord existing = requestWithStatus("pending");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing));
        when(profileRequests.updatePendingStatus(101L, "approved", reviewer, null)).thenReturn(0);

        assertThatThrownBy(() -> service.update(101L, new UpdateProfileRequestRequest("approved", null), reviewer))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(employees, never()).updateEmail(existing.employeeId(), existing.newValue());
        verify(notificationService, never()).notify(
            existing.employeeId(),
            "PROFILE_REQUEST_APPROVED",
            "คำขอแก้ไขข้อมูลได้รับการอนุมัติ",
            "คำขอแก้ไขข้อมูล (อีเมล) ของคุณได้รับการอนุมัติแล้ว",
            "/my-requests",
            true);
    }

    @Test
    void appliesApprovedPendingRequestAfterConditionalUpdate() {
        ProfileRequestRecord existing = requestWithStatus("pending");
        ProfileRequestRecord reviewed = requestWithStatus("approved");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing), Optional.of(reviewed));
        when(profileRequests.updatePendingStatus(101L, "approved", reviewer, null)).thenReturn(1);
        when(employees.findEmployeeSummaryById(existing.employeeId())).thenReturn(Optional.empty());

        service.update(101L, new UpdateProfileRequestRequest("approved", null), reviewer);

        verify(employees).updateEmail(existing.employeeId(), existing.newValue());
        verify(auditService).record(reviewer, "APPROVE_PROFILE_REQUEST", "profile_request", 101L, existing, reviewed);
        verify(notificationService).notify(
            existing.employeeId(),
            "PROFILE_REQUEST_APPROVED",
            "คำขอแก้ไขข้อมูลได้รับการอนุมัติ",
            "คำขอแก้ไขข้อมูล (อีเมล) ของคุณได้รับการอนุมัติแล้ว",
            "/my-requests",
            true);
    }

    @Test
    void notifiesEmployeeWithReasonWhenRejected() {
        ProfileRequestRecord existing = requestWithStatus("pending");
        ProfileRequestRecord reviewed = requestWithStatus("rejected");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing), Optional.of(reviewed));
        when(profileRequests.updatePendingStatus(101L, "rejected", reviewer, "Need clearer evidence")).thenReturn(1);
        when(employees.findEmployeeSummaryById(existing.employeeId())).thenReturn(Optional.empty());

        service.update(101L, new UpdateProfileRequestRequest("rejected", "Need clearer evidence"), reviewer);

        verify(auditService).record(reviewer, "REJECT_PROFILE_REQUEST", "profile_request", 101L, existing, reviewed);
        verify(notificationService).notify(
            existing.employeeId(),
            "PROFILE_REQUEST_REJECTED",
            "คำขอแก้ไขข้อมูลไม่ได้รับการอนุมัติ",
            "คำขอแก้ไขข้อมูล (อีเมล) ของคุณไม่ได้รับการอนุมัติ: Need clearer evidence",
            "/my-requests",
            true);
    }

    @Test
    void rejectsUnsupportedProfileFieldOnCreate() {
        UserPrincipal employee = new UserPrincipal(8L, "employee@glr.co.th", "Employee", "employee", 22L, true, LocalDate.now(), false, null, false);

        assertThatThrownBy(() -> service.create(new CreateProfileRequestRequest("salary", "เงินเดือน", "1", "2"), employee))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(profileRequests, never()).create(22L, new CreateProfileRequestRequest("salary", "เงินเดือน", "1", "2"), employee);
    }

    private ProfileRequestRecord requestWithStatus(String status) {
        return new ProfileRequestRecord(
            101L,
            22L,
            "email",
            "อีเมล",
            "old@glr.co.th",
            "new@glr.co.th",
            "Employee",
            LocalDate.now(),
            status,
            null
        );
    }
}
