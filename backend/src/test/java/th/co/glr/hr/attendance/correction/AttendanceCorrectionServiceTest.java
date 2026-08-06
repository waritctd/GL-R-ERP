package th.co.glr.hr.attendance.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import th.co.glr.hr.notification.NotificationService;

class AttendanceCorrectionServiceTest {
    private static final ZoneOffset BANGKOK_OFFSET = ZoneOffset.ofHours(7);
    private static final LocalDate PAST_DATE = LocalDate.of(2020, 1, 6); // a Monday

    private final AttendanceCorrectionRepository repository = mock(AttendanceCorrectionRepository.class);
    private final AttendanceDailyService dailyService = mock(AttendanceDailyService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AttendanceCorrectionService service = new AttendanceCorrectionService(
        repository, dailyService, auditService, notificationService);

    // --- submit: happy path ---------------------------------------------------------------------

    @Test
    void employeeSubmitsForThemselvesFromTheSession() {
        when(repository.employeeExists(10L)).thenReturn(true);
        when(repository.hasOpenRequest(10L, PAST_DATE)).thenReturn(false);
        when(repository.create(eq(10L), eq(10L), any())).thenReturn(55L);
        when(repository.findById(55L)).thenReturn(Optional.of(dto(55L, 10L, "SUBMITTED")));

        AttendanceCorrectionRequestDto result = service.submit(checkInRequest(), employee(10L));

        assertThat(result.id()).isEqualTo(55L);
        verify(repository).create(eq(10L), eq(10L), any());
        verify(auditService).record(any(), eq("SUBMIT_ATTENDANCE_CORRECTION_REQUEST"),
            eq("attendance_correction_request"), eq(55L), eq(null), any());
    }

    @Test
    void employeeCannotSubmitWithoutASession() {
        assertThatThrownBy(() -> service.submit(checkInRequest(), employee(null)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).create(anyLong(), anyLong(), any());
    }

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

    @Test
    void submitRefusesAFutureWorkDate() {
        when(repository.employeeExists(10L)).thenReturn(true);
        LocalDate future = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(1);
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            future, AttendanceCorrectionType.CHECK_IN,
            future.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้ว");

        assertThatThrownBy(() -> service.submit(request, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).create(anyLong(), anyLong(), any());
    }

    // --- submit: CHECK-constraint-consistency validated in Java, before the DB ever sees it -------
    // Mirrors chk_attendance_correction_fields_match_type (V135) -- see
    // AttendanceCorrectionService#validateRequestShape's javadoc for why this must reject with a
    // clear 400 rather than surface the constraint violation as an opaque 500.

    @Test
    void checkInTypeWithNullRequestedCheckInIsRejectedBeforeTheDatabase() {
        when(repository.employeeExists(10L)).thenReturn(true);
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            PAST_DATE, AttendanceCorrectionType.CHECK_IN, null, null, "ลืมสแกนนิ้ว");

        assertThatThrownBy(() -> service.submit(request, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void checkOutTypeWithNullRequestedCheckOutIsRejected() {
        when(repository.employeeExists(10L)).thenReturn(true);
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            PAST_DATE, AttendanceCorrectionType.CHECK_OUT, null, null, "ลืมสแกนนิ้ว");

        assertThatThrownBy(() -> service.submit(request, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void bothTypeRequiresBothTimes() {
        when(repository.employeeExists(10L)).thenReturn(true);
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            PAST_DATE, AttendanceCorrectionType.BOTH,
            PAST_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้วทั้งเข้าและออก");

        assertThatThrownBy(() -> service.submit(request, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void checkInTypeWithAnExtraRequestedCheckOutIsRejected() {
        when(repository.employeeExists(10L)).thenReturn(true);
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            PAST_DATE, AttendanceCorrectionType.CHECK_IN,
            PAST_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET),
            PAST_DATE.atTime(17, 30).atOffset(BANGKOK_OFFSET), "ลืมสแกนนิ้ว");

        assertThatThrownBy(() -> service.submit(request, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkOutBeforeCheckInIsRejectedForBothType() {
        when(repository.employeeExists(10L)).thenReturn(true);
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            PAST_DATE, AttendanceCorrectionType.BOTH,
            PAST_DATE.atTime(17, 30).atOffset(BANGKOK_OFFSET),
            PAST_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET), "เวลาสลับกัน");

        assertThatThrownBy(() -> service.submit(request, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

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

    @Test
    void cancelRefusesARequestThatIsAlreadyDecided() {
        when(repository.findById(55L)).thenReturn(Optional.of(dto(55L, 10L, "APPROVED")));

        assertThatThrownBy(() -> service.cancel(55L, employee(10L)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(repository, never()).cancel(anyLong());
    }

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

    private AttendanceCorrectionRequestDto dto(long id, long employeeId, String status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AttendanceCorrectionRequestDto(
            id, employeeId, "S001", "ทดสอบ ทดสอบ", PAST_DATE, "CHECK_IN",
            PAST_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้ว", status,
            employeeId, "ทดสอบ ทดสอบ", now, null, null, null, null, null, now, now, false);
    }
}
