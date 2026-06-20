package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.profile.ProfileRequestRepository;

class EmployeeServiceTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final EmployeeService service = new EmployeeService(employees, profileRequests);

    @Test
    void countsPendingRequestsOnlyForFilteredEmployees() {
        EmployeeFilter filter = new EmployeeFilter("finance", null, null, null, true);
        when(employees.findEmployees(filter, false)).thenReturn(List.of(employee(1L), employee(2L)));
        when(profileRequests.pendingCountsByEmployeeIds(List.of(1L, 2L))).thenReturn(Map.of(2L, 3));

        List<EmployeeDto> result = service.list(filter);

        assertThat(result).extracting(EmployeeDto::pendingRequestCount).containsExactly(0, 3);
        verify(profileRequests).pendingCountsByEmployeeIds(List.of(1L, 2L));
    }

    @Test
    void skipsPendingRequestCountQueryWhenNoEmployeesMatch() {
        EmployeeFilter filter = new EmployeeFilter("missing", null, null, null, true);
        when(employees.findEmployees(filter, false)).thenReturn(List.of());

        assertThat(service.list(filter)).isEmpty();

        verify(profileRequests, never()).pendingCountsByEmployeeIds(anyList());
    }

    private EmployeeDto employee(long id) {
        return new EmployeeDto(
            id,
            "GLR-" + id,
            "BC-" + id,
            "Employee " + id,
            "Employee " + id,
            "Emp",
            "E",
            "#e0e7ff",
            "#4338ca",
            "นาย",
            "ไม่ระบุ",
            null,
            null,
            "ไทย",
            "โสด",
            "employee" + id + "@glr.co.th",
            "0800000000",
            "SAL",
            "ฝ่ายขาย",
            "Sales",
            "ขายปลีก",
            "เจ้าหน้าที่",
            "Officer",
            "O2",
            "สำนักงานใหญ่ กรุงเทพฯ",
            "ACT",
            "ทำงานปกติ",
            "success",
            true,
            "รายเดือน",
            BigDecimal.ZERO,
            LocalDate.of(2024, 1, 1),
            null,
            "-",
            "",
            "",
            new AddressDto("", "", "", ""),
            new EmergencyContactDto("", "", ""),
            List.of(),
            List.of(),
            SensitiveDto.empty(),
            0
        );
    }
}
