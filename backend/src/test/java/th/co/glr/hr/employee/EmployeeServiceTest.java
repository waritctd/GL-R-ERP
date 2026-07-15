package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.TemporaryPasswordGenerator;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.Page;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.profile.ProfileRequestRepository;

class EmployeeServiceTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final ProfileRequestRepository profileRequests = mock(ProfileRequestRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final EmployeeService service = new EmployeeService(employees, profileRequests, auditService,
        mock(EmployeeAuthRepository.class), new TemporaryPasswordGenerator(), mock(PasswordEncoder.class));

    @Test
    void countsPendingRequestsOnlyForFilteredEmployees() {
        EmployeeFilter filter = new EmployeeFilter("finance", null, null, null, true);
        when(employees.findEmployees(filter, false)).thenReturn(List.of(employee(1L), employee(2L)));
        when(profileRequests.pendingCountsByEmployeeIds(List.of(1L, 2L))).thenReturn(Map.of(2L, 3));
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        try {
            List<EmployeeDto> result = service.list(filter, hrUser());

            assertThat(result).extracting(EmployeeDto::pendingRequestCount).containsExactly(0, 3);
            assertThat(appender.list).anyMatch(event ->
                event.getFormattedMessage().contains("LIST_EMPLOYEE_SALARY_SUMMARY")
                    && event.getFormattedMessage().contains("targetEmployeeIds=\"1,2\"")
                    && event.getFormattedMessage().contains("fields=\"current_salary\""));
            verify(profileRequests).pendingCountsByEmployeeIds(List.of(1L, 2L));
        } finally {
            detachAuditAppender(appender);
        }
    }

    @Test
    void skipsPendingRequestCountQueryWhenNoEmployeesMatch() {
        EmployeeFilter filter = new EmployeeFilter("missing", null, null, null, true);
        when(employees.findEmployees(filter, false)).thenReturn(List.of());

        assertThat(service.list(filter, hrUser())).isEmpty();

        verify(profileRequests, never()).pendingCountsByEmployeeIds(anyList());
    }

    @Test
    void listPageSkipsCountQueryWhenResultFitsFirstPage() {
        EmployeeFilter filter = new EmployeeFilter(null, null, null, null, null);
        PageRequest page = PageRequest.resolve(0, 50);
        when(employees.findEmployees(filter, false, page)).thenReturn(List.of(employee(1L), employee(2L)));
        when(profileRequests.pendingCountsByEmployeeIds(List.of(1L, 2L))).thenReturn(Map.of());

        Page<EmployeeDto> result = service.listPage(filter, hrUser(), page);

        assertThat(result.items()).hasSize(2);
        assertThat(result.total()).isEqualTo(2);
        verify(employees, never()).countEmployees(filter);
    }

    @Test
    void listPageRunsCountQueryWhenPageIsFull() {
        EmployeeFilter filter = new EmployeeFilter(null, null, null, null, null);
        PageRequest page = PageRequest.resolve(0, 2);
        when(employees.findEmployees(filter, false, page)).thenReturn(List.of(employee(1L), employee(2L)));
        when(profileRequests.pendingCountsByEmployeeIds(List.of(1L, 2L))).thenReturn(Map.of());
        when(employees.countEmployees(filter)).thenReturn(137);

        Page<EmployeeDto> result = service.listPage(filter, hrUser(), page);

        assertThat(result.total()).isEqualTo(137);
        verify(employees).countEmployees(filter);
    }

    @Test
    void logsAuditEventWhenHrViewsSensitiveDetail() {
        when(employees.findEmployeeById(5L, true)).thenReturn(Optional.of(employee(5L)));
        when(profileRequests.pendingCountByEmployee(5L)).thenReturn(0);
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        service.get(5L, new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 10L, true, LocalDate.now(), false, null, false));

        try {
            assertThat(appender.list).anyMatch(event ->
                event.getFormattedMessage().contains("VIEW_EMPLOYEE_DETAIL")
                    && event.getFormattedMessage().contains("targetEmployeeId=5")
                    && event.getFormattedMessage().contains("fields=\"restricted_pii,current_salary,salary_history\""));
        } finally {
            detachAuditAppender(appender);
        }
    }

    @Test
    void employeesCanFetchOwnSafeProfileWithoutPayrollOrSensitiveFields() {
        when(employees.findEmployeeById(5L, false)).thenReturn(Optional.of(employee(5L)));
        when(profileRequests.pendingCountByEmployee(5L)).thenReturn(2);

        EmployeeDto result = service.get(5L,
            new UserPrincipal(8L, "e@glr.co.th", "E", "employee", 5L, true, LocalDate.now(), false, null, false));

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.email()).isEqualTo("employee5@glr.co.th");
        assertThat(result.currentAddress()).isNotNull();
        assertThat(result.pendingRequestCount()).isEqualTo(2);
        assertThat(result.salary()).isNull();
        assertThat(result.salaryHistory()).isEmpty();
        assertThat(result.bank()).isNull();
        assertThat(result.bankAccount()).isNull();
        assertThat(result.payType()).isNull();
        assertThat(result.sensitive()).isNull();
    }

    @Test
    void doesNotLogAuditEventForSelfServiceView() {
        when(employees.findEmployeeById(5L, false)).thenReturn(Optional.of(employee(5L)));
        when(profileRequests.pendingCountByEmployee(5L)).thenReturn(0);
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        service.get(5L, new UserPrincipal(8L, "e@glr.co.th", "E", "employee", 5L, true, LocalDate.now(), false, null, false));

        try {
            assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("VIEW_EMPLOYEE_DETAIL"));
        } finally {
            detachAuditAppender(appender);
        }
    }

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

    private ListAppender<ILoggingEvent> attachAuditAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("th.co.glr.hr.audit")).addAppender(appender);
        return appender;
    }

    private void detachAuditAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("th.co.glr.hr.audit")).detachAppender(appender);
    }

    private UserPrincipal hrUser() {
        return new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 10L, true, LocalDate.now(), false, null, false);
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
