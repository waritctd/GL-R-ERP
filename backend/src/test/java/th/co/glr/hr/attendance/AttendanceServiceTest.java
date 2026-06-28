package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

class AttendanceServiceTest {
    private final AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    private final AppProperties properties = new AppProperties();
    private final AttendanceService attendanceService = new AttendanceService(attendanceRepository, new AttendanceDatParser(), properties);

    @BeforeEach
    void resetToken() {
        properties.getAttendance().setAgentToken(null);
    }

    @Test
    void insertsPunchWhenAgentTokenMatches() {
        properties.getAttendance().setAgentToken("secret");
        when(attendanceRepository.upsertPunch(any(NormalizedAttendancePunch.class))).thenReturn(99L);

        AttendancePunchResponse response = attendanceService.receivePunch(validRequest(), "secret");

        assertThat(response.punchId()).isEqualTo(99L);
        assertThat(response.inserted()).isTrue();
        assertThat(response.status()).isEqualTo("inserted");
        verify(attendanceRepository).upsertPunch(any(NormalizedAttendancePunch.class));
    }

    @Test
    void treatsNullRepositoryReturnAsDuplicate() {
        properties.getAttendance().setAgentToken("secret");
        when(attendanceRepository.upsertPunch(any(NormalizedAttendancePunch.class))).thenReturn(null);

        AttendancePunchResponse response = attendanceService.receivePunch(validRequest(), "secret");

        assertThat(response.punchId()).isNull();
        assertThat(response.inserted()).isFalse();
        assertThat(response.status()).isEqualTo("duplicate");
    }

    @Test
    void rejectsInvalidAgentTokenBeforeRepositoryCall() {
        properties.getAttendance().setAgentToken("secret");

        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "wrong"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(attendanceRepository);
    }

    @Test
    void rejectsRequestsWhenAgentTokenIsNotConfigured() {
        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "secret"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verifyNoInteractions(attendanceRepository);
    }

    @Test
    void importsDatFileAndCountsInsertedSkippedAndErrors() {
        when(attendanceRepository.findImportByHash(any(String.class))).thenReturn(Optional.empty());
        when(attendanceRepository.createImportFile(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(7L);
        when(attendanceRepository.upsertPunch(any(NormalizedAttendancePunch.class)))
            .thenReturn(101L)
            .thenReturn(null);

        AttendanceImportResponse response = attendanceService.importDatFile(new AttendanceDatImportRequest(
            "SHOWROOM",
            "SHOWROOM_SC700",
            "1_attlog.dat",
            """
            10012\t2020-11-02 10:33:55\t1\t0\t0\t0\r
            10034\t2020-11-02 10:40:02\t1\t0\t0\t0\r
            broken row\r
            """
        ), user("hr", 10L));

        assertThat(response.importId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo("imported");
        assertThat(response.rowCount()).isEqualTo(3);
        assertThat(response.insertedPunchCount()).isEqualTo(1);
        assertThat(response.skippedPunchCount()).isEqualTo(1);
        assertThat(response.errorCount()).isEqualTo(1);
        verify(attendanceRepository, times(2)).upsertPunch(any(NormalizedAttendancePunch.class));
        verify(attendanceRepository).updateImportCounts(7L, 3, 1, 1, 1);
    }

    @Test
    void returnsDuplicateImportWithoutReprocessingFile() {
        AttendanceImportResponse duplicate = new AttendanceImportResponse(8L, "duplicate_file", 10, 9, 1, 0);
        when(attendanceRepository.findImportByHash(any(String.class))).thenReturn(Optional.of(duplicate));

        AttendanceImportResponse response = attendanceService.importDatFile(new AttendanceDatImportRequest(
            "SHOWROOM",
            "SHOWROOM_SC700",
            "1_attlog.dat",
            "10012\t2020-11-02 10:33:55\t1\t0\t0\t0\r\n"
        ), user("hr", 10L));

        assertThat(response).isSameAs(duplicate);
        verify(attendanceRepository).findImportByHash(any(String.class));
    }

    @Test
    void employeesCanOnlyListTheirOwnPunches() {
        when(attendanceRepository.findPunches(any(AttendancePunchFilter.class))).thenReturn(List.of());

        attendanceService.listPunches(user("employee", 10L), LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), null, 50);

        verify(attendanceRepository).findPunches(new AttendancePunchFilter(10L, LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), 50));
    }

    @Test
    void employeesCannotListOtherEmployeePunches() {
        assertThatThrownBy(() -> attendanceService.listPunches(
                user("employee", 10L),
                LocalDate.parse("2020-11-01"),
                LocalDate.parse("2020-11-30"),
                11L,
                50))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void hrCanListAllPunches() {
        when(attendanceRepository.findPunches(any(AttendancePunchFilter.class))).thenReturn(List.of());

        attendanceService.listPunches(user("hr", 10L), LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), null, 500);

        verify(attendanceRepository).findPunches(new AttendancePunchFilter(null, LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), 500));
    }

    private AttendancePunchRequest validRequest() {
        return new AttendancePunchRequest(
            "SHOWROOM",
            "SHOWROOM_SC700",
            "10012",
            OffsetDateTime.parse("2026-06-28T08:15:30+07:00"),
            null,
            (short) 1,
            (short) 0,
            "0",
            "0",
            "BIOMETRIC",
            "LIVE_CAPTURE",
            Map.of("user_id", "10012")
        );
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(1L, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now());
    }
}
