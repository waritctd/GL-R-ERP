package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
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
    private final LeaveService leaveService = new LeaveService(
        leaveRepository, leaveAttachments, fileStorage, auditService, notificationService,
        Clock.fixed(FIXED_NOW, BUSINESS_ZONE));

    {
        // Schedule/holiday-aware working-day counting (2026-08-03): LeaveService now asks
        // leaveRepository.workingDayPredicate(...) for a per-request "is this date a working day"
        // predicate instead of assuming Mon-Fri itself -- see LeaveRepository/LeaveDayMath's javadoc.
        // Default every test to the pre-existing Mon-Fri/no-holiday behaviour (LeaveDayMath's own
        // legacy predicate) so the 30+ tests below that do not care about six-day-schedule or holiday
        // nuances keep testing exactly what they tested before. Tests THAT do care (see the
        // "schedule/holiday-aware" section near the bottom of this file) re-stub this per test.
        when(leaveRepository.workingDayPredicate(anyLong(), any(), any()))
            .thenReturn(LeaveDayMath::isWorkingDay);
    }

    @Test
    void submitAutoApprovesWhenQuotaAndAdvanceNoticeAreSatisfied() {
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("1.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
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
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
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
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
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
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), remainingAfter.capture(), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
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
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(57L);
        when(leaveRepository.findById(57L)).thenReturn(Optional.of(
            requestDto(57L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("at least 7"), eq(null), eq(null), eq(null), eq(null), eq(null));
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
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(58L);
        when(leaveRepository.findById(58L)).thenReturn(Optional.of(
            requestDto(58L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("medical certificate"), eq(null), eq(null), eq(null), eq(null), eq(null));
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
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(59L);
        when(leaveRepository.findById(59L)).thenReturn(Optional.of(
            requestDto(59L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), org.mockito.ArgumentMatchers.contains("medical certificate"), eq(null), eq(null), eq(null), eq(null), eq(null));
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
        // leaveWithoutPayType() carries a real (non-null) paidDaysCap of 0.00, so #submit's
        // boundByPaidCap path calls sumPaidDays -- must be stubbed or Mockito's default null return
        // NPEs inside BigDecimal#subtract.
        when(leaveRepository.sumPaidDays(eq(10L), eq("LEAVE_WITHOUT_PAY"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(60L);
        when(leaveRepository.findById(60L)).thenReturn(Optional.of(
            requestDto(60L, 10L, "APPROVED", request.startDate(), request.endDate(), "0.00", "2.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("0.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("2.00");
    }

    @Test
    void submitAcceptsSubDayLeaveAndComputesFractionalTotalDays() {
        // Sub-day leave (2026-07-25): 08:30-12:30 = 4 clock-hours / 8 = 0.50 day (no lunch
        // subtraction). Quota is fully used already (6/6) -> the whole 0.50 request is unpaid.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "VACATION",
            weekdayAfterNotice(),
            weekdayAfterNotice(),
            "Doctor visit",
            LocalTime.of(8, 30),
            LocalTime.of(12, 30),
            null,
            null,
            null,
            null,
            null
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("6.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(61L);
        when(leaveRepository.findById(61L)).thenReturn(Optional.of(
            requestDto(61L, 10L, "APPROVED", request.startDate(), request.endDate(), "0.00", "0.50")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(totalDays.getValue()).isEqualByComparingTo("0.50");
        assertThat(paidDays.getValue()).isEqualByComparingTo("0.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("0.50");
    }

    @Test
    void submitRejectsSubDayLeaveOnAWeekend() {
        // Sub-day leave, weekend guard (2026-07-25 review fix): Sat 2026-07-18 is a weekend -- a
        // timed leave on it must be rejected the same way the identical whole-day request would be
        // by workingDaysBetween, instead of being silently accepted and producing a base/30 x
        // fraction payroll deduction for a non-working day.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "VACATION",
            LocalDate.parse("2026-07-18"),
            LocalDate.parse("2026-07-18"),
            "Weekend errand",
            LocalTime.of(8, 30),
            LocalTime.of(12, 30),
            null,
            null,
            null,
            null,
            null
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));

        assertThatThrownBy(() -> leaveService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
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
    void leaveRequestsCanNowSpanQuotaYears() {
        // V118 cross-year quota fix (2026-08-02): this used to 400 (leaveRequestsCannotSpanQuotaYears,
        // pre-V118). A request spanning 31 Dec/1 Jan is now approved -- each year's days consume that
        // year's OWN quota, stubbed here as fully available in both years. The exact per-year
        // paid/unpaid split against real dates is proven in LeaveTypeRuleIntegrationTest and
        // LeaveUnpaidDeductionIntegrationTest (real calendar math this Mockito-based class can't
        // fake); this test only proves the request is no longer rejected outright, and that BOTH
        // years' sumUsedDays/sumPaidDays get consulted (not just the start year).
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null,
            "VACATION",
            LocalDate.parse("2026-12-31"),
            LocalDate.parse("2027-01-04"),
            "Year-end trip"
        );
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2027), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(110L);
        when(leaveRepository.findById(110L)).thenReturn(Optional.of(
            requestDto(110L, 10L, "APPROVED", request.startDate(), request.endDate(), "2.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        // Both years must have been consulted, proving the request's days were genuinely attributed
        // per year, not just against the start year the way the pre-fix single-quotaYear model did.
        verify(leaveRepository).sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class));
        verify(leaveRepository).sumUsedDays(eq(10L), eq("VACATION"), eq(2027), any(Collection.class));
        // The per-year attribution itself must have been persisted, one row per year touched.
        ArgumentCaptor<List<LeaveQuotaYearSplit>> splitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(leaveRepository).insertQuotaYearSplits(eq(110L), splitsCaptor.capture());
        List<LeaveQuotaYearSplit> splits = splitsCaptor.getValue();
        assertThat(splits).extracting(LeaveQuotaYearSplit::quotaYear).containsExactly(2026, 2027);
    }

    @Test
    void aCrossYearRequestThatIsAutoRejectedStillRecordsAQuotaYearSplitPerYearWithNothingConsumed() {
        // Wrong-way-round complement: even when the gate rejects (e.g. insufficient advance notice),
        // the per-year attribution rows must still be written -- with paidDays=unpaidDays=0 for every
        // year touched, exactly mirroring how the parent row's own paidDays/unpaidDays stay 0 for an
        // AUTO_REJECTED request today. A cross-year AUTO_REJECTED request that silently skipped
        // writing a quota-year row for one of its years would leave that year's attribution
        // permanently missing -- a data-integrity gap, not just a cosmetic one.
        //
        // Uses SICK (not VACATION) so autoRejectNote fires for a simple, unrelated reason (missing
        // medical certificate) rather than needing the cross-year date itself to trigger a gate.
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("SICK")).thenReturn(Optional.of(sickType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(2027), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        SubmitLeaveRequest sickRequest = new SubmitLeaveRequest(
            null, "SICK", LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-04"), "Year-end illness");
        when(leaveRepository.create(eq(10L), eq(10L), eq(sickRequest), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(111L);
        when(leaveRepository.findById(111L)).thenReturn(Optional.of(
            requestDto(111L, 10L, "AUTO_REJECTED", sickRequest.startDate(), sickRequest.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(sickRequest, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        ArgumentCaptor<List<LeaveQuotaYearSplit>> splitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(leaveRepository).insertQuotaYearSplits(eq(111L), splitsCaptor.capture());
        List<LeaveQuotaYearSplit> splits = splitsCaptor.getValue();
        assertThat(splits).extracting(LeaveQuotaYearSplit::quotaYear).containsExactly(2026, 2027);
        assertThat(splits).allSatisfy(split -> {
            assertThat(split.paidDays()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(split.unpaidDays()).isEqualByComparingTo(BigDecimal.ZERO);
        });
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
        // V118 cross-year quota fix: recordPayrollCorrectionIfNeeded now reads the per-year
        // attribution (not the parent's aggregate paidDays/totalDays) via findQuotaYearSplits --
        // single year here, carrying the same paid/total figures the parent DTO above does.
        when(leaveRepository.findQuotaYearSplits(80L)).thenReturn(List.of(
            new LeaveQuotaYearSplit(2026, new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("1.00"),
                BigDecimal.ZERO, BigDecimal.ZERO)));
        when(leaveRepository.findProcessedPayrollMonths(any(Collection.class)))
            .thenReturn(java.util.Set.of(LocalDate.parse("2026-07-01")));
        UserPrincipal hr = user("hr", 20L);

        leaveService.cancel(80L, null, hr);

        // Sub-day leave (2026-07-25): LeaveDayMath.unpaidWorkingDaysByMonth now returns BigDecimal
        // (multi-day branch emits "1.00", scale 2) instead of the old int-derived "1" (scale 0) --
        // compareTo, not equals, so the assertion doesn't depend on that internal scale choice.
        verify(leaveRepository).recordPayrollCorrection(
            eq(80L), eq(10L), eq(LocalDate.parse("2026-07-01")),
            argThat(value -> value != null && value.compareTo(new BigDecimal("1")) == 0));
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

    // ─────────────────────────────────────────────────────────────────────
    // §5 leave-rules-as-data (V116) -- new per-type gates. Each gate below has a reject case AND an
    // allow case (wrong-way-round: the reject case is the one that matters). The maternity-shaped
    // paid_days_cap 98/45/53 split specifically, and the DB-level (not just Java-level)
    // once-per-employment guard, are proven against real dates/real Postgres in
    // LeaveTypeRuleIntegrationTest -- LeaveDayMath.countWorkingDays is real, static calendar math
    // that this Mockito-based class cannot fake, so a 98-working-day request needs a real date range.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitRejectsWhenMinimumServiceIsNotMet() {
        // ORDINATION requires 12 completed months of service. Hired 2026-01-01, requesting leave
        // starting 2026-07-13: ~6 completed months -- short of 12.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "ORDINATION", weekdayAfterNotice(), weekdayAfterNotice(), "Ordination ceremony");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("ORDINATION")).thenReturn(Optional.of(ordinationType()));
        when(leaveRepository.hasOutstandingOrGrantedRequest(10L, "ORDINATION")).thenReturn(false);
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2026-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(90L);
        when(leaveRepository.findById(90L)).thenReturn(Optional.of(
            requestDto(90L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("month(s) of completed service"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitAllowsWhenMinimumServiceIsMet() {
        // Same type, same request date -- but hired more than 12 months earlier.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "ORDINATION", weekdayAfterNotice(), weekdayAfterNotice(), "Ordination ceremony");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("ORDINATION")).thenReturn(Optional.of(ordinationType()));
        when(leaveRepository.hasOutstandingOrGrantedRequest(10L, "ORDINATION")).thenReturn(false);
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2020-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(91L);
        when(leaveRepository.findById(91L)).thenReturn(Optional.of(
            requestDto(91L, 10L, "APPROVED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void submitRejectsWhenHireDateIsMissing() {
        // DECISION (V116): a NULL hire_date does NOT silently pass a min-service gate -- eligibility
        // cannot be verified, so the request is rejected, not approved. This is the wrong-way-round
        // proof: a still-not-eligible employee (unknown tenure) must not slip through as if they had
        // met the floor.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "ORDINATION", weekdayAfterNotice(), weekdayAfterNotice(), "Ordination ceremony");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("ORDINATION")).thenReturn(Optional.of(ordinationType()));
        when(leaveRepository.hasOutstandingOrGrantedRequest(10L, "ORDINATION")).thenReturn(false);
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.empty());
        when(leaveRepository.sumUsedDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(92L);
        when(leaveRepository.findById(92L)).thenReturn(Optional.of(
            requestDto(92L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("hire date is not on file"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitRejectsARequestExceedingTheMaxConsecutiveDaysCap() {
        // PERSONAL (fixture): max 3 consecutive days. Mon 2026-07-13 .. Thu 2026-07-16 is a 4-calendar
        // -day span (DECISION: consecutive = calendar days, not LeaveDayMath working days -- see
        // LeaveService#autoRejectNote).
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-16"), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        // Review fix (V116): every PERSONAL submission now runs the probation-passed gate
        // (personalProbationRejectionNote) regardless of what this test is actually about -- stub a
        // hire date far enough in the past that it never binds, so this test still isolates
        // max-consecutive-days. findProbationDays is deliberately left unstubbed (falls back to
        // SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS via Optional.empty()), which is still
        // comfortably in the past relative to this hire date.
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(93L);
        when(leaveRepository.findById(93L)).thenReturn(Optional.of(
            requestDto(93L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("consecutive day(s)"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitAllowsARequestWithinTheMaxConsecutiveDaysCap() {
        // Same fixture, a 3-calendar-day span -- exactly at the cap, must be allowed (not "strictly
        // less than").
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-15"), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        // Review fix (V116): see the identical stub note in
        // submitRejectsARequestExceedingTheMaxConsecutiveDaysCap above.
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(94L);
        when(leaveRepository.findById(94L)).thenReturn(Optional.of(
            requestDto(94L, 10L, "APPROVED", request.startDate(), request.endDate(), "3.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.2 PERSONAL "passed probation" gate (review fix, V116). Real prod data is mostly
    // probation_days=90, NOT the 119-day DEFAULT_PROBATION_DAYS fallback -- these tests are
    // specifically about the RESOLUTION (per-employee probation_days, falling back to
    // SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS only when NULL), not about 90 vs 119 being
    // "the" company number. All requests are single-day, comfortably under
    // personalTypeWithMaxConsecutive()'s 3-day cap, so that gate never interferes here.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitRejectsPersonalLeaveWhenTheEmployeeIsStillInProbation() {
        // Hired 2026-06-20, probation_days=90 -> probation ends 2026-09-18. Requesting PERSONAL
        // leave for 2026-07-13 (weekdayAfterNotice()) is well before that.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2026-06-20")));
        when(leaveRepository.findProbationDays(10L)).thenReturn(Optional.of(90));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(100L);
        when(leaveRepository.findById(100L)).thenReturn(Optional.of(
            requestDto(100L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("passed probation"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitAllowsPersonalLeaveAfterProbationHasPassed() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.findProbationDays(10L)).thenReturn(Optional.of(90));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(101L);
        when(leaveRepository.findById(101L)).thenReturn(Optional.of(
            requestDto(101L, 10L, "APPROVED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void submitRejectsPersonalLeaveWhenTheEmployeeHasNoHireDateOnFile() {
        // DECISION (same fail-closed direction as every other eligibility gate in this class): a
        // NULL hire_date does NOT silently pass the probation check -- eligibility cannot be
        // verified, so the request is rejected, not approved.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.empty());
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(102L);
        when(leaveRepository.findById(102L)).thenReturn(Optional.of(
            requestDto(102L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("hire date is not on file"), eq(null), eq(null), eq(null), eq(null), eq(null));
        // findProbationDays must never be reached once hire_date is already missing.
        verify(leaveRepository, org.mockito.Mockito.never()).findProbationDays(anyLong());
    }

    @Test
    void submitAllowsPersonalLeaveImmediatelyWhenProbationDaysIsZero() {
        // probation_days = 0 means eligible from the hire date itself -- hired on the exact request
        // date, no waiting period.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(weekdayAfterNotice()));
        when(leaveRepository.findProbationDays(10L)).thenReturn(Optional.of(0));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(103L);
        when(leaveRepository.findById(103L)).thenReturn(Optional.of(
            requestDto(103L, 10L, "APPROVED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void submitFallsBackToTheDefaultProbationPeriodWhenProbationDaysIsNullOnTheEmployee() {
        // findProbationDays deliberately unstubbed -> Optional.empty() -> falls back to
        // SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS (119). Hired exactly 119 days before
        // the request date -> probation ends ON the request date itself -- "at least", not "strictly
        // more than" -- so this must be APPROVED.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2026-03-16")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(104L);
        when(leaveRepository.findById(104L)).thenReturn(Optional.of(
            requestDto(104L, 10L, "APPROVED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void submitRejectsPersonalLeaveOneDayBeforeTheDefaultProbationPeriodEndsWhenProbationDaysIsNull() {
        // Wrong-way-round complement, pinning the exact DEFAULT_PROBATION_DAYS=119 boundary from the
        // other side: hired ONE DAY LATER than the passing case above (118 completed days, not 119)
        // -> still one day short -> must be rejected, proving the fallback is exactly 119, not some
        // other nearby number, and that the boundary is enforced correctly in both directions.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2026-03-17")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(105L);
        when(leaveRepository.findById(105L)).thenReturn(Optional.of(
            requestDto(105L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("passed probation"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitRejectsAnOncePerEmploymentTypeWhenAClaimAlreadyExists() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "ORDINATION", weekdayAfterNotice(), weekdayAfterNotice(), "Ordination ceremony");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("ORDINATION")).thenReturn(Optional.of(ordinationType()));
        when(leaveRepository.hasOutstandingOrGrantedRequest(10L, "ORDINATION")).thenReturn(true);
        when(leaveRepository.sumUsedDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(95L);
        when(leaveRepository.findById(95L)).thenReturn(Optional.of(
            requestDto(95L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        // Once-per-employment is checked BEFORE min-service -- findHireDate must never even be
        // called, proving the short-circuit (not just the final outcome).
        verify(leaveRepository, org.mockito.Mockito.never()).findHireDate(anyLong());
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("once during your employment"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitAllowsAnOncePerEmploymentTypeWhenNoClaimExistsYet() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "ORDINATION", weekdayAfterNotice(), weekdayAfterNotice(), "Ordination ceremony");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("ORDINATION")).thenReturn(Optional.of(ordinationType()));
        when(leaveRepository.hasOutstandingOrGrantedRequest(10L, "ORDINATION")).thenReturn(false);
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2020-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(96L);
        when(leaveRepository.findById(96L)).thenReturn(Optional.of(
            requestDto(96L, 10L, "APPROVED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void submitTranslatesARaceLostOncePerEmploymentInsertIntoAConflict() {
        // The Java-level check above (hasOutstandingOrGrantedRequest) is the normal AUTO_REJECTED
        // path; this proves the OTHER half -- the DuplicateKeyException catch in #submit that backs
        // ux_leave_once_per_employment (V116) when two submissions race and both read "no existing
        // claim" before either commits. See LeaveTypeRuleIntegrationTest for the real DB index firing.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "ORDINATION", weekdayAfterNotice(), weekdayAfterNotice(), "Ordination ceremony");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("ORDINATION")).thenReturn(Optional.of(ordinationType()));
        when(leaveRepository.hasOutstandingOrGrantedRequest(10L, "ORDINATION")).thenReturn(false);
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2020-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("ORDINATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("ux_leave_once_per_employment"));

        assertThatThrownBy(() -> leaveService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void submitBoundsPaidDaysByThePaidCapIndependentlyOfQuota() {
        // paid_days_cap (V116) bounds how many of the quota-covered days are actually PAID,
        // independently of the quota itself -- proven here with a small cap so the test doesn't need
        // a 98-working-day date range (LeaveDayMath.countWorkingDays is real, static calendar math
        // this Mockito-based class can't fake). The exact §5.4 MATERNITY 98/45/53 split is proven
        // against real dates in LeaveTypeRuleIntegrationTest.
        LeaveTypeDto cappedType = new LeaveTypeDto("VACATION", "Vacation", "Vacation leave",
            new BigDecimal("10.00"), false, new BigDecimal("4.00"), 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null);
        // Mon 2026-07-13 .. Mon 2026-07-20: working days 13,14,15,16,17,20 = 6 working days.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "VACATION", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20"), "Capped leave test");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(cappedType));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(97L);
        when(leaveRepository.findById(97L)).thenReturn(Optional.of(
            requestDto(97L, 10L, "APPROVED", request.startDate(), request.endDate(), "4.00", "2.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            unpaidDays.capture(), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        // 6 working days requested, 10-day quota fully covers them -- but the 4-day paid cap bounds
        // the PAID portion to 4, leaving 2 unpaid even though quota was never exhausted.
        assertThat(paidDays.getValue()).isEqualByComparingTo("4.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("2.00");
    }

    @Test
    void submitDoesNotApplyThePaidCapWhenItExceedsWhatWasRequested() {
        // Same fixture, but the cap (9) is larger than the 6 working days requested -- the cap must
        // not bind, and the result must be identical to the uncapped (quota-only) behaviour.
        LeaveTypeDto cappedType = new LeaveTypeDto("VACATION", "Vacation", "Vacation leave",
            new BigDecimal("10.00"), false, new BigDecimal("9.00"), 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null);
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "VACATION", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20"), "Capped leave test");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(cappedType));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(98L);
        when(leaveRepository.findById(98L)).thenReturn(Optional.of(
            requestDto(98L, 10L, "APPROVED", request.startDate(), request.endDate(), "6.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            unpaidDays.capture(), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("6.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("0.00");
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.4 MATERNITY calendar-day counting (V119, 2026-08-02). LeaveTypeRuleIntegrationTest and
    // LeaveCrossYearQuotaIntegrationTest carry the real-Postgres proof (real calendar math, real
    // LeaveDayMath, real V119 schema); these Mockito-level tests instead prove LeaveService#submit
    // reads the basis OFF THE LEAVE TYPE rather than hardcoding one -- both maternityType()
    // (CALENDAR_DAYS) and sickType() (WORKING_DAYS) are real LeaveTypeDto values a test can swap in.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitCountsCalendarDaysForMaternityButWorkingDaysForSickOnTheIdenticalDateRange() {
        // THE vacuous-fixture guard (CLAUDE.md): the SAME Mon 2026-07-13 .. Sun 2026-07-19 range (a
        // full week, both weekend days included) must count DIFFERENTLY depending on the leave
        // type's basis -- 7 calendar days for MATERNITY, but only 5 working days (Mon-Fri) for SICK.
        // A test asserting only one side would still pass if #computeTotalDays ignored the leave
        // type entirely and always used one basis.
        //
        // SICK's status here is irrelevant to what this test proves (totalDays is computed BEFORE
        // the auto-reject gates run, unconditionally on every submission -- see LeaveService#submit)
        // -- SICK has no attachment here, so it auto-rejects on the medical-certificate gate, which
        // is exactly why the create()/status matchers below are basis-agnostic (any(LeaveStatus.class))
        // rather than pinned to APPROVED. LeaveTypeRuleIntegrationTest's real-DB companion instead
        // supplies a real attachment so both sides land APPROVED.
        LocalDate start = LocalDate.parse("2026-07-13");
        LocalDate end = LocalDate.parse("2026-07-19");

        SubmitLeaveRequest maternityRequest = new SubmitLeaveRequest(null, "MATERNITY", start, end, "Maternity leave");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("MATERNITY")).thenReturn(Optional.of(maternityType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("MATERNITY"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("MATERNITY"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(maternityRequest), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026),
            any(LeaveStatus.class), any(BigDecimal.class), any(BigDecimal.class), nullable(String.class),
            eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(120L);
        when(leaveRepository.findById(120L)).thenReturn(Optional.of(
            requestDto(120L, 10L, "APPROVED", start, end, "7.00", "0.00")));

        leaveService.submit(maternityRequest, user("employee", 10L));

        ArgumentCaptor<BigDecimal> maternityTotalDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(maternityRequest), maternityTotalDays.capture(),
            any(BigDecimal.class), any(BigDecimal.class), eq(2026),
            any(LeaveStatus.class), any(BigDecimal.class), any(BigDecimal.class), nullable(String.class),
            eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(maternityTotalDays.getValue()).isEqualByComparingTo("7.00");

        SubmitLeaveRequest sickRequest = new SubmitLeaveRequest(null, "SICK", start, end, "Sick leave");
        when(leaveRepository.findLeaveType("SICK")).thenReturn(Optional.of(sickType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(sickRequest), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026),
            any(LeaveStatus.class), any(BigDecimal.class), any(BigDecimal.class), nullable(String.class),
            eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(121L);
        when(leaveRepository.findById(121L)).thenReturn(Optional.of(
            requestDto(121L, 10L, "AUTO_REJECTED", start, end, "0.00", "0.00")));

        leaveService.submit(sickRequest, user("employee", 10L));

        ArgumentCaptor<BigDecimal> sickTotalDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(sickRequest), sickTotalDays.capture(),
            any(BigDecimal.class), any(BigDecimal.class), eq(2026),
            any(LeaveStatus.class), any(BigDecimal.class), any(BigDecimal.class), nullable(String.class),
            eq(null), eq(null), eq(null), eq(null), eq(null));
        // The critical negative assertion: 5.00 (working days), NOT 7.00 -- proves SICK did not pick
        // up MATERNITY's calendar-day basis.
        assertThat(sickTotalDays.getValue()).isEqualByComparingTo("5.00");
        assertThat(sickTotalDays.getValue()).isNotEqualByComparingTo(maternityTotalDays.getValue());
    }

    @Test
    void submitAppliesTheFortyFiveDayPaidCapToCalendarDaysForMaternity() {
        // §5.4's 45-day paid cap applies to CALENDAR days (V119), not working days: a 50-calendar-day
        // MATERNITY request (Mon 2026-07-13 .. Sat 2026-08-31, chosen only for a round day count) is
        // fully within the 98-day quota but exceeds the 45-day paid cap by 5 -- 45 paid, 5 unpaid.
        // (The exact §5.4 98/45/53 split against real dates is LeaveTypeRuleIntegrationTest's real-DB
        // proof; this Mockito-level test isolates just the cap arithmetic.)
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "MATERNITY", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-08-31"), "Maternity leave");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("MATERNITY")).thenReturn(Optional.of(maternityType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("MATERNITY"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("MATERNITY"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(122L);
        when(leaveRepository.findById(122L)).thenReturn(Optional.of(
            requestDto(122L, 10L, "APPROVED", request.startDate(), request.endDate(), "45.00", "5.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), paidDays.capture(),
            unpaidDays.capture(), eq(2026),
            eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(totalDays.getValue()).isEqualByComparingTo("50.00");
        assertThat(paidDays.getValue()).isEqualByComparingTo("45.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("5.00");
    }

    // §5.2/§5.3 pro-ration (V120, defect 1 fix). Isolates #employeeAnnualQuota's DECISION (which
    // quota figure -- prorated vs full -- gets fed into the existing quota/paid-cap machinery) from
    // real calendar math; LeaveTypeRuleIntegrationTest proves the same decision survives into real
    // SQL/real dates.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitProratesVacationQuotaUnderOneYearOfServiceAndGrantsFullQuotaAfterOneYear() {
        // Both sides on one fixture/method (same LeaveTypeDto), DIFFERENT request date ranges so
        // each leaveRepository.create() invocation is unambiguously distinguishable to verify() --
        // this cannot pass by only ever constructing the easy (>1 year) case.
        LeaveTypeDto type = vacationTypeProratedFirstYear();
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(type));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);

        // 6 completed months of service (hired exactly 6 months before the request) -> prorated
        // quota = 6.00 * 6/12 = 3.00 -- captured via remainingAfter on a 1-day request (3.00 - 1.00 =
        // 2.00) so the exact prorated figure is pinned, not merely "some non-zero, non-6 number".
        SubmitLeaveRequest underOneYearRequest = new SubmitLeaveRequest(
            null, "VACATION", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), "Family trip");
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2026-01-13")));
        when(leaveRepository.create(eq(10L), eq(10L), eq(underOneYearRequest), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(200L);
        when(leaveRepository.findById(200L)).thenReturn(Optional.of(
            requestDto(200L, 10L, "APPROVED", underOneYearRequest.startDate(), underOneYearRequest.endDate(), "1.00", "0.00")));

        LeaveRequestDto underOneYear = leaveService.submit(underOneYearRequest, user("employee", 10L));
        assertThat(underOneYear.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> underOneYearRemainingAfter = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(underOneYearRequest), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class),
            underOneYearRemainingAfter.capture(), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(underOneYearRemainingAfter.getValue()).isEqualByComparingTo("2.00");

        // Wrong-way-round complement, identical fixture, DIFFERENT date range: hired well over a
        // year before the request -> full 6.00-day quota -> remainingAfter = 6.00 - 1.00 = 5.00, NOT
        // the 2.00 above.
        SubmitLeaveRequest overOneYearRequest = new SubmitLeaveRequest(
            null, "VACATION", LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-20"), "Family trip");
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.create(eq(10L), eq(10L), eq(overOneYearRequest), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(201L);
        when(leaveRepository.findById(201L)).thenReturn(Optional.of(
            requestDto(201L, 10L, "APPROVED", overOneYearRequest.startDate(), overOneYearRequest.endDate(), "1.00", "0.00")));

        LeaveRequestDto overOneYear = leaveService.submit(overOneYearRequest, user("employee", 10L));
        assertThat(overOneYear.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> overOneYearRemainingAfter = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(overOneYearRequest), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class),
            overOneYearRemainingAfter.capture(), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(overOneYearRemainingAfter.getValue()).isEqualByComparingTo("5.00");
    }

    @Test
    void submitRejectsVacationWhenAProratedFirstYearEmployeeHasNoHireDateOnFile() {
        // V120's dedicated proratedFirstYear/hire-date gate (autoRejectNote, runs before
        // minServiceMonths -- VACATION's own min_service_months is 0 post-V120, so that older gate
        // would never catch this): a missing hire_date must fail closed with an explanation, not
        // silently flow into #employeeAnnualQuota and produce a confusing zero-quota approval.
        LeaveTypeDto type = vacationTypeProratedFirstYear();
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "VACATION", weekdayAfterNotice(), weekdayAfterNotice(), "Family trip");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(type));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.empty());
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026), eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(202L);
        when(leaveRepository.findById(202L)).thenReturn(Optional.of(
            requestDto(202L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026), eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("hire date is not on file"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.2 first-year total-days cap (V120, defect 3 fix). Isolates the DECISION (effectiveCap =
    // min(proratedQuota, firstYearMaxDays), enforced as an outright rejection) from real calendar
    // math; LeaveTypeRuleIntegrationTest proves the same decision through the real repository.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitRejectsPersonalLeaveBeyondTheFirstYearCapAndAllowsExactlyAtIt() {
        // Both sides on one fixture/method. Hired 6 months before the request -> prorated quota =
        // 7.00 * 6/12 = 3.50 -- ABOVE the flat 3.00 firstYearMaxDays, so 3.00 is the binding
        // effectiveCap (min(3.50, 3.00) = 3.00).
        LeaveTypeDto type = personalTypeProratedFirstYear();
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(type));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(weekdayAfterNotice().minusMonths(6)));
        // findProbationDays deliberately unstubbed: 6 months (~183 days) safely clears the
        // DEFAULT_PROBATION_DAYS=119 fallback, so the probation gate never binds here -- isolating
        // this test to the first-year cap decision.
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("PERSONAL"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);

        // Exactly at the cap: 3 working days, must be APPROVED and fully paid.
        SubmitLeaveRequest atCap = new SubmitLeaveRequest(
            null, "PERSONAL", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-15"), "Family matter");
        when(leaveRepository.create(eq(10L), eq(10L), eq(atCap), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(210L);
        when(leaveRepository.findById(210L)).thenReturn(Optional.of(
            requestDto(210L, 10L, "APPROVED", atCap.startDate(), atCap.endDate(), "3.00", "0.00")));

        LeaveRequestDto atCapResult = leaveService.submit(atCap, user("employee", 10L));
        assertThat(atCapResult.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(atCap), any(BigDecimal.class), paidDays.capture(),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("3.00");

        // One more than the cap: 4 working days (a DIFFERENT date range so this isn't also blocked
        // by any other gate), must be refused outright -- NOT approved-with-a-partially-unpaid-split,
        // unlike ordinary quota exceedance elsewhere in this class (see
        // submissionApprovesWithPaidUnpaidSplitWhenQuotaIsInsufficient).
        SubmitLeaveRequest overCap = new SubmitLeaveRequest(
            null, "PERSONAL", LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-23"), "Family matter");
        when(leaveRepository.create(eq(10L), eq(10L), eq(overCap), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026), eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(211L);
        when(leaveRepository.findById(211L)).thenReturn(Optional.of(
            requestDto(211L, 10L, "AUTO_REJECTED", overCap.startDate(), overCap.endDate(), "0.00", "0.00")));

        LeaveRequestDto overCapResult = leaveService.submit(overCap, user("employee", 10L));
        assertThat(overCapResult.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(overCap), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026), eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("day(s) total per year"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitDoesNotApplyTheFirstYearCapAfterOneYearOfService() {
        // Wrong-way-round complement: an employee past 12 months of service is NOT subject to
        // firstYearMaxDays at all (not "subject to it with an unlimited allowance") -- a 4-day
        // request (over the flat 3, and over the old blanket max_consecutive_days=3 this replaces)
        // must be approved in full, proving the gate is genuinely skipped, not merely satisfied.
        LeaveTypeDto type = personalTypeProratedFirstYear();
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-16"), "Family matter"); // 4 working days
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(type));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("PERSONAL"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(212L);
        when(leaveRepository.findById(212L)).thenReturn(Optional.of(
            requestDto(212L, 10L, "APPROVED", request.startDate(), request.endDate(), "4.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("4.00");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Schedule/holiday-aware working-day counting (2026-08-03): LeaveService now builds its
    // isWorkingDay predicate via leaveRepository.workingDayPredicate(...) instead of assuming
    // Mon-Fri itself. These tests override this file's default (Mon-Fri/no-holiday) stub per test to
    // prove the predicate genuinely drives #submit's day counts -- LeaveScheduleHolidayAwareIntegrationTest
    // proves the same thing through the real repository/SQL, which these Mockito-level tests cannot.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitCountsASaturdayAsAWorkingDayWhenTheWorkingDayPredicateSaysSo() {
        // Mon 2026-07-13 .. Sat 2026-07-18: simulates an OPS_6D (six-day) employee by stubbing a
        // predicate that treats every day except Sunday as a working day.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "LEAVE_WITHOUT_PAY", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-18"),
            "Six-day schedule test");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("LEAVE_WITHOUT_PAY")).thenReturn(Optional.of(leaveWithoutPayType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("LEAVE_WITHOUT_PAY"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("LEAVE_WITHOUT_PAY"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.workingDayPredicate(eq(10L), eq(request.startDate()), eq(request.endDate())))
            .thenReturn(date -> date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(300L);
        when(leaveRepository.findById(300L)).thenReturn(Optional.of(
            requestDto(300L, 10L, "APPROVED", request.startDate(), request.endDate(), "0.00", "6.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), eq(BigDecimal.ZERO),
            unpaidDays.capture(), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(totalDays.getValue()).as("Mon-Sat all count under the six-day predicate").isEqualByComparingTo("6.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("6.00");
    }

    @Test
    void submitExcludesASeededHolidayFromTheDayCountViaThePredicate() {
        // Mon 2026-07-13 .. Tue 2026-07-14: simulates a seeded hr.holiday row on the Tuesday by
        // stubbing a predicate that excludes that one date specifically -- Monday still counts,
        // proving the exclusion is scoped to the one date, not "nothing counts".
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "LEAVE_WITHOUT_PAY", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"),
            "Holiday-in-range test");
        LocalDate seededHoliday = LocalDate.parse("2026-07-14");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("LEAVE_WITHOUT_PAY")).thenReturn(Optional.of(leaveWithoutPayType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("LEAVE_WITHOUT_PAY"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("LEAVE_WITHOUT_PAY"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.workingDayPredicate(eq(10L), eq(request.startDate()), eq(request.endDate())))
            .thenReturn(date -> !date.equals(seededHoliday));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(301L);
        when(leaveRepository.findById(301L)).thenReturn(Optional.of(
            requestDto(301L, 10L, "APPROVED", request.startDate(), request.endDate(), "0.00", "1.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), eq(BigDecimal.ZERO),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(totalDays.getValue()).as("Monday counts, the holiday-stubbed Tuesday does not").isEqualByComparingTo("1.00");
    }

    @Test
    void submitAllowsASubDayMaternityRequestOnADateThePredicateMarksNonWorkingButRejectsTheSameForAWorkingDaysType() {
        // The double-handling guard, at the LeaveService/mock level (LeaveDayMathTest and
        // LeaveScheduleHolidayAwareIntegrationTest cover the same guard at the pure-math and
        // real-Postgres levels respectively): a predicate that marks the ONE date in range as
        // non-working must reject a WORKING_DAYS sub-day request there, but must NOT reject the
        // identical sub-day shape for a CALENDAR_DAYS (MATERNITY) type -- see
        // LeaveService#validateSubDayTimes's basis gate.
        LocalDate nonWorkingDate = LocalDate.parse("2026-07-13");
        Predicate<LocalDate> alwaysNonWorking = date -> false;

        SubmitLeaveRequest maternitySubDay = new SubmitLeaveRequest(
            null, "MATERNITY", nonWorkingDate, nonWorkingDate, "Sub-day maternity",
            LocalTime.of(8, 30), LocalTime.of(12, 30), null, null, null, null, null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("MATERNITY")).thenReturn(Optional.of(maternityType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("MATERNITY"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumPaidDays(eq(10L), eq("MATERNITY"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.workingDayPredicate(eq(10L), eq(nonWorkingDate), eq(nonWorkingDate)))
            .thenReturn(alwaysNonWorking);
        when(leaveRepository.create(eq(10L), eq(10L), eq(maternitySubDay), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.APPROVED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(302L);
        when(leaveRepository.findById(302L)).thenReturn(Optional.of(
            requestDto(302L, 10L, "APPROVED", nonWorkingDate, nonWorkingDate, "0.50", "0.00")));

        LeaveRequestDto maternityResult = leaveService.submit(maternitySubDay, user("employee", 10L));
        assertThat(maternityResult.status())
            .as("CALENDAR_DAYS (MATERNITY) must not be rejected by the WORKING_DAYS-only gate")
            .isEqualTo("APPROVED");

        SubmitLeaveRequest vacationSubDay = new SubmitLeaveRequest(
            null, "VACATION", nonWorkingDate, nonWorkingDate, "Sub-day vacation",
            LocalTime.of(8, 30), LocalTime.of(12, 30), null, null, null, null, null);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.workingDayPredicate(eq(10L), eq(nonWorkingDate), eq(nonWorkingDate)))
            .thenReturn(alwaysNonWorking);

        assertThatThrownBy(() -> leaveService.submit(vacationSubDay, user("employee", 10L)))
            .as("WORKING_DAYS (VACATION) on the SAME non-working date must still be rejected")
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
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

    // §5 leave-rules-as-data (V116): these fixtures intentionally use "no restriction" for the new
    // fields NOT under test in a given method (paidDaysCap null, advanceNoticeDays kept at the
    // pre-V116 test value of 7 so the existing advance-notice test keeps its exact assertion,
    // minServiceMonths 0, maxConsecutiveDays null, oncePerEmployment false) -- isolating each gate to
    // the tests that specifically construct a LeaveTypeDto with that field set is more legible than
    // making every test carry every real seeded value. The real seeded values (VACATION notice=3,
    // min-service=12; PERSONAL notice=1, min-service=4, max-consecutive=3; etc.) are covered by
    // LeaveTypeRuleIntegrationTest against the real V116-migrated schema.
    private LeaveTypeDto vacationType() {
        // proratedFirstYear=false here (deliberate, same "no restriction on fields not under test"
        // convention as every other field in this fixture): pro-ration is covered by its own
        // dedicated fixture/tests below (vacationTypeProratedFirstYear,
        // submitProratesVacationQuotaForAnEmployeeUnderOneYearOfService friends), which construct a
        // LeaveTypeDto with proratedFirstYear=true explicitly rather than making every existing
        // VACATION test in this class newly depend on a stubbed hire date.
        return new LeaveTypeDto("VACATION", "Vacation", "Vacation leave", new BigDecimal("6.00"), false,
            null, 7, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null);
    }

    private LeaveTypeDto sickType() {
        return new LeaveTypeDto("SICK", "Sick", "Sick leave", new BigDecimal("30.00"), true,
            null, 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null);
    }

    private LeaveTypeDto leaveWithoutPayType() {
        return new LeaveTypeDto("LEAVE_WITHOUT_PAY", "Leave without pay", "Leave without pay", BigDecimal.ZERO, false,
            BigDecimal.ZERO, 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null);
    }

    private LeaveTypeDto ordinationType() {
        return new LeaveTypeDto("ORDINATION", "Ordination", "Ordination leave", new BigDecimal("60.00"), false,
            new BigDecimal("15.00"), 0, 12, null, true, LeaveDayCountBasis.WORKING_DAYS, false, null);
    }

    // proratedFirstYear=false, maxConsecutiveDays=3.00 (deliberately the OLD pre-V120 shape -- see
    // vacationType()'s identical note above): this fixture exists ONLY to exercise the generic
    // max_consecutive_days mechanism itself (still a real, supported column -- just no longer
    // populated for the real PERSONAL row post-V120), for
    // submitRejects/AllowsARequestWithin/ExceedingTheMaxConsecutiveDaysCap and the probation tests
    // below, none of which are about defect 3. The real post-V120 PERSONAL shape (maxConsecutiveDays
    // null, firstYearMaxDays 3.00) is personalTypeProratedFirstYear() below.
    private LeaveTypeDto personalTypeWithMaxConsecutive() {
        return new LeaveTypeDto("PERSONAL", "Personal", "Personal leave", new BigDecimal("7.00"), false,
            null, 0, 0, new BigDecimal("3.00"), false, LeaveDayCountBasis.WORKING_DAYS, false, null);
    }

    // §5.4 MATERNITY calendar-day counting (V119, 2026-08-02): the one fixture in this class with
    // CALENDAR_DAYS, used by the unit tests proving LeaveService#computeTotalDays and #submit select
    // the basis from the leave type, not a hardcoded assumption.
    private LeaveTypeDto maternityType() {
        return new LeaveTypeDto("MATERNITY", "Maternity", "Maternity leave", new BigDecimal("98.00"), true,
            new BigDecimal("45.00"), 0, 0, null, false, LeaveDayCountBasis.CALENDAR_DAYS, false, null);
    }

    // §5.2/§5.3 pro-ration (V120, defect 1 fix). Real completed-months-of-service arithmetic against
    // a real quota split (SICK/quota-exceeded-style scenarios) is proven against real dates in
    // LeaveTypeRuleIntegrationTest; these Mockito-level tests isolate #employeeAnnualQuota's decision
    // (which quota figure gets used) from the real calendar math LeaveDayMath performs elsewhere.

    private LeaveTypeDto vacationTypeProratedFirstYear() {
        return new LeaveTypeDto("VACATION", "Vacation", "Vacation leave", new BigDecimal("6.00"), false,
            null, 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, true, null);
    }

    // §5.2 first-year total-days cap (V120, defect 3 fix). maxConsecutiveDays=null (the OLD blanket
    // 2561-era rule this replaces -- see V120's migration comment) and firstYearMaxDays=3.00 (the
    // NEW tenure-scoped total, real seeded PERSONAL value) match the real post-V120 schema exactly,
    // unlike personalTypeWithMaxConsecutive() above (which deliberately keeps the pre-defect-3 shape
    // for its own, unrelated tests).
    private LeaveTypeDto personalTypeProratedFirstYear() {
        return new LeaveTypeDto("PERSONAL", "Personal", "Personal leave", new BigDecimal("7.00"), false,
            null, 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, true, new BigDecimal("3.00"));
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
            // Sub-day leave (2026-07-25): no test in this class needs a sub-day request, so
            // startTime/endTime stay null (legacy/whole-day) here.
            null,
            null,
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
            timestamp,
            // Paper-form contact block (2026-07-25): no test in this class asserts on these, so they
            // stay null.
            null,
            null,
            null,
            null,
            null
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }
}
