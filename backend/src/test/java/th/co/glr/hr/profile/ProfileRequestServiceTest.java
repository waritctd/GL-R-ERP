package th.co.glr.hr.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import th.co.glr.hr.employee.EmployeeDto;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;

class ProfileRequestServiceTest {
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ProfileRequestService service =
        new ProfileRequestService(profileRequests, employees, auditService, notificationService);
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
        // Notification coverage gap D: the requester hears about the decision -- the requester's
        // employee id is 22L (requestWithStatus).
        verify(notificationService).notify(
            eq(22L), eq("PROFILE_REQUEST_APPROVED"), anyString(), anyString(), eq("/profile"), eq(true));
        // BLOCKING 2: the reviewer (10L, this class's `reviewer` fixture) is the ACTOR, not the other
        // side of the decision -- must NOT be notified about their own approval.
        verify(notificationService, never()).notify(
            eq(10L), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void notifiesTheRequesterOnRejection() {
        ProfileRequestRecord existing = requestWithStatus("pending");
        ProfileRequestRecord reviewed = requestWithStatus("rejected");
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing), Optional.of(reviewed));
        when(profileRequests.updatePendingStatus(101L, "rejected", reviewer, "not enough evidence")).thenReturn(1);
        when(employees.findEmployeeSummaryById(existing.employeeId())).thenReturn(Optional.empty());

        service.update(101L, new UpdateProfileRequestRequest("rejected", "not enough evidence"), reviewer);

