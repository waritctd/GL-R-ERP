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

class ProfileRequestServiceTest {
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ProfileRequestService service = new ProfileRequestService(profileRequests, employees, auditService);
    private final UserPrincipal reviewer = new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 10L, true, LocalDate.now(), false, null, false);

    @Test
    void rejectsAlreadyReviewedRequestBeforeChangingStatus() {
        ProfileRequestRecord existing = requestWithStatus("approved");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(101L, new UpdateProfileRequestRequest("rejected", null), reviewer))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(profileRequests, never()).updatePendingStatus(101L, "rejected", reviewer, null);
        verify(employees, never()).updateEmail(existing.employeeId(), existing.newValue());
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
