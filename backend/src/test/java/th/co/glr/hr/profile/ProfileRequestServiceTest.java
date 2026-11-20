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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;

class ProfileRequestServiceTest {
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    // update()'s personal-delivery notification now goes through NotificationService#notify rather than
    // notifications.notifyEmployeeOfProfileRequest — see ProfileRequestService's constructor Javadoc.
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ProfileRequestService service =
        new ProfileRequestService(profileRequests, employees, auditService, notifications, notificationService);
    private final UserPrincipal reviewer = new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 10L, true, LocalDate.now(), false, null, false);

    @Test
    void rejectsStalePendingReviewWhenConditionalUpdateMisses() {
        ProfileRequestRecord existing = requestWithStatus("pending");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing));
        when(profileRequests.updatePendingStatus(101L, "approved", reviewer, null)).thenReturn(0);

        assertThatThrownBy(() -> service.update(101L, new UpdateProfileRequestRequest("approved", null), reviewer))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(employees, never()).updateEmail(existing.employeeId(), existing.newValue());
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
