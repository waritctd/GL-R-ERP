package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

class AttendanceServiceTest {
    private final AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    private final AppProperties properties = new AppProperties();
    // The daily roll-up is exercised for real in AttendanceDailyCalculatorTest and the
    // Testcontainers integration tests; here it is stubbed so these cases stay about punch
    // ingestion and scoping.
    private final AttendanceDailyService dailyService = mock(AttendanceDailyService.class);
    private final AttendanceService attendanceService =
        new AttendanceService(attendanceRepository, new AttendanceDatParser(), properties, dailyService);

    @BeforeEach
    void resetToken() {
        properties.getAttendance().setAgentToken(null);
    }

    @Test
    void insertsPunchWhenPerDeviceTokenMatches() {
        stubDevice(new AttendanceDeviceCredential("SHOWROOM", true, sha256Hex("secret")));
        when(attendanceRepository.upsertPunch(any(NormalizedAttendancePunch.class))).thenReturn(99L);

        AttendancePunchResponse response = attendanceService.receivePunch(validRequest(), "secret");

        assertThat(response.punchId()).isEqualTo(99L);
        assertThat(response.inserted()).isTrue();
        assertThat(response.status()).isEqualTo("inserted");
        verify(attendanceRepository).upsertPunch(any(NormalizedAttendancePunch.class));
    }

    @Test
    void treatsNullRepositoryReturnAsDuplicate() {
        stubDevice(new AttendanceDeviceCredential("SHOWROOM", true, sha256Hex("secret")));
        when(attendanceRepository.upsertPunch(any(NormalizedAttendancePunch.class))).thenReturn(null);

        AttendancePunchResponse response = attendanceService.receivePunch(validRequest(), "secret");

        assertThat(response.punchId()).isNull();
        assertThat(response.inserted()).isFalse();
        assertThat(response.status()).isEqualTo("duplicate");
    }

    @Test
    void rejectsInvalidPerDeviceTokenBeforeInsert() {
        stubDevice(new AttendanceDeviceCredential("SHOWROOM", true, sha256Hex("secret")));

        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "wrong"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(attendanceRepository, org.mockito.Mockito.never()).upsertPunch(any(NormalizedAttendancePunch.class));
    }

