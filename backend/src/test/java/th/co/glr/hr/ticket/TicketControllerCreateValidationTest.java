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

/** HTTP-level proof that the boundary enforces "a new deal must state a real ช่องทางดีล": POST /api/tickets
 * with entryChannel absent or blank is a 400 and the service is never reached. Standalone MockMvc (same
 * harness as AttendanceControllerTest): no Spring context, no DB. */
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

    @Test
    void createWithoutEntryChannelIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body("")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketService);
    }

    @Test
    void createWithNullEntryChannelIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"entryChannel\":null")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketService);
    }

    @Test
    void createWithBlankEntryChannelIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/tickets").session(sales()).contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"entryChannel\":\"  \"")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketService);
    }

    /** Control: proves the 400s above come from entryChannel validation, not the harness. */
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
