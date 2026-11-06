package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;

class ViewAsServiceTest {
    private final EmployeeAuthRepository employees = mock(EmployeeAuthRepository.class);

    private ViewAsService service(String code) {
        return new ViewAsService(employees, mock(AuditService.class), code);
    }

    private static EmployeeLoginRecord record(long id, String code, boolean active) {
        return new EmployeeLoginRecord(id, code, "e@example.test", "Name", active, 1L, "IT", null, null,
            java.time.LocalDate.now(), null, false, false);
    }

    @Test
    void blankCode_isFalse_andNeverQueries() {
        assertThat(service("  ").mayViewAs(1L)).isFalse();
        verifyNoInteractions(employees);
    }

    @Test
    void owner_isALiveCheck_failingClosedWhenTheCodeMovesOrTheAccountGoesInactive() {
        EmployeeLoginRecord ok = record(5L, " OWN ", true);
        when(employees.findByEmployeeId(5L)).thenReturn(Optional.of(ok));
        ViewAsService service = service("OWN");
        assertThat(service.mayViewAs(5L)).isTrue();

        EmployeeLoginRecord moved = record(5L, "OTHER", true);
        when(employees.findByEmployeeId(5L)).thenReturn(Optional.of(moved));
        assertThat(service.mayViewAs(5L)).isFalse();

        EmployeeLoginRecord off = record(5L, "OWN", false);
        when(employees.findByEmployeeId(5L)).thenReturn(Optional.of(off));
        assertThat(service.mayViewAs(5L)).isFalse();
    }
}
