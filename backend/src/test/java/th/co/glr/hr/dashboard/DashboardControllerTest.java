package th.co.glr.hr.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

class DashboardControllerTest {
    private final DashboardService dashboardService = mock(DashboardService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DashboardController(dashboardService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/dashboard/summary"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Not authenticated"));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void returnsStableRoleAwareContractWithoutSensitiveFields() throws Exception {
        when(dashboardService.summary(any(UserPrincipal.class))).thenReturn(summary());

        mvc.perform(get("/api/dashboard/summary").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("hr"))
            .andExpect(jsonPath("$.employeeId").value(10))
            .andExpect(jsonPath("$.divisionId").value(5))
            .andExpect(jsonPath("$.manager").value(true))
            .andExpect(jsonPath("$.generatedAt").value("2026-07-05T09:00:00+07:00"))
            .andExpect(jsonPath("$.headcount.active").value(30))
            .andExpect(jsonPath("$.headcount.byDivision[0].divisionName").value("Sales"))
            .andExpect(jsonPath("$.pendingApprovals.total").value(10))
            .andExpect(jsonPath("$.attendance.todayPresent").value(20))
            .andExpect(jsonPath("$.tickets.totalOpen").value(8))
            .andExpect(jsonPath("$.notifications.unread").value(3))
            .andExpect(jsonPath("$.totalOpen").value(8))
            .andExpect(jsonPath("$.salary").doesNotExist())
            .andExpect(jsonPath("$.currentSalary").doesNotExist())
            .andExpect(jsonPath("$.salaryHistory").doesNotExist())
            .andExpect(jsonPath("$.bankAccount").doesNotExist())
            .andExpect(jsonPath("$.payroll").doesNotExist())
            .andExpect(jsonPath("$.sensitive").doesNotExist());
    }

    private DashboardSummaryDto summary() {
        TicketSummaryDto tickets = new TicketSummaryDto(
            "all", 1, 2, 3, 4, 5, 6, 0, 7, 8, 36, 8, 1, 2, 3);
        return DashboardSummaryDto.of(
            "hr",
            10L,
            5L,
            true,
            OffsetDateTime.parse("2026-07-05T09:00:00+07:00"),
            new HeadcountSummaryDto(
                "all",
                30L,
                2L,
                32L,
                List.of(new DivisionHeadcountDto(1L, "SA", "Sales", 10, 0, 10))
            ),
            PendingApprovalsSummaryDto.of("all", 1, 2, 3, 0, 4),
            new AttendanceSummaryDto("all", 20L, 3L, 1L, 44L, 100L, null, null, null, null),
            99L,
            tickets,
            new NotificationSummaryDto(3, 7, 10)
        );
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(10L, role + "@glr.co.th", role, role, 10L, true,
                LocalDate.of(2026, 1, 1), false, 5L, true));
        return session;
    }
}
