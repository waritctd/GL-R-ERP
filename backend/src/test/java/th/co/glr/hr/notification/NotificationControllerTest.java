package th.co.glr.hr.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    private final NotificationRepository notifications = mock(NotificationRepository.class);
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
        when(notifications.markRead(10L, 7L)).thenReturn(1);

        mvc.perform(patch("/api/notifications/10/read").session(session(7L)))
            .andExpect(status().isNoContent());
    }

    @Test
    void markReadReturnsNotFoundWhenNotificationIsMissingOrNotOwned() throws Exception {
        when(notifications.markRead(10L, 7L)).thenReturn(0);

        mvc.perform(patch("/api/notifications/10/read").session(session(7L)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Notification not found"));
    }

    private MockHttpSession session(long employeeId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, "user@glr.co.th", "User", "employee", employeeId,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
