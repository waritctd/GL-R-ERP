package th.co.glr.hr.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

class NotificationControllerTest {
    private final NotificationService notifications = mock(NotificationService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new NotificationController(notifications, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void markReadRequiresAuthentication() throws Exception {
        mvc.perform(patch("/api/notifications/10/read"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(notifications);
    }

    @Test
    void markReadReturnsNoContentWhenOwnedNotificationUpdated() throws Exception {
        mvc.perform(patch("/api/notifications/10/read").session(session(7L)))
            .andExpect(status().isNoContent());

        verify(notifications).markRead(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any(UserPrincipal.class));
    }

    private MockHttpSession session(long employeeId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, "user@glr.co.th", "User", "employee", employeeId,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
