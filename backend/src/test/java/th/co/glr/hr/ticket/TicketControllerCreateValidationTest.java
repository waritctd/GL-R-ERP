package th.co.glr.hr.ticket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/** HTTP-level proof of what POST /api/tickets refuses at the boundary. Since the owner-approved short create form
 * (2026-11-16) an absent or blank entryChannel is no longer one of those things (it is stored UNSPECIFIED); a
 * negative estimatedValue is. Standalone MockMvc (same harness as AttendanceControllerTest): no Spring context, no
 * DB. */
class TicketControllerCreateValidationTest {

    private final TicketService ticketService = mock(TicketService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new TicketController(ticketService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

    private static String body(String channelFragment) {
        return "{\"title\":\"Test deal\",\"projectId\":77" + channelFragment + "}";
    }

    // The owner-approved short create form (2026-11-16) does not ask for a channel. These three used to pin "400 and
    // never reaches the service"; an absent/blank channel now reaches it and is stored UNSPECIFIED.
    @Test
    void createWithoutEntryChannelReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body("")))
            .andExpect(status().isOk());
        verify(ticketService).create(any(CreateTicketRequest.class), any(UserPrincipal.class));
    }

    @Test
    void createWithNullEntryChannelReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"entryChannel\":null")))
            .andExpect(status().isOk());
        verify(ticketService).create(any(CreateTicketRequest.class), any(UserPrincipal.class));
    }

    @Test
    void createWithBlankEntryChannelReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"entryChannel\":\"  \"")))
            .andExpect(status().isOk());
        verify(ticketService).create(any(CreateTicketRequest.class), any(UserPrincipal.class));
    }

    /** The boundary still validates what it can: a negative estimate never reaches the service. */
    @Test
    void createWithNegativeEstimatedValueIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"estimatedValue\":-5")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketService);
    }

    /** Control: proves the 400 above comes from field validation, not the harness. */
    @Test
    void createWithARealEntryChannelReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"entryChannel\":\"OWNER_DIRECT\"")))
            .andExpect(status().isOk());
        verify(ticketService).create(any(CreateTicketRequest.class), any(UserPrincipal.class));
    }

    /** The asymmetry is the design: the boundary lets UNSPECIFIED through; the service refuses it. */
    @Test
    void createWithUnspecifiedPassesTheBoundaryAndIsLeftToTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"entryChannel\":\"UNSPECIFIED\"")))
            .andExpect(status().isOk());
        verify(ticketService).create(any(CreateTicketRequest.class), any(UserPrincipal.class));
    }

    private MockHttpSession sales() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, "sales@glr.co.th", "sales", "sales", 10L, true, LocalDate.now(), false, null, false));
        return session;
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
