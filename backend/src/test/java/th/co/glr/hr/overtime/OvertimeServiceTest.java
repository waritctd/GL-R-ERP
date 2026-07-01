package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

class OvertimeServiceTest {
    private final OvertimeRepository overtimeRepository = mock(OvertimeRepository.class);
    private final OvertimeService overtimeService = new OvertimeService(overtimeRepository);

    @Test
    void employeesCanSubmitOwnOvertime() {
        SubmitOvertimeRequest request = validSubmit(null);
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(LocalDate.parse("2026-07-01"))))
            .thenReturn(55L);
        when(overtimeRepository.findById(55L)).thenReturn(Optional.of(requestDto(55L, 10L, "SUBMITTED")));

        OvertimeRequestDto result = overtimeService.submit(request, user("employee", 10L));

        assertThat(result.id()).isEqualTo(55L);
        verify(overtimeRepository).create(eq(10L), eq(10L), eq(request), eq(120), eq(OvertimeDayType.WORKDAY), eq(LocalDate.parse("2026-07-01")));
    }

    @Test
    void employeesCannotSubmitForAnotherEmployee() {
        when(overtimeRepository.findEmployeeAccess(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overtimeService.submit(validSubmit(11L), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(overtimeRepository).findEmployeeAccess(11L);
    }

    @Test
    void nonApproversCanOnlyListOwnRequests() {
        when(overtimeRepository.findEmployeeAccess(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> overtimeService.list(
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
        when(overtimeRepository.findRequests(any(OvertimeFilter.class))).thenReturn(List.of());

        overtimeService.list(
            user("hr", 10L),
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-30"),
            null,
            "submitted"
        );

        verify(overtimeRepository).findRequests(new OvertimeFilter(
            null,
            null,
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-30"),
            OvertimeStatus.SUBMITTED
        ));
    }

    @Test
    void directManagersCanSubmitRetroactiveOvertimeForReports() {
        SubmitOvertimeRequest request = new SubmitOvertimeRequest(
            10L,
            LocalDate.parse("2026-06-15"),
            OffsetDateTime.parse("2026-06-15T18:00:00+07:00"),
            OffsetDateTime.parse("2026-06-15T20:00:00+07:00"),
            "HOLIDAY",
            "Urgent delivery"
        );
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, true)));
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);
        when(overtimeRepository.create(eq(10L), eq(99L), eq(request), eq(120), eq(OvertimeDayType.HOLIDAY), eq(LocalDate.parse("2026-06-01"))))
            .thenReturn(56L);
        when(overtimeRepository.findById(56L)).thenReturn(Optional.of(requestDto(56L, 10L, "SUBMITTED")));

        OvertimeRequestDto result = overtimeService.submit(request, user("employee", 99L));

        assertThat(result.id()).isEqualTo(56L);
        verify(overtimeRepository).create(eq(10L), eq(99L), eq(request), eq(120), eq(OvertimeDayType.HOLIDAY), eq(LocalDate.parse("2026-06-01")));
    }

    @Test
    void employeesCannotSubmitRetroactiveOvertimeForThemselves() {
        SubmitOvertimeRequest request = new SubmitOvertimeRequest(
            null,
            LocalDate.parse("2026-06-15"),
            OffsetDateTime.parse("2026-06-15T18:00:00+07:00"),
            OffsetDateTime.parse("2026-06-15T20:00:00+07:00"),
            "WORKDAY",
            "Late request"
        );
        when(overtimeRepository.employeeExists(10L)).thenReturn(true);

        assertThatThrownBy(() -> overtimeService.submit(request, user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approveCalculatesPayableMinutesFromAttendanceOverlap() {
        OvertimeRequestDto submitted = requestDto(77L, 10L, "SUBMITTED");
        OvertimeRequestDto approved = requestDto(77L, 10L, "APPROVED");
        when(overtimeRepository.findById(77L))
            .thenReturn(Optional.of(submitted))
            .thenReturn(Optional.of(approved));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, true)));
        when(overtimeRepository.findAttendanceBounds(eq(10L), any(OffsetDateTime.class), any(OffsetDateTime.class)))
            .thenReturn(Optional.of(new OvertimeAttendanceBounds(
                OffsetDateTime.parse("2026-07-15T08:05:00+07:00"),
                OffsetDateTime.parse("2026-07-15T19:40:00+07:00")
            )));
        when(overtimeRepository.approve(eq(77L), eq(99L), any(OvertimeCalculation.class), eq("ok")))
            .thenReturn(1);

        overtimeService.approve(77L, new ReviewOvertimeRequest("ok"), user("employee", 99L));

        ArgumentCaptor<OvertimeCalculation> captor = ArgumentCaptor.forClass(OvertimeCalculation.class);
        verify(overtimeRepository).approve(eq(77L), eq(99L), captor.capture(), eq("ok"));
        OvertimeCalculation calculation = captor.getValue();
        assertThat(calculation.actualStartAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T18:00:00+07:00"));
        assertThat(calculation.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T19:40:00+07:00"));
        assertThat(calculation.actualMinutes()).isEqualTo(100);
        assertThat(calculation.payableMinutes()).isEqualTo(100);
    }

    @Test
    void employeesCannotApproveOvertime() {
        when(overtimeRepository.findById(77L)).thenReturn(Optional.of(requestDto(77L, 10L, "SUBMITTED")));
        when(overtimeRepository.findEmployeeAccess(10L)).thenReturn(Optional.of(new OvertimeEmployeeAccess(10L, 99L, true)));

        assertThatThrownBy(() -> overtimeService.approve(77L, new ReviewOvertimeRequest(null), user("employee", 10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private SubmitOvertimeRequest validSubmit(Long employeeId) {
        return new SubmitOvertimeRequest(
            employeeId,
            LocalDate.parse("2026-07-15"),
            OffsetDateTime.parse("2026-07-15T18:00:00+07:00"),
            OffsetDateTime.parse("2026-07-15T20:00:00+07:00"),
            "WORKDAY",
            "Customer shipment"
        );
    }

    private OvertimeRequestDto requestDto(long id, long employeeId, String status) {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-15T18:00:00+07:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-07-15T20:00:00+07:00");
        return new OvertimeRequestDto(
            id,
            employeeId,
            "EMP001",
            "Test Employee",
            LocalDate.parse("2026-07-15"),
            start,
            end,
            120,
            "WORKDAY",
            new BigDecimal("1.50"),
            "Customer shipment",
            status,
            null,
            null,
            0,
            0,
            null,
            LocalDate.parse("2026-06-01"),
            employeeId,
            "Test Employee",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            null,
            null,
            null,
            null,
            null,
            99L,
            "Test Manager",
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00"),
            OffsetDateTime.parse("2026-06-14T10:00:00+07:00")
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(employeeId == null ? 1L : employeeId, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false);
    }
}
