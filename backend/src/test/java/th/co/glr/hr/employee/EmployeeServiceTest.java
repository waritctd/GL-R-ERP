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
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
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

    @Test
    void logsAuditEventWhenHrViewsSensitiveDetail() {
        when(employees.findEmployeeById(5L, true)).thenReturn(Optional.of(employee(5L)));
        when(profileRequests.pendingCountByEmployee(5L)).thenReturn(0);
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        service.get(5L, new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 10L, true, LocalDate.now(), false, null, false));

        try {
            assertThat(appender.list).anyMatch(event ->
                event.getFormattedMessage().contains("VIEW_EMPLOYEE_DETAIL")
                    && event.getFormattedMessage().contains("targetEmployeeId=5"));
        } finally {
            detachAuditAppender(appender);
        }
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

    private ListAppender<ILoggingEvent> attachAuditAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("th.co.glr.hr.audit")).addAppender(appender);
        return appender;
    }

    private void detachAuditAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("th.co.glr.hr.audit")).detachAppender(appender);
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
