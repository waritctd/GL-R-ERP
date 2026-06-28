package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

class AttendanceServiceTest {
    private final AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    private final AppProperties properties = new AppProperties();
    private final AttendanceService attendanceService = new AttendanceService(attendanceRepository, properties);

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
}