        // Wrong-way-round: applyApprovedRequest must never run for a rejection.
        verify(employees, never()).updateEmail(existing.employeeId(), existing.newValue());
        verify(notificationService).notify(
            eq(22L), eq("PROFILE_REQUEST_REJECTED"), anyString(), anyString(), eq("/profile"), eq(true));
        // BLOCKING 2: same self-notify fix as the approval path above.
        verify(notificationService, never()).notify(
            eq(10L), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * BLOCKING 2's own failure case: requester and reviewer are the SAME employee id. Exactly one
     * notification (the requester-facing one) must result, not two.
     *
     * <p>S-5 correction (review, second pass): this test calls {@link ProfileRequestService#update}
     * DIRECTLY, bypassing {@code ProfileRequestController#update}'s real role gate ({@code
     * sessions.requireAnyRole(user, "hr")}) -- it is defence-in-depth against the service method
     * itself, not proof this state is reachable through the controller. It is NOT reachable there:
     * {@code ProfileRequestController#create} gates on role EXACTLY {@code "employee"} and {@code
     * #update} gates on role EXACTLY {@code "hr"}, and {@code UserPrincipal#role()} is a single fixed
     * value for a session's lifetime -- see {@link ProfileRequestService#update}'s own Javadoc, which
     * an earlier draft of this comment incorrectly contradicted ("an HR employee can file their own
     * ... and then approve it themselves" is not something the controller path permits in one
     * sitting).
     */
    @Test
    void selfReviewByTheSameEmployeeProducesExactlyOneNotification() {
        ProfileRequestRecord existing = requestWithStatus("pending");
        ProfileRequestRecord reviewed = requestWithStatus("approved");
        UserPrincipal hrReviewerWhoIsAlsoTheRequester =
            new UserPrincipal(22L, "hr@glr.co.th", "HR", "hr", 22L, true, LocalDate.now(), false, null, false);
        when(profileRequests.findById(101L)).thenReturn(Optional.of(existing), Optional.of(reviewed));
        when(profileRequests.updatePendingStatus(101L, "approved", hrReviewerWhoIsAlsoTheRequester, null)).thenReturn(1);
        when(employees.findEmployeeSummaryById(existing.employeeId())).thenReturn(Optional.empty());

        service.update(101L, new UpdateProfileRequestRequest("approved", null), hrReviewerWhoIsAlsoTheRequester);

        verify(notificationService, org.mockito.Mockito.times(1)).notify(
            eq(22L), eq("PROFILE_REQUEST_APPROVED"), anyString(), anyString(), eq("/profile"), eq(true));
        org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
    }

    @Test
    void submittingARequestNotifiesTheRequesterAndHr() {
        UserPrincipal employee = new UserPrincipal(8L, "employee@glr.co.th", "Employee", "employee", 22L, true, LocalDate.now(), false, null, false);
        CreateProfileRequestRequest request = new CreateProfileRequestRequest("email", "อีเมล", "old@glr.co.th", "new@glr.co.th");
        when(profileRequests.create(22L, request, employee)).thenReturn(101L);
        when(profileRequests.findById(101L)).thenReturn(Optional.of(requestWithStatus("pending")));
        when(employees.findEmployeeSummaryById(22L)).thenReturn(Optional.empty());
        // D2: HR is now resolved via EmployeeRepository#findHrEmployeeIds and notified on submit.
        when(employees.findHrEmployeeIds()).thenReturn(java.util.List.of(30L, 31L));

        ProfileRequestDto result = service.create(request, employee);

        assertThat(result.id()).isEqualTo(101L);
        verify(notificationService).notify(
            eq(22L), eq("PROFILE_REQUEST_SUBMITTED"), anyString(), anyString(), eq("/profile"), eq(true));
        // S4: the HR-facing link is the review queue (/requests), not /profile (the viewer's OWN
        // requests -- see App.jsx's routing).
        verify(notificationService).notify(
            eq(30L), eq("PROFILE_REQUEST_PENDING_HR"), anyString(), anyString(), eq("/requests"), eq(true));
        verify(notificationService).notify(
            eq(31L), eq("PROFILE_REQUEST_PENDING_HR"), anyString(), anyString(), eq("/requests"), eq(true));
    }

    /**
     * D2 self-notify guard: an HR employee filing their OWN request must not ALSO get the
     * reviewer-facing "pending HR review" notification about it -- same "nobody is notified about
     * their own action" principle as {@link #selfReviewByTheSameEmployeeProducesExactlyOneNotification}.
     *
     * <p>This state -- filing under role {@code "hr"} and simultaneously appearing in {@code
     * findHrEmployeeIds()} -- IS reachable in production, unlike {@code
     * #selfReviewByTheSameEmployeeProducesExactlyOneNotification}'s update-side scenario: {@link
     * th.co.glr.hr.profile.ProfileRequestController#create} gates on role EXACTLY {@code
     * "employee"}, but see {@link ProfileRequestService#hrRecipientsExcludingRequester}'s Javadoc --
     * {@code findHrEmployeeIds()} is resolved LIVE from the employee's current division, independent
     * of the session's own role snapshot, so an employee whose division has since become HR (without
     * a fresh login) can file this exact request while their session still reads {@code "employee"}.
     *
     * <p>S-1 (review, second pass): actor id raised from 30L to 10030L -- a realistic 4-5 digit id,
     * OUTSIDE Java's {@code Long} cache (-128..127). {@code
     * ProfileRequestService#hrRecipientsExcludingRequester}'s filter compares a boxed {@code Long}
     * (from {@code findHrEmployeeIds()}) against {@code request.employeeId()}, a PRIMITIVE {@code
     * long} field -- already safe today (the comparison auto-unboxes), but 30L could not have caught
     * a regression that first boxed the primitive side too (e.g. a refactor to a boxed {@code Long}
     * parameter), since two cached same-value Longs are always {@code ==} equal regardless.
     */
    @Test
    void hrEmployeeFilingTheirOwnRequestIsExcludedFromTheHrBroadcast() {
        UserPrincipal hrEmployee = new UserPrincipal(10030L, "hr@glr.co.th", "HR", "hr", 10030L, true, LocalDate.now(), false, null, false);
        CreateProfileRequestRequest request = new CreateProfileRequestRequest("email", "อีเมล", "old@glr.co.th", "new@glr.co.th");
        when(profileRequests.create(10030L, request, hrEmployee)).thenReturn(102L);
        when(profileRequests.findById(102L)).thenReturn(Optional.of(new ProfileRequestRecord(
            102L, 10030L, "email", "อีเมล", "old@glr.co.th", "new@glr.co.th", "HR", LocalDate.now(), "pending", null)));
        when(employees.findEmployeeSummaryById(10030L)).thenReturn(Optional.empty());
        when(employees.findHrEmployeeIds()).thenReturn(java.util.List.of(10030L, 31L));

        service.create(request, hrEmployee);

        verify(notificationService).notify(
            eq(10030L), eq("PROFILE_REQUEST_SUBMITTED"), anyString(), anyString(), eq("/profile"), eq(true));
        verify(notificationService).notify(
            eq(31L), eq("PROFILE_REQUEST_PENDING_HR"), anyString(), anyString(), eq("/requests"), eq(true));
        verify(notificationService, never()).notify(
            eq(10030L), eq("PROFILE_REQUEST_PENDING_HR"), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** S5: a {@code null} oldValue (never-set address/emergency contact) must not render as "null". */
    @Test
    void submittingARequestWithNoPriorValueRendersAPlaceholderNotTheLiteralNull() {
        UserPrincipal employee = new UserPrincipal(8L, "employee@glr.co.th", "Employee", "employee", 22L, true, LocalDate.now(), false, null, false);
        CreateProfileRequestRequest request = new CreateProfileRequestRequest("address", "ที่อยู่", null, "123 Main St");
        when(profileRequests.create(22L, request, employee)).thenReturn(103L);
        when(profileRequests.findById(103L)).thenReturn(Optional.of(new ProfileRequestRecord(
            103L, 22L, "address", "ที่อยู่", null, "123 Main St", "Employee", LocalDate.now(), "pending", null)));
        when(employees.findEmployeeSummaryById(22L)).thenReturn(Optional.empty());
        when(employees.findHrEmployeeIds()).thenReturn(java.util.List.of());

        service.create(request, employee);

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(
            eq(22L), eq("PROFILE_REQUEST_SUBMITTED"), anyString(), body.capture(), eq("/profile"), eq(true));
        assertThat(body.getValue()).doesNotContain("\"null\"");
    }

    /**
     * Nit (review, second pass): every OTHER test in this class stubs {@code
     * findEmployeeSummaryById} to {@code Optional.empty()}, so {@link
     * ProfileRequestService#notifySubmitted}'s real-name branch ({@code
     * request.employee().nameTh()}) is never exercised -- only the {@code "พนักงาน"} fallback that
     * fires when the lookup misses. Stubs a resolvable {@link EmployeeDto} instead, and asserts the
     * HR-facing message actually carries the requester's real name.
     */
    @Test
    void submittingARequestWithAResolvableEmployeeUsesTheirRealNameInTheHrMessage() {
        UserPrincipal employee = new UserPrincipal(8L, "employee@glr.co.th", "Employee", "employee", 22L, true, LocalDate.now(), false, null, false);
        CreateProfileRequestRequest request = new CreateProfileRequestRequest("email", "อีเมล", "old@glr.co.th", "new@glr.co.th");
        when(profileRequests.create(22L, request, employee)).thenReturn(104L);
        when(profileRequests.findById(104L)).thenReturn(Optional.of(new ProfileRequestRecord(
            104L, 22L, "email", "อีเมล", "old@glr.co.th", "new@glr.co.th", "Employee", LocalDate.now(), "pending", null)));
        when(employees.findEmployeeSummaryById(22L)).thenReturn(Optional.of(minimalEmployeeDto(22L, "สมชาย ใจดี")));
        when(employees.findHrEmployeeIds()).thenReturn(java.util.List.of(30L));

        service.create(request, employee);

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(
            eq(30L), eq("PROFILE_REQUEST_PENDING_HR"), anyString(), body.capture(), eq("/requests"), eq(true));
        assertThat(body.getValue()).contains("สมชาย ใจดี").doesNotContain("พนักงาน");
    }

    /** Minimal, mostly-null {@link EmployeeDto} -- only {@code id}/{@code nameTh} matter to the caller. */
    private EmployeeDto minimalEmployeeDto(long id, String nameTh) {
        return new EmployeeDto(
            id,               // id
            null,             // code
            null,             // badge
            nameTh,           // nameTh
            null,             // nameEn
            null,             // nickName
            null,             // initials
            null,             // avatarBg
            null,             // avatarFg
            null,             // titleTh
            null,             // genderTh
            null,             // birthDate
            null,             // age
            null,             // nationality
            null,             // maritalStatus
            null,             // email
            null,             // phone
            null,             // divisionId
            null,             // divisionTh
            null,             // divisionEn
            null,             // departmentTh
            null,             // positionTh
            null,             // positionEn
            null,             // level
            null,             // locationTh
            null,             // statusId
            null,             // statusTh
            null,             // statusTone
            true,             // active
            null,             // payType
            null,             // salary
            null,             // directorRemuneration
            null,             // withholdingTaxOverride
            null,             // hireDate
            null,             // confirmationDate
            null,             // reportsTo
            null,             // bank
            null,             // bankAccount
            null,             // currentAddress
            null,             // emergencyContact
            java.util.List.of(), // assignments
            java.util.List.of(), // salaryHistory
            null,             // sensitive
            0                 // pendingRequestCount
        );
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