    @Test
    void rejectsPunchFromUnknownDevice() {
        when(attendanceRepository.findDeviceCredential("SHOWROOM_SC700")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "secret"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(attendanceRepository, org.mockito.Mockito.never()).upsertPunch(any(NormalizedAttendancePunch.class));
    }

    @Test
    void rejectsPunchFromInactiveDevice() {
        stubDevice(new AttendanceDeviceCredential("SHOWROOM", false, sha256Hex("secret")));

        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "secret"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsPunchWhenDeviceBelongsToAnotherSite() {
        stubDevice(new AttendanceDeviceCredential("WAREHOUSE", true, sha256Hex("secret")));

        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "secret"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void fallsBackToSharedTokenWhenDeviceHasNoTokenYet() {
        stubDevice(new AttendanceDeviceCredential("SHOWROOM", true, null));
        properties.getAttendance().setAgentToken("shared-secret");
        when(attendanceRepository.upsertPunch(any(NormalizedAttendancePunch.class))).thenReturn(5L);

        AttendancePunchResponse response = attendanceService.receivePunch(validRequest(), "shared-secret");

        assertThat(response.inserted()).isTrue();
    }

    @Test
    void rejectsPunchWhenDeviceUnprovisionedAndNoSharedToken() {
        stubDevice(new AttendanceDeviceCredential("SHOWROOM", true, null));

        assertThatThrownBy(() -> attendanceService.receivePunch(validRequest(), "anything"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(attendanceRepository, org.mockito.Mockito.never()).upsertPunch(any(NormalizedAttendancePunch.class));
    }

    @Test
    void rotateDeviceTokenReturnsPlaintextAndStoresOnlyItsHash() {
        when(attendanceRepository.updateAgentTokenHash(
                org.mockito.ArgumentMatchers.eq("SHOWROOM_SC700"),
                org.mockito.ArgumentMatchers.anyString(),
                any(java.time.OffsetDateTime.class))).thenReturn(1);

        // lower-case input is normalized to the stored upper-case device_code
        RotateAgentTokenResponse response = attendanceService.rotateDeviceToken("showroom_sc700");

        assertThat(response.deviceCode()).isEqualTo("SHOWROOM_SC700");
        assertThat(response.agentToken()).matches("^[0-9a-f]{64}$");
        assertThat(response.rotatedAt()).isNotNull();

        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(attendanceRepository).updateAgentTokenHash(
            org.mockito.ArgumentMatchers.eq("SHOWROOM_SC700"), hash.capture(), any(java.time.OffsetDateTime.class));
        // The stored value is the hash of the returned token, never the token itself.
        assertThat(hash.getValue()).isEqualTo(sha256Hex(response.agentToken()));
        assertThat(hash.getValue()).isNotEqualTo(response.agentToken());
    }

    @Test
    void rotateDeviceTokenRejectsUnknownDevice() {
        when(attendanceRepository.updateAgentTokenHash(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                any(java.time.OffsetDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> attendanceService.rotateDeviceToken("NOPE"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void stubDevice(AttendanceDeviceCredential device) {
        when(attendanceRepository.findDeviceCredential("SHOWROOM_SC700")).thenReturn(Optional.of(device));
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        // Two valid rows parse; the batch insert reports 1 newly inserted (the
        // other is a dedup skip). The broken row is an error, not a punch.
        when(attendanceRepository.batchInsertPunches(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(1);

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
        verify(attendanceRepository).batchInsertPunches(org.mockito.ArgumentMatchers.anyList());
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

        verify(attendanceRepository).findPunches(new AttendancePunchFilter(10L, null, LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), 50));
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

        verify(attendanceRepository).findPunches(new AttendancePunchFilter(null, null, LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), 500));
    }

    @Test
    void divisionManagersListTheirDivisionPunches() {
        when(attendanceRepository.findPunches(any(AttendancePunchFilter.class))).thenReturn(List.of());

        attendanceService.listPunches(
            manager("employee", 10L, 5L), LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), null, 500);

        // Scoped to the manager's ฝ่าย (division 5), not just their own employee id.
        verify(attendanceRepository).findPunches(
            new AttendancePunchFilter(null, 5L, LocalDate.parse("2020-11-01"), LocalDate.parse("2020-11-30"), 500));
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

    @Test
    void hrCanNarrowTheDayViewToOneDivision() {
        AttendanceScope scope = attendanceService.resolveScope(user("hr", 1L), null, 42L);

        assertThat(scope.divisionId()).isEqualTo(42L);
        assertThat(scope.employeeId()).isNull();
    }

    /**
     * The division filter is a convenience for roles that can already see everything — it must never
     * become a way to look sideways. A ฝ่าย manager's division comes from their own principal, so a
     * requested one is ignored outright rather than merged.
     */
    @Test
    void aManagerCannotUseTheDivisionFilterToSeeAnotherDivision() {
        AttendanceScope scope = attendanceService.resolveScope(manager("employee", 5L, 7L), null, 42L);

        assertThat(scope.divisionId()).isEqualTo(7L);
    }

    @Test
    void anEmployeeCannotUseTheDivisionFilterToWidenTheirScope() {
        AttendanceScope scope = attendanceService.resolveScope(user("employee", 5L), null, 42L);

        assertThat(scope.divisionId()).isNull();
        assertThat(scope.employeeId()).isEqualTo(5L);
    }

    private UserPrincipal user(String role, Long employeeId) {
        return new UserPrincipal(1L, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager(String role, Long employeeId, Long divisionId) {
        return new UserPrincipal(1L, role + "@glr.co.th", role, role, employeeId, true, LocalDate.now(), false, divisionId, true);
    }
}
