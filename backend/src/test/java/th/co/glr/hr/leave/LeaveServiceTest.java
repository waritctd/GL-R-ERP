package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    // Wednesday 2026-07-01 09:00 Asia/Bangkok — all leave dates below are fixed relative to this.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private final LeaveRepository leaveRepository = mock(LeaveRepository.class);
    private final LeaveAttachmentRepository leaveAttachments = mock(LeaveAttachmentRepository.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AppProperties appProperties = new AppProperties();
    private final LeaveService leaveService = new LeaveService(
        leaveRepository, leaveAttachments, fileStorage, auditService, notificationService, appProperties,
        Clock.fixed(FIXED_NOW, BUSINESS_ZONE));

    @Test
    void submitAutoApprovesWhenQuotaAndAdvanceNoticeAreSatisfied() {
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("1.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null)))
            .thenReturn(55L);
        when(leaveRepository.findById(55L)).thenReturn(Optional.of(
            requestDto(55L, 10L, "APPROVED", request.startDate(), request.endDate(), "2.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.id()).isEqualTo(55L);
        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null));
        assertThat(totalDays.getValue()).isEqualByComparingTo("2.00");
        // Fully within quota (1 used, 6 quota -> 5 remaining, 2 requested): entirely paid, nothing
        // unpaid, matching the pre-redesign behaviour for a request quota fully covers.
        assertThat(paidDays.getValue()).isEqualByComparingTo("2.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("0.00");
        verify(notificationService).notify(eq(10L), eq("LEAVE_AUTO_APPROVED"), any(String.class), any(String.class), eq("/leave"), eq(true));
        verify(notificationService).notify(eq(99L), eq("LEAVE_AUTO_APPROVED"), any(String.class), any(String.class), eq("/leave"), eq(false));
    }

    @Test
    void submissionApprovesWithPaidUnpaidSplitWhenQuotaIsInsufficient() {
        // Leave -> payroll unpaid-day deduction (2026-07-23): the gate no longer auto-rejects purely
        // for exceeding quota -- it approves and splits paidDays/unpaidDays. Quota is 6, 5 already
        // used -> 1 remaining; this 2-day request gets 1 paid day + 1 unpaid day.
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("5.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null)))
            .thenReturn(56L);
        when(leaveRepository.findById(56L)).thenReturn(Optional.of(
            requestDto(56L, 10L, "APPROVED", request.startDate(), request.endDate(), "1.00", "1.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> remainingAfter = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), remainingAfter.capture(), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("1.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("1.00");
        assertThat(remainingAfter.getValue()).isEqualByComparingTo("0.00");
        verify(notificationService).notify(eq(10L), eq("LEAVE_AUTO_APPROVED"), any(String.class), any(String.class), eq("/leave"), eq(true));
    }

    @Test
    void submissionAutoRejectsWhenAdvanceNoticeIsTooShort() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "VACATION",
            weekdayWithinNotice(),
            weekdayWithinNotice(),
            "Urgent errand"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class)))
            .thenReturn(57L);
        when(leaveRepository.findById(57L)).thenReturn(Optional.of(
            requestDto(57L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("at least 7"));
    }

    @Test
    void submissionAutoRejectsSickLeaveWithoutCertificate() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "SICK",
            weekdayAfterNotice(),
            weekdayAfterNotice(),
            "Fever"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("SICK")).thenReturn(Optional.of(sickType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class)))
            .thenReturn(58L);
        when(leaveRepository.findById(58L)).thenReturn(Optional.of(
            requestDto(58L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("medical certificate"));
    }

    @Test
    void submissionOnSickLeaveBeyondQuotaStillRequiresCertificate() {
        // "SICK>30 still needs attachment": exceeding the 30-day quota changes the paid/unpaid split,
        // it does NOT waive the medical-certificate requirement -- that check runs independently of
        // quota availability.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "SICK",
            weekdayAfterNotice(),
            weekdayAfterNotice(),
            "Fever"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("SICK")).thenReturn(Optional.of(sickType()));
        // Quota already fully used (30/30) -- remaining is 0, so even WITH a certificate this would
        // be entirely unpaid; without one, it must still auto-reject on the certificate rule.
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("30.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class)))
            .thenReturn(59L);
        when(leaveRepository.findById(59L)).thenReturn(Optional.of(
            requestDto(59L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("medical certificate"));
    }

    @Test
    void submissionOnLeaveWithoutPayTypeIsAlwaysFullyUnpaid() {
        // LEAVE_WITHOUT_PAY has a 0-day statutory quota, so remaining is always 0 regardless of
        // usage: every requested day is unpaid from day 1.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "LEAVE_WITHOUT_PAY",
            LocalDate.parse("2026-07-13"),
            LocalDate.parse("2026-07-14"),
            "Extended personal matter"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("LEAVE_WITHOUT_PAY")).thenReturn(Optional.of(leaveWithoutPayType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("LEAVE_WITHOUT_PAY"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null)))
            .thenReturn(60L);
        when(leaveRepository.findById(60L)).thenReturn(Optional.of(
            requestDto(60L, 10L, "APPROVED", request.startDate(), request.endDate(), "0.00", "2.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("0.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("2.00");
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
        LeaveRequestDto submitted = requestDto(77L, 10L, "SUBMITTED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), "2.00", "0.00");
        LeaveRequestDto approved = requestDto(77L, 10L, "APPROVED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), "2.00", "0.00");
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
        when(leaveRepository.findById(77L)).thenReturn(Optional.of(
            requestDto(77L, 10L, "SUBMITTED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), "2.00", "0.00")));
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

    @Test
    void cancelAfterAnAlreadyProcessedMonthRecordsAPayrollCorrection() {
        // Cancel-after-close reversal (v1/minimal design): cancelling an APPROVED leave whose unpaid
        // days already landed in a PROCESSED payroll month records a correction so the credit isn't
        // silently lost. This is a Mockito-level proof that LeaveService calls through to the
        // repository correctly; the real weekday-math + real-DB behaviour is covered by
        // LeaveDayMathTest and the integration tests.
        LeaveRequestDto approved = requestDto(80L, 10L, "APPROVED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), "1.00", "1.00");
        when(leaveRepository.findById(80L)).thenReturn(Optional.of(approved));
        when(leaveRepository.cancel(80L, 20L, null)).thenReturn(1);
        when(leaveRepository.findProcessedPayrollMonths(any(Collection.class)))
            .thenReturn(java.util.Set.of(LocalDate.parse("2026-07-01")));
        UserPrincipal hr = user("hr", 20L);

        leaveService.cancel(80L, null, hr);

        verify(leaveRepository).recordPayrollCorrection(80L, 10L, LocalDate.parse("2026-07-01"), new BigDecimal("1"));
    }

    @Test
    void cancelWithNoUnpaidDaysRecordsNoCorrection() {
        LeaveRequestDto approved = requestDto(81L, 10L, "APPROVED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), "2.00", "0.00");
        when(leaveRepository.findById(81L)).thenReturn(Optional.of(approved));
        when(leaveRepository.cancel(81L, 20L, null)).thenReturn(1);
        UserPrincipal hr = user("hr", 20L);

        leaveService.cancel(81L, null, hr);

        verify(leaveRepository, org.mockito.Mockito.never()).findProcessedPayrollMonths(any(Collection.class));
        verify(leaveRepository, org.mockito.Mockito.never()).recordPayrollCorrection(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any(LocalDate.class), any(BigDecimal.class));
    }

    private SubmitLeaveRequest validSubmit(Long employeeId) {
        // Monday–Tuesday, 12 days after FIXED_NOW: 2 working days, well past the 7-day notice.
        return new SubmitLeaveRequest(
            employeeId,
            "VACATION",
            LocalDate.parse("2026-07-13"),
            LocalDate.parse("2026-07-14"),
            "Family trip"
        );
    }

    private LocalDate weekdayWithinNotice() {
        // Thursday, the day after FIXED_NOW: inside the 7-day advance-notice window.
        return LocalDate.parse("2026-07-02");
    }

    private LocalDate weekdayAfterNotice() {
        // Monday, 12 days after FIXED_NOW: outside the advance-notice window.
        return LocalDate.parse("2026-07-13");
    }

    private LeaveTypeDto vacationType() {
        return new LeaveTypeDto("VACATION", "Vacation", "Vacation leave", new BigDecimal("6.00"), false);
    }

    private LeaveTypeDto sickType() {
        return new LeaveTypeDto("SICK", "Sick", "Sick leave", new BigDecimal("30.00"), true);
    }

    private LeaveTypeDto leaveWithoutPayType() {
        return new LeaveTypeDto("LEAVE_WITHOUT_PAY", "Leave without pay", "Leave without pay", BigDecimal.ZERO, false);
    }

    private LeaveRequestDto requestDto(long id, long employeeId, String status, LocalDate startDate, LocalDate endDate,
                                        String paidDays, String unpaidDays) {
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
            new BigDecimal(paidDays),
            new BigDecimal(unpaidDays),
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
