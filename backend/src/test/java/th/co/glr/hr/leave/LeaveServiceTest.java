package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;

class LeaveServiceTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final LeaveRepository leaveRepository = mock(LeaveRepository.class);
    private final LeaveAttachmentRepository leaveAttachments = mock(LeaveAttachmentRepository.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    private final LeaveService leaveService = new LeaveService(
        leaveRepository, leaveAttachments, fileStorage, auditService, notificationService, appProperties);

    @Test
    void submitAutoApprovesWhenQuotaAndAdvanceNoticeAreSatisfied() {
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("1.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null)))
            .thenReturn(55L);
        when(leaveRepository.findById(55L)).thenReturn(Optional.of(requestDto(55L, 10L, "APPROVED", request.startDate(), request.endDate())));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.id()).isEqualTo(55L);
        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null));
        assertThat(totalDays.getValue()).isEqualByComparingTo("2.00");
        verify(notificationService).notify(eq(10L), eq("LEAVE_AUTO_APPROVED"), any(String.class), any(String.class), eq("/leave"), eq(true));
        verify(notificationService).notify(eq(99L), eq("LEAVE_AUTO_APPROVED"), any(String.class), any(String.class), eq("/leave"), eq(true));
    }

    @Test
    void submissionAutoRejectsWhenQuotaIsInsufficient() {
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("5.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class)))
            .thenReturn(56L);
        when(leaveRepository.findById(56L)).thenReturn(Optional.of(requestDto(56L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate())));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class));
        verify(notificationService).notify(eq(10L), eq("LEAVE_AUTO_REJECTED"), any(String.class), any(String.class), eq("/leave"), eq(true));
    }

    @Test
    void submissionAutoRejectsWhenAdvanceNoticeIsTooShort() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "VACATION",
            nextWeekdayWithinNotice(),
            nextWeekdayWithinNotice(),
            "Urgent errand"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class)))
            .thenReturn(57L);
        when(leaveRepository.findById(57L)).thenReturn(Optional.of(requestDto(57L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate())));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("at least 7"));
    }

    @Test
    void submissionAutoRejectsSickLeaveWithoutCertificate() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "SICK",
            nextWeekday(),
            nextWeekday(),
            "Fever"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("SICK")).thenReturn(Optional.of(sickType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class)))
            .thenReturn(58L);
        when(leaveRepository.findById(58L)).thenReturn(Optional.of(requestDto(58L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate())));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("medical certificate"));
    }

    @Test
    void nonApproversCanOnlyListOwnOrDirectReportRequests() {
        when(leaveRepository.findEmployeeAccess(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveService.list(
                user("employee", 10L),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                11L,
                null))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void hrCanListAllRequests() {
        when(leaveRepository.findRequests(any(LeaveFilter.class))).thenReturn(List.of());

        leaveService.list(
            user("hr", 20L),
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-30"),
            null,
            "submitted"
        );

        verify(leaveRepository).findRequests(new LeaveFilter(
            null,
            null,
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-30"),
            LeaveStatus.SUBMITTED
        ));
    }

    @Test
    void hrCanApproveLeave() {
        LeaveRequestDto submitted = requestDto(77L, 10L, "SUBMITTED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"));
        LeaveRequestDto approved = requestDto(77L, 10L, "APPROVED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"));
        when(leaveRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(approved));
        when(leaveRepository.approve(77L, 20L, "ok")).thenReturn(1);
        UserPrincipal hr = user("hr", 20L);

        LeaveRequestDto result = leaveService.approve(77L, new ReviewLeaveRequest("ok"), hr);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(leaveRepository).approve(77L, 20L, "ok");
        verify(auditService).record(hr, "APPROVE_LEAVE_REQUEST", "leave_request", 77L, submitted, approved);
    }

    @Test
    void employeesCannotApproveTheirOwnLeave() {
        when(leaveRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"))));
        when(leaveRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new LeaveEmployeeAccess(10L, 99L, true)));

        assertThatThrownBy(() -> leaveService.approve(77L, new ReviewLeaveRequest(null), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void leaveRequestsCannotSpanQuotaYears() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "VACATION",
            LocalDate.parse("2026-12-31"),
            LocalDate.parse("2027-01-02"),
            "Year-end trip"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));

        assertThatThrownBy(() -> leaveService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private SubmitLeaveRequest validSubmit(Long employeeId) {
        LocalDate startDate = nextWeekdayAfterNotice();
        return new SubmitLeaveRequest(
            employeeId,
            "VACATION",
            startDate,
            startDate.plusDays(1),
            "Family trip"
        );
    }

    private LocalDate nextWeekdayAfterNotice() {
        LocalDate date = LocalDate.now(BUSINESS_ZONE).plusDays(8);
        while (date.getDayOfWeek().getValue() >= 6) {
            date = date.plusDays(1);
        }
        if (date.getDayOfWeek().getValue() == 5) {
            date = date.plusDays(3);
        }
        return date;
    }

    private LocalDate nextWeekdayWithinNotice() {
        LocalDate date = LocalDate.now(BUSINESS_ZONE).plusDays(1);
        while (date.getDayOfWeek().getValue() >= 6) {
            date = date.plusDays(1);
        }
        return date;
    }

    private LocalDate nextWeekday() {
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        while (date.getDayOfWeek().getValue() >= 6) {
            date = date.plusDays(1);
        }
        return date;
    }

    private LeaveTypeDto vacationType() {
        return new LeaveTypeDto("VACATION", "Vacation", "Vacation leave", new BigDecimal("6.00"), false);
    }

    private LeaveTypeDto sickType() {
        return new LeaveTypeDto("SICK", "Sick", "Sick leave", new BigDecimal("30.00"), true);
    }

    private LeaveRequestDto requestDto(long id, long employeeId, String status, LocalDate startDate, LocalDate endDate) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-14T10:00:00+07:00");
        return new LeaveRequestDto(
            id,
            employeeId,
            "EMP001",
            "Test Employee",
            "VACATION",
            "Vacation",
            "Vacation leave",
            startDate,
            endDate,
            new BigDecimal("2.00"),
            startDate.getYear(),
            "Family trip",
            null,
            null,
            status,
            new BigDecimal("5.00"),
            new BigDecimal("3.00"),
            null,
            employeeId,
            "Test Employee",
            timestamp,
            null,
            null,
            null,
            null,
            null,
            99L,
            "Test Manager",
            timestamp,
            timestamp
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }
}
