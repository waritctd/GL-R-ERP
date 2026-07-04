package th.co.glr.hr.employee;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.common.Page;
import th.co.glr.hr.common.PageRequest;

class EmployeeControllerTest {
    private final EmployeeService employeeService = mock(EmployeeService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new EmployeeController(employeeService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

    @Test
    void requiresAuthenticationForEmployeeDetails() throws Exception {
        mvc.perform(get("/api/employees/10"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Not authenticated"));

        verifyNoInteractions(employeeService);
    }

    @Test
    void forbidsEmployeeRoleFromListingAllEmployees() throws Exception {
        mvc.perform(get("/api/employees").session(sessionFor("employee")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Forbidden"));

        verifyNoInteractions(employeeService);
    }

    @Test
    void validatesEmployeePayloadBeforeCreate() throws Exception {
        mvc.perform(post("/api/employees")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameTh\":\"ทดสอบ\",\"email\":\"not-an-email\"}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(employeeService);
    }

    @Test
    void mapsActiveQueryParameterForAuthorizedListRequests() throws Exception {
        EmployeeFilter filter = new EmployeeFilter(null, null, null, null, Boolean.TRUE);
        when(employeeService.listPage(eq(filter), any(UserPrincipal.class), any(PageRequest.class)))
            .thenReturn(new Page<>(List.of(), 0, PageRequest.DEFAULT_SIZE, 0));

        mvc.perform(get("/api/employees?active=true").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employees").isArray())
            .andExpect(jsonPath("$.total").value(0));

        verify(employeeService).listPage(eq(filter), any(UserPrincipal.class), any(PageRequest.class));
    }

    @Test
    void selfDetailResponseOmitsPayrollAndSensitiveFields() throws Exception {
        when(employeeService.get(eq(10L), any(UserPrincipal.class)))
            .thenReturn(employee(10L).withoutSensitiveSelfServiceFields());

        mvc.perform(get("/api/employees/10").session(sessionFor("employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employee.id").value(10))
            .andExpect(jsonPath("$.employee.email").value("employee10@glr.co.th"))
            .andExpect(jsonPath("$.employee.salary").doesNotExist())
            .andExpect(jsonPath("$.employee.salaryHistory").doesNotExist())
            .andExpect(jsonPath("$.employee.bank").doesNotExist())
            .andExpect(jsonPath("$.employee.bankAccount").doesNotExist())
            .andExpect(jsonPath("$.employee.payType").doesNotExist())
            .andExpect(jsonPath("$.employee.sensitive").doesNotExist());
    }

    @Test
    void hrDetailResponseIncludesHrFields() throws Exception {
        when(employeeService.get(eq(10L), any(UserPrincipal.class))).thenReturn(employee(10L));

        mvc.perform(get("/api/employees/10").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employee.salary").value(25000.0))
            .andExpect(jsonPath("$.employee.salaryHistory[0].newSalary").value(25000.0))
            .andExpect(jsonPath("$.employee.bank").value("ธนาคารทดสอบ"))
            .andExpect(jsonPath("$.employee.bankAccount").value("123-4-56789-0"))
            .andExpect(jsonPath("$.employee.payType").value("รายเดือน"))
            .andExpect(jsonPath("$.employee.sensitive.nationalId").value("1101700000010"));
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 10L, true, LocalDate.now(), false, null, false));
        return session;
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
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
            new SensitiveDto("1101700000010", "TAX-10", "SSO-10", "โรงพยาบาลทดสอบ", "PF-10"),
            0
        );
    }
}
