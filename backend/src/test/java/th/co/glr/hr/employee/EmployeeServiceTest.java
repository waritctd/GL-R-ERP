package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.TemporaryPasswordGenerator;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.profile.ProfileRequestRepository;

class EmployeeServiceTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final EmployeeService service = new EmployeeService(employees, profileRequests, auditService,
        mock(EmployeeAuthRepository.class), new TemporaryPasswordGenerator(), mock(PasswordEncoder.class),
        mock(PayrollRepository.class));

    @Test
    void deniesCrossUserEmployeeDetailBeforeRepositoryLookup() {
        UserPrincipal user = new UserPrincipal(
            8L, "e@glr.co.th", "E", "employee", 6L, true, LocalDate.now(), false, null, false);

        assertThatThrownBy(() -> service.get(5L, user))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(employees, profileRequests);
    }

    @Test
    void hrCanFetchFullEmployeeDetail() {
        when(employees.findEmployeeById(5L, true)).thenReturn(Optional.of(employee(5L)));
        when(profileRequests.pendingCountByEmployee(5L)).thenReturn(0);

        EmployeeDto result = service.get(5L,
            new UserPrincipal(9L, "hr2@glr.co.th", "HR2", "hr", 9L, true, LocalDate.now(), false, null, false));

        assertThat(result.salary()).isEqualByComparingTo("25000.00");
        assertThat(result.salaryHistory()).hasSize(1);
        assertThat(result.bank()).isEqualTo("ธนาคารทดสอบ");
        assertThat(result.bankAccount()).isEqualTo("123-4-56789-0");
        assertThat(result.payType()).isEqualTo("รายเดือน");
        assertThat(result.sensitive().nationalId()).isEqualTo("1101700000005");
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
            new BigDecimal("25000.00"),
            new BigDecimal("0.00"),
            null, // withholdingTaxOverride
            LocalDate.of(2024, 1, 1),
            null,
            "-",
            "ธนาคารทดสอบ",
            "123-4-56789-0",
            new AddressDto("", "", "", ""),
            new EmergencyContactDto("", "", ""),
            List.of(),
            List.of(new SalaryHistoryDto(LocalDate.of(2024, 1, 1), null, new BigDecimal("25000.00"), "Initial salary")),
            new SensitiveDto("1101700000005", "TAX-5", "SSO-5", "โรงพยาบาลทดสอบ", "PF-5"),
            0
        );
    }
}
