package th.co.glr.hr.attendance.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;

class AttendanceCorrectionServiceTest {
    private static final ZoneOffset BANGKOK_OFFSET = ZoneOffset.ofHours(7);
    private static final LocalDate PAST_DATE = LocalDate.of(2020, 1, 6); // a Monday

    private final AttendanceCorrectionRepository repository = mock(AttendanceCorrectionRepository.class);
    private final AttendanceDailyService dailyService = mock(AttendanceDailyService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final CeoApproverRepository ceoApprovers = mock(CeoApproverRepository.class);
    private final AttendanceCorrectionService service = new AttendanceCorrectionService(
        repository, dailyService, auditService, notificationService, ceoApprovers);

    // --- submit: happy path ---------------------------------------------------------------------

    @Test
    void submitRefusesADuplicateOpenRequestForTheSameDay() {
        when(repository.employeeExists(10L)).thenReturn(true);
        when(repository.hasOpenRequest(10L, PAST_DATE)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(checkInRequest(), employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(repository, never()).create(anyLong(), anyLong(), any());
    }

    // submit: CHECK-constraint-consistency validated in Java, before the DB ever sees it ------- Mirrors
    // chk_attendance_correction_fields_match_type.

    // --- cancel ----------------------------------------------------------------------------------

    @Test
    void requesterCanCancelTheirOwnSubmittedRequest() {
        when(repository.findById(55L)).thenReturn(Optional.of(dto(55L, 10L, "SUBMITTED")))
            .thenReturn(Optional.of(dto(55L, 10L, "CANCELLED")));
        when(repository.cancel(55L)).thenReturn(1);

        AttendanceCorrectionRequestDto result = service.cancel(55L, employee(10L));

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(repository).cancel(55L);
    }

    @Test
    void anEmployeeCannotCancelSomeoneElsesRequest() {
        when(repository.findById(55L)).thenReturn(Optional.of(dto(55L, 10L, "SUBMITTED")));

        assertThatThrownBy(() -> service.cancel(55L, employee(11L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).cancel(anyLong());
    }

    // notification deep links ------------------------------------------ notificationService was already mocked
    // above but no test here ever verified a single notify call.

    // --- helpers ------------------------------------------------------------

    private SubmitAttendanceCorrectionRequest checkInRequest() {
        return new SubmitAttendanceCorrectionRequest(
            PAST_DATE, AttendanceCorrectionType.CHECK_IN,
            PAST_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้วตอนเข้างาน");
    }

    private UserPrincipal employee(Long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, 1L, false);
    }

    private UserPrincipal ceo(Long employeeId) {
        return new UserPrincipal(500L, "ceo@glr.co.th", "ceo", "ceo", employeeId, true,
            LocalDate.now(), false, null, false);
    }

    private AttendanceCorrectionRequestDto dto(long id, long employeeId, String status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AttendanceCorrectionRequestDto(
            id, employeeId, "S001", "ทดสอบ ทดสอบ", PAST_DATE, "CHECK_IN",
            PAST_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้ว", status,
            employeeId, "ทดสอบ ทดสอบ", now, null, null, null, null, null, now, now, false);
    }
}
