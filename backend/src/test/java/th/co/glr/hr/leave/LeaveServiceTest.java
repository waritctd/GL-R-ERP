package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;

class LeaveServiceTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok — all leave dates below are fixed relative to this.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");
    // Partial-day span: the seeded standard schedule (08:30-17:30, 9h/540min, Mon-Fri) --
    // same fixture LeaveDayMathTest/AttendanceDailyCalculatorTest already use. Still resolved per
    // boundary date to validate TIME BOUNDS (LeaveService#validateWithinSchedule); the day-FRACTION
    // itself is the fixed 480-worked-minute/12:30-13:30-break rule (LeaveDayMath), not this
    // schedule's own span.
    private static final WorkSchedule STANDARD_SCHEDULE = new WorkSchedule(
        BUSINESS_ZONE, LocalTime.of(8, 30), LocalTime.of(17, 30), 5,
        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

    private final LeaveRepository leaveRepository = mock(LeaveRepository.class);
    private final LeaveAttachmentRepository leaveAttachments = mock(LeaveAttachmentRepository.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    // Kept as a field (not inlined into the constructor call) so #notifyPendingApproval's
    // HR-fallback branch (employeeRepository.findHrEmployeeIds()) is stubbable from a test -- see
    // #submitWithNoManagerOfRecordNotifiesEveryHrEmployeeAsTheFallbackApprover below. Mockito
    // defaults an unstubbed List-returning method to an empty list, so without a stub that branch
    // never executes.
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final LeaveService leaveService = new LeaveService(
        leaveRepository, leaveAttachments, fileStorage, auditService, notificationService,
        employeeRepository,
        Clock.fixed(FIXED_NOW, BUSINESS_ZONE));

    {
        // LeaveService asks leaveRepository.workingDayPredicate(...) for a per-request "is this
        // date a working day" predicate -- see LeaveRepository/LeaveDayMath's javadoc.
        // Default every test to the Mon-Fri/no-holiday behaviour (LeaveDayMath's own legacy
        // predicate) so the tests that do not care about six-day-schedule or holiday nuances are
        // unaffected. Tests THAT do care (see the "schedule/holiday-aware" section near the bottom
        // of this file) re-stub this per test.
        when(leaveRepository.workingDayPredicate(anyLong(), any(), any()))
            .thenReturn(LeaveDayMath::isWorkingDay);
        // LeaveService resolves leaveRepository.resolveOwnSchedule(...) for every TIMED request's
        // boundary date(s) -- default every test to the seeded standard 08:30-17:30 Mon-Fri
        // schedule (the same fixture LeaveDayMathTest/AttendanceDailyCalculatorTest use), so the
        // timed-request tests below need not each re-declare it. Tests that care about a DIFFERENT schedule (e.g. a
        // six-day division) re-stub this per test.
        when(leaveRepository.resolveOwnSchedule(anyLong(), any())).thenReturn(STANDARD_SCHEDULE);
        // §5.3.5 pool split:LeaveService#carriedInRemaining/#ownQuotaRemaining subtract
        // these from a pool's size, and Mockito defaults an unstubbed BigDecimal-returning method to
        // NULL -- which NPEs inside BigDecimal.subtract for EVERY test that reaches
        // #computeQuotaSplit, not just the pool-aware ones. The real methods can never return null
        // (both COALESCE in SQL and null-guard in Java), so defaulting them to ZERO here is the
        // faithful stand-in for "this employee has no recorded pool consumption yet", not a
        // convenience fiction. Tests that DO care about pool arithmetic re-stub these per test.
        when(leaveRepository.sumCarriedInDaysUsed(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.sumOwnQuotaDaysUsed(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
    }

    @Test
    void submitLandsSubmittedWhenQuotaAndAdvanceNoticeAreSatisfied() {
        // Leave requires approval: a rule-passing request does not auto-approve -- it lands
        // SUBMITTED and waits for a human (#approve/#reject).
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("1.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(55L);
        when(leaveRepository.findById(55L)).thenReturn(Optional.of(
            requestDto(55L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "2.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.id()).isEqualTo(55L);
        assertThat(result.status()).isEqualTo("SUBMITTED");
        ArgumentCaptor<BigDecimal> totalDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), totalDays.capture(), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(totalDays.getValue()).isEqualByComparingTo("2.00");
        // Fully within quota (1 used, 6 quota -> 5 remaining, 2 requested): entirely paid, nothing
        // unpaid, for a request quota fully covers.
        assertThat(paidDays.getValue()).isEqualByComparingTo("2.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("0.00");
        // The employee is told their request was submitted and is awaiting approval; the manager
        // (99L, the requestDto() fixture's hardcoded manager, != actor 10L) is told a request is
        // pending THEIR review -- LEAVE_PENDING_APPROVAL, not LEAVE_AUTO_APPROVED (nothing
        // auto-approves).
        verify(notificationService).notify(eq(10L), eq("LEAVE_SUBMITTED"), any(String.class), any(String.class), eq("/leave"), eq(false));
        verify(notificationService).notify(eq(99L), eq("LEAVE_PENDING_APPROVAL"), any(String.class), any(String.class), eq("/leave"), eq(false));
    }

    @Test
    void submissionLandsSubmittedWithPaidUnpaidSplitWhenQuotaIsInsufficient() {
        // Leave -> payroll unpaid-day deduction: the gate does not auto-reject merely for
        // exceeding quota -- it approves and splits paidDays/unpaidDays. Quota is 6, 5 already
        // used -> 1 remaining; this 2-day request gets 1 paid day + 1 unpaid day.
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(new BigDecimal("5.00"));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(56L);
        when(leaveRepository.findById(56L)).thenReturn(Optional.of(
            requestDto(56L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "1.00", "1.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        ArgumentCaptor<BigDecimal> paidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unpaidDays = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> remainingAfter = ArgumentCaptor.forClass(BigDecimal.class);
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), paidDays.capture(),
            unpaidDays.capture(), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), remainingAfter.capture(), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
        assertThat(paidDays.getValue()).isEqualByComparingTo("1.00");
        assertThat(unpaidDays.getValue()).isEqualByComparingTo("1.00");
        assertThat(remainingAfter.getValue()).isEqualByComparingTo("0.00");
        verify(notificationService).notify(eq(10L), eq("LEAVE_SUBMITTED"), any(String.class), any(String.class), eq("/leave"), eq(false));
    }

    @Test
    void submissionWarnsUnpaidWhenAdvanceNoticeIsTooShort() {
        // ADVANCE_NOTICE is WARN_UNPAID_ALL, not BLOCK: the request is submitted, wholly unpaid
        // by rule if approved.
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
        // NOTE: paidDays/unpaidDays are matched with BigDecimal.ZERO/BigDecimal.ONE below (scale 0), NOT "0.00"/"1.00"
        // -- a single-day request's totalDays (LeaveDayMath's ONE_DAY constant) is itself scale-0, and every
        // arithmetic step downstream (min/subtract) preserves that scale when both operands share it.
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ONE), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(57L);
        when(leaveRepository.findById(57L)).thenReturn(Optional.of(
            requestDto(57L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "0.00", "1.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        verify(leaveRepository).recordRuleWarnings(eq(57L),
            argThat(warnings -> warnings.size() == 1 && warnings.get(0).code() == LeaveRuleCode.ADVANCE_NOTICE
                && warnings.get(0).messageTh().contains("อย่างน้อย 7 วันทำงาน")),
            org.mockito.ArgumentMatchers.argThat(days -> days.compareTo(new BigDecimal("1.00")) == 0));
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.1 SICK certificate + filing-window + no-certificate tolerance. A certificate-less request
    // is ALLOWED up to sickType()'s seeded 3-occasions-per-month tolerance, and a certificate must
    // be filed within the seeded 3-working-day window. See LeaveService#sickCertificateRuleOutcome's
    // Javadoc for the full combined decision table this class of tests proves.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submissionAllowsTheThirdCertificatelessSickOccasionButWarnsUnpaidForTheFourthInTheSameMonth() {
        // THE boundary, asserted from both sides on ONE fixture (same request shape, same month):
        // 2 occasions already used -> this one is the 3rd -> still within the tolerance of 3 ->
        // SUBMITTED, fully paid (rule chain passed cleanly). 3 occasions already used -> this one
        // would be the 4th -> exceeds the tolerance -> still SUBMITTED (SICK_NO_CERT_TOLERANCE_EXHAUSTED
        // is WARN_UNPAID_ALL, not BLOCK), but wholly unpaid by rule if approved. If
        // the boundary comparison used > instead of >=, this test would fail on the "3 used" side
        // instead of passing.
        SubmitLeaveRequest request = new SubmitLeaveRequest(null, "SICK", weekdayAfterNotice(), weekdayAfterNotice(), "Fever");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("SICK")).thenReturn(Optional.of(sickType()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("SICK"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);

        // Side 1: 2 occasions already used this month -> the 3rd -> ALLOWED.
        when(leaveRepository.countNoCertificateRequestsInMonth(eq(10L), eq("SICK"),
            eq(request.startDate().withDayOfMonth(1)), any(LocalDate.class), any(Collection.class)))
            .thenReturn(2);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(58L);
        when(leaveRepository.findById(58L)).thenReturn(Optional.of(
            requestDto(58L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto allowed = leaveService.submit(request, user("employee", 10L));
        assertThat(allowed.status()).isEqualTo("SUBMITTED");

        // Side 2: 3 occasions already used this month -> the 4th -> REJECTED. Re-stub the SAME repository mock, same
        // request, only the occasion count changes.
        when(leaveRepository.countNoCertificateRequestsInMonth(eq(10L), eq("SICK"),
            eq(request.startDate().withDayOfMonth(1)), any(LocalDate.class), any(Collection.class)))
            .thenReturn(3);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ONE), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(59L);
        when(leaveRepository.findById(59L)).thenReturn(Optional.of(
            requestDto(59L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "0.00", "1.00")));

        LeaveRequestDto warned = leaveService.submit(request, user("employee", 10L));
        assertThat(warned.status()).isEqualTo("SUBMITTED");
        verify(leaveRepository).recordRuleWarnings(eq(59L),
            argThat(warnings -> warnings.size() == 1
                && warnings.get(0).code() == LeaveRuleCode.SICK_NO_CERT_TOLERANCE_EXHAUSTED
                && warnings.get(0).messageTh().contains("ไม่เกิน 3 ครั้งต่อเดือน")),
            org.mockito.ArgumentMatchers.argThat(days -> days.compareTo(BigDecimal.ONE) == 0));
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
    void cancelAfterAnAlreadyProcessedMonthRecordsAPayrollCorrection() {
        // Cancel-after-close reversal (v1/minimal design): cancelling an APPROVED leave whose unpaid days already
        // landed in a PROCESSED payroll month records a correction so the credit isn't silently lost.
        LeaveRequestDto approved = requestDto(80L, 10L, "APPROVED", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), "1.00", "1.00");
        when(leaveRepository.findById(80L)).thenReturn(Optional.of(approved));
        when(leaveRepository.cancel(80L, 20L, null)).thenReturn(1);
        // recordPayrollCorrectionIfNeeded reads the per-year attribution (not the parent's aggregate paidDays/totalDays) via findQuotaYearSplits --
        // single year here, carrying the same paid/total figures the parent DTO above does.
        when(leaveRepository.findQuotaYearSplits(80L)).thenReturn(List.of(
            new LeaveQuotaYearSplit(2026, new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("1.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2.00"), BigDecimal.ZERO)));
        when(leaveRepository.findProcessedPayrollMonths(any(Collection.class)))
            .thenReturn(java.util.Set.of(LocalDate.parse("2026-07-01")));
        UserPrincipal hr = user("hr", 20L);

        leaveService.cancel(80L, null, hr);

        // LeaveDayMath.unpaidWorkingDaysByMonth returns BigDecimal (multi-day branch emits "1.00",
        // scale 2) -- compareTo, not equals, so the assertion doesn't depend on that internal scale choice.
        verify(leaveRepository).recordPayrollCorrection(
            eq(80L), eq(10L), eq(LocalDate.parse("2026-07-01")),
            argThat(value -> value != null && value.compareTo(new BigDecimal("1")) == 0));
    }

    // Notification coverage gap B: cancel() must notify the requester and whoever the request was still pending
    // with -- but NOT an already-decided request's approvers.

    /** Like {@link #requestDto}, but with reviewedById/reviewedByName/managerEmployeeId controllable -- needed to prove the self-cancel-vs-reviewer-cancel wording and the pending-manager resolution in the gap B tests above. */

    /**
     * Like {@link #requestDto}, but with employeeId/managerEmployeeId controllable -- needed by the
     * two {@link #notifyPendingApproval} tests below, neither of which the fixed-{@code 10L}/
     * {@code 99L} {@link #requestDto} fixture can express (one needs {@code managerEmployeeId ==
     * null}; the other needs {@code managerEmployeeId} to equal the ACTOR, not a fixed third party).
     */
    private LeaveRequestDto requestDtoWithManager(long id, long employeeId, LocalDate startDate, LocalDate endDate, Long managerEmployeeId) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-14T10:00:00+07:00");
        return new LeaveRequestDto(
            id, employeeId, "EMP001", "Test Employee",
            "VACATION", "Vacation", "Vacation leave",
            startDate, endDate, null, null,
            new BigDecimal("2.00"), new BigDecimal("2.00"), new BigDecimal("0.00"),
            startDate.getYear(), "Family trip", null, null, "SUBMITTED",
            new BigDecimal("5.00"), new BigDecimal("3.00"), null,
            employeeId, "Test Employee", timestamp,
            null, null, null, null, null,
            managerEmployeeId, managerEmployeeId == null ? null : "Test Manager",
            timestamp, timestamp,
            null, null, null, null, null,
            null, false, null, Map.of(), false,
            // feat/pending-approver-info: no test using this fixture asserts on these (computed by
            // LeaveRepository#mapRequest, a real SQL row mapper this Mockito-level class never hits).
            null, null,
            // WARN_UNPAID_*: no test using this fixture asserts on these.
            List.of(), BigDecimal.ZERO
        );
    }

    /**
     * {@link #notifyPendingApproval}'s HR-fallback branch
     * ({@code employeeRepository.findHrEmployeeIds()}, taken when {@code managerEmployeeId == null})
     * is reached only by a request with no manager: Mockito defaults an unstubbed
     * {@code List}-returning method to an empty list, and every other submit test's
     * {@code requestDto()}/{@code requestDtoWithManager()} fixture with a non-null manager takes the
     * OTHER branch.
     *
     * <p>Employee ids are realistic 4-5 digit values (10010L/10200L/10201L), not values inside Java's
     * {@code Long} cache ([-128, 127]) -- boxed {@code Long}s in that range compare identical by
     * reference and can hide an equality bug.
     *
     * <p>Mutation check: blanking the body of the
     * {@code else { approvers.addAll(employeeRepository.findHrEmployeeIds()); }} branch must fail
     * exactly this test, on the missing 10200L {@code LEAVE_PENDING_APPROVAL} notification.
     */
    @Test
    void submitWithNoManagerOfRecordNotifiesEveryHrEmployeeAsTheFallbackApprover() {
        SubmitLeaveRequest request = validSubmit(10010L);
        when(leaveRepository.employeeExists(10010L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.sumUsedDays(eq(10010L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10010L), eq(10010L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(70L);
        when(leaveRepository.findById(70L)).thenReturn(Optional.of(
            requestDtoWithManager(70L, 10010L, request.startDate(), request.endDate(), null)));
        when(employeeRepository.findHrEmployeeIds()).thenReturn(List.of(10200L, 10201L));

        leaveService.submit(request, user("employee", 10010L));

        verify(notificationService).notify(eq(10010L), eq("LEAVE_SUBMITTED"), any(String.class), any(String.class), eq("/leave"), eq(false));
        verify(notificationService).notify(eq(10200L), eq("LEAVE_PENDING_APPROVAL"), any(String.class), any(String.class), eq("/leave"), eq(false));
        verify(notificationService).notify(eq(10201L), eq("LEAVE_PENDING_APPROVAL"), any(String.class), any(String.class), eq("/leave"), eq(false));
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5 leave-rules-as-data -- per-type gates. Each gate below has a reject case AND an
    // allow case (wrong-way-round: the reject case is the one that matters). The maternity-shaped
    // paid_days_cap 98/45/53 split specifically, and the DB-level (not just Java-level)
    // once-per-employment guard, are proven against real dates/real Postgres in
    // LeaveTypeRuleIntegrationTest -- LeaveDayMath.countWorkingDays is real, static calendar math
    // that this Mockito-based class cannot fake, so a 98-working-day request needs a real date range.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitWarnsUnpaidForTheExcessOverTheMaxConsecutiveDaysCap() {
        // MAX_CONSECUTIVE_DAYS is WARN_UNPAID_EXCESS, not BLOCK. Unlike the WARN_UNPAID_ALL
        // tests above, this is the ONE EXCESS (not ALL) code exercised in this class:
        // PERSONAL (fixture): max 3 consecutive days. Mon 2026-07-13 .. Thu 2026-07-16 is a 4-calendar
        // -day span (DECISION: consecutive = calendar days, not LeaveDayMath working days -- see
        // LeaveService#autoRejectNote) -- 1 day over the cap, so only that 1 (of 4 total working-day)
        // gets marked unpaid by rule; the other 3 still go through the ordinary quota/paid-cap
        // machinery below (7.00 quota, unused -> paidDays=3.00).
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-16"), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        // Every PERSONAL submission runs the probation-passed gate
        // (personalProbationRuleOutcome) regardless of what this test is actually about -- stub a
        // hire date far enough in the past that it never binds, so this test still isolates
        // max-consecutive-days. findProbationDays is deliberately left unstubbed (falls back to
        // SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS via Optional.empty()), which is still
        // comfortably in the past relative to this hire date.
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(93L);
        when(leaveRepository.findById(93L)).thenReturn(Optional.of(
            requestDto(93L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "3.00", "1.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        verify(leaveRepository).recordRuleWarnings(eq(93L),
            argThat(warnings -> warnings.size() == 1 && warnings.get(0).code() == LeaveRuleCode.MAX_CONSECUTIVE_DAYS
                && warnings.get(0).messageTh().contains("ติดต่อกันได้ไม่เกิน")),
            org.mockito.ArgumentMatchers.argThat(days -> days.compareTo(BigDecimal.ONE) == 0));
    }

    // ─────────────────────────────────────────────────────────────────────
    // confirm_date resolution: hr.employee.confirm_date, when present,
    // is authoritative over the computed hire_date + probation_days form, and the employee becomes
    // eligible the day AFTER it -- NOT on confirm_date itself. Both directions pinned around the
    // SAME request date (weekdayAfterNotice(), 2026-07-13) so a boundary bug in either direction is
    // caught. Shares SpecialMoneyPolicyEvaluator#hasPassedProbation with the special-money gate --
    // see that class's own tests for the resolution's unit coverage in isolation.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void submitWarnsUnpaidPersonalLeaveOnConfirmDateItself() {
        // PROBATION_NOT_PASSED is WARN_UNPAID_ALL, not BLOCK. hire_date/probation_days would say
        // "eligible" (hired long ago, low probation_days), but confirm_date is authoritative and the
        // request date IS confirm_date -- not yet eligible, so the whole 1-day request warns unpaid.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.findConfirmDate(10L)).thenReturn(Optional.of(weekdayAfterNotice()));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ONE), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(106L);
        when(leaveRepository.findById(106L)).thenReturn(Optional.of(
            requestDto(106L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "0.00", "1.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        verify(leaveRepository).recordRuleWarnings(eq(106L),
            argThat(warnings -> warnings.size() == 1 && warnings.get(0).code() == LeaveRuleCode.PROBATION_NOT_PASSED
                && warnings.get(0).messageTh().contains("ผ่านทดลองงานก่อน")),
            org.mockito.ArgumentMatchers.argThat(days -> days.compareTo(BigDecimal.ONE) == 0));
        // confirm_date is authoritative -- probation_days must never even be read once it is present.
        verify(leaveRepository, org.mockito.Mockito.never()).findProbationDays(anyLong());
    }

    @Test
    void submitAllowsPersonalLeaveTheDayAfterConfirmDate() {
        // SAME confirm_date family as the rejection above, pinned from the other side: confirm_date is one day BEFORE
        // the request date this time -> eligible.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "PERSONAL", weekdayAfterNotice(), weekdayAfterNotice(), "Family matter");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("PERSONAL")).thenReturn(Optional.of(personalTypeWithMaxConsecutive()));
        when(leaveRepository.findHireDate(10L)).thenReturn(Optional.of(LocalDate.parse("2015-01-01")));
        when(leaveRepository.findConfirmDate(10L)).thenReturn(Optional.of(weekdayAfterNotice().minusDays(1)));
        when(leaveRepository.sumUsedDays(eq(10L), eq("PERSONAL"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(107L);
        when(leaveRepository.findById(107L)).thenReturn(Optional.of(
            requestDto(107L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        verify(leaveRepository, org.mockito.Mockito.never()).findProbationDays(anyLong());
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
        // Once-per-employment is checked BEFORE min-service -- findHireDate must never even be called, proving the
        // short-circuit (not just the final outcome).
        verify(leaveRepository, org.mockito.Mockito.never()).findHireDate(anyLong());
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitTranslatesARaceLostOncePerEmploymentInsertIntoAConflict() {
        // The Java-level check above (hasOutstandingOrGrantedRequest) is the normal AUTO_REJECTED
        // path; this proves the OTHER half -- the DuplicateKeyException catch in #submit that backs
        // ux_leave_once_per_employment when two submissions race and both read "no existing
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
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("ux_leave_once_per_employment"));

        assertThatThrownBy(() -> leaveService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // §5.3 relational rules: department coverage, contiguous PERSONAL/VACATION, post-resignation. Mockito-level
    // decision tests; LeavePreviewIntegrationTest proves the same gates against real Postgres.

    @Test
    void submitRejectsVacationWhenAResignationHasBeenSubmitted() {
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "VACATION", weekdayAfterNotice(), weekdayAfterNotice(), "Family trip");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.hasSubmittedResignation(10L)).thenReturn(true);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(300L);
        when(leaveRepository.findById(300L)).thenReturn(Optional.of(
            requestDto(300L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("ยื่นใบลาออกแล้ว"), eq(null), eq(null), eq(null), eq(null), eq(null));
        // Categorical gate, runs before hire-date-dependent gates -- findHireDate must never be reached.
        verify(leaveRepository, org.mockito.Mockito.never()).findHireDate(anyLong());
    }

    @Test
    void submitAllowsVacationWhenNoResignationHasBeenSubmitted() {
        // Wrong-way-round complement, identical fixture: no hr.resignation row -> approved as normal.
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.hasSubmittedResignation(10L)).thenReturn(false);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(301L);
        when(leaveRepository.findById(301L)).thenReturn(Optional.of(
            requestDto(301L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "2.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void submitRejectsVacationContiguousWithAnExistingPersonalRequestAcrossAWeekend() {
        // §5.3.3 "ติดต่อกัน across a non-working day" DECISION: a Friday PERSONAL request immediately followed by a
        // Monday VACATION request, with an ordinary Sat/Sun weekend (no scheduled workday) between them, counts as
        // CONTIGUOUS -- the weekend does not break the run.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "VACATION", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), "Family trip"); // Monday
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.hasSubmittedResignation(10L)).thenReturn(false);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.findActiveRequestsByType(eq(10L), eq("PERSONAL"), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(new LeaveRequestSpan(LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-10")))); // Friday
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026), eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(310L);
        when(leaveRepository.findById(310L)).thenReturn(Optional.of(
            requestDto(310L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(2026), eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("โดยไม่มีวันทำงานคั่นระหว่างกัน"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitAllowsVacationWhenAPriorPersonalRequestHasAClearWorkingDayGap() {
        // Wrong-way-round complement, same paired PERSONAL request: the new VACATION request instead starts a week
        // later, with a genuine Mon-Fri working gap in between -- must be allowed.
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            null, "VACATION", LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-20"), "Family trip");
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.hasSubmittedResignation(10L)).thenReturn(false);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class))).thenReturn(BigDecimal.ZERO);
        when(leaveRepository.findActiveRequestsByType(eq(10L), eq("PERSONAL"), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(new LeaveRequestSpan(LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-10"))));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(2026), eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(311L);
        when(leaveRepository.findById(311L)).thenReturn(Optional.of(
            requestDto(311L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "1.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void submitBlocksVacationWhenItWouldLeaveNoOneElseInTheDepartmentAtWork() {
        // §5.3.2, first half of the vacuous-fixture pair: a THREE-person department where BOTH colleagues are on leave
        // that day -- one SUBMITTED, one APPROVED, proving a PENDING request blocks too, not only an APPROVED one.
        SubmitLeaveRequest request = validSubmit(null); // Mon 2026-07-13 .. Tue 2026-07-14
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.hasSubmittedResignation(10L)).thenReturn(false);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.findActiveDepartmentColleagues(10L)).thenReturn(List.of(20L, 30L));
        when(leaveRepository.findActiveLeaveSpans(eq(List.of(20L, 30L)), eq(request.startDate()), eq(request.endDate())))
            .thenReturn(List.of(
                new EmployeeLeaveSpan(20L, LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13")),
                new EmployeeLeaveSpan(30L, LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"))));
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class), any(String.class), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(320L);
        when(leaveRepository.findById(320L)).thenReturn(Optional.of(
            requestDto(320L, 10L, "AUTO_REJECTED", request.startDate(), request.endDate(), "0.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        verify(leaveRepository).create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), eq(BigDecimal.ZERO),
            eq(BigDecimal.ZERO), eq(request.startDate().getYear()),
            eq(LeaveStatus.AUTO_REJECTED), any(BigDecimal.class), any(BigDecimal.class),
            org.mockito.ArgumentMatchers.contains("ไม่มีพนักงานคนอื่นในแผนกมาทำงาน"), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void submitAllowsVacationWhenAColleagueRemainsAtWork() {
        // §5.3.2, wrong-way-round complement, THE SAME fixture -- only ONE colleague's leave state differs, proving
        // this cannot pass by a fixture that never contains a colleague at all or by a fixture that happens to sit
        // below the size-3 floor.
        SubmitLeaveRequest request = validSubmit(null);
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(vacationType()));
        when(leaveRepository.hasSubmittedResignation(10L)).thenReturn(false);
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(request.startDate().getYear()), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);
        when(leaveRepository.findActiveDepartmentColleagues(10L)).thenReturn(List.of(20L, 30L));
        when(leaveRepository.findActiveLeaveSpans(eq(List.of(20L, 30L)), eq(request.startDate()), eq(request.endDate())))
            .thenReturn(List.of(
                new EmployeeLeaveSpan(20L, LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"))));
            // 30L has no leave at all -- still at work.
        when(leaveRepository.create(eq(10L), eq(10L), eq(request), any(BigDecimal.class), any(BigDecimal.class),
            any(BigDecimal.class), eq(request.startDate().getYear()),
            eq(LeaveStatus.SUBMITTED), any(BigDecimal.class), any(BigDecimal.class), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(321L);
        when(leaveRepository.findById(321L)).thenReturn(Optional.of(
            requestDto(321L, 10L, "SUBMITTED", request.startDate(), request.endDate(), "2.00", "0.00")));

        LeaveRequestDto result = leaveService.submit(request, user("employee", 10L));

        assertThat(result.status()).isEqualTo("SUBMITTED");
    }

    // §5 advance notice counts the employee's own WORKING days: weekends and
    // holidays between filing and the leave do not count, a six-day employee's Saturday does.
    // FIXED_NOW = Wed 2026-07-01 (day 0, not counted); the leave's first day the employee would
    // actually work may itself be the Nth working day.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}..{1}, {2}, holiday {3}, notice {4} -> warns {5}")
    @org.junit.jupiter.params.provider.CsvSource({
        // Thu 07-09 is the 6th Mon-Fri working day but 8 calendar days out -- the old count let it pass
        "2026-07-09, 2026-07-09, MON_FRI, , 7, true",
        "2026-07-10, 2026-07-10, MON_FRI, , 7, false",
        // a holiday on Thu 07-02 pushes the 7th working day to Mon 07-13
        "2026-07-10, 2026-07-10, MON_FRI, 2026-07-02, 7, true",
        // six-day schedule: Sat 07-04 counts, so Thu 07-09 is already the 7th working day
        "2026-07-09, 2026-07-09, MON_SAT, , 7, false",
        // Sat-Mon leave, Mon-Fri employee: Sat is not their working day, so the notice runs to Mon (3rd)
        "2026-07-04, 2026-07-06, MON_FRI, , 3, false",
        // same leave, six-day employee: Sat IS their working day, so the notice stops at Sat (3rd < 4)
        "2026-07-04, 2026-07-06, MON_SAT, , 4, true",
        // leave starting on a holiday: the notice runs to Fri 07-03, the first day they would work
        "2026-07-02, 2026-07-03, MON_FRI, 2026-07-02, 1, false",
        // starts TODAY on a holiday (not past day 0): Thu 07-02 is still the 1st working day ahead
        "2026-07-01, 2026-07-02, MON_FRI, 2026-07-01, 1, false",
    })
    void previewCountsAdvanceNoticeInTheEmployeesWorkingDays(
            LocalDate startDate, LocalDate endDate, String schedule, LocalDate holiday, int noticeDays,
            boolean expectWarning) {
        Predicate<LocalDate> scheduleDay = "MON_SAT".equals(schedule)
            ? date -> date.getDayOfWeek() != DayOfWeek.SUNDAY
            : LeaveDayMath::isWorkingDay;
        // Like LeaveRepository#workingDayPredicate, a holiday is only seen inside the range asked for.
        when(leaveRepository.workingDayPredicate(eq(10L), any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(1);
            LocalDate to = invocation.getArgument(2);
            boolean holidayInRange = holiday != null && !holiday.isBefore(from) && !holiday.isAfter(to);
            return (Predicate<LocalDate>) date -> scheduleDay.test(date) && !(holidayInRange && date.equals(holiday));
        });
        when(leaveRepository.employeeExists(10L)).thenReturn(true);
        when(leaveRepository.findLeaveType("VACATION")).thenReturn(Optional.of(
            new LeaveTypeDto("VACATION", "ลาพักร้อน", "Vacation leave", new BigDecimal("6.00"), false,
                null, noticeDays, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null, null, 0, null, false)));
        when(leaveRepository.sumUsedDays(eq(10L), eq("VACATION"), eq(2026), any(Collection.class)))
            .thenReturn(BigDecimal.ZERO);

        LeavePreviewDto preview = leaveService.preview(
            new LeavePreviewRequest("VACATION", startDate, endDate, null, null, false, false,
                LeavePreviewDepth.FULL, null),
            user("employee", 10L));

        List<String> codes = preview.ruleWarnings().stream().map(LeaveRuleWarningDto::code).toList();
        assertThat(codes.contains("ADVANCE_NOTICE")).as("ADVANCE_NOTICE in %s", codes).isEqualTo(expectWarning);
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
        // proratedFirstYear=false here: pro-ration is covered by its own dedicated fixture/tests below, which
        // construct a LeaveTypeDto with proratedFirstYear=true explicitly rather than making every existing VACATION
        // test in this class newly depend on a stubbed hire date.
        return new LeaveTypeDto("VACATION", "ลาพักร้อน", "Vacation leave", new BigDecimal("6.00"), false,
            null, 7, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null, null, 0, null, false);
    }

    // §5.1 SICK certificate + filing-window + no-certificate tolerance: certificateFilingWindowDays=3,
    // noCertificateMonthlyTolerance=3 -- the REAL seeded values, matching the
    // precedent personalTypeProratedFirstYear()/vacationTypeProratedFirstYear() set of using
    // real seeded figures once a fixture's whole purpose is to exercise that exact rule (as opposed
    // to vacationType()/leaveWithoutPayType()/ordinationType() above and below, which deliberately
    // use "no restriction" placeholders for fields not under test in THEIR tests).
    private LeaveTypeDto sickType() {
        return new LeaveTypeDto("SICK", "ลาป่วย", "Sick leave", new BigDecimal("30.00"), true,
            null, 0, 0, null, false, LeaveDayCountBasis.WORKING_DAYS, false, null, 3, 3, null, false);
    }

    private LeaveTypeDto ordinationType() {
        return new LeaveTypeDto("ORDINATION", "ลาอุปสมบท", "Ordination leave", new BigDecimal("60.00"), false,
            new BigDecimal("15.00"), 0, 12, null, true, LeaveDayCountBasis.WORKING_DAYS, false, null, null, 0, null, false);
    }

    // proratedFirstYear=false, maxConsecutiveDays=3.00 (deliberately NOT the real PERSONAL shape --
    // see vacationType()'s identical note above): this fixture exists ONLY to exercise the generic
    // max_consecutive_days mechanism itself (still a real, supported column -- just not
    // populated for the real PERSONAL row), for
    // submitRejects/AllowsARequestWithin/ExceedingTheMaxConsecutiveDaysCap and the probation tests
    // below, none of which are about the first-year cap. The real PERSONAL shape
    // (maxConsecutiveDays null, firstYearMaxDays 3.00) is personalTypeProratedFirstYear() below.
    private LeaveTypeDto personalTypeWithMaxConsecutive() {
        return new LeaveTypeDto("PERSONAL", "ลากิจ", "Personal leave", new BigDecimal("7.00"), false,
            null, 0, 0, new BigDecimal("3.00"), false, LeaveDayCountBasis.WORKING_DAYS, false, null, null, 0, null, false);
    }

    // §5.2/§5.3 pro-ration. Real completed-months-of-service arithmetic against
    // a real quota split (SICK/quota-exceeded-style scenarios) is proven against real dates in
    // LeaveTypeRuleIntegrationTest; these Mockito-level tests isolate #employeeAnnualQuota's decision
    // (which quota figure gets used) from the real calendar math LeaveDayMath performs elsewhere.

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
            // Sub-day leave: no test in this class needs a sub-day request, so
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
            // Paper-form contact block: no test in this class asserts on these, so they
            // stay null.
            null,
            null,
            null,
            null,
            null,
            // §5.2 purpose/emergency-filing: no test in this class asserts on these via the
            // helper, so they stay null/false -- tests that need a specific value construct their
            // own LeaveRequestDto or assert on the AutoRejectResult-driven repository calls instead.
            null,
            false,
            // Structured rejection outcome: same reasoning as purpose/
            // emergency-filing directly above -- no test in this class asserts on these via the
            // helper (LeaveServiceTest is Mockito-level and never exercises the real
            // LeaveRepository#recordAutoRejectReason write, so a helper-constructed DTO never carries
            // a genuine code/params pair anyway).
            null,
            Map.of(),
            // No test in this class asserts on canReview via the
            // helper (LeaveService#withCanReviewFlag runs on the RETURNED dto, not this fixture, and
            // this class is Mockito-level, so it never exercises that code path anyway).
            false,
            // Pending-approver info: same reasoning as canReview above -- these are computed by
            // LeaveRepository#mapRequest (a real SQL row mapper this Mockito-level class never
            // exercises), so no test in this class asserts on them via the helper.
            null,
            null,
            // WARN_UNPAID_*: same reasoning -- no test in this class asserts on these via the
            // helper.
            List.of(),
            BigDecimal.ZERO
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }
}
